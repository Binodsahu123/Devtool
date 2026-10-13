package com.swift.browser.extensionengine.chromedebugger

import org.json.JSONObject

/**
 * Public interface contract for chrome.debugger extension subsystem.
 */
interface ChromeDebuggerApi {
    suspend fun attach(extensionId: String, target: ChromeDebuggee, requiredVersion: String)
    fun detach(extensionId: String, target: ChromeDebuggee)
    suspend fun sendCommand(extensionId: String, target: ChromeDebuggee, method: String, params: JSONObject): JSONObject
    suspend fun getTargets(extensionId: String): List<ChromeDebuggerTargetInfo>
}
