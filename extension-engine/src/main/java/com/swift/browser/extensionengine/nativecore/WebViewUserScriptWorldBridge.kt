package com.swift.browser.extensionengine.nativecore

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import androidx.webkit.JavaScriptExecutionWorld
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.ScriptHandler
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.swift.browser.extensionengine.ExtensionContextType
import com.swift.browser.extensionengine.ExtensionEngineApi
import com.swift.browser.extensionengine.ExtensionEngineImpl
import com.swift.browser.extensionengine.ExtensionUserScriptsAdapter
import com.swift.browser.extensionengine.WebNavigationStateMachine
import org.json.JSONArray
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.util.UUID
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

/** AndroidX WebView USER_SCRIPT/MAIN world transport; frame/document ownership is canonical elsewhere. */
object WebViewUserScriptWorldBridge {
    private const val LISTENER_PREFIX = "__orionUserScriptBridge_"
    private const val EXEC_BRIDGE_PREFIX = "__orionExtExecBridge_"
    private const val WORLD_PREFIX = "swift-user-script-"
    private const val RESPONSE_TIMEOUT_MS = 20_000L
    private const val CANONICAL_DOCUMENT_ATTR = "data-swift-browser-canonical-document-id"
    private const val CANONICAL_FRAME_ATTR = "data-swift-browser-frame-token"

    private data class PendingRequest(val expected: Int, val results: MutableList<JSONObject>, val callback: (JSONArray?, String?) -> Unit, val timeout: Runnable)
    private data class WorldState(
        val extensionId: String, val tabId: String, val worldId: String, val worldName: String,
        val listenerName: String, val world: JavaScriptExecutionWorld, var messaging: Boolean, var csp: String?,
        val handlers: MutableList<ScriptHandler> = mutableListOf(),
        // Only transport handles: provider frame token -> world reply proxy.
        val transports: MutableMap<String, JavaScriptReplyProxy> = ConcurrentHashMap(),
        val pending: MutableMap<String, PendingRequest> = ConcurrentHashMap()
    )
    private data class ViewState(val tabId: String, val webView: WeakReference<WebView>, val worlds: MutableMap<String, WorldState> = ConcurrentHashMap())

    private val lock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val states = WeakHashMap<WebView, ViewState>()

