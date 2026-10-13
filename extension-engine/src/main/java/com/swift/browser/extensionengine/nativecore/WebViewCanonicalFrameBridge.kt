package com.swift.browser.extensionengine.nativecore

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebView
import androidx.webkit.JavaScriptExecutionWorld
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.ScriptHandler
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.swift.browser.extensionengine.ExtensionEngineApi
import com.swift.browser.extensionengine.ExtensionEngineImpl
import com.swift.browser.extensionengine.FrameIdentity
import com.swift.browser.extensionengine.ScriptExecutionTarget
import com.swift.browser.extensionengine.WebNavigationStateMachine
import org.json.JSONArray
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.util.UUID
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * Canonical WebView frame transport only. Browser frame/document identity lives exclusively in
 * WebNavigationStateMachine; this class stores reply proxies and transport tokens, not a second
 * identity registry.
 */
object WebViewCanonicalFrameBridge {
    private const val TAG = "WebViewCanonicalFrameBridge"
    private const val LISTENER_NAME = "__orionCanonicalFrameBridge"
    private const val COMMAND_TIMEOUT_MS = 20_000L
    private const val CHILD_HANDSHAKE_TIMEOUT_MS = 150L

    data class FrameSnapshot(
        val tabId: String,
        val frameId: Int,
        val parentFrameId: Int,
        val documentId: String,
        val url: String,
        val isMainFrame: Boolean,
        val replyProxy: JavaScriptReplyProxy
    )

    private data class Pending(
        val expected: Int,
        val results: MutableList<JSONObject>,
        val callback: (JSONArray?, String?) -> Unit,
        val timeout: Runnable
    )

    private data class ViewState(
        val tabId: String,
        val webView: WeakReference<WebView>,
        // Transport handles only. Canonical frameId/documentId are resolved from the state machine.
        val transports: MutableMap<String, JavaScriptReplyProxy> = ConcurrentHashMap(),
        val documentTransportTokens: MutableMap<String, String> = ConcurrentHashMap(),
        val handlers: MutableList<ScriptHandler> = mutableListOf(),
        val pending: MutableMap<String, Pending> = ConcurrentHashMap(),
        var prepared: Boolean = false,
        var inventoryRunnable: Runnable? = null
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()
    private val views = WeakHashMap<WebView, ViewState>()

    fun isSupported(): Boolean = runCatching {
        WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.JS_INJECTION_IN_FRAME_AND_WORLD)
    }.getOrDefault(false)

    private fun engine(webView: WebView): ExtensionEngineImpl? = runCatching {
        ExtensionEngineApi.getInstance(webView.context.applicationContext).extensionManager.engine as? ExtensionEngineImpl
    }.getOrNull()

    private fun stateMachine(webView: WebView): WebNavigationStateMachine? = engine(webView)?.webNavigationAdapter?.stateMachine

