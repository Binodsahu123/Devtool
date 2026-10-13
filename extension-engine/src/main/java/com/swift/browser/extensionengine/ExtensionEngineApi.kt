package com.swift.browser.extensionengine

import android.content.Context
import android.net.Uri
import android.webkit.WebView
import com.swift.browser.extensionengine.ui.ExtensionUiState
import com.swift.browser.extensionengine.ui.InstallStage
import com.swift.browser.extensionengine.ui.WebStoreInstallPrompt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.atomic.AtomicLong

class ExtensionEngineApi private constructor(private val context: Context) {

    var delegate: BrowserDelegate? = null
        set(value) {
            field = value
            extensionManager.updateDelegate(value)
        }
    val extensionManager: ExtensionManager by lazy { ExtensionManager(context.applicationContext, delegate) }
    private val scope = CoroutineScope(Dispatchers.Main)
    private val ioScope = CoroutineScope(Dispatchers.IO)
    private val okHttpClient = OkHttpClient()

    private val _uiState = MutableStateFlow(ExtensionUiState())
    // Monotonic UI revision prevents an older refresh coroutine from overwriting a just-installed extension.
    private val uiRevision = AtomicLong(0L)
    val uiState: StateFlow<ExtensionUiState> = _uiState.asStateFlow()

    private val prefs = context.getSharedPreferences("swift_extension_api_prefs", Context.MODE_PRIVATE)

    init {
        // Register permission handler so prompts trigger UI state
        SwiftExtensionPermissionEngine.promptHandler = object : ExtensionPermissionPromptHandler {
            override fun showPrompt(extId: String, extName: String, permission: String, onResult: (String) -> Unit) {
                scope.launch {
                    val req = PendingExtensionPermissionRequest(extId, extName, permission) { result ->
                        onResult(result)
                        _uiState.update { it.copy(pendingPermissionRequest = null, showPermissionDialog = false) }
                    }
                    _uiState.update { it.copy(pendingPermissionRequest = req, showPermissionDialog = true) }
                }
            }
        }

        // Load initial state
        refreshExtensions()
        loadCustomScript()
    }

