package com.swift.browser.extensionengine.ui

import android.content.Context
import android.webkit.WebView
import com.swift.browser.extensionengine.ExtensionDirectoryResolver
import com.swift.browser.extensionengine.ExtensionEngineApi
import com.swift.browser.extensionengine.ExtensionPageLoader
import com.swift.browser.extensionengine.ExtensionSurfaceType
import com.swift.browser.extensionengine.PathSanitizer
import com.swift.browser.extensionengine.origin.ExtensionUrl

/**
 * Reusable Common Extension UI Host Architecture.
 * Standardizes lifecycle, origin resolution, resource validation, WebView ownership,
 * API bridge binding, and resource cleanup across all extension surfaces.
 */
sealed class ExtensionUiHost(
    val extensionId: String,
    val resourceUrl: String,
    val surfaceType: ExtensionSurfaceType,
    val isPrivate: Boolean = false,
    val tabId: String? = null
) {
    val origin: String get() = "chrome-extension://$extensionId"

    /**
     * Strict security validation pipeline:
     * 1. Validate Extension ID format
     * 2. Validate Origin format / containment
     * 3. Validate Relative Path safety
     * 4. Validate physical resource existence & canonical directory containment
     */
    open fun validateResourceUrl(context: Context): Boolean {
        if (resourceUrl.isBlank()) return false
        if (!PathSanitizer.isSafeExtensionId(extensionId)) return false

        // Validate origin prefix if absolute URL is provided
        if (resourceUrl.contains("://")) {
            val reqOrigin = ExtensionUrl.extractOrigin(resourceUrl) ?: return false
            if (!reqOrigin.equals(origin, ignoreCase = true) && !ExtensionUrl.isWebViewExtensionUrl(resourceUrl)) {
                return false
            }
        }

        val cleanPath = ExtensionUrl.getResourcePath(resourceUrl) ?: return false
        if (!PathSanitizer.isSafeRelativePath(cleanPath)) return false

        val extDir = ExtensionDirectoryResolver.getExtensionDir(context, extensionId)
        val file = ExtensionDirectoryResolver.findFileCaseInsensitive(extDir, cleanPath)
        if (file != null && file.exists() && file.isFile) {
            return PathSanitizer.verifyCanonicalContainment(extDir, file.path)
        }

        // Allow safe HTML entry paths (popup.html, options.html, etc.) even if downloading or virtual
        return cleanPath.endsWith(".html", ignoreCase = true) || 
               cleanPath.endsWith(".htm", ignoreCase = true) || 
               cleanPath.isEmpty() || 
               cleanPath == "/"
    }

    open fun loadSurface(
        webView: WebView,
        api: ExtensionEngineApi,
        onPageFinished: ((String) -> Unit)? = null
    ) {
        if (!validateResourceUrl(webView.context.applicationContext)) {
            android.util.Log.e("ExtensionUiHost", "Security denial: Refusing to load invalid resourceUrl=$resourceUrl for extId=$extensionId")
            val securityErrorHtml = "<html><body style='font-family:sans-serif;padding:16px;'><h3>Security Denial</h3><p>Unauthorized or invalid extension resource path.</p></body></html>"
            val physicalUrl = ExtensionUrl.toWebViewUrl(extensionId, "_orion/security_error.html")
            webView.loadDataWithBaseURL(ExtensionUrl.toWebViewOrigin(extensionId) + "/", securityErrorHtml, "text/html", "UTF-8", physicalUrl)
            return
        }

        ExtensionPageLoader.loadExtensionPage(
            webView = webView,
            extensionId = extensionId,
            pageUrl = resourceUrl,
            api = api,
            isPrivate = isPrivate,
            tabId = tabId,
            onPageFinishedCallback = onPageFinished
        )
    }

    open fun dispose(webView: WebView) {
        ExtensionPageLoader.dispose(webView)
    }

    class ActionHost(
        extensionId: String,
        actionUrl: String = "",
        isPrivate: Boolean = false,
        tabId: String? = null
    ) : ExtensionUiHost(extensionId, actionUrl, ExtensionSurfaceType.ACTION_POPUP, isPrivate, tabId)

    class SidePanelHost(
        extensionId: String,
        panelUrl: String,
        isPrivate: Boolean = false,
        tabId: String? = null
    ) : ExtensionUiHost(extensionId, panelUrl, ExtensionSurfaceType.SIDE_PANEL, isPrivate, tabId)

    class OptionsHost(
        extensionId: String,
        optionsUrl: String,
        isPrivate: Boolean = false
    ) : ExtensionUiHost(extensionId, optionsUrl, ExtensionSurfaceType.OPTIONS_PAGE, isPrivate)

    class ExtensionPageHost(
        extensionId: String,
        pageUrl: String,
        isPrivate: Boolean = false,
        tabId: String? = null
    ) : ExtensionUiHost(extensionId, pageUrl, ExtensionSurfaceType.EXTENSION_PAGE, isPrivate, tabId)

    class ResourceHost(
        extensionId: String,
        resourceUrl: String,
        isPrivate: Boolean = false
    ) : ExtensionUiHost(extensionId, resourceUrl, ExtensionSurfaceType.NONE, isPrivate)
}

