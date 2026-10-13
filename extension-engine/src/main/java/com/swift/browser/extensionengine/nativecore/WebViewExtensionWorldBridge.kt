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
import com.swift.browser.extensionengine.ContentScriptWorld
import com.swift.browser.extensionengine.ExtensionEngineApi
import com.swift.browser.extensionengine.ExtensionEngineImpl
import com.swift.browser.extensionengine.ScriptExecutionTarget
import com.swift.browser.extensionengine.WebNavigationStateMachine
import org.json.JSONArray
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.util.UUID
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * Isolated-world extension transport. It stores only extension-local reply proxies keyed by the
 * canonical provider frame token. frameId/documentId ownership remains in WebNavigationStateMachine.
 */
object WebViewExtensionWorldBridge {
    private const val TAG = "WebViewExtensionWorldBridge"
    private const val RESPONSE_TIMEOUT_MS = 15_000L
    private const val WORLD_PREFIX = "isolated_world_"
    private const val BRIDGE_PREFIX = "__orionExecBridge_"
    private const val CORE_SECRET_PREFIX = "swift-core-"
    private const val CANONICAL_DOCUMENT_ATTR = "data-swift-browser-canonical-document-id"
    private const val CANONICAL_FRAME_ATTR = "data-swift-browser-frame-token"

    private data class PendingRequest(
        val expected: Int,
        val results: MutableList<JSONObject>,
        val callback: (JSONArray?, String?) -> Unit,
        val timeout: Runnable
    )

    private data class ExtensionState(
        val extensionId: String,
        val listenerName: String,
        val world: JavaScriptExecutionWorld,
        val handler: ScriptHandler?,
        val secret: String,
        // transport only: providerFrameToken -> extension-world reply proxy
        val transports: MutableMap<String, JavaScriptReplyProxy> = ConcurrentHashMap(),
        val pending: MutableMap<String, PendingRequest> = ConcurrentHashMap()
    )

    private data class ViewState(
        val tabId: String,
        val webView: WeakReference<WebView>,
        val extensions: MutableMap<String, ExtensionState> = ConcurrentHashMap()
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val views = WeakHashMap<WebView, ViewState>()
    private val lock = Any()

    fun supportsRealWorldTransport(): Boolean = runCatching {
        WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.JS_INJECTION_IN_FRAME_AND_WORLD)
    }.getOrDefault(false)

    private fun engine(webView: WebView): ExtensionEngineImpl? = runCatching {
        ExtensionEngineApi.getInstance(webView.context.applicationContext).extensionManager.engine as? ExtensionEngineImpl
    }.getOrNull()

    private fun stateMachine(webView: WebView): WebNavigationStateMachine? = engine(webView)?.webNavigationAdapter?.stateMachine

    fun prepareExtensionWorld(webView: WebView, tabId: String, extensionId: String) {
        if (!supportsRealWorldTransport() || extensionId.isBlank()) return
        WebViewCanonicalFrameBridge.prepare(webView, tabId)
        synchronized(lock) {
            val state = views.getOrPut(webView) { ViewState(tabId, WeakReference(webView)) }
            if (state.extensions.containsKey(extensionId)) return
        }
        val safe = extensionId.replace(Regex("[^A-Za-z0-9_]"), "_").take(48)
        val listenerName = BRIDGE_PREFIX + safe
        val secret = CORE_SECRET_PREFIX + UUID.randomUUID().toString().replace("-", "")
        val nativeWorldName = NativeExtensionCoreBridge.worldName(extensionId) ?: (WORLD_PREFIX + extensionId)
        val world = WebViewCompat.getExecutionWorld(webView, nativeWorldName)
        val listener = object : WebViewCompat.WebMessageListener {
            override fun onPostMessage(view: WebView, message: WebMessageCompat, sourceOrigin: Uri, isMainFrame: Boolean, replyProxy: JavaScriptReplyProxy) {
                handleMessage(view, tabId, extensionId, message.data.orEmpty(), sourceOrigin.toString(), isMainFrame, replyProxy)
            }
        }
        try {
            WebViewCompat.addWebMessageListener(webView, listenerName, setOf("*"), world, listener)
            val handler = WebViewCompat.addJavaScriptOnEvent(webView, buildFrameBootstrap(listenerName, secret), WebViewCompat.INJECTION_EVENT_DOCUMENT_START, setOf("*"), world)
            synchronized(lock) {
                views.getOrPut(webView) { ViewState(tabId, WeakReference(webView)) }.extensions[extensionId] = ExtensionState(extensionId, listenerName, world, handler, secret)
            }
            NativeExtensionCoreBridge.ensureLoaded()
        } catch (t: Throwable) {
            Log.w(TAG, "Unable to install isolated-world bridge for $extensionId", t)
        }
    }

