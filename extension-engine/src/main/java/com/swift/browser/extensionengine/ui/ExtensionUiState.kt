package com.swift.browser.extensionengine.ui

import com.swift.browser.extensionengine.ExtensionMeta
import com.swift.browser.extensionengine.ParsedExtension
import com.swift.browser.extensionengine.PendingExtensionPermissionRequest

enum class InstallStage {
    IDLE,
    CONNECTING,
    DOWNLOADING,
    DOWNLOAD_COMPLETE,
    VERIFYING,
    EXTRACTING,
    PARSING,
    INSTALLING,
    REGISTERING_WORKER,
    STARTING_WORKER,
    INSTALLED,
    FAILED
}

data class WebStoreInstallPrompt(
    val extensionId: String,
    val name: String,
    val iconUrl: String? = null,
    val permissions: List<String> = emptyList(),
    val status: String = "READY",
    val stage: InstallStage = InstallStage.IDLE,
    val stageMessage: String? = null
)

data class ExtensionUiState(
    val installedExtensions: List<ParsedExtension> = emptyList(),
    val enabledExtensions: List<ParsedExtension> = emptyList(),
    val disabledExtensions: List<ParsedExtension> = emptyList(),
    val selectedExtension: ParsedExtension? = null,
    val activePopupExtension: ParsedExtension? = null,
    val activePopupUrl: String? = null,
    val pendingPermissionRequest: PendingExtensionPermissionRequest? = null,
    val isInstalling: Boolean = false,
    val installStage: InstallStage = InstallStage.IDLE,
    val installProgressMessage: String? = null,
    val downloadProgressPercent: Int? = null,
    val stageProgressPercent: Int? = null,
    val downloadBytesText: String? = null,
    val showWebStoreInstallDialog: Boolean = false,
    val webStoreInstallPrompt: WebStoreInstallPrompt? = null,
    val storeQuery: String = "",
    val storeResults: List<ExtensionMeta> = emptyList(),
    val isStoreLoading: Boolean = false,
    val errorMessage: String? = null,
    val showManagerOverlay: Boolean = false,
    val showActiveHubDialog: Boolean = false,
    val showPopupBottomSheet: Boolean = false,
    val showSidePanel: Boolean = false,
    val activeSidePanelExtension: ParsedExtension? = null,
    val activeSidePanelUrl: String? = null,
    val activeSidePanelTabId: String? = null,
    val showExtensionPage: Boolean = false,
    val activeExtensionPageExtension: ParsedExtension? = null,
    val activeExtensionPageUrl: String? = null,
    val showStoreScreen: Boolean = false,
    val showPermissionDialog: Boolean = false,
    val showAnalyzerScreen: Boolean = false,
    val showDetailDialog: Boolean = false,
    val showSettingsDialog: Boolean = false,
    val selectedSettingsExtensionId: String? = null,
    val showDeepAnalyzerDialog: Boolean = false,
    val selectedAnalyzerExtensionId: String? = null,
    val showDeveloperConsole: Boolean = false,
    val showZipInstaller: Boolean = false,
    val customScript: String = "",
    val isCustomScriptEnabled: Boolean = false,
    val isDeveloperModeEnabled: Boolean = false,
    val currentCwsExtensionId: String? = null
)
