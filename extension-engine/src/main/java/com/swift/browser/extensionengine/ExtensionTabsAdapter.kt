package com.swift.browser.extensionengine

import com.swift.browser.tabengine.api.TabEngineApi
import com.swift.browser.tabengine.model.TabGroupModel
import com.swift.browser.tabengine.model.TabModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

class ExtensionTabsAdapter(
    val tabEngine: TabEngineApi,
    private val registry: ExtensionRegistry,
    private val permissionManager: PermissionManager,
    private val messageBus: MessageBus,
    private val portManager: PortManager,
    private val eventManager: EventManager? = null
) {
    private val tabZoomMap = java.util.concurrent.ConcurrentHashMap<String, Double>()
    fun createTab(sender: ExtensionSender, createProperties: JSONObject): JSONObject {
        val valResult = ExtensionMessagingValidator.validateSender(sender, registry, permissionManager)
        if (valResult is ExtensionMessagingValidator.ValidationResult.Denied) {
            throw IllegalStateException(valResult.reason)
        }

        val url = createProperties.optString("url", "swift://newtab")
        val active = createProperties.optBoolean("active", true)
        val groupIdStr = if (createProperties.has("groupId")) {
            createProperties.get("groupId").toString()
        } else null

        val reqWindowId = if (createProperties.has("windowId")) createProperties.getInt("windowId") else null
        val isPrivate = if (reqWindowId != null) {
            reqWindowId == 2
        } else {
            sender.isPrivate
        }

        if (isPrivate && !permissionManager.isAllowedInPrivate(sender.extensionId)) {
            throw SecurityException("Access to private window denied")
        }

        val newTabModel = if (isPrivate) {
            val sessionId = sender.privateSessionId ?: "default_private_session"
            tabEngine.createPrivateTab(
                sessionId = sessionId,
                url = url,
                title = "Private Tab",
                groupId = groupIdStr
            )
        } else {
            tabEngine.createTab(
                url = url,
                title = "New Tab",
                isIncognito = false,
                groupId = groupIdStr
            )
        }

        if (active) {
            tabEngine.switchTab(newTabModel.id)
        }

        val wv = tabEngine.getWebView(newTabModel.id)
        if (wv != null && url.isNotBlank() && url != "swift://newtab") {
            wv.post { wv.loadUrl(url) }
        }

        return formatTabObject(newTabModel, sender.extensionId, active)
    }

    fun getTab(sender: ExtensionSender, tabIdInput: Any?): JSONObject {
        val valResult = ExtensionMessagingValidator.validateSender(sender, registry, permissionManager)
        if (valResult is ExtensionMessagingValidator.ValidationResult.Denied) {
            throw IllegalStateException(valResult.reason)
        }

        val uuid = resolveTabUuid(tabIdInput, sender)
        val tab = tabEngine.getTab(uuid) ?: throw IllegalArgumentException("Tab not found: $tabIdInput")

        if ((tab.isPrivate || tab.isIncognito) && !permissionManager.isAllowedInPrivate(sender.extensionId)) {
            throw SecurityException("Access to private tab denied")
        }

        val isActive = tabEngine.getActiveTab()?.id == tab.id
        return formatTabObject(tab, sender.extensionId, isActive)
    }

    fun queryTabs(sender: ExtensionSender, queryInfo: JSONObject): JSONArray {
        val valResult = ExtensionMessagingValidator.validateSender(sender, registry, permissionManager)
        if (valResult is ExtensionMessagingValidator.ValidationResult.Denied) {
            throw IllegalStateException(valResult.reason)
        }

        val hasPrivateAccess = permissionManager.isAllowedInPrivate(sender.extensionId)

        // Determine which windows to query
        val targetWindowId = when {
            queryInfo.has("windowId") -> queryInfo.getInt("windowId")
            queryInfo.optBoolean("currentWindow", false) || queryInfo.optBoolean("lastFocusedWindow", false) -> {
                val activeTab = tabEngine.getActiveTab()
                if (activeTab?.isPrivate == true) 2 else 1
            }
            else -> null
        }

        val tabsToQuery = mutableListOf<TabModel>()
        if (targetWindowId != null) {
            if (targetWindowId == 2) {
                if (hasPrivateAccess) {
                    val pTabs = if (!sender.privateSessionId.isNullOrBlank()) {
                        tabEngine.getPrivateTabs(sender.privateSessionId)
                    } else {
                        tabEngine.getPrivateTabs()
                    }
                    tabsToQuery.addAll(pTabs)
                }
            } else if (targetWindowId == 1) {
                tabsToQuery.addAll(tabEngine.getNormalTabs())
            }
        } else {
            // Include normal tabs
            tabsToQuery.addAll(tabEngine.getNormalTabs())
            // Include private tabs if allowed
            if (hasPrivateAccess) {
                val pTabs = if (!sender.privateSessionId.isNullOrBlank()) {
                    tabEngine.getPrivateTabs(sender.privateSessionId)
                } else {
                    tabEngine.getPrivateTabs()
                }
                tabsToQuery.addAll(pTabs)
            }
        }

        // Fallback: If tabsToQuery is empty, populate from active tab or tab groups
        val activeTab = tabEngine.getActiveTab()
        if (activeTab != null && tabsToQuery.none { it.id == activeTab.id }) {
            if (!activeTab.isPrivate || hasPrivateAccess) {
                tabsToQuery.add(activeTab)
            }
        }
        if (tabsToQuery.isEmpty()) {
            val allGroupTabs = tabEngine.groups.value.flatMap { it.tabs }
            val allowedTabs = allGroupTabs.filter { !it.isPrivate || hasPrivateAccess }
            tabsToQuery.addAll(allowedTabs)
        }

        val activeTabId = activeTab?.id ?: tabEngine.getActiveTab()?.id

        // Apply filters
        val activeFilter = if (queryInfo.has("active")) queryInfo.getBoolean("active") else null
        val urlFilter = if (queryInfo.has("url")) queryInfo.getString("url") else null
        val titleFilter = if (queryInfo.has("title")) queryInfo.getString("title") else null
        val statusFilter = if (queryInfo.has("status")) queryInfo.getString("status") else null
        val groupIdFilter = if (queryInfo.has("groupId")) queryInfo.get("groupId") else null

        val filtered = tabsToQuery.filter { tab ->
            val isActive = tab.id == activeTabId
            if (activeFilter != null && isActive != activeFilter) return@filter false
            if (urlFilter != null && !matchesUrlPattern(tab.url, urlFilter)) return@filter false
            if (titleFilter != null && !tab.title.contains(titleFilter, ignoreCase = true)) return@filter false
            if (statusFilter != null) {
                val currentStatus = "complete"
                if (!currentStatus.equals(statusFilter, ignoreCase = true)) return@filter false
            }
            if (groupIdFilter != null) {
                val tabGroupIntId = tab.groupId?.hashCode() ?: -1
                if (groupIdFilter is Number) {
                    if (tabGroupIntId != groupIdFilter.toInt()) return@filter false
                } else if (groupIdFilter.toString() != tab.groupId) {
                    return@filter false
                }
            }
            true
        }

        val jsonArray = JSONArray()
        filtered.forEachIndexed { idx, tab ->
            val isActive = tab.id == activeTabId
            val tabObj = formatTabObject(tab, sender.extensionId, isActive, idx)
            jsonArray.put(tabObj)
        }
        return jsonArray
    }

    fun updateTab(sender: ExtensionSender, tabIdInput: Any?, updateProperties: JSONObject): JSONObject {
        val valResult = ExtensionMessagingValidator.validateSender(sender, registry, permissionManager)
        if (valResult is ExtensionMessagingValidator.ValidationResult.Denied) {
            throw IllegalStateException(valResult.reason)
        }

        val uuid = resolveTabUuid(tabIdInput, sender)
        val tab = tabEngine.getTab(uuid) ?: throw IllegalArgumentException("Tab not found: $tabIdInput")

        if ((tab.isPrivate || tab.isIncognito) && !permissionManager.isAllowedInPrivate(sender.extensionId)) {
            throw SecurityException("Access to private tab denied")
        }

        if (updateProperties.has("url")) {
            val newUrl = updateProperties.getString("url")
            tabEngine.updateTab(uuid) { it.copy(url = newUrl) }
            val wv = tabEngine.getWebView(uuid)
            if (wv != null) {
                wv.post { wv.loadUrl(newUrl) }
            }
        }

        if (updateProperties.optBoolean("active", false) || updateProperties.optBoolean("selected", false)) {
            tabEngine.switchTab(uuid)
        }

        val updatedTab = tabEngine.getTab(uuid) ?: tab
        val isActive = tabEngine.getActiveTab()?.id == updatedTab.id
        return formatTabObject(updatedTab, sender.extensionId, isActive)
    }

    fun removeTabs(sender: ExtensionSender, tabIdInputs: List<Any>): JSONObject {
        val valResult = ExtensionMessagingValidator.validateSender(sender, registry, permissionManager)
        if (valResult is ExtensionMessagingValidator.ValidationResult.Denied) {
            throw IllegalStateException(valResult.reason)
        }

        for (input in tabIdInputs) {
            val uuid = resolveTabUuid(input, sender)
            tabEngine.closeTab(uuid)
            portManager.cleanupForTab(uuid)
        }
        return JSONObject().apply { put("success", true) }
    }

    fun reloadTab(sender: ExtensionSender, tabIdInput: Any?) {
        val valResult = ExtensionMessagingValidator.validateSender(sender, registry, permissionManager)
        if (valResult is ExtensionMessagingValidator.ValidationResult.Denied) {
            throw IllegalStateException(valResult.reason)
        }
        val uuid = resolveTabUuid(tabIdInput, sender)
        val webView = tabEngine.getWebView(uuid)
        webView?.post { webView.reload() }
    }

    fun goBack(sender: ExtensionSender, tabIdInput: Any?): Boolean {
        val valResult = ExtensionMessagingValidator.validateSender(sender, registry, permissionManager)
        if (valResult is ExtensionMessagingValidator.ValidationResult.Denied) {
            return false
        }
        val uuid = resolveTabUuid(tabIdInput, sender)
        val tab = tabEngine.getTab(uuid) ?: tabEngine.getActiveTab() ?: return false
        if ((tab.isPrivate || tab.isIncognito) && !permissionManager.isAllowedInPrivate(sender.extensionId)) {
            return false
        }
        val webView = tabEngine.getWebView(uuid) ?: return false
        webView.post {
            if (webView.canGoBack()) {
                webView.goBack()
            }
        }
        return true
    }

    fun goForward(sender: ExtensionSender, tabIdInput: Any?): Boolean {
        val valResult = ExtensionMessagingValidator.validateSender(sender, registry, permissionManager)
        if (valResult is ExtensionMessagingValidator.ValidationResult.Denied) {
            return false
        }
        val uuid = resolveTabUuid(tabIdInput, sender)
        val tab = tabEngine.getTab(uuid) ?: tabEngine.getActiveTab() ?: return false
        if ((tab.isPrivate || tab.isIncognito) && !permissionManager.isAllowedInPrivate(sender.extensionId)) {
            return false
        }
        val webView = tabEngine.getWebView(uuid) ?: return false
        webView.post {
            if (webView.canGoForward()) {
                webView.goForward()
            }
        }
        return true
    }

    fun sendMessageToTab(
        sender: ExtensionSender,
        targetTabIdInput: Any?,
        message: JSONObject,
        callbackId: String?
    ) {
        val valResult = ExtensionMessagingValidator.validateSender(sender, registry, permissionManager)
        if (valResult is ExtensionMessagingValidator.ValidationResult.Denied) {
            throw IllegalStateException(valResult.reason)
        }

        val uuid = resolveTabUuid(targetTabIdInput, sender)
        val targetTab = tabEngine.getTab(uuid) ?: throw IllegalArgumentException("Target tab not found: $targetTabIdInput")

        messageBus.broadcastMessage(
            sender = sender.copy(contextType = ExtensionContextType.BACKGROUND, tabId = targetTab.id),
            message = message,
            callbackId = callbackId,
            targetTabId = uuid
        )
    }

    fun connectToTab(
        sender: ExtensionSender,
        targetTabIdInput: Any?,
        connectInfo: JSONObject?
    ): PortConnection {
        val valResult = ExtensionMessagingValidator.validateSender(sender, registry, permissionManager)
        if (valResult is ExtensionMessagingValidator.ValidationResult.Denied) {
            throw IllegalStateException(valResult.reason)
        }

        val uuid = resolveTabUuid(targetTabIdInput, sender)
        val channelId = "port_tab_${uuid}_${System.currentTimeMillis()}"
        val name = connectInfo?.optString("name", "") ?: ""

        portManager.connect(
            extensionId = sender.extensionId,
            channelId = channelId,
            portName = name,
            senderId = sender.extensionId,
            sender = sender,
            tabId = uuid
        )

        return portManager.registry.get(channelId)!!
    }

    private fun resolveTabUuid(input: Any?, sender: ExtensionSender): String {
        if (input == null || input == "" || input == "null") {
            val active = tabEngine.getActiveTab()
            if (active != null) {
                if (active.isPrivate && !permissionManager.isAllowedInPrivate(sender.extensionId)) {
                    return tabEngine.getNormalTabs().firstOrNull()?.id ?: active.id
                }
                return active.id
            }
            return ""
        }
        return when (input) {
            is Int -> TabIdMapper.getUuid(input) ?: input.toString()
            is Number -> TabIdMapper.getUuid(input.toInt()) ?: input.toString()
            is String -> {
                val parsedInt = input.toIntOrNull()
                if (parsedInt != null) {
                    TabIdMapper.getUuid(parsedInt) ?: input
                } else {
                    TabIdMapper.getUuidFromString(input)
                }
            }
            else -> input.toString()
        }
    }

    private fun matchesUrlPattern(url: String, pattern: String): Boolean {
        if (pattern.isBlank()) return true
        if (pattern == "<all_urls>") return true
        return try {
            if (pattern.contains("*")) {
                PermissionManager.matchHostPattern(url, pattern)
            } else {
                url.contains(pattern, ignoreCase = true)
            }
        } catch (e: Exception) {
            url.contains(pattern, ignoreCase = true)
        }
    }

    fun formatTabObject(tab: TabModel, extensionId: String, isActive: Boolean, index: Int = 0): JSONObject {
        val intId = TabIdMapper.getIntId(tab.id)
        val ext = registry.getExtension(extensionId)
        val hasTabsOrHostPermission = ext != null && (
                permissionManager.hasApiPermission(extensionId, "tabs") ||
                permissionManager.hasHostPermission(extensionId, tab.url)
        )
        val hasActiveTabPermission = ext != null && isActive && permissionManager.hasApiPermission(extensionId, "activeTab")
        val canAccessSensitiveProperties = hasTabsOrHostPermission || hasActiveTabPermission

        return JSONObject().apply {
            put("id", intId)
            put("index", index.coerceAtLeast(0))
            put("windowId", if (tab.isPrivate || tab.isIncognito) 2 else 1)
            put("active", isActive)
            put("selected", isActive)
            put("highlighted", isActive)
            put("pinned", false)
            put("status", if (tab.isLoading) "loading" else "complete")
            put("discarded", tab.freezeState != 0)
            put("autoDiscardable", true)
            put("incognito", tab.isIncognito || tab.isPrivate)
            if (tab.groupId != null) {
                put("groupId", tab.groupId.hashCode())
            } else {
                put("groupId", -1)
            }
            if (canAccessSensitiveProperties) {
                val canShowSensitive = !tab.isPrivate || permissionManager.isAllowedInPrivate(extensionId)
                if (canShowSensitive) {
                    put("url", tab.url)
                    put("title", tab.title)
                    if (tab.faviconUrl != null) put("favIconUrl", tab.faviconUrl)
                }
            }
        }
    }

    fun captureVisibleTab(sender: ExtensionSender, windowId: Int, options: JSONObject): String {
        val valResult = ExtensionMessagingValidator.validateSender(sender, registry, permissionManager)
        if (valResult is ExtensionMessagingValidator.ValidationResult.Denied) {
            throw IllegalStateException(valResult.reason)
        }
        val ext = registry.getExtension(sender.extensionId)
        val hasActiveTab = ext != null && permissionManager.hasApiPermission(sender.extensionId, "activeTab")
        val hasTabs = ext != null && permissionManager.hasApiPermission(sender.extensionId, "tabs")
        val hasAllUrls = ext != null && (
            permissionManager.hasHostPermission(sender.extensionId, "<all_urls>") ||
            permissionManager.hasHostPermission(sender.extensionId, "*://*/*")
        )
        if (!hasActiveTab && !hasTabs && !hasAllUrls) {
            throw SecurityException("Extension does not have permission to capture visible tab")
        }

        val activeTab = tabEngine.getActiveTab() ?: throw IllegalStateException("No active tab")
        if (activeTab.isPrivate && !permissionManager.isAllowedInPrivate(sender.extensionId)) {
            throw SecurityException("Private tab access denied")
        }

        val webView = tabEngine.getWebView(activeTab.id) ?: throw IllegalStateException("WebView not available")

        val format = options.optString("format", "jpeg").lowercase()
        val quality = options.optInt("quality", 90).coerceIn(0, 100)

        val latch = java.util.concurrent.CountDownLatch(1)
        var resultBase64 = ""
        var errorMsg: String? = null

        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        handler.post {
            try {
                val width = webView.width.coerceAtLeast(1)
                val height = webView.height.coerceAtLeast(1)
                val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(bitmap)
                webView.draw(canvas)

                val out = java.io.ByteArrayOutputStream()
                val compressFormat = if (format == "png") {
                    android.graphics.Bitmap.CompressFormat.PNG
                } else {
                    android.graphics.Bitmap.CompressFormat.JPEG
                }
                bitmap.compress(compressFormat, quality, out)
                val bytes = out.toByteArray()
                val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                val mime = if (format == "png") "image/png" else "image/jpeg"
                resultBase64 = "data:$mime;base64,$b64"
                bitmap.recycle()
            } catch (e: Throwable) {
                errorMsg = e.message
            } finally {
                latch.countDown()
            }
        }

        latch.await(3, java.util.concurrent.TimeUnit.SECONDS)
        if (errorMsg != null) throw IllegalStateException("Capture failed: $errorMsg")
        if (resultBase64.isEmpty()) throw IllegalStateException("Capture timed out or produced empty data")
        return resultBase64
    }

    fun detectLanguage(sender: ExtensionSender, tabId: Int? = null): String {
        val valResult = ExtensionMessagingValidator.validateSender(sender, registry, permissionManager)
        if (valResult is ExtensionMessagingValidator.ValidationResult.Denied) {
            throw IllegalStateException(valResult.reason)
        }
        val targetTab = if (tabId != null) {
            val uuid = TabIdMapper.getUuid(tabId) ?: return "und"
            tabEngine.getTab(uuid) ?: return "und"
        } else {
            tabEngine.getActiveTab() ?: return "und"
        }
        if (targetTab.isPrivate && !permissionManager.isAllowedInPrivate(sender.extensionId)) {
            return "und"
        }

        val webView = tabEngine.getWebView(targetTab.id)
        if (webView != null) {
            val latch = java.util.concurrent.CountDownLatch(1)
            var detectedLang: String? = null
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                val js = """
                    (function() {
                        var docLang = (document.documentElement && document.documentElement.lang) || (document.body && document.body.getAttribute('lang'));
                        if (docLang && docLang.trim().length > 0) return docLang.trim().split('-')[0].toLowerCase();
                        var meta = document.querySelector('meta[http-equiv="content-language"], meta[name="language"], meta[property="og:locale"]');
                        if (meta && meta.content) return meta.content.trim().split('_')[0].split('-')[0].toLowerCase();
                        var text = (document.body ? document.body.innerText || '' : '').substring(0, 1000);
                        if (/[\u0900-\u097F]/.test(text)) return 'hi';
                        if (/[\u4E00-\u9FFF]/.test(text)) return 'zh';
                        if (/[\u3040-\u30FF]/.test(text)) return 'ja';
                        if (/[\u0600-\u06FF]/.test(text)) return 'ar';
                        if (/[\u0400-\u04FF]/.test(text)) return 'ru';
                        if (/[\u0370-\u03FF]/.test(text)) return 'el';
                        if (/[\u0590-\u05FF]/.test(text)) return 'he';
                        return '';
                    })();
                """.trimIndent()
                try {
                    webView.evaluateJavascript(js) { res ->
                        val clean = res?.trim()?.removeSurrounding("\"")
                        if (!clean.isNullOrBlank() && clean != "null") {
                            detectedLang = clean
                        }
                        latch.countDown()
                    }
                } catch (e: Throwable) {
                    latch.countDown()
                }
            }
            try {
                latch.await(1500, java.util.concurrent.TimeUnit.MILLISECONDS)
            } catch (_: Throwable) {}
            if (!detectedLang.isNullOrBlank()) {
                return detectedLang!!
            }
        }

        val host = android.net.Uri.parse(targetTab.url).host?.lowercase() ?: ""
        val tld = host.substringAfterLast('.', "")
        val langFromTld = when (tld) {
            "de" -> "de"
            "fr" -> "fr"
            "es" -> "es"
            "it" -> "it"
            "ru" -> "ru"
            "jp" -> "ja"
            "cn" -> "zh"
            "in" -> "hi"
            "br" -> "pt"
            else -> null
        }
        return langFromTld ?: "en"
    }

    fun discard(sender: ExtensionSender, tabId: Int? = null): JSONObject? {
        val valResult = ExtensionMessagingValidator.validateSender(sender, registry, permissionManager)
        if (valResult is ExtensionMessagingValidator.ValidationResult.Denied) {
            throw IllegalStateException(valResult.reason)
        }
        val targetTab = if (tabId != null) {
            val uuid = TabIdMapper.getUuid(tabId) ?: return null
            tabEngine.getTab(uuid) ?: return null
        } else {
            tabEngine.getActiveTab() ?: return null
        }
        if (targetTab.isPrivate && !permissionManager.isAllowedInPrivate(sender.extensionId)) {
            throw SecurityException("Private tab access denied")
        }
        val isActive = targetTab.id == tabEngine.getActiveTab()?.id
        if (!isActive) {
            val webView = tabEngine.getWebView(targetTab.id)
            if (webView != null) {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    try {
                        webView.onPause()
                        webView.stopLoading()
                    } catch (_: Exception) {}
                }
            }
        }
        eventManager?.triggerEvent("tabs.onUpdated", JSONObject().apply {
            put("tabId", TabIdMapper.getIntId(targetTab.id))
            put("changeInfo", JSONObject().put("discarded", true))
            put("tab", formatTabObject(targetTab, sender.extensionId, isActive).put("discarded", true))
        })
        return formatTabObject(targetTab, sender.extensionId, isActive).apply {
            put("discarded", true)
        }
    }

    fun goBack(sender: ExtensionSender, tabId: Int? = null): JSONObject {
        val valResult = ExtensionMessagingValidator.validateSender(sender, registry, permissionManager)
        if (valResult is ExtensionMessagingValidator.ValidationResult.Denied) {
            throw IllegalStateException(valResult.reason)
        }
        val targetUuid = if (tabId != null) TabIdMapper.getUuid(tabId) else tabEngine.getActiveTab()?.id
        val targetTab = if (targetUuid != null) tabEngine.getTab(targetUuid) else null
        if (targetTab != null && targetTab.isPrivate && !permissionManager.isAllowedInPrivate(sender.extensionId)) {
            throw SecurityException("Private tab access denied")
        }
        if (targetUuid != null) {
            val webView = tabEngine.getWebView(targetUuid)
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                if (webView != null && webView.canGoBack()) {
                    webView.goBack()
                }
            }
        }
        return JSONObject().put("status", "success")
    }

    fun goForward(sender: ExtensionSender, tabId: Int? = null): JSONObject {
        val valResult = ExtensionMessagingValidator.validateSender(sender, registry, permissionManager)
        if (valResult is ExtensionMessagingValidator.ValidationResult.Denied) {
            throw IllegalStateException(valResult.reason)
        }
        val targetUuid = if (tabId != null) TabIdMapper.getUuid(tabId) else tabEngine.getActiveTab()?.id
        val targetTab = if (targetUuid != null) tabEngine.getTab(targetUuid) else null
        if (targetTab != null && targetTab.isPrivate && !permissionManager.isAllowedInPrivate(sender.extensionId)) {
            throw SecurityException("Private tab access denied")
        }
        if (targetUuid != null) {
            val webView = tabEngine.getWebView(targetUuid)
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                if (webView != null && webView.canGoForward()) {
                    webView.goForward()
                }
            }
        }
        return JSONObject().put("status", "success")
    }

    fun getZoom(sender: ExtensionSender, tabId: Any? = null): Double {
        val uuid = resolveTabUuid(tabId, sender)
        return tabZoomMap[uuid] ?: 1.0
    }

    fun setZoom(sender: ExtensionSender, tabId: Any? = null, zoomFactor: Double): Double {
        val uuid = resolveTabUuid(tabId, sender)
        val validFactor = zoomFactor.coerceIn(0.25, 5.0)
        val oldZoom = tabZoomMap[uuid] ?: 1.0
        tabZoomMap[uuid] = validFactor
        val webView = tabEngine.getWebView(uuid)
        if (webView != null) {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                try {
                    webView.settings.textZoom = (validFactor * 100).toInt()
                    webView.setInitialScale((validFactor * 100).toInt())
                    webView.zoomBy((validFactor / oldZoom).toFloat())
                } catch (e: Exception) {
                    // Ignore zoom error on destroyed webview
                }
            }
        }
        eventManager?.triggerEvent("tabs.onZoomChange", JSONObject().apply {
            put("tabId", TabIdMapper.getIntId(uuid))
            put("oldZoomFactor", oldZoom)
            put("newZoomFactor", validFactor)
            put("zoomSettings", getZoomSettings(sender, TabIdMapper.getIntId(uuid)))
        })
        return validFactor
    }

    fun getZoomSettings(sender: ExtensionSender, tabId: Int? = null): JSONObject {
        return JSONObject().apply {
            put("mode", "automatic")
            put("scope", "per-origin")
            put("defaultZoomFactor", 1.0)
        }
    }

    fun setZoomSettings(sender: ExtensionSender, tabId: Int? = null, zoomSettings: JSONObject): JSONObject {
        return zoomSettings
    }

    fun duplicateTab(sender: ExtensionSender, tabIdInput: Any?): JSONObject {
        val valResult = ExtensionMessagingValidator.validateSender(sender, registry, permissionManager)
        if (valResult is ExtensionMessagingValidator.ValidationResult.Denied) {
            throw IllegalStateException(valResult.reason)
        }
        val uuid = resolveTabUuid(tabIdInput, sender)
        val tab = tabEngine.getTab(uuid) ?: throw IllegalArgumentException("Tab not found: $tabIdInput")
        if ((tab.isPrivate || tab.isIncognito) && !permissionManager.isAllowedInPrivate(sender.extensionId)) {
            throw SecurityException("Access to private tab denied")
        }
        val newTab = tabEngine.createTab(
            url = tab.url,
            title = tab.title,
            isIncognito = tab.isPrivate || tab.isIncognito,
            groupId = tab.groupId
        )
        tabEngine.switchTab(newTab.id)
        val isActive = tabEngine.getActiveTab()?.id == newTab.id
        return formatTabObject(newTab, sender.extensionId, isActive)
    }

    fun moveTab(sender: ExtensionSender, tabIdInput: Any?, moveProperties: JSONObject): JSONObject {
        val valResult = ExtensionMessagingValidator.validateSender(sender, registry, permissionManager)
        if (valResult is ExtensionMessagingValidator.ValidationResult.Denied) {
            throw IllegalStateException(valResult.reason)
        }
        val uuid = resolveTabUuid(tabIdInput, sender)
        val tab = tabEngine.getTab(uuid) ?: throw IllegalArgumentException("Tab not found: $tabIdInput")
        val index = moveProperties.optInt("index", 0)
        tabEngine.reorderTab(uuid, index)
        val isActive = tabEngine.getActiveTab()?.id == tab.id
        val updatedTab = tabEngine.getTab(uuid) ?: tab
        val formatted = formatTabObject(updatedTab, sender.extensionId, isActive, index = index)
        eventManager?.triggerEvent("tabs.onMoved", JSONObject().apply {
            put("tabId", TabIdMapper.getIntId(uuid))
            put("moveInfo", JSONObject().apply {
                put("fromIndex", 0)
                put("toIndex", index)
                put("windowId", if (tab.isPrivate || tab.isIncognito) 2 else 1)
            })
        })
        return formatted
    }

    fun moveTabs(sender: ExtensionSender, tabIdInputs: List<Any>, moveProperties: JSONObject): JSONArray {
        val result = JSONArray()
        val index = moveProperties.optInt("index", 0)
        for ((offset, input) in tabIdInputs.withIndex()) {
            val uuid = resolveTabUuid(input, sender)
            val targetIdx = index + offset
            tabEngine.reorderTab(uuid, targetIdx)
            val tab = tabEngine.getTab(uuid)
            if (tab != null) {
                val isActive = tabEngine.getActiveTab()?.id == tab.id
                result.put(formatTabObject(tab, sender.extensionId, isActive, index = targetIdx))
                eventManager?.triggerEvent("tabs.onMoved", JSONObject().apply {
                    put("tabId", TabIdMapper.getIntId(uuid))
                    put("moveInfo", JSONObject().apply {
                        put("fromIndex", 0)
                        put("toIndex", targetIdx)
                        put("windowId", if (tab.isPrivate || tab.isIncognito) 2 else 1)
                    })
                })
            }
        }
        return result
    }

    fun highlight(sender: ExtensionSender, highlightInfo: JSONObject): JSONObject {
        val valResult = ExtensionMessagingValidator.validateSender(sender, registry, permissionManager)
        if (valResult is ExtensionMessagingValidator.ValidationResult.Denied) {
            throw IllegalStateException(valResult.reason)
        }
        val targetWindowId = highlightInfo.optInt("windowId", 1)
        val tabIndices = mutableListOf<Int>()
        val tabs = highlightInfo.opt("tabs")
        if (tabs is JSONArray && tabs.length() > 0) {
            for (i in 0 until tabs.length()) {
                val tabVal = tabs.get(i)
                val uuid = resolveTabUuid(tabVal, sender)
                if (i == 0) {
                    tabEngine.switchTab(uuid)
                }
                tabIndices.add(TabIdMapper.getIntId(uuid))
            }
        } else if (tabs is Number) {
            val uuid = resolveTabUuid(tabs.toInt(), sender)
            tabEngine.switchTab(uuid)
            tabIndices.add(TabIdMapper.getIntId(uuid))
        }
        val tabObj = tabEngine.getActiveTab()
        val windowObj = JSONObject().apply {
            put("id", if (tabObj?.isPrivate == true) 2 else 1)
            put("focused", true)
            put("tabs", JSONArray().apply {
                val allTabs = if (tabObj?.isPrivate == true) tabEngine.getPrivateTabs() else tabEngine.getNormalTabs()
                for ((idx, t) in allTabs.withIndex()) {
                    put(formatTabObject(t, sender.extensionId, t.id == tabObj?.id, index = idx))
                }
            })
        }
        eventManager?.triggerEvent("tabs.onHighlighted", JSONObject().apply {
            put("windowId", if (tabObj?.isPrivate == true) 2 else 1)
            put("tabIds", JSONArray(tabIndices))
        })
        return windowObj
    }

    fun group(sender: ExtensionSender, options: JSONObject): Int {
        val valResult = ExtensionMessagingValidator.validateSender(sender, registry, permissionManager)
        if (valResult is ExtensionMessagingValidator.ValidationResult.Denied) {
            throw IllegalStateException(valResult.reason)
        }
        val tabIds = options.optJSONArray("tabIds")
        val requestedGroupId = if (options.has("groupId")) options.getInt("groupId") else (System.currentTimeMillis() % 100000).toInt()
        val groupIdStr = requestedGroupId.toString()

        if (tabIds != null) {
            for (i in 0 until tabIds.length()) {
                val numericId = tabIds.getInt(i)
                val uuid = TabIdMapper.getUuid(numericId)
                if (uuid != null) {
                    val tab = tabEngine.getTab(uuid)
                    if (tab != null && (!tab.isPrivate || permissionManager.isAllowedInPrivate(sender.extensionId))) {
                        tabEngine.updateTab(uuid) { it.copy(groupId = groupIdStr) }
                    }
                }
            }
        }
        return requestedGroupId
    }

    fun ungroup(sender: ExtensionSender, tabIds: List<Int>): JSONObject {
        val valResult = ExtensionMessagingValidator.validateSender(sender, registry, permissionManager)
        if (valResult is ExtensionMessagingValidator.ValidationResult.Denied) {
            throw IllegalStateException(valResult.reason)
        }
        for (numericId in tabIds) {
            val uuid = TabIdMapper.getUuid(numericId)
            if (uuid != null) {
                val tab = tabEngine.getTab(uuid)
                if (tab != null && (!tab.isPrivate || permissionManager.isAllowedInPrivate(sender.extensionId))) {
                    tabEngine.updateTab(uuid) { it.copy(groupId = null) }
                }
            }
        }
        return JSONObject().put("status", "success")
    }

    fun executeScript(
        sender: ExtensionSender,
        tabId: Int?,
        details: JSONObject,
        callback: (JSONArray?, String?) -> Unit
    ) {
        val valResult = ExtensionMessagingValidator.validateSender(sender, registry, permissionManager)
        if (valResult is ExtensionMessagingValidator.ValidationResult.Denied) {
            callback(null, valResult.reason)
            return
        }
        val targetUuid = if (tabId != null) TabIdMapper.getUuid(tabId) else tabEngine.getActiveTab()?.id
        if (targetUuid == null) {
            callback(null, "TABS_NOT_FOUND")
            return
        }
        val targetTab = tabEngine.getTab(targetUuid)
        if (targetTab == null) {
            callback(null, "TABS_NOT_FOUND")
            return
        }
        if (targetTab.isPrivate && !permissionManager.isAllowedInPrivate(sender.extensionId)) {
            callback(null, "TABS_PRIVATE_DENIED")
            return
        }

        val code = details.optString("code", "")
        val file = details.optString("file", "")
        val webView = tabEngine.getWebView(targetUuid)
        if (webView == null) {
            callback(null, "TABS_WEBVIEW_NOT_FOUND")
            return
        }

        val scriptToRun = when {
            code.isNotBlank() -> code
            file.isNotBlank() -> {
                val ext = registry.getExtension(sender.extensionId)
                if (ext == null) {
                    callback(null, "EXTENSION_NOT_FOUND")
                    return
                }
                val cleanPath = file.removePrefix("/").removePrefix("./")
                val extDir = ExtensionDirectoryResolver.getExtensionDir(webView.context, ext.id)
                val scriptFile = ExtensionDirectoryResolver.findFileCaseInsensitive(extDir, cleanPath)
                if (scriptFile == null || !scriptFile.exists()) {
                    callback(null, "SCRIPT_FILE_NOT_FOUND")
                    return
                }
                scriptFile.readText()
            }
            else -> {
                callback(null, "SCRIPT_EMPTY")
                return
            }
        }

        android.os.Handler(android.os.Looper.getMainLooper()).post {
            webView.evaluateJavascript(scriptToRun) { rawRes ->
                val array = JSONArray()
                if (rawRes != null && rawRes != "null") {
                    array.put(rawRes.removeSurrounding("\""))
                } else {
                    array.put(JSONObject.NULL)
                }
                callback(array, null)
            }
        }
    }

    fun insertCSS(
        sender: ExtensionSender,
        tabId: Int?,
        details: JSONObject,
        callback: (Boolean, String?) -> Unit
    ) {
        val valResult = ExtensionMessagingValidator.validateSender(sender, registry, permissionManager)
        if (valResult is ExtensionMessagingValidator.ValidationResult.Denied) {
            callback(false, valResult.reason)
            return
        }
        val targetUuid = if (tabId != null) TabIdMapper.getUuid(tabId) else tabEngine.getActiveTab()?.id
        if (targetUuid == null) {
            callback(false, "TABS_NOT_FOUND")
            return
        }
        val targetTab = tabEngine.getTab(targetUuid)
        if (targetTab == null) {
            callback(false, "TABS_NOT_FOUND")
            return
        }
        if (targetTab.isPrivate && !permissionManager.isAllowedInPrivate(sender.extensionId)) {
            callback(false, "TABS_PRIVATE_DENIED")
            return
        }

        val code = details.optString("code", "")
        val file = details.optString("file", "")
        val webView = tabEngine.getWebView(targetUuid)
        if (webView == null) {
            callback(false, "TABS_WEBVIEW_NOT_FOUND")
            return
        }

        val cssContent = when {
            code.isNotBlank() -> code
            file.isNotBlank() -> {
                val ext = registry.getExtension(sender.extensionId)
                if (ext == null) {
                    callback(false, "EXTENSION_NOT_FOUND")
                    return
                }
                val cleanPath = file.removePrefix("/").removePrefix("./")
                val extDir = ExtensionDirectoryResolver.getExtensionDir(webView.context, ext.id)
                val cssFile = ExtensionDirectoryResolver.findFileCaseInsensitive(extDir, cleanPath)
                if (cssFile == null || !cssFile.exists()) {
                    callback(false, "CSS_FILE_NOT_FOUND")
                    return
                }
                cssFile.readText()
            }
            else -> ""
        }

        val escaped = JSONObject.quote(cssContent)
        val js = """
            (function() {
                var s = document.createElement('style');
                s.setAttribute('data-orion-extension', '${sender.extensionId}');
                s.textContent = $escaped;
                (document.head || document.documentElement).appendChild(s);
            })();
        """.trimIndent()

        android.os.Handler(android.os.Looper.getMainLooper()).post {
            webView.evaluateJavascript(js) {
                callback(true, null)
            }
        }
    }

    fun removeCSS(
        sender: ExtensionSender,
        tabId: Int?,
        details: JSONObject,
        callback: (Boolean, String?) -> Unit
    ) {
        val targetUuid = if (tabId != null) TabIdMapper.getUuid(tabId) else tabEngine.getActiveTab()?.id
        if (targetUuid == null) {
            callback(false, "TABS_NOT_FOUND")
            return
        }
        val webView = tabEngine.getWebView(targetUuid)
        if (webView == null) {
            callback(false, "TABS_WEBVIEW_NOT_FOUND")
            return
        }
        val js = """
            (function() {
                var styles = document.querySelectorAll('style[data-orion-extension="${sender.extensionId}"]');
                for (var i = 0; i < styles.length; i++) {
                    styles[i].parentNode && styles[i].parentNode.removeChild(styles[i]);
                }
            })();
        """.trimIndent()
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            webView.evaluateJavascript(js) {
                callback(true, null)
            }
        }
    }
}