    fun prepare(webView: WebView, tabId: String) {
        if (!isSupported() || tabId.isBlank()) return
        synchronized(lock) {
            val existing = views[webView]
            if (existing?.prepared == true) return
            views[webView] = existing ?: ViewState(tabId, WeakReference(webView))
        }

        val world = JavaScriptExecutionWorld(JavaScriptExecutionWorld.PAGE_WORLD_NAME, webView)
        val listener = object : WebViewCompat.WebMessageListener {
            override fun onPostMessage(
                view: WebView,
                message: WebMessageCompat,
                sourceOrigin: Uri,
                isMainFrame: Boolean,
                replyProxy: JavaScriptReplyProxy
            ) {
                handleMessage(view, tabId, message.data.orEmpty(), sourceOrigin.toString(), isMainFrame, replyProxy)
            }
        }

        try {
            WebViewCompat.addWebMessageListener(webView, LISTENER_NAME, setOf("*"), world, listener)
            val handler = WebViewCompat.addJavaScriptOnEvent(
                webView,
                buildBootstrap(),
                WebViewCompat.INJECTION_EVENT_DOCUMENT_START,
                setOf("*"),
                world
            )
            synchronized(lock) {
                val state = views.getOrPut(webView) { ViewState(tabId, WeakReference(webView)) }
                state.handlers += handler
                state.prepared = true
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Unable to prepare canonical frame transport", t)
            synchronized(lock) { views.remove(webView) }
        }
    }

    private fun buildBootstrap(): String = """
        (function() {
            'use strict';
            const bridge = window[${JSONObject.quote(LISTENER_NAME)}];
            if (!bridge) return;
            const transport = (() => {
                try { return 'swift-doc-transport-' + crypto.randomUUID(); }
                catch (_) { return 'swift-doc-transport-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2); }
            })();
            let frameToken = '';
            let parentFrameToken = '';
            const send = (type, extra) => {
                try { bridge.postMessage(JSON.stringify(Object.assign({ type:type, frameToken:frameToken, parentFrameToken:parentFrameToken, documentTransportToken:transport, url:location.href }, extra || {}))); } catch (_) {}
            };
            const childTokenFor = sourceWindow => {
                try {
                    const frames = document.querySelectorAll('iframe,frame');
                    for (let i=0;i<frames.length;i++) {
                        const el=frames[i];
                        if (el.contentWindow !== sourceWindow) continue;
                        let token=el.getAttribute('data-swift-browser-frame-token') || '';
                        if (!token) {
                            try { token='swift-frame-' + crypto.randomUUID(); }
                            catch (_) { token='swift-frame-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2); }
                            el.setAttribute('data-swift-browser-frame-token', token);
                        }
                        return token;
                    }
                } catch (_) {}
                return '';
            };
            const sendHello = () => { if (frameToken) send('hello'); };
            window.addEventListener('message', event => {
                try {
                    const d=event && event.data;
                    if (!d || d.__swiftFrameIdentity !== true) return;
                    if (d.type === 'discover-parent' && event.source && event.source !== window) {
                        const childToken=childTokenFor(event.source);
                        event.source.postMessage({
                            __swiftFrameIdentity:true,
                            type:'parent-response',
                            childTransportToken:d.childTransportToken || '',
                            parentFrameToken:frameToken,
                            childFrameToken:childToken
                        }, '*');
                    } else if (d.type === 'parent-response' && d.childTransportToken === transport && typeof d.parentFrameToken === 'string') {
                        parentFrameToken=d.parentFrameToken;
                        frameToken=String(d.childFrameToken || '');
                        if (frameToken) { try{ if(document.documentElement) document.documentElement.setAttribute('data-swift-browser-frame-token',frameToken); }catch(_){}; sendHello(); }
                    }
                } catch (_) {}
            }, false);
            bridge.onmessage = event => {
                const raw=event && event.data ? String(event.data) : '';
                let command; try { command=JSON.parse(raw); } catch (_) { return; }
                if (!command) return;
                if (command.type==='hello-ack') { try { if (document.documentElement) { document.documentElement.setAttribute('data-swift-browser-canonical-document-id', String(command.documentId || '')); document.documentElement.setAttribute('data-swift-browser-frame-token', String(frameToken || '')); } } catch (_) {} return; }
                if (command.type!=='execute-main' && command.type!=='insert-css' && command.type!=='remove-css') return;
                (async()=>{
                    let result=null, error=null;
                    try {
                        if (command.type==='insert-css' || command.type==='remove-css') {
                            const key=String(command.cssKey || ''); if (!key) throw new Error('CSS_KEY_REQUIRED');
                            const attr='data-swift-extension-css-key';
                            let affected=0;
                            document.querySelectorAll('style['+attr+']').forEach(node=>{ if(node.getAttribute(attr)===key){ if(command.type==='remove-css') node.remove(); affected++; }});
                            if(command.type==='insert-css'){ const style=document.createElement('style'); style.setAttribute(attr,key); style.textContent=String(command.css||''); (document.head||document.documentElement||document.body).appendChild(style); affected=1; }
                            result=affected;
                        } else {
                            const args=Array.isArray(command.args)?command.args:[];
                            let value;
                            if(command.isFunction){ const fn=eval('('+String(command.code||'')+')'); value=fn.apply(globalThis,args); }
                            else { value=(0,eval)(String(command.code||'')); }
                            result=await Promise.resolve(value);
                        }
                    } catch(e) { error=e && (e.stack || e.message) ? String(e.stack || e.message) : String(e); }
                    send(command.type==='execute-main'?'execute-result':'css-result', { requestId:String(command.requestId||''), frameId:command.frameId, documentId:String(command.documentId||''), result:result, error:error });
                })();
                }
            };
            window.addEventListener('pagehide', ()=>send('destroy'), {once:true});
            if(window.parent && window.parent!==window){
                let tries=0;
                const discover=()=>{ tries++; try{ window.parent.postMessage({__swiftFrameIdentity:true,type:'discover-parent',childTransportToken:transport},'*'); }catch(_){} if(tries<12) setTimeout(discover, ${CHILD_HANDSHAKE_TIMEOUT_MS}); };
                discover();
            } else { frameToken='swift-top-frame'; try{ if(document.documentElement) document.documentElement.setAttribute('data-swift-browser-frame-token',frameToken); }catch(_){}; sendHello(); }
        })();
    """.trimIndent()

    private fun handleMessage(
        webView: WebView,
        tabId: String,
        payload: String,
        sourceOrigin: String,
        isMainFrame: Boolean,
        replyProxy: JavaScriptReplyProxy
    ) {
        val obj = runCatching { JSONObject(payload) }.getOrNull() ?: return
        val state = synchronized(lock) { views[webView] } ?: return
        when (obj.optString("type")) {
            "hello" -> {
                val frameToken = obj.optString("frameToken").trim()
                val parentFrameToken = obj.optString("parentFrameToken").trim().ifBlank { null }
                val transportToken = obj.optString("documentTransportToken").trim()
                if (frameToken.isBlank() || transportToken.isBlank()) return
                val machine = stateMachine(webView) ?: return
                val frame = runCatching {
                    machine.registerProviderFrame(
                        tabId = tabId,
                        frameToken = frameToken,
                        parentFrameToken = parentFrameToken,
                        url = obj.optString("url", sourceOrigin),
                        isMainFrame = isMainFrame,
                        privateSession = false,
                        documentTransportToken = transportToken
                    )
                }.getOrNull() ?: return
                synchronized(lock) {
                    state.transports[frameToken] = replyProxy
                    state.documentTransportTokens[frame.documentId.orEmpty()] = transportToken
                }
                machine.markDocumentIdentityAvailable(tabId)
                replyProxy.postMessage(JSONObject().apply {
                    put("type", "hello-ack"); put("frameId", frame.frameId); put("parentFrameId", frame.parentFrameId)
                    put("documentId", frame.documentId ?: JSONObject.NULL); put("navigationId", frame.navigationId)
                }.toString())
                scheduleInventoryComplete(state)
            }
            "execute-result", "css-result" -> handleExecutionResult(state, webView, tabId, obj)
            "destroy" -> {
                val frameToken = obj.optString("frameToken").trim()
                val transportToken = obj.optString("documentTransportToken").trim()
                val machine = stateMachine(webView) ?: return
                val current = machine.getFrameIdentityByProviderToken(tabId, frameToken) ?: return
                if (current.documentTransportToken != transportToken || current.destroyed) return
                machine.removeFrameByProviderToken(tabId, frameToken, transportToken)
                synchronized(lock) { state.transports.remove(frameToken) }
                scheduleInventoryComplete(state)
            }
        }
    }

    private fun handleExecutionResult(state: ViewState, webView: WebView, tabId: String, obj: JSONObject) {
        val requestId = obj.optString("requestId").trim()
        if (requestId.isBlank()) return
        val frameId = obj.optInt("frameId", -1)
        val documentId = obj.optString("documentId", "").trim()
        if (frameId < 0 || documentId.isBlank()) return
        val machine = stateMachine(webView) ?: return
        if (!machine.isCurrentDocument(tabId, frameId, documentId)) return
        val pending = state.pending[requestId] ?: return
        synchronized(pending.results) {
            if (pending.results.any { it.optInt("frameId", -1) == frameId && it.optString("documentId") == documentId }) return
            pending.results += obj
        }
        if (pending.results.size < pending.expected) return
        if (state.pending.remove(requestId) != null) {
            mainHandler.removeCallbacks(pending.timeout)
            val result = JSONArray()
            synchronized(pending.results) { pending.results.sortedBy { it.optInt("frameId", Int.MAX_VALUE) }.forEach(result::put) }
            mainHandler.post { pending.callback(result, null) }
        }
    }

    private fun scheduleInventoryComplete(state: ViewState) {
        state.inventoryRunnable?.let(mainHandler::removeCallbacks)
        state.inventoryRunnable = Runnable {
            val machine = state.webView.get()?.let(::stateMachine) ?: return@Runnable
            machine.markFrameInventoryCompleteForProvider(state.tabId)
        }.also { mainHandler.postDelayed(it, 350L) }
    }

    fun resolve(webView: WebView, documentId: String): FrameSnapshot? {
        val machine = stateMachine(webView) ?: return null
        val frame = machine.getFrameIdentityByDocumentId(machineTab(webView), documentId) ?: return null
        val token = frame.providerToken ?: return null
        val proxy = synchronized(lock) { views[webView]?.transports?.get(token) } ?: return null
        return FrameSnapshot(frame.tabId, frame.frameId, frame.parentFrameId, frame.documentId ?: return null, frame.url, frame.frameId == 0, proxy)
    }

    private fun machineTab(webView: WebView): String = synchronized(lock) { views[webView]?.tabId.orEmpty() }

    fun frames(webView: WebView): List<FrameSnapshot> {
        val machine = stateMachine(webView) ?: return emptyList()
        val state = synchronized(lock) { views[webView] } ?: return emptyList()
        return machine.getFrameIdentities(state.tabId).mapNotNull { frame ->
            val token = frame.providerToken ?: return@mapNotNull null
            val proxy = state.transports[token] ?: return@mapNotNull null
            FrameSnapshot(frame.tabId, frame.frameId, frame.parentFrameId, frame.documentId ?: return@mapNotNull null, frame.url, frame.frameId == 0, proxy)
        }.sortedBy { it.frameId }
    }

    private fun selectFrames(webView: WebView, target: ScriptExecutionTarget): List<FrameSnapshot> {
        val machine = stateMachine(webView) ?: return emptyList()
        val resolution = machine.resolveTargets(target.tabId, target.frameIds, target.documentIds, target.allFrames)
        if (resolution.status != "SUCCESS") return emptyList()
        val state = synchronized(lock) { views[webView] } ?: return emptyList()
        return resolution.frames.mapNotNull { frame ->
            val token = frame.providerToken ?: return@mapNotNull null
            val proxy = state.transports[token] ?: return@mapNotNull null
            FrameSnapshot(frame.tabId, frame.frameId, frame.parentFrameId, frame.documentId ?: return@mapNotNull null, frame.url, frame.frameId == 0, proxy)
        }.sortedBy { it.frameId }
    }

    private fun executeCommand(
        webView: WebView,
        target: ScriptExecutionTarget,
        commandType: String,
        extra: JSONObject,
        callback: (JSONArray?, String?) -> Unit
    ) {
        if (!isSupported()) { callback(null, "SCRIPTING_FRAME_PROVIDER_UNAVAILABLE"); return }
        val state = synchronized(lock) { views[webView] } ?: run { callback(null, "SCRIPTING_FRAME_PROVIDER_UNAVAILABLE"); return }
        val selected = selectFrames(webView, target)
        if (selected.isEmpty()) { callback(null, "SCRIPTING_FRAME_TARGET_NOT_READY"); return }
        val requestId = "frame-${UUID.randomUUID()}"
        val timeout = Runnable {
            if (state.pending.remove(requestId) != null) callback(null, "SCRIPTING_EXECUTION_TIMEOUT")
        }
        state.pending[requestId] = Pending(selected.size, mutableListOf(), callback, timeout)
        mainHandler.postDelayed(timeout, COMMAND_TIMEOUT_MS)
        selected.forEach { frame ->
            val command = JSONObject(extra.toString()).apply {
                put("type", commandType); put("requestId", requestId); put("frameId", frame.frameId); put("documentId", frame.documentId)
            }.toString()
            runCatching { frame.replyProxy.postMessage(command) }.onFailure { Log.w(TAG, "Frame command delivery failed", it) }
        }
    }

    fun executeMain(webView: WebView, target: ScriptExecutionTarget, code: String, isFunction: Boolean, args: JSONArray, callback: (JSONArray?, String?) -> Unit) =
        executeCommand(webView, target, "execute-main", JSONObject().apply { put("code", code); put("isFunction", isFunction); put("args", args) }, callback)

    fun executeCss(webView: WebView, target: ScriptExecutionTarget, cssKey: String, css: String, callback: (JSONArray?, String?) -> Unit) =
        executeCommand(webView, target, "insert-css", JSONObject().apply { put("cssKey", cssKey); put("css", css) }, callback)

    fun removeCss(webView: WebView, target: ScriptExecutionTarget, cssKey: String, callback: (JSONArray?, String?) -> Unit) =
        executeCommand(webView, target, "remove-css", JSONObject().apply { put("cssKey", cssKey) }, callback)

    fun removeDocument(webView: WebView, tabId: String, documentId: String): Boolean {
        val machine = stateMachine(webView) ?: return false
        val frame = machine.getFrameIdentityByDocumentId(tabId, documentId) ?: return false
        val token = frame.providerToken ?: return false
        if (!machine.removeFrameByProviderToken(tabId, token, frame.documentTransportToken)) return false
        synchronized(lock) { views[webView]?.transports?.remove(token) }
        return true
    }

    fun clear(webView: WebView) {
        val state = synchronized(lock) { views.remove(webView) } ?: return
        state.handlers.forEach { runCatching { it.remove() } }
        state.pending.values.forEach { mainHandler.removeCallbacks(it.timeout) }
        state.inventoryRunnable?.let(mainHandler::removeCallbacks)
        runCatching { WebViewCompat.removeWebMessageListener(webView, LISTENER_NAME) }
        stateMachine(webView)?.clearTab(state.tabId)
    }

    fun clearForTab(tabId: String) {
        val targets = synchronized(lock) { views.entries.filter { it.value.tabId == tabId }.map { it.key } }
        targets.forEach(::clear)
    }

    fun onNavigation(webView: WebView, tabId: String, url: String) {
        val state = synchronized(lock) { views[webView] } ?: return
        val machine = stateMachine(webView) ?: return
        val liveTokens = machine.getFrameIdentities(tabId).mapNotNull { it.providerToken }.toSet()
        synchronized(lock) { state.transports.keys.filter { it !in liveTokens }.forEach(state.transports::remove) }
    }
}
