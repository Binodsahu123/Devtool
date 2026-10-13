package com.swift.browser.browserengine

import android.webkit.WebSettings
import android.webkit.WebView

/**
 * Utility class to configure WebView instances with security, DOM storage,
 * and remote debugging capabilities required for Developer Tools and modern web apps.
 */
object WebViewConfigurator {

    /**
     * Applies essential WebSettings and debugging flags to the given WebView instance.
     */
    fun configure(webView: WebView?) {
        if (webView == null) return

        // WebContents debugging is enabled on demand by DeveloperToolsEngine only.
        // Do not expose every browser WebView to a debugging socket by default.

        // Configure WebSettings for DOM storage, database, file access, and popup permissions
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            javaScriptCanOpenWindowsAutomatically = true

            // Universal access for local file URLs
            allowFileAccessFromFileURLs = true
            allowUniversalAccessFromFileURLs = true

            // Allow mixed content on secure connections for DevTools asset loading
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }
    }
}
