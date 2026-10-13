package com.swift.browser.developertoolsengine

import android.graphics.Color as AndroidColor
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import java.io.ByteArrayInputStream
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

/** Layout modes of the Developer Tools panel. */
enum class DevToolsDockMode { BOTTOM, RIGHT, FULLSCREEN }

/**
 * Displays the real bundled Chromium DevTools frontend. This UI deliberately receives
 * the active URL from Swift Browser and never creates/owns a browser tab or site WebView.
 */
@Composable
fun DeveloperToolsPanel(
    frontendUrl: String?,
    errorMessage: String?,
    isFullscreen: Boolean,
    isSideDocked: Boolean,
    onClose: () -> Unit,
    onToggleFullscreen: () -> Unit,
    onToggleSideDock: () -> Unit,
    modifier: Modifier = Modifier
) {
    val allowedFrontendUri = remember(frontendUrl) {
        frontendUrl?.let { runCatching { android.net.Uri.parse(it) }.getOrNull() }
    }
    var loadedUrl by remember { mutableStateOf<String?>(null) }
    var frontendWebView by remember { mutableStateOf<WebView?>(null) }
    var frontendReady by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier,
        color = Color(0xFF202124),
        contentColor = Color(0xFFE8EAED),
        shape = if (isFullscreen) RoundedCornerShape(0.dp) else RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp),
        tonalElevation = 3.dp
    ) {
        Column(Modifier.fillMaxSize().background(Color(0xFF202124))) {
            Row(
                modifier = Modifier.fillMaxWidth().height(42.dp).padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "SWIFT DEVTOOLS",
                    color = Color(0xFF8AB4F8),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
                Text(
                    text = when {
                        errorMessage != null -> "Connection error"
                        frontendUrl != null -> "Connected to active tab"
                        else -> "Connecting to active tab…"
                    },
                    color = Color(0xFFBDC1C6),
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Button(
                    enabled = frontendReady && frontendWebView != null,
                    onClick = {
                        // Ported from the Unix Shells DevTools action: ask the real Chromium
                        // frontend to enter element-inspection mode. The active website remains
                        // the Swift TabEngine WebView; this executes only inside DevTools UI.
                        frontendWebView?.evaluateJavascript(
                            "(function(){if(window.InspectorFrontendHost && typeof window.InspectorFrontendHost.enterInspectElementMode === 'function'){window.InspectorFrontendHost.enterInspectElementMode();return true;}return false;})()",
                            null
                        )
                    },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF303134),
                        contentColor = Color(0xFFE8EAED),
                        disabledContainerColor = Color(0xFF252629),
                        disabledContentColor = Color(0xFF777777)
                    )
                ) {
                    Text("Inspect", fontSize = 11.sp)
                }
                Button(
                    onClick = onToggleSideDock,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF303134),
                        contentColor = Color(0xFFE8EAED)
                    )
                ) {
                    Text(if (isSideDocked) "Bottom" else "Side", fontSize = 11.sp)
                }
                Button(
                    onClick = onToggleFullscreen,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF303134),
                        contentColor = Color(0xFFE8EAED)
                    )
                ) {
                    Text(if (isFullscreen) "Dock" else "Expand", fontSize = 11.sp)
                }
                Button(
                    onClick = onClose,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF3C4043),
                        contentColor = Color(0xFFE8EAED)
                    )
                ) {
                    Text("Close", fontSize = 11.sp)
                }
            }

            Box(Modifier.fillMaxSize().weight(1f), contentAlignment = Alignment.Center) {
                when {
                    errorMessage != null -> Column(
                        modifier = Modifier.fillMaxWidth().padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("Developer Tools could not connect", color = Color(0xFFF28B82), fontWeight = FontWeight.SemiBold)
                        Text(errorMessage, color = Color(0xFFE8EAED), fontSize = 12.sp)
                        Text("Open a website in Swift Browser, then reopen Developer Tools.", color = Color(0xFFBDC1C6), fontSize = 12.sp)
                    }
                    frontendUrl.isNullOrBlank() -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Color(0xFF8AB4F8), modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.height(10.dp))
                        Text("Connecting to Swift Browser's active WebView…", color = Color(0xFFBDC1C6), fontSize = 12.sp)
                    }
                    else -> AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            WebView(ctx).apply {
                                setBackgroundColor(AndroidColor.rgb(32, 33, 36))
                                settings.apply {
                                    javaScriptEnabled = true
                                    domStorageEnabled = true
                                    databaseEnabled = false
                                    allowFileAccess = false
                                    allowContentAccess = false
                                    javaScriptCanOpenWindowsAutomatically = false
                                    mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                                    cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
                                }
                                webChromeClient = WebChromeClient()
                                webViewClient = object : WebViewClient() {
                                    private fun isLocalDevToolsUrl(url: android.net.Uri): Boolean {
                                        val expected = allowedFrontendUri ?: return false
                                        return url.scheme == "http" &&
                                            url.host == expected.host &&
                                            url.port == expected.port
                                    }

                                    override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                                        frontendReady = false
                                    }

                                    override fun onPageFinished(view: WebView, url: String) {
                                        frontendReady = runCatching { isLocalDevToolsUrl(android.net.Uri.parse(url)) }
                                            .getOrDefault(false)
                                    }

                                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                        // Top-level navigation must stay inside the bundled local frontend.
                                        return !isLocalDevToolsUrl(request.url)
                                    }

                                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                                        val uri = request.url
                                        val scheme = uri.scheme.orEmpty().lowercase()
                                        if (scheme == "data" || scheme == "blob" || scheme == "about") return null
                                        if (scheme == "http" || scheme == "https") {
                                            if (isLocalDevToolsUrl(uri)) return null
                                            // Do not fetch a hosted frontend, fonts, scripts, or other remote assets.
                                            return WebResourceResponse(
                                                "text/plain",
                                                "UTF-8",
                                                403,
                                                "Blocked by local-only Swift DevTools policy",
                                                emptyMap(),
                                                ByteArrayInputStream(ByteArray(0))
                                            )
                                        }
                                        return WebResourceResponse(
                                            "text/plain",
                                            "UTF-8",
                                            403,
                                            "Unsupported DevTools resource scheme",
                                            emptyMap(),
                                            ByteArrayInputStream(ByteArray(0))
                                        )
                                    }
                                }
                                loadedUrl = frontendUrl
                                frontendWebView = this
                                frontendReady = false
                                loadUrl(frontendUrl)
                            }
                        },
                        update = { webView ->
                            if (loadedUrl != frontendUrl && !frontendUrl.isNullOrBlank()) {
                                loadedUrl = frontendUrl
                                frontendReady = false
                                webView.loadUrl(frontendUrl)
                            }
                        },
                        onRelease = { webView ->
                            try { webView.stopLoading() } catch (_: Exception) { }
                            try { webView.loadUrl("about:blank") } catch (_: Exception) { }
                            try { webView.destroy() } catch (_: Exception) { }
                            if (frontendWebView === webView) frontendWebView = null
                            frontendReady = false
                            loadedUrl = null
                        }
                    )
                }
            }
        }
    }
}
