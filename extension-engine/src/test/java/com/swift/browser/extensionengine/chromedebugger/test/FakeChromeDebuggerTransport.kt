package com.swift.browser.extensionengine.chromedebugger.test

import com.swift.browser.extensionengine.chromedebugger.ChromeDebuggerCdpEvent
import com.swift.browser.extensionengine.chromedebugger.ChromeDebuggerTargetManager
import com.swift.browser.extensionengine.chromedebugger.ChromeDebuggerTransport
import com.swift.browser.extensionengine.chromedebugger.ChromeDebuggerTransportFactory
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Deterministic test-only transport. Production code never references this class; production's
 * default factory is ChromeDebuggerCdpTransport backed by the authenticated WebSocket bridge.
 */
class FakeChromeDebuggerTransportFactory : ChromeDebuggerTransportFactory {
    val transports = ConcurrentHashMap<String, FakeChromeDebuggerTransport>()

    override fun create(
        target: ChromeDebuggerTargetManager.ResolvedTarget,
        onEvent: (ChromeDebuggerCdpEvent) -> Unit
    ): ChromeDebuggerTransport = FakeChromeDebuggerTransport(target.targetId, onEvent).also {
        transports[target.targetId] = it
    }
}

class FakeChromeDebuggerTransport internal constructor(
    private val targetId: String,
    private val onEvent: (ChromeDebuggerCdpEvent) -> Unit
) : ChromeDebuggerTransport {
    @Volatile private var connected = false

    override suspend fun connect() { connected = true }

    override suspend fun sendCommand(method: String, params: JSONObject, timeoutMs: Long): JSONObject {
        check(connected) { "Test CDP transport is disconnected" }
        return when (method) {
            "Page.navigate" -> JSONObject().put("frameId", "test-frame-$targetId")
            "DOM.getDocument" -> JSONObject().put("root", JSONObject().put("nodeId", 1))
            "CSS.getComputedStyleForNode" -> JSONObject().put("computedStyle", JSONArray())
            "Target.getTargets" -> JSONObject().put("targetInfos", JSONArray())
            else -> JSONObject()
        }
    }

    override fun close(reason: String) { connected = false }
    override fun isConnected(): Boolean = connected

    fun emit(method: String, params: JSONObject = JSONObject()) {
        onEvent(ChromeDebuggerCdpEvent(method, params))
    }
}
