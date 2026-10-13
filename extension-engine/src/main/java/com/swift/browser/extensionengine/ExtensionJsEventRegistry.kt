package com.swift.browser.extensionengine

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Generation-aware Event Registry for Extension JS Bridge.
 * Tracks event listener subscriptions by (extensionId, runtimeGenerationId, eventName, listenerId).
 */
class ExtensionJsEventRegistry(
    private val eventManager: EventManager,
    private val webRequestAdapter: ExtensionWebRequestAdapter? = null,
    private val registryForPersistence: ExtensionRegistry? = null
) {

    data class EventSubscription(
        val extensionId: String,
        val runtimeGenerationId: String,
        val eventName: String,
        val listenerId: String,
        val filter: JSONObject? = null,
        val extraInfoSpec: JSONArray? = null
    )

    // Key: "extId_eventName_listenerId" -> Subscription
    private val subscriptions = ConcurrentHashMap<String, EventSubscription>()

    fun addListener(
        extensionId: String,
        runtimeGenerationId: String,
        eventName: String,
        listenerId: String,
        filter: JSONObject? = null,
        extraInfoSpec: JSONArray? = null,
        isBackgroundContext: Boolean = false
    ) {
        if (eventName.isBlank() || extensionId.isBlank() || listenerId.isBlank()) return

        val key = "${extensionId}_${eventName}_${listenerId}"
        val sub = EventSubscription(extensionId, runtimeGenerationId, eventName, listenerId, filter, extraInfoSpec)
        subscriptions[key] = sub

        // Register in EventManager
        val ext = registryForPersistence?.getExtension(extensionId)
        val isMv3WorkerContext = isBackgroundContext && ext?.let {
            it.isServiceWorker || it.manifestVersion >= 3
        } == true
        val isMv2EventPage = isBackgroundContext && ext?.let {
            it.manifestVersion == 2 && !it.backgroundSpec.persistent
        } == true

        if (isMv2EventPage || isMv3WorkerContext) {
            // The browser-side EventManager owns persistence for both lazy models.
            // NativeExtensionCoreBridge is MV3-only; keeping MV2 out of that path
            // avoids duplicate listener state and duplicate event wakeups.
            eventManager.addLazyListener(eventName, extensionId)
        } else {
            eventManager.addListener(eventName, extensionId)
        }

        // WebRequest event special handling
        if (eventName.startsWith("webRequest.") && webRequestAdapter != null) {
            val extraSpecs = mutableListOf<String>()
            if (extraInfoSpec != null) {
                for (i in 0 until extraInfoSpec.length()) {
                    extraSpecs.add(extraInfoSpec.optString(i, ""))
                }
            }
            val webFilter = WebRequestFilter.fromJsonObject(filter)
            webRequestAdapter.subscriptionRegistry.addSubscription(extensionId, eventName, webFilter, extraSpecs, listenerId)
        }
    }

    fun removeListener(
        extensionId: String,
        eventName: String,
        listenerId: String
    ) {
        if (eventName.isBlank() || extensionId.isBlank()) return

        val removedKeys = mutableListOf<String>()
        if (listenerId.isNotBlank()) {
            val key = "${extensionId}_${eventName}_${listenerId}"
            if (subscriptions.remove(key) != null) removedKeys += key
        } else {
            // No listener ID means remove every listener for this event on this extension.
            subscriptions.keys.filter { it.startsWith("${extensionId}_${eventName}_") }.forEach { key ->
                if (subscriptions.remove(key) != null) removedKeys += key
            }
        }

        val remaining = subscriptions.values.any { it.extensionId == extensionId && it.eventName == eventName }
        if (!remaining) {
            val lazy = registryForPersistence?.getExtension(extensionId)?.let {
                it.isServiceWorker || (it.manifestVersion == 2 && !it.backgroundSpec.persistent)
            } == true
            if (lazy) eventManager.removeLazyListener(eventName, extensionId) else eventManager.removeListener(eventName, extensionId)
        }
        if (eventName.startsWith("webRequest.") && webRequestAdapter != null) {
            if (listenerId.isNotBlank()) {
                webRequestAdapter.subscriptionRegistry.removeSubscription(extensionId, eventName, listenerId)
            } else {
                removedKeys.forEach { key ->
                    val sub = key.substringAfter("${extensionId}_${eventName}_")
                    if (sub.isNotBlank()) webRequestAdapter.subscriptionRegistry.removeSubscription(extensionId, eventName, sub)
                }
            }
        }
    }

    fun removeAllForGeneration(extensionId: String, runtimeGenerationId: String) {
        val keysToRemove = subscriptions.entries
            .filter { it.value.extensionId == extensionId && it.value.runtimeGenerationId == runtimeGenerationId }
            .map { it.key }

        for (k in keysToRemove) {
            val sub = subscriptions.remove(k) ?: continue
            val remaining = subscriptions.values.any { it.extensionId == sub.extensionId && it.eventName == sub.eventName }
            if (!remaining) {
                val lazy = registryForPersistence?.getExtension(sub.extensionId)?.let {
                    it.isServiceWorker || (it.manifestVersion == 2 && !it.backgroundSpec.persistent)
                } == true
                if (!lazy) eventManager.removeListener(sub.eventName, sub.extensionId)
            }
            if (sub.eventName.startsWith("webRequest.") && webRequestAdapter != null) {
                webRequestAdapter.subscriptionRegistry.removeSubscription(sub.extensionId, sub.eventName, sub.listenerId)
            }
        }
    }

    fun cleanupExtensionState(extensionId: String) {
        val keysToRemove = subscriptions.keys.filter { it.startsWith("${extensionId}_") }
        for (k in keysToRemove) {
            val sub = subscriptions.remove(k) ?: continue
            eventManager.removeListener(sub.eventName, extensionId)
            if (sub.eventName.startsWith("webRequest.") && webRequestAdapter != null) {
                webRequestAdapter.subscriptionRegistry.removeSubscription(extensionId, sub.eventName, sub.listenerId)
            }
        }
        eventManager.removeAllLazyListeners(extensionId)
    }
}
