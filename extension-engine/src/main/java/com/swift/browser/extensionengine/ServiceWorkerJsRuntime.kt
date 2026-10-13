package com.swift.browser.extensionengine

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.webkit.JavaScriptExecutionWorld
import android.webkit.WebViewClient
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import com.swift.browser.extensionengine.origin.ExtensionUrl
import com.swift.browser.extensionengine.security.ExtensionWebMessageBridge

/**
 * Dedicated JS execution environment for Manifest V3 Extension Service Workers.
 * Isolated from page DOM, page windows, and raw native bridge instances.
 */
enum class ServiceWorkerWebViewState { CREATED, CONFIGURING, BOOTSTRAPPING, READY, STOPPING, STOPPED, FAILED }

class ServiceWorkerJsRuntime(
    private val context: Context,
    val scriptInjector: ScriptInjector,
    private val messageBus: MessageBus,
    private var runtimeBridgeProvider: ((WebView) -> Any)? = null,
    private var contextAwareRuntimeBridgeProvider: ((WebView, ExtensionContextType) -> Any)? = null,
    private var networkPermissionChecker: ((String, String, Boolean) -> Boolean)? = null
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    data class ActiveWorkerInstance(
        val extensionId: String,
        val generationId: Int,
        val webView: WebView,
        val scriptPath: String,
        val startTime: Long = System.currentTimeMillis(),
        @Volatile var state: ServiceWorkerWebViewState = ServiceWorkerWebViewState.CREATED,
        var isInitialized: Boolean = false,
        var startupCallback: ((WorkerStartupResult, String?) -> Unit)? = null
    )

    private val activeWorkers = ConcurrentHashMap<String, ActiveWorkerInstance>()

    var onWorkerCrash: ((String, String) -> Unit)? = null

    fun setRuntimeBridgeProvider(provider: (WebView) -> Any) {
        this.runtimeBridgeProvider = provider
    }

    fun setContextAwareRuntimeBridgeProvider(provider: (WebView, ExtensionContextType) -> Any) {
        this.contextAwareRuntimeBridgeProvider = provider
    }

    fun setNetworkPermissionChecker(checker: (String, String, Boolean) -> Boolean) {
        this.networkPermissionChecker = checker
    }

    /**
     * Spawns a dedicated Service Worker runtime instance for the specified extension.
     */
    fun startWorker(
        parsedExtension: ParsedExtension,
        scriptPath: String,
        generationId: Int,
        bootstrapScript: String,
        isPrivate: Boolean = false,
        onStartupResult: (WorkerStartupResult, String?) -> Unit
    ) {
        val extensionId = parsedExtension.id
        val current = activeWorkers[extensionId]
        if (current != null && current.generationId == generationId && current.isInitialized) { onStartupResult(WorkerStartupResult.SUCCESS, null); return }

        // Destroy existing instance if any
        stopWorker(extensionId)

        mainHandler.post {
            try {
                val webView = WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = false
                    settings.databaseEnabled = false
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.mediaPlaybackRequiresUserGesture = true
                }

                webView.tag = ExtensionContextType.SERVICE_WORKER
                val bridge = contextAwareRuntimeBridgeProvider?.invoke(webView, ExtensionContextType.SERVICE_WORKER)
                    ?: runtimeBridgeProvider?.invoke(webView)
                if (bridge != null) {
                    webView.addJavascriptInterface(bridge, "SwiftExtensionBridge")

                    // Prefer the same origin-scoped WebMessage transport used by extension pages.
                    // The JS-interface remains only as a compatibility fallback.
                    if (ExtensionWebMessageBridge.isWebMessageListenerSupported()) {
                        val allowedOrigin = ExtensionUrl.toWebViewOrigin(extensionId)
                        ExtensionWebMessageBridge.setupWebMessageListener(
                            webView = webView,
                            listenerName = "__orionExtensionBridge",
                            allowedOrigins = setOf(allowedOrigin),
                            world = null
                        ) { payload, sourceOrigin, isMainFrame, _ ->
                            if (!sourceOrigin.equals(allowedOrigin, ignoreCase = true) || !isMainFrame) {
                                return@setupWebMessageListener
                            }
                            try {
                                val root = JSONObject(payload)
                                (bridge as? RuntimeBridge)?.postMessage(payload, root.optString("callbackId", ""))
                            } catch (e: Throwable) {
                                onWorkerCrash?.invoke(extensionId, "Invalid WebMessage payload: ${e.message}")
                            }
                        }
                    }
                }

                val instance = ActiveWorkerInstance(
                    extensionId = extensionId,
                    generationId = generationId,
                    webView = webView,
                    scriptPath = scriptPath,
                    startupCallback = onStartupResult
                )
                instance.state = ServiceWorkerWebViewState.CONFIGURING
                activeWorkers[extensionId] = instance

                webView.webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                        val msg = consoleMessage?.message() ?: ""
                        if (msg.contains("[SW_STARTUP_SUCCESS]")) {
                            handleStartupSuccess(extensionId, generationId)
                        } else if (msg.contains("[SW_STARTUP_FAILED]")) {
                            val err = msg.substringAfter("[SW_STARTUP_FAILED]").trim()
                            handleStartupFailed(extensionId, generationId, err)
                        }
                        return super.onConsoleMessage(consoleMessage)
                    }
                }

                webView.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): WebResourceResponse? {
                        val url = request?.url?.toString() ?: return null
                        val extensionOrigin = ExtensionUrl.toWebViewOrigin(extensionId)
                        if (url.startsWith("chrome-extension://") || url.startsWith(extensionOrigin)) {
                            val resp = ExtensionDirectoryResolver.handleExtensionRequest(context, url, isPrivate)
                            if (resp != null) return resp
                            return WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", mapOf(), ByteArrayInputStream(byteArrayOf()))
                        }
                        val allowedExternal = networkPermissionChecker?.invoke(extensionId, url, isPrivate) == true
                        if (allowedExternal) return null
                        return WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", mapOf(), ByteArrayInputStream(byteArrayOf()))
                    }

                    override fun onRenderProcessGone(
                        view: WebView?,
                        detail: android.webkit.RenderProcessGoneDetail?
                    ): Boolean {
                        val reason = if (detail?.didCrash() == true) "process_crash" else "process_killed"
                        stopWorker(extensionId)
                        onWorkerCrash?.invoke(extensionId, reason)
                        return true
                    }
                }

                // Compile worker code sandbox script
                val scriptContent = loadExtensionResource(context, parsedExtension, scriptPath)

                val workerSandboxWrapper = compileWorkerSandbox(
                    extensionId = extensionId,
                    scriptPath = scriptPath,
                    generationId = generationId,
                    bootstrapScript = bootstrapScript,
                    scriptContent = scriptContent
                )

                // WebView cannot use chrome-extension:// as a normal page origin.
                // Use the same canonical synthetic HTTPS extension origin as extension pages
                // so the origin-scoped bridge and resource/security checks share one model.
                webView.loadDataWithBaseURL(
                    "${ExtensionUrl.toWebViewOrigin(extensionId)}/",
                    "<!DOCTYPE html><html><head><script>$workerSandboxWrapper</script></head><body></body></html>",
                    "text/html",
                    "UTF-8",
                    null
                )

                // Enforce a 10.0s startup timeout
                mainHandler.postDelayed({
                    val cur = activeWorkers[extensionId]
                    if (cur != null && cur.generationId == generationId && !cur.isInitialized) {
                        handleStartupFailed(extensionId, generationId, "Worker startup timed out after 10000ms")
                    }
                }, 10000L)

            } catch (e: Exception) {
                onStartupResult(WorkerStartupResult.FAILED, e.message ?: "Failed to spawn worker webview")
            }
        }
    }

    private fun handleStartupSuccess(extensionId: String, generationId: Int) {
        val instance = activeWorkers[extensionId] ?: return
        if (instance.generationId != generationId) return
        if (instance.isInitialized) return

        instance.isInitialized = true
        instance.state = ServiceWorkerWebViewState.READY
        val callback = instance.startupCallback
        instance.startupCallback = null
        callback?.invoke(WorkerStartupResult.SUCCESS, null)
    }

    private fun handleStartupFailed(extensionId: String, generationId: Int, errorMsg: String) {
        val instance = activeWorkers[extensionId] ?: return
        if (instance.generationId != generationId) return

        val callback = instance.startupCallback
        instance.startupCallback = null
        stopWorker(extensionId)
        callback?.invoke(WorkerStartupResult.FAILED, errorMsg)
    }

    /**
     * Dispatches an event to an active worker JS execution context.
     */
    fun evaluateEvent(
        extensionId: String,
        generationId: Int,
        eventName: String,
        payload: Any?,
        callback: ((Boolean) -> Unit)? = null
    ) {
        mainHandler.post {
            val instance = activeWorkers[extensionId]
            if (instance == null || instance.generationId != generationId || !instance.isInitialized) {
                callback?.invoke(false)
                return@post
            }

            val payloadStr = when (payload) {
                is JSONObject -> payload.toString()
                is String -> JSONObject.quote(payload)
                null -> "null"
                else -> payload.toString()
            }

            val extJs = JSONObject.quote(extensionId)
            val eventJs = JSONObject.quote(eventName)
            val js = "(function(){ if (typeof _swiftDispatchEvent === 'function') { _swiftDispatchEvent($extJs, $eventJs, $payloadStr); return true; } return false; })()"
            instance.webView.evaluateJavascript(js) { res ->
                callback?.invoke(res == "true")
            }
        }
    }

    fun stopWorker(extensionId: String) {
        mainHandler.post {
            val instance = activeWorkers.remove(extensionId) ?: return@post
            instance.state = ServiceWorkerWebViewState.STOPPING
            try {
                instance.webView.stopLoading()
                instance.webView.removeJavascriptInterface("SwiftExtensionBridge")
                if (ExtensionWebMessageBridge.isWebMessageListenerSupported()) {
                    try {
                        ExtensionWebMessageBridge.removeWebMessageListener(
                            instance.webView,
                            "__orionExtensionBridge"
                        )
                    } catch (_: Throwable) {
                    }
                }
                instance.webView.loadUrl("about:blank")
                instance.webView.destroy()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun stopAll() {
        mainHandler.post {
            val keys = ArrayList(activeWorkers.keys)
            for (key in keys) {
                stopWorker(key)
            }
        }
    }

    fun isWorkerRunning(extensionId: String): Boolean {
        val instance = activeWorkers[extensionId] ?: return false
        return instance.isInitialized
    }


    fun getWorkerGeneration(extensionId: String): Int {
        return activeWorkers[extensionId]?.generationId ?: 0
    }

    private fun compileWorkerSandbox(
        extensionId: String,
        scriptPath: String,
        generationId: Int,
        bootstrapScript: String,
        scriptContent: String
    ): String {
        val cleanScriptPath = if (scriptPath.startsWith("/")) scriptPath else "/$scriptPath"

        return """
            (function() {
                'use strict';

                // Define worker execution flags
                window._isServiceWorkerContext = true;

                // Construct self context object
                var selfObj = {
                    name: "$extensionId",
                    location: {
                        href: "chrome-extension://$extensionId$cleanScriptPath",
                        origin: "chrome-extension://$extensionId",
                        protocol: "chrome-extension:",
                        host: "$extensionId",
                        hostname: "$extensionId",
                        port: "",
                        pathname: "$cleanScriptPath",
                        search: "",
                        hash: ""
                    },
                    registration: {
                        scope: "chrome-extension://$extensionId/",
                        active: true
                    },
                    addEventListener: function(type, listener) {
                        if (!window._swiftExtensionEvents) window._swiftExtensionEvents = {};
                        if (!window._swiftExtensionEvents["$extensionId"]) window._swiftExtensionEvents["$extensionId"] = {};
                        if (!window._swiftExtensionEvents["$extensionId"][type]) window._swiftExtensionEvents["$extensionId"][type] = [];
                        var bucket = window._swiftExtensionEvents["$extensionId"][type];
                        if (bucket.indexOf(listener) < 0) bucket.push(listener);
                    },
                    removeEventListener: function(type, listener) {
                        var bucket = window._swiftExtensionEvents && window._swiftExtensionEvents["$extensionId"] && window._swiftExtensionEvents["$extensionId"][type];
                        if (bucket) { var idx = bucket.indexOf(listener); if (idx >= 0) bucket.splice(idx, 1); }
                    },
                    importScripts: function() {
                        for (var i = 0; i < arguments.length; i++) {
                            var url = arguments[i];
                            if (!url) continue;
                            if (url.indexOf("http://") === 0 || url.indexOf("https://") === 0 || url.indexOf("//") === 0) {
                                throw new Error("SecurityError: importScripts from remote HTTP/HTTPS origins is strictly prohibited in Manifest V3 Service Workers.");
                            }
                            var resolvedUrl = url;
                            var physicalOrigin = "${ExtensionUrl.toWebViewOrigin(extensionId)}";
                            var extPrefix = "chrome-extension://" + "$extensionId";
                            if (url.indexOf(extPrefix) === 0) {
                                resolvedUrl = physicalOrigin + url.substring(extPrefix.length);
                            } else {
                                resolvedUrl = (url.charAt(0) === '/') ? (physicalOrigin + url) : (physicalOrigin + "/" + url);
                            }
                            // Synchronous fetch of extension package asset via XHR
                            // against Orion's synthetic extension origin.

                            try {
                                var xhr = new XMLHttpRequest();
                                xhr.open("GET", resolvedUrl, false);
                                xhr.send();
                                if (xhr.status === 200 || xhr.status === 0) {
                                    eval(xhr.responseText);
                                } else {
                                    throw new Error("Failed to load script: " + url + " (Status " + xhr.status + ")");
                                }
                            } catch (e) {
                                console.error("[IMPORT_SCRIPTS_ERROR]", e);
                                throw e;
                            }
                        }
                    }
                };

                // Install bootstrap extension API router
                $bootstrapScript

                var extCtx = window._swiftGetExtensionContext ? window._swiftGetExtensionContext("$extensionId") : {};
                selfObj.chrome = extCtx;
                selfObj.browser = extCtx;
                selfObj.self = selfObj;
                selfObj.globalThis = selfObj;

                // Execute service worker script in worker global context
                try {
                    (function(self, chrome, browser, importScripts, globalThis) {
                        $scriptContent
                    }).call(selfObj, selfObj, selfObj.chrome, selfObj.browser, selfObj.importScripts, selfObj);

                    console.log("[SW_STARTUP_SUCCESS] Generation $generationId for $extensionId");
                } catch (e) {
                    console.error("[SW_STARTUP_FAILED] " + (e.stack || e.message || e));
                }
            })();
        """.trimIndent()
    }

    private fun loadExtensionResource(
        context: Context,
        parsedExtension: ParsedExtension,
        relativePath: String
    ): String {
        val extDir = ExtensionDirectoryResolver.getExtensionDir(context, parsedExtension.id, parsedExtension.name)
        val file = ExtensionDirectoryResolver.findFileCaseInsensitive(extDir, relativePath)
        return if (file != null && file.exists()) {
            try {
                file.readText()
            } catch (e: Exception) {
                ""
            }
        } else ""
    }
}

