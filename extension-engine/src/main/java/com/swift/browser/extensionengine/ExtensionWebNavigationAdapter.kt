package com.swift.browser.extensionengine

import com.swift.browser.tabengine.api.TabEngineApi
import org.json.JSONArray
import org.json.JSONObject

data class WebNavigationFrameInfo(
    val frameId: Int,
    val parentFrameId: Int = -1,
    val url: String = "",
    val origin: String = "",
    val errorOccurred: Boolean = false,
    val documentId: String = "",
    val lifecycleState: String = "UNKNOWN",
    val navigationId: Long = 0L
)

/** API adapter over the single callback-fed WebNavigationStateMachine. */
class ExtensionWebNavigationAdapter(
    private val permissionAdapter: ExtensionPermissionAdapter,
    private val registry: ExtensionRegistry,
    private val eventManager: EventManager,
    private val tabEngine: TabEngineApi? = null
) {
    val stateMachine: WebNavigationStateMachine = WebNavigationStateMachine { eventName, payload, url, isPrivate ->
        dispatchEventToExtensions(eventName, payload, url, isPrivate)
    }

    private fun verifyWebNavigationPermission(sender: ExtensionSender) {
        registry.getExtension(sender.extensionId)
            ?: throw SecurityException("SecurityError: Extension ${sender.extensionId} not found.")
        if (!permissionAdapter.hasApiPermission(sender.extensionId, "webNavigation", sender.isPrivate)) {
            throw SecurityException("SecurityError: Extension does not have 'webNavigation' permission.")
        }
        if (sender.isPrivate && !permissionAdapter.isAllowedInPrivate(sender.extensionId)) {
            throw SecurityException("SecurityError: Extension is not allowed in private mode.")
        }
    }

    /** Called only by a browser/provider callback that has a real frame identity. */
    fun registerFrame(tabId: String, frameId: Int, parentFrameId: Int = -1, url: String = "", documentId: String? = null, providerToken: String? = null) {
        stateMachine.registerFrame(tabId, frameId, parentFrameId, url, documentId, providerToken)
    }

    fun markFrameError(tabId: String, frameId: Int, errorOccurred: Boolean = true) {
        if (frameId != 0 && stateMachine.getFrameIdentity(tabId, frameId) == null) return
        if (errorOccurred) stateMachine.onErrorOccurred(tabId, stateMachine.getFrameIdentity(tabId, frameId)?.url ?: "", "FRAME_ERROR", false, frameId)
    }

    fun clearFramesForTab(tabId: String) = stateMachine.clearTab(tabId)

    fun unregisterFrame(tabId: String, frameId: Int): Boolean = stateMachine.removeFrameById(tabId, frameId)

    /** Provider-only bookkeeping used by the WebView frame/world bridge. */
    fun markProviderFrameInventoryComplete(tabId: String) = stateMachine.markFrameInventoryCompleteForProvider(tabId)

    fun markProviderDocumentIdentityAvailable(tabId: String) = stateMachine.markDocumentIdentityAvailable(tabId)

    fun getFrame(sender: ExtensionSender, details: JSONObject): JSONObject? {
        verifyWebNavigationPermission(sender)
        val tabIdRaw = details.opt("tabId")?.toString() ?: return null
        val frameId = details.optInt("frameId", 0)
        val uuid = TabIdMapper.getUuidFromString(tabIdRaw)
        val tab = tabEngine?.getTab(uuid) ?: tabEngine?.getTab(tabIdRaw)
        if (tab != null && tab.isPrivate != sender.isPrivate && !permissionAdapter.isAllowedInPrivate(sender.extensionId)) return null

        if (frameId != 0 && stateMachine.getFrameIdentity(uuid, frameId) == null && stateMachine.getFrameIdentity(tabIdRaw, frameId) == null) {
            return null
        }
        var frame = stateMachine.getFrameIdentity(uuid, frameId) ?: stateMachine.getFrameIdentity(tabIdRaw, frameId)
        if (frame == null && frameId == 0 && !tab?.url.isNullOrBlank()) {
            frame = stateMachine.ensureTopFrame(uuid, tab!!.url)
        }
        if (frame == null) return null
        val url = frame.url
        if (url.isNotBlank() && !permissionAdapter.hasHostPermission(sender.extensionId, url, sender.isPrivate)) {
            return JSONObject().apply {
                put("errorOccurred", frame.lifecycleState == FrameLifecycleState.FAILED)
                put("frameId", frame.frameId)
                put("parentFrameId", frame.parentFrameId)
                put("url", "")
                put("origin", frame.origin)
                put("lifecycleState", frame.lifecycleState.name)
                put("documentId", frame.documentId ?: JSONObject.NULL)
            }
        }
        return frameJson(frame)
    }

    fun getAllFrames(sender: ExtensionSender, details: JSONObject): JSONArray {
        verifyWebNavigationPermission(sender)
        val tabIdRaw = details.opt("tabId")?.toString() ?: return JSONArray()
        val uuid = TabIdMapper.getUuidFromString(tabIdRaw)
        val tab = tabEngine?.getTab(uuid) ?: tabEngine?.getTab(tabIdRaw)
        if (tab != null && tab.isPrivate != sender.isPrivate && !permissionAdapter.isAllowedInPrivate(sender.extensionId)) return JSONArray()
        if (stateMachine.getAllFrames(uuid).isEmpty() && stateMachine.getAllFrames(tabIdRaw).isEmpty() && !tab?.url.isNullOrBlank()) {
            stateMachine.ensureTopFrame(uuid, tab!!.url)
        }
        val result = JSONArray()
        stateMachine.getAllFrames(uuid).ifEmpty { stateMachine.getAllFrames(tabIdRaw) }
            .filter { frame -> frame.url.isBlank() || permissionAdapter.hasHostPermission(sender.extensionId, frame.url, sender.isPrivate) }
            .forEach { result.put(frameJson(it)) }
        return result
    }

    private fun frameJson(frame: FrameIdentity): JSONObject = JSONObject().apply {
        put("errorOccurred", frame.lifecycleState == FrameLifecycleState.FAILED)
        put("frameId", frame.frameId)
        put("parentFrameId", frame.parentFrameId)
        put("url", frame.url)
        put("origin", frame.origin)
        put("lifecycleState", frame.lifecycleState.name)
        put("navigationId", frame.navigationId)
        put("documentId", frame.documentId ?: JSONObject.NULL)
    }

    private fun frameJson(frame: WebNavigationFrameInfo): JSONObject = JSONObject().apply {
        put("errorOccurred", frame.errorOccurred)
        put("frameId", frame.frameId)
        put("parentFrameId", frame.parentFrameId)
        put("url", frame.url)
        put("origin", frame.origin)
        put("lifecycleState", frame.lifecycleState)
        put("navigationId", frame.navigationId)
        put("documentId", if (frame.documentId.isNotEmpty()) frame.documentId else JSONObject.NULL)
    }

    private fun dispatchEventToExtensions(cleanEvent: String, payload: JSONObject, url: String, isPrivate: Boolean) {
        for (ext in registry.getEnabledExtensions()) {
            val extId = ext.id.lowercase().trim()
            if (isPrivate && !permissionAdapter.isAllowedInPrivate(extId)) continue
            if (!permissionAdapter.hasApiPermission(extId, "webNavigation", isPrivate)) continue
            val eventPayload = if (url.isBlank() || permissionAdapter.hasHostPermission(extId, url, isPrivate)) payload
            else JSONObject(payload.toString()).apply { put("url", "") }
            eventManager.triggerEventForExtension(extId, cleanEvent, eventPayload)
        }
    }

    fun notifyNavigationEvent(eventName: String, tabId: String, url: String, isPrivate: Boolean = false) {
        when (eventName.removePrefix("webNavigation.")) {
            "onBeforeNavigate" -> notifyBeforeNavigate(tabId, url, isPrivate)
            "onCommitted" -> notifyCommitted(tabId, url, isPrivate)
            "onDOMContentLoaded" -> notifyDOMContentLoaded(tabId, url, isPrivate)
            "onCompleted" -> notifyCompleted(tabId, url, isPrivate)
            "onErrorOccurred" -> notifyErrorOccurred(tabId, url, "net::ERR_FAILED", isPrivate)
            "onReferenceFragmentUpdated" -> notifyReferenceFragmentUpdated(tabId, url, isPrivate)
            "onHistoryStateUpdated" -> notifyHistoryStateUpdated(tabId, url, isPrivate)
            else -> throw IllegalArgumentException("NAVIGATION_EVENT_UNSUPPORTED")
        }
    }

    fun notifyBeforeNavigate(tabId: String, url: String, isPrivate: Boolean = false, frameId: Int = 0, parentFrameId: Int = -1): String =
        if (stateMachine.onBeforeNavigate(tabId, url, isPrivate, frameId, parentFrameId)) NAVIGATION_TARGET_SUCCESS else if (frameId != 0) FRAME_NOT_FOUND else "NAVIGATION_STATE_UNAVAILABLE"

    fun notifyCommitted(tabId: String, url: String, isPrivate: Boolean = false, frameId: Int = 0, transitionType: String = "link"): String =
        if (stateMachine.onCommitted(tabId, url, isPrivate, frameId, transitionType)) NAVIGATION_TARGET_SUCCESS else if (frameId != 0) FRAME_NOT_FOUND else "NAVIGATION_STATE_UNAVAILABLE"

    fun notifyDOMContentLoaded(tabId: String, url: String, isPrivate: Boolean = false, frameId: Int = 0): String =
        if (stateMachine.onDOMContentLoaded(tabId, url, isPrivate, frameId)) NAVIGATION_TARGET_SUCCESS else if (frameId != 0) FRAME_NOT_FOUND else "NAVIGATION_STATE_UNAVAILABLE"

    fun notifyCompleted(tabId: String, url: String, isPrivate: Boolean = false, frameId: Int = 0): String =
        if (stateMachine.onCompleted(tabId, url, isPrivate, frameId)) NAVIGATION_TARGET_SUCCESS else if (frameId != 0) FRAME_NOT_FOUND else "NAVIGATION_STATE_UNAVAILABLE"

    fun notifyErrorOccurred(tabId: String, url: String, error: String, isPrivate: Boolean = false, frameId: Int = 0): String =
        if (stateMachine.onErrorOccurred(tabId, url, error, isPrivate, frameId)) NAVIGATION_TARGET_SUCCESS else if (frameId != 0) FRAME_NOT_FOUND else "NAVIGATION_STATE_UNAVAILABLE"

    fun notifyCreatedNavigationTarget(sourceTabId: String, newTabId: String, url: String, isPrivate: Boolean = false) =
        stateMachine.onCreatedNavigationTarget(sourceTabId, newTabId, url, isPrivate)

    fun notifyReferenceFragmentUpdated(tabId: String, url: String, isPrivate: Boolean = false) =
        stateMachine.onReferenceFragmentUpdated(tabId, url, isPrivate)

    fun notifyHistoryStateUpdated(tabId: String, url: String, isPrivate: Boolean = false, transitionType: String = "link") =
        stateMachine.onHistoryStateUpdated(tabId, url, isPrivate, transitionType)
}
