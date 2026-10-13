package com.swift.browser.extensionengine.chromedebugger

import org.json.JSONObject

/**
 * Identifies a debuggee target (Tab, Extension, or TargetId).
 * Matches chrome.debugger.Debuggee specification.
 */
data class ChromeDebuggee(
    val tabId: Int? = null,
    val extensionId: String? = null,
    val targetId: String? = null
) {
    fun toIdentifier(): String {
        return when {
            tabId != null -> "tab_$tabId"
            !extensionId.isNullOrBlank() -> "ext_$extensionId"
            !targetId.isNullOrBlank() -> targetId
            else -> "unknown_target"
        }
    }

    fun toJsonObject(): JSONObject {
        return JSONObject().apply {
            tabId?.let { put("tabId", it) }
            extensionId?.let { put("extensionId", it) }
            targetId?.let { put("targetId", it) }
        }
    }

    companion object {
        fun fromJsonObject(json: JSONObject?): ChromeDebuggee {
            if (json == null) return ChromeDebuggee()
            val tab = if (json.has("tabId") && !json.isNull("tabId")) json.optInt("tabId") else null
            val ext = if (json.has("extensionId") && !json.isNull("extensionId")) json.optString("extensionId").takeIf { it.isNotBlank() } else null
            val tgt = if (json.has("targetId") && !json.isNull("targetId")) json.optString("targetId").takeIf { it.isNotBlank() } else null
            return ChromeDebuggee(tabId = tab, extensionId = ext, targetId = tgt)
        }
    }
}

/**
 * Target information returned by chrome.debugger.getTargets().
 * Matches chrome.debugger.TargetInfo specification.
 */
data class ChromeDebuggerTargetInfo(
    val id: String,
    val type: String, // "page", "background_page", "service_worker", "iframe", "other"
    val title: String,
    val url: String,
    val attached: Boolean,
    val tabId: Int? = null,
    val extensionId: String? = null,
    val faviconUrl: String? = null
) {
    fun toJsonObject(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("type", type)
            put("title", title)
            put("url", url)
            put("attached", attached)
            tabId?.let { put("tabId", it) }
            extensionId?.let { put("extensionId", it) }
            faviconUrl?.let { put("faviconUrl", it) }
        }
    }
}

/**
 * Internal Chrome DevTools Protocol request frame.
 */
data class CdpRequest(
    val id: Long,
    val method: String,
    val params: JSONObject = JSONObject(),
    val sessionId: String? = null
) {
    fun toJsonString(): String {
        val json = JSONObject()
        json.put("id", id)
        json.put("method", method)
        json.put("params", params)
        sessionId?.let { json.put("sessionId", it) }
        return json.toString()
    }
}

/**
 * Internal Chrome DevTools Protocol response frame.
 */
data class ChromeDebuggerCdpResponse(
    val id: Long,
    val result: JSONObject? = null,
    val error: ChromeDebuggerCdpErrorDetails? = null,
    val sessionId: String? = null
) {
    val isSuccess: Boolean get() = error == null

    fun toResultJsonObject(): JSONObject {
        return result ?: JSONObject()
    }
}

data class ChromeDebuggerCdpErrorDetails(
    val code: Int,
    val message: String,
    val data: String? = null
) {
    fun toJsonObject(): JSONObject {
        return JSONObject().apply {
            put("code", code)
            put("message", message)
            data?.let { put("data", it) }
        }
    }
}

/**
 * Event emitted by CDP target.
 */
data class ChromeDebuggerCdpEvent(
    val method: String,
    val params: JSONObject = JSONObject(),
    val sessionId: String? = null
)

/**
 * Active Debugger Session representation.
 */
data class ChromeDebuggerSession(
    val sessionId: String,
    val extensionId: String,
    val debuggee: ChromeDebuggee,
    val targetId: String,
    val requiredVersion: String,
    val attachedAt: Long = System.currentTimeMillis(),
    @Volatile var isActive: Boolean = true
)