    fun refreshExtensions() {
        val revisionAtStart = uiRevision.get()
        scope.launch {
            try {
                (extensionManager.engine as? ExtensionEngineImpl)?.awaitHydrated()
                val registeredList = extensionManager.engine.registry.getAllRegisteredExtensions()
                val parsedList = registeredList.map { info ->
                    val isEnabled = info.state == ExtensionState.INSTALLED_ENABLED || info.state == ExtensionState.ACTIVE
                    info.extension.copy(isEnabled = isEnabled)
                }
                val enabled = parsedList.filter { it.isEnabled }
                val disabled = parsedList.filter { !it.isEnabled }

                // Do not apply stale data captured before a newer install/uninstall mutation.
                if (revisionAtStart != uiRevision.get()) return@launch
                _uiState.update {
                    it.copy(
                        installedExtensions = parsedList,
                        enabledExtensions = enabled,
                        disabledExtensions = disabled
                    )
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun openManagerUi() {
        refreshExtensions()
        _uiState.update { it.copy(showManagerOverlay = true) }
    }

    fun closeManagerUi() {
        _uiState.update { it.copy(showManagerOverlay = false) }
    }

    fun openActiveHub() {
        refreshExtensions()
        _uiState.update { it.copy(showActiveHubDialog = true) }
    }

    fun closeActiveHub() {
        _uiState.update { it.copy(showActiveHubDialog = false) }
    }

    fun getActiveTabId(): String? = try {
        extensionManager.engine.activeTabManager.getActiveTabId()
    } catch (_: Throwable) {
        null
    }

    fun openPopup(extensionId: String, tabId: String? = null) {
        val ext = _uiState.value.installedExtensions.find { it.id == extensionId }
            ?: extensionManager.engine.registry.getExtension(extensionId)

        if (ext != null) {
            val resolvedTabId = tabId ?: getActiveTabId()
            val surface = ExtensionSurfaceResolver.resolveActionSurface(context, ext, resolvedTabId)
            when (surface.surfaceType) {
                ExtensionSurfaceType.ACTION_POPUP -> {
                    _uiState.update {
                        it.copy(
                            selectedExtension = ext,
                            activePopupExtension = ext,
                            activePopupUrl = surface.fullUrl,
                            showPopupBottomSheet = true
                        )
                    }
                }
                ExtensionSurfaceType.ACTION_ONLY -> {
                    // Chrome's sidePanel.openPanelOnActionClick behavior takes precedence over
                    // the normal action click event when the extension explicitly enables it.
                    val sender = ExtensionSender(
                        extensionId = ext.id,
                        tabId = resolvedTabId,
                        contextType = ExtensionContextType.EXTENSION_PAGE
                    )
                    val behavior = try {
                        extensionManager.engine.sidePanelAdapter.getPanelBehavior(sender)
                    } catch (_: Throwable) {
                        org.json.JSONObject()
                    }
                    if (behavior.optBoolean("openPanelOnActionClick", false)) {
                        openSidePanel(ext.id, resolvedTabId)
                    } else {
                        dispatchActionClicked(ext.id, resolvedTabId)
                    }
                }
                else -> {
                    android.util.Log.d("EXT_SURFACE", "[EXT_SURFACE] No action popup or click surface for extensionId=$extensionId")
                }
            }
        }
    }

    fun openSidePanel(extensionId: String, tabId: String? = null) {
        val ext = _uiState.value.installedExtensions.find { it.id == extensionId }
            ?: extensionManager.engine.registry.getExtension(extensionId) ?: return

        val resolvedTabId = tabId ?: getActiveTabId()
        val surface = ExtensionSurfaceResolver.resolveSidePanelSurface(context, ext, resolvedTabId)
        if (surface.surfaceType == ExtensionSurfaceType.SIDE_PANEL) {
            _uiState.update {
                it.copy(
                    selectedExtension = ext,
                    activeSidePanelExtension = ext,
                    activeSidePanelUrl = surface.fullUrl,
                    activeSidePanelTabId = resolvedTabId,
                    showSidePanel = true
                )
            }
        } else {
            android.util.Log.w("EXT_SURFACE", "[EXT_SURFACE] Cannot open side panel surfaceType=${surface.surfaceType} extensionId=$extensionId")
        }
    }

    fun dispatchActionClicked(extensionId: String, tabId: String? = null) {
        try {
            val ext = extensionManager.engine.registry.getExtension(extensionId)
            val tabData = org.json.JSONObject().apply {
                if (!tabId.isNullOrBlank()) {
                    val numericId = TabIdMapper.getIntId(tabId)
                    put("id", numericId)
                }
                put("active", true)
            }
            val data = org.json.JSONObject().apply {
                put("tab", tabData)
            }

            val actionName = when {
                ext?.actionSpec?.actionType == "browser_action" -> "browserAction.onClicked"
                ext?.actionSpec?.actionType == "page_action" -> "pageAction.onClicked"
                ext?.manifestVersion == 2 -> "browserAction.onClicked"
                else -> "action.onClicked"
            }

            extensionManager.engine.eventManager.triggerEventForExtension(extensionId, actionName, data)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun openSidePanelUrl(extensionId: String): String? {
        val ext = _uiState.value.installedExtensions.find { it.id == extensionId }
            ?: extensionManager.engine.registry.getExtension(extensionId) ?: return null
        val surface = ExtensionSurfaceResolver.resolveSidePanelSurface(context, ext)
        return if (surface.surfaceType == ExtensionSurfaceType.SIDE_PANEL) surface.fullUrl else null
    }

    fun openOptionsPageUrl(extensionId: String): String? {
        val ext = _uiState.value.installedExtensions.find { it.id == extensionId }
            ?: extensionManager.engine.registry.getExtension(extensionId) ?: return null
        val surface = ExtensionSurfaceResolver.resolveOptionsSurface(context, ext)
        return if (surface.surfaceType == ExtensionSurfaceType.OPTIONS_PAGE) surface.fullUrl else null
    }

    fun getResolvedSurfaces(extensionId: String): List<ResolvedExtensionSurface> {
        val ext = _uiState.value.installedExtensions.find { it.id == extensionId }
            ?: extensionManager.engine.registry.getExtension(extensionId) ?: return emptyList()
        return ExtensionSurfaceResolver.resolveAllSurfaces(context, ext)
    }

    /**
     * Intelligently inspects the extension's declared manifest surfaces and launches the best surface.
     * Follows Chrome action priority:
     * 1. If Action Popup exists -> Opens Action Popup Bottom Sheet.
     * 2. If openPanelOnActionClick is enabled & Side Panel exists -> Opens Mobile Side Panel.
     * 3. Otherwise -> Dispatches action.onClicked event.
     * 4. Fallback -> Opens Options Settings page or Extension Detail Dialog.
     */
    fun launchExtension(extensionId: String, tabId: String? = null) {
        val ext = _uiState.value.installedExtensions.find { it.id == extensionId }
            ?: extensionManager.engine.registry.getExtension(extensionId) ?: return

        val resolvedTabId = tabId ?: getActiveTabId()

        // 1. Check Action Popup
        val actionSurface = ExtensionSurfaceResolver.resolveActionSurface(context, ext, resolvedTabId)
        if (actionSurface.surfaceType == ExtensionSurfaceType.ACTION_POPUP) {
            openPopup(extensionId, resolvedTabId)
            return
        }

        // 2. Check if Side Panel should open on action click (openPanelOnActionClick == true)
        val sidePanelSurface = ExtensionSurfaceResolver.resolveSidePanelSurface(context, ext, resolvedTabId)
        val sender = ExtensionSender(
            extensionId = ext.id,
            tabId = resolvedTabId,
            contextType = ExtensionContextType.EXTENSION_PAGE
        )
        val behavior = try {
            extensionManager.engine.sidePanelAdapter.getPanelBehavior(sender)
        } catch (_: Throwable) {
            org.json.JSONObject()
        }
        val openPanelOnActionClick = behavior.optBoolean("openPanelOnActionClick", false)
        if (openPanelOnActionClick && sidePanelSurface.surfaceType == ExtensionSurfaceType.SIDE_PANEL) {
            openSidePanel(extensionId, resolvedTabId)
            return
        }

        // 3. Dispatch action.onClicked event
        if (actionSurface.surfaceType == ExtensionSurfaceType.ACTION_ONLY || ext.actionSpec.hasAction || ext.manifestVersion >= 3) {
            dispatchActionClicked(ext.id, resolvedTabId)
            return
        }

        // 4. Check Options Page
        val optionsSurface = ExtensionSurfaceResolver.resolveOptionsSurface(context, ext)
        if (optionsSurface.surfaceType == ExtensionSurfaceType.OPTIONS_PAGE) {
            openOptionsPage(extensionId)
            return
        }

        // 5. Fallback: Open Extension Detail Dialog
        openDetailDialog(extensionId)
    }

    fun closePopup() {
        _uiState.update {
            it.copy(
                activePopupExtension = null,
                activePopupUrl = null,
                showPopupBottomSheet = false
            )
        }
    }

    fun closeSidePanel() {
        _uiState.update {
            it.copy(
                activeSidePanelExtension = null,
                activeSidePanelUrl = null,
                activeSidePanelTabId = null,
                showSidePanel = false
            )
        }
    }


    fun openExtensionPage(extensionId: String, pageUrl: String) {
        val ext = _uiState.value.installedExtensions.find { it.id == extensionId }
            ?: extensionManager.engine.registry.getExtension(extensionId) ?: return
        _uiState.update {
            it.copy(
                selectedExtension = ext,
                activeExtensionPageExtension = ext,
                activeExtensionPageUrl = pageUrl,
                showExtensionPage = true
            )
        }
    }

    fun openOptionsPage(extensionId: String) {
        val url = openOptionsPageUrl(extensionId) ?: return
        openExtensionPage(extensionId, url)
    }

    fun closeExtensionPage() {
        _uiState.update {
            it.copy(
                showExtensionPage = false,
                activeExtensionPageExtension = null,
                activeExtensionPageUrl = null
            )
        }
    }

    fun openStoreScreen() {
        _uiState.update { it.copy(showStoreScreen = true) }
    }

    fun closeStoreScreen() {
        _uiState.update { it.copy(showStoreScreen = false) }
    }

    fun openAnalyzerScreen() {
        _uiState.update { it.copy(showAnalyzerScreen = true) }
    }

    fun closeAnalyzerScreen() {
        _uiState.update { it.copy(showAnalyzerScreen = false) }
    }

    fun openDetailDialog(extensionId: String? = null) {
        val ext = if (extensionId != null) {
            _uiState.value.installedExtensions.find { it.id == extensionId }
        } else {
            _uiState.value.installedExtensions.firstOrNull()
        }
        _uiState.update { it.copy(selectedExtension = ext, showDetailDialog = true) }
    }

    fun closeDetailDialog() {
        _uiState.update { it.copy(showDetailDialog = false) }
    }

    fun openSettingsDialog(extensionId: String? = null) {
        _uiState.update { it.copy(selectedSettingsExtensionId = extensionId, showSettingsDialog = true) }
    }

    fun closeSettingsDialog() {
        _uiState.update { it.copy(showSettingsDialog = false, selectedSettingsExtensionId = null) }
    }

    fun openDeepAnalyzerDialog(extensionId: String? = null) {
        _uiState.update { it.copy(selectedAnalyzerExtensionId = extensionId, showDeepAnalyzerDialog = true) }
    }

    fun closeDeepAnalyzerDialog() {
        _uiState.update { it.copy(showDeepAnalyzerDialog = false, selectedAnalyzerExtensionId = null) }
    }

    fun setDeveloperModeEnabled(enabled: Boolean) {
        _uiState.update { it.copy(isDeveloperModeEnabled = enabled) }
        if (!enabled) {
            _uiState.update { it.copy(showDeveloperConsole = false) }
        }
    }

    fun openDeveloperConsole() {
        if (!_uiState.value.isDeveloperModeEnabled) {
            _uiState.update { it.copy(errorMessage = "Enable Extension Developer Mode in Settings -> Developer Options first.") }
            return
        }
        _uiState.update { it.copy(showDeveloperConsole = true) }
    }

    fun closeDeveloperConsole() {
        _uiState.update { it.copy(showDeveloperConsole = false) }
    }

    fun openZipInstaller() {
        _uiState.update { it.copy(showZipInstaller = true) }
    }

    fun closeZipInstaller() {
        _uiState.update { it.copy(showZipInstaller = false) }
    }

    fun setEnabled(extensionId: String, enabled: Boolean) {
        ioScope.launch {
            try {
                extensionManager.toggleExtension(extensionId, enabled)
                
                // Dark Reader special case handling (Rule 11)
                if (extensionId.contains("dark_reader", ignoreCase = true)) {
                    applyDarkReaderSpecialHandling(enabled)
                }

                refreshExtensions()
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "Failed to toggle extension: ${e.localizedMessage}") }
            }
        }
    }

    private fun applyDarkReaderSpecialHandling(enabled: Boolean) {
        // Persist dark reader filter flag for content scripts
        prefs.edit().putBoolean("dark_reader_active", enabled).apply()
    }

    private fun publishInstalledExtension(parsed: ParsedExtension) {
        _uiState.update { current ->
            val merged = (current.installedExtensions.filterNot { it.id == parsed.id } + parsed)
                .sortedBy { it.name.lowercase() }
            val enabled = merged.filter { it.isEnabled }
            val disabled = merged.filterNot { it.isEnabled }
            current.copy(
                installedExtensions = merged,
                enabledExtensions = enabled,
                disabledExtensions = disabled,
                selectedExtension = if (current.selectedExtension?.id == parsed.id) parsed else current.selectedExtension
            )
        }
    }

    fun installFromZip(uri: Uri) {
        _uiState.update { it.copy(isInstalling = true, installProgressMessage = "Validating and extracting extension ZIP...") }
        ioScope.launch {
            try {
                val parsed = extensionManager.installExtension(uri)
                uiRevision.incrementAndGet()
                publishInstalledExtension(parsed)
                _uiState.update {
                    it.copy(
                        isInstalling = false,
                        installStage = InstallStage.INSTALLED,
                        installProgressMessage = null,
                        errorMessage = null
                    )
                }
            } catch (e: Exception) {
                android.util.Log.e("ExtensionEngineApi", "Failed to install extension from ZIP: $uri", e)
                _uiState.update {
                    it.copy(
                        isInstalling = false,
                        installProgressMessage = null,
                        errorMessage = "ZIP Install Error: ${e.localizedMessage ?: e.message ?: e.javaClass.simpleName}"
                    )
                }
            }
        }
    }

    fun uninstallExtension(extensionId: String) {
        ioScope.launch {
            try {
                extensionManager.uninstallExtension(extensionId)
                SwiftExtensionPermissionEngine.resetExtensionPermissions(context, extensionId)
                uiRevision.incrementAndGet()
                refreshExtensions()
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "Uninstall Error: ${e.localizedMessage}") }
            }
        }
    }

    fun exportExtension(extensionId: String, onResult: ((Boolean, String) -> Unit)? = null) {
        ExtensionWebStoreManager.exportExtension(
            scope = ioScope,
            context = context,
            extensionId = extensionId,
            extensionManager = extensionManager
        ) { success, msg ->
            if (onResult != null) {
                onResult(success, msg)
            } else if (!success) {
                _uiState.update { it.copy(errorMessage = msg) }
            }
        }
    }

    fun shareExtension(extensionId: String) {
        ExtensionWebStoreManager.shareExtension(
            scope = ioScope,
            context = context,
            extensionId = extensionId,
            extensionManager = extensionManager
        )
    }

    fun searchStore(query: String, apiKey: String = "") {
        _uiState.update { it.copy(storeQuery = query, isStoreLoading = true) }
        ExtensionWebStoreManager.searchChromeWebStore(
            scope = ioScope,
            query = query,
            apiKey = apiKey,
            okHttpClient = okHttpClient
        ) { results ->
            _uiState.update { it.copy(storeResults = results, isStoreLoading = false) }
        }
    }

    fun promptWebStoreInstall(extensionId: String, name: String, iconUrl: String? = null) {
        val prompt = WebStoreInstallPrompt(
            extensionId = extensionId,
            name = name,
            iconUrl = iconUrl,
            status = "READY"
        )
        _uiState.update {
            it.copy(
                showWebStoreInstallDialog = true,
                webStoreInstallPrompt = prompt,
                downloadProgressPercent = null,
                errorMessage = null
            )
        }
    }

    fun confirmWebStoreInstall(extensionId: String) {
        val currentPrompt = _uiState.value.webStoreInstallPrompt ?: WebStoreInstallPrompt(extensionId, "Extension")
        _uiState.update {
            it.copy(
                isInstalling = true,
                installStage = com.swift.browser.extensionengine.ui.InstallStage.CONNECTING,
                installProgressMessage = "Connecting to Chrome Web Store...",
                downloadProgressPercent = 0,
                stageProgressPercent = 2,
                webStoreInstallPrompt = currentPrompt.copy(status = "DOWNLOADING", stage = com.swift.browser.extensionengine.ui.InstallStage.CONNECTING, stageMessage = "Connecting..."),
                errorMessage = null
            )
        }
        ExtensionWebStoreManager.downloadChromeExtension(
            scope = ioScope,
            context = context,
            extensionId = extensionId,
            okHttpClient = okHttpClient,
            extensionManager = extensionManager,
            onStageChanged = { stage, message, progressPercent ->
                _uiState.update {
                    it.copy(
                        installStage = stage,
                        installProgressMessage = message,
                        stageProgressPercent = progressPercent,
                        webStoreInstallPrompt = currentPrompt.copy(
                            status = when (stage) {
                                com.swift.browser.extensionengine.ui.InstallStage.INSTALLED -> "SUCCESS"
                                com.swift.browser.extensionengine.ui.InstallStage.FAILED -> "ERROR"
                                else -> "DOWNLOADING"
                            },
                            stage = stage,
                            stageMessage = message
                        )
                    )
                }
            },
            onProgress = { percent, bytesRead, totalBytes ->
                val progressText = if (totalBytes > 0) {
                    val readMb = String.format(java.util.Locale.US, "%.1f", bytesRead / (1024f * 1024f))
                    val totalMb = String.format(java.util.Locale.US, "%.1f", totalBytes / (1024f * 1024f))
                    "Downloading: $percent% ($readMb / $totalMb MB)"
                } else {
                    "Downloading: $percent%"
                }
                _uiState.update {
                    it.copy(
                        downloadProgressPercent = percent,
                        downloadBytesText = progressText
                    )
                }
            },
            onInstalled = {
                refreshExtensions()
                _uiState.update {
                    it.copy(
                        isInstalling = false,
                        installStage = com.swift.browser.extensionengine.ui.InstallStage.INSTALLED,
                        downloadProgressPercent = 100,
                        stageProgressPercent = 100,
                        installProgressMessage = "Installed!",
                        webStoreInstallPrompt = currentPrompt.copy(status = "SUCCESS", stage = com.swift.browser.extensionengine.ui.InstallStage.INSTALLED, stageMessage = "Installed!")
                    )
                }
            },
            onResult = { success, msg ->
                _uiState.update {
                    it.copy(
                        isInstalling = false,
                        errorMessage = if (success) null else msg,
                        webStoreInstallPrompt = if (success) {
                            currentPrompt.copy(status = "SUCCESS", stage = com.swift.browser.extensionengine.ui.InstallStage.INSTALLED)
                        } else {
                            currentPrompt.copy(status = "ERROR", stage = com.swift.browser.extensionengine.ui.InstallStage.FAILED, stageMessage = msg)
                        }
                    )
                }
            }
        )
    }

    fun cancelWebStoreInstall() {
        _uiState.update {
            it.copy(
                showWebStoreInstallDialog = false,
                webStoreInstallPrompt = null,
                downloadProgressPercent = null,
                downloadBytesText = null,
                isInstalling = false,
                installProgressMessage = null
            )
        }
    }

    fun installFromStore(extensionId: String) {
        promptWebStoreInstall(extensionId, "Chrome Extension")
        confirmWebStoreInstall(extensionId)
    }

    fun getLocalFallbackExtensions(): List<ExtensionMeta> {
        return ExtensionWebStoreManager.getLocalFallbackExtensions()
    }

    fun loadCustomScript() {
        val script = prefs.getString("custom_extension_script", "") ?: ""
        val enabled = prefs.getBoolean("custom_extension_script_enabled", false)
        _uiState.update { it.copy(customScript = script, isCustomScriptEnabled = enabled) }
    }

    fun setCustomExtensionScript(script: String, enabled: Boolean) {
        prefs.edit()
            .putString("custom_extension_script", script)
            .putBoolean("custom_extension_script_enabled", enabled)
            .apply()
        _uiState.update { it.copy(customScript = script, isCustomScriptEnabled = enabled) }
    }

    fun getCustomExtensionScript(): String {
        return if (_uiState.value.isCustomScriptEnabled) _uiState.value.customScript else ""
    }

    fun checkChromeWebStorePage(url: String): String? {
        if (url.isBlank()) return null
        return ExtensionWebStoreManager.extractExtensionId(url)
    }

    fun setupWebView(webView: WebView, tabId: String? = null) {
        extensionManager.setupWebView(webView, tabId)
    }

    /** Binds an extension surface with its explicit private/incognito context. */
    fun setupWebView(
        webView: WebView,
        tabId: String?,
        isPrivate: Boolean,
        privateSessionId: String? = null
    ) {
        extensionManager.setupWebView(webView, tabId, isPrivate, privateSessionId)
    }

    /** Returns the compiled extension bootstrap for the given extension page context. */
    fun compileBootstrapScript(extensionId: String): String =
        extensionManager.engine.compileBootstrapScript(extensionId)

    fun notifyTabUpdated(tabId: String, url: String, status: String = "complete") {
        try {
            val intTabId = TabIdMapper.getIntId(tabId)
            val data = org.json.JSONObject().apply {
                put("tabId", intTabId)
                put("changeInfo", org.json.JSONObject().apply {
                    put("status", status)
                    put("url", url)
                })
                put("tab", org.json.JSONObject().apply {
                    put("id", intTabId)
                    put("url", url)
                    put("status", status)
                })
            }
            extensionManager.engine.eventManager.triggerEvent("tabs.onUpdated", data)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun notifyTabActivated(tabId: String) {
        try {
            val intTabId = TabIdMapper.getIntId(tabId)
            val data = org.json.JSONObject().apply {
                put("activeInfo", org.json.JSONObject().apply {
                    put("tabId", intTabId)
                })
            }
            extensionManager.engine.eventManager.triggerEvent("tabs.onActivated", data)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun notifyTabCreated(tabId: String, url: String = "") {
        try {
            val intTabId = TabIdMapper.getIntId(tabId)
            val data = org.json.JSONObject().apply {
                put("tab", org.json.JSONObject().apply {
                    put("id", intTabId)
                    put("url", url)
                    put("active", true)
                })
            }
            extensionManager.engine.eventManager.triggerEvent("tabs.onCreated", data)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /** Called before TabEngine removes a tab so chrome.sessions can persist a recoverable entry. */
    fun notifyTabWillRemove(tabId: String) {
        try {
            extensionManager.engine.sessionsAdapter.recordTabBeforeRemoval(tabId)
        } catch (_: Throwable) {
            // Session journaling must never block tab destruction.
        }
    }

    fun notifyTabRemoved(tabId: String) {
        try {
            val intTabId = TabIdMapper.getIntId(tabId)
            val data = org.json.JSONObject().apply {
                put("tabId", intTabId)
                put("removeInfo", org.json.JSONObject().apply {
                    put("isWindowClosing", false)
                })
            }
            extensionManager.engine.eventManager.triggerEvent("tabs.onRemoved", data)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun onPageStarted(
        webView: WebView,
        url: String,
        tabId: String? = null,
        isPrivate: Boolean = false
    ) {
        extensionManager.engine.contentScriptManager.onPageStarted(webView, url)
        val resolvedTabId = tabId ?: getActiveTabId() ?: ""
        (webView.tag as? RuntimeBridge)?.failPendingForDocument(resolvedTabId, 0, null)
        notifyTabUpdated(tabId = resolvedTabId, url = url, status = "loading")
        val engineImpl = extensionManager.engine as? ExtensionEngineImpl
        engineImpl?.webNavigationAdapter?.notifyBeforeNavigate(resolvedTabId, url, isPrivate)
    }

    fun onPageCommitVisible(
        webView: WebView,
        url: String?,
        tabId: String? = null,
        isPrivate: Boolean = false
    ) {
        val targetUrl = url ?: webView.url ?: ""
        if (targetUrl.isBlank()) return
        val resolvedTabId = tabId ?: getActiveTabId() ?: ""
        val engineImpl = extensionManager.engine as? ExtensionEngineImpl
        engineImpl?.webNavigationAdapter?.notifyCommitted(resolvedTabId, targetUrl, isPrivate)
        engineImpl?.webNavigationAdapter?.notifyDOMContentLoaded(resolvedTabId, targetUrl, isPrivate)
    }

    fun onPageFinished(
        webView: WebView,
        url: String?,
        tabId: String? = null,
        isPrivate: Boolean = false
    ) {
        val targetUrl = url ?: webView.url ?: ""
        if (targetUrl.isBlank()) return
        val resolvedTabId = tabId ?: getActiveTabId() ?: ""
        notifyTabUpdated(tabId = resolvedTabId, url = targetUrl, status = "complete")
        val engineImpl = extensionManager.engine as? ExtensionEngineImpl
        engineImpl?.webNavigationAdapter?.notifyCompleted(resolvedTabId, targetUrl, isPrivate)
    }

    fun onReceivedError(
        webView: WebView?,
        url: String?,
        errorCode: Int = -1,
        description: String = "net::ERR_FAILED",
        tabId: String? = null,
        isPrivate: Boolean = false
    ) {
        val targetUrl = url ?: webView?.url ?: ""
        val resolvedTabId = tabId ?: getActiveTabId() ?: ""
        val engineImpl = extensionManager.engine as? ExtensionEngineImpl
        engineImpl?.webNavigationAdapter?.notifyErrorOccurred(resolvedTabId, targetUrl, description, isPrivate)
        notifyTabUpdated(tabId = resolvedTabId, url = targetUrl, status = "complete")
    }

    fun onReceivedHttpError(
        webView: WebView?,
        url: String?,
        statusCode: Int = 404,
        reasonPhrase: String = "Not Found",
        tabId: String? = null,
        isPrivate: Boolean = false
    ) {
        val targetUrl = url ?: webView?.url ?: ""
        val resolvedTabId = tabId ?: getActiveTabId() ?: ""
        val engineImpl = extensionManager.engine as? ExtensionEngineImpl
        engineImpl?.webNavigationAdapter?.notifyErrorOccurred(resolvedTabId, targetUrl, "HTTP $statusCode: $reasonPhrase", isPrivate)
    }

    fun doUpdateVisitedHistory(
        webView: WebView?,
        url: String?,
        isReload: Boolean,
        tabId: String? = null,
        isPrivate: Boolean = false
    ) {
        val targetUrl = url ?: webView?.url ?: ""
        if (targetUrl.isBlank()) return
        val resolvedTabId = tabId ?: getActiveTabId() ?: ""
        val engineImpl = extensionManager.engine as? ExtensionEngineImpl
        if (targetUrl.contains("#")) {
            engineImpl?.webNavigationAdapter?.notifyReferenceFragmentUpdated(resolvedTabId, targetUrl, isPrivate)
        } else {
            engineImpl?.webNavigationAdapter?.notifyHistoryStateUpdated(resolvedTabId, targetUrl, isPrivate)
        }
    }

    fun onCreatedNavigationTarget(
        sourceTabId: String,
        newTabId: String,
        url: String,
        isPrivate: Boolean = false
    ) {
        val engineImpl = extensionManager.engine as? ExtensionEngineImpl
        engineImpl?.webNavigationAdapter?.notifyCreatedNavigationTarget(sourceTabId, newTabId, url, isPrivate)
    }

    fun injectContentScripts(
        webView: WebView,
        url: String,
        runAt: String? = null,
        isPrivate: Boolean = false,
        privateSessionId: String? = null
    ) {
        extensionManager.injectContentScripts(webView, url, runAt, isPrivate, privateSessionId)
        // Inject custom user script if enabled
        val customScript = getCustomExtensionScript()
        if (customScript.isNotBlank()) {
            webView.post {
                webView.evaluateJavascript("(function(){ $customScript })();", null)
            }
        }
    }

    fun getPermissionDecision(extId: String, permission: String): String {
        return SwiftExtensionPermissionEngine.getPermissionDecision(context, extId, permission)
    }

    fun setPermissionDecision(extId: String, permission: String, decision: String) {
        SwiftExtensionPermissionEngine.setPermissionDecision(context, extId, permission, decision)
        refreshExtensions()
    }

    fun getHostDecision(extId: String, hostPattern: String): String {
        return SwiftExtensionPermissionEngine.getHostDecision(context, extId, hostPattern)
    }

    fun setHostDecision(extId: String, hostPattern: String, decision: String) {
        SwiftExtensionPermissionEngine.setHostDecision(context, extId, hostPattern, decision)
        refreshExtensions()
    }

    fun hasAndroidPermission(permission: String): Boolean {
        return com.swift.browser.permissionengine.AndroidRuntimePermissionManager.hasPermission(context, permission)
    }

    fun getPermissionInfo(permission: String): ExtensionPermissionInfo? {
        return SwiftExtensionPermissionEngine.permissionsCatalog[permission]
    }

    fun confirmInstall(extensionId: String, selectedPermissions: List<String> = emptyList(), selectedHostPermissions: List<String> = emptyList()) {
        ioScope.launch {
            try {
                selectedPermissions.forEach { perm ->
                    SwiftExtensionPermissionEngine.setPermissionDecision(context, extensionId, perm, "ALLOW_ALWAYS")
                }
                selectedHostPermissions.forEach { host ->
                    SwiftExtensionPermissionEngine.setHostDecision(context, extensionId, host, "ALLOW_ALWAYS")
                }
                extensionManager.toggleExtension(extensionId, true)
                refreshExtensions()
                _uiState.update { it.copy(showDetailDialog = false) }
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "Install Approval Error: ${e.localizedMessage}") }
            }
        }
    }

    fun dismissError() {

        _uiState.update { it.copy(errorMessage = null) }
    }

    fun getEnabledExtensions(): List<ParsedExtension> {
        return _uiState.value.enabledExtensions
    }

    val omniboxSuggestionsVersion: kotlinx.coroutines.flow.StateFlow<Int>
        get() = ExtensionOmniboxAdapter.suggestionsVersion

    fun getExtensionIconFile(ext: ParsedExtension, tabId: String? = null): java.io.File? {
        val actionState = getActionState(ext.id, tabId)
        val installDir = java.io.File(ext.installPath)

        // 1. Dynamic action icon
        if (actionState.icon.isNotBlank()) {
            val f = java.io.File(installDir, actionState.icon)
            if (f.exists() && f.isFile) return f
            val fDirect = java.io.File(actionState.icon)
            if (fDirect.exists() && fDirect.isFile) return fDirect
        }

        // 2. ActionSpec defaultIconMap
        val iconMap = ext.actionSpec.defaultIconMap
        if (iconMap.isNotEmpty()) {
            val candidatePath = iconMap["38"] ?: iconMap["32"] ?: iconMap["48"] ?: iconMap["128"] ?: iconMap["16"] ?: iconMap.values.firstOrNull()
            if (!candidatePath.isNullOrBlank()) {
                val f = java.io.File(installDir, candidatePath)
                if (f.exists() && f.isFile) return f
                val fDirect = java.io.File(candidatePath)
                if (fDirect.exists() && fDirect.isFile) return fDirect
            }
        }

        // 3. ext.iconPath
        if (ext.iconPath.isNotBlank()) {
            val f = java.io.File(installDir, ext.iconPath)
            if (f.exists() && f.isFile) return f
            val fDirect = java.io.File(ext.iconPath)
            if (fDirect.exists() && fDirect.isFile) return fDirect
        }

        return null
    }

    fun getActionState(extensionId: String, tabId: String? = null): ExtensionActionState {
        val key = if (!tabId.isNullOrBlank()) "${extensionId}_$tabId" else null
        if (key != null && ExtensionActionAdapter.tabActionStates.containsKey(key)) {
            return ExtensionActionAdapter.tabActionStates[key]!!
        }
        return ExtensionActionAdapter.globalActionStates[extensionId] ?: ExtensionActionState(extensionId)
    }

    fun triggerContextMenuClick(
        extensionId: String,
        menuItemId: String,
        pageUrl: String,
        tabId: String = "",
        selectionText: String? = null,
        linkUrl: String? = null,
        srcUrl: String? = null,
        frameUrl: String? = null,
        isEditable: Boolean = false,
        mediaType: String? = null,
        frameId: Int = 0,
        documentId: String? = null
    ) {
        try {
            extensionManager.engine.contextMenusAdapter.triggerClick(
                extensionId = extensionId,
                menuItemId = menuItemId,
                pageUrl = pageUrl,
                tabId = tabId,
                selectionText = selectionText,
                linkUrl = linkUrl,
                srcUrl = srcUrl,
                frameUrl = frameUrl,
                isEditable = isEditable,
                mediaType = mediaType,
                frameId = frameId,
                documentId = documentId
            )
        } catch (e: Exception) {
            android.util.Log.e("ExtensionEngineApi", "Failed to trigger context menu click", e)
        }
    }

    fun findOmniboxKeywordOwner(keyword: String): String? {
        val cleanKw = keyword.lowercase().trim()
        val directMatch = ExtensionOmniboxAdapter.registeredKeywords[cleanKw]
        if (directMatch != null) return directMatch

        for (ext in _uiState.value.enabledExtensions) {
            try {
                val manifest = org.json.JSONObject(ext.manifestJson)
                val omnibox = manifest.optJSONObject("omnibox")
                val kw = omnibox?.optString("keyword")?.lowercase()?.trim()
                if (kw == cleanKw) {
                    ExtensionOmniboxAdapter.registeredKeywords[cleanKw] = ext.id
                    return ext.id
                }
            } catch (_: Exception) {}
        }
        return null
    }

    fun getOmniboxDefaultSuggestion(extensionId: String): String? {
        return ExtensionOmniboxAdapter.defaultSuggestions[extensionId]
    }

    fun getOmniboxSuggestions(keyword: String, text: String): List<OmniboxSuggestion> {
        return ExtensionOmniboxAdapter.getSuggestionsForKeyword(keyword, text)
    }

    fun triggerOmniboxInputStarted(extensionId: String) {
        try {
            extensionManager.engine.omniboxAdapter.triggerInputStarted(extensionId)
        } catch (e: Exception) {
            android.util.Log.e("ExtensionEngineApi", "Failed to trigger omnibox inputStarted", e)
        }
    }

    fun triggerOmniboxInputChanged(extensionId: String, text: String) {
        try {
            extensionManager.engine.omniboxAdapter.triggerInputChanged(extensionId, text)
        } catch (e: Exception) {
            android.util.Log.e("ExtensionEngineApi", "Failed to trigger omnibox inputChanged", e)
        }
    }

    fun triggerOmniboxInputEntered(extensionId: String, text: String, disposition: String = "currentTab") {
        try {
            extensionManager.engine.omniboxAdapter.triggerInputEntered(extensionId, text, disposition)
        } catch (e: Exception) {
            android.util.Log.e("ExtensionEngineApi", "Failed to trigger omnibox inputEntered", e)
        }
    }

    fun triggerOmniboxEntered(extensionId: String, text: String, disposition: String = "currentTab") {
        triggerOmniboxInputEntered(extensionId, text, disposition)
    }

    fun deleteOmniboxSuggestion(extensionId: String, text: String) {
        try {
            val sender = ExtensionSender(extensionId = extensionId)
            val details = org.json.JSONObject().put("text", text)
            extensionManager.engine.omniboxAdapter.deleteSuggestion(sender, details)
        } catch (e: Exception) {
            android.util.Log.e("ExtensionEngineApi", "Failed to delete omnibox suggestion", e)
        }
    }

    fun triggerOmniboxInputCancelled(extensionId: String) {
        try {
            extensionManager.engine.omniboxAdapter.triggerInputCancelled(extensionId)
        } catch (e: Exception) {
            android.util.Log.e("ExtensionEngineApi", "Failed to trigger omnibox inputCancelled", e)
        }
    }

    companion object {
        @Volatile
        private var instance: ExtensionEngineApi? = null

        fun getInstance(context: Context): ExtensionEngineApi {
            return instance ?: synchronized(this) {
                instance ?: ExtensionEngineApi(context.applicationContext).also { instance = it }
            }
        }
    }
}
