package com.swift.browser.extensionengine.security

import android.net.Uri
import android.webkit.WebView
import androidx.webkit.JavaScriptExecutionWorld
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * Modern origin-scoped WebMessage transport used by extension-owned WebViews.
 *
 * The transport deliberately keeps a narrow surface: payload in, response callback out.
 * The browser-side API router remains authoritative for permissions and capability checks.
 */
object ExtensionWebMessageBridge {

    enum class BridgeFeatureStatus {
        SUPPORTED,
        UNSUPPORTED_BY_WEBVIEW
    }

    fun isWebMessageListenerSupported(): Boolean =
        try {
            WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
        } catch (_: Throwable) {
            false
        }

    fun isWorldScopedWebMessageSupported(): Boolean =
        try {
            WebViewFeature.isFeatureSupported(WebViewFeature.JS_INJECTION_IN_FRAME_AND_WORLD)
        } catch (_: Throwable) {
            false
        }

    fun setupWebMessageListener(
        webView: WebView,
        listenerName: String,
        allowedOrigins: Set<String>,
        onMessageReceived: (
            message: String,
            sourceOrigin: String,
            isMainFrame: Boolean,
            replyProxy: JavaScriptReplyProxy?
        ) -> Unit
    ): BridgeFeatureStatus {
        return setupWebMessageListener(
            webView = webView,
            listenerName = listenerName,
            allowedOrigins = allowedOrigins,
            world = null,
            onMessageReceived = onMessageReceived
        )
    }

    fun setupWebMessageListener(
        webView: WebView,
        listenerName: String,
        allowedOrigins: Set<String>,
        world: JavaScriptExecutionWorld?,
        requireWorldScoped: Boolean = false,
        onMessageReceived: (
            message: String,
            sourceOrigin: String,
            isMainFrame: Boolean,
            replyProxy: JavaScriptReplyProxy?
        ) -> Unit
    ): BridgeFeatureStatus {
        if (!isWebMessageListenerSupported()) {
            return BridgeFeatureStatus.UNSUPPORTED_BY_WEBVIEW
        }
        if (allowedOrigins.isEmpty()) {
            return BridgeFeatureStatus.UNSUPPORTED_BY_WEBVIEW
        }
        if (requireWorldScoped && (world == null || !isWorldScopedWebMessageSupported())) {
            return BridgeFeatureStatus.UNSUPPORTED_BY_WEBVIEW
        }

        return try {
            val rules = allowedOrigins.toSet()
            val listener = object : WebViewCompat.WebMessageListener {
                override fun onPostMessage(
                    view: WebView,
                    message: WebMessageCompat,
                    sourceOrigin: Uri,
                    isMainFrame: Boolean,
                    replyProxy: JavaScriptReplyProxy
                ) {
                    val payload = message.data ?: ""
                    onMessageReceived(payload, sourceOrigin.toString(), isMainFrame, replyProxy)
                }
            }

            if (world != null && isWorldScopedWebMessageSupported()) {
                WebViewCompat.addWebMessageListener(
                    webView,
                    listenerName,
                    rules,
                    world,
                    listener
                )
            } else {
                WebViewCompat.addWebMessageListener(
                    webView,
                    listenerName,
                    rules,
                    listener
                )
            }
            BridgeFeatureStatus.SUPPORTED
        } catch (_: Throwable) {
            BridgeFeatureStatus.UNSUPPORTED_BY_WEBVIEW
        }
    }

    /** Removes the listener previously installed for the supplied extension-owned WebView. */
    fun removeWebMessageListener(webView: WebView, listenerName: String) {
        if (!isWebMessageListenerSupported()) return
        try {
            WebViewCompat.removeWebMessageListener(webView, listenerName)
        } catch (_: Throwable) {
            // Idempotent teardown across WebView provider versions.
        }
    }
}
