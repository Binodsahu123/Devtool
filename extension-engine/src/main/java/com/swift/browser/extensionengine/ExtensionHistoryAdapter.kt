package com.swift.browser.extensionengine

import com.swift.browser.historyengine.HistoryBrowsingContext
import com.swift.browser.historyengine.HistoryItem
import com.swift.browser.historyengine.HistoryEngine
import org.json.JSONArray
import org.json.JSONObject

/**
 * ExtensionHistoryAdapter bridges chrome.history.* calls to Orion's history-engine.
 * Enforces strict Private Mode boundaries (no history logging or leaks in private mode).
 */
class ExtensionHistoryAdapter(
    private val permissionManager: PermissionManager,
    private val registry: ExtensionRegistry,
    private val eventManager: EventManager,
    private val historyApi: HistoryEngine
) {

    private fun verifyHistoryPermission(sender: ExtensionSender) {
        val ext = registry.getExtension(sender.extensionId)
            ?: throw SecurityException("SecurityError: Extension ${sender.extensionId} not found.")
        if (!permissionManager.hasApiPermission(sender.extensionId, ext.permissions, "history")) {
            throw SecurityException("SecurityError: Extension does not have 'history' permission in manifest.")
        }
    }

    private fun formatHistoryItem(item: HistoryItem): JSONObject {
        return JSONObject().apply {
            put("id", item.id.toString())
            put("url", item.url)
            put("title", item.title)
            put("lastVisitTime", item.timestamp)
            put("visitCount", item.visitCount)
            put("typedCount", 1)
        }
    }

    suspend fun search(sender: ExtensionSender, queryInfo: JSONObject): JSONArray {
        verifyHistoryPermission(sender)

        // Strict Private Mode boundary check: private sessions do not expose history
        if (sender.isPrivate) {
            return JSONArray()
        }

        val text = queryInfo.optString("text", "")
        val maxResults = queryInfo.optInt("maxResults", 100)
        val startTime = if (queryInfo.has("startTime")) queryInfo.getLong("startTime") else 0L
        val endTime = if (queryInfo.has("endTime")) queryInfo.getLong("endTime") else Long.MAX_VALUE

        val results = when (historyApi) {
            is com.swift.browser.historyengine.HistoryRepository -> historyApi.queryHistory(
                query = text,
                startTime = startTime,
                endTime = endTime,
                limit = maxResults.coerceIn(0, 10_000)
            )
            else -> historyApi.queryHistory(text, maxResults)
                .filter { it.timestamp in startTime..endTime }
                .take(maxResults.coerceIn(0, 10_000))
        }

        val array = JSONArray()
        for (item in results) {
            array.put(formatHistoryItem(item))
        }
        return array
    }

    suspend fun getVisits(sender: ExtensionSender, details: JSONObject): JSONArray {
        verifyHistoryPermission(sender)

        if (sender.isPrivate) {
            return JSONArray()
        }

        val url = details.optString("url", "")
        if (url.isBlank()) return JSONArray()

        val results = historyApi.queryHistory(url, 100) ?: emptyList()
        val item = results.find { it.url == url } ?: return JSONArray()

        val visits = JSONArray()
        visits.put(JSONObject().apply {
            put("id", item.id.toString())
            put("visitId", item.id.toString())
            put("visitTime", item.timestamp)
            put("referringVisitId", "UNAVAILABLE")
            put("transition", "UNAVAILABLE")
            put("isLocal", true)
        })

        return visits
    }

    suspend fun addUrl(sender: ExtensionSender, details: JSONObject): JSONObject {
        verifyHistoryPermission(sender)

        if (sender.isPrivate) {
            return JSONObject().apply { put("status", "success") }
        }

        val url = details.optString("url", "")
        val title = details.optString("title", "")

        if (url.isNotBlank()) {
            val persisted = if (historyApi is com.swift.browser.historyengine.HistoryRepository) {
                historyApi.addHistoryItemAndGet(url, title, HistoryBrowsingContext.NORMAL)
            } else {
                historyApi.addHistoryItem(url, title, HistoryBrowsingContext.NORMAL)
                null
            }

            eventManager.triggerEvent("history.onVisited", JSONObject().apply {
                put("id", (persisted?.id ?: System.currentTimeMillis()).toString())
                put("url", url)
                put("title", persisted?.title ?: title)
                put("lastVisitTime", persisted?.timestamp ?: System.currentTimeMillis())
                put("visitCount", persisted?.visitCount ?: 1)
            })
        }

        return JSONObject().apply { put("status", "success") }
    }

    suspend fun deleteUrl(sender: ExtensionSender, details: JSONObject): JSONObject {
        verifyHistoryPermission(sender)

        if (sender.isPrivate) {
            return JSONObject().apply { put("status", "success") }
        }

        val url = details.optString("url", "")
        if (url.isNotBlank()) {
            val items = historyApi.queryHistory(url) ?: emptyList()
            val match = items.find { it.url == url }
            if (match != null) {
                historyApi.deleteHistoryItem(match.id)
            }

            eventManager.triggerEvent("history.onVisitRemoved", JSONObject().apply {
                put("allHistory", false)
                put("urls", JSONArray().put(url))
            })
        }

        return JSONObject().apply { put("status", "success") }
    }

    suspend fun deleteRange(sender: ExtensionSender, range: JSONObject): JSONObject {
        verifyHistoryPermission(sender)

        if (sender.isPrivate) {
            return JSONObject().apply { put("status", "success") }
        }

        val startTime = range.optLong("startTime", 0L)
        val endTime = range.optLong("endTime", Long.MAX_VALUE)
        historyApi.deleteHistoryRange(startTime, endTime)

        eventManager.triggerEvent("history.onVisitRemoved", JSONObject().apply {
            put("allHistory", false)
        })

        return JSONObject().apply { put("status", "success") }
    }

    suspend fun deleteAll(sender: ExtensionSender): JSONObject {
        verifyHistoryPermission(sender)

        if (sender.isPrivate) {
            return JSONObject().apply { put("status", "success") }
        }

        historyApi.clearAllHistory()

        eventManager.triggerEvent("history.onVisitRemoved", JSONObject().apply {
            put("allHistory", true)
        })

        return JSONObject().apply { put("status", "success") }
    }
}
