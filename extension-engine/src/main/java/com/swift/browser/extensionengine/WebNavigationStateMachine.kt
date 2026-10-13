package com.swift.browser.extensionengine

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

enum class FrameLifecycleState {
    UNKNOWN,
    STARTED,
    DOM_READY,
    COMMITTED,
    COMPLETED,
    FAILED,
    DESTROYED
}

/**
 * Single authoritative browser-owned logical frame/document identity.
 * providerToken/documentTransportToken are transport handles only; they are never exposed as
 * Chrome frameId/documentId authorities.
 */
data class FrameIdentity(
    val tabId: String,
    val frameId: Int,
    var parentFrameId: Int = -1,
    var providerToken: String? = null,
    var documentTransportToken: String? = null,
    var documentId: String? = null,
    var url: String = "",
    var origin: String = "",
    var lifecycleState: FrameLifecycleState = FrameLifecycleState.UNKNOWN,
    var navigationId: Long = 0L,
    var generation: Long = 0L,
    var lastError: String? = null,
    var privateSession: Boolean = false,
    var createdAt: Long = System.currentTimeMillis(),
    var committed: Boolean = false,
    var destroyed: Boolean = false
)

typealias FrameNavigationContext = FrameIdentity
typealias NavigationState = FrameLifecycleState

data class FrameTargetResolution(
    val status: String,
    val frames: List<FrameIdentity> = emptyList(),
    val documentId: String? = null
)

const val FRAME_NOT_FOUND = "FRAME_NOT_FOUND"
const val DOCUMENT_NOT_FOUND = "DOCUMENT_NOT_FOUND"
const val DOCUMENT_DESTROYED = "DOCUMENT_DESTROYED"
const val FRAME_TARGETING_UNSUPPORTED = "FRAME_TARGETING_UNSUPPORTED"
const val DOCUMENT_TARGETING_UNSUPPORTED = "DOCUMENT_TARGETING_UNSUPPORTED"
const val NAVIGATION_TARGET_SUCCESS = "SUCCESS"

