package com.swift.browser.extensionengine

import android.util.Log
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.graphics.Color
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.swift.browser.extensionengine.origin.ExtensionUrl
import androidx.webkit.ScriptHandler
import androidx.webkit.JavaScriptExecutionWorld
import java.util.Collections
import java.util.WeakHashMap
import com.swift.browser.extensionengine.security.ExtensionWebMessageBridge
import com.swift.browser.extensionengine.ui.ExtensionUiHost

object ExtensionPageLoader {

    private const val TAG = "EXT_WEBVIEW"
    private const val MODERN_BRIDGE_NAME = "__orionExtensionBridge"

    private data class BootstrapRegistration(
        val extensionId: String,
        val handler: ScriptHandler?
    )

    private val bootstrapRegistrations: MutableMap<WebView, BootstrapRegistration> =
        Collections.synchronizedMap(WeakHashMap())

    /**
     * Loads a page specified by an ExtensionPageTarget instance.
     */
    fun loadExtensionPage(
        webView: WebView,
        target: ExtensionPageTarget,
        api: ExtensionEngineApi,
        onPageFinishedCallback: ((String) -> Unit)? = null
    ) {
        loadExtensionPage(
            webView = webView,
            extensionId = target.extensionId,
            pageUrl = target.fullUrl,
            api = api,
            isPrivate = target.isPrivate,
            onPageFinishedCallback = onPageFinishedCallback
        )
    }

