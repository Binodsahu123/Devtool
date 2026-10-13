package com.swift.browser.browserengine

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import com.swift.browser.newsengine.NewsItemEntity

import com.swift.browser.desktopengine.api.DesktopEngineProvider

enum class BrowserState {
    IDLE,
    LOADING,
    READY,
    STOPPED,
    ERROR,
    RESTORING,
    ATTACHED,
    DETACHED
}

data class BrowserPageState(
    val title: String = "",
    val url: String = "",
    val favicon: String? = null,
    val isIncognito: Boolean = false,
    val userAgentMode: String = "Default",
    val loadingProgress: Int = 0,
    val isLoading: Boolean = false
)

data class BrowserNavigationState(
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val historyIndex: Int = 0,
    val historyCount: Int = 0
)

data class BrowserError(
    val errorCode: Int = 0,
    val description: String = "",
    val failingUrl: String = ""
)

data class BrowserSession(
    val sessionId: String = "session_${System.currentTimeMillis()}",
    val activeTabId: String = "",
    val tabUrls: List<String> = emptyList()
)

enum class MediaType {
    VIDEO,
    AUDIO,
    IMAGE,
    DOCUMENT
}

data class LocalMediaItem(
    val id: String = "",
    val title: String = "",
    val artist: String = "",
    val path: String = "",
    val folder: String = "",
    val size: Long = 0L,
    val sizeFormatted: String = "",
    val mimeType: String = "",
    val dateAdded: Long = 0L,
    val duration: Long = 0L,
    val durationFormatted: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val thumbnailUri: String? = null,
    val type: MediaType = MediaType.DOCUMENT,
    val isFavorite: Boolean = false
)

data class ClearBrowsingDataResult(
    val historySuccess: Boolean = true,
    val cookiesSuccess: Boolean = true,
    val cacheSuccess: Boolean = true,
    val downloadsSuccess: Boolean = true,
    val errors: List<String> = emptyList()
) {
    val isAllSuccess: Boolean get() = historySuccess && cookiesSuccess && cacheSuccess && downloadsSuccess && errors.isEmpty()
}

data class BrowserUiState(
    val feedCategory: String = "For You",
    val isFeedLoading: Boolean = false,
    val articles: List<NewsItemEntity> = emptyList(),
    val isIncognito: Boolean = false,
    val activeTabId: String = "",
    val globalAdBlockEnabled: Boolean = true,
    val isJavaScriptEnabled: Boolean = true
)