    private fun buildFrameBootstrap(listenerName: String, secret: String): String = """
        (function(){
            'use strict';
            const bridge=window[${JSONObject.quote(listenerName)}]; if(!bridge) return;
            const secret=${JSONObject.quote(secret)};
            const frameToken=(document.documentElement && document.documentElement.getAttribute(${JSONObject.quote(CANONICAL_FRAME_ATTR)})) || '';
            const documentId=(document.documentElement && document.documentElement.getAttribute(${JSONObject.quote(CANONICAL_DOCUMENT_ATTR)})) || '';
            if(!frameToken || !documentId) return;
            const send=(type,extra)=>{ try{ bridge.postMessage(JSON.stringify(Object.assign({type:type,secret:secret,frameToken:frameToken,documentId:documentId,url:location.href},extra||{}))); }catch(_){} };
            bridge.onmessage=event=>{
                const raw=event&&event.data?String(event.data):''; let cmd; try{cmd=JSON.parse(raw);}catch(_){return;}
                if(!cmd || cmd.secret!==secret) return;
                if(cmd.type!=='execute') return;
                (async()=>{
                    let result=null,error=null;
                    try{
                        const args=Array.isArray(cmd.args)?cmd.args:[]; let value;
                        if(cmd.isFunction){ const fn=eval('('+String(cmd.code||'')+')'); value=fn.apply(globalThis,args); }
                        else { value=(0,eval)(String(cmd.code||'')); }
                        value=await Promise.resolve(value);
                        const seen=new WeakSet();
                        const serialize=v=>{
                            if(v===undefined||v===null)return null; if(typeof v==='number')return Number.isFinite(v)?v:null;
                            if(typeof v==='boolean'||typeof v==='string')return v; if(typeof v==='bigint')return v.toString();
                            if(v instanceof Date)return v.toISOString(); if(v instanceof Error)return {name:v.name,message:v.message,stack:v.stack||''};
                            if(typeof v==='object'){ if(seen.has(v))return '[Circular]'; seen.add(v); if(Array.isArray(v))return v.map(serialize); const o={}; for(const k of Object.keys(v)){try{o[k]=serialize(v[k]);}catch(_){} } return o; }
                            return String(v);
                        };
                        result=serialize(value);
                    }catch(e){ error=e&& (e.stack||e.message)?String(e.stack||e.message):String(e); }
                    send('execute-result',{requestId:String(cmd.requestId||''),result:result,error:error});
                })();
            };
            send('frame-ready');
        })();
    """.trimIndent()

    private fun handleMessage(webView: WebView, tabId: String, extensionId: String, payload: String, sourceOrigin: String, isMainFrame: Boolean, replyProxy: JavaScriptReplyProxy) {
        val obj = runCatching { JSONObject(payload) }.getOrNull() ?: return
        val state = synchronized(lock) { views[webView]?.extensions?.get(extensionId) } ?: return
        if (obj.optString("secret") != state.secret) return
        when (obj.optString("type")) {
            "api" -> {
                val callbackId = obj.optString("callbackId", "")
                (webView.tag as? com.swift.browser.extensionengine.RuntimeBridge)?.postWebMessage(payload, callbackId, replyProxy)
            }
            "frame-ready" -> {
                val token = obj.optString("frameToken").trim(); val doc = obj.optString("documentId").trim()
                if (token.isBlank() || doc.isBlank()) return
                val machine = stateMachine(webView) ?: return
                val frame = machine.getFrameIdentityByProviderToken(tabId, token) ?: return
                if (frame.documentId != doc || frame.destroyed) return
                synchronized(lock) { state.transports[token] = replyProxy }
            }
            "document-start", "document-end" -> Unit
            "frame-destroyed" -> {
                val token = obj.optString("frameToken").trim(); val doc = obj.optString("documentId").trim()
                if (token.isBlank() || doc.isBlank()) return
                val machine = stateMachine(webView) ?: return
                val frame = machine.getFrameIdentityByProviderToken(tabId, token) ?: return
                if (frame.documentId == doc) synchronized(lock) { state.transports.remove(token) }
            }
            "execute-result" -> handleExecutionResult(state, webView, tabId, obj)
        }
    }