/** Canonical callback-fed frame/document state machine. */
class WebNavigationStateMachine(
    private val eventDispatcher: (eventName: String, payload: JSONObject, url: String, isPrivate: Boolean) -> Unit
) {
    private val tabFrames = ConcurrentHashMap<String, ConcurrentHashMap<Int, FrameIdentity>>()
    private val documents = ConcurrentHashMap<String, FrameIdentity>()
    private val navigationCounters = ConcurrentHashMap<String, AtomicLong>()
    private val providerFrameIds = ConcurrentHashMap<String, ConcurrentHashMap<String, Int>>()
    private val completeFrameInventories = ConcurrentHashMap<String, Boolean>()
    private val documentIdentityAvailable = ConcurrentHashMap<String, Boolean>()

    private fun canonicalTabId(tabId: String): String = TabIdMapper.getUuidFromString(tabId).ifBlank { tabId.trim() }

    private fun nextNavigationId(tabId: String): Long =
        navigationCounters.getOrPut(canonicalTabId(tabId)) { AtomicLong(0L) }.incrementAndGet()

    private fun originOf(url: String): String {
        return try {
            if (url.isBlank()) return ""
            val uri = Uri.parse(url)
            val scheme = uri.scheme ?: return ""
            val host = uri.host ?: return "$scheme:"
            val port = if (uri.port > 0 && uri.port != 80 && uri.port != 443) ":${uri.port}" else ""
            "$scheme://$host$port"
        } catch (_: Throwable) {
            ""
        }
    }

    private fun frameMap(tabId: String): ConcurrentHashMap<Int, FrameIdentity> =
        tabFrames.getOrPut(canonicalTabId(tabId)) { ConcurrentHashMap() }

    private fun providerMap(tabId: String): ConcurrentHashMap<String, Int> =
        providerFrameIds.getOrPut(canonicalTabId(tabId)) { ConcurrentHashMap() }

    private fun newDocumentId(): String = "swift-document-${UUID.randomUUID()}"

    private fun createDocument(frame: FrameIdentity, transportToken: String? = null) {
        frame.documentId?.let { oldId -> documents[oldId]?.apply { lifecycleState = FrameLifecycleState.DESTROYED; destroyed = true } }
        val id = newDocumentId()
        frame.documentId = id
        frame.documentTransportToken = transportToken
        frame.createdAt = System.currentTimeMillis()
        frame.committed = false
        frame.destroyed = false
        frame.lifecycleState = FrameLifecycleState.UNKNOWN
        frame.generation += 1
        documents[id] = frame
    }

    private fun markDocumentDestroyed(frame: FrameIdentity) {
        frame.documentId?.let { id -> documents[id]?.apply { lifecycleState = FrameLifecycleState.DESTROYED; destroyed = true } }
        frame.documentId = null
        frame.documentTransportToken = null
        frame.destroyed = true
        frame.committed = false
        frame.lifecycleState = FrameLifecycleState.DESTROYED
    }

    private fun invalidateDescendants(tabId: String, parentFrameId: Int) {
        val canonical = canonicalTabId(tabId)
        val frames = tabFrames[canonical] ?: return
        frames.values.filter { it.frameId != parentFrameId && it.parentFrameId == parentFrameId }.forEach { child ->
            invalidateDescendants(canonical, child.frameId)
            markDocumentDestroyed(child)
            child.providerToken?.let { providerMap(canonical).remove(it) }
            frames.remove(child.frameId, child)
        }
    }

    private fun currentFrame(tabId: String, frameId: Int): FrameIdentity? =
        tabFrames[canonicalTabId(tabId)]?.get(frameId)

    /** Browser-owned logical frame registration. documentId is ignored for authority. */
    @Synchronized
    fun registerFrame(
        tabId: String,
        frameId: Int,
        parentFrameId: Int = if (frameId == 0) -1 else 0,
        url: String = "",
        documentId: String? = null,
        providerToken: String? = null,
        privateSession: Boolean = false
    ): WebNavigationFrameInfo {
        require(tabId.isNotBlank()) { "TAB_ID_REQUIRED" }
        require(frameId >= 0) { "FRAME_ID_INVALID" }
        val canonical = canonicalTabId(tabId)
        if (frameId == 0) require(parentFrameId == -1) { "TOP_FRAME_PARENT_INVALID" }
        if (frameId != 0 && parentFrameId < 0) throw IllegalArgumentException("FRAME_PARENT_NOT_FOUND")
        val frames = frameMap(canonical)
        if (frameId != 0 && !frames.containsKey(parentFrameId)) throw IllegalArgumentException("FRAME_PARENT_NOT_FOUND")

        val existing = frames[frameId]
        val changed = existing == null
        val frame = existing ?: FrameIdentity(canonical, frameId, parentFrameId)
        if (existing != null && existing.documentId == null) createDocument(frame)
        if (changed) createDocument(frame)
        frame.parentFrameId = parentFrameId
        frame.privateSession = privateSession
        if (url.isNotBlank()) {
            frame.url = url
            frame.origin = originOf(url)
        }
        if (!providerToken.isNullOrBlank()) {
            frame.providerToken = providerToken
            providerMap(canonical)[providerToken] = frameId
        }
        frames[frameId] = frame
        // A caller-supplied documentId never overrides a browser-owned identity.
        return frameInfo(frame)
    }

    /** Registers a WebView provider transport. Browser generates the authoritative documentId. */
    @Synchronized
    fun registerProviderFrame(
        tabId: String,
        frameToken: String,
        parentFrameToken: String? = null,
        url: String = "",
        isMainFrame: Boolean,
        privateSession: Boolean = false,
        documentTransportToken: String? = null
    ): FrameIdentity {
        require(tabId.isNotBlank() && frameToken.isNotBlank()) { "FRAME_TRANSPORT_INVALID" }
        val canonical = canonicalTabId(tabId)
        val frames = frameMap(canonical)
        val ids = providerMap(canonical)
        val parentId = if (isMainFrame) -1 else {
            parentFrameToken?.takeIf { it.isNotBlank() }?.let(ids::get)
                ?: throw IllegalArgumentException("FRAME_PARENT_NOT_FOUND")
        }
        val frameId = if (isMainFrame) 0 else {
            val existingId = ids[frameToken]
            if (existingId != null) existingId else frames.keys.filter { it > 0 }.maxOrNull()?.plus(1) ?: 1
        }

        if (isMainFrame) invalidateDescendants(canonical, 0)
        val frame = frames[frameId] ?: FrameIdentity(canonical, frameId, parentId)
        val previousDocToken = frame.documentTransportToken
        if (previousDocToken != null && documentTransportToken != null && previousDocToken != documentTransportToken) {
            frame.documentId?.let { old -> documents[old]?.apply { lifecycleState = FrameLifecycleState.DESTROYED; destroyed = true } }
            frame.documentId = null
        }
        if (frame.documentId == null) createDocument(frame, documentTransportToken)
        else if (documentTransportToken != null && frame.documentTransportToken != documentTransportToken) createDocument(frame, documentTransportToken)
        frame.providerToken = frameToken
        frame.privateSession = privateSession
        frame.parentFrameId = parentId
        frame.url = url
        frame.origin = originOf(url)
        frame.destroyed = false
        frames[frameId] = frame
        ids[frameToken] = frameId
        documentIdentityAvailable[canonical] = true
        return frame
    }

    @Synchronized
    fun ensureTopFrame(tabId: String, url: String, privateSession: Boolean = false): FrameIdentity {
        val canonical = canonicalTabId(tabId)
        val existing = currentFrame(canonical, 0)
        if (existing != null && !existing.destroyed) {
            if (url.isNotBlank()) { existing.url = url; existing.origin = originOf(url) }
            existing.privateSession = privateSession
            return existing
        }
        registerFrame(canonical, 0, -1, url, null, null, privateSession)
        return currentFrame(canonical, 0)!!
    }

    private fun frameInfo(frame: FrameIdentity): WebNavigationFrameInfo = WebNavigationFrameInfo(
        frameId = frame.frameId,
        parentFrameId = frame.parentFrameId,
        url = frame.url,
        origin = frame.origin,
        errorOccurred = frame.lifecycleState == FrameLifecycleState.FAILED,
        documentId = frame.documentId ?: "",
        lifecycleState = frame.lifecycleState.name,
        navigationId = frame.navigationId
    )

    fun getFrameInfo(tabId: String, frameId: Int): WebNavigationFrameInfo? = currentFrame(tabId, frameId)?.takeIf { !it.destroyed }?.let(::frameInfo)

    fun getAllFrames(tabId: String): List<WebNavigationFrameInfo> =
        tabFrames[canonicalTabId(tabId)]?.values?.filter { !it.destroyed && it.lifecycleState != FrameLifecycleState.DESTROYED }
            ?.sortedBy { it.frameId }?.map(::frameInfo) ?: emptyList()

    fun getFrameIdentity(tabId: String, frameId: Int): FrameIdentity? = currentFrame(tabId, frameId)?.takeIf { !it.destroyed }

    fun getFrameIdentities(tabId: String): List<FrameIdentity> =
        tabFrames[canonicalTabId(tabId)]?.values?.filter { !it.destroyed && it.lifecycleState != FrameLifecycleState.DESTROYED }?.sortedBy { it.frameId } ?: emptyList()

    fun getFrameIdentityByDocumentId(tabId: String, documentId: String): FrameIdentity? =
        documents[documentId]?.takeIf { it.tabId == canonicalTabId(tabId) && !it.destroyed && it.lifecycleState != FrameLifecycleState.DESTROYED }

    fun getFrameIdentityByProviderToken(tabId: String, frameToken: String): FrameIdentity? {
        val canonical = canonicalTabId(tabId)
        val id = providerFrameIds[canonical]?.get(frameToken) ?: return null
        return getFrameIdentity(canonical, id)
    }

    fun isCurrentDocument(tabId: String, frameId: Int, documentId: String, documentTransportToken: String? = null): Boolean {
        val frame = getFrameIdentity(tabId, frameId) ?: return false
        if (frame.documentId != documentId) return false
        if (documentTransportToken != null && frame.documentTransportToken != documentTransportToken) return false
        return !frame.destroyed
    }

    fun resolveTargets(tabId: String, frameIds: List<Int>?, documentIds: List<String>?, allFrames: Boolean): FrameTargetResolution {
        if (allFrames && !frameIds.isNullOrEmpty()) return FrameTargetResolution(FRAME_TARGETING_UNSUPPORTED)
        if (allFrames && !documentIds.isNullOrEmpty()) return FrameTargetResolution(DOCUMENT_TARGETING_UNSUPPORTED)
        if (!frameIds.isNullOrEmpty() && !documentIds.isNullOrEmpty()) return FrameTargetResolution(FRAME_TARGETING_UNSUPPORTED)
        val canonical = canonicalTabId(tabId)
        val frames = tabFrames[canonical]?.values?.filter { !it.destroyed && it.lifecycleState != FrameLifecycleState.DESTROYED } ?: emptyList()
        if (!documentIds.isNullOrEmpty()) {
            if (documentIdentityAvailable[canonical] != true) return FrameTargetResolution(DOCUMENT_TARGETING_UNSUPPORTED)
            val unique = documentIds.distinct()
            val matched = unique.mapNotNull { documents[it]?.takeIf { f -> f.tabId == canonical && !f.destroyed && f.lifecycleState != FrameLifecycleState.DESTROYED } }
            if (matched.size != unique.size) return FrameTargetResolution(DOCUMENT_NOT_FOUND)
            return FrameTargetResolution(NAVIGATION_TARGET_SUCCESS, matched.sortedBy { it.frameId }, matched.singleOrNull()?.documentId)
        }
        if (allFrames) {
            if (documentIdentityAvailable[canonical] != true || completeFrameInventories[canonical] != true) return FrameTargetResolution(FRAME_TARGETING_UNSUPPORTED)
            if (frames.isEmpty()) return FrameTargetResolution(FRAME_NOT_FOUND)
            return FrameTargetResolution(NAVIGATION_TARGET_SUCCESS, frames.sortedBy { it.frameId })
        }
        val requested = frameIds?.distinct()?.takeIf { it.isNotEmpty() } ?: listOf(0)
        val matched = requested.mapNotNull { id -> frames.firstOrNull { it.frameId == id } }
        if (matched.size != requested.size) return FrameTargetResolution(FRAME_NOT_FOUND)
        return FrameTargetResolution(NAVIGATION_TARGET_SUCCESS, matched.sortedBy { it.frameId })
    }

    @Synchronized
    fun removeFrameById(tabId: String, frameId: Int): Boolean {
        val canonical = canonicalTabId(tabId)
        val frames = tabFrames[canonical] ?: return false
        val frame = frames.remove(frameId) ?: return false
        markDocumentDestroyed(frame)
        frame.providerToken?.let { providerMap(canonical).remove(it) }
        invalidateDescendants(canonical, frameId)
        return true
    }

    @Synchronized
    fun removeFrameByProviderToken(tabId: String, frameToken: String, documentTransportToken: String? = null): Boolean {
        val frame = getFrameIdentityByProviderToken(tabId, frameToken) ?: return false
        if (documentTransportToken != null && frame.documentTransportToken != documentTransportToken) return false
        return removeFrameById(tabId, frame.frameId)
    }

    @Synchronized
    fun markDocumentIdentityAvailable(tabId: String) { documentIdentityAvailable[canonicalTabId(tabId)] = true }

    @Synchronized
    fun markFrameInventoryCompleteForProvider(tabId: String) { completeFrameInventories[canonicalTabId(tabId)] = true }

    @Synchronized
    fun clearTab(tabId: String) {
        val canonical = canonicalTabId(tabId)
        tabFrames.remove(canonical)?.values?.forEach(::markDocumentDestroyed)
        providerFrameIds.remove(canonical)
        navigationCounters.remove(canonical)
        completeFrameInventories.remove(canonical)
        documentIdentityAvailable.remove(canonical)
        documents.entries.removeIf { it.value.tabId == canonical }
    }

    @Synchronized
    fun onBeforeNavigate(tabId: String, url: String, isPrivate: Boolean = false, frameId: Int = 0, parentFrameId: Int = -1): Boolean {
        val frame = if (frameId == 0) ensureTopFrame(tabId, url, isPrivate) else currentFrame(tabId, frameId) ?: return false
        if (frameId == 0) invalidateDescendants(tabId, 0)
        markDocumentDestroyed(frame)
        createDocument(frame)
        frame.parentFrameId = if (frameId == 0) -1 else parentFrameId
        frame.privateSession = isPrivate
        frame.pendingNavigation(url, nextNavigationId(tabId))
        emit("webNavigation.onBeforeNavigate", frame, url, isPrivate) { it.put("processId", 0) }
        return true
    }

    @Synchronized
    fun onCommitted(tabId: String, url: String, isPrivate: Boolean = false, frameId: Int = 0, transitionType: String = "link"): Boolean {
        val frame = currentFrame(tabId, frameId) ?: return false
        if (frame.documentId == null) createDocument(frame)
        if (frame.navigationId == 0L) frame.navigationId = nextNavigationId(tabId)
        frame.url = url; frame.origin = originOf(url); frame.lifecycleState = FrameLifecycleState.COMMITTED
        frame.committed = true; frame.destroyed = false; frame.privateSession = isPrivate
        emit("webNavigation.onCommitted", frame, url, isPrivate) { p -> p.put("processId", 0); p.put("transitionType", transitionType); p.put("transitionQualifiers", JSONArray()) }
        return true
    }

    @Synchronized
    fun onDOMContentLoaded(tabId: String, url: String, isPrivate: Boolean = false, frameId: Int = 0): Boolean {
        val frame = currentFrame(tabId, frameId) ?: return false
        if (frame.destroyed || frame.lifecycleState == FrameLifecycleState.FAILED) return false
        frame.url = url; frame.origin = originOf(url); frame.lifecycleState = FrameLifecycleState.DOM_READY; frame.privateSession = isPrivate
        emit("webNavigation.onDOMContentLoaded", frame, url, isPrivate) { it.put("processId", 0) }; return true
    }

    @Synchronized
    fun onCompleted(tabId: String, url: String, isPrivate: Boolean = false, frameId: Int = 0): Boolean {
        val frame = currentFrame(tabId, frameId) ?: return false
        if (frame.destroyed || frame.lifecycleState == FrameLifecycleState.FAILED) return false
        frame.url = url; frame.origin = originOf(url); frame.lifecycleState = FrameLifecycleState.COMPLETED; frame.privateSession = isPrivate
        emit("webNavigation.onCompleted", frame, url, isPrivate) { it.put("processId", 0) }; return true
    }

    @Synchronized
    fun onErrorOccurred(tabId: String, url: String, error: String, isPrivate: Boolean = false, frameId: Int = 0): Boolean {
        val frame = currentFrame(tabId, frameId) ?: return false
        if (frame.destroyed) return false
        frame.url = url; frame.origin = originOf(url); frame.lifecycleState = FrameLifecycleState.FAILED; frame.lastError = error; frame.privateSession = isPrivate
        emit("webNavigation.onErrorOccurred", frame, url, isPrivate) { p -> p.put("processId", 0); p.put("error", error) }; return true
    }

    fun onCreatedNavigationTarget(sourceTabId: String, newTabId: String, url: String, isPrivate: Boolean = false) {
        eventDispatcher("webNavigation.onCreatedNavigationTarget", JSONObject().apply {
            put("sourceTabId", TabIdMapper.getIntId(sourceTabId)); put("sourceProcessId", 0); put("sourceFrameId", 0)
            put("tabId", TabIdMapper.getIntId(newTabId)); put("url", url); put("timeStamp", System.currentTimeMillis())
        }, url, isPrivate)
    }

    @Synchronized fun onReferenceFragmentUpdated(tabId: String, url: String, isPrivate: Boolean = false): Boolean {
        val frame = currentFrame(tabId, 0) ?: return false; frame.url = url; frame.origin = originOf(url)
        emit("webNavigation.onReferenceFragmentUpdated", frame, url, isPrivate) { p -> p.put("processId", 0); p.put("transitionType", "link"); p.put("transitionQualifiers", JSONArray().put("client_redirect")) }; return true
    }

    @Synchronized fun onHistoryStateUpdated(tabId: String, url: String, isPrivate: Boolean = false, transitionType: String = "link"): Boolean {
        val frame = currentFrame(tabId, 0) ?: return false; frame.url = url; frame.origin = originOf(url)
        emit("webNavigation.onHistoryStateUpdated", frame, url, isPrivate) { p -> p.put("processId", 0); p.put("transitionType", transitionType); p.put("transitionQualifiers", JSONArray()) }; return true
    }

    private fun emit(eventName: String, frame: FrameIdentity, url: String, isPrivate: Boolean, extra: (JSONObject) -> Unit) {
        eventDispatcher(eventName, JSONObject().apply {
            put("tabId", TabIdMapper.getIntId(frame.tabId)); put("url", url); put("frameId", frame.frameId); put("parentFrameId", frame.parentFrameId)
            put("timeStamp", System.currentTimeMillis()); put("lifecycleState", frame.lifecycleState.name); put("origin", frame.origin)
            frame.documentId?.let { put("documentId", it) }; put("navigationId", frame.navigationId); extra(this)
        }, url, isPrivate)
    }

    private fun FrameIdentity.pendingNavigation(url: String, navigation: Long) {
        this.url = url; this.origin = originOf(url); this.navigationId = navigation; this.lifecycleState = FrameLifecycleState.STARTED; this.lastError = null; this.destroyed = false; this.committed = false
    }
}