open class BrowserViewModel(
    val app: android.app.Application? = null,
    val repo: com.swift.browser.data.BrowserRepository? = null,
    val preferenceManager: com.swift.browser.databasecore.PreferenceManager? = null
) : ViewModel() {
        val extensionApi: com.swift.browser.extensionengine.ExtensionEngineApi? by lazy {
        com.swift.browser.analyticscore.StartupTracker.recordStage(
            stage = "EXTENSION_ENGINE_CREATE",
            className = "ExtensionEngineApi",
            methodName = "getInstance"
        )
        app?.let {
            val api = com.swift.browser.extensionengine.ExtensionEngineApi.getInstance(it)
            api.delegate = object : com.swift.browser.extensionengine.BrowserDelegate {
                override fun queryTabs(queryInfo: org.json.JSONObject): org.json.JSONArray {
                    if (queryInfo.optBoolean("topSites", false)) {
                        val result = org.json.JSONArray()
                        val items = topSites.value
                        for (site in items) {
                            if (!site.isHidden) {
                                result.put(org.json.JSONObject().apply {
                                    put("url", site.url)
                                    put("title", site.title)
                                })
                            }
                        }
                        return result
                    }
                    return org.json.JSONArray()
                }
                override fun createTab(url: String, active: Boolean) {
                    val targetUrl = searchEngine.processInput(url)
                    val newTab = tabEngine.createTab(targetUrl, targetUrl)
                    if (active) tabEngine.switchTab(newTab.id)
                }
                override fun removeTab(tabId: String) {
                    val uuid: String? = tabId
                    if (uuid != null) tabEngine.closeTab(uuid)
                }
                override fun reloadTab(tabId: String) {
                    val uuid: String? = tabId
                    if (uuid != null) {
                        tabEngine.getWebView(uuid)?.post { tabEngine.getWebView(uuid)?.reload() }
                    }
                }
                override fun updateTab(tabId: String, url: String) {
                    val uuid: String? = tabId
                    if (uuid != null) {
                        tabEngine.updateTab(uuid) { it.copy(url = url) }
                        tabEngine.getWebView(uuid)?.post { tabEngine.getWebView(uuid)?.loadUrl(url) }
                    }
                }
                override fun showNotification(title: String, message: String) {
                    // Handled by extension engine or UI layer
                }
                override fun downloadFile(url: String, filename: String?) {
                    // Trigger download
                }
                override fun getActiveTabId(): String? = tabEngine.activeTabId.value
                override fun getWebView(tabId: String): android.webkit.WebView? = tabEngine.getWebView(tabId)
                override fun executeScriptOnTab(tabId: String, code: String, callback: (String?) -> Unit) {
                    val uuid: String? = tabId
                    if (uuid != null) {
                        val wv = tabEngine.getWebView(uuid)
                        wv?.post {
                            wv.evaluateJavascript(code, callback)
                        } ?: callback(null)
                    } else callback(null)
                }
                override fun executeExtensionScript(
                    extensionId: String,
                    target: com.swift.browser.extensionengine.ScriptExecutionTarget,
                    code: String,
                    isFunction: Boolean,
                    argsJson: String,
                    callback: (org.json.JSONArray?, String?) -> Unit
                ) {
                    val uuid = target.tabId
                    val wv = tabEngine.getWebView(uuid)
                    if (wv == null) {
                        callback(null, "SCRIPTING_TAB_NOT_FOUND")
                        return
                    }
                    val args = runCatching { org.json.JSONArray(argsJson) }.getOrElse {
                        callback(null, "SCRIPTING_INVALID_ARGS")
                        return
                    }
                    wv.post {
                        com.swift.browser.extensionengine.nativecore.WebViewExtensionWorldBridge.execute(
                            webView = wv,
                            tabId = uuid,
                            extensionId = extensionId,
                            target = target,
                            code = code,
                            isFunction = isFunction,
                            args = args,
                            callback = callback
                        )
                    }
                }
                override fun checkExtensionPermission(extensionId: String, permission: String, callback: (Boolean) -> Unit) {
                    val engine = api.extensionManager.engine as? com.swift.browser.extensionengine.ExtensionEngineImpl
                    val extension = engine?.registry?.getExtension(extensionId)
                    val granted = extension != null && try {
                        engine!!.permissionManager.hasApiPermission(extensionId, extension.permissions, permission)
                    } catch (_: Throwable) {
                        false
                    }
                    callback(granted)
                }
            }
            api
        }
    }
    val uiState = kotlinx.coroutines.flow.MutableStateFlow(BrowserUiState())
    val scrollChromeController = com.swift.browser.tabengine.ui.ScrollChromeController(viewModelScope)
    val contextMenuState = kotlinx.coroutines.flow.MutableStateFlow(ContextMenuState())

    data class RewriteRecord(
        val sourceUrl: String,
        val targetUrl: String,
        val timestamp: Long = System.currentTimeMillis()
    )

    private val lastRewriteCache = java.util.concurrent.ConcurrentHashMap<String, RewriteRecord>()

    val downloadEngine: com.swift.browser.downloadengine.DownloadEngine by lazy {
        com.swift.browser.analyticscore.StartupTracker.recordStage(
            stage = "DOWNLOAD_ENGINE_CREATE",
            className = "DownloadEngineProvider",
            methodName = "getEngine"
        )
        if (app != null) {
            com.swift.browser.downloadengine.DownloadEngineProvider.getEngine(app)
        } else {
            object : com.swift.browser.downloadengine.DownloadEngine {
                private val _dl = kotlinx.coroutines.flow.MutableStateFlow<List<com.swift.browser.downloadengine.DownloadItem>>(emptyList())
                override fun getDownloadsFlow() = _dl.asStateFlow()
                override fun getDownloadsByCategory(category: String) = _dl.asStateFlow()
                override suspend fun startDownload(url: String, fileName: String, mimeType: String, threads: Int) = 0L
                override fun setConfig(config: com.swift.browser.downloadengine.DownloadConfig) {}
                override fun getConfig() = com.swift.browser.downloadengine.DownloadConfig()
                override suspend fun insertOrUpdateDownload(item: com.swift.browser.downloadengine.DownloadItem) {}
                override suspend fun pauseDownload(id: Long) {}
                override suspend fun resumeDownload(id: Long) {}
                override suspend fun cancelDownload(id: Long) {}
                override suspend fun deleteDownload(id: Long) {
                    _dl.value = _dl.value.filterNot { it.id == id }
                }
                override suspend fun removeDownloadedFile(id: Long) {}
                override suspend fun renameDownload(id: Long, newName: String) {}
            }
        }
    }

    val downloads: kotlinx.coroutines.flow.StateFlow<List<com.swift.browser.downloadengine.DownloadItem>> by lazy { 
        downloadEngine.getDownloadsFlow().stateIn(
            viewModelScope,
            SharingStarted.Lazily,
            emptyList()
        )
    }

    val bookmarkEngine: com.swift.browser.bookmarkengine.api.BookmarkEngineApi by lazy {
        com.swift.browser.analyticscore.StartupTracker.recordStage(
            stage = "BOOKMARK_ENGINE_CREATE",
            className = "BookmarkEngineProvider",
            methodName = "getEngine"
        )
        if (app != null) {
            com.swift.browser.bookmarkengine.api.BookmarkEngineProvider.getEngine(app, this.viewModelScope)
        } else {
            com.swift.browser.bookmarkengine.api.BookmarkEngineProvider.api
        }
    }

    val bookmarks: kotlinx.coroutines.flow.StateFlow<List<com.swift.browser.bookmarkengine.Bookmark>> by lazy { bookmarkEngine.bookmarks }

    val readerEngine: com.swift.browser.readerengine.engine.ReaderModeEngine by lazy { com.swift.browser.readerengine.engine.ReaderModeEngine() }

    val translateEngine: com.swift.browser.translateengine.TranslateEngineApi by lazy {
        com.swift.browser.analyticscore.StartupTracker.recordStage(
            stage = "TRANSLATE_ENGINE_CREATE",
            className = "TranslateEngineApi",
            methodName = "getInstance"
        )
        if (app != null) {
            com.swift.browser.translateengine.TranslateEngineApi.getInstance(app)
        } else {
            com.swift.browser.translateengine.TranslateEngineApi.getInstance(android.app.Application())
        }
    }

    val historyEngine: com.swift.browser.historyengine.HistoryEngine by lazy {
        com.swift.browser.analyticscore.StartupTracker.recordStage(
            stage = "HISTORY_ENGINE_CREATE",
            className = "HistoryEngineProvider",
            methodName = "getEngine"
        )
        repo?.historyEngine ?: com.swift.browser.historyengine.api.HistoryEngineProvider.api
    }

    val history: kotlinx.coroutines.flow.StateFlow<List<com.swift.browser.historyengine.HistoryItem>> by lazy {
        historyEngine.getHistoryFlow().stateIn(
            viewModelScope,
            SharingStarted.Lazily,
            emptyList()
        )
    }

    val permissionEngine: com.swift.browser.permissionengine.PermissionEngine? by lazy {
        com.swift.browser.analyticscore.StartupTracker.recordStage(
            stage = "PERMISSION_ENGINE_CREATE",
            className = "PermissionEngineProvider",
            methodName = "get"
        )
        if (app != null) {
            com.swift.browser.permissionengine.PermissionEngineProvider.get(app)
        } else null
    }

    val privateModeEngine: com.swift.browser.privatemode.PrivateModeEngineApi by lazy {
        com.swift.browser.analyticscore.StartupTracker.recordStage(
            stage = "PRIVATE_MODE_ENGINE_CREATE",
            className = "PrivateModeEngineProvider",
            methodName = "getEngine"
        )
        if (app != null) {
            com.swift.browser.privatemode.PrivateModeEngineProvider.getEngine(app)
        } else {
            com.swift.browser.privatemode.PrivateModeEngineProvider.api
        }
    }

    val isBiometricUnlocked: kotlinx.coroutines.flow.StateFlow<Boolean> by lazy {
        privateModeEngine.isBiometricUnlocked
    }

    fun lockPrivateTabs() {
        privateModeEngine.lockPrivateTabs()
    }

    fun unlockPrivateTabs() {
        privateModeEngine.unlockPrivateTabs()
    }

    fun authenticatePrivateTabs(
        activity: androidx.fragment.app.FragmentActivity,
        onResult: (com.swift.browser.privatemode.BiometricAuthResult) -> Unit = {}
    ) {
        privateModeEngine.authenticateBiometric(
            activity = activity,
            config = com.swift.browser.privatemode.PrivateBiometricConfig(
                promptTitle = "Unlock Private Tabs",
                promptSubtitle = "Fingerprint or face unlock required",
                promptDescription = "Authenticate with biometrics to view active private tabs",
                allowDeviceCredentialFallback = true
            ),
            onResult = onResult
        )
    }

    val topSiteEngine: TopSiteEngine? by lazy {
        com.swift.browser.analyticscore.StartupTracker.recordStage(
            stage = "TOPSITE_ENGINE_CREATE",
            className = "TopSiteEngineImpl",
            methodName = "init"
        )
        val canonicalRepo = repo ?: app?.let { com.swift.browser.data.BrowserRepository(com.swift.browser.data.BrowserDatabase.getDatabase(it)) }
        canonicalRepo?.let { TopSiteEngineImpl(it) }
    }

    val topSites: kotlinx.coroutines.flow.StateFlow<List<com.swift.browser.data.TopSite>> by lazy {
        topSiteEngine?.getTopSites()?.stateIn(
            viewModelScope,
            SharingStarted.Lazily,
            emptyList()
        ) ?: kotlinx.coroutines.flow.MutableStateFlow(emptyList())
    }

    val newsEngine: com.swift.browser.newsengine.api.NewsEngineApi by lazy {
        com.swift.browser.analyticscore.StartupTracker.recordStage(
            stage = "NEWS_ENGINE_CREATE",
            className = "NewsEngineProvider",
            methodName = "getEngine"
        )
        if (app != null) {
            com.swift.browser.newsengine.api.NewsEngineProvider.getEngine(app, this.viewModelScope)
        } else {
            object : com.swift.browser.newsengine.api.NewsEngineApi {
                private val _uiState = kotlinx.coroutines.flow.MutableStateFlow(com.swift.browser.newsengine.state.NewsUiState())
                override val uiState = _uiState.asStateFlow()
                override fun selectCategory(category: String) {
                    _uiState.value = _uiState.value.copy(feedCategory = category)
                }
                override fun refresh() {}
                override fun getNewsFlow() = kotlinx.coroutines.flow.emptyFlow<List<com.swift.browser.newsengine.NewsItemEntity>>()
                override fun getFeedUrlForCategory(category: String) = ""
            }
        }
    }

    val tabEngine: com.swift.browser.tabengine.api.TabEngineApi by lazy {
        com.swift.browser.analyticscore.StartupTracker.recordStage(
            stage = "TAB_ENGINE_CREATE",
            className = "TabEngineProvider",
            methodName = "getEngine"
        )
        if (app != null) {
            com.swift.browser.tabengine.api.TabEngineProvider.getEngine(app, this.viewModelScope).apply {
                viewModelScope.launch(Dispatchers.IO) {
                    com.swift.browser.analyticscore.StartupTracker.recordStage(
                        stage = "TAB_ENGINE_INITIALIZE",
                        className = "TabEngine",
                        methodName = "initialize"
                    )
                    initialize()
                    android.util.Log.d("StartupTiming", "TAB_ENGINE_READY: ${System.currentTimeMillis()}")
                }
            }
        } else {
            object : com.swift.browser.tabengine.api.TabEngineApi {
                private val _groups = kotlinx.coroutines.flow.MutableStateFlow<List<com.swift.browser.tabengine.model.TabGroupModel>>(
                    listOf(
                        com.swift.browser.tabengine.model.TabGroupModel(
                            id = "group_1",
                            name = "Main Group",
                            tabs = listOf(com.swift.browser.tabengine.model.TabModel(id = "tab_1", url = "swift://newtab", title = "New Tab"))
                        )
                    )
                )
                override val groups = _groups.asStateFlow()
                override val activeGroupId = kotlinx.coroutines.flow.MutableStateFlow<String?>("group_1")
                override val activeTabId = kotlinx.coroutines.flow.MutableStateFlow<String?>("tab_1")

                override fun initialize() {}
                override fun shutdown() {}
                override fun handleLowMemory() {}
                override fun createTab(url: String, title: String, isIncognito: Boolean, groupId: String?): com.swift.browser.tabengine.model.TabModel {
                    val newTab = com.swift.browser.tabengine.model.TabModel(id = java.util.UUID.randomUUID().toString(), url = url, title = title, isIncognito = isIncognito)
                    val currentGroups = _groups.value
                    val targetGroup = currentGroups.firstOrNull() ?: com.swift.browser.tabengine.model.TabGroupModel(id = "group_1", name = "Main Group")
                    val updatedGroup = targetGroup.copy(tabs = targetGroup.tabs + newTab, activeTabId = newTab.id)
                    _groups.value = currentGroups.map { if (it.id == targetGroup.id) updatedGroup else it }
                    activeTabId.value = newTab.id
                    return newTab
                }
                override fun closeTab(tabId: String) {
                    _groups.value = _groups.value.map { g ->
                        g.copy(tabs = g.tabs.filterNot { it.id == tabId })
                    }.filter { it.tabs.isNotEmpty() }
                }
                override fun switchTab(tabId: String) {
                    activeTabId.value = tabId
                }
                override fun getWebView(tabId: String?) = null
                override fun putWebView(tabId: String?, webView: android.webkit.WebView) {}
                override fun getAllWebViews() = emptyMap<String, android.webkit.WebView>()
                override fun removeWebView(tabId: String?) = null
                override fun registerBrowserCoreJob(tabId: String, generation: Long, job: kotlinx.coroutines.Job) {}
                override fun cancelBrowserCoreJobs(tabId: String) {}
                override fun updateTabIfWebViewCurrent(
                    tabId: String,
                    webView: android.webkit.WebView,
                    generation: Long,
                    updater: (com.swift.browser.tabengine.model.TabModel) -> com.swift.browser.tabengine.model.TabModel
                ): Boolean {
                    updateTab(tabId, updater)
                    return true
                }
                override fun updateTab(tabId: String, updater: (com.swift.browser.tabengine.model.TabModel) -> com.swift.browser.tabengine.model.TabModel) {
                    _groups.value = _groups.value.map { g ->
                        g.copy(tabs = g.tabs.map { if (it.id == tabId) updater(it) else it })
                    }
                }
                override fun updateGroup(groupId: String, updater: (com.swift.browser.tabengine.model.TabGroupModel) -> com.swift.browser.tabengine.model.TabGroupModel) {
                    _groups.value = _groups.value.map { if (it.id == groupId) updater(it) else it }
                }
                override fun createGroup(name: String, isIncognito: Boolean, color: Long?): com.swift.browser.tabengine.model.TabGroupModel {
                    val g = com.swift.browser.tabengine.model.TabGroupModel(id = java.util.UUID.randomUUID().toString(), name = name, isIncognito = isIncognito, color = color ?: 0xFFCCCCCC)
                    _groups.value = _groups.value + g
                    return g
                }
                override fun switchGroup(groupId: String) { activeGroupId.value = groupId }
                override fun closeGroup(groupId: String) { _groups.value = _groups.value.filterNot { it.id == groupId } }
                override fun moveTabToGroup(tabId: String, targetGroupId: String) {}
                override fun getActiveTab() = _groups.value.flatMap { it.tabs }.find { it.id == activeTabId.value }
                override fun getActiveGroup() = _groups.value.find { it.id == activeGroupId.value }
                override fun getTab(tabId: String) = _groups.value.flatMap { it.tabs }.find { it.id == tabId }
                override fun closeAllIncognitoTabs() { _groups.value = _groups.value.map { g -> g.copy(tabs = g.tabs.filterNot { it.isIncognito }) } }
                override fun saveSession() {}
                override fun restoreSession() = _groups.value
            }
        }
    }

    val searchEngine: com.swift.browser.searchengine.SearchEngine by lazy {
        com.swift.browser.analyticscore.StartupTracker.recordStage(
            stage = "SEARCH_ENGINE_CREATE",
            className = "SearchEngineProvider",
            methodName = "getEngine"
        )
        com.swift.browser.searchengine.SearchEngineProvider.getEngine()
    }
    val searchSuggestionsProvider by lazy { com.swift.browser.searchengine.SearchSuggestionsProvider(searchEngine) }
    private var textToSpeech: android.speech.tts.TextToSpeech? = null

    init {
        // Wire core tab engine state asynchronously so constructor finishes in <1ms
        viewModelScope.launch {
            tabEngine.activeTabId.collect { tabId ->
                val activeTab = tabId?.let { tabEngine.getTab(it) }
                uiState.update { current ->
                    current.copy(
                        activeTabId = tabId ?: "",
                        isIncognito = activeTab?.isIncognito ?: false
                    )
                }
            }
        }
        viewModelScope.launch(Dispatchers.Default) {
            preferenceManager?.let { prefs ->
                launch {
                    prefs.isPurgePrivateOnTimeoutOrExit.collect { enabled ->
                        privateModeEngine.isAutoPurgeOnTimeoutOrExit = enabled
                    }
                }
                launch {
                    prefs.biometricTimeoutSeconds.collect { seconds ->
                        privateModeEngine.biometricTimeoutMillis = seconds * 1000L
                    }
                }
            }
            launch {
                newsEngine.uiState.collect { newsState ->
                    uiState.update { current ->
                        current.copy(
                            feedCategory = newsState.feedCategory,
                            isFeedLoading = newsState.isFeedLoading,
                            articles = newsState.articles
                        )
                    }
                }
            }
        }
    }

    fun normalizeUrl(url: String): String {
        return com.swift.browser.browserengine.BrowserNavigationEngine.normalizeUrl(url)
    }

    fun shouldPerformRewrite(tabId: String, currentUrl: String, targetUrl: String): Boolean {
        val normCurrent = normalizeUrl(currentUrl)
        val normTarget = normalizeUrl(targetUrl)
        if (normCurrent.isEmpty() || normCurrent == normTarget) {
            return false
        }
        val lastRecord = lastRewriteCache[tabId]
        val now = System.currentTimeMillis()
        if (lastRecord != null && (now - lastRecord.timestamp) < 3000L) {
            val normLastSource = normalizeUrl(lastRecord.sourceUrl)
            val normLastTarget = normalizeUrl(lastRecord.targetUrl)
            val isSameDirection = normLastSource == normCurrent && normLastTarget == normTarget
            val isReverseDirection = normLastSource == normTarget && normLastTarget == normCurrent
            if (isSameDirection || isReverseDirection) {
                android.util.Log.w("BrowserViewModel", "Rewrite loop blocked for tab $tabId ($normCurrent <-> $normTarget)")
                return false
            }
        }
        lastRewriteCache[tabId] = RewriteRecord(currentUrl, targetUrl, now)
        return true
    }

    fun clearRewriteCache(tabId: String? = null) {
        if (tabId != null) {
            lastRewriteCache.remove(tabId)
        } else {
            lastRewriteCache.clear()
        }
    }

    fun showContextMenu(
        url: String,
        isImage: Boolean,
        isImageLink: Boolean = false,
        imageUrl: String = "",
        tabId: String? = null
    ) {
        contextMenuState.value = ContextMenuState(
            show = true,
            url = url,
            isImage = isImage,
            isImageLink = isImageLink,
            imageUrl = imageUrl,
            tabId = tabId
        )
    }

    fun showContextMenuState(state: ContextMenuState) {
        contextMenuState.value = state.copy(show = true)
    }

    fun dismissContextMenu() {
        contextMenuState.value = ContextMenuState(show = false)
    }

    fun handleIncomingIntent(intent: android.content.Intent?) {
        if (intent == null) return
        val rawUrl = when {
            !intent.dataString.isNullOrBlank() -> intent.dataString?.trim()
            intent.action == android.content.Intent.ACTION_SEND && intent.type == "text/plain" -> intent.getStringExtra(android.content.Intent.EXTRA_TEXT)?.trim()
            else -> null
        }
        if (rawUrl.isNullOrBlank()) return

        // 1. If data is http/https:
        if (rawUrl.startsWith("http://", ignoreCase = true) || rawUrl.startsWith("https://", ignoreCase = true)) {
            addNewTab(rawUrl)
            return
        }

        // Standard local or internal schemes supported by the browser engine
        if (rawUrl.startsWith("file://", ignoreCase = true) ||
            rawUrl.startsWith("content://", ignoreCase = true) ||
            rawUrl.startsWith("swift://", ignoreCase = true) ||
            rawUrl.startsWith("about:", ignoreCase = true)
        ) {
            addNewTab(rawUrl)
            return
        }

        // 2. If data starts with intent://:
        if (rawUrl.startsWith("intent://", ignoreCase = true)) {
            try {
                val parsedIntent = android.content.Intent.parseUri(rawUrl, android.content.Intent.URI_INTENT_SCHEME)
                val fallbackUrl = parsedIntent.getStringExtra("browser_fallback_url")

                // Use browser_fallback_url when it is valid http/https
                if (!fallbackUrl.isNullOrBlank() && (fallbackUrl.startsWith("http://", ignoreCase = true) || fallbackUrl.startsWith("https://", ignoreCase = true))) {
                    addNewTab(fallbackUrl)
                    return
                }

                // Check if dataString from parsed intent is valid http/https
                val dataStr = parsedIntent.dataString
                if (!dataStr.isNullOrBlank() && (dataStr.startsWith("http://", ignoreCase = true) || dataStr.startsWith("https://", ignoreCase = true))) {
                    addNewTab(dataStr)
                    return
                }

                // Otherwise resolve the external Intent safely
                parsedIntent.addCategory(android.content.Intent.CATEGORY_BROWSABLE)
                parsedIntent.component = null
                parsedIntent.selector = null
                parsedIntent.flags = parsedIntent.flags or android.content.Intent.FLAG_ACTIVITY_NEW_TASK

                val context = app?.applicationContext
                if (context != null) {
                    try {
                        context.startActivity(parsedIntent)
                    } catch (e: Exception) {
                        android.util.Log.e("BrowserViewModel", "Target app unavailable for incoming intent: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("BrowserViewModel", "Failed parsing incoming intent URI: ${e.message}")
            }
            return
        }

        // 3. If data is a plain query or domain, process safely:
        if (rawUrl.contains(".") && !rawUrl.contains(" ") && !rawUrl.contains("://")) {
            addNewTab("https://$rawUrl")
        } else if (!rawUrl.contains("://")) {
            addNewTab(rawUrl)
        }
    }

    fun captureActiveVideoState() {}

    fun saveTabsState() {
        tabEngine.saveSession()
    }

    fun syncPrivateModeSettings() {
        preferenceManager?.let { prefs ->
            privateModeEngine.isAutoPurgeOnTimeoutOrExit = prefs.isPurgePrivateOnTimeoutOrExit.value
            privateModeEngine.biometricTimeoutMillis = prefs.biometricTimeoutSeconds.value * 1000L
        }
    }

    fun purgeAllPrivateCacheAndCookies() {
        viewModelScope.launch {
            privateModeEngine.purgeAllPrivateCacheAndCookies()
        }
    }

    fun onAppPause() {
        syncPrivateModeSettings()
        privateModeEngine.onAppBackgrounded()
        privateModeEngine.lockPrivateTabs()
    }

    fun onAppResume() {
        syncPrivateModeSettings()
        privateModeEngine.onAppForegrounded()
        tabEngine.restoreSession()
    }

    fun onAppDestroy() {
        syncPrivateModeSettings()
        if (privateModeEngine.isAutoPurgeOnTimeoutOrExit) {
            viewModelScope.launch {
                privateModeEngine.onAppExit()
            }
        }
    }

    fun openUrl(urlOrQuery: String, inNewTab: Boolean = false, isIncognito: Boolean = false) {
        val selectedEngine = preferenceManager?.getString("default_search_engine", "Google") ?: "Google"
        val targetUrl = if (urlOrQuery.startsWith("chrome-extension://", ignoreCase = true) ||
            urlOrQuery.startsWith("swift-extension://", ignoreCase = true) ||
            urlOrQuery.startsWith("swift://", ignoreCase = true) ||
            urlOrQuery.startsWith("about:", ignoreCase = true) ||
            urlOrQuery.startsWith("devtools://", ignoreCase = true)
        ) {
            urlOrQuery
        } else {
            searchEngine.processInput(urlOrQuery, selectedEngine)
        }
        val currentActiveId = tabEngine.activeTabId.value
        val currentTab = if (!currentActiveId.isNullOrEmpty()) tabEngine.getTab(currentActiveId) else null

        if (inNewTab || currentTab == null || currentTab.url == "swift://newtab" || currentTab.url == "swift://newtab-incognito" || currentTab.url.isEmpty()) {
            if (currentTab != null && (currentTab.url == "swift://newtab" || currentTab.url == "swift://newtab-incognito" || currentTab.url.isEmpty())) {
                tabEngine.updateTab(currentTab.id) { it.copy(url = targetUrl, title = targetUrl) }
                val wv = tabEngine.getWebView(currentTab.id)
                BrowserNavigationApi.navigate(
                    NavigationRequest(
                        tabId = currentTab.id,
                        url = targetUrl,
                        source = NavigationSource.USER_INPUT,
                        applyDesktopPolicy = true,
                        webView = wv
                    )
                )
            } else {
                val newTab = tabEngine.createTab(targetUrl, targetUrl, isIncognito)
                tabEngine.switchTab(newTab.id)
            }
        } else {
            tabEngine.updateTab(currentTab.id) { it.copy(url = targetUrl, title = targetUrl) }
            val wv = tabEngine.getWebView(currentTab.id)
            BrowserNavigationApi.navigate(
                NavigationRequest(
                    tabId = currentTab.id,
                    url = targetUrl,
                    source = NavigationSource.USER_INPUT,
                    applyDesktopPolicy = true,
                    webView = wv
                )
            )
        }
    }

    fun goHome() {
        android.util.Log.d("OrionBrowser", "ORION_HOME_START")
        val activeId = tabEngine.activeTabId.value
        if (activeId.isNullOrEmpty()) {
            android.util.Log.w("OrionBrowser", "ORION_HOME_FAILURE activeTabId is null")
            return
        }
        android.util.Log.d("OrionBrowser", "ORION_HOME_ACTIVE_TAB tabId=$activeId")

        val tab = tabEngine.getTab(activeId)
        if (tab == null) {
            android.util.Log.w("OrionBrowser", "ORION_HOME_FAILURE tab is null for $activeId")
            return
        }

        // Rapid click guard / Idempotency: if already on swift://newtab, don't re-navigate
        if (tab.url == "swift://newtab") {
            android.util.Log.d("OrionBrowser", "ORION_HOME_SUCCESS already on newtab")
            scrollChromeController.reset()
            return
        }

        // Reset chrome
        scrollChromeController.reset()

        // Reset translation state
        translateEngine.resetStateForNavigation(activeId)

        // Update current tab model
        android.util.Log.d("OrionBrowser", "ORION_HOME_NAVIGATE to swift://newtab")
        tabEngine.updateTab(activeId) {
            it.copy(
                url = "swift://newtab",
                title = "New Tab",
                isLoading = false,
                progress = 0
            )
        }

        val webView = tabEngine.getWebView(activeId)
        android.util.Log.d("OrionBrowser", "ORION_HOME_WEBVIEW_STATE exists=${webView != null}")
        if (webView != null) {
            try {
                webView.stopLoading()
            } catch (t: Throwable) {
                android.util.Log.w("OrionBrowser", "ORION_HOME_STOP_LOADING_ERR: ${t.javaClass.simpleName} ${t.message}")
            }
        }
        android.util.Log.d("OrionBrowser", "ORION_HOME_SUCCESS")
    }

    fun navigateToHome() {
        goHome()
    }

    fun downloadActiveFile(url: String, mimetype: String = "", contentDisposition: String = "") {
        viewModelScope.launch(Dispatchers.IO) {
            val fileName = android.webkit.URLUtil.guessFileName(url, contentDisposition, mimetype)
            downloadEngine.startDownload(url, fileName, mimetype, 3)
        }
    }

    fun addNewTab(url: String) {
        val targetUrl = if (url == "swift://newtab" || url.startsWith("http://") || url.startsWith("https://") || url.startsWith("about:") || url.startsWith("swift://") || url.startsWith("chrome-extension://") || url.startsWith("swift-extension://")) url else searchEngine.processInput(url)
        val newTab = tabEngine.createTab(targetUrl, if (targetUrl == "swift://newtab") "New Tab" else targetUrl)
        tabEngine.switchTab(newTab.id)
        extensionApi?.notifyTabCreated(newTab.id, targetUrl)
        extensionApi?.notifyTabActivated(newTab.id)
    }

    fun openInNewTabInGroup(url: String, parentTabId: String? = null) {
        val activeId = parentTabId ?: tabEngine.activeTabId.value
        val targetUrl = if (url == "swift://newtab" || url.startsWith("http://") || url.startsWith("https://") || url.startsWith("about:") || url.startsWith("swift://") || url.startsWith("chrome-extension://") || url.startsWith("swift-extension://")) url else searchEngine.processInput(url)
        if (activeId != null) {
            val parentTab = tabEngine.getTab(activeId)
            var targetGroupId = parentTab?.groupId
            if (targetGroupId == null) {
                val newGroup = tabEngine.createGroup("Group " + (tabEngine.groups.value.size + 1), parentTab?.isIncognito ?: false)
                targetGroupId = newGroup.id
                if (parentTab != null) {
                    tabEngine.moveTabToGroup(parentTab.id, targetGroupId)
                }
            }
            val newTab = tabEngine.createTab(targetUrl, if (targetUrl == "swift://newtab") "New Tab" else targetUrl, parentTab?.isIncognito ?: false, targetGroupId)
            tabEngine.switchTab(newTab.id)
        } else {
            addNewTab(targetUrl)
        }
    }

    fun closeTab(tabId: String) {
        // Capture the tab while it still exists. The sessions adapter owns the durable recently-
        // closed journal; event notification remains after the canonical TabEngine close.
        extensionApi?.notifyTabWillRemove(tabId)
        tabEngine.closeTab(tabId)
        extensionApi?.notifyTabRemoved(tabId)
    }

    fun setDownloadsOpen(open: Boolean) {}
    fun setHistoryOpen(open: Boolean) {}
    fun setBookmarksOpen(open: Boolean) {}

    fun createNewTabGroup(name: String, isPrivate: Boolean) {
        tabEngine.createGroup(name, isPrivate)
    }

    fun translateActivePage(lang: String) {}

    fun deleteDownloads(ids: Set<Long>) {
        viewModelScope.launch(Dispatchers.IO) {
            ids.forEach { id ->
                downloadEngine.deleteDownload(id)
            }
        }
    }

    fun renameDownload(id: Long, newName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            downloadEngine.renameDownload(id, newName)
        }
    }

    fun deleteBookmark(bookmark: com.swift.browser.bookmarkengine.Bookmark) {
        bookmarkEngine.deleteBookmark(bookmark)
    }

    fun addBookmarkExternally(url: String, title: String) {
        bookmarkEngine.addBookmark(url, title)
    }

    fun toggleBookmarkActive(url: String, title: String) {
        viewModelScope.launch(Dispatchers.IO) {
            bookmarkEngine.toggleBookmark(url, title)
        }
    }

    fun addHistory(url: String, title: String) {
        viewModelScope.launch(Dispatchers.IO) {
            historyEngine.addHistoryItem(url, title)
        }
    }

    fun deleteHistoryItem(id: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            historyEngine.deleteHistoryItem(id)
        }
    }

    fun clearAllHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            historyEngine.clearAllHistory()
        }
    }

    fun addCustomShortcut(url: String, title: String) {
        viewModelScope.launch(Dispatchers.IO) {
            topSiteEngine?.addCustomShortcut(url, title)
        }
    }

    fun deleteCustomShortcut(url: String) {
        viewModelScope.launch(Dispatchers.IO) {
            topSiteEngine?.removeTopSite(com.swift.browser.data.TopSite(url = url, title = "", isCustom = true))
        }
    }

    fun removeTopSite(topSite: com.swift.browser.data.TopSite) {
        viewModelScope.launch(Dispatchers.IO) {
            topSiteEngine?.removeTopSite(topSite)
        }
    }

    fun clearBrowsingData(
        clearHistory: Boolean,
        clearCookies: Boolean,
        clearCache: Boolean,
        clearDownloads: Boolean,
        context: android.content.Context,
        onComplete: ((ClearBrowsingDataResult) -> Unit)? = null
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            var histSuccess = true
            var cookieSuccess = true
            var cacheSuccess = true
            var dlSuccess = true
            val errorList = mutableListOf<String>()

            supervisorScope {
                if (clearHistory) {
                    launch {
                        try {
                            historyEngine.clearAllHistory()
                        } catch (e: Exception) {
                            histSuccess = false
                            errorList.add("History: ${e.message}")
                        }
                    }
                }
                if (clearCookies) {
                    launch {
                        try {
                            com.swift.browser.cookieengine.CookieEngineApi.getInstance(context).removeAllCookies()
                        } catch (e: Exception) {
                            cookieSuccess = false
                            errorList.add("Cookies: ${e.message}")
                        }
                    }
                }
                if (clearCache) {
                    launch {
                        try {
                            android.webkit.WebStorage.getInstance().deleteAllData()
                            withContext(Dispatchers.Main) {
                                try {
                                    val webView = android.webkit.WebView(context)
                                    webView.clearCache(true)
                                    webView.destroy()
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                            }
                        } catch (e: Exception) {
                            cacheSuccess = false
                            errorList.add("Cache: ${e.message}")
                        }
                    }
                }
                if (clearDownloads) {
                    launch {
                        try {
                            downloads.value.forEach { item ->
                                downloadEngine.deleteDownload(item.id)
                            }
                        } catch (e: Exception) {
                            dlSuccess = false
                            errorList.add("Downloads: ${e.message}")
                        }
                    }
                }
            }

            val result = ClearBrowsingDataResult(
                historySuccess = histSuccess,
                cookiesSuccess = cookieSuccess,
                cacheSuccess = cacheSuccess,
                downloadsSuccess = dlSuccess,
                errors = errorList
            )
            onComplete?.invoke(result)
        }
    }

    fun startListeningToPageText(
        context: android.content.Context? = null,
        webView: android.webkit.WebView? = null
    ) {
        if (context == null || webView == null) return
        webView.evaluateJavascript(
            "(function() { return document.body ? (document.body.innerText || document.body.textContent || '') : ''; })()"
        ) { result ->
            val text = result?.removeSurrounding("\"")?.replace("\\n", "\n")?.replace("\\\"", "\"")?.trim() ?: ""
            if (text.isNotBlank()) {
                if (textToSpeech == null) {
                    textToSpeech = android.speech.tts.TextToSpeech(context.applicationContext) { status ->
                        if (status == android.speech.tts.TextToSpeech.SUCCESS) {
                            textToSpeech?.language = java.util.Locale.getDefault()
                            textToSpeech?.speak(text.take(4000), android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "tts_page")
                        }
                    }
                } else {
                    textToSpeech?.speak(text.take(4000), android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "tts_page")
                }
                android.widget.Toast.makeText(context, "Reading page aloud...", android.widget.Toast.LENGTH_SHORT).show()
            } else {
                android.widget.Toast.makeText(context, "No text found on page to read", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun stopListeningToPageText() {
        textToSpeech?.stop()
    }

    override fun onCleared() {
        super.onCleared()
        scrollChromeController.dispose()
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        textToSpeech = null
    }
}

