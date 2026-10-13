package com.swift.browser.extensionengine

import android.content.Context
import com.swift.browser.tabengine.api.TabEngineApi
import org.json.JSONArray
import org.json.JSONObject

class ExtensionSessionsAdapter(
    private val context: Context,
    private val tabEngine: TabEngineApi,
    private val tabsAdapter: ExtensionTabsAdapter,
    private val registry: ExtensionRegistry,
    private val permissionManager: PermissionManager,
    private val journal: ExtensionSessionJournal = ExtensionSessionJournal(context)
) {

    private fun validate(sender: ExtensionSender) {
        val valResult = ExtensionMessagingValidator.validateSender(sender, registry, permissionManager)
        if (valResult is ExtensionMessagingValidator.ValidationResult.Denied) {
            throw IllegalStateException(valResult.reason)
        }
        val ext = registry.getExtension(sender.extensionId)
            ?: throw SecurityException("SecurityError: Extension not found.")
        if (!permissionManager.hasApiPermission(sender.extensionId, ext.permissions, "sessions")) {
            throw SecurityException("SecurityError: The 'sessions' permission is required.")
        }
    }

    /**
     * Records the tab before the TabEngine destroys it. The browser calls this from its canonical
     * close path, so the extension sessions journal never races a later getTab(null).
     */
    fun recordTabBeforeRemoval(tabId: String) {
        val tab = runCatching { tabEngine.getTab(tabId) }.getOrNull() ?: return
        if (tab.isIncognito || tab.isPrivate) return
        val active = tabEngine.getActiveTab()?.id == tab.id
        journal.record(tab, active)
    }

    fun restore(sender: ExtensionSender, sessionId: String?): JSONObject {
        validate(sender)
        if (sessionId.isNullOrBlank()) {
            // Preserve the browser's existing restoreSession mechanism when callers do not
            // identify a recently-closed extension session.
            val restoredGroups = tabEngine.restoreSession()
            val restoredTab = restoredGroups.firstOrNull()?.tabs?.firstOrNull()
            return JSONObject().apply {
                put("tab", restoredTab?.let {
                    val isActive = tabEngine.getActiveTab()?.id == it.id
                    tabsAdapter.formatTabObject(it, sender.extensionId, isActive)
                } ?: JSONObject.NULL)
                put("lastModified", System.currentTimeMillis() / 1000)
            }
        }

        val entry = journal.consume(sessionId)
            ?: throw IllegalArgumentException("SESSIONS_SESSION_NOT_FOUND")

        val restoredTab = tabEngine.createTab(
            url = entry.url,
            title = entry.title.ifBlank { entry.url },
            isIncognito = false,
            groupId = null
        )
        if (entry.wasActive) {
            runCatching { tabEngine.switchTab(restoredTab.id) }
        }

        return JSONObject().apply {
            put(
                "tab",
                tabsAdapter.formatTabObject(
                    restoredTab,
                    sender.extensionId,
                    tabEngine.getActiveTab()?.id == restoredTab.id
                )
            )
            put("lastModified", entry.closedAt / 1000L)
        }
    }

    fun getDevices(sender: ExtensionSender): JSONArray {
        validate(sender)

        // This browser has no connected Chrome Sync account provider. Returning the local device
        // with its locally recoverable sessions is useful and honest; remote device sessions are
        // not fabricated.
        val sessions = JSONArray()
        journal.list().forEach { entry ->
            sessions.put(
                JSONObject().apply {
                    put("sessionId", entry.id)
                    put("lastModified", entry.closedAt)
                    put(
                        "tab",
                        JSONObject().apply {
                            put("id", entry.tabId.ifBlank { JSONObject.NULL })
                            put("url", entry.url)
                            put("title", entry.title)
                        }
                    )
                }
            )
        }
        return JSONArray().apply {
            put(
                JSONObject().apply {
                    put("deviceName", "Swift Browser Device")
                    put("sessions", sessions)
                }
            )
        }
    }

    fun getRecentlyClosed(sender: ExtensionSender, filter: JSONObject? = null): JSONArray {
        validate(sender)
        val requestedMax = filter?.optInt("maxResults", 10)?.coerceIn(1, 25) ?: 10
        val result = JSONArray()
        journal.list(requestedMax).forEach { entry ->
            result.put(
                JSONObject().apply {
                    put("sessionId", entry.id)
                    put("lastModified", entry.closedAt)
                    put(
                        "tab",
                        JSONObject().apply {
                            put("id", entry.tabId.ifBlank { JSONObject.NULL })
                            put("windowId", 1)
                            put("index", 0)
                            put("url", entry.url)
                            put("title", entry.title)
                            put("active", entry.wasActive)
                            put("incognito", false)
                        }
                    )
                }
            )
        }
        return result
    }
}
