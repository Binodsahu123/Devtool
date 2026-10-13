package com.swift.browser.extensionengine

enum class CapabilityStatus {
    FULL,
    PARTIAL,
    UNSUPPORTED
}

data class ApiCapability(
    val namespace: String,
    val status: CapabilityStatus,
    val backend: String,
    val note: String
)

/**
 * Authoritative runtime capability registry. A namespace is FULL only when its
 * browser operation and Chrome-visible semantics are backed by the canonical
 * browser engine; adapters, parsers, or simulated state alone are PARTIAL.
 */
object ExtensionCapabilityMatrix {
    val capabilities: Map<String, ApiCapability> = mapOf(
        "runtime" to ApiCapability("runtime", CapabilityStatus.FULL, "ExtensionRuntimeEngine", "Manifest V2/V3 messaging, connections, and events"),
        "storage" to ApiCapability("storage", CapabilityStatus.PARTIAL, "StorageManager + ExtensionStorageSyncJournal + StorageSyncTransport", "Local/managed/session persistence plus durable sync journal and quota policy are implemented; a real external account sync provider must be supplied through StorageSyncTransport"),
        "tabs" to ApiCapability("tabs", CapabilityStatus.PARTIAL, "TabEngineApi", "Tab creation, query, update, removal, capture, and messaging; full Chrome permission redaction and tab event parity remain partial"),
        "windows" to ApiCapability("windows", CapabilityStatus.PARTIAL, "TabEngineApi", "Window query, creation, updates, and closing; multi-window desktop parity is partial"),
        "history" to ApiCapability("history", CapabilityStatus.PARTIAL, "HistoryEngine", "History search, add, delete, and visit details backed by local database"),
        "bookmarks" to ApiCapability("bookmarks", CapabilityStatus.PARTIAL, "BookmarkEngineApi", "Bookmark creation, editing, deletion, and tree navigation"),
        "downloads" to ApiCapability("downloads", CapabilityStatus.PARTIAL, "DownloadEngine", "Download search and control backed by the download engine"),
        "cookies" to ApiCapability("cookies", CapabilityStatus.PARTIAL, "CookieEngineApi", "Cookie operations with profile/private-session routing; host permission enforcement is partial"),
        "i18n" to ApiCapability("i18n", CapabilityStatus.FULL, "ExtensionI18nAdapter", "Manifest locale message resolution"),
        "scripting" to ApiCapability("scripting", CapabilityStatus.PARTIAL, "WebViewCanonicalFrameBridge + WebViewExtensionWorldBridge", "FrameIds/documentIds/allFrames and MAIN/ISOLATED execution are provider-backed; dynamic CSS targeting uses the canonical frame transport on supported AndroidX WebView"),
        "webRequest" to ApiCapability("webRequest", CapabilityStatus.PARTIAL, "WebResourceInterceptionCoordinator + V8ServiceWorkerBackend", "Real lifecycle is wired for V8 service-worker fetch; normal page traffic remains limited by Android WebView callbacks"),
        "declarativeNetRequest" to ApiCapability("declarativeNetRequest", CapabilityStatus.PARTIAL, "ExtensionDnrAdapter + V8ServiceWorkerBackend", "Real DNR block, redirect, request-header, and response-header enforcement exists for V8 fetch; Android WebView page transactions cannot expose the same mutation hook"),
        "webNavigation" to ApiCapability("webNavigation", CapabilityStatus.PARTIAL, "ExtensionWebNavigationAdapter", "Lifecycle events are wired from browser callbacks; frame-level subnavigation hooks remain partial"),
        "permissions" to ApiCapability("permissions", CapabilityStatus.FULL, "ExtensionPermissionAdapter", "Dynamic request, contains, remove, and getAll"),
        "alarms" to ApiCapability("alarms", CapabilityStatus.FULL, "ExtensionAlarmsAdapter", "Timer and periodic alarm scheduling with worker wake-up"),
        "action" to ApiCapability("action", CapabilityStatus.PARTIAL, "ExtensionActionAdapter + ExtensionEngineApi", "Per-tab state and popup routing use the existing Android browser host; exact Chrome toolbar placement/icon rendering remains host-dependent"),
        "browserAction" to ApiCapability("browserAction", CapabilityStatus.PARTIAL, "ExtensionActionAdapter", "Manifest V2 state is implemented; visible toolbar integration remains host-dependent"),
        "pageAction" to ApiCapability("pageAction", CapabilityStatus.PARTIAL, "ExtensionActionAdapter", "State is implemented; visible page-action UI integration remains host-dependent"),
        "contextMenus" to ApiCapability("contextMenus", CapabilityStatus.PARTIAL, "ExtensionContextMenusAdapter + WebContextMenuBottomSheet", "Menu tree, URL-pattern filtering, and click routing use the existing Android context-menu host; exact desktop menu parity is partial"),
        "commands" to ApiCapability("commands", CapabilityStatus.PARTIAL, "ExtensionCommandsAdapter", "Command state is implemented; Android keyboard/shortcut delivery is host-dependent"),
        "omnibox" to ApiCapability("omnibox", CapabilityStatus.PARTIAL, "ExtensionOmniboxAdapter + BrowserScreen", "Keyword, input events, and suggestions are connected to the existing Android address-bar host; exact Chrome provider parity remains partial"),
        "sidePanel" to ApiCapability("sidePanel", CapabilityStatus.PARTIAL, "ExtensionSidePanelAdapter + ExtensionEngineApi", "Options and open() require and route through the existing Android side-panel host; unsupported host surfaces fail explicitly"),
        "management" to ApiCapability("management", CapabilityStatus.FULL, "ExtensionRegistry", "Extension info, enable/disable, and uninstall"),
        "notifications" to ApiCapability("notifications", CapabilityStatus.FULL, "NotificationEngine", "Android system notification creation and update"),
        "idle" to ApiCapability("idle", CapabilityStatus.PARTIAL, "ExtensionIdleAdapter", "Query state and idle interval; provider/device behavior is not identical to Chrome"),
        "tts" to ApiCapability("tts", CapabilityStatus.PARTIAL, "ExtensionTtsAdapter", "Android text-to-speech bridge with platform-dependent voices and lifecycle"),
        "search" to ApiCapability("search", CapabilityStatus.PARTIAL, "ExtensionSearchAdapter", "Search URL routing through browser delegate"),
        "topSites" to ApiCapability("topSites", CapabilityStatus.PARTIAL, "ExtensionTopSitesAdapter + HistoryEngine", "Top-site candidates are derived from the canonical history engine; exact Chrome Top Sites ranking/host parity is not guaranteed"),
        "system" to ApiCapability("system", CapabilityStatus.FULL, "ExtensionSystemAdapter", "CPU, memory, and storage information"),
        "sessions" to ApiCapability("sessions", CapabilityStatus.PARTIAL, "TabEngineApi + ExtensionSessionJournal", "Recently closed and local restore are durable through the browser tab/session engine; cross-device getDevices synchronization is unavailable"),
        "tabGroups" to ApiCapability("tabGroups", CapabilityStatus.PARTIAL, "TabEngineApi", "Group state exists; full Chrome group semantics and UI are incomplete"),

        // Implemented only as explicit unsupported/partial boundaries.
        "debugger" to ApiCapability("debugger", CapabilityStatus.PARTIAL, "ChromeDebuggerAdapter + ChromeDebuggerSessionManager + ChromeDebuggerCdpTransport", "Real Chromium CDP WebSocket transport for live WebView page targets; extension/background-worker and full Chrome target parity remain host-limited"),
        "devtools" to ApiCapability("devtools", CapabilityStatus.UNSUPPORTED, "None", "Chrome extension devtools.* APIs (custom extension panels/sidebar integration) are not fully implemented; Swift Browser's built-in Developer Tools UI is provided separately by developer-tools-engine"),
        "desktopCapture" to ApiCapability("desktopCapture", CapabilityStatus.UNSUPPORTED, "None", "Desktop screen capture API is not available"),
        "identity" to ApiCapability("identity", CapabilityStatus.UNSUPPORTED, "None", "No OAuth/web-auth token broker is connected; synthetic tokens are not Chrome Identity support"),
        "offscreen" to ApiCapability("offscreen", CapabilityStatus.PARTIAL, "ExtensionOffscreenAdapter", "Hidden WebView approximation exists; Chrome offscreen-document lifecycle and permissions are not equivalent"),
        "userScripts" to ApiCapability("userScripts", CapabilityStatus.PARTIAL, "ExtensionUserScriptsAdapter + WebViewUserScriptWorldBridge", "USER_SCRIPT world, worldId, globs, messaging, execute(), getWorldConfigurations(), and resetWorldConfiguration() use the canonical WebView world transport; CSP enforcement remains explicitly unsupported"),
        "privacy" to ApiCapability("privacy", CapabilityStatus.PARTIAL, "ExtensionPrivacyAdapter", "Adapter exists; exact per-profile/per-origin Chrome privacy semantics are not guaranteed"),
        "contentSettings" to ApiCapability("contentSettings", CapabilityStatus.PARTIAL, "ExtensionContentSettingsAdapter", "Adapter exists; exact per-origin Chrome content-setting enforcement is not guaranteed"),
        "power" to ApiCapability("power", CapabilityStatus.FULL, "ExtensionPowerAdapter", "Android WakeLock-backed requestKeepAwake/releaseKeepAwake with per-extension ownership"),
        "gcm" to ApiCapability("gcm", CapabilityStatus.UNSUPPORTED, "None", "Legacy Google Cloud Messaging API is unsupported"),
        "instanceID" to ApiCapability("instanceID", CapabilityStatus.UNSUPPORTED, "None", "Instance ID API is unsupported"),
        "fontSettings" to ApiCapability("fontSettings", CapabilityStatus.UNSUPPORTED, "None", "Font settings API is unsupported"),
        "wallpaper" to ApiCapability("wallpaper", CapabilityStatus.UNSUPPORTED, "None", "ChromeOS wallpaper API is unsupported"),
        "proxy" to ApiCapability("proxy", CapabilityStatus.UNSUPPORTED, "None", "System proxy configuration API is unsupported"),
        "vpnProvider" to ApiCapability("vpnProvider", CapabilityStatus.UNSUPPORTED, "None", "ChromeOS VPN provider API is unsupported")
    )

    private val normalizedCapabilities: Map<String, ApiCapability> by lazy {
        capabilities.entries.associate { (key, value) -> key.lowercase() to value }
    }

    fun getCapability(namespace: String): ApiCapability? {
        val normalized = namespace.trim().lowercase()
        return capabilities[namespace] ?: normalizedCapabilities[normalized]
    }

    fun getStatus(namespace: String): CapabilityStatus =
        getCapability(namespace)?.status ?: CapabilityStatus.UNSUPPORTED

    fun isSupported(namespace: String): Boolean =
        getStatus(namespace) != CapabilityStatus.UNSUPPORTED
}
