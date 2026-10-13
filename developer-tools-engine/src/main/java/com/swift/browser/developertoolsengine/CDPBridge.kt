package com.swift.browser.developertoolsengine

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * Localhost-only transport bridge for the Android WebView DevTools abstract socket.
 *
 * This is adapted from Unix Shells' CDPBridge: the browser WebView stays owned by
 * Swift Browser, while this class forwards DevTools Protocol HTTP/WebSocket bytes
 * to WebView's existing `webview_devtools_remote_<pid>` socket. It never creates
 * or owns a website WebView or a browser/tab manager.
 *
 * Each connection must carry the per-process `swiftToken` query parameter. That
 * parameter is stripped before forwarding the request into WebView. The Origin
 * header is removed only for authenticated WebSocket upgrades because WebView's
 * CDP endpoint may reject a localhost DevTools frontend origin.
 */
class CDPBridge(
    private val authToken: String,
    private val requestedHttpPort: Int = 0,
    private val requestedWebSocketPort: Int = 0
) {
    data class Ports(val http: Int, val webSocket: Int)

    companion object {
        private const val TAG = "SwiftCDPBridge"
        private const val MAX_HEADER_BYTES = 64 * 1024
        private const val BUFFER_SIZE = 64 * 1024
        private const val AUTH_PARAM = "swiftToken"
    }

    @Volatile private var running = false
    @Volatile private var socketName: String? = null
    @Volatile private var httpServer: ServerSocket? = null
    @Volatile private var webSocketServer: ServerSocket? = null
    private val activeClients = Collections.newSetFromMap(ConcurrentHashMap<Socket, Boolean>())
    private val activeLocalSockets = Collections.newSetFromMap(ConcurrentHashMap<LocalSocket, Boolean>())

    @Synchronized
    @Throws(IOException::class)
    fun start(pid: Int): Ports {
        if (running) {
            return Ports(
                httpServer?.localPort ?: error("HTTP CDP server has no bound port"),
                webSocketServer?.localPort ?: error("WebSocket CDP server has no bound port")
            )
        }

        socketName = "webview_devtools_remote_$pid"
        val http = bindLoopback(requestedHttpPort)
        var createdWebSocket: ServerSocket? = null
        try {
            val boundWebSocket = bindLoopback(requestedWebSocketPort)
            createdWebSocket = boundWebSocket
            httpServer = http
            webSocketServer = boundWebSocket
            running = true
            thread(name = "swift-cdp-http-accept", isDaemon = true) { acceptLoop(http, "http") }
            thread(name = "swift-cdp-ws-accept", isDaemon = true) { acceptLoop(boundWebSocket, "websocket") }
            Log.i(TAG, "CDP bridge listening on loopback ports ${http.localPort}/${boundWebSocket.localPort}")
            return Ports(http.localPort, boundWebSocket.localPort)
        } catch (error: Exception) {
            running = false
            try { http.close() } catch (_: Exception) { }
            try { createdWebSocket?.close() } catch (_: Exception) { }
            httpServer = null
            webSocketServer = null
            throw error
        }
    }

    @Synchronized
    fun stop() {
        running = false
        try { httpServer?.close() } catch (_: Exception) { }
        try { webSocketServer?.close() } catch (_: Exception) { }
        activeClients.toList().forEach { try { it.close() } catch (_: Exception) { } }
        activeLocalSockets.toList().forEach { try { it.close() } catch (_: Exception) { } }
        activeClients.clear()
        activeLocalSockets.clear()
        httpServer = null
        webSocketServer = null
        socketName = null
    }

    private fun bindLoopback(preferredPort: Int): ServerSocket {
        fun bind(port: Int): ServerSocket {
            val server = ServerSocket()
            server.reuseAddress = true
            server.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 32)
            return server
        }
        if (preferredPort > 0) {
            try {
                return bind(preferredPort)
            } catch (e: IOException) {
                Log.w(TAG, "Port $preferredPort unavailable; using an ephemeral loopback port", e)
            }
        }
        return bind(0)
    }

    private fun acceptLoop(server: ServerSocket, label: String) {
        while (running && !server.isClosed) {
            try {
                val client = server.accept()
                client.tcpNoDelay = true
                activeClients.add(client)
                thread(name = "swift-cdp-$label-${client.port}", isDaemon = true) {
                    try { handleConnection(client) } finally { activeClients.remove(client) }
                }
            } catch (e: SocketException) {
                if (running) Log.w(TAG, "CDP $label accept socket stopped unexpectedly", e)
                break
            } catch (e: IOException) {
                if (running) Log.w(TAG, "CDP $label accept failed", e)
            }
        }
    }

    private fun handleConnection(client: Socket) {
        var localSocket: LocalSocket? = null
        try {
            client.soTimeout = 8_000
            val input = client.getInputStream()
            val headerBytes = readHeader(input) ?: return
            val header = headerBytes.toString(Charsets.ISO_8859_1)
            val requestLine = header.lineSequence().firstOrNull().orEmpty()
            if (!isAuthorized(requestLine)) {
                writeHttpError(client, 403, "Forbidden", "Swift Browser CDP token missing or invalid")
                return
            }

            val isWebSocketUpgrade = Regex("(?im)^Upgrade\\s*:\\s*websocket\\s*$").containsMatchIn(header)
            val sanitizedHeader = sanitizeHeader(header, stripOrigin = isWebSocketUpgrade)
            val targetSocketName = socketName ?: run {
                writeHttpError(client, 503, "Service Unavailable", "WebView CDP socket is not ready")
                return
            }

            val connectedLocalSocket = LocalSocket()
            localSocket = connectedLocalSocket
            connectedLocalSocket.connect(
                LocalSocketAddress(targetSocketName, LocalSocketAddress.Namespace.ABSTRACT)
            )
            activeLocalSockets.add(connectedLocalSocket)
            client.soTimeout = 0
            val localOut = connectedLocalSocket.outputStream
            localOut.write(sanitizedHeader.toByteArray(Charsets.ISO_8859_1))
            localOut.flush()

            val finished = java.util.concurrent.CountDownLatch(1)
            val clientToWebView = thread(name = "swift-cdp-client-to-webview", isDaemon = true) {
                try { pipe(input, localOut) } finally { finished.countDown() }
            }
            val webViewToClient = thread(name = "swift-cdp-webview-to-client", isDaemon = true) {
                try { pipe(connectedLocalSocket.inputStream, client.getOutputStream()) } finally { finished.countDown() }
            }
            finished.await()
            try { client.close() } catch (_: Exception) { }
            try { connectedLocalSocket.close() } catch (_: Exception) { }
            clientToWebView.join()
            webViewToClient.join()
        } catch (e: Exception) {
            if (running && e !is IOException) Log.w(TAG, "CDP proxy connection ended: ${e.message}")
            else if (running && e.message?.contains("socket", ignoreCase = true) == true) Log.d(TAG, "CDP socket connection ended")
        } finally {
            try { client.close() } catch (_: Exception) { }
            try { localSocket?.close() } catch (_: Exception) { }
            localSocket?.let { activeLocalSockets.remove(it) }
        }
    }

    private fun readHeader(input: java.io.InputStream): ByteArray? {
        val out = ByteArrayOutputStream()
        var matched = 0
        val terminator = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())
        while (out.size() < MAX_HEADER_BYTES) {
            val value = input.read()
            if (value < 0) return if (out.size() == 0) null else out.toByteArray()
            out.write(value)
            if (value.toByte() == terminator[matched]) {
                matched++
                if (matched == terminator.size) return out.toByteArray()
            } else {
                matched = if (value.toByte() == terminator[0]) 1 else 0
            }
        }
        throw IOException("CDP HTTP request header exceeded $MAX_HEADER_BYTES bytes")
    }

    private fun isAuthorized(requestLine: String): Boolean {
        val parts = requestLine.split(' ')
        if (parts.size < 2) return false
        val query = parts[1].substringAfter('?', "")
        val suppliedToken = query.split('&')
            .mapNotNull { component ->
                val idx = component.indexOf('=')
                if (idx <= 0) null else component.substring(0, idx) to component.substring(idx + 1)
            }
            .firstOrNull { (key, _) -> decode(key) == AUTH_PARAM }
            ?.second?.let(::decode)
        return suppliedToken != null && constantTimeEquals(suppliedToken, authToken)
    }

    private fun sanitizeHeader(header: String, stripOrigin: Boolean): String {
        val lines = header.split("\r\n").toMutableList()
        if (lines.isNotEmpty()) {
            val parts = lines[0].split(' ')
            if (parts.size >= 3) {
                lines[0] = "${parts[0]} ${stripAuthQuery(parts[1])} ${parts.drop(2).joinToString(" ")}"
            }
        }
        val sanitized = lines.filterIndexed { index, line ->
            index == 0 || !(stripOrigin && line.startsWith("Origin:", ignoreCase = true))
        }
        // Ensure the upstream HTTP parser receives the complete CRLF-CRLF terminator.
        return sanitized.joinToString("\r\n").trimEnd('\r', '\n') + "\r\n\r\n"
    }

    private fun stripAuthQuery(target: String): String {
        val question = target.indexOf('?')
        if (question < 0) return target
        val path = target.substring(0, question)
        val query = target.substring(question + 1)
        val remaining = query.split('&').filterNot { component ->
            val key = component.substringBefore('=')
            decode(key) == AUTH_PARAM
        }
        return path + if (remaining.isEmpty()) "" else "?${remaining.joinToString("&")}"
    }

    private fun decode(value: String): String = try {
        URLDecoder.decode(value, Charsets.UTF_8.name())
    } catch (_: Exception) { value }

    private fun constantTimeEquals(left: String, right: String): Boolean {
        if (left.length != right.length) return false
        var difference = 0
        for (i in left.indices) difference = difference or (left[i].code xor right[i].code)
        return difference == 0
    }

    private fun writeHttpError(socket: Socket, status: Int, label: String, message: String) {
        try {
            val body = message.toByteArray(Charsets.UTF_8)
            val header = "HTTP/1.1 $status $label\r\nContent-Type: text/plain; charset=utf-8\r\n" +
                "Content-Length: ${body.size}\r\nConnection: close\r\nCache-Control: no-store\r\n\r\n"
            socket.getOutputStream().apply {
                write(header.toByteArray(Charsets.ISO_8859_1))
                write(body)
                flush()
            }
        } catch (_: Exception) { }
    }

    private fun pipe(input: java.io.InputStream, output: java.io.OutputStream) {
        val buffer = ByteArray(BUFFER_SIZE)
        try {
            while (running) {
                val count = input.read(buffer)
                if (count < 0) break
                output.write(buffer, 0, count)
                output.flush()
            }
        } catch (_: IOException) {
            // A browser frontend or WebView closed this CDP stream.
        }
    }
}
