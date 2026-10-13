package com.swift.browser.extensionengine

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Durable local journal for chrome.sessions-compatible recently closed tabs.
 *
 * This is intentionally a browser-local journal. It does not pretend to be Chrome account sync.
 * Private/incognito tabs are never persisted.
 */
class ExtensionSessionJournal(private val context: Context) {
    data class Entry(
        val id: String,
        val url: String,
        val title: String,
        val tabId: String,
        val groupId: String?,
        val wasActive: Boolean,
        val closedAt: Long
    )

    companion object {
        private const val PREFS = "extension_sessions_v2"
        private const val KEY = "recently_closed"
        private const val MAX_ENTRIES = 25
    }

    private val lock = Any()

    fun record(tab: com.swift.browser.tabengine.model.TabModel, wasActive: Boolean) {
        if (tab.isIncognito || tab.isPrivate) return
        val url = tab.url.trim()
        if (url.isBlank() || url.startsWith("swift://", ignoreCase = true) ||
            url.startsWith("chrome://", ignoreCase = true)) return

        synchronized(lock) {
            val entries = loadLocked().filterNot { it.tabId == tab.id }.toMutableList()
            entries.add(
                Entry(
                    id = "session-" + UUID.randomUUID().toString(),
                    url = url,
                    title = tab.title,
                    tabId = tab.id,
                    groupId = tab.groupId,
                    wasActive = wasActive,
                    closedAt = System.currentTimeMillis()
                )
            )
            saveLocked(entries.sortedByDescending { it.closedAt }.take(MAX_ENTRIES))
        }
    }

    fun list(limit: Int? = null): List<Entry> = synchronized(lock) {
        val entries = loadLocked()
        if (limit == null || limit <= 0) entries else entries.take(limit)
    }

    fun consume(id: String): Entry? = synchronized(lock) {
        val entries = loadLocked()
        val found = entries.firstOrNull { it.id == id } ?: return null
        saveLocked(entries.filterNot { it.id == id })
        found
    }

    fun clear() = synchronized(lock) {
        saveLocked(emptyList())
    }

    private fun loadLocked(): List<Entry> {
        return try {
            val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY, "[]") ?: "[]"
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val url = obj.optString("url", "").trim()
                    if (url.isBlank()) continue
                    add(
                        Entry(
                            id = obj.optString("id", "session-" + UUID.randomUUID()),
                            url = url,
                            title = obj.optString("title", url),
                            tabId = obj.optString("tabId", ""),
                            groupId = obj.optString("groupId").takeIf { it.isNotBlank() },
                            wasActive = obj.optBoolean("wasActive", false),
                            closedAt = obj.optLong("closedAt", 0L)
                        )
                    )
                }
            }.sortedByDescending { it.closedAt }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun saveLocked(entries: List<Entry>) {
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(
                JSONObject().apply {
                    put("id", entry.id)
                    put("url", entry.url)
                    put("title", entry.title)
                    put("tabId", entry.tabId)
                    if (entry.groupId != null) put("groupId", entry.groupId)
                    put("wasActive", entry.wasActive)
                    put("closedAt", entry.closedAt)
                }
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, array.toString())
            .apply()
    }
}
