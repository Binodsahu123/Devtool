package com.swift.browser.extensionengine.chromedebugger

import com.swift.browser.extensionengine.ChromiumDevToolsTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

/** Transport contract used by session orchestration. Production uses the real CDP socket. */
interface ChromeDebuggerTransport {
    suspend fun connect()
    suspend fun sendCommand(method: String, params: JSONObject = JSONObject(), timeoutMs: Long = 15000L): JSONObject
    fun close(reason: String = ChromeDebuggerErrors.ERROR_TARGET_CLOSED)
    fun isConnected(): Boolean
}

/** Injectable factory keeps session tests deterministic without faking CDP in production. */
fun interface ChromeDebuggerTransportFactory {
    fun create(
        target: ChromeDebuggerTargetManager.ResolvedTarget,
        onEvent: (ChromeDebuggerCdpEvent) -> Unit
    ): ChromeDebuggerTransport
}

/**
 * Chrome-debugger facade over the one canonical real Chromium CDP transport.
 *
 * No evaluateJavascript pseudo-CDP exists in this layer. Target/session ownership,
 * WebSocket framing, request correlation, timeouts, and disconnect semantics all
 * live in ChromiumDevToolsTransport so Developer Tools and chrome.debugger cannot
 * silently diverge at the wire-protocol layer.
 */
class ChromeDebuggerCdpTransport(
    private val target: ChromeDebuggerTargetManager.ResolvedTarget,
    private val onEventCallback: (ChromeDebuggerCdpEvent) -> Unit
) : ChromeDebuggerTransport {
    private val delegate = ChromiumDevToolsTransport()

    @Volatile
    private var connected = false

    init {
        delegate.setEventListener { method, params ->
            onEventCallback(ChromeDebuggerCdpEvent(method = method, params = params, sessionId = null))
        }
    }

    override suspend fun connect() = withContext(Dispatchers.IO) {
        if (connected) return@withContext
        val webView = target.webView
            ?: throw ChromeDebuggerException(
                ChromeDebuggerErrors.ERROR_TARGET_NOT_FOUND,
                "Target ${target.targetId} has no live WebView/CDP endpoint."
            )
        delegate.connect(target.targetId, target.tabId?.toString() ?: "", webView)
        connected = true
    }

    override suspend fun sendCommand(
        method: String,
        params: JSONObject,
        timeoutMs: Long
    ): JSONObject {
        if (!connected) connect()
        return withContext(Dispatchers.IO) {
            try {
                val boundedTimeout = timeoutMs.coerceIn(1_000L, 60_000L)
                withTimeout(boundedTimeout) { delegate.sendCommand(method, params) }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                throw ChromeDebuggerException(
                    ChromeDebuggerErrors.ERROR_TIMEOUT,
                    "CDP command '$method' timed out after ${timeoutMs}ms."
                )
            } catch (e: Exception) {
                throw when (e) {
                    is ChromeDebuggerException -> e
                    else -> ChromeDebuggerException(
                        ChromeDebuggerErrors.ERROR_COMMAND_FAILED,
                        e.message ?: "Failed to execute CDP command '$method'"
                    )
                }
            }
        }
    }

    override fun close(reason: String) {
        connected = false
        delegate.disconnect(reason)
    }

    override fun isConnected(): Boolean = connected && delegate.isConnected()

}
