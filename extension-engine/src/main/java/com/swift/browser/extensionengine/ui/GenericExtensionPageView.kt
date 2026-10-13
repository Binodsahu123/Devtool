package com.swift.browser.extensionengine.ui

import android.graphics.Color
import android.webkit.WebView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.swift.browser.extensionengine.ExtensionEngineApi
import com.swift.browser.extensionengine.ExtensionPageLoader

/** Shared WebView host used by popup, side panel, options and other extension HTML surfaces. */
@Composable
fun GenericExtensionPageView(
    extensionId: String,
    pageUrl: String,
    api: ExtensionEngineApi,
    isPrivate: Boolean = false,
    tabId: String? = null,
    uiHost: ExtensionUiHost? = null,
    modifier: Modifier = Modifier
) {
    key(extensionId, pageUrl, isPrivate, tabId) {
        val host = remember(extensionId, pageUrl, isPrivate, tabId, uiHost) {
            uiHost ?: ExtensionUiHost.ExtensionPageHost(
                extensionId = extensionId,
                pageUrl = pageUrl,
                isPrivate = isPrivate,
                tabId = tabId
            )
        }
        val holder = remember { arrayOfNulls<WebView>(1) }

        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    layoutParams = android.view.ViewGroup.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    holder[0] = this
                    setBackgroundColor(Color.TRANSPARENT)
                    host.loadSurface(
                        webView = this,
                        api = api
                    )
                }
            },
            modifier = modifier
        )

        DisposableEffect(Unit) {
            onDispose {
                holder[0]?.let { webView ->
                    host.dispose(webView)
                    holder[0] = null
                }
            }
        }
    }
}
