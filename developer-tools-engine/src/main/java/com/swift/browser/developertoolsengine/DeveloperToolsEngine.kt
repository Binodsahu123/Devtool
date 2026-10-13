package com.swift.browser.developertoolsengine

import android.content.Context
import android.os.Process
import android.util.Log
import android.webkit.WebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.Proxy
import java.net.URL
import java.security.SecureRandom
import java.util.Locale

/**
 * Canonical DevTools runtime for Swift Browser.
 *
 * This module deliberately does not create browser tabs, navigate pages, or maintain
 * browser state. BrowserScreen obtains the current WebView from Swift's TabEngine and
 * passes its tab ID and live WebView here. CDP discovery only chooses that WebView's
 * target; ambiguous matches fail closed instead of attaching to another tab.
 */
class DeveloperToolsEngine private constructor(context: Context) {
    companion object {
        private const val TAG = "SwiftDeveloperTools"
        private const val AUTH_PARAM = "swiftToken"

        @Volatile private var singleton: DeveloperToolsEngine? = null

        fun get(context: Context): DeveloperToolsEngine = singleton ?: synchronized(this) {
            singleton ?: DeveloperToolsEngine(context.applicationContext).also { singleton = it }
        }

        private fun secureToken(): String {
            val bytes = ByteArray(24)
            SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }
    }

    private data class CdpTarget(
        val id: String,
        val type: String,
        val title: String,
        val url: String
    )

    private val appContext = context.applicationContext
    private val authToken = secureToken()

    @Volatile private var bridge: CDPBridge? = null
    @Volatile private var frontendServer: DevToolsServer? = null
    @Volatile private var ports: CDPBridge.Ports? = null
    @Volatile private var frontendPort: Int? = null
    @Volatile private var started = false

    /** Run the WebView debugging toggle on the UI thread, and socket/server setup off it. */
    private suspend fun ensureRuntimeStarted() {
        val needsStart = synchronized(this) { !started }
        if (needsStart) {
            withContext(Dispatchers.Main.immediate) {
                WebView.setWebContentsDebuggingEnabled(true)
            }
        }
        withContext(Dispatchers.IO) { ensureStarted() }
    }

    @Synchronized
    private fun ensureStarted() {
        if (started) return
        val newBridge = CDPBridge(authToken)
        val newServer = DevToolsServer(appContext)
        try {
            val newPorts = newBridge.start(Process.myPid())
            val newFrontendPort = newServer.startLocal()
            bridge = newBridge
            ports = newPorts
            frontendServer = newServer
            frontendPort = newFrontendPort
            started = true
            Log.i(TAG, "Swift Developer Tools backend started on loopback; frontend assets are bundled locally")
        } catch (error: Exception) {
            newBridge.stop()
            try { newServer.stop() } catch (_: Exception) { }
            throw error
        }
    }

    /** Opens the exact active Swift tab in the imported Chromium DevTools frontend. */
    suspend fun openForTarget(
        tabId: String,
        webView: WebView,
        pageUrl: String,
        pageTitle: String
    ): String {
        val livePageInfo = withContext(Dispatchers.Main.immediate) {
            Triple(
                webView.url?.takeIf { it.isNotBlank() } ?: pageUrl,
                webView.title?.takeIf { it.isNotBlank() } ?: pageTitle,
                tabId
            )
        }

        ensureRuntimeStarted()
        return withContext(Dispatchers.IO) {
            val target = resolveTargetInternal(
                pageUrl = livePageInfo.first,
                pageTitle = livePageInfo.second,
                expectedTabId = livePageInfo.third
            )
            val currentPorts = ports ?: error("CDP bridge is not running")
            val uiPort = frontendPort ?: error("DevTools frontend server is not running")
            "http://127.0.0.1:$uiPort/devtools_app.html?ws=127.0.0.1:${currentPorts.webSocket}/devtools/page/${target.id}?$AUTH_PARAM=$authToken"
        }
    }

    /**
     * Opens a real CDP session. If the caller supplies the target ID from discovery, that ID is
     * honored and checked against the supplied WebView instead of being silently ignored.
     */
    suspend fun connectCdp(
        webView: WebView,
        preferredTargetId: String? = null,
        expectedTabId: String? = null,
        onEvent: (method: String, params: JSONObject) -> Unit = { _, _ -> }
    ): CdpWebSocketConnection {
        val pageInfo = withContext(Dispatchers.Main.immediate) {
            webView.url.orEmpty() to webView.title.orEmpty()
        }
        ensureRuntimeStarted()
        val target = withContext(Dispatchers.IO) {
            resolveTargetInternal(
                pageUrl = pageInfo.first,
                pageTitle = pageInfo.second,
                expectedTabId = expectedTabId,
                preferredTargetId = preferredTargetId
            )
        }
        val wsPort = ports?.webSocket ?: error("CDP WebSocket port is unavailable")
        val url = "ws://127.0.0.1:$wsPort/devtools/page/${target.id}?$AUTH_PARAM=$authToken"
        val connection = CdpWebSocketConnection(url, onEvent)
        try {
            connection.connect()
            return connection
        } catch (error: Exception) {
            // A failed handshake must not leave a late-opening WebSocket alive without an owner.
            connection.close()
            throw error
        }
    }

    /** Explicit app shutdown hook. Browser navigation/tab state is never owned by this module. */
    @Synchronized
    fun shutdown() {
        bridge?.stop()
        frontendServer?.stop()
        bridge = null
        frontendServer = null
        ports = null
        frontendPort = null
        started = false
        // Do not disable process-wide WebView debugging here: an ExtensionEngine CDP session may
        // still be active. It is enabled on-demand, not during normal browsing.
    }

