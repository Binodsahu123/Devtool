package com.swift.browser.developertoolsengine

import android.content.Context
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.io.IOException
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Serves the exact bundled Chromium DevTools frontend imported from the Unix Shells
 * source ZIP. The server binds only to IPv4 loopback and deliberately never fetches
 * a hosted Google/Chromium frontend at runtime.
 */
class DevToolsServer(
    private val context: Context,
    requestedPort: Int = 0
) : NanoHTTPD("127.0.0.1", requestedPort) {

    companion object {
        private const val TAG = "SwiftDevToolsServer"
        private const val ASSET_ROOT = "devtools_frontend"
    }

    @Volatile
    private var didStart = false

    fun startLocal(): Int {
        if (!didStart) {
            start(SOCKET_READ_TIMEOUT, false)
            didStart = true
        }
        return listeningPort
    }

    override fun serve(session: IHTTPSession): Response {
        val rawRequestedPath = session.uri.substringBefore('?').ifBlank { "/" }
        val requestedPath = try {
            // Treat '+' literally in paths, then decode percent-encoding before checking traversal.
            URLDecoder.decode(rawRequestedPath.replace("+", "%2B"), StandardCharsets.UTF_8.name())
        } catch (_: IllegalArgumentException) {
            return errorResponse(Response.Status.BAD_REQUEST, "Malformed asset path")
        }
        if (requestedPath.contains('\\') || requestedPath.contains('\u0000') ||
            requestedPath.split('/').any { it == ".." || it == "." }) {
            return errorResponse(Response.Status.FORBIDDEN, "Invalid asset path")
        }

        val path = if (requestedPath == "/") "/devtools_app.html" else requestedPath
        val relativePath = path.removePrefix("/")
        return try {
            val stream = context.assets.open("$ASSET_ROOT/$relativePath")
            val response = newChunkedResponse(Response.Status.OK, mimeType(path), stream)
            response.addHeader("Cache-Control", "no-store")
            response.addHeader("X-Content-Type-Options", "nosniff")
            response
        } catch (_: IOException) {
            // Chromium DevTools sometimes requests a directory path. Keep that local-only fallback.
            try {
                val stream = context.assets.open("$ASSET_ROOT/$relativePath/index.html")
                val response = newChunkedResponse(Response.Status.OK, "text/html; charset=utf-8", stream)
                response.addHeader("Cache-Control", "no-store")
                response
            } catch (_: IOException) {
                Log.d(TAG, "Bundled DevTools asset not found: $relativePath")
                errorResponse(Response.Status.NOT_FOUND, "DevTools asset not found: $relativePath")
            }
        }
    }

    private fun errorResponse(status: Response.IStatus, message: String): Response =
        newFixedLengthResponse(status, "text/plain; charset=utf-8", message)

    private fun mimeType(path: String): String = when {
        path.endsWith(".html", true) -> "text/html; charset=utf-8"
        path.endsWith(".js", true) || path.endsWith(".mjs", true) -> "application/javascript; charset=utf-8"
        path.endsWith(".css", true) -> "text/css; charset=utf-8"
        path.endsWith(".json", true) || path.endsWith(".map", true) -> "application/json; charset=utf-8"
        path.endsWith(".svg", true) -> "image/svg+xml"
        path.endsWith(".png", true) -> "image/png"
        path.endsWith(".avif", true) -> "image/avif"
        path.endsWith(".webp", true) -> "image/webp"
        path.endsWith(".gif", true) -> "image/gif"
        path.endsWith(".jpg", true) || path.endsWith(".jpeg", true) -> "image/jpeg"
        path.endsWith(".ico", true) -> "image/x-icon"
        path.endsWith(".woff", true) -> "font/woff"
        path.endsWith(".woff2", true) -> "font/woff2"
        path.endsWith(".ttf", true) -> "font/ttf"
        path.endsWith(".otf", true) -> "font/otf"
        path.endsWith(".wasm", true) -> "application/wasm"
        path.endsWith(".webmanifest", true) -> "application/manifest+json"
        else -> "application/octet-stream"
    }

    override fun stop() {
        didStart = false
        super.stop()
    }
}