    private fun handleExecutionResult(state: ExtensionState, webView: WebView, tabId: String, obj: JSONObject) {
        val requestId=obj.optString("requestId").trim(); val frameId=obj.optInt("frameId",-1); val doc=obj.optString("documentId").trim()
        if(requestId.isBlank()||frameId<0||doc.isBlank())return
        val machine=stateMachine(webView)?:return
        if(!machine.isCurrentDocument(tabId,frameId,doc))return
        val pending=state.pending[requestId]?:return
        synchronized(pending.results){if(pending.results.any{it.optInt("frameId",-1)==frameId&&it.optString("documentId")==doc})return;pending.results+=obj}
        if(pending.results.size<pending.expected)return
        if(state.pending.remove(requestId)!=null){mainHandler.removeCallbacks(pending.timeout);val arr=JSONArray();synchronized(pending.results){pending.results.sortedBy{it.optInt("frameId",Int.MAX_VALUE)}.forEach(arr::put)};mainHandler.post{pending.callback(arr,null)}}
    }

    fun execute(webView: WebView, tabId: String, extensionId: String, target: ScriptExecutionTarget, code: String, isFunction: Boolean, args: JSONArray, callback: (JSONArray?, String?) -> Unit) {
        if(target.world==ContentScriptWorld.MAIN){ WebViewCanonicalFrameBridge.executeMain(webView,target,code,isFunction,args,callback); return }
        prepareExtensionWorld(webView,tabId,extensionId)
        val state=synchronized(lock){views[webView]?.extensions?.get(extensionId)}?:run{callback(null,"SCRIPTING_ISOLATED_WORLD_UNAVAILABLE");return}
        val machine=stateMachine(webView)?:run{callback(null,"SCRIPTING_FRAME_REGISTRY_UNAVAILABLE");return}
        val resolution=machine.resolveTargets(tabId,target.frameIds,target.documentIds,target.allFrames)
        if(resolution.status!="SUCCESS"){callback(null,resolution.status);return}
        val selected=resolution.frames.mapNotNull{frame->val token=frame.providerToken?:return@mapNotNull null;val proxy=state.transports[token]?:return@mapNotNull null;Triple(frame,token,proxy)}.sortedBy{it.first.frameId}
        if(selected.size!=resolution.frames.size){callback(null,"SCRIPTING_FRAME_NOT_READY");return}
        val requestId="req-${UUID.randomUUID()}"
        val timeout=Runnable{if(state.pending.remove(requestId)!=null)callback(null,"SCRIPTING_EXECUTION_TIMEOUT")}
        state.pending[requestId]=PendingRequest(selected.size,mutableListOf(),callback,timeout);mainHandler.postDelayed(timeout,RESPONSE_TIMEOUT_MS)
        val command=JSONObject().apply{put("type","execute");put("secret",state.secret);put("requestId",requestId);put("isFunction",isFunction);put("code",code);put("args",args)}.toString()
        selected.forEach{runCatching{it.third.postMessage(command)}.onFailure{Log.w(TAG,"Isolated frame transport delivery failed",it)}}
    }

    fun clear(webView: WebView) {
        val state=synchronized(lock){views.remove(webView)}?:return
        state.extensions.values.forEach{ext->runCatching{WebViewCompat.removeWebMessageListener(webView,ext.listenerName)};runCatching{ext.handler?.remove()};ext.pending.values.forEach{mainHandler.removeCallbacks(it.timeout)} }
    }

    fun clearForTab(tabId:String){synchronized(lock){views.entries.filter{it.value.tabId==tabId}.map{it.key}}.forEach(::clear)}

    fun onNavigation(webView:WebView,tabId:String,url:String){
        val state=synchronized(lock){views[webView]}?:return
        val machine=stateMachine(webView)?:return
        val live=machine.getFrameIdentities(tabId).mapNotNull{it.providerToken}.toSet()
        synchronized(lock){state.extensions.values.forEach{ext->ext.transports.keys.filter{it !in live}.forEach(ext.transports::remove)}}
    }
}
