package com.swift.browser.extensionengine

import org.json.JSONObject

class PortMessenger(private val messageBus: MessageBus) {

    fun sendConnect(
        extensionId: String,
        channelId: String,
        portName: String,
        senderId: String,
        targetContextType: ExtensionContextType? = null,
        targetTabId: String? = null,
        targetFrameId: Int? = null,
        targetDocumentId: String? = null
    ) {
        messageBus.broadcastPortConnect(extensionId, channelId, portName, senderId, targetContextType, targetTabId, targetFrameId, targetDocumentId)
    }

    fun postMessage(
        channelId: String,
        message: JSONObject,
        targetExtensionId: String? = null,
        targetContextType: ExtensionContextType? = null,
        targetTabId: String? = null,
        targetFrameId: Int? = null,
        targetDocumentId: String? = null
    ) {
        messageBus.broadcastPortMessage(channelId, message, targetExtensionId, targetContextType, targetTabId, targetFrameId, targetDocumentId)
    }

    fun sendDisconnect(
        channelId: String,
        targetExtensionId: String? = null,
        targetContextType: ExtensionContextType? = null,
        targetTabId: String? = null,
        targetFrameId: Int? = null,
        targetDocumentId: String? = null
    ) {
        messageBus.broadcastPortDisconnect(channelId, targetExtensionId, targetContextType, targetTabId, targetFrameId, targetDocumentId)
    }
}
