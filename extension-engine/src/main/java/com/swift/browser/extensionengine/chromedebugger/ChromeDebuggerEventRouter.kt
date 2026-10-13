package com.swift.browser.extensionengine.chromedebugger

import com.swift.browser.extensionengine.EventManager
import org.json.JSONObject

/**
 * Routes CDP events and detachment notifications to the chrome.debugger event listeners.
 */
class ChromeDebuggerEventRouter(
    private val eventManager: EventManager
) {

    /**
     * Dispatches chrome.debugger.onEvent to the listening extension.
     * Parameters: (Debuggee source, String method, Object params)
     */
    fun dispatchDebuggerEvent(
        extensionId: String,
        debuggee: ChromeDebuggee,
        event: ChromeDebuggerCdpEvent
    ) {
        val payload = JSONObject().apply {
            put("source", debuggee.toJsonObject())
            put("method", event.method)
            put("params", event.params)
        }
        eventManager.triggerEventForExtension(extensionId, "debugger.onEvent", payload)
    }

    /**
     * Dispatches chrome.debugger.onDetach to the listening extension.
     * Parameters: (Debuggee source, String reason)
     */
    fun dispatchDebuggerDetach(
        extensionId: String,
        debuggee: ChromeDebuggee,
        reason: String
    ) {
        val payload = JSONObject().apply {
            put("source", debuggee.toJsonObject())
            put("reason", reason)
        }
        eventManager.triggerEventForExtension(extensionId, "debugger.onDetach", payload)
    }
}
