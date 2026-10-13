package com.swift.browser.extensionengine

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.UUID

class ExtensionMessageRouter(
    private val registry: ExtensionRegistry,
    private val permissionManager: PermissionManager,
    private val messageBus: MessageBus,
    private val portManager: PortManager,
    private val swRegistry: ServiceWorkerRegistry,
    private val swWakeController: ServiceWorkerWakeController,
    private val mv2BackgroundManager: BackgroundScriptManager? = null
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pendingMessages = ConcurrentHashMap<String, PendingMessage>()

    // For keeping track of active port states
    private val portStates = ConcurrentHashMap<String, PortState>()

    fun getPortState(channelId: String): PortState {
        return portStates[channelId] ?: PortState.DISCONNECTED
    }

    fun setPortState(channelId: String, state: PortState) {
        portStates[channelId] = state
    }

    /**
     * Checks if a web page is allowed to connect/message the target extension based on externally_connectable.
     */
    fun checkExternallyConnectable(sender: ExtensionSender, targetExtId: String): Boolean {
        val targetExt = registry.getExtension(targetExtId) ?: return false
        val spec = targetExt.externallyConnectable

        // If externally_connectable is not declared in manifest, default is deny for web pages
        if (sender.contextType == ExtensionContextType.WEB_PAGE) {
            val url = sender.url ?: return false
            if (spec.matches.isEmpty()) return false
            return spec.matches.any { pattern ->
                permissionManager.hasHostPermission(targetExtId, listOf(pattern), emptyList(), url)
            }
        }

        // If another extension is trying to connect
        if (sender.extensionId != targetExtId) {
            if (spec.ids.isEmpty()) {
                // If spec is entirely empty, standard Chrome allows cross-extension connections by default
                return true
            }
            return spec.ids.contains(sender.extensionId)
        }

        return true
    }

    /**
     * Entry point to handle runtime.sendMessage
     */
    fun handleSendMessage(
        sender: ExtensionSender,
        targetExtensionId: String,
        payload: JSONObject,
        callbackId: String?,
        expectsResponse: Boolean,
        targetTabId: String? = null,
        targetFrameId: Int? = null,
        targetDocumentId: String? = null,
        onResponse: (Any?, String?) -> Unit
    ) {
        val cleanTarget = targetExtensionId.lowercase().trim()
        val ext = registry.getExtension(cleanTarget)

        if (ext == null) {
            onResponse(null, MessagingError.EXTENSION_NOT_FOUND)
            return
        }

        if (!registry.isExtensionEnabled(cleanTarget)) {
            onResponse(null, MessagingError.EXTENSION_DISABLED)
            return
        }

        // Enforce private-mode context propagation (Section 30)
        if (sender.isPrivate && !permissionManager.isAllowedInPrivate(cleanTarget)) {
            onResponse(null, MessagingError.PRIVATE_CONTEXT_INVALID)
            return
        }

        // Validate Sender (Section 7 / 13)
        val senderVal = ExtensionMessagingValidator.validateSender(sender, registry, permissionManager)
        if (senderVal is ExtensionMessagingValidator.ValidationResult.Denied) {
            onResponse(null, MessagingError.PERMISSION_DENIED)
            return
        }

        // Validate Cross-Extension / Externally Connectable
        if (sender.contextType == ExtensionContextType.WEB_PAGE) {
            if (!checkExternallyConnectable(sender, cleanTarget)) {
                onResponse(null, MessagingError.EXTERNAL_CONNECTION_DENIED)
                return
            }
        } else if (sender.extensionId != cleanTarget) {
            val crossVal = ExtensionMessagingValidator.validateCrossExtension(sender, cleanTarget, registry, permissionManager)
            if (crossVal is ExtensionMessagingValidator.ValidationResult.Denied) {
                onResponse(null, MessagingError.EXTERNAL_CONNECTION_DENIED)
                return
            }
            if (!checkExternallyConnectable(sender, cleanTarget)) {
                onResponse(null, MessagingError.EXTERNAL_CONNECTION_DENIED)
                return
            }
        }

        // Create the canonical ExtensionMessage object
        val messageId = UUID.randomUUID().toString()
        val targetGen = registry.getExtensionGeneration(cleanTarget).toString()
        val targetContext = when {
            targetTabId != null -> ExtensionContextType.CONTENT_SCRIPT.name
            ext.isServiceWorker -> ExtensionContextType.SERVICE_WORKER.name
            ext.manifestVersion == 2 && (ext.backgroundScripts.isNotEmpty() || ext.backgroundSpec.page.isNotBlank()) -> ExtensionContextType.BACKGROUND.name
            else -> null
        }
        val msg = ExtensionMessage(
            messageId = messageId,
            extensionId = sender.extensionId,
            targetExtensionId = cleanTarget,
            sender = sender,
            targetContext = targetContext,
            targetTabId = targetTabId,
            targetFrameId = targetFrameId,
            targetDocumentId = targetDocumentId,
            payload = payload,
            privateSessionId = sender.privateSessionId,
            runtimeGenerationId = targetGen,
            callbackId = callbackId,
            expectsResponse = expectsResponse
        )

        // Track pending message for asynchronous response lifecycle. MV2 response messages
        // acquire a browser-side keepalive so an event page cannot unload before the reply.
        if (expectsResponse && callbackId != null) {
            val key = "${cleanTarget}_${targetGen}_$callbackId"
            val mv2Keepalive = if (ext.manifestVersion == 2 && targetTabId == null) {
                mv2BackgroundManager?.acquireKeepalive(
                    extensionId = cleanTarget,
                    isPrivate = sender.isPrivate,
                    privateSessionId = sender.privateSessionId
                )
            } else null
            val pending = PendingMessage(
                messageId = messageId,
                callbackId = callbackId,
                extensionId = cleanTarget,
                runtimeGenerationId = targetGen,
                deadline = msg.deadline,
                onResponse = onResponse,
                onCompleted = mv2Keepalive?.let { token ->
                    { mv2BackgroundManager?.releaseKeepalive(cleanTarget, token, sender.isPrivate, sender.privateSessionId) }
                }
            )
            pendingMessages[key] = pending

            mainHandler.postDelayed({
                val expired = pendingMessages.remove(key)
                if (expired != null) {
                    expired.onCompleted?.invoke()
                    expired.onResponse(null, MessagingError.MESSAGE_TIMEOUT)
                }
            }, msg.deadline - msg.createdAt)
        }

        // Target Context Resolution (Section 7, 8, 9, 10)
        if (ext.isServiceWorker) {
            // MV3: Native-V8 service worker is woken first. MessageBus owns the actual JS message.
            swWakeController.wakeAndExecute(cleanTarget, null, object : ServiceWorkerWakeController.WakeCallback {
                override fun onWoken(extensionId: String, success: Boolean) {
                    if (success) deliverToListeners(msg)
                    else completePendingWithError(cleanTarget, msg.runtimeGenerationId, msg.callbackId, MessagingError.MESSAGE_TARGET_UNAVAILABLE, onResponse)
                }
            })
        } else if (ext.manifestVersion == 2 && targetTabId == null && mv2BackgroundManager != null) {
            // MV2 event/persistent backgrounds are document contexts, not service workers.
            // Wake the dedicated WebView before delivering runtime.onMessage.
            mv2BackgroundManager.wakeForMessage(
                extensionId = cleanTarget,
                bootstrapScript = "",
                isPrivate = sender.isPrivate,
                privateSessionId = sender.privateSessionId,
                permissionManager = permissionManager
            ) { success ->
                if (success) deliverToListeners(msg)
                else completePendingWithError(cleanTarget, msg.runtimeGenerationId, msg.callbackId, MessagingError.MESSAGE_TARGET_UNAVAILABLE, onResponse)
            }
        } else {
            deliverToListeners(msg)
        }
    }

    /**
     * Entry point to deliver a successful response back to the sender
     */
    private fun completePendingWithError(
        extensionId: String,
        generationId: String?,
        callbackId: String?,
        error: String,
        fallback: (Any?, String?) -> Unit
    ) {
        if (callbackId == null) {
            fallback(null, error)
            return
        }
        val key = "${extensionId}_${generationId}_${callbackId}"
        val pending = pendingMessages.remove(key)
        if (pending != null) {
            pending.onCompleted?.invoke()
            pending.onResponse(null, error)
        } else {
            fallback(null, error)
        }
    }

    fun handleSendResponse(extensionId: String, targetCallbackId: String, responseData: Any) {
        val gen = registry.getExtensionGeneration(extensionId).toString()
        val key = "${extensionId}_${gen}_$targetCallbackId"
        var pending = pendingMessages.remove(key)
        if (pending == null) {
            val entry = pendingMessages.entries.firstOrNull { 
                it.key.endsWith("_$targetCallbackId") || it.value.callbackId == targetCallbackId 
            }
            if (entry != null) {
                pendingMessages.remove(entry.key)
                pending = entry.value
            }
        }
        if (pending != null) {
            pending.onCompleted?.invoke()
            pending.onResponse(responseData, null)
        }
    }

    private fun deliverToListeners(msg: ExtensionMessage) {
        // Use the MessageBus transport to broadcast cleanly
        // We will include targetExtensionId in the payload or targetTabId so listeners can filter precisely
        val wrapped = JSONObject().apply {
            put("__targetExtensionId__", msg.targetExtensionId)
            put("__senderExtensionId__", msg.sender.extensionId)
            put("__targetGenerationId__", msg.runtimeGenerationId)
            if (msg.targetTabId != null) put("__targetTabId__", msg.targetTabId)
            if (msg.targetFrameId != null) put("__targetFrameId__", msg.targetFrameId)
            if (msg.targetDocumentId != null) put("__targetDocumentId__", msg.targetDocumentId)
            if (msg.targetContext != null) put("__targetContextType__", msg.targetContext)
            put("__payload__", msg.payload)
        }
        messageBus.broadcastMessage(msg.sender, wrapped, msg.callbackId, msg.targetTabId ?: msg.sender.tabId)
    }

    /**
     * Clean up private sessions
     */
    fun cleanupPrivateSession(privateSessionId: String) {
        val keysToRemove = pendingMessages.filter { it.value.runtimeGenerationId == privateSessionId }.keys
        keysToRemove.forEach { pendingMessages.remove(it) }

        val activePorts = portManager.registry.getAllActive()
        activePorts.forEach { port ->
            if (port.sender?.privateSessionId == privateSessionId) {
                portManager.disconnect(port.channelId)
                portStates.remove(port.channelId)
            }
        }
    }
}

