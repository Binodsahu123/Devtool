package com.swift.browser.browserengine.ui

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.launch
import android.graphics.Bitmap
import android.net.Uri
import android.view.ViewGroup
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import com.swift.browser.browserengine.BrowserUiState
import com.swift.browser.browserengine.BrowserViewModel
import com.swift.browser.browserengine.BrowserWebView
import com.swift.browser.browserengine.BrowserWebViewController
import com.swift.browser.developertoolsengine.DevToolsDockMode
import com.swift.browser.developertoolsengine.DeveloperToolsEngine
import com.swift.browser.developertoolsengine.DeveloperToolsPanel
import com.swift.browser.desktopengine.api.DesktopEngineProvider
import com.swift.browser.browserengine.ErrorPageEngine
import com.swift.browser.browserengine.FindInPageEngine
import com.swift.browser.browserengine.PrintEngine
import com.swift.browser.browserengine.util.ShortcutUtils
import com.swift.browser.permissionengine.ui.PermissionPromptDialog
import com.swift.browser.data.TopSite
import com.swift.browser.tabengine.model.TabGroupModel
import com.swift.browser.tabengine.model.TabModel
import com.swift.browser.vpnengine.api.VpnEngineProvider
import com.swift.browser.vpnengine.domain.VpnConnectionState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(
    viewModel: BrowserViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val uiState by viewModel.uiState.collectAsState()
    val downloadsList by viewModel.downloads.collectAsState()
    val bookmarksList by viewModel.bookmarks.collectAsState()
    val historyList by viewModel.history.collectAsState()
    val groupsList by viewModel.tabEngine.groups.collectAsState()
    val newsState by viewModel.newsEngine.uiState.collectAsState()
    val translateUiState by viewModel.translateEngine.uiState.collectAsState()

    val activeTabId by viewModel.tabEngine.activeTabId.collectAsState()

    // Screen and Overlay states
    var activeQuickTool by remember { mutableStateOf<String?>(null) }
    var pendingToolLaunch by remember { mutableStateOf<String?>(null) }
    var isTabSwitcherOpen by remember { mutableStateOf(false) }
    var isBookmarksOpen by remember { mutableStateOf(false) }
    var isHistoryOpen by remember { mutableStateOf(false) }
    var isDownloadsOpen by remember { mutableStateOf(false) }
    var isPasswordManagerOpen by remember { mutableStateOf(false) }
    var isDevToolsOpen by remember { mutableStateOf(false) }
    var devToolsDockMode by remember { mutableStateOf(DevToolsDockMode.BOTTOM) }
    var devToolsFrontendUrl by remember { mutableStateOf<String?>(null) }
    var devToolsLoadError by remember { mutableStateOf<String?>(null) }
    var isAiPanelOpen by remember { mutableStateOf(false) }
    var isVoiceOverlayOpen by remember { mutableStateOf(false) }
    var isReaderModeOpen by remember { mutableStateOf(false) }
    var isTranslateBarOpen by remember { mutableStateOf(false) }

    LaunchedEffect(activeTabId) {
        // A tab owns its own WebView scroll position. Never let the previous tab's
        // chrome progress leak into the newly active tab.
        viewModel.scrollChromeController.reset()
        isTranslateBarOpen = false
        activeTabId?.let { viewModel.translateEngine.resetStateForNavigation(it) }
    }
    var isAboutDialogOpen by remember { mutableStateOf(false) }
    var isFindInPageOpen by remember { mutableStateOf(false) }
    var findInPageQuery by remember { mutableStateOf("") }
    var showBrowserMenu by remember { mutableStateOf(false) }
    var isUrlEditing by remember { mutableStateOf(false) }
    var urlInputText by remember { mutableStateOf("") }
    var activeOmniboxExtensionId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(isUrlEditing) {
        if (isUrlEditing) {
            viewModel.scrollChromeController.reset()
        }
    }

    DisposableEffect(context) {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                if (intent?.action == "com.swift.browser.ACTION_CLEAR_HISTORY") {
                    coroutineScope.launch {
                        viewModel.clearAllHistory()
                    }
                }
            }
        }
        val filter = android.content.IntentFilter("com.swift.browser.ACTION_CLEAR_HISTORY")
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        onDispose {
            context.unregisterReceiver(receiver)
        }
    }
    val contextMenuState by viewModel.contextMenuState.collectAsState()

    // Dialog states
    var showSslDialog by remember { mutableStateOf(false) }
    var showAddShortcutDialog by remember { mutableStateOf(false) }
    var showRecentTabsDialog by remember { mutableStateOf(false) }
    var showClearDataDialog by remember { mutableStateOf(false) }
    var showHelpFeedbackDialog by remember { mutableStateOf(false) }
    var showExtensionsDialog by remember { mutableStateOf(false) }
    var showWebNotificationsDialog by remember { mutableStateOf(false) }
    var showMediaDetectedDialog by remember { mutableStateOf(false) }
    var showOrionAiAssistantBottomSheet by remember { mutableStateOf(false) }
    var showPrintPreviewDialog by remember { mutableStateOf(false) }

    // Developer Tools uses the Unix Shells frontend/backend imported into its own module.
    // The inspected page and tab remain owned by Swift's existing Tab Engine.
    val developerToolsEngine = remember(context) { DeveloperToolsEngine.get(context) }

    // Extension Engine Integration
    val extensionApi = remember(context) { com.swift.browser.extensionengine.ExtensionEngineApi.getInstance(context) }
    val extensionUiState by extensionApi.uiState.collectAsState()

    // 3-dot menu actions: close the menu and let Compose render that change first.
    // Heavy/engine work starts only after the menu has had a frame to dismiss.
    fun launchMenuAction(action: suspend () -> Unit) {
        showBrowserMenu = false
        coroutineScope.launch {
            withFrameNanos { }
            action()
        }
    }

    /** Single action shared by every Swift Browser menu entry for Developer Tools. */
    fun toggleDeveloperTools() {
        if (isDevToolsOpen) {
            isDevToolsOpen = false
        } else {
            isDevToolsOpen = true
            devToolsDockMode = DevToolsDockMode.BOTTOM
            isUrlEditing = false
            devToolsFrontendUrl = null
            devToolsLoadError = null
        }
    }

    val findInPageEngine = remember { FindInPageEngine() }
    val findInPageState by findInPageEngine.state.collectAsState()

    // Speech Recognizer Launcher for Voice Search
    val speechRecognizerLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val spokenText = result.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!spokenText.isNullOrEmpty()) {
                val formatted = if (spokenText.startsWith("http://") || spokenText.startsWith("https://")) spokenText
                else "https://www.google.com/search?q=${java.net.URLEncoder.encode(spokenText, "UTF-8")}"
                viewModel.addNewTab(formatted)
            }
        }
    }

    val webViewController = remember(context) { BrowserWebViewController(context) }

    val currentTabId = activeTabId
    val activeTab = remember(groupsList, currentTabId) {
        val found = if (currentTabId != null) viewModel.tabEngine.getTab(currentTabId) else null
        found ?: groupsList.flatMap { it.tabs }.find { it.id == currentTabId }
            ?: groupsList.flatMap { it.tabs }.firstOrNull()
            ?: TabModel(id = "default", url = "swift://newtab", title = "New Tab")
    }

    val tabNavigationHistory = remember { androidx.compose.runtime.mutableStateListOf<String>() }
    androidx.compose.runtime.LaunchedEffect(activeTab.id) {
        if (activeTab.id != "default") {
            tabNavigationHistory.remove(activeTab.id)
            tabNavigationHistory.add(activeTab.id)
        }
    }
    androidx.compose.runtime.LaunchedEffect(groupsList) {
        val existingIds = groupsList.flatMap { it.tabs }.map { it.id }.toSet()
        tabNavigationHistory.retainAll(existingIds)
    }

    val activeTabGroup = remember(groupsList, activeTab) {
        groupsList.find { group -> group.tabs.any { it.id == activeTab.id } } ?: groupsList.firstOrNull()
    }

    val currentWebUrl = activeTab.url
    val currentWebTitle = activeTab.title
    val webProgress = activeTab.progress
    val currentFavicon = activeTab.favicon
    val desktopState by DesktopEngineProvider.api.getTabState(activeTab.id).collectAsState()
    val isDesktopMode = desktopState.isDesktopModeEnabled
    val isBiometricUnlocked by viewModel.isBiometricUnlocked.collectAsStateWithLifecycle()

    val isNewTab = activeTab.url.startsWith("swift://newtab") || activeTab.url.isEmpty() || activeTab.url == "about:blank"
    val isDiagnosticsPage = activeTab.url.startsWith("swift://diagnostics")

    var activeWebView by remember { mutableStateOf<BrowserWebView?>(null) }

    androidx.compose.runtime.LaunchedEffect(activeTab.id, isNewTab) {
        if (isNewTab) {
            activeWebView = null
        } else {
            val wv = viewModel.tabEngine.getWebView(activeTab.id) as? BrowserWebView
            activeWebView = if (wv != null && !wv.isDestroyed) wv else null
        }
    }

    androidx.compose.runtime.LaunchedEffect(
        isDevToolsOpen, activeTab.id, activeWebView
    ) {
        if (!isDevToolsOpen) {
            devToolsFrontendUrl = null
            devToolsLoadError = null
        } else {
            val targetWebView = activeWebView
            if (targetWebView == null || targetWebView.isDestroyed) {
                devToolsFrontendUrl = null
                devToolsLoadError = "There is no active website WebView to inspect. Open a website in Swift Browser first."
            } else {
                devToolsFrontendUrl = null
                devToolsLoadError = null
                try {
                    val targetUrl = targetWebView.url?.takeIf { it.isNotBlank() } ?: currentWebUrl
                    val targetTitle = targetWebView.title?.takeIf { it.isNotBlank() } ?: currentWebTitle
                    devToolsFrontendUrl = developerToolsEngine.openForTarget(
                        tabId = activeTab.id,
                        webView = targetWebView,
                        pageUrl = targetUrl,
                        pageTitle = targetTitle
                    )
                } catch (error: Exception) {
                    devToolsLoadError = error.message ?: "Unable to connect to the active WebView's CDP target."
                }
            }
        }
    }

    val defaultTopSites = remember {
        listOf(
            TopSite(id = 1, url = "https://www.google.com", title = "Google"),
            TopSite(id = 2, url = "https://www.wikipedia.org", title = "Wikipedia"),
            TopSite(id = 3, url = "https://github.com", title = "GitHub"),
            TopSite(id = 4, url = "https://reddit.com", title = "Reddit"),
            TopSite(id = 5, url = "https://news.ycombinator.com", title = "Hacker News"),
            TopSite(id = 6, url = "swift://diagnostics", title = "Diagnostics")
        )
    }
    val realTopSites by viewModel.topSites.collectAsState()
    val topSitesList = if (realTopSites.isNotEmpty()) realTopSites else defaultTopSites

    val isPageBookmarked = remember(bookmarksList, currentWebUrl) {
        bookmarksList.any { it.url == currentWebUrl }
    }

    val totalTabsCount = remember(groupsList) {
        groupsList.sumOf { it.tabs.size }.coerceAtLeast(1)
    }

    // File Chooser Launcher
    var fileUploadCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    val fileChooserLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uris = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
        fileUploadCallback?.onReceiveValue(uris)
        fileUploadCallback = null
    }

    val canGoBackInWebView = if (isNewTab) false else (activeWebView != null && !activeWebView!!.isDestroyed && activeWebView!!.canGoBack())
    val hasActiveOverlays = com.swift.browser.videoengine.api.VideoEngineApi.isCustomViewShowing() ||
            extensionUiState.showPopupBottomSheet ||
            extensionUiState.showSidePanel ||
            extensionUiState.showExtensionPage ||
            extensionUiState.showActiveHubDialog ||
            extensionUiState.showManagerOverlay ||
            extensionUiState.showStoreScreen ||
            showSslDialog ||
            showAddShortcutDialog ||
            showRecentTabsDialog ||
            showClearDataDialog ||
            showHelpFeedbackDialog ||
            showExtensionsDialog ||
            showWebNotificationsDialog ||
            isAboutDialogOpen ||
            contextMenuState.show ||
            activeQuickTool != null ||
            pendingToolLaunch != null ||
            isReaderModeOpen ||
            isFindInPageOpen ||
            isTranslateBarOpen ||
            translateUiState.isVisible ||
            isVoiceOverlayOpen ||
            showBrowserMenu ||
            isDevToolsOpen ||
            isAiPanelOpen ||
            isBookmarksOpen ||
            isHistoryOpen ||
            isDownloadsOpen ||
            isPasswordManagerOpen ||
            isTabSwitcherOpen ||
            isUrlEditing

    val canGoBackInHistory = tabNavigationHistory.size > 1

    // BackHandler: handles overlays, webview back navigation, and returning to home/newtab
    BackHandler(
        enabled = hasActiveOverlays || canGoBackInWebView || !isNewTab || (canGoBackInHistory && groupsList.flatMap { it.tabs }.size > 1)
    ) {
        when {
            com.swift.browser.videoengine.api.VideoEngineApi.isCustomViewShowing() -> {
                val activity = context as? android.app.Activity
                if (activity != null) {
                    com.swift.browser.videoengine.api.VideoEngineApi.hideCustomView(activity)
                }
            }
            extensionUiState.showPopupBottomSheet -> extensionApi.closePopup()
            extensionUiState.showSidePanel -> extensionApi.closeSidePanel()
            extensionUiState.showExtensionPage -> extensionApi.closeExtensionPage()
            extensionUiState.showActiveHubDialog -> extensionApi.closeActiveHub()
            extensionUiState.showManagerOverlay -> extensionApi.closeManagerUi()
            extensionUiState.showStoreScreen -> extensionApi.closeStoreScreen()
            showSslDialog -> showSslDialog = false
            showAddShortcutDialog -> showAddShortcutDialog = false
            showRecentTabsDialog -> showRecentTabsDialog = false
            showClearDataDialog -> showClearDataDialog = false
            showHelpFeedbackDialog -> showHelpFeedbackDialog = false
            showExtensionsDialog -> showExtensionsDialog = false
            showWebNotificationsDialog -> showWebNotificationsDialog = false
            contextMenuState.show -> viewModel.dismissContextMenu()
            isAboutDialogOpen -> isAboutDialogOpen = false
            activeQuickTool != null -> activeQuickTool = null
            pendingToolLaunch != null -> pendingToolLaunch = null
            isReaderModeOpen -> isReaderModeOpen = false
            isFindInPageOpen -> {
                isFindInPageOpen = false
                findInPageEngine.toggleFindInPage(false, activeWebView)
            }
            isTranslateBarOpen -> {
                isTranslateBarOpen = false
                viewModel.translateEngine.dismissTranslateBar(activeWebView, activeTab.id, currentWebUrl)
            }
            translateUiState.isVisible -> {
                viewModel.translateEngine.dismissTranslateBar(activeWebView, activeTab.id, currentWebUrl)
            }
            isVoiceOverlayOpen -> isVoiceOverlayOpen = false
            showBrowserMenu -> showBrowserMenu = false
            isDevToolsOpen && devToolsDockMode == DevToolsDockMode.FULLSCREEN -> devToolsDockMode = DevToolsDockMode.BOTTOM
            isDevToolsOpen -> isDevToolsOpen = false
            isAiPanelOpen -> isAiPanelOpen = false
            isBookmarksOpen -> isBookmarksOpen = false
            isHistoryOpen -> isHistoryOpen = false
            isDownloadsOpen -> isDownloadsOpen = false
            isPasswordManagerOpen -> isPasswordManagerOpen = false
            isTabSwitcherOpen -> isTabSwitcherOpen = false
            isUrlEditing -> {
                isUrlEditing = false
                focusManager.clearFocus()
            }
            canGoBackInWebView -> {
                val wv = activeWebView
                if (wv != null && !wv.isDestroyed && wv.canGoBack()) {
                    wv.safeGoBack()
                } else {
                    activeWebView?.stopLoading()
                    viewModel.tabEngine.updateTab(activeTab.id) { it.copy(url = "swift://newtab", title = "New Tab") }
                }
            }
            !isNewTab -> {
                activeWebView?.stopLoading()
                viewModel.tabEngine.updateTab(activeTab.id) { it.copy(url = "swift://newtab", title = "New Tab") }
            }
            canGoBackInHistory -> {
                tabNavigationHistory.remove(activeTab.id)
                val targetTabId = tabNavigationHistory.lastOrNull()
                if (targetTabId != null && targetTabId != activeTab.id) {
                    viewModel.tabEngine.switchTab(targetTabId)
                }
            }
        }
    }

    Box(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // Main Navigation Layers & Overlays
        when {
            activeQuickTool != null -> {
                QuickToolsOverlay(
                    toolName = activeQuickTool!!,
                    viewModel = viewModel,
                    onClose = { activeQuickTool = null }
                )
            }

            pendingToolLaunch == "video" -> {
                com.swift.browser.videoengine.ui.VideoCenterHomeScreen(
                    onBack = { pendingToolLaunch = null }
                )
            }

            pendingToolLaunch == "music" -> {
                com.swift.browser.audioengine.AudioCenterHomeScreen(
                    onBack = { pendingToolLaunch = null }
                )
            }

            isReaderModeOpen -> {
                val readerState by viewModel.readerEngine.readerState.collectAsState()
                LaunchedEffect(activeTab.id, currentWebUrl) {
                    activeWebView?.let { webView ->
                        viewModel.readerEngine.triggerReaderMode(webView, activeTab.id)
                    }
                }
                com.swift.browser.readerengine.ui.ReaderModeScreen(
                    state = readerState,
                    onClose = {
                        viewModel.readerEngine.closeReaderMode()
                        isReaderModeOpen = false
                    },
                    onUpdateFontSize = { size -> viewModel.readerEngine.updateReaderFontSize(size) },
                    onUpdateTheme = { theme -> viewModel.readerEngine.updateReaderTheme(theme) },
                    onUpdateTypeface = { isSerif -> viewModel.readerEngine.updateReaderTypeface(isSerif) }
                )
            }


            isBookmarksOpen -> {
                com.swift.browser.bookmarkengine.ui.BookmarksOverlay(
                    bookmarks = bookmarksList,
                    onDismiss = { isBookmarksOpen = false },
                    onNavigate = { url ->
                        viewModel.openUrl(url)
                        isBookmarksOpen = false
                    },
                    onDelete = { bookmark ->
                        viewModel.deleteBookmark(bookmark)
                    }
                )
            }

            isHistoryOpen -> {
                com.swift.browser.historyengine.ui.HistoryOverlay(
                    history = historyList,
                    onDismiss = { isHistoryOpen = false },
                    onNavigate = { url ->
                        viewModel.openUrl(url)
                        isHistoryOpen = false
                    },
                    onDelete = { id ->
                        viewModel.deleteHistoryItem(id)
                    },
                    onClearAll = {
                        viewModel.clearAllHistory()
                    },
                    onClearBrowsingData = { clearHistory, clearCookies, clearCache, _ ->
                        viewModel.clearBrowsingData(clearHistory, clearCookies, clearCache, false, context)
                    }
                )
            }

            isDownloadsOpen -> {
                com.swift.browser.downloadengine.ui.DownloadsOverlay(
                    downloads = downloadsList,
                    onDismiss = { isDownloadsOpen = false },
                    onOpenFile = { filePath, mimeType, _ ->
                        try {
                            val file = java.io.File(filePath)
                            val uri = androidx.core.content.FileProvider.getUriForFile(
                                context,
                                "${context.packageName}.fileprovider",
                                file
                            )
                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(uri, mimeType)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            Toast.makeText(context, "Cannot open file", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onDeleteDownloads = { ids -> viewModel.deleteDownloads(ids) },
                    onDeleteDownload = { id -> viewModel.deleteDownloads(setOf(id)) },
                    onRenameDownloadFile = { id, _, newName ->
                        viewModel.renameDownload(id, newName)
                        true
                    }
                )
            }

            isPasswordManagerOpen -> {
                Dialog(
                    onDismissRequest = { isPasswordManagerOpen = false },
                    properties = DialogProperties(usePlatformDefaultWidth = false)
                ) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        com.swift.browser.passwordengine.ui.PasswordManagerScreen(
                            viewModel = viewModel(),
                            onNavigateBack = { isPasswordManagerOpen = false }
                        )
                    }
                }
            }

            isAiPanelOpen -> {
                AIChatPanel(
                    tabId = activeTab.id,
                    url = if (isNewTab) "swift://newtab" else currentWebUrl,
                    pageText = currentWebTitle,
                    onDismiss = { isAiPanelOpen = false },
                    viewModel = viewModel
                )
            }



            isUrlEditing -> {
                val searchSuggestions by viewModel.searchSuggestionsProvider.suggestions.collectAsState()
                val isPrivateMode = uiState.isIncognito

                LaunchedEffect(urlInputText, isPrivateMode) {
                    viewModel.searchSuggestionsProvider.fetchSuggestions(
                        query = urlInputText,
                        engineName = viewModel.preferenceManager?.getString("default_search_engine", "Google") ?: "Google",
                        historyResults = if (isPrivateMode) emptyList() else historyList.map {
                            com.swift.browser.searchengine.SearchSuggestion(
                                com.swift.browser.searchengine.SuggestionType.HISTORY, it.title, it.url
                            )
                        },
                        bookmarkResults = bookmarksList.map {
                            com.swift.browser.searchengine.SearchSuggestion(
                                com.swift.browser.searchengine.SuggestionType.BOOKMARK, it.title, it.url
                            )
                        },
                        coroutineScope = this,
                        browsingContext = if (isPrivateMode) com.swift.browser.searchengine.BrowsingContext.PRIVATE
                        else com.swift.browser.searchengine.BrowsingContext.NORMAL
                    )
                }

                val omniboxVersion by extensionApi.omniboxSuggestionsVersion.collectAsState()
                val activeExtensionSuggestions = remember(urlInputText, activeOmniboxExtensionId, omniboxVersion) {
                    if (activeOmniboxExtensionId != null) {
                        val spaceIdx = urlInputText.indexOf(' ')
                        if (spaceIdx > 0) {
                            val keyword = urlInputText.substring(0, spaceIdx).trim()
                            val query = urlInputText.substring(spaceIdx + 1)
                            extensionApi.getOmniboxSuggestions(keyword, query)
                        } else {
                            val kw = urlInputText.trim()
                            val ownerExtId = extensionApi.findOmniboxKeywordOwner(kw)
                            if (ownerExtId != null) {
                                extensionApi.getOmniboxSuggestions(kw, "")
                            } else emptyList()
                        }
                    } else emptyList()
                }

                com.swift.browser.searchengine.ui.SearchFocusedOverlay(
                    activeTab = com.swift.browser.searchengine.ui.SearchTabPreview(
                        title = currentWebTitle,
                        url = currentWebUrl
                    ),
                    searchSuggestions = searchSuggestions,
                    extensionSuggestions = activeExtensionSuggestions.map {
                        com.swift.browser.searchengine.ui.SearchExtensionSuggestion(
                            content = it.content,
                            description = it.description,
                            deletable = it.deletable
                        )
                    },
                    onExtensionSuggestionSelect = { extSug ->
                        if (activeOmniboxExtensionId != null) {
                            extensionApi.triggerOmniboxInputEntered(activeOmniboxExtensionId!!, extSug.content, "currentTab")
                            activeOmniboxExtensionId = null
                            isUrlEditing = false
                            focusManager.clearFocus()
                            viewModel.scrollChromeController.reset()
                        }
                    },
                    onExtensionSuggestionDelete = { extSug ->
                        if (activeOmniboxExtensionId != null) {
                            extensionApi.deleteOmniboxSuggestion(activeOmniboxExtensionId!!, extSug.content)
                        }
                    },
                    currentInputUrl = urlInputText,
                    addressBarPosition = "top",
                    history = if (isPrivateMode) emptyList() else historyList.map {
                        com.swift.browser.searchengine.ui.SearchHistoryPreview(it.title, it.url)
                    },
                    onSearch = { query ->
                        if (activeOmniboxExtensionId != null) {
                            val spaceIdx = query.indexOf(' ')
                            val queryText = if (spaceIdx > 0) query.substring(spaceIdx + 1) else query
                            extensionApi.triggerOmniboxInputEntered(activeOmniboxExtensionId!!, queryText, "currentTab")
                            activeOmniboxExtensionId = null
                            isUrlEditing = false
                            focusManager.clearFocus()
                            viewModel.scrollChromeController.reset()
                        } else if (query.startsWith("devtools://", ignoreCase = true)) {
                            isUrlEditing = false
                            focusManager.clearFocus()
                            if (!isDevToolsOpen) {
                                isDevToolsOpen = true
                                devToolsDockMode = DevToolsDockMode.FULLSCREEN
                            }
                            viewModel.scrollChromeController.reset()
                        } else {
                            isUrlEditing = false
                            focusManager.clearFocus()
                            viewModel.openUrl(query, false)
                            viewModel.scrollChromeController.reset()
                        }
                    },
                    onEdit = { text ->
                        urlInputText = text
                        val spaceIdx = text.indexOf(' ')
                        if (spaceIdx > 0) {
                            val keyword = text.substring(0, spaceIdx).trim()
                            val query = text.substring(spaceIdx + 1)
                            val ownerExtId = extensionApi.findOmniboxKeywordOwner(keyword)
                            if (ownerExtId != null) {
                                if (activeOmniboxExtensionId != ownerExtId) {
                                    activeOmniboxExtensionId = ownerExtId
                                    extensionApi.triggerOmniboxInputStarted(ownerExtId)
                                }
                                extensionApi.triggerOmniboxInputChanged(ownerExtId, query)
                            } else if (activeOmniboxExtensionId != null) {
                                extensionApi.triggerOmniboxInputCancelled(activeOmniboxExtensionId!!)
                                activeOmniboxExtensionId = null
                            }
                        } else if (activeOmniboxExtensionId != null) {
                            extensionApi.triggerOmniboxInputCancelled(activeOmniboxExtensionId!!)
                            activeOmniboxExtensionId = null
                        }
                    },
                    onClose = {
                        if (activeOmniboxExtensionId != null) {
                            extensionApi.triggerOmniboxInputCancelled(activeOmniboxExtensionId!!)
                            activeOmniboxExtensionId = null
                        }
                        isUrlEditing = false
                        focusManager.clearFocus()
                        viewModel.scrollChromeController.reset()
                    },
                    onVoiceClick = {
                        try {
                            val intent = Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH)
                                putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Speak to search...")
                            }
                            speechRecognizerLauncher.launch(intent)
                        } catch (_: Exception) {
                            Toast.makeText(context, "Voice recognition not available", Toast.LENGTH_SHORT).show()
                        }
                    },
                    isGlass = false,
                    bottomBar = {
                        if (!isNewTab) {
                            QuickAccessToolsBar(
                                onToolSelect = { tool ->
                                    when (tool) {
                                        "video", "music" -> pendingToolLaunch = tool
                                        "ai" -> isAiPanelOpen = true
                                        else -> activeQuickTool = tool
                                    }
                                    isUrlEditing = false
                                    viewModel.scrollChromeController.reset()
                                },
                                isIncognito = activeTab.isIncognito,
                                modifier = Modifier.fillMaxWidth().imePadding()
                            )
                        }
                    }
                )
            }

            else -> {
                val mainScale by androidx.compose.animation.core.animateFloatAsState(
                    targetValue = if (isTabSwitcherOpen) 0.92f else 1f,
                    animationSpec = com.swift.browser.tabengine.animation.TabAnimationTransitions.FluidSpring,
                    label = "main_content_scale"
                )
                val mainAlpha by androidx.compose.animation.core.animateFloatAsState(
                    targetValue = if (isTabSwitcherOpen) 0.4f else 1f,
                    animationSpec = com.swift.browser.tabengine.animation.TabAnimationTransitions.SmoothSpring,
                    label = "main_content_alpha"
                )

                // Main Browser Shell: New Tab Page or Real WebView with Browser Shell Controls
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = mainScale
                            scaleY = mainScale
                            alpha = mainAlpha
                        }
                ) {
                    if (isDiagnosticsPage) {
                        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                            WebRTCCompatibilityDiagnosticsScreen(
                                onClose = {
                                    viewModel.tabEngine.updateTab(activeTab.id) { it.copy(url = "swift://newtab", title = "New Tab") }
                                },
                                onNavigateToUrl = { url ->
                                    viewModel.openUrl(url)
                                },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    } else if (isNewTab) {
                        // NEW TAB SCREEN WITH CHROME-STYLE HOME SEARCH
                        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                            NewTabScreen(
                                state = uiState,
                                newsState = newsState,
                                activeTab = activeTab,
                                tabCount = totalTabsCount,
                                topSites = topSitesList,
                                recentHistory = historyList,
                                onSearch = { query ->
                                    viewModel.openUrl(query)
                                },
                                onRequestSearchFocus = {
                                    urlInputText = ""
                                    isUrlEditing = true
                                    viewModel.scrollChromeController.reset()
                                },
                                onIncognitoClick = {
                                    val privateTab = viewModel.tabEngine.createTab(
                                        "swift://newtab-incognito",
                                        "Private Tab",
                                        isIncognito = true
                                    )
                                    viewModel.tabEngine.switchTab(privateTab.id)
                                },
                                onArticleClick = { url -> viewModel.openUrl(url) },
                                onCategorySelected = { category -> viewModel.newsEngine.selectCategory(category) },
                                onAddShortcut = { title, url -> viewModel.addCustomShortcut(url, title) },
                                onRemoveTopSite = { topSite -> viewModel.removeTopSite(topSite) },
                                onTabSwitcherClick = { isTabSwitcherOpen = true },
                                onDownloadsClick = { isDownloadsOpen = true },
                                onHistoryClick = { isHistoryOpen = true },
                                onBookmarksClick = { isBookmarksOpen = true },
                                onAIChatClick = { isAiPanelOpen = true },
                                onQuickToolSelected = { tool ->
                                    when (tool) {
                                        "video", "music" -> pendingToolLaunch = tool
                                        "ai" -> isAiPanelOpen = true
                                        else -> activeQuickTool = tool
                                    }
                                },
                                optionsMenuContent = { expanded, onDismiss ->
                                    DropdownMenu(
                                        expanded = expanded,
                                        onDismissRequest = onDismiss
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text("New Tab") },
                                            leadingIcon = { Icon(Icons.Default.Add, contentDescription = null) },
                                            onClick = { android.os.Handler(android.os.Looper.getMainLooper()).post {
                                                onDismiss()
                                                viewModel.addNewTab("swift://newtab")
                                            } }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("New Private Tab") },
                                            leadingIcon = { Icon(Icons.Default.VisibilityOff, contentDescription = null) },
                                            onClick = { android.os.Handler(android.os.Looper.getMainLooper()).post {
                                                onDismiss()
                                                viewModel.tabEngine.createTab("swift://newtab", "Private Tab", isIncognito = true)
                                            } }
                                        )
                                        HorizontalDivider()
                                        DropdownMenuItem(
                                            text = { Text("History") },
                                            leadingIcon = { Icon(Icons.Default.History, contentDescription = null) },
                                            onClick = { android.os.Handler(android.os.Looper.getMainLooper()).post {
                                                onDismiss()
                                                isHistoryOpen = true
                                            } }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Bookmarks") },
                                            leadingIcon = { Icon(Icons.Default.Bookmark, contentDescription = null) },
                                            onClick = { android.os.Handler(android.os.Looper.getMainLooper()).post {
                                                onDismiss()
                                                isBookmarksOpen = true
                                            } }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Downloads") },
                                            leadingIcon = { Icon(Icons.Default.FileDownload, contentDescription = null) },
                                            onClick = { android.os.Handler(android.os.Looper.getMainLooper()).post {
                                                onDismiss()
                                                isDownloadsOpen = true
                                            } }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Web Notifications") },
                                            leadingIcon = { Icon(Icons.Default.Notifications, contentDescription = null) },
                                            onClick = { android.os.Handler(android.os.Looper.getMainLooper()).post {
                                                onDismiss()
                                                showWebNotificationsDialog = true
                                            } }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Recent Tabs") },
                                            leadingIcon = { Icon(Icons.Default.Tab, contentDescription = null) },
                                            onClick = { android.os.Handler(android.os.Looper.getMainLooper()).post {
                                                onDismiss()
                                                showRecentTabsDialog = true
                                            } }
                                        )
                                        HorizontalDivider()
                                        DropdownMenuItem(
                                            text = { Text("Swift AI Assistant") },
                                            leadingIcon = { Icon(Icons.Default.AutoAwesome, contentDescription = null) },
                                            onClick = { android.os.Handler(android.os.Looper.getMainLooper()).post {
                                                onDismiss()
                                                isAiPanelOpen = true
                                            } }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Extensions") },
                                            leadingIcon = { Icon(Icons.Default.Extension, contentDescription = null) },
                                            onClick = { android.os.Handler(android.os.Looper.getMainLooper()).post {
                                                onDismiss()
                                                extensionApi.openManagerUi()
                                            } }
                                        )
                                        DropdownMenuItem(
                                            text = { Text(if (isDevToolsOpen) "Close Developer Tools" else "Developer Tools") },
                                            leadingIcon = { Icon(Icons.Default.Code, contentDescription = null) },
                                            onClick = { android.os.Handler(android.os.Looper.getMainLooper()).post {
                                                onDismiss()
                                                toggleDeveloperTools()
                                            } }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Clear Browsing Data") },
                                            leadingIcon = { Icon(Icons.Default.DeleteSweep, contentDescription = null) },
                                            onClick = { android.os.Handler(android.os.Looper.getMainLooper()).post {
                                                onDismiss()
                                                showClearDataDialog = true
                                            } }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Help & Feedback") },
                                            leadingIcon = { Icon(Icons.Default.HelpOutline, contentDescription = null) },
                                            onClick = { android.os.Handler(android.os.Looper.getMainLooper()).post {
                                                onDismiss()
                                                showHelpFeedbackDialog = true
                                            } }
                                        )
                                        HorizontalDivider()
                                        DropdownMenuItem(
                                            text = { Text("Settings") },
                                            leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null) },
                                            onClick = { android.os.Handler(android.os.Looper.getMainLooper()).post {
                                                onDismiss()
                                                context.startActivity(Intent(context, com.swift.browser.settingsengine.AdvancedSettingsActivity::class.java))
                                            } }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("About Swift Browser") },
                                            leadingIcon = { Icon(Icons.Default.Info, contentDescription = null) },
                                            onClick = { android.os.Handler(android.os.Looper.getMainLooper()).post {
                                                onDismiss()
                                                isAboutDialogOpen = true
                                            } }
                                        )
                                    }
                                },
                                onVoiceClick = {
                                    try {
                                        val intent = Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                            putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH)
                                            putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Speak to search...")
                                        }
                                        speechRecognizerLauncher.launch(intent)
                                    } catch (e: Exception) {
                                        Toast.makeText(context, "Voice recognition not available", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                modifier = Modifier.fillMaxSize()
                            )
                        }

                        // Bottom Quick Access Tools Bar (Video, Music, Swift AI, Editing, Learn & Earn)
                        androidx.compose.animation.AnimatedVisibility(
                            visible = isNewTab && !isTabSwitcherOpen,
                            enter = androidx.compose.animation.expandVertically(
                                animationSpec = androidx.compose.animation.core.tween(
                                    durationMillis = 200,
                                    easing = androidx.compose.animation.core.FastOutSlowInEasing
                                )
                            ) + androidx.compose.animation.fadeIn(
                                animationSpec = androidx.compose.animation.core.tween(200)
                            ),
                            exit = androidx.compose.animation.shrinkVertically(
                                animationSpec = androidx.compose.animation.core.tween(
                                    durationMillis = 200,
                                    easing = androidx.compose.animation.core.FastOutSlowInEasing
                                )
                            ) + androidx.compose.animation.fadeOut(
                                animationSpec = androidx.compose.animation.core.tween(200)
                            )
                        ) {
                            QuickAccessToolsBar(
                                onToolSelect = { tool ->
                                    when (tool) {
                                        "video", "music" -> pendingToolLaunch = tool
                                        "ai" -> isAiPanelOpen = true
                                        else -> activeQuickTool = tool
                                    }
                                },
                                isIncognito = activeTab.isIncognito,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    } else {
                        // ACTIVE WEBPAGE HOST WITH EXCLUSIVE STATUS BAR RESERVATION & DYNAMIC EXPANSION ARCHITECTURE
                        val hideProgress by viewModel.scrollChromeController.hideProgress.collectAsState()
                        val density = androidx.compose.ui.platform.LocalDensity.current
                        val defaultToolbarHeightPx = with(density) { 56.dp.toPx() }
                        val defaultBottomHeightPx = with(density) { 60.dp.toPx() }
                        var topToolbarHeightPx by remember { mutableFloatStateOf(defaultToolbarHeightPx) }
                        var bottomToolbarHeightPx by remember { mutableFloatStateOf(defaultBottomHeightPx) }

                        val shouldShowBottom =
                            !isTabSwitcherOpen &&
                            !isReaderModeOpen &&
                            !isNewTab &&
                            !isDevToolsOpen

                        // Chromium-Style Layered Viewport Architecture:
                        // Root Box holds WebView (Layer 1) and independent GPU-translated Overlays (Layer 2 & 3).
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .windowInsetsPadding(WindowInsets.statusBars)
                                .clipToBounds()
                        ) {
                            // 1. Synchronized WebView Canvas & SidePanel Layout
                            Row(modifier = Modifier.fillMaxSize()) {
                                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                                    Column(modifier = Modifier.fillMaxSize()) {
                                        if (!(isDevToolsOpen && devToolsDockMode == DevToolsDockMode.FULLSCREEN)) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .weight(if (isDevToolsOpen && devToolsDockMode == DevToolsDockMode.BOTTOM) 0.5f else 1f)
                                        ) {
                                            key(activeTab.id) {
                                        AndroidView(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .layout { measurable, constraints ->
                                            val progress = hideProgress.coerceIn(0f, 1f)
                                            val topH = (if (topToolbarHeightPx > 0f && topToolbarHeightPx.isFinite()) topToolbarHeightPx else defaultToolbarHeightPx) * (1f - progress)
                                            val bottomH = if (shouldShowBottom) {
                                                (if (bottomToolbarHeightPx > 0f && bottomToolbarHeightPx.isFinite()) bottomToolbarHeightPx else defaultBottomHeightPx) * (1f - progress)
                                            } else 0f

                                            val topOffset = topH.roundToInt()
                                            val targetHeight = (constraints.maxHeight - topH - bottomH).roundToInt().coerceAtLeast(0)

                                            val placeable = measurable.measure(
                                                constraints.copy(
                                                    minHeight = targetHeight,
                                                    maxHeight = targetHeight
                                                )
                                            )
                                            layout(constraints.maxWidth, constraints.maxHeight) {
                                                placeable.place(0, topOffset)
                                            }
                                        }
                                        .testTag("browser_webview"),
                                    factory = { ctx ->
                                            val existing = viewModel.tabEngine.getWebView(activeTab.id) as? BrowserWebView
                                            val wv = if (existing != null && !existing.isDestroyed) {
                                                existing
                                            } else {
                                                val newWebView = webViewController.createAndConfigureWebView(
                                                    tabId = activeTab.id,
                                                    config = BrowserWebViewController.WebViewConfiguration(
                                                        isJavaScriptEnabled = true,
                                                        isHardwareAccelerationEnabled = true,
                                                        isDesktopMode = isDesktopMode,
                                                        isIncognito = activeTab.isIncognito
                                                    ),
                                                    getTab = { id -> viewModel.tabEngine.getTab(id) },
                                                    updateTabModel = { id, updater -> viewModel.tabEngine.updateTab(id, updater) },
                                                    triggerTabUpdatedEvent = { tabId, url -> extensionApi.notifyTabUpdated(tabId, url) },
                                                    extensionSetup = { wv, tabId -> extensionApi.setupWebView(wv, tabId) },
                                                    injectContentScripts = { wv, url, runAt ->
                                                        val isPrivate = (wv as? com.swift.browser.browserengine.BrowserWebView)?.isPrivate ?: false
                                                        val pId = (wv as? com.swift.browser.browserengine.BrowserWebView)?.privateSessionId
                                                        extensionApi.injectContentScripts(wv, url, runAt, isPrivate, pId)
                                                    },
                                                    flushCookies = { com.swift.browser.cookieengine.CookieEngineApi.getInstance(context).flush() },
                                                    permissionEngine = viewModel.permissionEngine,
                                                    uiCallbacks = object : BrowserWebViewController.WebViewUiCallbacks {
                                                        private fun isCallbackValid(source: BrowserWebViewController.WebViewCallbackSource): Boolean {
                                                            val tabId = source.tabId
                                                            val currentWebView = viewModel.tabEngine.getWebView(tabId) as? BrowserWebView
                                                            val currentGen = viewModel.tabEngine.getNavigationGeneration(tabId)
                                                            val tabModel = viewModel.tabEngine.getTab(tabId)

                                                            if (tabModel == null) return false
                                                            if (source.webView.isDestroyed) return false
                                                            if (currentWebView == null || currentWebView != source.webView) return false
                                                            if (source.generation != currentGen) return false
                                                            if (source.webView.generation != source.generation) return false
                                                            return true
                                                        }

                                                        override fun onContextMenuRequested(url: String, isImage: Boolean, isImageLink: Boolean, imageUrl: String, contextMenuState: com.swift.browser.browserengine.ContextMenuState?) {
                                                            if (contextMenuState != null) {
                                                                viewModel.showContextMenuState(contextMenuState)
                                                            } else {
                                                                viewModel.showContextMenu(
                                                                    url = url,
                                                                    isImage = isImage,
                                                                    isImageLink = isImageLink,
                                                                    imageUrl = if (imageUrl.isNotBlank()) imageUrl else if (isImage || isImageLink) url else "",
                                                                    tabId = activeTab?.id
                                                                )
                                                            }
                                                        }
                                                        override fun onDownloadRequested(url: String, userAgent: String, contentDisposition: String, mimetype: String, contentLength: Long) {
                                                            Toast.makeText(ctx, "Starting download: ${URLUtil.guessFileName(url, contentDisposition, mimetype)}", Toast.LENGTH_SHORT).show()
                                                            viewModel.downloadActiveFile(url, mimetype, contentDisposition)
                                                        }
                                                        override fun onBlockedAdCountIncremented(tabId: String) {}
                                                        override fun onPageStarted(source: BrowserWebViewController.WebViewCallbackSource, url: String) {
                                                            if (!isCallbackValid(source)) return
                                                            isTranslateBarOpen = false
                                                            viewModel.translateEngine.resetStateForNavigation(source.tabId)
                                                            viewModel.tabEngine.updateTabIfWebViewCurrent(
                                                                tabId = source.tabId,
                                                                webView = source.webView,
                                                                generation = source.generation
                                                            ) { it.copy(url = url, isLoading = true) }

                                                            val job = coroutineScope.launch {
                                                                if (!isCallbackValid(source)) return@launch
                                                                viewModel.translateEngine.onPageStarted(
                                                                    context = ctx,
                                                                    tabId = source.tabId,
                                                                    webView = source.webView,
                                                                    url = url,
                                                                    isDesktop = isDesktopMode,
                                                                    currentTranslateTargetCode = viewModel.translateEngine.uiState.value.targetLanguageCode,
                                                                    onAutoTranslateRequested = { _, _ -> }
                                                                )
                                                            }
                                                            viewModel.tabEngine.registerBrowserCoreJob(source.tabId, source.generation, job)
                                                        }
                                                        override fun onPageFinished(source: BrowserWebViewController.WebViewCallbackSource, url: String, title: String) {
                                                            if (!isCallbackValid(source)) return
                                                            val tab = viewModel.tabEngine.getTab(source.tabId)
                                                            val isIncognitoTab = tab?.isIncognito ?: false
                                                            val effectiveTitle = if (title.isNotBlank()) title else (tab?.title?.takeIf { it.isNotBlank() } ?: url)
                                                            if (!viewModel.tabEngine.updateTabIfWebViewCurrent(source.tabId, source.webView, source.generation) { it.copy(url = url, title = effectiveTitle, isLoading = false) }) return
                                                            if (!isIncognitoTab && url != "swift://newtab" && url != "swift://newtab-incognito" && !url.startsWith("about:")) {
                                                                viewModel.addHistory(url, effectiveTitle)
                                                            }
                                                        }
                                                        override fun onProgressChanged(source: BrowserWebViewController.WebViewCallbackSource, progress: Int) {
                                                            if (!isCallbackValid(source)) return
                                                            if (!viewModel.tabEngine.updateTabIfWebViewCurrent(source.tabId, source.webView, source.generation) { it.copy(progress = progress, isLoading = progress < 100) }) return
                                                        }
                                                        override fun onTitleReceived(source: BrowserWebViewController.WebViewCallbackSource, title: String) {
                                                            if (!isCallbackValid(source)) return
                                                            if (!viewModel.tabEngine.updateTabIfWebViewCurrent(source.tabId, source.webView, source.generation) { it.copy(title = title) }) return
                                                        }
                                                        override fun onFaviconReceived(source: BrowserWebViewController.WebViewCallbackSource, favicon: Bitmap) {
                                                            if (!isCallbackValid(source)) return
                                                            if (!viewModel.tabEngine.updateTabIfWebViewCurrent(source.tabId, source.webView, source.generation) { it.copy(favicon = favicon) }) return
                                                        }
                                                        override fun onErrorPageRequested(view: WebView?, errorType: String, failingUrl: String) {
                                                            ErrorPageEngine.loadErrorPage(view, errorType, failingUrl)
                                                        }
                                                        override fun onShowFileChooser(filePathCallback: ValueCallback<Array<Uri>>?, fileChooserParams: WebChromeClient.FileChooserParams?) {
                                                            fileUploadCallback?.onReceiveValue(null)
                                                            fileUploadCallback = filePathCallback
                                                            try {
                                                                val intent = fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                                                                    addCategory(Intent.CATEGORY_OPENABLE)
                                                                    type = "*/*"
                                                                }
                                                                fileChooserLauncher.launch(intent)
                                                            } catch (e: Exception) {
                                                                fileUploadCallback?.onReceiveValue(null)
                                                                fileUploadCallback = null
                                                            }
                                                        }
                                                        override fun onShowCustomView(view: android.view.View, callback: WebChromeClient.CustomViewCallback) {
                                                            val activity = context as? android.app.Activity
                                                            if (activity != null) {
                                                                com.swift.browser.videoengine.api.VideoEngineApi.showCustomView(activity, view, callback)
                                                            }
                                                        }
                                                        override fun onHideCustomView() {
                                                            val activity = context as? android.app.Activity
                                                            if (activity != null) {
                                                                com.swift.browser.videoengine.api.VideoEngineApi.hideCustomView(activity)
                                                            }
                                                        }
                                                        override fun onNewTabRequested(url: String, isIncognito: Boolean): WebView? {
                                                            val nTab = viewModel.tabEngine.createTab(url, url, isIncognito)
                                                            viewModel.tabEngine.switchTab(nTab.id)
                                                            return viewModel.tabEngine.getWebView(nTab.id)
                                                        }
                                                    }
                                                )
                                                viewModel.tabEngine.putWebView(activeTab.id, newWebView)
                                                if (activeTab.url != "swift://newtab" && activeTab.url.isNotEmpty()) {
                                                    com.swift.browser.browserengine.BrowserNavigationApi.navigate(
                                                        com.swift.browser.browserengine.NavigationRequest(
                                                            tabId = activeTab.id,
                                                            url = activeTab.url,
                                                            source = com.swift.browser.browserengine.NavigationSource.NEW_TAB,
                                                            applyDesktopPolicy = true,
                                                            webView = newWebView
                                                        )
                                                    )
                                                }
                                                newWebView
                                            }
                                            (wv as? com.swift.browser.browserengine.BrowserWebView)?.let { browserWebView ->
                                                val ownerTabId = activeTab.id
                                                browserWebView.scrollListener = object : com.swift.browser.browserengine.BrowserWebViewScrollListener {
                                                    private fun isCurrentOwner(): Boolean {
                                                        if (browserWebView.isDestroyed) return false
                                                        if (viewModel.tabEngine.activeTabId.value != ownerTabId) return false
                                                        return viewModel.tabEngine.getWebView(ownerTabId) === browserWebView
                                                    }

                                                    override fun onTouchStart() {
                                                        if (isCurrentOwner()) {
                                                            viewModel.scrollChromeController.onTouchStart()
                                                        }
                                                    }

                                                    override fun onWebViewScroll(diffY: Int, isAtTop: Boolean) {
                                                        if (isCurrentOwner()) {
                                                            viewModel.scrollChromeController.onScroll(diffY, isAtTop)
                                                        }
                                                    }

                                                    override fun onTouchRelease() {
                                                        if (isCurrentOwner()) {
                                                            viewModel.scrollChromeController.onTouchRelease()
                                                        }
                                                    }
                                                }
                                            }
                                            activeWebView = wv
                                            wv
                                        },
                                        update = { webView ->
                                            activeWebView = webView
                                        },
                                        onRelease = { webView ->
                                            (webView as? com.swift.browser.browserengine.BrowserWebView)?.scrollListener = null
                                        }
                                    )
                                }
                                        }
                                        }
                                        if (isDevToolsOpen && devToolsDockMode != DevToolsDockMode.RIGHT) {
                                            DeveloperToolsPanel(
                                                frontendUrl = devToolsFrontendUrl,
                                                errorMessage = devToolsLoadError,
                                                isFullscreen = devToolsDockMode == DevToolsDockMode.FULLSCREEN,
                                                isSideDocked = false,
                                                onClose = { isDevToolsOpen = false },
                                                onToggleFullscreen = {
                                                    devToolsDockMode = if (devToolsDockMode == DevToolsDockMode.FULLSCREEN) {
                                                        DevToolsDockMode.BOTTOM
                                                    } else {
                                                        DevToolsDockMode.FULLSCREEN
                                                    }
                                                },
                                                onToggleSideDock = {
                                                    devToolsDockMode = if (devToolsDockMode == DevToolsDockMode.RIGHT) {
                                                        DevToolsDockMode.BOTTOM
                                                    } else {
                                                        DevToolsDockMode.RIGHT
                                                    }
                                                },
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .weight(if (devToolsDockMode == DevToolsDockMode.FULLSCREEN) 1f else 0.5f)
                                            )
                                        }
                                    }
                                }

                            if (isDevToolsOpen && devToolsDockMode == DevToolsDockMode.RIGHT) {
                                DeveloperToolsPanel(
                                    frontendUrl = devToolsFrontendUrl,
                                    errorMessage = devToolsLoadError,
                                    isFullscreen = false,
                                    isSideDocked = true,
                                    onClose = { isDevToolsOpen = false },
                                    onToggleFullscreen = { devToolsDockMode = DevToolsDockMode.FULLSCREEN },
                                    onToggleSideDock = { devToolsDockMode = DevToolsDockMode.BOTTOM },
                                    modifier = Modifier.weight(1f).fillMaxHeight()
                                )
                            }

                            if (extensionUiState.showSidePanel && extensionUiState.activeSidePanelExtension != null && !extensionUiState.activeSidePanelUrl.isNullOrBlank()) {
                                val matchesTab = extensionUiState.activeSidePanelTabId == null || extensionUiState.activeSidePanelTabId == activeTab.id
                                if (matchesTab) {
                                    com.swift.browser.extensionengine.ui.ExtensionSidePanelSurface(
                                        extension = extensionUiState.activeSidePanelExtension!!,
                                        panelUrl = extensionUiState.activeSidePanelUrl!!,
                                        api = extensionApi,
                                        tabId = activeTab.id,
                                        onClose = { extensionApi.closeSidePanel() },
                                        modifier = Modifier.width(340.dp)
                                    )
                                }
                            }
                        }

                            // 2. Normal Chrome toolbar
                            Column(
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .fillMaxWidth()
                                    .onGloballyPositioned { coordinates ->
                                        val height = coordinates.size.height.toFloat()
                                        if (height > 0f && height.isFinite() && kotlin.math.abs(height - topToolbarHeightPx) > 1f) {
                                            topToolbarHeightPx = height
                                            viewModel.scrollChromeController.updateCollapseDistance(height)
                                        }
                                    }
                                    .graphicsLayer {
                                        val progress = hideProgress.coerceIn(0f, 1f)
                                        val h = if (topToolbarHeightPx > 0f && topToolbarHeightPx.isFinite()) topToolbarHeightPx else size.height
                                        translationY = -h * progress
                                    }
                            ) {
                                Surface(
                                    color = MaterialTheme.colorScheme.surface,
                                    contentColor = MaterialTheme.colorScheme.onSurface,
                                    tonalElevation = 4.dp,
                                    shadowElevation = 2.dp,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                            Column {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 4.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // Go Home Button
                                    IconButton(
                                        onClick = { android.os.Handler(android.os.Looper.getMainLooper()).post {
                                            focusManager.clearFocus()
                                            viewModel.goHome()
                                        } },
                                        modifier = Modifier.size(36.dp).testTag("omnibox_home")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Home,
                                            contentDescription = "Go Home",
                                            tint = LocalContentColor.current,
                                            modifier = Modifier.size(22.dp)
                                        )
                                    }

                                    // URL Input Bar
                                    Surface(
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(40.dp)
                                            .padding(horizontal = 4.dp)
                                            .clickable {
                                                urlInputText = if (currentWebUrl.startsWith("swift://") || currentWebUrl.startsWith("intent://") || currentWebUrl == "about:blank") "" else currentWebUrl
                                                isUrlEditing = true
                                                viewModel.scrollChromeController.reset()
                                            },
                                        shape = RoundedCornerShape(20.dp),
                                        color = MaterialTheme.colorScheme.surfaceVariant,
                                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            // Security / Incognito / DevTools Indicator
                                            val isHttps = currentWebUrl.startsWith("https://")
                                            val isPrivateTab = activeTab.isIncognito
                                            val devToolsUrlString = "devtools://swift/developer-tools"
                                            IconButton(
                                                onClick = { android.os.Handler(android.os.Looper.getMainLooper()).post { showSslDialog = true } },
                                                modifier = Modifier.size(20.dp)
                                            ) {
                                                Icon(
                                                    imageVector = if (isDevToolsOpen) Icons.Default.Code else if (isPrivateTab) Icons.Default.Security else if (isHttps) Icons.Default.Lock else Icons.Default.LockOpen,
                                                    contentDescription = if (isDevToolsOpen) "Developer Tools" else if (isPrivateTab) "Private Mode" else "SSL Status",
                                                    tint = if (isDevToolsOpen) Color(0xFF818CF8) else if (isPrivateTab) Color(0xFF90CAF9) else if (isHttps) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.size(14.dp)
                                                )
                                            }

                                            if (isDesktopMode && !isDevToolsOpen) {
                                                Text("🖥", modifier = Modifier.padding(start = 2.dp), fontSize = 12.sp)
                                            }

                                            Spacer(modifier = Modifier.width(6.dp))

                                            val displayUrl = when {
                                                isDevToolsOpen -> devToolsUrlString
                                                currentWebUrl.startsWith("swift://newtab") || currentWebUrl == "about:blank" -> "Search or type URL"
                                                currentWebUrl.startsWith("intent://") -> ""
                                                else -> currentWebUrl.replace("https://", "").replace("http://", "")
                                            }
                                            Text(
                                                text = displayUrl,
                                                style = LocalTextStyle.current.copy(
                                                    color = if (isDevToolsOpen) Color(0xFF818CF8) else if (displayUrl == "Search or type URL") MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f) else MaterialTheme.colorScheme.onSurfaceVariant,
                                                    fontSize = 13.sp
                                                ),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .clickable {
                                                        urlInputText = if (isDevToolsOpen) devToolsUrlString else if (currentWebUrl.startsWith("swift://") || currentWebUrl.startsWith("intent://") || currentWebUrl == "about:blank") "" else currentWebUrl
                                                        isUrlEditing = true
                                                        viewModel.scrollChromeController.reset()
                                                    }
                                            )

                                            // Reader Mode trigger
                                            IconButton(
                                                onClick = { android.os.Handler(android.os.Looper.getMainLooper()).post { isReaderModeOpen = true } },
                                                modifier = Modifier.size(24.dp).testTag("reader_mode_trigger")
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.MenuBook,
                                                    contentDescription = "Reader Mode",
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }

                                            // Bookmark Star toggle
                                            IconButton(
                                                onClick = { android.os.Handler(android.os.Looper.getMainLooper()).post {
                                                    viewModel.toggleBookmarkActive(currentWebUrl, currentWebTitle)
                                                    Toast.makeText(context, if (isPageBookmarked) "Bookmark removed" else "Bookmark saved", Toast.LENGTH_SHORT).show()
                                                } },
                                                modifier = Modifier.size(24.dp).testTag("bookmark_star_toggle")
                                            ) {
                                                Icon(
                                                    imageVector = if (isPageBookmarked) Icons.Default.Star else Icons.Default.StarBorder,
                                                    contentDescription = "Bookmark Page",
                                                    tint = if (isPageBookmarked) Color(0xFFFFD700) else MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        }
                                    }

                                    // AI Button placed beside tab counter and menu
                                    IconButton(
                                        onClick = { android.os.Handler(android.os.Looper.getMainLooper()).post { showOrionAiAssistantBottomSheet = true } },
                                        modifier = Modifier.size(36.dp).testTag("ai_star_btn")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.AutoAwesome,
                                            contentDescription = "Active Page Summary Assistant",
                                            tint = LocalContentColor.current,
                                            modifier = Modifier.size(22.dp)
                                        )
                                    }

                                    // Tab Switcher with tab count badge (Exact Old Circle Badge Design)
                                    Box(
                                        contentAlignment = Alignment.BottomEnd,
                                        modifier = Modifier
                                            .size(36.dp)
                                            .clickable { isTabSwitcherOpen = true }
                                    ) {
                                        IconButton(
                                            onClick = { android.os.Handler(android.os.Looper.getMainLooper()).post { isTabSwitcherOpen = true } },
                                            modifier = Modifier.size(36.dp).testTag("tab_switcher_btn")
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Tab,
                                                contentDescription = "Tabs List",
                                                tint = LocalContentColor.current,
                                                modifier = Modifier.size(22.dp)
                                            )
                                        }
                                        Surface(
                                            modifier = Modifier
                                                .padding(bottom = 2.dp, end = 2.dp)
                                                .size(14.dp),
                                            shape = CircleShape,
                                            color = MaterialTheme.colorScheme.primary
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Text(
                                                    text = totalTabsCount.toString(),
                                                    color = MaterialTheme.colorScheme.onPrimary,
                                                    fontSize = 8.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }
                                    }

                                    // PDF Print Icon
                                    IconButton(
                                        onClick = { android.os.Handler(android.os.Looper.getMainLooper()).post { showPrintPreviewDialog = true } },
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.PictureAsPdf,
                                            contentDescription = "Save Page to PDF",
                                            tint = Color(0xFFF43F5E),
                                            modifier = Modifier.size(22.dp)
                                        )
                                    }

                                    // Extension Toolbar Actions directly on Toolbar
                                    extensionUiState.enabledExtensions.forEach { enabledExt ->
                                        val actionState = extensionApi.getActionState(enabledExt.id, activeTab.id)
                                        val actionTitle = actionState.title.ifBlank { enabledExt.name }
                                        val badgeText = actionState.badgeText

                                        if (actionState.visible) {
                                            val iconFile = remember(enabledExt.id, actionState.icon, activeTab.id) {
                                                extensionApi.getExtensionIconFile(enabledExt, activeTab.id)
                                            }
                                            Box(
                                                 modifier = Modifier.size(36.dp),
                                                 contentAlignment = Alignment.Center
                                             ) {
                                                 IconButton(
                                                     onClick = { android.os.Handler(android.os.Looper.getMainLooper()).post {
                                                         extensionApi.launchExtension(enabledExt.id, activeTab.id)
                                                     } },
                                                     enabled = actionState.enabled,
                                                     modifier = Modifier.size(36.dp).testTag("ext_action_${enabledExt.id}")
                                                 ) {
                                                     if (iconFile != null && iconFile.exists()) {
                                                         AsyncImage(
                                                             model = iconFile,
                                                             contentDescription = actionTitle,
                                                             modifier = Modifier.size(20.dp)
                                                         )
                                                     } else {
                                                         Icon(
                                                             imageVector = com.swift.browser.extensionengine.ui.ExtensionIconMapper.getIconForExtension(enabledExt.id, enabledExt.name),
                                                             contentDescription = actionTitle,
                                                             tint = if (actionState.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                                                             modifier = Modifier.size(20.dp)
                                                         )
                                                     }
                                                 }
                                                 if (badgeText.isNotBlank()) {
                                                     val bgParsed = try {
                                                         Color(android.graphics.Color.parseColor(actionState.badgeBackgroundColor))
                                                     } catch (_: Throwable) {
                                                         MaterialTheme.colorScheme.primary
                                                     }
                                                     val txtParsed = try {
                                                         Color(android.graphics.Color.parseColor(actionState.badgeTextColor))
                                                     } catch (_: Throwable) {
                                                         Color.White
                                                     }
                                                     Surface(
                                                         modifier = Modifier
                                                             .align(Alignment.TopEnd)
                                                             .padding(top = 2.dp, end = 2.dp),
                                                         shape = RoundedCornerShape(6.dp),
                                                         color = bgParsed
                                                     ) {
                                                         Text(
                                                             text = badgeText,
                                                             fontSize = 8.sp,
                                                             fontWeight = FontWeight.Bold,
                                                             color = txtParsed,
                                                             modifier = Modifier.padding(horizontal = 3.dp, vertical = 1.dp)
                                                         )
                                                     }
                                                 }
                                             }
                                         }
                                     }

                                    // 3-Dot Options Menu
                                    Box(modifier = Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                                        IconButton(
                                            onClick = { android.os.Handler(android.os.Looper.getMainLooper()).post { showBrowserMenu = true } },
                                            modifier = Modifier.size(36.dp).testTag("menu_nav_btn")
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.MoreVert,
                                                contentDescription = "Options Menu",
                                                tint = LocalContentColor.current,
                                                modifier = Modifier.size(22.dp)
                                            )
                                        }

                                        DropdownMenu(
                                            expanded = showBrowserMenu,
                                            onDismissRequest = { showBrowserMenu = false }
                                        ) {
                                            // Top quick row: Back, Forward, Refresh/Stop, Bookmark, Site Info
                                            Row(
                                                modifier = Modifier
                                                    .width(240.dp)
                                                    .padding(horizontal = 8.dp, vertical = 2.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                IconButton(
                                                    onClick = { android.os.Handler(android.os.Looper.getMainLooper()).post {
                                                        launchMenuAction {
                                                            val wv = activeWebView
                                                            if (wv != null && !wv.isDestroyed && wv.canGoBack()) {
                                                                wv.safeGoBack()
                                                            } else if (!isNewTab) {
                                                                activeWebView?.stopLoading()
                                                                viewModel.tabEngine.updateTab(activeTab.id) { it.copy(url = "swift://newtab", title = "New Tab") }
                                                            }
                                                        }
                                                    } },
                                                    enabled = canGoBackInWebView || !isNewTab
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                                        contentDescription = "Back",
                                                        tint = if (canGoBackInWebView || !isNewTab) LocalContentColor.current else LocalContentColor.current.copy(alpha = 0.3f)
                                                    )
                                                }
                                                IconButton(
                                                    onClick = { android.os.Handler(android.os.Looper.getMainLooper()).post {
                                                        launchMenuAction {
                                                            activeWebView?.goForward()
                                                        }
                                                    } },
                                                    enabled = activeWebView?.canGoForward() == true
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                                        contentDescription = "Forward",
                                                        tint = if (activeWebView?.canGoForward() == true) LocalContentColor.current else LocalContentColor.current.copy(alpha = 0.3f)
                                                    )
                                                }
                                                IconButton(
                                                    onClick = { android.os.Handler(android.os.Looper.getMainLooper()).post {
                                                        launchMenuAction {
                                                            if (webProgress in 1..99) {
                                                                activeWebView?.stopLoading()
                                                            } else {
                                                                activeWebView?.reload()
                                                            }
                                                        }
                                                    } }
                                                ) {
                                                    Icon(
                                                        imageVector = if (webProgress in 1..99) Icons.Default.Close else Icons.Default.Refresh,
                                                        contentDescription = if (webProgress in 1..99) "Stop" else "Refresh"
                                                    )
                                                }
                                                IconButton(
                                                    onClick = { android.os.Handler(android.os.Looper.getMainLooper()).post {
                                                        launchMenuAction {
                                                            viewModel.toggleBookmarkActive(currentWebUrl, currentWebTitle)
                                                            Toast.makeText(context, if (isPageBookmarked) "Bookmark removed" else "Bookmark saved", Toast.LENGTH_SHORT).show()
                                                        }
                                                    } }
                                                ) {
                                                    Icon(
                                                        imageVector = if (isPageBookmarked) Icons.Default.Star else Icons.Default.StarBorder,
                                                        contentDescription = "Bookmark",
                                                        tint = if (isPageBookmarked) Color(0xFFFFD700) else LocalContentColor.current
                                                    )
                                                }
                                                IconButton(
                                                    onClick = { android.os.Handler(android.os.Looper.getMainLooper()).post {
                                                        showBrowserMenu = false
                                                        showSslDialog = true
                                                    } }
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.Info,
                                                        contentDescription = "Info"
                                                    )
                                                }
                                            }

                                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                                            DropdownMenuItem(
                                                text = { Text("New tab") },
                                                leadingIcon = { Icon(Icons.Default.Add, contentDescription = null) },
                                                onClick = { android.os.Handler(android.os.Looper.getMainLooper()).post {
                                                    showBrowserMenu = false
                                                    viewModel.addNewTab("swift://newtab")
                                                } }
                                            )

                                            DropdownMenuItem(
                                                text = { Text("New private tab") },
                                                leadingIcon = { Icon(Icons.Default.VisibilityOff, contentDescription = null) },
                                                onClick = { android.os.Handler(android.os.Looper.getMainLooper()).post {
                                                    launchMenuAction {
                                                        val privateEngine = com.swift.browser.privatemode.PrivateModeEngineProvider.getEngine(context)
                                                        val session = privateEngine.startPrivateSession()
                                                        val tab = viewModel.tabEngine.createTab("swift://newtab", "Private Tab", isIncognito = true)
                                                        privateEngine.registerPrivateTab(tab.id, session.id)
                                                    }
                                                } }
                                            )

                                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                                            DropdownMenuItem(
                                                text = { Text("History") },
                                                leadingIcon = { Icon(Icons.Default.History, contentDescription = null) },
                                                onClick = { android.os.Handler(android.os.Looper.getMainLooper()).post {
                                                    showBrowserMenu = false
                                                    isHistoryOpen = true
                                                } }
                                            )

                                            DropdownMenuItem(
                                                text = { Text("Bookmarks") },
                                                leadingIcon = { Icon(Icons.Default.Star, contentDescription = null) },
                                                onClick = { android.os.Handler(android.os.Looper.getMainLooper()).post {
                                                    showBrowserMenu = false
                                                    isBookmarksOpen = true
                                                } }
                                            )

                                            DropdownMenuItem(
                                                text = { Text("Downloads") },
                                                leadingIcon = { Icon(Icons.Default.Download, contentDescription = null) },
                                                onClick = { android.os.Handler(android.os.Looper.getMainLooper()).post {
                                                    showBrowserMenu = false
                                                    isDownloadsOpen = true
                                                } }
                                            )

                                            DropdownMenuItem(
                                                text = { Text("Web Notifications") },
                                                leadingIcon = { Icon(Icons.Default.Notifications, contentDescription = null) },
                                                onClick = { android.os.Handler(android.os.Looper.getMainLooper()).post {
                                                    showBrowserMenu = false
                                                    showWebNotificationsDialog = true
                                                } }
                                            )

                                            DropdownMenuItem(
                                                text = { Text("Recent tabs") },
                                                leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null) },
                                                onClick = { android.os.Handler(android.os.Looper.getMainLooper()).post {
                                                    showBrowserMenu = false
                                                    showRecentTabsDialog = true
                                                } }
                                            )

                                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                                            DropdownMenuItem(
                                                text = { Text("Find in page") },
                                                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                                                onClick = { android.os.Handler(android.os.Looper.getMainLooper()).post {
                                                    launchMenuAction {
                                                        isFindInPageOpen = true
                                                        findInPageEngine.toggleFindInPage(true, activeWebView)
                                                    }
                                                } }
                                            )

                                            DropdownMenuItem(
                                                text = { Text("Add to Home screen") },
                                                leadingIcon = { Icon(Icons.Default.Launch, contentDescription = null) },
                                                onClick = { android.os.Handler(android.os.Looper.getMainLooper()).post {
                                                    showBrowserMenu = false
                                                    showAddShortcutDialog = true
                                                } }
                                            )

                                            DropdownMenuItem(
                                                text = { Text("Translate...") },
                                                leadingIcon = { Icon(Icons.Default.Translate, contentDescription = null) },
                                                onClick = { android.os.Handler(android.os.Looper.getMainLooper()).post {
                                                    launchMenuAction {
                                                        viewModel.translateEngine.triggerTranslationSelection(activeWebView, activeTab.id, currentWebUrl)
                                                    }
                                                } }
                                            )

                                            DropdownMenuItem(
                                                text = { Text("AI Page Assistant") },
                                                leadingIcon = { Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = Color(0xFF818CF8)) },
                                                onClick = { android.os.Handler(android.os.Looper.getMainLooper()).post {
                                                    showBrowserMenu = false
                                                    showOrionAiAssistantBottomSheet = true
                                                } }
                                            )

                                            DropdownMenuItem(
                                                text = { Text("Print") },
                                                leadingIcon = { Icon(Icons.Default.Print, contentDescription = null) },
                                                onClick = { android.os.Handler(android.os.Looper.getMainLooper()).post {
                                                    launchMenuAction {
                                                        showPrintPreviewDialog = true
                                                    }
                                                } }
                                            )

                                            DropdownMenuItem(
                                                text = { Text("Orion VPN") },
                                                leadingIcon = { Icon(Icons.Default.VpnKey, contentDescription = null, tint = Color(0xFF10B981)) },
                                                onClick = { android.os.Handler(android.os.Looper.getMainLooper()).post {
                                                    launchMenuAction {
                                                        VpnEngineProvider.api.launchVpnUi(context)
                                                    }
                                                } }
                                            )

                                            DropdownMenuItem(
                                                text = { Text(if (isDevToolsOpen) "Close Developer Tools" else "Developer Tools") },
                                                leadingIcon = { Icon(Icons.Default.Code, contentDescription = null, tint = Color(0xFF6366F1)) },
                                                onClick = {
                                                    launchMenuAction { toggleDeveloperTools() }
                                                }
                                            )

                                            DropdownMenuItem(
                                                text = { Text("Extensions Management") },
                                                leadingIcon = { Icon(Icons.Default.Extension, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                                                onClick = { android.os.Handler(android.os.Looper.getMainLooper()).post {
                                                    launchMenuAction {
                                                        extensionApi.openManagerUi()
                                                    }
                                                } }
                                            )

                                            extensionUiState.enabledExtensions.forEach { enabledExt ->
                                                val actionState = extensionApi.getActionState(enabledExt.id, activeTabId)
                                                val actionTitle = actionState.title.ifBlank { enabledExt.name }
                                                val badgeText = actionState.badgeText

                                                DropdownMenuItem(
                                                    text = {
                                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                                            Text(actionTitle, modifier = Modifier.weight(1f, fill = false))
                                                            if (badgeText.isNotBlank()) {
                                                                Spacer(modifier = Modifier.width(6.dp))
                                                                Surface(
                                                                    shape = RoundedCornerShape(8.dp),
                                                                    color = MaterialTheme.colorScheme.primaryContainer
                                                                ) {
                                                                    Text(
                                                                        text = badgeText,
                                                                        fontSize = 10.sp,
                                                                        fontWeight = FontWeight.Bold,
                                                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                                    )
                                                                }
                                                            }
                                                        }
                                                    },
                                                    leadingIcon = {
                                                        Icon(
                                                            imageVector = com.swift.browser.extensionengine.ui.ExtensionIconMapper.getIconForExtension(enabledExt.id, enabledExt.name),
                                                            contentDescription = null,
                                                            tint = MaterialTheme.colorScheme.primary
                                                        )
                                                    },
                                                    onClick = { android.os.Handler(android.os.Looper.getMainLooper()).post {
                                                        launchMenuAction {
                                                            extensionApi.launchExtension(enabledExt.id, activeTabId)
                                                        }
                                                    } }
                                                )
                                            }

                                            DropdownMenuItem(
                                                text = { Text("Listen to this page") },
                                                leadingIcon = { Icon(Icons.Default.VolumeUp, contentDescription = null) },
                                                onClick = { android.os.Handler(android.os.Looper.getMainLooper()).post {
                                                    launchMenuAction {
                                                        viewModel.startListeningToPageText(context, activeWebView)
                                                    }
                                                } }
                                            )

                                            DropdownMenuItem(
                                                text = { Text("Show Reading mode") },
                                                leadingIcon = { Icon(Icons.Default.ChromeReaderMode, contentDescription = null) },
                                                onClick = { android.os.Handler(android.os.Looper.getMainLooper()).post {
                                                    showBrowserMenu = false
                                                    isReaderModeOpen = true
                                                } }
                                            )

                                            DropdownMenuItem(
                                                text = { Text("Desktop site") },
                                                leadingIcon = { Icon(Icons.Default.Devices, contentDescription = null) },
                                                trailingIcon = {
                                                    Checkbox(
                                                        checked = isDesktopMode,
                                                        onCheckedChange = null
                                                    )
                                                },
                                                onClick = { android.os.Handler(android.os.Looper.getMainLooper()).post {
                                                    launchMenuAction {
                                                        DesktopEngineProvider.api.toggleForSite(activeTab.id, currentWebUrl, context, activeWebView)
                                                    }
                                                } }
                                            )

                                            DropdownMenuItem(
                                                text = { Text("Share page") },
                                                leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) },
                                                onClick = { android.os.Handler(android.os.Looper.getMainLooper()).post {
                                                    launchMenuAction {
                                                        val sendIntent = Intent().apply {
                                                            action = Intent.ACTION_SEND
                                                            putExtra(Intent.EXTRA_TEXT, currentWebUrl)
                                                            type = "text/plain"
                                                        }
                                                        context.startActivity(Intent.createChooser(sendIntent, "Share Webpage"))
                                                    }
                                                } }
                                            )

                                            DropdownMenuItem(
                                                text = { Text("Delete browsing data") },
                                                leadingIcon = { Icon(Icons.Default.DeleteForever, contentDescription = null) },
                                                onClick = { android.os.Handler(android.os.Looper.getMainLooper()).post {
                                                    showBrowserMenu = false
                                                    showClearDataDialog = true
                                                } }
                                            )

                                            DropdownMenuItem(
                                                text = { Text("Help & feedback") },
                                                leadingIcon = { Icon(Icons.Default.Help, contentDescription = null) },
                                                onClick = { android.os.Handler(android.os.Looper.getMainLooper()).post {
                                                    showBrowserMenu = false
                                                    showHelpFeedbackDialog = true
                                                } }
                                            )

                                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                                            DropdownMenuItem(
                                                text = { Text("Settings") },
                                                leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null) },
                                                onClick = { android.os.Handler(android.os.Looper.getMainLooper()).post {
                                                    launchMenuAction {
                                                        context.startActivity(Intent(context, com.swift.browser.settingsengine.AdvancedSettingsActivity::class.java))
                                                    }
                                                } }
                                            )
                                        }
                                    }
                                }

                                // Web Loader linear progress indicator
                                if (webProgress in 1..99) {
                                    LinearProgressIndicator(
                                        progress = { webProgress / 100f },
                                        modifier = Modifier.fillMaxWidth().height(2.dp),
                                        color = MaterialTheme.colorScheme.primary,
                                        trackColor = Color.Transparent
                                    )
                                } else {
                                    Spacer(modifier = Modifier.height(2.dp))
                                }
                            }
                        }



                        // Find in Page Bar
                        if (isFindInPageOpen) {
                            Surface(
                                color = Color(0xFF1E293B),
                                border = BorderStroke(1.dp, Color(0xFF334155)),
                                modifier = Modifier.fillMaxWidth().height(48.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    androidx.compose.foundation.text.BasicTextField(
                                        value = findInPageQuery,
                                        onValueChange = {
                                            findInPageQuery = it
                                            findInPageEngine.search(it, activeWebView)
                                        },
                                        singleLine = true,
                                        textStyle = androidx.compose.ui.text.TextStyle(
                                            color = Color.White,
                                            fontSize = 13.sp
                                        ),
                                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                                    )

                                    Text(
                                        text = "${findInPageState.currentMatch}/${findInPageState.totalMatches}",
                                        color = Color(0xFF94A3B8),
                                        fontSize = 12.sp
                                    )

                                    IconButton(
                                        onClick = { android.os.Handler(android.os.Looper.getMainLooper()).post { findInPageEngine.findNext(false, activeWebView) } },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Previous match", tint = Color.White)
                                    }

                                    IconButton(
                                        onClick = { android.os.Handler(android.os.Looper.getMainLooper()).post { findInPageEngine.findNext(true, activeWebView) } },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Next match", tint = Color.White)
                                    }

                                    IconButton(
                                        onClick = { android.os.Handler(android.os.Looper.getMainLooper()).post {
                                            isFindInPageOpen = false
                                            findInPageEngine.toggleFindInPage(false, activeWebView)
                                        } },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(Icons.Default.Close, contentDescription = "Close search", tint = Color.White)
                                    }
                                }
                            }
                        }

                                // Translate Bar
                                if (isTranslateBarOpen || translateUiState.isVisible) {
                                    com.swift.browser.translateengine.ui.TranslateEngineUi(
                                        engine = viewModel.translateEngine,
                                        isDesktopMode = isDesktopMode,
                                        activeWebView = activeWebView,
                                        tabId = activeTab.id,
                                        currentUrl = currentWebUrl
                                    )
                                }
                            }

                            // Floating Extension Action Button (positioned neatly right above the download FAB)
                            com.swift.browser.extensionengine.ui.ExtensionChromeStoreFloatingButton(
                                currentUrl = currentWebUrl,
                                api = extensionApi,
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(end = 16.dp, bottom = 128.dp)
                            )

                            FloatingActionButton(
                                onClick = { android.os.Handler(android.os.Looper.getMainLooper()).post { showMediaDetectedDialog = true } },
                                containerColor = Color(0xFFFF7A59),
                                contentColor = Color.White,
                                shape = CircleShape,
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(end = 16.dp, bottom = 72.dp)
                                    .size(48.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.FileDownload,
                                    contentDescription = "Fast Download Detected Media",
                                    modifier = Modifier.size(24.dp)
                                )
                            }

                            // Biometric Lock Guard for Active Private Tab Canvas
                            if ((activeTab.isIncognito || activeTab.isPrivate) && !isBiometricUnlocked) {
                                com.swift.browser.privatemode.ui.PrivateTabsBiometricLockView(
                                    modifier = Modifier.fillMaxSize(),
                                    engine = viewModel.privateModeEngine,
                                    onAuthenticated = {
                                        // Biometric authentication unlocked private mode
                                    },
                                    onCloseAllPrivateTabs = {
                                        viewModel.tabEngine.groups.value.flatMap { it.tabs }.filter { it.isIncognito || it.isPrivate }.forEach {
                                            viewModel.closeTab(it.id)
                                        }
                                    }
                                )
                            }

                            // 3. Bottom Chrome Overlay
                            val allTabs = groupsList.flatMap { it.tabs }

                            if (shouldShowBottom) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .fillMaxWidth()
                                        .onGloballyPositioned { coordinates ->
                                            val h = coordinates.size.height.toFloat()
                                            if (h > 0f && h.isFinite() && kotlin.math.abs(h - bottomToolbarHeightPx) > 1f) {
                                                bottomToolbarHeightPx = h
                                            }
                                        }
                                        .graphicsLayer {
                                            val progress = hideProgress.coerceIn(0f, 1f)
                                            val h = if (bottomToolbarHeightPx > 0f && bottomToolbarHeightPx.isFinite()) bottomToolbarHeightPx else size.height
                                            translationY = h * progress
                                        }
                                ) {
                                    com.swift.browser.tabengine.ui.BottomTabStripLayout(
                                        tabs = allTabs,
                                        activeTabId = activeTab.id,
                                        onTabSelect = { tabId ->
                                            viewModel.tabEngine.switchTab(tabId)
                                        },
                                        onTabClose = { tabId ->
                                            viewModel.tabEngine.closeTab(tabId)
                                        },
                                        onNewTab = {
                                            val nTab = viewModel.tabEngine.createTab("swift://newtab", "New Tab", false, activeTab.groupId)
                                            viewModel.tabEngine.switchTab(nTab.id)
                                        },
                                        onOpenTabSwitcher = {
                                            isTabSwitcherOpen = true
                                        },
                                        onVoiceClick = {
                                            try {
                                                val intent = Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                                    putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH)
                                                    putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Speak to search...")
                                                }
                                                speechRecognizerLauncher.launch(intent)
                                            } catch (e: Exception) {
                                                Toast.makeText(context, "Voice recognition not available", Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        isGlass = false,
                                        applyNavigationPadding = !isNewTab
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // High-Performance Animated Tab Switcher Overlay
        androidx.compose.animation.AnimatedVisibility(
            visible = isTabSwitcherOpen,
            enter = com.swift.browser.tabengine.animation.TabAnimationTransitions.tabSwitcherEnter,
            exit = com.swift.browser.tabengine.animation.TabAnimationTransitions.tabSwitcherExit
        ) {
            com.swift.browser.tabengine.ui.TabSwitcherLayout(
                groups = groupsList,
                activeGroupId = activeTabGroup?.id,
                activeTabId = activeTab.id,
                onGroupSelected = { groupId -> viewModel.tabEngine.switchGroup(groupId) },
                onNewGroup = { viewModel.createNewTabGroup("New Group", false) },
                onTabSelected = { tabId ->
                    viewModel.tabEngine.switchTab(tabId)
                    isTabSwitcherOpen = false
                },
                onTabClosed = { tabId -> viewModel.closeTab(tabId) },
                onNewTab = { isIncognito ->
                    viewModel.tabEngine.createTab("swift://newtab", if (isIncognito) "Incognito Tab" else "New Tab", isIncognito = isIncognito)
                    isTabSwitcherOpen = false
                },
                onCloseSwitcher = { isTabSwitcherOpen = false },
                isPrivateUnlocked = isBiometricUnlocked,
                onAuthenticateBiometric = {
                    val activity = (context as? androidx.fragment.app.FragmentActivity)
                        ?: (context as? android.content.ContextWrapper)?.let {
                            var ctx: android.content.Context? = it
                            while (ctx is android.content.ContextWrapper) {
                                if (ctx is androidx.fragment.app.FragmentActivity) break
                                ctx = ctx.baseContext
                            }
                            ctx as? androidx.fragment.app.FragmentActivity
                        }
                    if (activity != null) {
                        viewModel.authenticatePrivateTabs(activity)
                    } else {
                        viewModel.unlockPrivateTabs()
                    }
                },
                onCloseAllPrivateTabs = {
                    viewModel.tabEngine.groups.value.flatMap { it.tabs }.filter { it.isIncognito || it.isPrivate }.forEach {
                        viewModel.closeTab(it.id)
                    }
                }
            )
        }

        // Context Menu for Long-Pressed Links & Images
        WebContextMenuBottomSheet(
            state = contextMenuState,
            onDismiss = { viewModel.dismissContextMenu() },
            onOpenInNewTab = { url ->
                viewModel.addNewTab(url)
            },
            onOpenInNewTabGroup = { url ->
                viewModel.openInNewTabInGroup(url, contextMenuState.tabId ?: activeTab?.id)
            },
            onOpenInIncognito = { url ->
                val newTab = viewModel.tabEngine.createTab(url, url, isIncognito = true)
                viewModel.tabEngine.switchTab(newTab.id)
            },
            onDownloadLink = { url ->
                Toast.makeText(context, "Starting download...", Toast.LENGTH_SHORT).show()
                viewModel.downloadActiveFile(url)
            },
            onAddToReadingList = { url, title ->
                // ReaderEngine / Reading list hook
            }
        )

        // Global About App Dialog
        AboutAppDialog(
            show = isAboutDialogOpen,
            onDismiss = { isAboutDialogOpen = false }
        )

        // Add to Home Screen Shortcut Dialog
        AddShortcutDialog(
            show = showAddShortcutDialog,
            url = currentWebUrl,
            title = currentWebTitle,
            onDismiss = { showAddShortcutDialog = false },
            onConfirm = { name, url ->
                ShortcutUtils.pinWebpageShortcut(context, url, name, currentFavicon)
                Toast.makeText(context, "Shortcut added to home screen", Toast.LENGTH_SHORT).show()
            }
        )

        // Clear Browsing Data Dialog
        ClearBrowsingDataDialog(
            show = showClearDataDialog,
            onDismiss = { showClearDataDialog = false },
            onConfirm = { clearHistory, clearCookies, clearCache, clearDownloads ->
                viewModel.clearBrowsingData(clearHistory, clearCookies, clearCache, clearDownloads, context)
                Toast.makeText(context, "Browsing data cleared", Toast.LENGTH_SHORT).show()
            }
        )

        // Recent Tabs Dialog
        RecentTabsDialog(
            show = showRecentTabsDialog,
            recentTabs = groupsList.flatMap { it.tabs },
            onDismiss = { showRecentTabsDialog = false },
            onTabSelected = { tab ->
                viewModel.tabEngine.switchTab(tab.id)
            }
        )

        // Help & Feedback Dialog
        HelpFeedbackDialog(
            show = showHelpFeedbackDialog,
            onDismiss = { showHelpFeedbackDialog = false }
        )

        // Extension Engine UI Overlay Host
        com.swift.browser.extensionengine.ui.ExtensionEngineUi(api = extensionApi)

        // Extensions Dialog
        ExtensionsManagementDialog(
            show = showExtensionsDialog,
            onDismiss = { showExtensionsDialog = false }
        )


        // Web Notifications Host (Delegated to :notification-engine)
        if (showWebNotificationsDialog) {
            com.swift.browser.notificationengine.api.NotificationEngineProvider.api.NotificationCenterUi(
                onBack = { showWebNotificationsDialog = false },
                onOpenUrl = { url ->
                    viewModel.addNewTab(url)
                    showWebNotificationsDialog = false
                },
                modifier = Modifier
            )
        }

        // Canonical Site Info Bottom Sheet
        SiteInfoBottomSheet(
            show = showSslDialog,
            url = currentWebUrl,
            title = currentWebTitle,
            onDismiss = { showSslDialog = false },
            onOpenSiteSettings = {
                showSslDialog = false
            }
        )

        // Media Resource Detected Bottom Sheet (Matching video 00:20-00:30)
        MediaResourceDetectedDialog(
            show = showMediaDetectedDialog,
            url = currentWebUrl,
            onDismiss = { showMediaDetectedDialog = false },
            onDownload = { fileName, threads ->
                viewModel.downloadActiveFile(currentWebUrl, "video/mp4", "")
            }
        )

        // Orion AI Assistant Bottom Sheet (Matching video 00:40)
        OrionAiAssistantBottomSheet(
            show = showOrionAiAssistantBottomSheet,
            pageTitle = currentWebTitle,
            pageUrl = currentWebUrl,
            onDismiss = { showOrionAiAssistantBottomSheet = false }
        )

        // Print Preview Dialog (Matching video 00:50)
        PrintPreviewDialog(
            show = showPrintPreviewDialog,
            title = currentWebTitle,
            url = currentWebUrl,
            onDismiss = { showPrintPreviewDialog = false },
            onPrint = {
                PrintEngine.printCurrentPage(context, activeWebView)
            }
        )

        // Canonical Single Authority Website Permission Dialog
        PermissionPromptDialog()
    }
}
