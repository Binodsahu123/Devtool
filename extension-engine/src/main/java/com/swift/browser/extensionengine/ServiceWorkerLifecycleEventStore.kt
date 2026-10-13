package com.swift.browser.extensionengine

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Durable at-least-once store for MV3 lifecycle events that must survive a browser-process death.
 * This store contains only lifecycle events; ordinary browser events remain owned by EventManager.
 */
class ServiceWorkerLifecycleEventStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        "orion_mv3_lifecycle_events_v1",
        Context.MODE_PRIVATE
    )
    private val cache = ConcurrentHashMap<String, MutableList<PersistedEvent>>()

    data class PersistedEvent(
        val eventId: String,
        val eventName: String,
        val payloadJson: String,
        val timestamp: Long,
        val extensionId: String,
        val generationId: Int = 0,
        val deliveryAttempts: Int = 0,
        val keepaliveRequirement: String? = null
    ) {
        fun toQueuedEvent(): QueuedServiceWorkerEvent {
            val payload: Any? = runCatching {
                if (payloadJson.isBlank()) null else JSONObject(payloadJson)
            }.getOrElse { payloadJson }
            return QueuedServiceWorkerEvent(
                eventId = eventId,
                eventName = eventName,
                payload = payload,
                timestamp = timestamp,
                generationId = generationId,
                deliveryAttempts = deliveryAttempts,
                extensionId = extensionId,
                sourceContext = "lifecycle",
                keepaliveRequirement = keepaliveRequirement
            )
        }
    }

    init {
        prefs.all.forEach { (extensionId, raw) ->
            if (raw is String) {
                cache[extensionId] = decode(extensionId, raw).toMutableList()
            }
        }
    }

    @Synchronized
    fun put(event: QueuedServiceWorkerEvent): Boolean {
        val extensionId = event.extensionId.lowercase().trim()
        if (extensionId.isBlank() || event.eventId.isBlank() || event.eventName.isBlank()) return false
        val events = cache.getOrPut(extensionId) { mutableListOf() }
        if (events.any { it.eventId == event.eventId }) return true

        val payloadJson = when (val payload = event.payload) {
            null -> "{}"
            is JSONObject -> payload.toString()
            is JSONArray -> payload.toString()
            is String -> runCatching { JSONObject(payload).toString() }.getOrElse { JSONObject().put("value", payload).toString() }
            else -> JSONObject.wrap(payload)?.toString() ?: "{}"
        }
        events += PersistedEvent(
            eventId = event.eventId,
            eventName = event.eventName,
            payloadJson = payloadJson,
            timestamp = event.timestamp,
            extensionId = extensionId,
            generationId = event.generationId,
            deliveryAttempts = event.deliveryAttempts,
            keepaliveRequirement = event.keepaliveRequirement
        )
        persist(extensionId, events)
        return true
    }

    @Synchronized
    fun update(event: QueuedServiceWorkerEvent): Boolean {
        val key = event.extensionId.lowercase().trim()
        if (key.isBlank() || event.eventId.isBlank()) return false
        val events = cache[key] ?: return false
        val index = events.indexOfFirst { it.eventId == event.eventId }
        if (index < 0) return false
        val payloadJson = when (val payload = event.payload) {
            null -> "{}"
            is JSONObject -> payload.toString()
            is JSONArray -> payload.toString()
            is String -> runCatching { JSONObject(payload).toString() }.getOrElse { JSONObject().put("value", payload).toString() }
            else -> JSONObject.wrap(payload)?.toString() ?: "{}"
        }
        events[index] = PersistedEvent(
            eventId = event.eventId,
            eventName = event.eventName,
            payloadJson = payloadJson,
            timestamp = event.timestamp,
            extensionId = key,
            generationId = event.generationId,
            deliveryAttempts = event.deliveryAttempts,
            keepaliveRequirement = event.keepaliveRequirement
        )
        persist(key, events)
        return true
    }

    @Synchronized
    fun remove(extensionId: String, eventId: String) {
        val key = extensionId.lowercase().trim()
        val events = cache[key] ?: return
        if (events.removeIf { it.eventId == eventId }) {
            if (events.isEmpty()) {
                cache.remove(key)
                prefs.edit().remove(key).commit()
            } else {
                persist(key, events)
            }
        }
    }

    @Synchronized
    fun removeExtension(extensionId: String) {
        val key = extensionId.lowercase().trim()
        cache.remove(key)
        prefs.edit().remove(key).commit()
    }

    fun pending(extensionId: String): List<QueuedServiceWorkerEvent> =
        cache[extensionId.lowercase().trim()]
            ?.map(PersistedEvent::toQueuedEvent)
            ?: emptyList()

    fun hasPending(extensionId: String): Boolean =
        cache[extensionId.lowercase().trim()]?.isNotEmpty() == true

    private fun persist(key: String, events: List<PersistedEvent>) {
        val array = JSONArray()
        events.forEach { event ->
            array.put(JSONObject().apply {
                put("eventId", event.eventId)
                put("eventName", event.eventName)
                put("payload", event.payloadJson)
                put("timestamp", event.timestamp)
                put("generationId", event.generationId)
                put("deliveryAttempts", event.deliveryAttempts)
                event.keepaliveRequirement?.let { put("keepaliveRequirement", it) }
            })
        }
        // Lifecycle events are correctness-critical; commit synchronously so a process death
        // between enqueue and the next Android process lifetime cannot discard them.
        prefs.edit().putString(key, array.toString()).commit()
    }

    private fun decode(extensionId: String, raw: String): List<PersistedEvent> {
        val result = mutableListOf<PersistedEvent>()
        runCatching {
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val eventId = item.optString("eventId").trim()
                val eventName = item.optString("eventName").trim()
                if (eventId.isBlank() || eventName.isBlank()) continue
                result += PersistedEvent(
                    eventId = eventId,
                    eventName = eventName,
                    payloadJson = item.optString("payload", "{}"),
                    timestamp = item.optLong("timestamp", System.currentTimeMillis()),
                    extensionId = extensionId,
                    generationId = item.optInt("generationId", 0),
                    deliveryAttempts = item.optInt("deliveryAttempts", 0),
                    keepaliveRequirement = item.optString("keepaliveRequirement").takeIf { it.isNotBlank() }
                )
            }
        }
        return result
    }
}
