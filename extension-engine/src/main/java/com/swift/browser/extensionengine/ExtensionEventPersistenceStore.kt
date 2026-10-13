package com.swift.browser.extensionengine

import android.content.Context
import org.json.JSONArray
import java.util.concurrent.ConcurrentHashMap

/** Persists browser-level lazy listener registrations for MV2 event pages and MV3 workers. */
class ExtensionEventPersistenceStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("orion_extension_lazy_events_v2", Context.MODE_PRIVATE)
    private val cache = ConcurrentHashMap<String, MutableSet<String>>()

    init {
        prefs.all.forEach { (key, value) ->
            if (value is String) {
                runCatching {
                    val arr = JSONArray(value)
                    val set = linkedSetOf<String>()
                    for (i in 0 until arr.length()) arr.optString(i).takeIf { it.isNotBlank() }?.let(set::add)
                    if (set.isNotEmpty()) cache[key] = set
                }
            }
        }
    }

    @Synchronized fun add(extensionId: String, eventName: String) {
        val key = extensionId.lowercase().trim()
        val set = cache.getOrPut(key) { linkedSetOf() }
        if (!set.add(eventName)) return
        persist(key, set)
    }

    @Synchronized fun remove(extensionId: String, eventName: String) {
        val key = extensionId.lowercase().trim()
        val set = cache[key] ?: return
        if (set.remove(eventName)) {
            if (set.isEmpty()) { cache.remove(key); prefs.edit().remove(key).apply() }
            else persist(key, set)
        }
    }

    @Synchronized fun removeExtension(extensionId: String) {
        val key = extensionId.lowercase().trim()
        cache.remove(key)
        prefs.edit().remove(key).apply()
    }

    fun extensionsForEvent(eventName: String): Set<String> =
        cache.entries.asSequence().filter { eventName in it.value }.map { it.key }.toSet()

    fun eventsForExtension(extensionId: String): Set<String> = cache[extensionId.lowercase().trim()]?.toSet() ?: emptySet()

    private fun persist(key: String, set: Set<String>) {
        prefs.edit().putString(key, JSONArray(set.toList()).toString()).apply()
    }
}
