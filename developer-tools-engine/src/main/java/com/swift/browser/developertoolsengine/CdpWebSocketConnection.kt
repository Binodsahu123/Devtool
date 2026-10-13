package com.swift.browser.developertoolsengine

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * A real Chrome DevTools Protocol WebSocket session. This is shared by the built-in
 * DevTools frontend and the browser's chrome.debugger adapter so they do not maintain
 * separate protocol implementations.
 */
class CdpWebSocketConnection internal constructor(
    private val websocketUrl: String,
    private val onEvent: (method: String, params: JSONObject) -> Unit = { _, _ -> }
) : Closeable {
    companion object {
        private val sharedClient = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    private val nextId = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JSONObject>>()
    private val opened = CompletableDeferred<Unit>()
    @Volatile private var socket: WebSocket? = null
    @Volatile private var closed = false
    @Volatile private var connectedSuccessfully = false
    @Volatile private var connectStarted = false
    private val connectLock = Any()

    suspend fun connect() {
        check(!closed) { "CDP WebSocket is closed" }
        // Multiple callers may request a connection at once. Create exactly one socket and let
        // everyone await the same open result instead of leaking a competing WebSocket.
        synchronized(connectLock) {
            if (!connectStarted) {
                connectStarted = true
                val request = Request.Builder().url(websocketUrl).build()
                socket = sharedClient.newWebSocket(request, object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        connectedSuccessfully = true
                        opened.complete(Unit)
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        receive(text)
                    }

                    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                        receive(bytes.utf8())
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        connectedSuccessfully = false
                        closed = true
                        webSocket.close(code, reason)
                        failPending(IllegalStateException("CDP WebSocket is closing: $reason"))
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        connectedSuccessfully = false
                        closed = true
                        failPending(IllegalStateException("CDP WebSocket closed ($code): $reason"))
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        connectedSuccessfully = false
                        closed = true
                        if (!opened.isCompleted) opened.completeExceptionally(t)
                        failPending(t)
                    }
                })
            }
        }
        withTimeout(10_000) { opened.await() }
        check(connectedSuccessfully && !closed) { "CDP WebSocket did not remain connected" }
    }

    suspend fun sendCommand(
        method: String,
        params: JSONObject = JSONObject(),
        timeoutMs: Long = 15_000L
    ): JSONObject {
        check(!closed) { "CDP WebSocket is closed" }
        if (!connectedSuccessfully) connect()
        check(!closed && connectedSuccessfully) { "CDP WebSocket is not connected" }
        val ws = socket ?: throw IllegalStateException("CDP WebSocket is not connected")
        val id = nextId.getAndIncrement()
        val response = CompletableDeferred<JSONObject>()
        pending[id] = response
        val payload = JSONObject().put("id", id).put("method", method).put("params", params)
        if (!ws.send(payload.toString())) {
            pending.remove(id)
            throw IllegalStateException("Failed to send CDP command '$method'")
        }
        return try {
            withTimeout(timeoutMs.coerceIn(1_000L, 60_000L)) { response.await() }
        } finally {
            pending.remove(id)
        }
    }

    fun isConnected(): Boolean = !closed && connectedSuccessfully && socket != null

    private fun receive(text: String) {
        val message = try { JSONObject(text) } catch (_: Exception) { return }
        if (message.has("id")) {
            val id = message.optInt("id", -1)
            val deferred = pending[id] ?: return
            if (message.has("error")) {
                val error = message.optJSONObject("error")
                deferred.completeExceptionally(
                    CdpProtocolException(
                        error?.optInt("code", -1) ?: -1,
                        error?.optString("message", "CDP command failed") ?: "CDP command failed"
                    )
                )
            } else {
                deferred.complete(message.optJSONObject("result") ?: JSONObject())
            }
            return
        }
        val method = message.optString("method")
        if (method.isNotBlank()) {
            try { onEvent(method, message.optJSONObject("params") ?: JSONObject()) } catch (_: Exception) { }
        }
    }

    private fun failPending(error: Throwable) {
        pending.entries.toList().forEach { (id, deferred) ->
            pending.remove(id)
            deferred.completeExceptionally(error)
        }
    }

    override fun close() = close("Swift Browser CDP session closed")

    fun close(reason: String) {
        // Always cancel the underlying socket, even if a listener already marked the session
        // closed after a failed handshake. This also cleans up late async callbacks.
        closed = true
        connectedSuccessfully = false
        socket?.close(1000, reason.take(120))
        socket?.cancel()
        failPending(CancellationException(reason))
    }
}

class CdpProtocolException(
    val code: Int,
    message: String
) : IllegalStateException(message)
