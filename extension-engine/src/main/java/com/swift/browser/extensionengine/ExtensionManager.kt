package com.swift.browser.extensionengine

import android.content.Context
import android.net.Uri
import android.webkit.WebView

class ExtensionManager(
    private val context: Context,
    private val delegate: BrowserDelegate?
) {
    val engine = ExtensionEngineImpl(context, delegate)

    fun updateDelegate(newDelegate: BrowserDelegate?) {
        engine.delegate = newDelegate
    }

    fun setupWebView(webView: WebView, tabId: String? = null) {
        engine.setupWebView(webView, tabId)
    }

    fun setupWebView(
        webView: WebView,
        tabId: String?,
        isPrivate: Boolean,
        privateSessionId: String? = null
    ) {
        engine.setupWebView(webView, tabId, isPrivate, privateSessionId)
    }

    fun injectContentScripts(
        webView: WebView,
        url: String,
        runAt: String? = null,
        isPrivate: Boolean = false,
        privateSessionId: String? = null
    ) {
        engine.injectContentScripts(webView, url, runAt, isPrivate, privateSessionId)
    }

    suspend fun installExtension(uri: Uri, expectedExtensionId: String? = null): ParsedExtension {
        return engine.installExtension(uri, expectedExtensionId)
    }

    suspend fun uninstallExtension(id: String) {
        engine.uninstallExtension(id)
    }

    suspend fun toggleExtension(id: String, enabled: Boolean) {
        engine.toggleExtension(id, enabled)
    }

    fun shutdown() {
        engine.shutdown()
    }
}
