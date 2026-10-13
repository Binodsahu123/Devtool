package com.swift.browser.extensionengine

import org.json.JSONObject

data class PortConnection(
    val channelId: String,
    val name: String,
    val senderId: String,
    val targetId: String,
    val sender: ExtensionSender? = null,
    val tabId: String? = null,
    val frameId: Int? = null,
    val targetContextType: ExtensionContextType? = null,
    val targetTabId: String? = null,
    val targetFrameId: Int? = null,
    val targetDocumentId: String? = null,
    val privateSessionId: String? = null,
    val runtimeGenerationId: String? = null,
    var isDisconnected: Boolean = false
) {
    fun toJSONObject(): JSONObject {
        return JSONObject().apply {
            put("channelId", channelId)
            put("name", name)
            put("senderId", senderId)
            put("targetId", targetId)
            sender?.let { put("sender", it.toJSONObject()) }
            tabId?.let { put("tabId", it) }
            frameId?.let { put("frameId", it) }
            targetContextType?.let { put("targetContextType", it.name) }
            targetTabId?.let { put("targetTabId", it) }
            targetFrameId?.let { put("targetFrameId", it) }
            targetDocumentId?.let { put("targetDocumentId", it) }
            privateSessionId?.let { put("privateSessionId", it) }
            runtimeGenerationId?.let { put("runtimeGenerationId", it) }
            put("isDisconnected", isDisconnected)
        }
    }
}
