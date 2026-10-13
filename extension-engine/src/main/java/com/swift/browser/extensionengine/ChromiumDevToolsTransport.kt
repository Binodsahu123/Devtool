package com.swift.browser.extensionengine

import android.webkit.WebView
import com.swift.browser.developertoolsengine.CdpWebSocketConnection
import com.swift.browser.developertoolsengine.DeveloperToolsEngine
import com.swift.browser.extensionengine.chromedebugger.ChromeDebuggerErrors
import com.swift.browser.extensionengine.chromedebugger.ChromeDebuggerException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Compatibility facade that delegates to the Developer Tools Engine's canonical
 * authenticated CDP WebSocket transport. chrome.debugger and the DevTools frontend
 * use the same bridge and protocol wiring; this class does not fake CDP via JS eval.
 */
class ChromiumDevToolsTransport {
    /** This adapter owns only CDP sessions; tab enumeration belongs to ChromeDebuggerTargetManager/TabEngine. */
    @Volatile private var connected = false
    @Volatile private var eventListener: ((String, JSONObject) -> Unit)? = null
    @Volatile private var connection: CdpWebSocketConnection? = null

    suspend fun connect(targetId: String, tabId: String, webView: WebView?) = withContext(Dispatchers.IO) {
        if (connected && connection?.isConnected() == true) return@withContext
        val targetWebView = webView ?: throw ChromeDebuggerException(
            ChromeDebuggerErrors.ERROR_TARGET_NOT_FOUND,
            "CDP target $targetId (tab $tabId) has no live WebView. Extension worker targets require a native worker runtime endpoint."
        )
        try {
            val engine = DeveloperToolsEngine.get(targetWebView.context)
            // ChromeDebuggerTargetManager exposes Swift-owned synthetic IDs such as
            // target_tab_<uuid>. They are not Chromium/WebView CDP target IDs. Pass a preferred
            // ID only when it is native; otherwise resolve against this tab's actual WebView URL.
            val nativeTargetId = targetId.takeUnless { it.startsWith("target_tab_") }
            val newConnection = engine.connectCdp(
                webView = targetWebView,
                preferredTargetId = nativeTargetId,
                expectedTabId = tabId
            ) { method, params ->
                eventListener?.invoke(method, params)
            }
            connection?.close()
            connection = newConnection
            connected = true
        } catch (error: Exception) {
            connected = false
            connection?.close()
            connection = null
            throw ChromeDebuggerException(
                ChromeDebuggerErrors.ERROR_TARGET_NOT_FOUND,
                "Could not connect to the WebView's real CDP target: ${error.message ?: error.javaClass.simpleName}"
            )
        }
    }

    suspend fun sendCommand(method: String, params: JSONObject?): JSONObject = withContext(Dispatchers.IO) {
        if (!connected || connection?.isConnected() != true) {
            throw ChromeDebuggerException(
                ChromeDebuggerErrors.ERROR_COMMAND_FAILED,
                "CDP transport is disconnected"
            )
        }
        try {
            connection!!.sendCommand(method, params ?: JSONObject())
        } catch (error: Exception) {
            throw ChromeDebuggerException(
                ChromeDebuggerErrors.ERROR_COMMAND_FAILED,
                "CDP command '$method' failed: ${error.message ?: error.javaClass.simpleName}"
            )
        }
    }

    fun disconnect(reason: String = "target_closed") {
        connected = false
        connection?.close(reason)
        connection = null
    }

    fun isConnected(): Boolean = connected && connection?.isConnected() == true

    fun setEventListener(listener: ((method: String, params: JSONObject) -> Unit)?) {
        eventListener = listener
    }
}