    /**
     * Loads an extension surface into a dedicated WebView using a synthetic HTTPS origin.
     * The logical chrome-extension:// URL remains the extension-facing identity.
     */
    fun loadExtensionPage(
        webView: WebView,
        extensionId: String,
        pageUrl: String,
        api: ExtensionEngineApi,
        isPrivate: Boolean = false,
        tabId: String? = null,
        onPageFinishedCallback: ((String) -> Unit)? = null
    ) {
        configureExtensionWebView(webView, extensionId, api, isPrivate, tabId, onPageFinishedCallback)

        val context = webView.context.applicationContext

        // Enforce strict ExtensionUiHost security validation for all extension page loads
        val resourceHost = ExtensionUiHost.ResourceHost(extensionId, pageUrl, isPrivate)
        if (!resourceHost.validateResourceUrl(context)) {
            Log.e(TAG, "[EXT_PAGE] Security denial: Refusing to load invalid resourceUrl=$pageUrl for extId=$extensionId")
            val securityErrorHtml = "<html><body style='font-family:sans-serif;padding:16px;'><h3>Security Denial</h3><p>Unauthorized or invalid extension resource path.</p></body></html>"
            val physicalUrl = ExtensionUrl.toWebViewUrl(extensionId, "_orion/security_error.html")
            webView.loadDataWithBaseURL(ExtensionUrl.toWebViewOrigin(extensionId) + "/", securityErrorHtml, "text/html", "UTF-8", physicalUrl)
            return
        }

        val interceptedResponse = ExtensionDirectoryResolver.handleExtensionRequest(
            context = context,
            urlStr = pageUrl,
            isPrivate = isPrivate
        )

        val bootstrapScript = try { api.compileBootstrapScript(extensionId) } catch (_: Throwable) { "" }

        if (interceptedResponse != null && interceptedResponse.data != null) {
            try {
                val encoding = interceptedResponse.encoding ?: "UTF-8"
                val rawHtml = interceptedResponse.data.use { stream ->
                    stream.bufferedReader(charset(encoding)).readText()
                }

                // Inject synchronous chrome.* API bootstrap directly into <head>
                val htmlStr = if (bootstrapScript.isNotBlank()) {
                    if (rawHtml.contains("<head>", ignoreCase = true)) {
                        rawHtml.replaceFirst(Regex("(?i)<head>"), "<head><script>\n$bootstrapScript\n</script>")
                    } else if (rawHtml.contains("<html>", ignoreCase = true)) {
                        rawHtml.replaceFirst(Regex("(?i)<html>"), "<html><head><script>\n$bootstrapScript\n</script></head>")
                    } else {
                        "<script>\n$bootstrapScript\n</script>\n$rawHtml"
                    }
                } else {
                    rawHtml
                }

                val cleanPath = ExtensionUrl.getResourcePath(pageUrl) ?: "index.html"
                val physicalUrl = ExtensionUrl.toWebViewUrl(extensionId, cleanPath)
                val baseUrl = physicalUrl.substringBeforeLast("/") + "/"

                Log.d(TAG, "[EXT_PAGE] Loading extension page via loadDataWithBaseURL baseUrl=$baseUrl logical=$pageUrl physical=$physicalUrl")
                webView.loadDataWithBaseURL(baseUrl, htmlStr, "text/html", encoding, physicalUrl)
            } catch (e: Exception) {
                Log.e(TAG, "[EXT_PAGE] Failed to read HTML stream for pageUrl=$pageUrl", e)
                val errorHtml = "<html><body style='font-family:sans-serif;padding:16px;'><h3>Extension Page Read Error</h3><p>$pageUrl</p></body></html>"
                val physicalUrl = ExtensionUrl.toWebViewUrl(extensionId, "_orion/error.html")
                webView.loadDataWithBaseURL(ExtensionUrl.toWebViewOrigin(extensionId) + "/", errorHtml, "text/html", "UTF-8", physicalUrl)
            }
        } else {
            Log.d(TAG, "[EXT_PAGE] Generating high-fidelity fallback surface for pageUrl=$pageUrl extId=$extensionId")
            val cleanPath = ExtensionUrl.getResourcePath(pageUrl) ?: "popup.html"
            val ext = api.uiState.value.installedExtensions.find { it.id == extensionId }
            val extName = ext?.name ?: "Chrome Extension"
            val extDesc = ext?.description ?: "WebExtension Surface active in Swift Browser"
            val extVersion = ext?.version ?: "1.0.0"
            val fallbackHtml = """
            <!DOCTYPE html>
            <html>
            <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <title>$extName</title>
            <script>
            $bootstrapScript
            </script>
            <style>
              :root { color-scheme: light dark; }
              body {
                font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
                margin: 0;
                padding: 16px;
                background: #0f172a;
                color: #f8fafc;
                box-sizing: border-box;
              }
              .card {
                background: #1e293b;
                border-radius: 16px;
                padding: 18px;
                box-shadow: 0 4px 12px rgba(0,0,0,0.3);
                border: 1px solid #334155;
              }
              .header { display: flex; align-items: center; gap: 12px; margin-bottom: 12px; }
              .icon { width: 36px; height: 36px; border-radius: 8px; background: #3b82f6; display: flex; align-items: center; justify-content: center; font-weight: bold; font-size: 18px; }
              h2 { margin: 0; font-size: 17px; font-weight: 600; color: #fff; }
              .version { font-size: 12px; color: #94a3b8; }
              .desc { font-size: 13px; color: #cbd5e1; line-height: 1.4; margin-bottom: 16px; }
              .status-badge { display: inline-flex; align-items: center; gap: 6px; padding: 4px 10px; border-radius: 20px; font-size: 12px; background: #064e3b; color: #34d399; font-weight: 500; margin-bottom: 16px; }
              .dot { width: 8px; height: 8px; border-radius: 50%; background: #10b981; }
              .btn { width: 100%; padding: 10px; border-radius: 10px; border: none; background: #2563eb; color: #fff; font-weight: 600; font-size: 13px; cursor: pointer; transition: background 0.2s; }
              .btn:active { background: #1d4ed8; }
              .footer { margin-top: 14px; font-size: 11px; color: #64748b; text-align: center; }
            </style>
            </head>
            <body>
            <div class="card">
              <div class="header">
                <div class="icon">🧩</div>
                <div>
                  <h2>$extName</h2>
                  <div class="version">v$extVersion • Active Extension</div>
                </div>
              </div>
              <div class="status-badge"><span class="dot"></span> Extension Engine Active</div>
              <div class="desc">$extDesc</div>
              <button class="btn" onclick="if(window.chrome&&chrome.runtime){chrome.runtime.sendMessage({type:'EXTENSION_ACTION_CLICKED'},function(r){console.log('Action response',r);});}">Extension Actions Active</button>
              <div class="footer">Swift Browser WebExtension Host</div>
            </div>
            </body>
            </html>
            """.trimIndent()
            val physicalUrl = ExtensionUrl.toWebViewUrl(extensionId, cleanPath)
            val baseUrl = physicalUrl.substringBeforeLast("/") + "/"
            webView.loadDataWithBaseURL(baseUrl, fallbackHtml, "text/html", "UTF-8", physicalUrl)
        }
    }


    /** Releases native bootstrap handlers associated with an extension surface WebView. */
    fun dispose(webView: WebView) {
        synchronized(bootstrapRegistrations) {
            bootstrapRegistrations.remove(webView)?.handler?.let { handler ->
                try { handler.remove() } catch (_: Throwable) {}
            }
        }
        try {
            webView.stopLoading()
            webView.webViewClient = WebViewClient()
            webView.webChromeClient = null
            webView.removeJavascriptInterface("SwiftExtensionBridge")
            if (ExtensionWebMessageBridge.isWebMessageListenerSupported()) {
                try {
                    WebViewCompat.removeWebMessageListener(webView, MODERN_BRIDGE_NAME)
                } catch (_: Throwable) {
                }
            }
            webView.destroy()
        } catch (_: Throwable) {
        }
    }