    private suspend fun resolveTargetInternal(
        pageUrl: String,
        pageTitle: String,
        expectedTabId: String? = null,
        preferredTargetId: String? = null
    ): CdpTarget {
        var lastTargets = emptyList<CdpTarget>()
        repeat(18) {
            lastTargets = try {
                fetchTargets()
            } catch (error: Exception) {
                Log.d(TAG, "CDP target discovery attempt failed: ${error.message}")
                emptyList()
            }

            val target = selectTarget(
                targets = lastTargets,
                pageUrl = pageUrl,
                pageTitle = pageTitle,
                preferredTargetId = preferredTargetId
            )
            if (target != null) return target
            delay(200)
        }

        val summary = lastTargets
            .filter { it.type == "page" }
            .joinToString { "${it.id}:${it.title.take(48)}:${it.url.take(120)}" }
            .take(900)
        val tabLabel = expectedTabId?.let { "Tab '$it'" } ?: "Active Swift tab"
        throw IllegalStateException(
            if (lastTargets.isEmpty()) {
                "$tabLabel: the WebView CDP target is not ready. Open a website in Swift Browser and try again."
            } else {
                "$tabLabel: could not safely match its WebView to one CDP target. Active URL: $pageUrl. Targets: $summary"
            }
        )
    }

    private fun selectTarget(
        targets: List<CdpTarget>,
        pageUrl: String,
        pageTitle: String,
        preferredTargetId: String?
    ): CdpTarget? {
        val uiPort = frontendPort
        val pages = targets.filter { target ->
            target.type == "page" && target.id.isNotBlank() &&
                !target.url.contains("devtools_app.html", ignoreCase = true) &&
                !target.url.contains("/devtools_frontend/", ignoreCase = true) &&
                (uiPort == null || !target.url.startsWith("http://127.0.0.1:$uiPort/")) &&
                (uiPort == null || !target.url.startsWith("http://localhost:$uiPort/"))
        }

        val requested = normalizeTargetUrl(pageUrl)

        // Chrome extensions pass the ID returned by target discovery. Respect it, but fail closed
        // if it does not describe the same currently supplied Swift WebView.
        if (!preferredTargetId.isNullOrBlank()) {
            val explicit = pages.firstOrNull { it.id == preferredTargetId } ?: return null
            if (requested.isNotBlank() && normalizeTargetUrl(explicit.url) != requested) {
                throw IllegalStateException(
                    "Requested CDP target '$preferredTargetId' does not match the supplied Swift WebView URL. " +
                        "Expected '$pageUrl', target URL '${explicit.url}'."
                )
            }
            return explicit
        }

        if (requested.isBlank()) {
            val titleMatches = pages.filter { pageTitle.isNotBlank() && it.title == pageTitle }
            return titleMatches.singleOrNull()
        }

        // URL is the authoritative tab binding. A unique URL is safe; duplicate URL tabs can be
        // disambiguated by title only when exactly one candidate matches. Never choose an arbitrary
        // remaining page: WebView's public API does not expose its CDP target ID directly.
        val exactUrl = pages.filter { normalizeTargetUrl(it.url) == requested }
        if (exactUrl.size == 1) return exactUrl.single()
        if (exactUrl.size > 1) {
            val titleMatches = exactUrl.filter { pageTitle.isNotBlank() && it.title == pageTitle }
            return titleMatches.singleOrNull()
        }
        return null
    }

    /** Normalizes only URL-equivalent differences (scheme/host case, default port, root slash). */
    private fun normalizeTargetUrl(raw: String): String {
        val value = raw.trim()
        if (value.isBlank()) return ""
        return try {
            val uri = URI(value).normalize()
            val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return value
            val host = uri.host?.lowercase(Locale.ROOT)
            if (host == null) return "$scheme:${uri.rawSchemeSpecificPart.orEmpty()}"

            val userInfo = uri.rawUserInfo?.let { "$it@" }.orEmpty()
            val port = when {
                scheme == "http" && uri.port == 80 -> -1
                scheme == "https" && uri.port == 443 -> -1
                else -> uri.port
            }
            val authority = userInfo + host + if (port >= 0) ":$port" else ""
            val path = uri.rawPath.orEmpty().let { if (it == "/") "" else it }
            buildString {
                append(scheme).append("://").append(authority).append(path)
                uri.rawQuery?.let { append('?').append(it) }
                uri.rawFragment?.let { append('#').append(it) }
            }
        } catch (_: Exception) {
            value
        }
    }

    private fun fetchTargets(): List<CdpTarget> {
        val cdpPorts = ports ?: error("CDP bridge is not started")
        val connection = URL("http://127.0.0.1:${cdpPorts.http}/json?$AUTH_PARAM=$authToken")
            .openConnection(Proxy.NO_PROXY) as HttpURLConnection
        connection.connectTimeout = 2_500
        connection.readTimeout = 2_500
        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept", "application/json")
        try {
            val status = connection.responseCode
            if (status !in 200..299) throw IllegalStateException("CDP target discovery returned HTTP $status")
            val response = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val array = JSONArray(response)
            return buildList(array.length()) {
                for (i in 0 until array.length()) {
                    val item: JSONObject = array.optJSONObject(i) ?: continue
                    val id = item.optString("id")
                    if (id.isBlank()) continue
                    add(
                        CdpTarget(
                            id = id,
                            type = item.optString("type", "page"),
                            title = item.optString("title"),
                            url = item.optString("url")
                        )
                    )
                }
            }
        } finally {
            connection.disconnect()
        }
    }
}
