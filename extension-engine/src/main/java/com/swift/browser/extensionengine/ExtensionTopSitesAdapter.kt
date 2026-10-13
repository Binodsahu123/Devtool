package com.swift.browser.extensionengine

import org.json.JSONArray
import org.json.JSONObject
import com.swift.browser.historyengine.HistoryEngine

class ExtensionTopSitesAdapter(
    private val permissionManager: PermissionManager,
    private val registry: ExtensionRegistry,
    private val historyEngine: HistoryEngine? = null
) {
    private fun validate(sender: ExtensionSender): ParsedExtension =
        ExtensionSenderAccessValidator.validate(
            sender = sender,
            registry = registry,
            permissionManager = permissionManager,
            apiPermission = "topSites",
            useSecurityException = true
        )

    suspend fun get(sender: ExtensionSender, delegate: BrowserDelegate?): JSONArray {
        validate(sender)
        val delegated = runCatching { delegate?.queryTabs(JSONObject().put("topSites", true)) }.getOrNull()
        if (delegated != null && delegated.length() > 0) return delegated

        val history = historyEngine
            ?: throw UnsupportedOperationException("TOP_SITES_HISTORY_BACKEND_UNAVAILABLE")
        val recent = history.queryHistory("", 100)
        val result = JSONArray()
        recent.asSequence()
            .filter { it.url.isNotBlank() && !it.url.startsWith("chrome-extension://", true) }
            .sortedWith(compareByDescending<com.swift.browser.historyengine.HistoryItem> { it.visitCount }.thenByDescending { it.timestamp })
            .distinctBy { it.url }
            .take(20)
            .forEach { item ->
                result.put(JSONObject().put("url", item.url).put("title", item.title))
            }
        return result
    }
}
