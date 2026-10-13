package com.swift.browser.extensionengine

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.swift.browser.extensionengine.origin.ExtensionUrl
import com.swift.browser.extensionengine.security.ExtensionWebMessageBridge
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Canonical MV2 background-page runtime.
 *
 * MV2 only:
 *  - persistent background page => one dedicated WebView kept alive while enabled
 *  - event page (background.persistent=false) => WebView is created on demand and
 *    suspended after an idle window
 *  - background.page is loaded as-is; its HTML owns its script tags
 *  - background.scripts is executed exactly once, in manifest order, in a generated page
 *
 * MV3 is deliberately not handled here; it has its own Native-V8 service-worker runtime.
 */
class BackgroundScriptManager(
    val context: Context,
    val scriptInjector: ScriptInjector,
    val messageBus: MessageBus
) {

    enum class State {
        REGISTERED,
        STARTING,
        RUNNING,
        SUSPENDING,
        SUSPENDED,
        CRASHED,
        DISABLED,
        UNREGISTERED
    }

    data class BackgroundHostKey(
        val extensionId: String,
        val isPrivate: Boolean = false,
        val privateSessionId: String? = null
    ) {
        fun canonical(): String = buildString {
            append(extensionId.lowercase())
            append('|')
            append(if (isPrivate) '1' else '0')
            append('|')
            append(privateSessionId?.lowercase().orEmpty())
        }
    }

    private data class HostRuntime(
        val key: BackgroundHostKey,
        val extension: ParsedExtension,
        val generation: Int,
        var state: State,
        var webView: WebView? = null,
        var bridge: RuntimeBridge? = null,
        var documentStartHandler: ScriptHandler? = null,
        var fallbackBootstrapAttemptedUrl: String? = null,
        var scriptsLoaded: Boolean = false,
        var pageReady: Boolean = false,
        var suspendToken: Long = 0L,
        var crashCount: Int = 0,
        val bootstrapScript: String,
        val permissionManager: PermissionManager?,
        val pendingReady: MutableList<(Boolean) -> Unit> = mutableListOf()
    )

    private val main = Handler(Looper.getMainLooper())
    private val hosts = ConcurrentHashMap<String, HostRuntime>()
    private val definitions = ConcurrentHashMap<String, ParsedExtension>()
    private val generationCounters = ConcurrentHashMap<String, AtomicInteger>()

    /** Supplies the canonical extension bootstrap without coupling MV2 to MV3 runtime code. */
    var bootstrapScriptProvider: ((String) -> String)? = null

    private var _runtimeBridgeProvider: ((WebView) -> RuntimeBridge)? = null
    private var _contextAwareRuntimeBridgeProvider: ((WebView, ExtensionContextType) -> RuntimeBridge)? = null

    val runtimeBridgeProvider: ((WebView) -> RuntimeBridge)? get() = _runtimeBridgeProvider

    var consoleLogCallback: ((level: String, message: String) -> Unit)? = null

    /** Diagnostics callback; this is intentionally NOT wired into the MV3 service-worker crash path. */
    var onBackgroundCrash: ((extensionId: String, reason: String) -> Unit)? = null

    /** True when this extension currently owns at least one live Port connection. */
    var activePortProvider: ((extensionId: String) -> Boolean)? = null

    /** Browser-side keepalive counts for in-flight MV2 background operations. */
    private val keepaliveCounts = ConcurrentHashMap<String, AtomicInteger>()
    private val keepaliveTokenSequence = AtomicInteger(0)

    fun setRuntimeBridgeProvider(provider: (WebView) -> RuntimeBridge) {
        _runtimeBridgeProvider = provider
    }

    fun setContextAwareRuntimeBridgeProvider(provider: (WebView, ExtensionContextType) -> RuntimeBridge) {
        _contextAwareRuntimeBridgeProvider = provider
    }

    /** Register/refresh the canonical MV2 definition without starting a WebView. */
    fun registerExtension(ext: ParsedExtension) {
        require(ext.manifestVersion == 2) { "BackgroundScriptManager accepts MV2 only" }
        definitions[ext.id.lowercase()] = ext
        if (!ext.isEnabled) {
            stopAllForExtension(ext.id, terminalState = State.DISABLED)
        }
    }

    fun unregisterExtension(extensionId: String) {
        val cleanId = extensionId.lowercase().trim()
        definitions.remove(cleanId)
        stopAllForExtension(cleanId, terminalState = State.UNREGISTERED)
        generationCounters.remove(cleanId)
    }

    fun handlesExtension(extensionId: String): Boolean = definitions.containsKey(extensionId.lowercase().trim())

    fun isEventPage(extensionId: String): Boolean =
        definitions[extensionId.lowercase().trim()]?.let { isEventPage(it) } == true

    fun isPersistentBackground(extensionId: String): Boolean =
        definitions[extensionId.lowercase().trim()]?.let { !isEventPage(it) } == true

    private fun isEventPage(ext: ParsedExtension): Boolean =
        ext.manifestVersion == 2 && (ext.backgroundScripts.isNotEmpty() || ext.backgroundSpec.page.isNotBlank()) && !ext.backgroundSpec.persistent

    fun getState(extensionId: String, isPrivate: Boolean = false, privateSessionId: String? = null): State {
        val host = hosts[BackgroundHostKey(extensionId, isPrivate, privateSessionId).canonical()]
        return host?.state ?: State.REGISTERED.takeIf { handlesExtension(extensionId) } ?: State.UNREGISTERED
    }

    fun hasBackgroundWorker(id: String): Boolean =
        hosts.values.any { it.key.extensionId.equals(id, ignoreCase = true) && it.state == State.RUNNING }

    fun getBackgroundWebView(extensionId: String): WebView? =
        hosts[BackgroundHostKey(extensionId).canonical()]?.webView

    /**
     * Start persistent MV2 pages immediately; event pages only register their definition.
     * Call dispatchEvent()/wakeForMessage() to start an event page on demand.
     */
    @SuppressLint("JavascriptInterface", "SetJavaScriptEnabled")
    fun startBackgroundWorker(
        ext: ParsedExtension,
        bootstrapScript: String = "",
        isPrivate: Boolean = false,
        permissionManager: PermissionManager? = null,
        privateSessionId: String? = null,
        onStarted: ((Boolean) -> Unit)? = null
    ) {
        if (ext.manifestVersion != 2 || ext.isServiceWorker) {
            onStarted?.invoke(false)
            return
        }
        if (ext.backgroundScripts.isEmpty() && ext.backgroundSpec.page.isBlank()) {
            onStarted?.invoke(true)
            return
        }
        if (isPrivate) {
            val allowed = ext.allowedInPrivate || (permissionManager?.isAllowedInPrivate(ext.id) == true)
            if (!allowed) {
                onStarted?.invoke(false)
                return
            }
        }

        registerExtension(ext)
        val key = BackgroundHostKey(ext.id, isPrivate, privateSessionId)
        val canonical = key.canonical()
        main.post {
            val existing = hosts[canonical]
            if (existing != null) {
                when (existing.state) {
                    State.RUNNING -> { cancelPendingSuspend(existing); onStarted?.invoke(true) }
                    State.STARTING -> existing.pendingReady += { onStarted?.invoke(it) }
                    State.SUSPENDING -> {
                        cancelPendingSuspend(existing)
                        onStarted?.invoke(true)
                    }
                    State.SUSPENDED, State.CRASHED, State.REGISTERED -> {
                        existing.pendingReady += { onStarted?.invoke(it) }
                        startHost(ext, bootstrapScript, key, permissionManager)
                    }
                    State.DISABLED, State.UNREGISTERED -> onStarted?.invoke(false)
                }
                return@post
            }

            // Persistent pages start with the extension; event pages are dormant until an event wakes them.
            if (isEventPage(ext) && onStarted == null) {
                return@post
            }

            val generation = generationCounters
                .computeIfAbsent(ext.id.lowercase()) { AtomicInteger(0) }
                .incrementAndGet()
            val host = HostRuntime(
                key = key,
                extension = ext,
                generation = generation,
                state = State.REGISTERED,
                bootstrapScript = bootstrapScript,
                permissionManager = permissionManager
            )
            if (onStarted != null) host.pendingReady += onStarted
            hosts[canonical] = host
            startHost(ext, bootstrapScript, key, permissionManager)
        }
    }

    /** Wake/create the MV2 background context and invoke the supplied action once it is ready. */
    fun wakeForMessage(
        extensionId: String,
        bootstrapScript: String,
        isPrivate: Boolean = false,
        privateSessionId: String? = null,
        permissionManager: PermissionManager? = null,
        onReady: (Boolean) -> Unit
    ) {
        val ext = definitions[extensionId.lowercase().trim()]
        if (ext == null || ext.manifestVersion != 2 || !ext.isEnabled) {
            onReady(false)
            return
        }
        val key = BackgroundHostKey(ext.id, isPrivate, privateSessionId)
        main.post {
            val existing = hosts[key.canonical()]
            if (existing?.state == State.RUNNING) {
                cancelPendingSuspend(existing)
                onReady(true)
                return@post
            }
            startBackgroundWorker(
                ext = ext,
                bootstrapScript = bootstrapScript,
                isPrivate = isPrivate,
                permissionManager = permissionManager,
                privateSessionId = privateSessionId,
                onStarted = onReady
            )
        }
    }

    /**
     * Dispatch a browser event to the extension's live background page. For an event page,
     * this function wakes it first, then sends exactly one EVENT_DISPATCH through MessageBus.
     */
    fun dispatchEvent(
        extensionId: String,
        eventName: String,
        payload: org.json.JSONObject,
        bootstrapScript: String,
        isPrivate: Boolean = false,
        privateSessionId: String? = null,
        permissionManager: PermissionManager? = null,
        onDelivered: ((Boolean) -> Unit)? = null
    ): Boolean {
        val ext = definitions[extensionId.lowercase().trim()] ?: return false
        if (ext.manifestVersion != 2 || !ext.isEnabled) return false
        val effectiveBootstrap = bootstrapScript.ifBlank { bootstrapScriptProvider?.invoke(ext.id).orEmpty() }

        wakeForMessage(
            extensionId = ext.id,
            bootstrapScript = effectiveBootstrap,
            isPrivate = isPrivate,
            privateSessionId = privateSessionId,
            permissionManager = permissionManager
        ) { ready ->
            if (!ready) {
                onDelivered?.invoke(false)
                return@wakeForMessage
            }
            val msg = org.json.JSONObject().apply {
                put("type", "EVENT_DISPATCH")
                put("eventName", eventName)
                put("data", payload)
                put("__targetExtensionId__", ext.id)
                put("__targetContextType__", ExtensionContextType.BACKGROUND.name)
            }
            messageBus.broadcastMessage(ext.id, null, msg)
            onDelivered?.invoke(true)
            scheduleEventPageSuspend(BackgroundHostKey(ext.id, isPrivate, privateSessionId))
        }
        return true
    }

    fun stopBackgroundWorker(extensionId: String) {
        stopAllForExtension(extensionId, terminalState = null)
    }

    /**
     * Executes a named function on the MV2 background page. This is used to provide a safe
     * cross-WebView proxy for runtime.getBackgroundPage(); the real Window object cannot be
     * transferred between Android WebViews, but callable background functions can.
     */
    fun invokeBackgroundFunction(
        extensionId: String,
        functionName: String,
        args: org.json.JSONArray,
        isPrivate: Boolean = false,
        privateSessionId: String? = null,
        onResult: (Boolean, Any?, String?) -> Unit
    ) {
        val ext = definitions[extensionId.lowercase().trim()]
        if (ext == null || ext.manifestVersion != 2 || !ext.isEnabled || functionName.isBlank()) {
            onResult(false, null, "BACKGROUND_PAGE_UNAVAILABLE")
            return
        }
        val key = BackgroundHostKey(ext.id, isPrivate, privateSessionId)
        val keepaliveToken = if (isEventPage(ext)) acquireKeepalive(ext.id, isPrivate, privateSessionId) else null
        var keepaliveReleased = false
        fun releaseCallKeepalive() {
            if (!keepaliveReleased) {
                keepaliveReleased = true
                releaseKeepalive(ext.id, keepaliveToken, isPrivate, privateSessionId)
            }
        }
        val run: () -> Unit = run@{
            val host = hosts[key.canonical()]
            val webView = host?.webView
            if (host?.state != State.RUNNING || webView == null) {
                releaseCallKeepalive()
                onResult(false, null, "BACKGROUND_PAGE_UNAVAILABLE")
                return@run
            }
            val fnParts = functionName.split('.').filter { it.matches(Regex("^[A-Za-z_$][A-Za-z0-9_$]*$")) }
            if (fnParts.isEmpty() || fnParts.joinToString(".") != functionName) {
                releaseCallKeepalive()
                onResult(false, null, "INVALID_BACKGROUND_FUNCTION")
                return@run
            }
            val argsJson = args.toString()
            val fnPath = fnParts.joinToString(".")
            val script = """
                (async function() {
                    try {
                        const fn = window.$fnPath;
                        if (typeof fn !== 'function') throw new Error('BACKGROUND_FUNCTION_NOT_FOUND');
                        const value = await fn.apply(window, $argsJson);
                        return JSON.stringify({ok:true, value: value === undefined ? null : value});
                    } catch (e) {
                        return JSON.stringify({ok:false, error: String(e && (e.message || e) || 'BACKGROUND_FUNCTION_FAILED')});
                    }
                })();
            """.trimIndent()
            runCatching {
                webView.evaluateJavascript(script) { raw ->
                    try {
                        val decoded = runCatching {
                            val value = org.json.JSONTokener(raw ?: "null").nextValue()
                            if (value is String) value else raw.orEmpty()
                        }.getOrDefault(raw.orEmpty())
                        val envelope = org.json.JSONObject(decoded.ifBlank { "{}" })
                        if (envelope.optBoolean("ok", false)) {
                            releaseCallKeepalive()
                            onResult(true, envelope.opt("value"), null)
                        } else {
                            releaseCallKeepalive()
                            onResult(false, null, envelope.optString("error", "BACKGROUND_FUNCTION_FAILED"))
                        }
                    } catch (t: Throwable) {
                        releaseCallKeepalive()
                        onResult(false, null, "BACKGROUND_FUNCTION_RESULT_INVALID")
                    }
                }
            }.onFailure {
                releaseCallKeepalive()
                onResult(false, null, "BACKGROUND_FUNCTION_EVALUATION_FAILED")
            }
        }

        if (hosts[key.canonical()]?.state == State.RUNNING) {
            cancelPendingSuspend(hosts[key.canonical()]!!)
            run()
        } else {
            val effectiveBootstrap = bootstrapScriptProvider?.invoke(ext.id).orEmpty()
            wakeForMessage(ext.id, effectiveBootstrap, isPrivate, privateSessionId, null) { ok ->
                if (ok) run() else {
                    releaseCallKeepalive()
                    onResult(false, null, "BACKGROUND_PAGE_UNAVAILABLE")
                }
            }
        }
    }

    /**
     * Acquires a bounded browser-side keepalive for a MV2 background context.
     * A token survives event-page suspension scheduling and is released by the caller.
     */
    fun acquireKeepalive(
        extensionId: String,
        isPrivate: Boolean = false,
        privateSessionId: String? = null
    ): String? {
        val ext = definitions[extensionId.lowercase().trim()] ?: return null
        if (ext.manifestVersion != 2 || !ext.isEnabled) return null
        val key = BackgroundHostKey(ext.id, isPrivate, privateSessionId).canonical()
        keepaliveCounts.computeIfAbsent(key) { AtomicInteger(0) }.incrementAndGet()
        return "mv2ka_${System.nanoTime()}_${keepaliveTokenSequence.incrementAndGet()}"
    }

    /**
     * Re-evaluates event-page idle state after a Port connect/disconnect activity change.
     */
    fun notifyPortActivityChanged(
        extensionId: String,
        isPrivate: Boolean = false,
        privateSessionId: String? = null
    ) {
        val key = BackgroundHostKey(extensionId, isPrivate, privateSessionId)
        hosts[key.canonical()]?.let { host ->
            if (host.state == State.RUNNING && isEventPage(host.extension) && activePortProvider?.invoke(host.extension.id) != true) {
                scheduleEventPageSuspend(key)
            } else if (host.state == State.RUNNING) {
                cancelPendingSuspend(host)
            }
        }
    }

    fun releaseKeepalive(
        extensionId: String,
        token: String?,
        isPrivate: Boolean = false,
        privateSessionId: String? = null
    ) {
        if (token == null) return
        val key = BackgroundHostKey(extensionId, isPrivate, privateSessionId).canonical()
        val count = keepaliveCounts[key] ?: return
        if (count.decrementAndGet() <= 0) {
            keepaliveCounts.remove(key, count)
            hosts[key]?.let {
                if (it.state == State.RUNNING && isEventPage(it.extension)) scheduleEventPageSuspend(it.key)
            }
        }
    }

    fun stopAll() {
        val keys = hosts.values.map { it.key }
        keys.forEach { stopHost(it, State.UNREGISTERED) }
        definitions.clear()
    }

    private fun stopAllForExtension(extensionId: String, terminalState: State?) {
        val clean = extensionId.lowercase().trim()
        hosts.values.filter { it.key.extensionId == clean }.map { it.key }.forEach { key ->
            stopHost(key, terminalState)
        }
    }

    private fun startHost(
        ext: ParsedExtension,
        bootstrapScript: String,
        key: BackgroundHostKey,
        permissionManager: PermissionManager?
    ) {
        val canonical = key.canonical()
        val host = hosts[canonical] ?: return
        if (host.state == State.STARTING || host.state == State.RUNNING) return
        if (!ext.isEnabled) {
            host.state = State.DISABLED
            completeReady(host, false)
            return
        }
        host.state = State.STARTING
        cancelPendingSuspend(host)

        val wv = try {
            WebView(context.applicationContext)
        } catch (t: Throwable) {
            host.state = State.CRASHED
            completeReady(host, false)
            Log.e(TAG, "Failed to create MV2 background WebView for ${ext.id}", t)
            return
        }
        host.webView = wv

        configureWebView(wv, ext, key, bootstrapScript, permissionManager, host)
        hosts[canonical] = host

        val origin = ExtensionUrl.toWebViewOrigin(ext.id)
        val pagePath = ext.backgroundSpec.page.trim().removePrefix("/")
        val targetUrl = if (pagePath.isNotBlank()) {
            "$origin/$pagePath"
        } else {
            "$origin/_generated_mv2_background_page.html"
        }
        try {
            if (pagePath.isBlank()) {
                val html = """
                    <!doctype html>
                    <html>
                    <head><meta charset="utf-8"><title>${escapeHtml(ext.name)}</title></head>
                    <body></body>
                    </html>
                """.trimIndent()
                wv.loadDataWithBaseURL(origin + "/", html, "text/html", "UTF-8", targetUrl)
            } else {
                wv.loadUrl(targetUrl)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to load MV2 background page for ${ext.id}", t)
            handleHostCrash(host, "load_background_page", t)
        }
    }

    @SuppressLint("JavascriptInterface", "SetJavaScriptEnabled")
    private fun configureWebView(
        wv: WebView,
        ext: ParsedExtension,
        key: BackgroundHostKey,
        bootstrapScript: String,
        permissionManager: PermissionManager?,
        host: HostRuntime
    ) {
        val origin = ExtensionUrl.toWebViewOrigin(ext.id)
        val bridge = _contextAwareRuntimeBridgeProvider?.invoke(wv, ExtensionContextType.BACKGROUND)
            ?: _runtimeBridgeProvider?.invoke(wv)
        host.bridge = bridge

        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.settings.databaseEnabled = false
        wv.settings.allowFileAccess = false
        wv.settings.allowContentAccess = false
        runCatching {
            wv.settings.allowFileAccessFromFileURLs = false
            wv.settings.allowUniversalAccessFromFileURLs = false
        }
        wv.settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
        wv.settings.userAgentString = com.swift.browser.desktopengine.useragent.UserAgentManager.getMobileUserAgent(context)
        wv.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        wv.tag = ExtensionContextType.BACKGROUND

        if (bridge != null) {
            wv.tag = bridge
            wv.addJavascriptInterface(bridge, "SwiftExtensionBridge")
            if (ExtensionWebMessageBridge.isWebMessageListenerSupported()) {
                runCatching {
                    ExtensionWebMessageBridge.setupWebMessageListener(
                        webView = wv,
                        listenerName = MODERN_BRIDGE_NAME,
                        allowedOrigins = setOf(origin),
                        world = null
                    ) { payload, sourceOrigin, isMainFrame, _ ->
                        if (!isMainFrame || !sourceOrigin.equals(origin, ignoreCase = true)) return@setupWebMessageListener
                        runCatching {
                            val root = org.json.JSONObject(payload)
                            bridge.postWebMessage(payload, root.optString("callbackId", ""))
                        }
                    }
                }.onFailure { Log.w(TAG, "Modern MV2 bridge unavailable for ${ext.id}", it) }
            }
        }

        // Earliest-available API bootstrap. This is critical for background.page HTML,
        // whose own script tags can execute before onPageFinished.
        host.documentStartHandler = try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                WebViewCompat.addDocumentStartJavaScript(
                    wv,
                    "window._isMV2BackgroundPage = true;\n$bootstrapScript",
                    setOf(origin)
                )
            } else null
        } catch (t: Throwable) {
            Log.w(TAG, "Document-start bootstrap unavailable for ${ext.id}", t)
            null
        }

        wv.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                consoleMessage?.let {
                    val level = it.messageLevel()?.name ?: "LOG"
                    val message = it.message() ?: ""
                    consoleLogCallback?.invoke(level, "$message (${it.sourceId()}:${it.lineNumber()})")
                }
                return true
            }
        }

        wv.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                if (host.documentStartHandler == null && view != null && url != null && host.fallbackBootstrapAttemptedUrl != url) {
                    host.fallbackBootstrapAttemptedUrl = url
                    runCatching { view.evaluateJavascript("window._isMV2BackgroundPage = true;\n$bootstrapScript", null) }
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return true
                return !ExtensionUrl.isWebViewExtensionUrl(url) || !url.startsWith(origin + "/")
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val url = request?.url?.toString() ?: return null
                if (!ExtensionUrl.isWebViewExtensionUrl(url)) return null
                val parsed = ExtensionUrl.parseExtensionUrl(url) ?: return null
                if (!parsed.extensionId.equals(ext.id, ignoreCase = true)) return null
                return ExtensionDirectoryResolver.handleExtensionRequest(context, url, isPrivate = key.isPrivate)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (view == null || host.pageReady) return
                val isGeneratedScriptPage = ext.backgroundSpec.page.isBlank()
                if (isGeneratedScriptPage && !host.scriptsLoaded) {
                    host.scriptsLoaded = true
                    executeBackgroundScriptsSequentially(host, bootstrapScript)
                } else {
                    host.pageReady = true
                    host.state = State.RUNNING
                    completeReady(host, true)
                }
            }

            override fun onRenderProcessGone(
                view: WebView?,
                detail: android.webkit.RenderProcessGoneDetail?
            ): Boolean {
                handleHostCrash(host, "onRenderProcessGone", null)
                return true
            }
        }
    }

    private fun executeBackgroundScriptsSequentially(host: HostRuntime, bootstrapScript: String) {
        val ext = host.extension
        val extensionDir = ExtensionDirectoryResolver.getExtensionDir(context, ext.id, ext.name)
        val scripts = ext.backgroundScripts.toList()
        if (scripts.isEmpty()) {
            host.pageReady = true
            host.state = State.RUNNING
            completeReady(host, true)
            return
        }

        fun runAt(index: Int) {
            if (index >= scripts.size) {
                host.pageReady = true
                host.state = State.RUNNING
                completeReady(host, true)
                return
            }
            val webView = host.webView ?: run {
                completeReady(host, false)
                return
            }
            val path = scripts[index]
            val code = readExtensionFile(extensionDir, path)
            if (code.isBlank()) {
                handleHostCrash(host, "missing_background_script:$path", null)
                return
            }

            val wrapper = """
                (function() {
                    window._isMV2BackgroundPage = true;
                    try {
                        if (typeof window._swiftGetExtensionContext === 'undefined') {
                            $bootstrapScript
                        }
                        var browser = window._swiftGetExtensionContext(${org.json.JSONObject.quote(ext.id)});
                        var chrome = browser;
                        window.chrome = chrome;
                        window.browser = browser;
                        if (typeof self !== 'undefined') {
                            self.chrome = chrome;
                            self.browser = browser;
                        }
                        (function(){
                            $code
                        }).call(window);
                    } catch (e) {
                        console.error("MV2 background script error in ${escapeJs(path)}", e);
                        if (typeof window._orionPostMessage === 'function') {
                            try { window._orionPostMessage("runtime.reportCrash", [String(e && (e.stack || e.message || e))], ""); } catch (_) {}
                        }
                    }
                })();
            """.trimIndent()

            try {
                webView.evaluateJavascript(wrapper) {
                    runAt(index + 1)
                }
            } catch (t: Throwable) {
                handleHostCrash(host, "background_script_eval:$path", t)
            }
        }

        runAt(0)
    }

    private fun scheduleEventPageSuspend(key: BackgroundHostKey) {
        val canonical = key.canonical()
        val host = hosts[canonical] ?: return
        if (!isEventPage(host.extension)) return
        cancelPendingSuspend(host)
        val token = System.nanoTime()
        host.suspendToken = token
        main.postDelayed({
            val current = hosts[canonical] ?: return@postDelayed
            if (current.suspendToken != token || current.state != State.RUNNING) return@postDelayed
            if (keepaliveCounts[current.key.canonical()]?.get() ?: 0 > 0 || activePortProvider?.invoke(current.extension.id) == true) {
                scheduleEventPageSuspend(current.key)
                return@postDelayed
            }
            current.state = State.SUSPENDING
            // Chrome's runtime.onSuspend is best-effort; asynchronous work is not guaranteed.
            messageBus.broadcastMessage(
                current.extension.id,
                null,
                org.json.JSONObject().apply {
                    put("type", "EVENT_DISPATCH")
                    put("eventName", "runtime.onSuspend")
                    put("data", org.json.JSONObject())
                    put("__targetExtensionId__", current.extension.id)
                    put("__targetContextType__", ExtensionContextType.BACKGROUND.name)
                }
            )
            main.postDelayed({
                if (current.suspendToken == token && current.state == State.SUSPENDING) {
                    if (keepaliveCounts[current.key.canonical()]?.get() ?: 0 > 0 || activePortProvider?.invoke(current.extension.id) == true) {
                        cancelPendingSuspend(current)
                    } else {
                        stopHost(current.key, null)
                    }
                }
            }, SUSPEND_GRACE_MS)
        }, EVENT_PAGE_IDLE_MS)
    }

    private fun cancelPendingSuspend(host: HostRuntime) {
        host.suspendToken = System.nanoTime()
        if (host.state == State.SUSPENDING) {
            host.state = State.RUNNING
            messageBus.broadcastMessage(
                host.extension.id,
                null,
                org.json.JSONObject().apply {
                    put("type", "EVENT_DISPATCH")
                    put("eventName", "runtime.onSuspendCanceled")
                    put("data", org.json.JSONObject())
                    put("__targetExtensionId__", host.extension.id)
                    put("__targetContextType__", ExtensionContextType.BACKGROUND.name)
                }
            )
        }
    }

    private fun stopHost(key: BackgroundHostKey, terminalState: State?) {
        val host = hosts.remove(key.canonical()) ?: return
        host.suspendToken = System.nanoTime()
        host.documentStartHandler?.let { runCatching { it.remove() } }
        host.documentStartHandler = null
        host.bridge?.let {
            runCatching { messageBus.unregisterListener(it) }
            runCatching { messageBus.unregisterPortListener(it) }
        }
        keepaliveCounts.remove(key.canonical())
        host.bridge = null

        val webView = host.webView
        host.webView = null
        host.pageReady = false
        host.scriptsLoaded = false
        try {
            if (webView != null) {
                ExtensionWebMessageBridge.removeWebMessageListener(webView, MODERN_BRIDGE_NAME)
                webView.stopLoading()
                webView.webViewClient = WebViewClient()
                webView.webChromeClient = null
                webView.removeJavascriptInterface("SwiftExtensionBridge")
                webView.destroy()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Error destroying MV2 background WebView for ${key.extensionId}", t)
        }
        host.state = terminalState ?: State.SUSPENDED
        completeReady(host, terminalState == null || terminalState == State.SUSPENDED)
    }

    private fun completeReady(host: HostRuntime, ok: Boolean) {
        val callbacks = synchronized(host.pendingReady) {
            val copy = host.pendingReady.toList()
            host.pendingReady.clear()
            copy
        }
        callbacks.forEach { cb -> runCatching { cb(ok) } }
    }

    private fun handleHostCrash(host: HostRuntime, reason: String, throwable: Throwable?) {
        host.crashCount++
        host.state = State.CRASHED
        Log.e(TAG, "MV2 background crash ext=${host.extension.id} reason=$reason", throwable)
        onBackgroundCrash?.invoke(host.extension.id, reason)

        val callbacks = synchronized(host.pendingReady) {
            val copy = host.pendingReady.toList()
            host.pendingReady.clear()
            copy
        }
        callbacks.forEach { cb -> runCatching { cb(false) } }

        val key = host.key
        // Persistent MV2 pages restart automatically with bounded backoff; event pages stay dormant.
        val shouldRestart = host.extension.backgroundSpec.persistent && host.extension.isEnabled
        stopHost(key, State.CRASHED)
        if (shouldRestart) {
            val delay = (CRASH_BACKOFF_BASE_MS shl (host.crashCount - 1).coerceAtMost(4)).coerceAtMost(CRASH_BACKOFF_MAX_MS)
            main.postDelayed({
                val ext = definitions[host.extension.id.lowercase()] ?: return@postDelayed
                if (ext.isEnabled) {
                    startBackgroundWorker(
                        ext = ext,
                        bootstrapScript = host.bootstrapScript,
                        isPrivate = key.isPrivate,
                        permissionManager = host.permissionManager,
                        privateSessionId = key.privateSessionId
                    )
                }
            }, delay)
        }
    }

    private fun readExtensionFile(extensionDir: File, relativePath: String): String {
        val normalized = runCatching { PathSanitizer.sanitizeRelativePath(relativePath) }.getOrElse { return "" }
        val canonicalDir = runCatching { extensionDir.canonicalFile }.getOrElse { return "" }
        val target = runCatching { File(canonicalDir, normalized).canonicalFile }.getOrElse { return "" }
        if (!isDescendant(target, canonicalDir) || !target.isFile) return ""
        return runCatching { target.readText() }.getOrDefault("")
    }

    private fun isDescendant(file: File, root: File): Boolean {
        val rootPath = root.path.trimEnd(File.separatorChar) + File.separator
        return file.path == root.path || file.path.startsWith(rootPath)
    }

    private fun escapeHtml(value: String): String =
        value.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")

    private fun escapeJs(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")

    companion object {
        private const val TAG = "MV2BackgroundRuntime"
        private const val EVENT_PAGE_IDLE_MS = 10_000L
        private const val SUSPEND_GRACE_MS = 5_000L
        private const val CRASH_BACKOFF_BASE_MS = 500L
        private const val CRASH_BACKOFF_MAX_MS = 8_000L
        private const val MODERN_BRIDGE_NAME = "__orionExtensionBridge"
    }
}
