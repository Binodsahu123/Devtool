package com.swift.browser.browserengine

import android.content.Context
import com.swift.browser.audioengine.WebMediaPlaybackController
import android.graphics.Bitmap
import android.net.Uri
import android.os.Message
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.swift.browser.tabengine.model.TabModel

/**
 * Controller and factory responsible for instantiating, configuring, attaching lifecycle callbacks,
 * and maintaining WebViews for tabs in the Swift Browser Engine.
 */
class BrowserWebViewController(
    private val context: Context,
    private val callbackEngine: WebViewCoreCallbackEngine = WebViewCoreCallbackEngine.getInstance()
) {

    data class WebViewConfiguration(
        val isJavaScriptEnabled: Boolean = true,
        val isHardwareAccelerationEnabled: Boolean = true,
        val isDesktopMode: Boolean = false,
        val isIncognito: Boolean = false,
        val isPrivate: Boolean = isIncognito,
        val privateSessionId: String? = null,
        val profileName: String? = null
    )

    data class WebViewCallbackSource(
        val tabId: String,
        val webView: BrowserWebView,
        val generation: Long = webView.generation
    )

    interface WebViewUiCallbacks {
        fun onContextMenuRequested(
            url: String,
            isImage: Boolean,
            isImageLink: Boolean,
            imageUrl: String = "",
            contextMenuState: ContextMenuState? = null
        ) {}
        fun onDownloadRequested(url: String, userAgent: String, contentDisposition: String, mimetype: String, contentLength: Long) {}
        fun onBlockedAdCountIncremented(tabId: String) {}
        fun onPageStarted(source: WebViewCallbackSource, url: String) {}
        fun onPageFinished(source: WebViewCallbackSource, url: String, title: String) {}
        fun onProgressChanged(source: WebViewCallbackSource, progress: Int) {}
        fun onTitleReceived(source: WebViewCallbackSource, title: String) {}
        fun onFaviconReceived(source: WebViewCallbackSource, favicon: Bitmap) {}
        fun onErrorPageRequested(view: WebView?, errorType: String, failingUrl: String) {}
        fun onShowFileChooser(filePathCallback: ValueCallback<Array<Uri>>?, fileChooserParams: WebChromeClient.FileChooserParams?) {}
        fun onShowCustomView(view: View, callback: WebChromeClient.CustomViewCallback) {}
        fun onHideCustomView() {}
        fun onNewTabRequested(url: String, isIncognito: Boolean): WebView? = null
        fun onPendingGeolocationPrompt(origin: String, callback: GeolocationPermissions.Callback) {}
        fun onClearPermissionRequest() {}
        fun getDomain(url: String): String = try { Uri.parse(url).host ?: "" } catch (e: Exception) { "" }
        fun getPermissionStatus(host: String, permission: String): String = "Ask"
        fun logPermissionAction(host: String, permission: String, action: String) {}
    }

    fun createAndConfigureWebView(
        tabId: String,
        config: WebViewConfiguration,
        getTab: (String) -> TabModel?,
        updateTabModel: (String, (TabModel) -> TabModel) -> Unit,
        triggerTabUpdatedEvent: (String, String) -> Unit,
        extensionSetup: (WebView, String) -> Unit,
        injectContentScripts: (WebView, String, String) -> Unit,
        flushCookies: () -> Unit,
        permissionEngine: Any?,
        uiCallbacks: WebViewUiCallbacks
    ): BrowserWebView {
        android.util.Log.d("StartupTiming", "WEBVIEW_CREATE_REQUEST: ${System.currentTimeMillis()}")
        val currentStartupState = StartupCoordinator.instance.startupState.value
        if (currentStartupState == StartupState.FAILED) {
            android.util.Log.e("StartupTiming", "WEBVIEW_CREATE_BLOCKED: ${System.currentTimeMillis()} startup failed")
            val error = StartupCoordinator.instance.startupError.value ?: IllegalStateException("WebView creation blocked because startup failed")
            throw error
        }
        android.util.Log.d("StartupTiming", "WEBVIEW_CREATE_ALLOWED: ${System.currentTimeMillis()}")

        val tabModel = getTab(tabId)
        val isPrivateMode = config.isPrivate || config.isIncognito || tabModel?.isPrivate == true || tabModel?.isIncognito == true

        val webView = BrowserWebView(context, tabId).apply {
            id = View.generateViewId()
            isFocusable = true
            isFocusableInTouchMode = true
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )

            WebMediaPlaybackController.registerWebView(tabId, this)

            this.isPrivate = isPrivateMode

            if (isPrivateMode) {
                val privateModeEngine = com.swift.browser.privatemode.PrivateModeEngineProvider.getEngine(context)
                var pSessionId: String? = config.privateSessionId ?: tabModel?.privateSessionId ?: privateModeEngine.state.value.tabToSessionMap[tabId]
                if (pSessionId == null || privateModeEngine.getSession(pSessionId) == null) {
                    val activeSession = privateModeEngine.getActivePrivateSession() ?: privateModeEngine.openSession()
                    pSessionId = activeSession.sessionId
                }
                val finalSessionId = pSessionId ?: "default_private_session"
                privateModeEngine.attachTab(finalSessionId, tabId)
                val session = privateModeEngine.getSession(finalSessionId)
                val pName = config.profileName ?: session?.profileName ?: "private_profile_$finalSessionId"

                this.privateSessionId = finalSessionId
                this.profileName = pName

                // FIRST: Set private profile & WebViewCompat before settings & bridges
                privateModeEngine.configureWebViewForPrivateMode(this, finalSessionId)
            }

            // THEN: Apply WebSettings
            BrowserWebViewSettings.applySettings(
                webView = this,
                jsEnabled = config.isJavaScriptEnabled,
                hwEnabled = config.isHardwareAccelerationEnabled,
                isDesktop = config.isDesktopMode,
                isIncognito = isPrivateMode
            )

            // Register Web Speech Recognition Bridge
            val speechBridgeInstance = WebSpeechRecognitionBridge(
                webView = this,
                context = context,
                tabId = tabId,
                isIncognito = isPrivateMode
            )
            this.speechBridge = speechBridgeInstance
            addJavascriptInterface(
                speechBridgeInstance,
                WebSpeechRecognitionBridge.INTERFACE_NAME
            )

            // Register the browser-owned page-world frame/document identity bridge before any
            // extension-specific world. All extensions in this WebView then share one canonical
            // documentId/frameId namespace.
            runCatching {
                com.swift.browser.extensionengine.nativecore.WebViewCanonicalFrameBridge.prepare(this, tabId)
            }.onFailure {
                Log.w("BrowserWebViewController", "Canonical WebView frame bridge unavailable", it)
            }

            // Register provider-native extension execution worlds before the first navigation.
            // This is deliberately done while the WebView is still pristine so document-start
            // bridges exist in every future frame.
            runCatching {
                val extensionApi = com.swift.browser.extensionengine.ExtensionEngineApi.getInstance(context)
                val engineImpl = extensionApi.extensionManager.engine as? com.swift.browser.extensionengine.ExtensionEngineImpl
                engineImpl?.registry?.getAllActiveExtensions()?.forEach { ext ->
                    com.swift.browser.extensionengine.nativecore.WebViewExtensionWorldBridge.prepareExtensionWorld(
                        this, tabId, ext.id
                    )
                }
                com.swift.browser.extensionengine.nativecore.NativeExtensionCoreBridge.ensureLoaded()
            }.onFailure {
                Log.w("BrowserWebViewController", "Extension native-world bootstrap unavailable", it)
            }

            // Register Web Notification Bridge
            val notificationBridgeInstance = com.swift.browser.notificationengine.AndroidNotificationBridge(
                context = context,
                webView = this,
                tabId = tabId,
                isIncognito = isPrivateMode
            )
            addJavascriptInterface(
                notificationBridgeInstance,
                com.swift.browser.notificationengine.AndroidNotificationBridge.INTERFACE_NAME
            )

            // Register Web Clipboard Bridge
            val clipboardBridgeInstance = WebClipboardBridge(
                webView = this,
                context = context,
                tabId = tabId,
                isIncognito = isPrivateMode
            )
            addJavascriptInterface(
                clipboardBridgeInstance,
                WebClipboardBridge.INTERFACE_NAME
            )

            // Register Web Screen Capture Bridge
            val screenCaptureBridgeInstance = com.swift.browser.browserengine.screencapture.WebScreenCaptureBridge(
                webView = this,
                context = context,
                tabId = tabId,
                isIncognito = isPrivateMode
            )
            addJavascriptInterface(
                screenCaptureBridgeInstance,
                com.swift.browser.browserengine.screencapture.WebScreenCaptureBridge.INTERFACE_NAME
            )

            // Initialize WebRTC Runtime Manager
            com.swift.browser.browserengine.webrtc.WebRtcRuntimeManager.initialize(context)

            // Register WebRTC Bridge
            val webRtcBridgeInstance = com.swift.browser.browserengine.webrtc.WebRtcBridge(
                webView = this,
                tabId = tabId
            )
            addJavascriptInterface(
                webRtcBridgeInstance,
                com.swift.browser.browserengine.webrtc.WebRtcRuntimeManager.INTERFACE_NAME
            )

            // Initialize Extension Bridge & Content Script Hooks
            try {
                extensionSetup(this, tabId)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to run extensionSetup for tab $tabId", e)
            }

            // Long Click Listener for Context Menu with requestFocusNodeHref for accurate Image + Link detection
            setOnLongClickListener {
                val result = hitTestResult
                val type = result.type
                val extra = result.extra

                val currentPageUrl = url ?: ""
                val isEditable = (type == WebView.HitTestResult.EDIT_TEXT_TYPE)
                val isHitImage = (type == WebView.HitTestResult.IMAGE_TYPE || type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE)
                val isHitLink = (type == WebView.HitTestResult.SRC_ANCHOR_TYPE || type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE)
                val isPhone = (type == WebView.HitTestResult.PHONE_TYPE)
                val isGeo = (type == WebView.HitTestResult.GEO_TYPE)
                val isEmail = (type == WebView.HitTestResult.EMAIL_TYPE)

                val handler = object : android.os.Handler(android.os.Looper.getMainLooper()) {
                    override fun handleMessage(msg: Message) {
                        val href = msg.data.getString("url")
                        val src = msg.data.getString("src")

                        val hasHref = !href.isNullOrBlank()
                        val hasSrc = !src.isNullOrBlank()

                        val isImageLink = (isHitImage && isHitLink) || (hasHref && (hasSrc || isHitImage))
                        val isImage = !isImageLink && (isHitImage || hasSrc)
                        val isLink = isHitLink || hasHref

                        val realLinkUrl = when {
                            hasHref -> href
                            isHitLink && !extra.isNullOrBlank() -> extra
                            else -> null
                        }

                        val realSrcUrl = when {
                            hasSrc -> src
                            isHitImage && !extra.isNullOrBlank() -> extra
                            else -> null
                        }

                        val isVideo = realSrcUrl?.let { it.endsWith(".mp4", ignoreCase = true) || it.endsWith(".webm", ignoreCase = true) || it.endsWith(".mkv", ignoreCase = true) || it.endsWith(".mov", ignoreCase = true) } == true
                        val isAudio = realSrcUrl?.let { it.endsWith(".mp3", ignoreCase = true) || it.endsWith(".ogg", ignoreCase = true) || it.endsWith(".wav", ignoreCase = true) || it.endsWith(".aac", ignoreCase = true) } == true
                        val mediaType = when {
                            isImage || isImageLink -> "image"
                            isVideo -> "video"
                            isAudio -> "audio"
                            else -> null
                        }

                        evaluateJavascript("window.getSelection().toString()") { selTextRaw ->
                            val selectionText = selTextRaw?.removePrefix("\"")?.removeSuffix("\"")?.replace("\\n", "\n")?.takeIf { it.isNotBlank() }
                            val isSelection = !selectionText.isNullOrBlank()

                            val menuState = ContextMenuState(
                                show = true,
                                pageUrl = currentPageUrl,
                                linkUrl = realLinkUrl,
                                srcUrl = realSrcUrl,
                                selectionText = selectionText,
                                isEditable = isEditable,
                                isImage = isImage,
                                isVideo = isVideo,
                                isAudio = isAudio,
                                isLink = isLink,
                                isSelection = isSelection,
                                isImageLink = isImageLink,
                                mediaType = mediaType,
                                tabId = tabId
                            )

                            uiCallbacks.onContextMenuRequested(
                                url = realLinkUrl ?: currentPageUrl,
                                isImage = isImage,
                                isImageLink = isImageLink,
                                imageUrl = realSrcUrl ?: "",
                                contextMenuState = menuState
                            )
                        }
                    }
                }

                val msg = handler.obtainMessage()
                requestFocusNodeHref(msg)
                true
            }

            // Download Listener
            setDownloadListener { url, userAgent, contentDisposition, mimetype, contentLength ->
                uiCallbacks.onDownloadRequested(
                    url = url,
                    userAgent = userAgent,
                    contentDisposition = contentDisposition ?: "",
                    mimetype = mimetype,
                    contentLength = contentLength
                )
            }

            // Core WebViewClient
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    val result = callbackEngine.shouldInterceptRequest(
                        context = context,
                        view = view,
                        request = request,
                        currentDocUrl = getTab(tabId)?.url,
                        onAdBlocked = { uiCallbacks.onBlockedAdCountIncremented(tabId) }
                    )
                    return result ?: super.shouldInterceptRequest(view, request)
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    if (view != null && url != null) {
                        runCatching {
                            com.swift.browser.extensionengine.nativecore.WebViewExtensionWorldBridge.onNavigation(view, tabId, url)
                        }
                        try {
                            val isPrivate = getTab(tabId)?.isPrivate ?: false
                            com.swift.browser.extensionengine.ExtensionEngineApi.getInstance(context).onPageStarted(view, url, tabId, isPrivate)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                    callbackEngine.onPageStarted(
                        context = context,
                        view = view,
                        url = url,
                        favicon = favicon,
                        tabId = tabId,
                        getTab = getTab,
                        updateTabModel = updateTabModel,
                        triggerTabUpdatedEvent = triggerTabUpdatedEvent
                    )
                    if (url != null) {
                        (view as? BrowserWebView)?.speechBridge?.onPageStarted(url)
                        val source = (view as? BrowserWebView)?.let { WebViewCallbackSource(tabId, it) }
                        if (source != null) {
                            uiCallbacks.onPageStarted(source, url)
                        }
                        com.swift.browser.browserengine.webrtc.GenericWebMediaCompatibilityEngine.invalidateSession(tabId)
                    }
                }

                override fun onPageCommitVisible(view: WebView?, url: String?) {
                    super.onPageCommitVisible(view, url)
                    if (view != null && url != null) {
                        try {
                            val isPrivate = getTab(tabId)?.isPrivate ?: false
                            com.swift.browser.extensionengine.ExtensionEngineApi.getInstance(context).onPageCommitVisible(view, url, tabId, isPrivate)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                    val isDesktop = try { com.swift.browser.desktopengine.api.DesktopEngineProvider.api.isDesktopMode(android.net.Uri.parse(url).host ?: "") } catch(e: Exception) { false }
                    callbackEngine.onPageCommitVisible(
                        context = context,
                        view = view,
                        url = url,
                        tabId = tabId,
                        isDesktopMode = isDesktop,
                        extensionSetup = extensionSetup,
                        injectContentScripts = injectContentScripts
                    )
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    if (view != null && url != null) {
                        try {
                            val isPrivate = getTab(tabId)?.isPrivate ?: false
                            com.swift.browser.extensionengine.ExtensionEngineApi.getInstance(context).onPageFinished(view, url, tabId, isPrivate)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                    WebResourceInterceptionCoordinator.onMainFrameCompleted(context, tabId, url ?: "")
                    callbackEngine.onPageFinished(
                        context = context,
                        view = view,
                        url = url,
                        tabId = tabId,
                        getTab = getTab,
                        updateTabModel = updateTabModel,
                        flushCookies = flushCookies,
                        injectContentScripts = injectContentScripts
                    )
                    if (url != null) {
                        val source = (view as? BrowserWebView)?.let { WebViewCallbackSource(tabId, it) }
                        if (source != null) {
                            uiCallbacks.onPageFinished(source, url, view?.title ?: "")
                        }
                    }
                }

                override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                    super.doUpdateVisitedHistory(view, url, isReload)
                    if (view != null && url != null) {
                        try {
                            val isPrivate = getTab(tabId)?.isPrivate ?: false
                            com.swift.browser.extensionengine.ExtensionEngineApi.getInstance(context).doUpdateVisitedHistory(view, url, isReload, tabId, isPrivate)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                    if (url != null && !url.startsWith("swift://") && !url.startsWith("about:") && !url.startsWith("file://")) {
                        updateTabModel(tabId) { it.copy(url = url) }
                        val source = (view as? BrowserWebView)?.let { WebViewCallbackSource(tabId, it) }
                        if (source != null) {
                            uiCallbacks.onPageStarted(source, url) // Update URL bar
                        }
                    }
                }

                override fun onReceivedSslError(
                    view: WebView?,
                    handler: SslErrorHandler?,
                    error: android.net.http.SslError?
                ) {
                    callbackEngine.onReceivedSslError(
                        view = view,
                        handler = handler,
                        error = error,
                        loadErrorPage = { v, type, failingUrl ->
                            uiCallbacks.onErrorPageRequested(v, type, failingUrl)
                        }
                    )
                }

                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    // WebView cannot synthesize HTTP 3xx from shouldInterceptRequest.
                    // For top-level extension redirects/upgrades, take navigation control here.
                    when (val extensionDecision = WebResourceInterceptionCoordinator.resolveMainFrameNavigationDecision(
                        context = context,
                        view = view,
                        request = request,
                        currentDocUrl = getTab(tabId)?.url
                    )) {
                        is WebResourceInterceptionCoordinator.MainFrameNavigationDecision.Blocked -> {
                            return true
                        }
                        is WebResourceInterceptionCoordinator.MainFrameNavigationDecision.Redirect -> {
                            if (view != null) {
                                BrowserNavigationApi.navigate(
                                    NavigationRequest(
                                        tabId = tabId,
                                        url = extensionDecision.url,
                                        source = NavigationSource.RECOVERY,
                                        applyDesktopPolicy = false,
                                        webView = view
                                    )
                                )
                                return true
                            }
                        }
                        WebResourceInterceptionCoordinator.MainFrameNavigationDecision.Continue -> Unit
                    }
                    return callbackEngine.shouldOverrideUrlLoading(
                        view = view,
                        request = request,
                        isUASwitchPending = false,
                        lastManualLoadUrl = getTab(tabId)?.url ?: "",
                        safeLoadUrl = { v, loadUrl ->
                            BrowserNavigationApi.navigate(
                                NavigationRequest(
                                    tabId = tabId,
                                    url = loadUrl,
                                    source = NavigationSource.RECOVERY,
                                    applyDesktopPolicy = false,
                                    webView = v
                                )
                            )
                        },
                        tabId = tabId
                    )
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?
                ) {
                    super.onReceivedError(view, request, error)
                    if (request?.isForMainFrame == true) {
                        try {
                            val isPrivate = getTab(tabId)?.isPrivate ?: false
                            val failingUrl = request.url?.toString() ?: ""
                            val errDesc = error?.description?.toString() ?: "net::ERR_FAILED"
                            val errCode = error?.errorCode ?: -1
                            WebResourceInterceptionCoordinator.onMainFrameError(context, tabId, failingUrl, errDesc)
                            com.swift.browser.extensionengine.ExtensionEngineApi.getInstance(context).onReceivedError(view, failingUrl, errCode, errDesc, tabId, isPrivate)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                    val isMainFrame = request?.isForMainFrame ?: false
                    val currentTab = getTab(tabId)
                    if (isMainFrame && currentTab?.hasLoadedSuccessfully != true) {
                        val failingUrl = request?.url?.toString() ?: ""
                        if (!com.swift.browser.extensionengine.origin.ExtensionUrl.isExtensionUrl(failingUrl)) {
                            uiCallbacks.onErrorPageRequested(view, "offline", failingUrl)
                        }
                    }
                }

                override fun onReceivedHttpError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    errorResponse: WebResourceResponse?
                ) {
                    super.onReceivedHttpError(view, request, errorResponse)
                    val failingUrl = request?.url?.toString() ?: ""
                    val statusCode = errorResponse?.statusCode ?: 404
                    val reason = errorResponse?.reasonPhrase ?: "HTTP Error"
                    try {
                        val isPrivate = getTab(tabId)?.isPrivate ?: false
                        if (request?.isForMainFrame == true) {
                            WebResourceInterceptionCoordinator.onMainFrameCompleted(context, tabId, failingUrl, statusCode)
                        }
                        com.swift.browser.extensionengine.ExtensionEngineApi.getInstance(context).onReceivedHttpError(
                            webView = view,
                            url = failingUrl,
                            statusCode = statusCode,
                            reasonPhrase = reason,
                            tabId = tabId,
                            isPrivate = isPrivate
                        )
                    } catch (e: Throwable) {
                        Log.e("BrowserWebViewController", "Error notifying extension API of HTTP error: ${e.message}", e)
                    }
                    val isMainFrame = request?.isForMainFrame ?: false
                    if (isMainFrame && statusCode == 404) {
                        if (!com.swift.browser.extensionengine.origin.ExtensionUrl.isExtensionUrl(failingUrl)) {
                            uiCallbacks.onErrorPageRequested(view, "404", failingUrl)
                        }
                    }
                }

                override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                    return callbackEngine.onRenderProcessGone(
                        view = view,
                        detail = detail,
                        tabId = tabId,
                        removeWebView = { /* Handled via tab model update */ },
                        updateTabModel = updateTabModel
                    )
                }
            }

            // Core WebChromeClient
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                    return callbackEngine.onConsoleMessage(consoleMessage)
                }

                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    super.onProgressChanged(view, newProgress)
                    updateTabModel(tabId) {
                        it.copy(progress = newProgress, isLoading = newProgress < 100)
                    }
                    val source = (view as? BrowserWebView)?.let { WebViewCallbackSource(tabId, it) }
                    if (source != null) {
                        uiCallbacks.onProgressChanged(source, newProgress)
                    }
                }

                override fun onReceivedTitle(view: WebView?, titleStr: String?) {
                    super.onReceivedTitle(view, titleStr)
                    val currentUrl = view?.url ?: ""
                    val finalTitle = if (currentUrl == "swift://newtab" || currentUrl == "swift://newtab-incognito") {
                        val isTabIncognito = getTab(tabId)?.isIncognito == true
                        if (isTabIncognito) "Incognito Tab" else "New Tab"
                    } else {
                        (titleStr ?: "").ifBlank { currentUrl }
                    }
                    updateTabModel(tabId) { it.copy(title = finalTitle) }
                    val source = (view as? BrowserWebView)?.let { WebViewCallbackSource(tabId, it) }
                    if (source != null) {
                        uiCallbacks.onTitleReceived(source, finalTitle)
                    }
                }

                override fun onReceivedIcon(view: WebView?, icon: Bitmap?) {
                    super.onReceivedIcon(view, icon)
                    if (icon != null) {
                        updateTabModel(tabId) { it.copy(favicon = icon) }
                        val source = (view as? BrowserWebView)?.let { WebViewCallbackSource(tabId, it) }
                        if (source != null) {
                            uiCallbacks.onFaviconReceived(source, icon)
                        }
                    }
                }

                override fun onGeolocationPermissionsShowPrompt(
                    origin: String?,
                    callback: GeolocationPermissions.Callback?
                ) {
                    val tabModel = getTab(tabId)
                    val isIncognito = tabModel?.isIncognito ?: false
                    val tabUrl = tabModel?.url ?: ""
                    callbackEngine.onGeolocationPermissionsShowPrompt(
                        origin = origin,
                        callback = callback,
                        context = context,
                        tabId = tabId,
                        url = tabUrl,
                        isIncognito = isIncognito
                    )
                }

                override fun onPermissionRequest(request: PermissionRequest?) {
                    callbackEngine.onPermissionRequest(
                        request = request,
                        context = context,
                        tabId = tabId,
                        isIncognito = getTab(tabId)?.isIncognito == true,
                        url = getTab(tabId)?.url ?: ""
                    )
                }

                override fun onPermissionRequestCanceled(request: PermissionRequest?) {
                    callbackEngine.onPermissionRequestCanceled(
                        request = request,
                        clearPendingRequest = { uiCallbacks.onClearPermissionRequest() }
                    )
                }

                override fun onCreateWindow(
                    view: WebView?,
                    isDialog: Boolean,
                    isUserGesture: Boolean,
                    resultMsg: Message?
                ): Boolean {
                    val isTabIncognito = getTab(tabId)?.isIncognito == true
                    return callbackEngine.onCreateWindow(
                        view = view,
                        isDialog = isDialog,
                        isUserGesture = isUserGesture,
                        resultMsg = resultMsg,
                        tabId = tabId,
                        isIncognito = isTabIncognito,
                        createAndAddTabWebView = { ctx, newTabId ->
                            uiCallbacks.onNewTabRequested("about:blank", isTabIncognito)
                        }
                    )
                }

                override fun onShowFileChooser(
                    webView: WebView?,
                    filePathCallback: ValueCallback<Array<Uri>>?,
                    fileChooserParams: FileChooserParams?
                ): Boolean {
                    val isTabIncognito = getTab(tabId)?.isIncognito == true
                    return callbackEngine.onShowFileChooser(
                        webView = webView,
                        filePathCallback = filePathCallback,
                        fileChooserParams = fileChooserParams,
                        tabId = tabId,
                        isIncognito = isTabIncognito,
                        setFileChooserCallback = { cb, params ->
                            uiCallbacks.onShowFileChooser(cb, params)
                        }
                    )
                }

                override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                    if (view != null && callback != null) {
                        val isIncognito = getTab(tabId)?.isIncognito == true
                        val currentUrl = getTab(tabId)?.url ?: ""
                        val params = com.swift.browser.permissionengine.FullscreenRequestParams(
                            origin = currentUrl,
                            tabId = tabId,
                            userGesture = null,
                            isIncognito = isIncognito
                        )
                        com.swift.browser.permissionengine.PermissionEngineApi.handleFullscreenRequest(context, params) { isAllowed ->
                            if (isAllowed) {
                                uiCallbacks.onShowCustomView(view, callback)
                            } else {
                                try { callback.onCustomViewHidden() } catch (_: Exception) {}
                            }
                        }
                    }
                }

                override fun onHideCustomView() {
                    uiCallbacks.onHideCustomView()
                }
            }
        }

        if (firstWebViewLogged.compareAndSet(false, true)) {
            android.util.Log.d("StartupTiming", "ORION_FIRST_WEBVIEW_READY: ${System.currentTimeMillis()}")
        }
        return webView
    }

    companion object {
        private const val TAG = "BrowserWebViewController"
        private val firstWebViewLogged = java.util.concurrent.atomic.AtomicBoolean(false)
    }
}
