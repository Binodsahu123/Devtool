package com.swift.browser.extensionengine

import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

interface MessageListener {
    fun onMessageReceived(extensionId: String, senderTabId: String?, message: JSONObject, callbackId: String?, targetTabId: String?)
    fun onStructuredMessageReceived(sender: ExtensionSender, message: JSONObject, callbackId: String?, targetTabId: String?) {
        val targetExtId = message.optString("__targetExtensionId__", message.optString("__targetExtensionId", sender.extensionId))
        onMessageReceived(targetExtId, sender.tabId, message, callbackId, targetTabId)
    }
    fun onResponseReceived(extensionId: String, callbackId: String, response: Any)
}

interface PortConnectionListener {
    fun onPortConnect(
        extensionId: String,
        channelId: String,
        portName: String,
        senderId: String,
        targetContextType: ExtensionContextType?,
        targetTabId: String?,
        targetFrameId: Int?,
        targetDocumentId: String?
    )
    fun onPortMessage(
        channelId: String,
        message: JSONObject,
        targetExtensionId: String? = null,
        targetContextType: ExtensionContextType? = null,
        targetTabId: String? = null,
        targetFrameId: Int? = null,
        targetDocumentId: String? = null
    )
    fun onPortDisconnect(
        channelId: String,
        targetExtensionId: String? = null,
        targetContextType: ExtensionContextType? = null,
        targetTabId: String? = null,
        targetFrameId: Int? = null,
        targetDocumentId: String? = null
    )
}

class MessageBus {
    private val listeners = CopyOnWriteArrayList<MessageListener>()
    private val portListeners = CopyOnWriteArrayList<PortConnectionListener>()

    fun clear() {
        listeners.clear()
        portListeners.clear()
    }

    fun registerListener(listener: MessageListener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
    }

    fun unregisterListener(listener: MessageListener) {
        listeners.remove(listener)
    }

    fun registerPortListener(listener: PortConnectionListener) {
        if (!portListeners.contains(listener)) {
            portListeners.add(listener)
        }
    }

    fun unregisterPortListener(listener: PortConnectionListener) {
        portListeners.remove(listener)
    }

    fun broadcastMessage(
        extensionId: String,
        senderTabId: String?,
        message: JSONObject,
        callbackId: String? = null,
        targetTabId: String? = null
    ) {
        listeners.forEach { listener ->
            try {
                listener.onMessageReceived(extensionId, senderTabId, message, callbackId, targetTabId)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun broadcastMessage(
        sender: ExtensionSender,
        message: JSONObject,
        callbackId: String? = null,
        targetTabId: String? = null
    ) {
        listeners.forEach { listener ->
            try {
                listener.onStructuredMessageReceived(sender, message, callbackId, targetTabId)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun broadcastResponse(extensionId: String, callbackId: String, response: Any) {
        listeners.forEach { listener ->
            try {
                listener.onResponseReceived(extensionId, callbackId, response)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun broadcastPortConnect(
        extensionId: String,
        channelId: String,
        portName: String,
        senderId: String,
        targetContextType: ExtensionContextType? = null,
        targetTabId: String? = null,
        targetFrameId: Int? = null,
        targetDocumentId: String? = null
    ) {
        portListeners.forEach { listener ->
            try {
                listener.onPortConnect(extensionId, channelId, portName, senderId, targetContextType, targetTabId, targetFrameId, targetDocumentId)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun broadcastPortMessage(
        channelId: String,
        message: JSONObject,
        targetExtensionId: String? = null,
        targetContextType: ExtensionContextType? = null,
        targetTabId: String? = null,
        targetFrameId: Int? = null,
        targetDocumentId: String? = null
    ) {
        portListeners.forEach { listener ->
            try {
                listener.onPortMessage(channelId, message, targetExtensionId, targetContextType, targetTabId, targetFrameId, targetDocumentId)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun broadcastPortDisconnect(
        channelId: String,
        targetExtensionId: String? = null,
        targetContextType: ExtensionContextType? = null,
        targetTabId: String? = null,
        targetFrameId: Int? = null,
        targetDocumentId: String? = null
    ) {
        portListeners.forEach { listener ->
            try {
                listener.onPortDisconnect(channelId, targetExtensionId, targetContextType, targetTabId, targetFrameId, targetDocumentId)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
