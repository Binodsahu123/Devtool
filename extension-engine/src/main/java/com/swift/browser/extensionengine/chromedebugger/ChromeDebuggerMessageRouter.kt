package com.swift.browser.extensionengine.chromedebugger

import com.swift.browser.extensionengine.ExtensionSender
import org.json.JSONArray
import org.json.JSONObject

/**
 * Message router and argument deserializer for chrome.debugger bridge calls.
 */
class ChromeDebuggerMessageRouter(
    private val adapter: ChromeDebuggerAdapter
) {
    suspend fun route(
        sender: ExtensionSender,
        api: String,
        args: JSONArray
    ): Any? {
        val extId = sender.extensionId

        return when (api) {
            "debugger.attach" -> {
                val targetObj = args.optJSONObject(0)
                    ?: throw ChromeDebuggerException(
                        ChromeDebuggerErrors.ERROR_INVALID_ARGUMENTS,
                        "Missing required 'target' argument in debugger.attach"
                    )
                val debuggee = ChromeDebuggee.fromJsonObject(targetObj)
                val requiredVersion = args.optString(1, "1.3")
                adapter.attach(extId, debuggee, requiredVersion)
                JSONObject()
            }

            "debugger.detach" -> {
                val targetObj = args.optJSONObject(0)
                    ?: throw ChromeDebuggerException(
                        ChromeDebuggerErrors.ERROR_INVALID_ARGUMENTS,
                        "Missing required 'target' argument in debugger.detach"
                    )
                val debuggee = ChromeDebuggee.fromJsonObject(targetObj)
                adapter.detach(extId, debuggee)
                JSONObject()
            }

            "debugger.sendCommand" -> {
                val targetObj = args.optJSONObject(0)
                    ?: throw ChromeDebuggerException(
                        ChromeDebuggerErrors.ERROR_INVALID_ARGUMENTS,
                        "Missing required 'target' argument in debugger.sendCommand"
                    )
                val debuggee = ChromeDebuggee.fromJsonObject(targetObj)
                val method = args.optString(1, "")
                if (method.isBlank()) {
                    throw ChromeDebuggerException(
                        ChromeDebuggerErrors.ERROR_INVALID_ARGUMENTS,
                        "Missing required 'method' argument in debugger.sendCommand"
                    )
                }
                val commandParams = args.optJSONObject(2) ?: JSONObject()
                adapter.sendCommand(extId, debuggee, method, commandParams)
            }

            "debugger.getTargets" -> {
                val targets = adapter.getTargets(extId)
                val array = JSONArray()
                targets.forEach { target ->
                    array.put(target.toJsonObject())
                }
                array
            }

            else -> throw UnsupportedOperationException("Unknown chrome.debugger API: $api")
        }
    }
}
