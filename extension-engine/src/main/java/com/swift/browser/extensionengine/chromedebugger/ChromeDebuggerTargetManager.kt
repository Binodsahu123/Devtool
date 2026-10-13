package com.swift.browser.extensionengine.chromedebugger

import android.webkit.WebView
import com.swift.browser.extensionengine.ExtensionRegistry
import com.swift.browser.extensionengine.TabIdMapper
import com.swift.browser.tabengine.api.TabEngineApi
import com.swift.browser.tabengine.model.TabModel
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages target resolution and discovery for chrome.debugger API.
 * Reuses canonical Tab Engine and ExtensionRegistry without duplicating them.
 */
class ChromeDebuggerTargetManager(
    private val tabEngine: TabEngineApi,
    private val registry: ExtensionRegistry
) {
    // Maps synthetic targetId -> tabUuid or extId
    private val targetIdToTabUuid = ConcurrentHashMap<String, String>()
    private val tabUuidToTargetId = ConcurrentHashMap<String, String>()

    data class ResolvedTarget(
        val targetId: String,
        val type: String, // "page", "background_page", "worker"
        val title: String,
        val url: String,
        val isPrivate: Boolean,
        val tabId: Int?,
        val extensionId: String?,
        val webView: WebView?
    )

    fun getTargetIdForTab(tabUuid: String): String {
        return tabUuidToTargetId.computeIfAbsent(tabUuid) {
            val id = "target_tab_$tabUuid"
            targetIdToTabUuid[id] = tabUuid
            id
        }
    }

    /**
     * Resolves a ChromeDebuggee into a concrete ResolvedTarget.
     */
    fun resolveTarget(debuggee: ChromeDebuggee): ResolvedTarget? {
        // 1. By tabId
        if (debuggee.tabId != null) {
            val tabUuid = TabIdMapper.getUuid(debuggee.tabId)
                ?: debuggee.tabId.toString()
            val tab = tabEngine.getTab(tabUuid) ?: return null
            val webView = tabEngine.getWebView(tab.id)
            val targetId = getTargetIdForTab(tab.id)
            return ResolvedTarget(
                targetId = targetId,
                type = "page",
                title = tab.title,
                url = tab.url,
                isPrivate = tab.isPrivate || tab.isIncognito,
                tabId = debuggee.tabId,
                extensionId = null,
                webView = webView
            )
        }

        // 2. By targetId
        if (!debuggee.targetId.isNullOrBlank()) {
            val targetId = debuggee.targetId
            val tabUuid = targetIdToTabUuid[targetId]
                ?: targetId.removePrefix("target_tab_")
            val tab = tabEngine.getTab(tabUuid)
            if (tab != null) {
                val webView = tabEngine.getWebView(tab.id)
                val intTabId = TabIdMapper.getIntId(tab.id)
                return ResolvedTarget(
                    targetId = targetId,
                    type = "page",
                    title = tab.title,
                    url = tab.url,
                    isPrivate = tab.isPrivate || tab.isIncognito,
                    tabId = intTabId,
                    extensionId = null,
                    webView = webView
                )
            }

            // Check if targetId is an extension
            val extId = targetId.removePrefix("target_ext_")
            val ext = registry.getExtension(extId)
            if (ext != null) {
                return ResolvedTarget(
                    targetId = targetId,
                    type = if (ext.isServiceWorker) "service_worker" else "background_page",
                    title = ext.name,
                    url = "chrome-extension://${ext.id}/",
                    isPrivate = false,
                    tabId = null,
                    extensionId = ext.id,
                    webView = null
                )
            }
        }

        // 3. By extensionId
        if (!debuggee.extensionId.isNullOrBlank()) {
            val ext = registry.getExtension(debuggee.extensionId) ?: return null
            val targetId = "target_ext_${ext.id}"
            return ResolvedTarget(
                targetId = targetId,
                type = if (ext.isServiceWorker) "service_worker" else "background_page",
                title = ext.name,
                url = "chrome-extension://${ext.id}/",
                isPrivate = false,
                tabId = null,
                extensionId = ext.id,
                webView = null
            )
        }

        return null
    }

    /**
     * Enumerates all inspectable targets across tabs and extensions.
     */
    fun getAllTargets(attachedTargetIds: Set<String>): List<ChromeDebuggerTargetInfo> {
        val results = mutableListOf<ChromeDebuggerTargetInfo>()

        // Enumerate open tabs from TabEngine
        val allTabs = mutableListOf<TabModel>()
        tabEngine.groups.value.forEach { group ->
            allTabs.addAll(group.tabs)
        }
        // Also check if any standalone active tabs exist
        tabEngine.getActiveTab()?.let { active ->
            if (allTabs.none { it.id == active.id }) {
                allTabs.add(active)
            }
        }

        allTabs.distinctBy { it.id }.forEach { tab ->
            val targetId = getTargetIdForTab(tab.id)
            val intId = TabIdMapper.getIntId(tab.id)
            results.add(
                ChromeDebuggerTargetInfo(
                    id = targetId,
                    type = "page",
                    title = tab.title.ifBlank { "Untitled" },
                    url = tab.url.ifBlank { "about:blank" },
                    attached = attachedTargetIds.contains(targetId),
                    tabId = intId,
                    extensionId = null,
                    faviconUrl = null
                )
            )
        }

        // Enumerate enabled extensions
        registry.getAllActiveExtensions().filter { registry.isExtensionEnabled(it.id) }.forEach { ext ->
            val targetId = "target_ext_${ext.id}"
            results.add(
                ChromeDebuggerTargetInfo(
                    id = targetId,
                    type = if (ext.isServiceWorker) "service_worker" else "background_page",
                    title = ext.name,
                    url = "chrome-extension://${ext.id}/",
                    attached = attachedTargetIds.contains(targetId),
                    tabId = null,
                    extensionId = ext.id,
                    faviconUrl = ext.iconPath.takeIf { !it.isNullOrBlank() }
                )
            )
        }

        return results
    }

    fun onTabClosed(tabUuid: String) {
        val targetId = tabUuidToTargetId.remove(tabUuid)
        if (targetId != null) {
            targetIdToTabUuid.remove(targetId)
        }
    }
}
