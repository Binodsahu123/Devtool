package com.swift.browser.extensionengine

import android.content.Context
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Browser-process style extension event router.
 *
 * One router owns event fan-out. MV3 lazy contexts are sent to the service-worker dispatcher;
 * MV2 event pages are sent to BackgroundScriptManager, which wakes the page when required.
 */
class EventManager(
    private val messageBus: MessageBus,
    context: Context? = null
) {
    private val eventListeners = ConcurrentHashMap<String, CopyOnWriteArraySet<String>>()
    private val lazyStore = context?.let { ExtensionEventPersistenceStore(it) }

    /** MV3 only. */
    var serviceWorkerEventDispatcher: ServiceWorkerEventDispatcher? = null

    /** MV2 only. */
    var mv2BackgroundManager: BackgroundScriptManager? = null

    fun addListener(eventName: String, extensionId: String) {
        if (eventName.isBlank() || extensionId.isBlank()) return
        eventListeners.computeIfAbsent(eventName) { CopyOnWriteArraySet() }.add(extensionId.lowercase().trim())
    }

    fun addLazyListener(eventName: String, extensionId: String) {
        addListener(eventName, extensionId)
        lazyStore?.add(extensionId, eventName)
    }

    fun removeListener(eventName: String, extensionId: String) {
        eventListeners[eventName]?.let { set ->
            set.remove(extensionId.lowercase().trim())
            if (set.isEmpty()) eventListeners.remove(eventName, set)
        }
    }

    fun removeLazyListener(eventName: String, extensionId: String) {
        removeListener(eventName, extensionId)
        lazyStore?.remove(extensionId, eventName)
    }

    fun removeAllLazyListeners(extensionId: String) {
        val clean = extensionId.lowercase().trim()
        lazyStore?.removeExtension(clean)
        eventListeners.values.forEach { it.remove(clean) }
        eventListeners.entries.removeIf { it.value.isEmpty() }
    }

    fun hasListener(eventName: String, extensionId: String): Boolean =
        eventListeners[eventName]?.contains(extensionId.lowercase().trim()) == true ||
            lazyStore?.eventsForExtension(extensionId)?.contains(eventName) == true

    /**
     * Routes one event to one extension. There is no MV2 -> MV3 fallback and no double-dispatch.
     */
    fun triggerEventForExtension(
        extensionId: String,
        eventName: String,
        params: JSONObject,
        isPrivate: Boolean = false,
        privateSessionId: String? = null,
        wakeWithoutListener: Boolean = false
    ) {
        val cleanId = extensionId.lowercase().trim()
        val mv2 = mv2BackgroundManager
        if (mv2?.handlesExtension(cleanId) == true) {
            // MV2 owns this extension exclusively. Most browser events should not wake an
            // event page unless a lazy listener was registered. Install/start lifecycle events
            // are the exception: the page must bootstrap once so it can register its listeners.
            if (!wakeWithoutListener && !hasListener(eventName, cleanId)) return
            mv2.dispatchEvent(
                extensionId = cleanId,
                eventName = eventName,
                payload = params,
                bootstrapScript = "",
                isPrivate = isPrivate,
                privateSessionId = privateSessionId
            )
            return
        }

        val swDispatcher = serviceWorkerEventDispatcher
        if (swDispatcher != null) {
            // MV3 lazy contexts are listener-driven. Lifecycle events that must bootstrap a
            // fresh worker explicitly bypass this gate at their lifecycle owner; ordinary browser
            // events must not wake every MV3 worker that merely exists in the registry.
            if (!wakeWithoutListener && !hasListener(eventName, cleanId)) return
            swDispatcher.dispatchRuntimeEvent(cleanId, eventName, params)
            return
        }

        val message = JSONObject().apply {
            put("type", "EVENT_DISPATCH")
            put("eventName", eventName)
            put("data", params)
            put("__targetExtensionId__", cleanId)
        }
        messageBus.broadcastMessage(cleanId, null, message)
    }

    fun triggerEvent(eventName: String, params: JSONObject) {
        val targets = linkedSetOf<String>()
        eventListeners[eventName]?.let(targets::addAll)
        lazyStore?.extensionsForEvent(eventName)?.let(targets::addAll)
        targets.forEach { triggerEventForExtension(it, eventName, params) }
    }
}
