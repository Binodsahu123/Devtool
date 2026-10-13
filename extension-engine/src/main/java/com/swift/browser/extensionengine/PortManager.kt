package com.swift.browser.extensionengine

import org.json.JSONObject

class PortManager(private val messageBus: MessageBus) {
    @Volatile private var generationProvider: ((String)->Int)? = null
    fun setWorkerGenerationProvider(p:(String)->Int){ generationProvider=p }
    val registry = PortRegistry()
    val messenger = PortMessenger(messageBus)

    fun connect(
        extensionId: String,
        channelId: String,
        portName: String,
        senderId: String,
        sender: ExtensionSender? = null,
        tabId: String? = null,
        frameId: Int? = null,
        targetContextType: ExtensionContextType? = null,
        targetTabId: String? = null,
        targetFrameId: Int? = null,
        targetDocumentId: String? = null,
        emitConnect: Boolean = true
    ) {
        val effectiveSender = sender ?: ExtensionSender(
            extensionId = senderId,
            tabId = tabId,
            frameId = frameId
        )
        val effectiveTabId = tabId ?: effectiveSender.tabId
        val effectiveFrameId = frameId ?: effectiveSender.frameId
        val connection = PortConnection(
            channelId = channelId,
            name = portName,
            senderId = senderId,
            targetId = extensionId,
            sender = effectiveSender,
            tabId = effectiveTabId,
            frameId = effectiveFrameId,
            targetContextType = targetContextType,
            targetTabId = targetTabId,
            targetFrameId = targetFrameId,
            targetDocumentId = targetDocumentId,
            privateSessionId = effectiveSender.privateSessionId,
            runtimeGenerationId = generationProvider?.invoke(extensionId)?.takeIf{it>0}?.toString()
        )
        registry.register(connection)
        if (emitConnect) {
            messenger.sendConnect(
                extensionId = extensionId,
                channelId = channelId,
                portName = portName,
                senderId = senderId,
                targetContextType = targetContextType,
                targetTabId = targetTabId ?: effectiveSender.tabId,
                targetFrameId = targetFrameId,
                targetDocumentId = targetDocumentId
            )
        }
    }

    fun postMessage(channelId: String, message: JSONObject, senderExtensionId: String? = null) {
        val conn = registry.get(channelId) ?: return
        if (conn.isDisconnected) return
        val recorded = conn.runtimeGenerationId?.toIntOrNull()
        if (recorded != null && generationProvider?.invoke(conn.targetId) ?: recorded != recorded) { disconnect(channelId); return }

        if (senderExtensionId != null &&
            !conn.senderId.equals(senderExtensionId, ignoreCase = true) &&
            !conn.targetId.equals(senderExtensionId, ignoreCase = true) &&
            !conn.sender?.extensionId.equals(senderExtensionId, ignoreCase = true)
        ) return

        // Route only to the opposite endpoint. Never echo a port message back to its sender.
        val senderIsSource = senderExtensionId != null &&
            (conn.senderId.equals(senderExtensionId, ignoreCase = true) ||
             conn.sender?.extensionId.equals(senderExtensionId, ignoreCase = true))

        val targetExtensionId = if (senderIsSource) conn.targetId else conn.senderId
        val targetContextType = if (senderIsSource) conn.targetContextType else conn.sender?.contextType
        val targetTabId = if (senderIsSource) conn.targetTabId else conn.sender?.tabId
        val targetFrameId = if (senderIsSource) conn.targetFrameId else conn.sender?.frameId
        val targetDocumentId = if (senderIsSource) conn.targetDocumentId else conn.sender?.documentId

        messenger.postMessage(
            channelId = channelId,
            message = message,
            targetExtensionId = targetExtensionId,
            targetContextType = targetContextType,
            targetTabId = targetTabId,
            targetFrameId = targetFrameId,
            targetDocumentId = targetDocumentId
        )
    }

    fun disconnect(channelId: String, senderExtensionId: String? = null) {
        val conn = registry.get(channelId) ?: return
        if (senderExtensionId != null &&
            !conn.senderId.equals(senderExtensionId, ignoreCase = true) &&
            !conn.targetId.equals(senderExtensionId, ignoreCase = true) &&
            !conn.sender?.extensionId.equals(senderExtensionId, ignoreCase = true)
        ) return

        val senderIsSource = senderExtensionId != null &&
            (conn.senderId.equals(senderExtensionId, ignoreCase = true) ||
             conn.sender?.extensionId.equals(senderExtensionId, ignoreCase = true))

        if (registry.remove(channelId) != null) {
            conn.isDisconnected = true
            val targetExtensionId = if (senderIsSource) conn.targetId else if (senderExtensionId == null) null else conn.senderId
            val targetContextType = if (senderIsSource) conn.targetContextType else if (senderExtensionId == null) null else conn.sender?.contextType
            val targetTabId = if (senderIsSource) conn.targetTabId else if (senderExtensionId == null) null else conn.sender?.tabId
            val targetFrameId = if (senderIsSource) conn.targetFrameId else if (senderExtensionId == null) null else conn.sender?.frameId
            val targetDocumentId = if (senderIsSource) conn.targetDocumentId else if (senderExtensionId == null) null else conn.sender?.documentId
            messenger.sendDisconnect(channelId, targetExtensionId, targetContextType, targetTabId, targetFrameId, targetDocumentId)
        }
    }

    fun cleanupForTab(tabId: String) {
        registry.getAllActive().filter { it.tabId == tabId || it.sender?.tabId == tabId || it.targetTabId == tabId }
            .forEach { disconnect(it.channelId) }
    }

    fun cleanupForFrame(tabId: String, frameId: Int) {
        registry.getAllActive().filter {
            (it.tabId == tabId || it.sender?.tabId == tabId || it.targetTabId == tabId) &&
                (it.frameId == frameId || it.sender?.frameId == frameId || it.targetFrameId == frameId)
        }.forEach { disconnect(it.channelId) }
    }

    fun cleanupForExtension(extensionId: String) {
        registry.getAllActive().filter {
            it.targetId.equals(extensionId, ignoreCase = true) ||
                it.senderId.equals(extensionId, ignoreCase = true) ||
                it.sender?.extensionId.equals(extensionId, ignoreCase = true)
        }.forEach { disconnect(it.channelId) }
    }

    fun hasActiveForExtension(extensionId: String): Boolean {
        return registry.getAllActive().any { connection ->
            connection.senderId.equals(extensionId, ignoreCase = true) ||
                connection.targetId.equals(extensionId, ignoreCase = true) ||
                connection.sender?.extensionId?.equals(extensionId, ignoreCase = true) == true
        }
    }
}