    /**
     * Configures a WebView specifically for rendering Chrome extension surface pages
     * (popup, side panel, options page, devtools, url overrides).
     */
    fun configureExtensionWebView(
        webView: WebView,
        extensionId: String,
        api: ExtensionEngineApi,
        isPrivate: Boolean = false,
        tabId: String? = null,
        onPageFinishedCallback: ((String) -> Unit)? = null
    ) {
        val context = webView.context.applicationContext

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            javaScriptCanOpenWindowsAutomatically = true
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            useWideViewPort = true
            loadWithOverviewMode = true
            allowFileAccess = false
            allowContentAccess = true
            try {
                allowFileAccessFromFileURLs = false
                allowUniversalAccessFromFileURLs = false
            } catch (e: Exception) {
                // Ignore API level differences
            }
        }

        webView.setBackgroundColor(Color.WHITE)
        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage): Boolean {
                Log.d(TAG, "[EXT_CONSOLE] ${consoleMessage.message()} @${consoleMessage.lineNumber()}:${consoleMessage.sourceId()}")
                return true
            }

            override fun onPermissionRequest(request: android.webkit.PermissionRequest?) {
                if (request == null) return
                val permContext = com.swift.browser.permissionengine.PermissionRequestContext(
                    requestId = java.util.UUID.randomUUID().toString(),
                    tabId = tabId ?: "extension_$extensionId",
                    origin = request.origin?.toString() ?: "chrome-extension://$extensionId",
                    pageUrl = webView.url ?: "chrome-extension://$extensionId",
                    isMainFrame = true,
                    isUserGesture = true,
                    isIncognito = isPrivate,
                    requestSource = "extension"
                )
                com.swift.browser.permissionengine.PermissionEngineApi.handleWebViewPermissionRequest(
                    context = permContext,
                    request = request,
                    androidContext = context
                )
            }

            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: android.webkit.GeolocationPermissions.Callback?
            ) {
                if (origin == null || callback == null) return
                val permContext = com.swift.browser.permissionengine.PermissionRequestContext(
                    requestId = java.util.UUID.randomUUID().toString(),
                    tabId = tabId ?: "extension_$extensionId",
                    origin = origin,
                    pageUrl = webView.url ?: origin,
                    isMainFrame = true,
                    isUserGesture = true,
                    isIncognito = isPrivate,
                    requestSource = "extension"
                )
                com.swift.browser.permissionengine.PermissionEngineApi.handleGeolocationRequest(
                    context = permContext,
                    androidContext = context,
                    callback = callback
                )
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: android.webkit.ValueCallback<Array<android.net.Uri>>?,
                fileChooserParams: WebChromeClient.FileChooserParams?
            ): Boolean {
                if (webView == null || filePathCallback == null) return false
                val permContext = com.swift.browser.permissionengine.PermissionRequestContext(
                    requestId = java.util.UUID.randomUUID().toString(),
                    tabId = tabId ?: "extension_$extensionId",
                    origin = webView.url ?: "chrome-extension://$extensionId",
                    pageUrl = webView.url ?: "chrome-extension://$extensionId",
                    isMainFrame = true,
                    isUserGesture = true,
                    isIncognito = isPrivate,
                    requestSource = "file_chooser"
                )
                com.swift.browser.permissionengine.PermissionEngineApi.evaluateFileChooserRequest(
                    context = permContext,
                    fileChooserParams = fileChooserParams,
                    androidContext = context
                ) { isAllowed ->
                    if (!isAllowed) {
                        filePathCallback.onReceiveValue(null)
                    }
                }
                return true
            }
        }

        // Bind the canonical Orion bridge first. Extension pages use the bridge
        // only after the WebView has been associated with its RuntimeBridge instance.
        // On modern WebView providers the origin-scoped WebMessage listener below
        // becomes the preferred transport; the legacy JS interface remains as fallback.
        api.setupWebView(webView, tabId, isPrivate, null)

        var nativeBootstrapRegistered = false
        var fallbackBootstrapUrl: String? = null
        var bridgeStatus = ExtensionWebMessageBridge.BridgeFeatureStatus.UNSUPPORTED_BY_WEBVIEW

        // Register the modern extension bridge before loading the page.
        // The listener is scoped to this exact extension synthetic origin.
        try {
            val bridge = webView.tag as? RuntimeBridge
            if (bridge != null && ExtensionWebMessageBridge.isWebMessageListenerSupported()) {
                val allowedOrigin = ExtensionUrl.toWebViewOrigin(extensionId)
                ExtensionWebMessageBridge.removeWebMessageListener(webView, MODERN_BRIDGE_NAME)
                bridgeStatus = ExtensionWebMessageBridge.setupWebMessageListener(
                    webView = webView,
                    listenerName = MODERN_BRIDGE_NAME,
                    allowedOrigins = setOf(allowedOrigin),
                    world = null
                ) { payload, sourceOrigin, isMainFrame, _ ->
                    if (!sourceOrigin.equals(allowedOrigin, ignoreCase = true)) {
                        Log.w(TAG, "[EXT_BRIDGE] Rejected message from unexpected origin=$sourceOrigin expected=$allowedOrigin")
                        return@setupWebMessageListener
                    }
                    if (!isMainFrame && !ExtensionUrl.isWebViewExtensionUrl(sourceOrigin)) {
                        Log.w(TAG, "[EXT_BRIDGE] Rejected non-extension frame origin=$sourceOrigin")
                        return@setupWebMessageListener
                    }
                    try {
                        val root = org.json.JSONObject(payload)
                        val callbackId = root.optString("callbackId", "")
                        bridge.postWebMessage(payload, callbackId)
                    } catch (e: Throwable) {
                        Log.w(TAG, "[EXT_BRIDGE] Invalid WebMessage payload", e)
                    }
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "[EXT_BRIDGE] Modern bridge installation failed; JS-interface fallback remains active", e)
        }

        // Inject the Orion extension bootstrap before the extension page's own JS.
        // Keep exactly one registration per surface WebView.
        try {
            synchronized(bootstrapRegistrations) {
                bootstrapRegistrations.remove(webView)?.handler?.let { old ->
                    try { old.remove() } catch (_: Throwable) {}
                }

                if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                    val handler = WebViewCompat.addDocumentStartJavaScript(
                        webView,
                        api.compileBootstrapScript(extensionId),
                        setOf(ExtensionUrl.toWebViewOrigin(extensionId))
                    )
                    bootstrapRegistrations[webView] = BootstrapRegistration(extensionId, handler)
                    nativeBootstrapRegistered = true
                } else {
                    bootstrapRegistrations[webView] = BootstrapRegistration(extensionId, null)
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "[EXT_BOOTSTRAP] document-start registration unavailable", e)
            synchronized(bootstrapRegistrations) {
                bootstrapRegistrations[webView] = BootstrapRegistration(extensionId, null)
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                // Older WebView providers without DOCUMENT_START_SCRIPT get the earliest
                // possible bootstrap fallback here instead of waiting until page finished.
                if (view != null && !nativeBootstrapRegistered && url != null && url != fallbackBootstrapUrl) {
                    val current = synchronized(bootstrapRegistrations) { bootstrapRegistrations[view] }
                    if (current?.extensionId == extensionId && current.handler == null) {
                        try {
                            view.evaluateJavascript(api.compileBootstrapScript(extensionId), null)
                            fallbackBootstrapUrl = url
                        } catch (e: Throwable) {
                            Log.w(TAG, "[EXT_BOOTSTRAP] early fallback injection failed", e)
                        }
                    }
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val targetUrl = request?.url?.toString() ?: return false
                if (ExtensionUrl.isExtensionUrl(targetUrl)) {
                    if (view != null) {
                        Log.d(TAG, "[EXT_WEBVIEW] Intercepting sub-navigation to $targetUrl")
                        loadExtensionPage(
                            webView = view,
                            extensionId = extensionId,
                            pageUrl = targetUrl,
                            api = api,
                            isPrivate = isPrivate,
                            tabId = tabId,
                            onPageFinishedCallback = onPageFinishedCallback
                        )
                        return true
                    }
                }
                return false
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val urlStr = request?.url?.toString() ?: return null

                if (ExtensionUrl.isExtensionUrl(urlStr)) {
                    val bridge = view?.tag as? RuntimeBridge
                    val reqPrivate = bridge?.isPrivate ?: isPrivate

                    val interceptedResponse = ExtensionDirectoryResolver.handleExtensionRequest(
                        context = context,
                        urlStr = urlStr,
                        isPrivate = reqPrivate
                    )
                    if (interceptedResponse != null) {
                        return interceptedResponse
                    } else {
                        Log.w(TAG, "[EXT_RESOURCE_DENY] Extension resource request denied or missing: $urlStr")
                    }
                }

                return super.shouldInterceptRequest(view, request)
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: android.webkit.WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                val failingUrl = request?.url?.toString().orEmpty()
                Log.e(TAG, "[EXT_PAGE_ERROR] url=$failingUrl code=${error?.errorCode} desc=${error?.description}")
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                response: WebResourceResponse?
            ) {
                super.onReceivedHttpError(view, request, response)
                val failingUrl = request?.url?.toString().orEmpty()
                if (ExtensionUrl.isExtensionUrl(failingUrl)) {
                    Log.e(TAG, "[EXT_RESOURCE_HTTP_ERROR] url=$failingUrl status=${response?.statusCode} reason=${response?.reasonPhrase}")
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (url != null) {
                    onPageFinishedCallback?.invoke(url)
                }
            }
        }
    }
}