    fun isScriptInjectionSupported(): Boolean = runCatching { WebViewFeature.isFeatureSupported(WebViewFeature.JS_INJECTION_IN_FRAME_AND_WORLD) }.getOrDefault(false)
    fun isSupported(): Boolean = isScriptInjectionSupported() && runCatching { WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) }.getOrDefault(false)

    private fun stateMachine(webView: WebView): WebNavigationStateMachine? = runCatching {
        (ExtensionEngineApi.getInstance(webView.context.applicationContext).extensionManager.engine as? ExtensionEngineImpl)?.webNavigationAdapter?.stateMachine
    }.getOrNull()

    fun prepareWorld(webView: WebView, tabId: String, extensionId: String, worldId: String, messaging: Boolean, isPrivate: Boolean, csp: String? = null) {
        ensureWorld(webView, tabId, extensionId, worldId, messaging, isPrivate, csp)
    }

    fun install(webView: WebView, tabId: String, extensionId: String, script: ExtensionUserScriptsAdapter.RegisteredUserScript, code: String, originRules: Set<String>, urlGuard: String, isPrivate: Boolean): ScriptHandler? {
        if (!isSupported() || code.isBlank() || extensionId.isBlank()) return null
        val world = script.world.trim().uppercase()
        if (world != "USER_SCRIPT" && world != "MAIN") return null
        return if (world == "MAIN") installMainScript(webView, script, code, originRules, urlGuard) else {
            val ws = ensureWorld(webView, tabId, extensionId, script.worldId, false, isPrivate) ?: return null
            val phase = phaseFor(script.runAt) ?: return null
            val runCode = if (script.runAt.equals("document_idle", true)) """
                (function(){ const run=()=>{ $code }; if(typeof requestIdleCallback==='function') requestIdleCallback(run,{timeout:250}); else setTimeout(run,0); })();
            """.trimIndent() else code
            addScriptHandler(webView, ws, buildGuardedUserScript(script.id, urlGuard, runCode), phase, originRules)
        }
    }

    private fun phaseFor(runAt: String): Int? = when (runAt.lowercase()) {
        "document_start" -> WebViewCompat.INJECTION_EVENT_DOCUMENT_START
        "document_end" -> WebViewCompat.INJECTION_EVENT_DOCUMENT_END
        "document_idle" -> WebViewCompat.INJECTION_EVENT_DOCUMENT_END
        else -> null
    }

    private fun installMainScript(webView: WebView, script: ExtensionUserScriptsAdapter.RegisteredUserScript, code: String, originRules: Set<String>, urlGuard: String): ScriptHandler? {
        val phase = phaseFor(script.runAt) ?: return null
        val runCode = if (script.runAt.equals("document_idle", true)) """
            (function(){ const run=()=>{ $code }; if(typeof requestIdleCallback==='function') requestIdleCallback(run,{timeout:250}); else setTimeout(run,0); })();
        """.trimIndent() else code
        return runCatching {
            WebViewCompat.addJavaScriptOnEvent(webView, buildGuardedUserScript(script.id, urlGuard, runCode), phase, originRules.ifEmpty { setOf("*") }, JavaScriptExecutionWorld(JavaScriptExecutionWorld.PAGE_WORLD_NAME, webView))
        }.getOrNull()
    }

    private fun buildGuardedUserScript(id: String, urlGuard: String, code: String): String = """
        (function(){ 'use strict'; if(!($urlGuard)) return; try{ $code }catch(e){ try{ console.error('[Swift UserScript:${JSONObject.quote(id)}]',e); }catch(_){} } })();
    """.trimIndent()

    private fun ensureWorld(webView: WebView, tabId: String, extensionId: String, worldId: String, messaging: Boolean, isPrivate: Boolean, csp: String? = null): WorldState? {
        if (!isSupported()) return null
        val safe = worldId.trim().ifBlank { "default" }
        synchronized(lock) {
            states[webView]?.worlds?.get(worldKey(extensionId, safe))?.let { existing -> if (messaging) existing.messaging = true; if (csp != null) existing.csp = csp; return existing }
        }
        val listenerName = listenerName(extensionId, safe)
        val worldName = worldKey(extensionId, safe)
        val world = JavaScriptExecutionWorld(worldName, webView)
        val ws = WorldState(extensionId, tabId, safe, worldName, listenerName, world, messaging, csp)
        val listener = object : WebViewCompat.WebMessageListener {
            override fun onPostMessage(view: WebView, message: WebMessageCompat, sourceOrigin: Uri, isMainFrame: Boolean, replyProxy: JavaScriptReplyProxy) {
                handleWorldMessage(view, ws, message.data.orEmpty(), sourceOrigin, isMainFrame, replyProxy, isPrivate)
            }
        }
        try {
            WebViewCanonicalFrameBridge.prepare(webView, tabId)
            WebViewCompat.addWebMessageListener(webView, listenerName, setOf("*"), world, listener)
            val handler = WebViewCompat.addJavaScriptOnEvent(webView, buildWorldBootstrap(listenerName, extensionId, safe, messaging), WebViewCompat.INJECTION_EVENT_DOCUMENT_START, setOf("*"), world)
            ws.handlers += handler
            synchronized(lock) { states.getOrPut(webView) { ViewState(tabId, WeakReference(webView)) }.worlds[worldName] = ws }
            return ws
        } catch (_: Throwable) { return null }
    }

    private fun addScriptHandler(webView: WebView, state: WorldState, code: String, phase: Int, originRules: Set<String>): ScriptHandler? = runCatching {
        WebViewCompat.addJavaScriptOnEvent(webView, code, phase, originRules.ifEmpty { setOf("*") }, state.world).also { state.handlers += it }
    }.getOrNull()

    private fun buildWorldBootstrap(listenerName: String, extensionId: String, worldId: String, exposeMessaging: Boolean): String {
        val execName = EXEC_BRIDGE_PREFIX + extensionId.replace(Regex("[^A-Za-z0-9_]"), "_").take(32) + "_" + worldId.replace(Regex("[^A-Za-z0-9_]"), "_").take(24)
        return """
            (function(){
                'use strict';
                const bridge=window[${JSONObject.quote(listenerName)}]; if(!bridge) return;
                const frameToken=(document.documentElement&&document.documentElement.getAttribute(${JSONObject.quote(CANONICAL_FRAME_ATTR)}))||'';
                const documentId=(document.documentElement&&document.documentElement.getAttribute(${JSONObject.quote(CANONICAL_DOCUMENT_ATTR)}))||'';
                if(!frameToken||!documentId) return;
                const send=(type,extra)=>{try{bridge.postMessage(JSON.stringify(Object.assign({type:type,extensionId:${JSONObject.quote(extensionId)},frameToken:frameToken,documentId:documentId,url:location.href},extra||{})));}catch(_){} };
                try{window[${JSONObject.quote(execName)}]=bridge;}catch(_){}
                send('user-frame-ready');
                bridge.onmessage=event=>{
                    const raw=event&&event.data?String(event.data):'';let cmd;try{cmd=JSON.parse(raw);}catch(_){return;}
                    if(!cmd||cmd.type!=='execute')return;
                    (async()=>{let result=null,error=null;try{const args=Array.isArray(cmd.args)?cmd.args:[];let value;if(cmd.isFunction){const fn=eval('('+String(cmd.code||'')+')');value=fn.apply(globalThis,args);}else{value=(0,eval)(String(cmd.code||''));}result=await Promise.resolve(value);}catch(e){error=e&& (e.stack||e.message)?String(e.stack||e.message):String(e);}send('execute-result',{requestId:String(cmd.requestId||''),result:result,error:error});})();
                };
                ${if (exposeMessaging) """
                window.addEventListener('message',event=>{ const d=event&&event.data; if(!d||d.__swiftUserScriptMessage!==true) return; send('api',{payload:d.payload||null}); });
                """ else ""}
            })();
        """.trimIndent()
    }

    private fun handleWorldMessage(view: WebView, state: WorldState, payload: String, sourceOrigin: Uri, isMainFrame: Boolean, replyProxy: JavaScriptReplyProxy, isPrivate: Boolean) {
        val obj=runCatching{JSONObject(payload)}.getOrNull()?:return
        if(!obj.optString("extensionId",state.extensionId).equals(state.extensionId,true))return
        val machine=stateMachine(view)?:return
        when(obj.optString("type")){
            "user-frame-ready"->{ val token=obj.optString("frameToken").trim();val doc=obj.optString("documentId").trim();if(token.isBlank()||doc.isBlank())return;val frame=machine.getFrameIdentityByProviderToken(state.tabId,token)?:return;if(frame.documentId!=doc)return;synchronized(lock){state.transports[token]=replyProxy} }
            "api"->{ if(!state.messaging)return;val doc=obj.optString("documentId").trim();val frame=machine.getFrameIdentityByDocumentId(state.tabId,doc);val runtime=(ExtensionEngineApi.getInstance(view.context.applicationContext).extensionManager.engine as? ExtensionEngineImpl)?:return;runtime.createBridge(webView = view, tabId = state.tabId, isPrivate = isPrivate, contextTypeOverride = ExtensionContextType.USER_SCRIPT, ownExtensionId = state.extensionId, frameId = frame?.frameId, documentId = frame?.documentId).postWebMessage(payload,obj.optString("callbackId"),replyProxy) }
            "execute-result"->{val requestId=obj.optString("requestId").trim();val frameId=obj.optInt("frameId",-1);val doc=obj.optString("documentId").trim();if(requestId.isBlank()||frameId<0||doc.isBlank()||!machine.isCurrentDocument(state.tabId,frameId,doc))return;val pending=state.pending[requestId]?:return;synchronized(pending.results){if(pending.results.any{it.optInt("frameId",-1)==frameId&&it.optString("documentId")==doc})return;pending.results+=obj};if(pending.results.size>=pending.expected&&state.pending.remove(requestId)!=null){mainHandler.removeCallbacks(pending.timeout);val arr=JSONArray();synchronized(pending.results){pending.results.sortedBy{it.optInt("frameId",Int.MAX_VALUE)}.forEach(arr::put)};mainHandler.post{pending.callback(arr,null)}}}
        }
    }

    fun execute(webView: WebView, tabId: String, extensionId: String, worldId: String, code: String, isFunction: Boolean, args: JSONArray, allFrames: Boolean, frameIds: List<Int>, documentIds: List<String>?, callback: (JSONArray?, String?) -> Unit) {
        val state=synchronized(lock){states[webView]?.worlds?.get(worldKey(extensionId,worldId))}?:run{callback(null,"USER_SCRIPTS_WORLD_NOT_READY");return}
        val machine=stateMachine(webView)?:run{callback(null,"USER_SCRIPTS_FRAME_REGISTRY_UNAVAILABLE");return}
        val resolution=machine.resolveTargets(tabId,frameIds,documentIds,allFrames);if(resolution.status!="SUCCESS"){callback(null,resolution.status);return}
        val selected=resolution.frames.mapNotNull{frame->val token=frame.providerToken?:return@mapNotNull null;val proxy=state.transports[token]?:return@mapNotNull null;Triple(frame,token,proxy)}.sortedBy{it.first.frameId}
        if(selected.size!=resolution.frames.size){callback(null,"USER_SCRIPTS_FRAME_NOT_READY");return}
        val requestId="user-script-${UUID.randomUUID()}";val timeout=Runnable{if(state.pending.remove(requestId)!=null)callback(null,"USER_SCRIPTS_EXECUTION_TIMEOUT")};state.pending[requestId]=PendingRequest(selected.size,mutableListOf(),callback,timeout);mainHandler.postDelayed(timeout,RESPONSE_TIMEOUT_MS)
        selected.forEach{val command=JSONObject().apply{put("type","execute");put("requestId",requestId);put("frameId",it.first.frameId);put("documentId",it.first.documentId);put("code",code);put("isFunction",isFunction);put("args",args)}.toString();runCatching{it.third.postMessage(command)}}
    }

    private fun worldKey(extensionId:String,worldId:String):String= WORLD_PREFIX+extensionId.replace(Regex("[^A-Za-z0-9_]"),"_").take(40)+"-"+worldId.replace(Regex("[^A-Za-z0-9_]"),"_").take(32)
    private fun listenerName(extensionId:String,worldId:String):String= LISTENER_PREFIX+extensionId.replace(Regex("[^A-Za-z0-9_]"),"_").take(32)+"_"+worldId.replace(Regex("[^A-Za-z0-9_]"),"_").take(24)

    fun getWorldConfigurations(extensionId:String,prefs:android.content.SharedPreferences):JSONArray{val prefix="${extensionId}_world_";val worlds=linkedSetOf<String>();prefs.all.keys.forEach{key->if(key.startsWith(prefix)){when{key.endsWith("_messaging")->worlds+=key.removePrefix(prefix).removeSuffix("_messaging").ifBlank{"default"};key.endsWith("_csp")->worlds+=key.removePrefix(prefix).removeSuffix("_csp").ifBlank{"default"}}}};if(worlds.isEmpty())worlds+="default";return JSONArray().apply{worlds.sorted().forEach{id->put(JSONObject().put("worldId",id).put("csp",prefs.getString("${prefix}${id}_csp",null)).put("messaging",prefs.getBoolean("${prefix}${id}_messaging",false)))}}}
    fun clear(webView:WebView){val state=synchronized(lock){states.remove(webView)}?:return;state.worlds.values.forEach{w->w.handlers.forEach{runCatching{it.remove()}};w.pending.values.forEach{mainHandler.removeCallbacks(it.timeout)};runCatching{WebViewCompat.removeWebMessageListener(webView,w.listenerName)}}}
}
