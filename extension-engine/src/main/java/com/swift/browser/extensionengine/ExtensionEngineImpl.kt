package com.swift.browser.extensionengine

import android.content.Context
import android.net.Uri
import android.webkit.WebView
import java.io.File
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.swift.browser.tabengine.api.TabEngineApi
import com.swift.browser.tabengine.api.TabEngineProvider
import com.swift.browser.extensionengine.resources.ExtensionResourceResolver

class ExtensionEngineImpl(
    private val context: Context,
    initialDelegate: BrowserDelegate?
) : ExtensionEngine {

    val activeTabManager = ActiveTabManager(initialDelegate)

    var delegate: BrowserDelegate? = initialDelegate
        set(value) {
            field = value
            activeTabManager.delegate = value
        }

    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    // Extension installs/updates are serialized to prevent concurrent staging/registry/UI races.
    private val installMutex = Mutex()

    /** Single browser-core engine graph shared by extension adapters and browser UI. */
    val coreServices: BrowserCoreServices by lazy {
        BrowserCoreServices.get(context, ioScope)
    }

    val tabEngineApi: TabEngineApi by lazy { coreServices.tabs }

    val database = ExtensionDatabase.getInstance(context)
    val registry: ExtensionRegistry by lazy { ExtensionRegistryImpl(
        bookmarkEngine = coreServices.bookmarks,
        historyEngine = coreServices.history,
        tabEngine = coreServices.tabs
    ) }
    val parser = ManifestParser()
    val loader = ExtensionLoader(context, parser, database, registryProvider = { registry })
    val permissionAdapter = ExtensionPermissionAdapter(context)
    val permissionManager = PermissionManager(context, permissionAdapter)
    // Canonical extension storage is intentionally isolated from browser-global preferences.
    // Registry metadata and extension storage share the existing ExtensionDatabase, but the
    // capability boundary remains explicit so no second browser storage engine is introduced.
    val storageManager = StorageManager(database, context, registry)
    val storageBackend = ExtensionStorageBackend(storageManager)
    val messageBus = MessageBus()
    val eventManager = EventManager(messageBus, context)
    val dnrAdapter = ExtensionDnrAdapter(
        permissionAdapter = permissionAdapter,
        registry = registry,
        ruleStore = ExtensionDnrRuleStore(context)
    )
    val webRequestAdapter = ExtensionWebRequestAdapter(permissionAdapter, registry, eventManager, dnrAdapter)
    val popupManager = PopupManager()
        val scriptInjector = ScriptInjector()
    val cssInjector = CssInjector()
    val worldAwareScriptExecutor = WorldAwareScriptExecutor()
    val contentScriptManager = ContentScriptManager(
        context = context,
        permissionManager = permissionManager,
        scriptInjector = scriptInjector,
        cssInjector = cssInjector,
        extensionRegistry = registry,
        generationLookup = { id -> registry.getExtensionGeneration(id) }
    )
    val backgroundScriptManager = BackgroundScriptManager(context, scriptInjector, messageBus)
    val powerAdapter = ExtensionPowerAdapter(context, permissionManager, registry)

    val portManager = PortManager(messageBus)

    val tabsAdapter by lazy {
        ExtensionTabsAdapter(
            tabEngine = tabEngineApi,
            registry = registry,
            permissionManager = permissionManager,
            messageBus = messageBus,
            portManager = portManager,
            eventManager = eventManager
        )
    }

    val windowsAdapter by lazy {
        ExtensionWindowsAdapter(
            tabEngine = tabEngineApi,
            tabsAdapter = tabsAdapter,
            registry = registry,
            permissionManager = permissionManager
        )
    }

    val tabGroupsAdapter by lazy {
        ExtensionTabGroupsAdapter(
            tabEngine = tabEngineApi,
            registry = registry,
            permissionManager = permissionManager
        )
    }

    val sessionsAdapter by lazy {
        ExtensionSessionsAdapter(
            context = context,
            tabEngine = tabEngineApi,
            tabsAdapter = tabsAdapter,
            registry = registry,
            permissionManager = permissionManager
        )
    }
    val cookieAdapter by lazy {
        ExtensionCookieAdapter(
            context = context,
            permissionAdapter = permissionAdapter,
            registry = registry,
            eventManager = eventManager,
            tabEngine = tabEngineApi
        )
    }

    val bookmarksAdapter by lazy {
        ExtensionBookmarksAdapter(
            context = context,
            permissionManager = permissionManager,
            registry = registry,
            eventManager = eventManager,
            bookmarkApi = coreServices.bookmarks
        )
    }

    val historyAdapter by lazy {
        ExtensionHistoryAdapter(
            permissionManager = permissionManager,
            registry = registry,
            eventManager = eventManager,
            historyApi = coreServices.history
        )
    }

    val downloadsAdapter by lazy {
        ExtensionDownloadsAdapter(
            context = context,
            permissionManager = permissionManager,
            registry = registry,
            eventManager = eventManager,
            downloadEngine = coreServices.downloads
        )
    }

    val scriptingAdapter by lazy {
        ExtensionScriptingAdapter(
            permissionAdapter = permissionAdapter,
            registry = registry,
            contentScriptManager = contentScriptManager,
            scriptInjector = scriptInjector,
            cssInjector = cssInjector,
            worldAwareScriptExecutor = worldAwareScriptExecutor
        )
    }

    val actionAdapter by lazy {
        ExtensionActionAdapter(
            permissionManager = permissionManager,
            registry = registry,
            eventManager = eventManager,
            context = context
        )
    }

    val contextMenusAdapter by lazy {
        ExtensionContextMenusAdapter(
            permissionManager = permissionManager,
            registry = registry,
            eventManager = eventManager
        )
    }

    val commandsAdapter by lazy {
        ExtensionCommandsAdapter(
            permissionManager = permissionManager,
            registry = registry,
            eventManager = eventManager
        )
    }

    val omniboxAdapter by lazy {
        ExtensionOmniboxAdapter(
            permissionManager = permissionManager,
            registry = registry,
            eventManager = eventManager
        )
    }

    val sidePanelAdapter by lazy {
        ExtensionSidePanelAdapter(
            permissionManager = permissionManager,
            registry = registry
        )
    }

    val managementAdapter by lazy {
        ExtensionManagementAdapter(
            permissionManager = permissionManager,
            registry = registry,
            eventManager = eventManager
        )
    }

    val topSitesAdapter by lazy {
        ExtensionTopSitesAdapter(
            permissionManager = permissionManager,
            registry = registry
        )
    }

    val idleAdapter by lazy {
        ExtensionIdleAdapter(
            permissionManager = permissionManager,
            registry = registry
        )
    }

    val ttsAdapter by lazy {
        ExtensionTtsAdapter(
            permissionManager = permissionManager,
            registry = registry
        )
    }

    val searchAdapter by lazy {
        ExtensionSearchAdapter(
            permissionManager = permissionManager,
            registry = registry
        )
    }

    val alarmsAdapter by lazy {
        ExtensionAlarmsAdapter(
            permissionManager = permissionManager,
            registry = registry,
            eventManager = eventManager
        ).also {
            it.serviceWorkerEventDispatcher = swEventDispatcher
        }
    }

    val systemAdapter by lazy {
        ExtensionSystemAdapter(
            permissionManager = permissionManager,
            registry = registry
        )
    }

    val notificationsAdapter by lazy {
        ExtensionNotificationsAdapter(
            permissionManager = permissionManager,
            registry = registry,
            eventManager = eventManager
        )
    }

    val webNavigationAdapter by lazy {
        ExtensionWebNavigationAdapter(
            permissionAdapter = permissionAdapter,
            registry = registry,
            eventManager = eventManager,
            tabEngine = tabEngineApi
        )
    }

    val privacyAdapter by lazy {
        ExtensionPrivacyAdapter(
            context = context,
            permissionManager = permissionManager,
            registry = registry
        )
    }

    val contentSettingsAdapter by lazy {
        ExtensionContentSettingsAdapter(
            context = context,
            permissionManager = permissionManager,
            registry = registry
        )
    }

    val userScriptsAdapter by lazy {
        ExtensionUserScriptsAdapter(
            context = context,
            permissionManager = permissionManager,
            registry = registry,
            worldAwareScriptExecutor = worldAwareScriptExecutor
        )
    }

    val browsingDataAdapter by lazy {
        ExtensionBrowsingDataAdapter(
            context = context,
            permissionManager = permissionManager,
            registry = registry,
            history = coreServices.history,
            bookmarks = coreServices.bookmarks,
            downloads = coreServices.downloads
        )
    }

    val offscreenAdapter by lazy {
        ExtensionOffscreenAdapter(
            context = context,
            permissionManager = permissionManager,
            registry = registry
        )
    }

    val debuggerAdapter by lazy {
        com.swift.browser.extensionengine.chromedebugger.ChromeDebuggerAdapter(
            tabEngine = tabEngineApi,
            registry = registry,
            permissionManager = permissionManager,
            permissionAdapter = permissionAdapter,
            eventManager = eventManager
        )
    }

    val resourceResolver by lazy {
        ExtensionResourceResolver(context, registry, permissionManager)
    }
    val tabMessenger = TabMessenger(messageBus)
    val tabBridge = TabBridge(tabMessenger, activeTabManager, portManager)
    val domBridge = DomBridge()
    val postMessageBridge = PostMessageBridge()

    val swJsRuntime = ServiceWorkerJsRuntime(context, scriptInjector, messageBus)
    val webViewBackend = WebViewServiceWorkerBackend(swJsRuntime)
    val v8Backend = V8ServiceWorkerBackend(context)
    val backendSelector = ServiceWorkerBackendSelector(context, webViewBackend, v8Backend)

    val swLifecycleEventStore = ServiceWorkerLifecycleEventStore(context)
    val swRegistry = ServiceWorkerRegistry(context, registry).also {
        it.setLifecycleEventStore(swLifecycleEventStore)
    }
    val mv3LifecyclePolicy = Mv3ServiceWorkerLifecycleCoordinator(swRegistry)
    val swWakeController = ServiceWorkerWakeController(
        serviceWorkerRegistry = swRegistry,
        executionBackend = webViewBackend,
        extensionRegistry = registry,
        bootstrapScriptProvider = { extensionId -> cachedBootstrapScript(extensionId) },
        permissionAdapter = permissionAdapter,
        backendSelector = backendSelector,
        lifecyclePolicy = mv3LifecyclePolicy
    )
    val swShutdownController = ServiceWorkerShutdownController(
        serviceWorkerRegistry = swRegistry,
        messageBus = messageBus,
        portManager = portManager,
        swJsRuntime = swJsRuntime,
        executionBackend = webViewBackend,
        v8Backend = v8Backend,
        extensionRegistry = registry,
        lifecyclePolicy = mv3LifecyclePolicy
    )
    val swLifecycleManager = ServiceWorkerLifecycleManager(
        serviceWorkerRegistry = swRegistry,
        wakeController = swWakeController,
        shutdownController = swShutdownController,
        extensionRegistry = registry,
        permissionAdapter = permissionAdapter,
        eventManager = eventManager
    )
    val swEventDispatcher = ServiceWorkerEventDispatcher(swWakeController).also {
        mv3LifecyclePolicy.setRestartCallback { extensionId ->
            // Lifecycle policy owns backoff/scheduling; the wake controller remains the sole
            // execution entry point and replays the already-queued event(s).
            swWakeController.wakeAndExecute(extensionId, null)
        }
    }
    val messageRouter = ExtensionMessageRouter(
        registry = registry,
        permissionManager = permissionManager,
        messageBus = messageBus,
        portManager = portManager,
        swRegistry = swRegistry,
        swWakeController = swWakeController,
        mv2BackgroundManager = backgroundScriptManager
    )
    val serviceWorkerRuntime = ExtensionServiceWorkerRuntime(
        serviceWorkerRegistry = swRegistry,
        lifecycleManager = swLifecycleManager,
        wakeController = swWakeController,
        shutdownController = swShutdownController,
        eventDispatcher = swEventDispatcher
    )

    private val runtimeMap = mutableMapOf<String, ExtensionRuntime>()

    // Bootstrap generation can read locale files and create a large JavaScript
    // payload. Cache it so service-worker wakeups never rebuild it on Main.
    private val compiledBootstrapScripts = ConcurrentHashMap<String, String>()
    private val bootstrapManifestOverrides = ConcurrentHashMap<String, String>()

    private fun cachedBootstrapScript(extensionId: String): String {
        compiledBootstrapScripts[extensionId]?.let { return it }
        val compiled = compileBootstrapScriptUncached(extensionId)
        compiledBootstrapScripts[extensionId] = compiled
        return compiled
    }

    // WebViews that belong to the browser. Weak keys prevent the extension layer
    // from keeping destroyed tabs/WebViews alive. Registration is refreshed whenever
    // the enabled extension set changes.
    private val boundWebViews = java.util.Collections.synchronizedMap(WeakHashMap<WebView, Boolean>())

    private fun refreshBoundWebViews() {
        val snapshot = synchronized(boundWebViews) { boundWebViews.toMap() }
        for ((webView, isPrivate) in snapshot) {
            try {
                contentScriptManager.refreshPersistentScriptsForWebView(webView, isPrivate)
            } catch (e: Throwable) {
                android.util.Log.w("ExtensionEngine", "Failed to refresh content scripts", e)
            }
        }
    }

    private val _hydrationDeferred = kotlinx.coroutines.CompletableDeferred<Unit>()
    val hydrationDeferred: kotlinx.coroutines.Deferred<Unit> get() = _hydrationDeferred

    suspend fun awaitHydrated(): Boolean {
        return try {
            _hydrationDeferred.await()
            true
        } catch (_: Throwable) {
            false
        }
    }

    init {
        // Establish canonical browser-engine singletons before any extension API adapter can run.
        // This is intentionally before lazy adapter creation and before the background load pass.
        coreServices.initializeCoreEngines()

        permissionManager.setRegistry(registry)
        permissionAdapter.setRegistry(registry)
        permissionAdapter.setEventManager(eventManager)
        eventManager.serviceWorkerEventDispatcher = swEventDispatcher
        alarmsAdapter.serviceWorkerEventDispatcher = swEventDispatcher
        ExtensionDirectoryResolver.configure(registry, permissionManager)
        contentScriptManager.userScriptsAdapter = userScriptsAdapter
        userScriptsAdapter.onRegistrationsChanged = {
            refreshBoundWebViews()
        }
        // Dynamic content-script registration/unregistration must refresh the
        // already-bound tab WebViews. Without this callback, new registrations
        // only became effective after a later WebView recreation/navigation.
        contentScriptManager.onContentScriptsChanged = {
            refreshBoundWebViews()
        }

        // Track storage changes and dispatch storage.onChanged event down through EventManager
        storageManager.changeListener = { extensionId, area, changes ->
            val data = org.json.JSONObject().apply {
                put("changes", changes)
                put("areaName", area)
            }
            eventManager.triggerEventForExtension(extensionId, "storage.onChanged", data)
        }

        // Initialize bootstrap provider for intercepting HTML page requests
        ExtensionDirectoryResolver.bootstrapProvider = { id ->
            cachedBootstrapScript(id)
        }

        // Initialize bridge provider inside the canonical MV2 background-page runtime.
        backgroundScriptManager.bootstrapScriptProvider = { id -> cachedBootstrapScript(id) }
        backgroundScriptManager.setRuntimeBridgeProvider { webView ->
            webView.tag = ExtensionContextType.BACKGROUND
            createBridge(webView)
        }
        backgroundScriptManager.setContextAwareRuntimeBridgeProvider { webView, contextType ->
            webView.tag = contextType
            createBridge(webView)
        }
        swJsRuntime.setRuntimeBridgeProvider { webView ->
            webView.tag = ExtensionContextType.SERVICE_WORKER
            createBridge(webView)
        }
        swJsRuntime.setContextAwareRuntimeBridgeProvider { webView, contextType ->
            webView.tag = contextType
            createBridge(webView)
        }
        swJsRuntime.setNetworkPermissionChecker { extId, url, _ ->
            val ext = registry.getExtension(extId) ?: return@setNetworkPermissionChecker false
            permissionManager.hasHostPermission(extId, ext.hostPermissions, ext.permissions, url)
        }

        v8Backend?.let { backend ->
            backend.setWebRequestAdapter(webRequestAdapter)
            backend.setMessagingInfrastructure(
                messageBus = messageBus,
                portManager = portManager,
                serviceWorkerRegistry = swRegistry,
                wakeController = swWakeController,
                extensionRegistry = registry,
                eventManager = eventManager,
                messageRouter = messageRouter
            )
            backend.setRuntimeBridgeProvider { _ ->
                createBridge(null, contextTypeOverride = ExtensionContextType.SERVICE_WORKER)
            }
            backend.setContextAwareRuntimeBridgeProvider { _, contextType ->
                createBridge(null, contextTypeOverride = contextType)
            }
            backend.setNetworkPermissionChecker { extId, url, _ ->
                val ext = registry.getExtension(extId) ?: return@setNetworkPermissionChecker false
                permissionManager.hasHostPermission(extId, ext.hostPermissions, ext.permissions, url)
            }
            backend.setOnWorkerCrashListener { extensionId, reason ->
                handleServiceWorkerCrash(extensionId, reason)
            }
        }

        // MV2 background WebView crashes are owned by BackgroundScriptManager. Never feed them
        // into the MV3 service-worker state machine.
        backgroundScriptManager.onBackgroundCrash = { extensionId, reason ->
            android.util.Log.w("ExtensionEngine", "MV2 background runtime crashed: $extensionId ($reason)")
        }
        swJsRuntime.onWorkerCrash = { extensionId, reason ->
            handleServiceWorkerCrash(extensionId, reason)
        }
        swWakeController.setShutdownCancellation { id -> swShutdownController.cancelSuspension(id) }
        portManager.setWorkerGenerationProvider { id -> swRegistry.getWorkerGeneration(id) }
        backgroundScriptManager.activePortProvider = { id -> portManager.hasActiveForExtension(id) }
        swShutdownController.startIdleMonitor()

        // Load installed active extensions asynchronously with a start delay to keep startup instantaneous
        ioScope.launch {
            try {
                // Initialize extension state immediately off the main thread so content-script
                // registrations are available before the first user navigation when possible.
                val dbList = database.extensionDao().getAllExtensions()
                loader.recoverPendingInstallTransactions(
                    dbList.associate { it.extensionId to it.version }
                )

                // Process database & manifest parsing off the main thread (on Dispatchers.IO)
                val preparedRuntimes = mutableListOf<Pair<ParsedExtension, ExtensionRuntime?>>()
                for (entity in dbList) {
                    try {
                        val canonicalDir = ExtensionDirectoryResolver.migrateLegacyInstallPath(
                            context, entity.extensionId, entity.installPath
                        )
                        val normalizedEntity = if (canonicalDir != null && canonicalDir.absolutePath != entity.installPath) {
                            val oldRoot = File(entity.installPath)
                            fun rebasePath(path: String): String {
                                if (path.isBlank()) return path
                                val file = File(path)
                                return try {
                                    val relative = file.relativeTo(oldRoot).path
                                    File(canonicalDir, relative).absolutePath
                                } catch (_: Exception) {
                                    File(canonicalDir, file.name).absolutePath
                                }
                            }
                            entity.copy(
                                installPath = canonicalDir.absolutePath,
                                manifestPath = rebasePath(entity.manifestPath),
                                iconPath = rebasePath(entity.iconPath),
                                popupPath = rebasePath(entity.popupPath),
                                backgroundPath = rebasePath(entity.backgroundPath)
                            ).also { database.extensionDao().insertExtension(it) }
                        } else entity
                        val parsed = loader.loadFromDatabase(normalizedEntity)
                        var runtime: ExtensionRuntime? = null
                        if (entity.enabledState) {
                            // The registry is populated on the Main activation pass,
                            // but bootstrap generation happens here on IO. Preserve
                            // the parsed manifest so the generated runtime does not
                            // see an empty fallback manifest during process startup.
                            bootstrapManifestOverrides[parsed.id] = parsed.manifestJson
                            if (!parsed.isServiceWorker) {
                                runtime = ExtensionRuntime(
                                    parsed, context, backgroundScriptManager,
                                    contentScriptManager, popupManager, cachedBootstrapScript(parsed.id)
                                )
                            }
                        }
                        preparedRuntimes.add(Pair(parsed, runtime))
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }

                // Register and start active extensions on Main thread cleanly
                withContext(Dispatchers.Main) {
                    for (pair in preparedRuntimes) {
                        val parsed = pair.first
                        val runtime = pair.second
                        try {
                            val desiredState = if (parsed.isEnabled) {
                                ExtensionState.INSTALLED_ENABLED
                            } else {
                                ExtensionState.INSTALLED_DISABLED
                            }
                            registry.register(parsed, desiredState)
                            permissionAdapter.onExtensionRegistered(parsed)
                                                        if (parsed.isEnabled && parsed.contentScripts.isNotEmpty()) {
                                contentScriptManager.registerManifestScripts(parsed)
                            }
                            if (parsed.isEnabled) {
                                contentScriptManager.restorePersistedDynamicContentScripts(parsed.id)
                            }
                            if (!parsed.isServiceWorker) {
                                if (runtime != null) {
                                    runtimeMap[parsed.id] = runtime
                                    runtime.start()
                                    eventManager.triggerEventForExtension(
                                        parsed.id,
                                        "runtime.onStartup",
                                        org.json.JSONObject()
                                    )
                                }
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                    // The registry must be fully hydrated before static DNR indexing begins;
                    // otherwise a generation guard cannot distinguish a not-yet-registered extension
                    // from a stale update. Index one extension at a time to bound heap pressure.
                    val dnrCandidates = preparedRuntimes.map { it.first }
                        .filter { it.isEnabled && it.declarativeNetRequest.rulesets.isNotEmpty() }
                    ioScope.launch(Dispatchers.IO) {
                        for (parsed in dnrCandidates) {
                            try {
                                val extDir = ExtensionDirectoryResolver.getCanonicalExtensionDir(context, parsed.id)
                                dnrAdapter.loadStaticRulesets(
                                    parsed.id,
                                    parsed.declarativeNetRequest.rulesets,
                                    extDir,
                                    expectedRegistryGeneration = registry.getExtensionGeneration(parsed.id)
                                )
                            } catch (e: Throwable) {
                                android.util.Log.e("ExtensionEngine", "Deferred startup DNR indexing failed for ${parsed.id}; extension remains installed", e)
                            }
                        }
                    }
                    // The registry was initially populated asynchronously. Any WebView
                    // created during that window may have been configured before extensions
                    // were registered, so refresh native content-script handlers now.
                    refreshBoundWebViews()
                    // Register MV3 workers as dormant metadata only. Do not wake every worker
                    // on browser startup; actual extension events wake the specific worker lazily.
                    swLifecycleManager.onProcessRestart()
                    _hydrationDeferred.complete(Unit)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                _hydrationDeferred.completeExceptionally(e)
            }
        }
    }

    /**
     * Binds a WebView with the extension bridge, allowing injection of API support.
     */
    fun setupWebView(webView: WebView, tabId: String? = null, isPrivate: Boolean = false, privateSessionId: String? = null) {
        val action = {
            try {
                webView.settings.javaScriptEnabled = true
                webView.settings.domStorageEnabled = true

                val bridge = createBridge(webView, tabId, isPrivate, privateSessionId)
                webView.tag = bridge
                if (tabId == null) {
                    // Extension pages/background contexts keep their legacy bridge.
                    // A content page must use the world-scoped WebMessage listener only.
                    webView.addJavascriptInterface(bridge, "SwiftExtensionBridge")
                } else {
                    webView.removeJavascriptInterface("SwiftExtensionBridge")
                }
                val storeBridge = ChromeWebStoreBridge(context, ExtensionEngineApi.getInstance(context), webView)
                webView.addJavascriptInterface(storeBridge, "SwiftWebStoreBridge")
                synchronized(boundWebViews) { boundWebViews[webView] = true }
                // Register BEFORE the next navigation. WebViewCompat document-start/event
                // scripts only affect frames that begin loading after registration.
                contentScriptManager.registerPersistentScriptsForWebView(webView, isPrivate)
            } catch (e: java.lang.Exception) {
                e.printStackTrace()
            }
        }
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            action()
        } else {
            webView.post(action)
        }
    }

    /**
     * Injects matching scripts on page reload / transition completion.
     */
    fun injectContentScripts(
        webView: WebView,
        url: String,
        runAt: String? = null,
        isPrivate: Boolean = false,
        privateSessionId: String? = null
    ) {
        if (url.startsWith("swift://") || url == "about:blank" || url.startsWith("file://")) return

        val resolvedPrivate = if (isPrivate) {
            true
        } else {
            try {
                val method = webView.javaClass.getMethod("isPrivate")
                method.invoke(webView) as? Boolean ?: false
            } catch (e: Exception) {
                try {
                    val field = webView.javaClass.getField("isPrivate")
                    field.get(webView) as? Boolean ?: false
                } catch (ex: Exception) {
                    false
                }
            }
        }

        val resolvedSessionId = privateSessionId ?: try {
            val method = webView.javaClass.getMethod("getPrivateSessionId")
            method.invoke(webView) as? String
        } catch (e: Exception) {
            try {
                val field = webView.javaClass.getField("privateSessionId")
                field.get(webView) as? String
            } catch (ex: Exception) {
                null
            }
        }

        val evaluator = object : ScriptEvaluator {
            override fun evaluateJavascript(code: String, callback: ((String?) -> Unit)?) {
                webView.evaluateJavascript(code) { res -> callback?.invoke(res) }
            }
            override fun post(action: () -> Unit) {
                webView.post(action)
            }
        }

        webView.post {
            try {
                if (ChromeWebStoreScript.isWebStoreUrl(url)) {
                    ChromeWebStoreScript.injectWebStoreBridge(webView, url)
                }

                // Content-script eligibility is owned by the registry, not runtimeMap.
                // MV3 extensions intentionally have no ExtensionRuntime entry because
                // their lifecycle is managed by ServiceWorkerLifecycleManager. Filtering
                // through runtimeMap made every MV3 content script disappear.
                val enabledList = registry.getAllActiveExtensions()
                    .filter { registry.isExtensionEnabled(it.id) }

                // ContentScriptManager handles evaluating and choosing content scripts matching the specified phase
                contentScriptManager.matchAndInject(
                    evaluator = evaluator,
                    url = url,
                    parsedExtensions = enabledList,
                    runAtFilter = runAt ?: "document_idle",
                    bootstrapScriptProvider = { extId -> cachedBootstrapScript(extId) },
                    isPrivate = resolvedPrivate,
                    privateSessionId = resolvedSessionId
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override suspend fun installExtension(uri: Uri, expectedExtensionId: String?): ParsedExtension = installMutex.withLock {
        // Read the persisted record BEFORE touching the live package directory. This remains
        // authoritative even if the in-memory registry has not finished hydrating after process start.
        val knownPrevious = expectedExtensionId?.let { database.extensionDao().getExtensionById(it) }
        val parsed = loader.loadAndInstallFromZip(
            uri = uri,
            expectedExtensionId = expectedExtensionId,
            expectedExistingVersion = knownPrevious?.version
        )
        val previousEntity = database.extensionDao().getExtensionById(parsed.id)
        val wasEnabled = previousEntity?.enabledState ?: knownPrevious?.enabledState ?: true
        val effectiveParsed = parsed.copy(isEnabled = wasEnabled)

        // Stop/rebind every per-extension runtime before exposing the new package. This is
        // especially important for MV2 updates where the previous WebView background runtime
        // can otherwise remain alive after runtimeMap is replaced.
        runtimeMap.remove(parsed.id)?.let { oldRuntime ->
            runCatching { oldRuntime.stop() }
        }
        runCatching { backgroundScriptManager.stopBackgroundWorker(parsed.id) }
        contentScriptManager.unregisterContentScripts(parsed.id, null)

        compiledBootstrapScripts.remove(parsed.id)
        bootstrapManifestOverrides[parsed.id] = effectiveParsed.manifestJson
        registry.register(
            effectiveParsed,
            if (wasEnabled) ExtensionState.INSTALLED_ENABLED else ExtensionState.INSTALLED_DISABLED
        )
        permissionAdapter.onExtensionRegistered(effectiveParsed)
        if (effectiveParsed.isEnabled && effectiveParsed.contentScripts.isNotEmpty()) {
            contentScriptManager.registerManifestScripts(effectiveParsed)
        }
        if (effectiveParsed.isEnabled) {
            contentScriptManager.restorePersistedDynamicContentScripts(effectiveParsed.id)
        }

        // Persist only after the package has been committed and the canonical registry has the
        // same version/state. Loader no longer writes a second DB copy.
        database.extensionDao().insertExtension(ExtensionEntity(
            extensionId = effectiveParsed.id,
            name = effectiveParsed.name,
            shortName = effectiveParsed.shortName,
            version = effectiveParsed.version,
            description = effectiveParsed.description,
            iconPath = effectiveParsed.iconPath,
            installPath = effectiveParsed.installPath,
            popupPath = effectiveParsed.popupPath,
            manifestPath = effectiveParsed.manifestPath,
            backgroundPath = effectiveParsed.backgroundPath,
            enabledState = wasEnabled,
            manifestJson = effectiveParsed.manifestJson,
            dateInstalled = previousEntity?.dateInstalled ?: System.currentTimeMillis()
        ))

        // Filesystem package + registry + Room now agree on this revision. Only now is it safe
        // to delete the old backup and transaction journal.
        val currentGen = swRegistry.getWorkerGeneration(effectiveParsed.id)
        android.util.Log.i("CRASH_BOUNDARY", "[CRASH_BOUNDARY] Stage: extension package commit | Status: START | Extension: ${effectiveParsed.id} | Generation: $currentGen | Version: ${effectiveParsed.version}")
        loader.finalizeCommittedInstall(effectiveParsed.id, effectiveParsed.version)
        android.util.Log.i("CRASH_BOUNDARY", "[CRASH_BOUNDARY] Stage: extension package commit | Status: SUCCESS | Extension: ${effectiveParsed.id} | Generation: $currentGen | Version: ${effectiveParsed.version}")

        val installReason = if (previousEntity == null) "install" else "update"
        if (installReason == "update") {
            // Chrome clears dynamic user-script registrations when an extension updates.
            // The updated extension's onInstalled handler can re-register them.
            runCatching { userScriptsAdapter.clearForExtension(effectiveParsed.id) }
        }

        // MV3 installation is committed first. The lifecycle manager then treats the required
        // install/onInstalled/activate sequence as the event demand that is allowed to wake the
        // worker. No unrelated ExtensionEngine construction path initializes Native V8.
        if (effectiveParsed.isServiceWorker) {
            try {
                swLifecycleManager.onExtensionInstalled(
                    effectiveParsed,
                    reason = installReason,
                    previousVersion = previousEntity?.version.orEmpty()
                )
            } catch (e: Exception) {
                android.util.Log.w("ExtensionEngine", "Failed to register dormant MV3 worker for ${effectiveParsed.id}", e)
            }
        } else {
            // MV2 keeps its legacy WebView background runtime.
            val bootstrapScript = cachedBootstrapScript(effectiveParsed.id)
            val runtime = ExtensionRuntime(
                effectiveParsed, context, backgroundScriptManager,
                contentScriptManager, popupManager, bootstrapScript
            )
            runtimeMap[effectiveParsed.id] = runtime
            runtime.start()
            // A freshly installed MV2 event page is dormant until its first required event.
            // onInstalled is the bootstrap event itself, so explicitly wake once without a
            // pre-existing lazy listener, then let the page re-register its listeners normally.
            eventManager.triggerEventForExtension(
                effectiveParsed.id,
                "runtime.onInstalled",
                org.json.JSONObject().apply {
                    put("reason", installReason)
                    put("previousVersion", previousEntity?.version ?: "")
                },
                wakeWithoutListener = true
            )
        }

        // Heavy static DNR indexing is deliberately outside the install-critical path.
        // The extension is visible/usable before this optimization work completes.
        if (effectiveParsed.isEnabled && effectiveParsed.declarativeNetRequest.rulesets.isNotEmpty()) {
            val extDir = ExtensionDirectoryResolver.getExtensionDir(context, effectiveParsed.id, effectiveParsed.name)
            ioScope.launch(Dispatchers.Default) {
                try {
                    dnrAdapter.loadStaticRulesets(
                        effectiveParsed.id,
                        effectiveParsed.declarativeNetRequest.rulesets,
                        extDir,
                        expectedRegistryGeneration = registry.getExtensionGeneration(effectiveParsed.id)
                    )
                } catch (e: Throwable) {
                    android.util.Log.e("ExtensionEngine", "Deferred DNR indexing failed for ${effectiveParsed.id}; extension remains installed", e)
                }
            }
        }

        // WebView refresh is asynchronous; it must not delay the install result.
        ioScope.launch(Dispatchers.Main.immediate) {
            try { refreshBoundWebViews() } catch (e: Exception) {
                android.util.Log.w("ExtensionEngine", "Deferred WebView refresh failed", e)
            }
        }
        android.util.Log.i("ExtensionInstallPerf", "INSTALL_CRITICAL_PATH_DONE id=${effectiveParsed.id} mv=${effectiveParsed.manifestVersion}")
        return@withLock effectiveParsed
    }

    override suspend fun uninstallExtension(id: String) {
        compiledBootstrapScripts.remove(id)
        bootstrapManifestOverrides.remove(id)
        withContext(Dispatchers.Main) {
            if (swRegistry.isRegistered(id)) {
                swLifecycleManager.onExtensionUninstalled(id)
            } else {
                runtimeMap.remove(id)?.stop()
                backgroundScriptManager.stopBackgroundWorker(id)
            }
            ExtensionActionAdapter.cleanupExtensionState(id)
            ExtensionContextMenusAdapter.cleanupExtensionState(id)
            ExtensionCommandsAdapter.cleanupExtensionState(id)
            ExtensionOmniboxAdapter.cleanupExtensionState(id)
            ExtensionSidePanelAdapter.cleanupExtensionState(id)
            userScriptsAdapter.clearForExtension(id)
            powerAdapter.clearExtension(id)
        }
        permissionAdapter.onExtensionUninstalled(id)
        dnrAdapter.clearExtension(id)
        webRequestAdapter.subscriptionRegistry.removeAllForExtension(id)
        contentScriptManager.registry.unregisterAllForExtension(id)
        contentScriptManager.registry.incrementGeneration()
        registry.unregister(id)
        database.extensionDao().deleteExtensionById(id)
        try {
            ExtensionDirectoryResolver.deleteExtensionDir(context, id)
        } catch (e: Throwable) {
            android.util.Log.w("ExtensionEngine", "Failed to delete extension package directory for $id", e)
        }
        refreshBoundWebViews()
    }

    override suspend fun toggleExtension(id: String, enabled: Boolean) {
        val parsed = registry.getExtension(id) ?: return
        val targetState = if (enabled) ExtensionState.INSTALLED_ENABLED else ExtensionState.INSTALLED_DISABLED

        // In-memory lifecycle is changed first so every adapter observes the same state.
        withContext(Dispatchers.Main) {
            try {
                if (registry.getExtensionState(id) != targetState) {
                    registry.transitionState(id, targetState)
                }
            } catch (t: Throwable) {
                android.util.Log.w("ExtensionEngine", "Unable to transition extension $id to $targetState", t)
            }

            if (enabled) {
                if (parsed.contentScripts.isNotEmpty()) {
                    contentScriptManager.registerManifestScripts(parsed)
                }
                contentScriptManager.restorePersistedDynamicContentScripts(id)
                dnrAdapter.reactivateExtension(id)
            } else {
                dnrAdapter.deactivateExtension(id)
                contentScriptManager.registry.unregisterAllForExtension(id)
                contentScriptManager.registry.incrementGeneration()
                ExtensionActionAdapter.cleanupExtensionState(id)
                ExtensionContextMenusAdapter.cleanupExtensionState(id)
                ExtensionCommandsAdapter.cleanupExtensionState(id)
                ExtensionOmniboxAdapter.cleanupExtensionState(id)
                ExtensionSidePanelAdapter.cleanupExtensionState(id)
                powerAdapter.clearExtension(id)
            }
        }

        if (enabled && parsed.declarativeNetRequest.rulesets.isNotEmpty()) {
            try {
                withContext(Dispatchers.IO) {
                    val extDir = ExtensionDirectoryResolver.getCanonicalExtensionDir(context, parsed.id)
                    dnrAdapter.loadStaticRulesets(
                        parsed.id,
                        parsed.declarativeNetRequest.rulesets,
                        extDir,
                        expectedRegistryGeneration = registry.getExtensionGeneration(parsed.id)
                    )
                }
            } catch (t: Throwable) {
                android.util.Log.w("ExtensionEngine", "Static DNR activation failed for $id; dynamic/session rules remain available", t)
                dnrAdapter.reactivateExtension(id)
            }
        }

        database.extensionDao().updateEnabledState(id, enabled)

        withContext(Dispatchers.Main) {
            if (parsed.isServiceWorker) {
                if (enabled) {
                    swLifecycleManager.onExtensionInstalled(parsed, reason = "enable", previousVersion = parsed.version)
                } else {
                    swLifecycleManager.onExtensionDisabled(id)
                }
            } else {
                if (enabled) {
                    val runtime = runtimeMap[id] ?: ExtensionRuntime(
                        parsed, context, backgroundScriptManager,
                        contentScriptManager, popupManager, cachedBootstrapScript(parsed.id)
                    ).also { runtimeMap[id] = it }
                    runtime.start()
                } else {
                    runtimeMap.remove(id)?.stop()
                    backgroundScriptManager.stopBackgroundWorker(id)
                }
            }
            refreshBoundWebViews()
        }
    }

    override fun shutdown() {
        messageBus.clear()
        runtimeMap.values.forEach { runCatching { it.stop() } }
        runtimeMap.clear()
    }

    override fun setAllowedInPrivate(id: String, allowed: Boolean) {
        permissionManager.setAllowedInPrivate(id, allowed)
    }

    override fun isAllowedInPrivate(id: String): Boolean {
        return permissionManager.isAllowedInPrivate(id)
    }

    fun clearPrivateSession(privateSessionId: String? = null) {
        storageManager.clearPrivateStorage(privateSessionId)
    }

    fun createBridge(
        webView: WebView?,
        tabId: String? = null,
        isPrivate: Boolean = false,
        privateSessionId: String? = null,
        contextTypeOverride: ExtensionContextType? = null,
        ownExtensionId: String? = null,
        frameId: Int? = null,
        documentId: String? = null
    ): RuntimeBridge {
        val bridge = RuntimeBridge(
            context = context,
            webView = webView,
            storageManager = storageManager,
            storageBackend = storageBackend,
            messageBus = messageBus,
            delegate = delegate,
            backgroundScriptManager = backgroundScriptManager,
            eventManager = eventManager,
            tabId = tabId,
            frameId = frameId,
            documentId = documentId,
            portManager = portManager,
            tabBridge = tabBridge,
            registry = registry,
            permissionManager = permissionManager,
            isPrivate = isPrivate,
            privateSessionId = privateSessionId,
            tabsAdapter = tabsAdapter,
            windowsAdapter = windowsAdapter,
            tabGroupsAdapter = tabGroupsAdapter,
            sessionsAdapter = sessionsAdapter,
            permissionsAdapter = permissionAdapter,
            cookieAdapter = cookieAdapter,
            bookmarksAdapter = bookmarksAdapter,
            historyAdapter = historyAdapter,
            downloadsAdapter = downloadsAdapter,
            dnrAdapter = dnrAdapter,
            webRequestAdapter = webRequestAdapter,
            scriptingAdapter = scriptingAdapter,
            actionAdapter = actionAdapter,
            contextMenusAdapter = contextMenusAdapter,
            commandsAdapter = commandsAdapter,
            omniboxAdapter = omniboxAdapter,
            sidePanelAdapter = sidePanelAdapter,
            managementAdapter = managementAdapter,
            topSitesAdapter = topSitesAdapter,
            idleAdapter = idleAdapter,
            ttsAdapter = ttsAdapter,
            searchAdapter = searchAdapter,
            alarmsAdapter = alarmsAdapter,
            systemAdapter = systemAdapter,
            notificationsAdapter = notificationsAdapter,
            coreServices = coreServices,
            webNavigationAdapter = webNavigationAdapter,
            browsingDataAdapter = browsingDataAdapter,
            privacyAdapter = privacyAdapter,
            contentSettingsAdapter = contentSettingsAdapter,
            offscreenAdapter = offscreenAdapter,
            userScriptsAdapter = userScriptsAdapter,
            powerAdapter = powerAdapter,
            debuggerAdapter = debuggerAdapter,
            serviceWorkerEventDispatcher = swEventDispatcher,
            messageRouter = messageRouter,
            contextTypeOverride = contextTypeOverride ?: (webView?.tag as? ExtensionContextType),
            forcedOwnExtensionId = ownExtensionId
        )
        bridge.onWorkerCrash = { extId, reason ->
            val ext = registry.getExtension(extId)
            if (ext?.isServiceWorker == true || (ext?.manifestVersion ?: 0) >= 3) {
                handleServiceWorkerCrash(extId, reason)
            } else {
                backgroundScriptManager.onBackgroundCrash?.invoke(extId, reason)
            }
        }
        return bridge
    }

    fun handleServiceWorkerCrash(extensionId: String, reason: String) {
        val worker = swRegistry.getWorker(extensionId) ?: return
        if (!worker.isMV3) return

        val generation = worker.workerGenerationId
        val decision = mv3LifecyclePolicy.handleRuntimeFailure(
            extensionId = extensionId,
            generation = generation,
            reason = reason
        )

        if (decision.terminal) {
            ExtensionDebuggerEngine.instance.logError(
                extensionId,
                "Service Worker",
                DebugErrorType.RUNTIME,
                "Service Worker entered terminal FAILED state after repeated runtime failures: $reason"
            )
        }
    }

    private fun loadExtensionMessagesJson(extensionId: String): String {
        try {
            val extensionDir = ExtensionDirectoryResolver.getExtensionDir(context, extensionId)
            val localesDir = File(extensionDir, "_locales")
            if (!localesDir.exists() || !localesDir.isDirectory) {
                return "{}"
            }

            val currentLocale = java.util.Locale.getDefault()
            val langCode = currentLocale.language
            val country = currentLocale.country
            val fullCode = if (country.isNotBlank()) "${langCode}_$country" else langCode

            val candidateDirs = listOf(
                fullCode,
                fullCode.replace("_", "-"),
                langCode,
                "en",
                "en-US",
                "en_US"
            )

            var messagesFile: File? = null
            for (cand in candidateDirs) {
                val f = File(localesDir, cand + "/messages.json")
                if (f.exists() && f.isFile) {
                    messagesFile = f
                    break
                }
            }

            if (messagesFile == null) {
                val subfolders = localesDir.listFiles { f -> f.isDirectory }
                if (subfolders != null) {
                    for (sub in subfolders) {
                        val f = File(sub, "messages.json")
                        if (f.exists() && f.isFile) {
                            messagesFile = f
                            break
                        }
                    }
                }
            }

            if (messagesFile != null && messagesFile.exists()) {
                val fileContent = messagesFile.readText()
                // Validate it's correct JSON
                val testObj = org.json.JSONObject(fileContent)
                return testObj.toString()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return "{}"
    }

    /** Public bootstrap entry point used by extension pages. It is cached so
     * page document-start callbacks cannot rebuild the payload on Main. */
    fun compileBootstrapScript(extensionId: String): String = cachedBootstrapScript(extensionId)

    private fun compileBootstrapScriptUncached(extensionId: String): String {
        val ext = registry.getExtension(extensionId)
        val manifestJsonSafe = bootstrapManifestOverrides[extensionId]
            ?: ext?.manifestJson
            ?: "{}"
        val quotedManifest = org.json.JSONObject.quote(manifestJsonSafe)
        val messagesJsonSafe = loadExtensionMessagesJson(extensionId)
        val quotedMessages = org.json.JSONObject.quote(messagesJsonSafe)

        val domUtils = domBridge.compileDomUtilities()
        val postMessageUtils = PostMessageBridge().compilePostMessageScript()

        return """
            (function() {
                const extId = "$extensionId";

                // Inject DOM and PostMessage Utility Helpers
                $domUtils
                $postMessageUtils

                window._swiftManifests = window._swiftManifests || {};
                window._swiftManifests[extId] = $quotedManifest;

                window._swiftExtensionMessages = window._swiftExtensionMessages || {};
                try {
                    window._swiftExtensionMessages[extId] = JSON.parse($quotedMessages);
                } catch(e) {
                    window._swiftExtensionMessages[extId] = {};
                }

                // Bind diagnostic listeners for uncaught errors and promise rejections
                window.addEventListener("error", function(e) {
                    console.error("[UNCAUGHT_ERROR] " + e.message + " (at " + e.filename + ":" + e.lineno + ":" + e.colno + ")");
                });
                window.addEventListener("unhandledrejection", function(e) {
                    console.error("[UNHANDLED_REJECTION] " + (e.reason ? (e.reason.message || e.reason) : "Unknown Promise Rejection"));
                });

                window._extCallbacks = window._extCallbacks || {};
                window._extResponse = function(callbackId, error, result) {
                    const cb = window._extCallbacks[callbackId];
                    if (cb) {
                        delete window._extCallbacks[callbackId];
                        const errorObject = error
                            ? (typeof error === "object" ? error : { error: String(error) })
                            : null;
                        cb(errorObject, result);
                    }
                };

                // Same-world response hook for AndroidX isolated-world WebMessage transport.
                // RuntimeBridge replies through JavaScriptReplyProxy, so the callback never
                // crosses into PAGE_WORLD via evaluateJavascript.
                window._orionHandleApiResponse = function(message) {
                    try {
                        const data = typeof message === "string" ? JSON.parse(message) : message;
                        if (!data || data.type !== "api-response" || !data.callbackId) return false;
                        const result = data.resultJson !== undefined
                            ? JSON.parse(String(data.resultJson))
                            : null;
                        window._extResponse(data.callbackId, data.error ? { error: String(data.error) } : null, result);
                        return true;
                    } catch (_) {
                        return false;
                    }
                };

                // Canonical extension transport for every surface (popup, side panel,
                // options, background and service-worker-like contexts).  The API
                // bootstrap never calls SwiftExtensionBridge directly; it uses this
                // function so transport selection is centralized and consistent.
                window._orionPostMessage = function(apiName, args, callbackId) {
                    const payload = JSON.stringify({
                        api: apiName,
                        extensionId: extId,
                        args: args || [],
                        callbackId: callbackId || ""
                    });
                    // Content scripts may run inside an AndroidX isolated world.
                    // Their bridge is registered with an extension-specific name
                    // because the generic extension-page listener is not visible
                    // across execution worlds.
                    try {
                        const contentBridgeName = "__orionContentBridge_" + extId.replace(/[^A-Za-z0-9_]/g, "_");
                        const contentBridge = window[contentBridgeName];
                        if (contentBridge && typeof contentBridge.postMessage === 'function') {
                            contentBridge.postMessage(payload);
                            return true;
                        }
                    } catch (e) {
                        console.warn("[EXT_BRIDGE] Isolated content transport failed", e);
                    }
                    try {
                        const execBridgeName = "__orionExtExecBridge_" + extId.replace(/[^A-Za-z0-9_]/g, "_");
                        const execBridge = window[execBridgeName];
                        if (execBridge && typeof execBridge.postMessage === 'function') {
                            execBridge.postMessage(payload);
                            return true;
                        }
                    } catch (e) {
                        console.warn("[EXT_BRIDGE] Execution-world transport failed", e);
                    }
                    try {
                        if (typeof __orionExtensionBridge !== 'undefined' &&
                            __orionExtensionBridge &&
                            typeof __orionExtensionBridge.postMessage === 'function') {
                            __orionExtensionBridge.postMessage(payload);
                            return true;
                        }
                    } catch (e) {
                        console.warn("[EXT_BRIDGE] Modern transport failed; trying legacy fallback", e);
                    }
                    try {
                        if (typeof SwiftExtensionBridge !== 'undefined' &&
                            SwiftExtensionBridge &&
                            typeof SwiftExtensionBridge.postMessage === 'function') {
                            SwiftExtensionBridge.postMessage(payload, callbackId || ("msg_" + Date.now()));
                            return true;
                        }
                    } catch (e) {
                        console.error("[EXT_BRIDGE] Legacy transport failed", e);
                    }
                    return false;
                };

                window._swiftExtensionEvents = window._swiftExtensionEvents || {};
                window._swiftDispatchEvent = function(extId, eventName, data) {
                    const extEvents = window._swiftExtensionEvents[extId] || {};
                    const listeners = extEvents[eventName] || [];
                    let eventArgs = [];
                    if (eventName === "tabs.onUpdated") {
                        const intTabId = data.tabId || 0;
                        const changeInfo = data.changeInfo || {};
                        const tab = data.tab || {};
                        eventArgs = [intTabId, changeInfo, tab];
                    } else if (eventName === "tabs.onActivated") {
                        eventArgs = [data.activeInfo || {}];
                    } else if (eventName === "tabs.onCreated" || eventName === "tabs.onRemoved") {
                        eventArgs = [data.tab || data.tabId || data];
                    } else if (eventName === "storage.onChanged") {
                        const changes = data.changes || {};
                        const areaName = data.areaName || "local";
                        eventArgs = [changes, areaName];
                    } else if (eventName === "webNavigation.onCompleted" || eventName === "webNavigation.onHistoryStateUpdated" || eventName === "webNavigation.onBeforeNavigate" || eventName === "webNavigation.onCommitted" || eventName === "webNavigation.onDOMContentLoaded" || eventName === "webNavigation.onErrorOccurred") {
                        eventArgs = [data.details || data];
                    } else if (eventName === "alarms.onAlarm") {
                        eventArgs = [data.alarm || data];
                    } else if (eventName === "commands.onCommand") {
                        eventArgs = [data.command || data.name || data];
                    } else if (eventName === "cookies.onChanged") {
                        eventArgs = [data.changeInfo || data];
                    } else if (eventName === "downloads.onCreated" || eventName === "downloads.onErased") {
                        eventArgs = [data.downloadItem || data.downloadId || data];
                    } else if (eventName === "downloads.onChanged") {
                        eventArgs = [data.downloadDelta || data];
                    } else if (eventName === "history.onVisited") {
                        eventArgs = [data.historyItem || data];
                    } else if (eventName === "history.onVisitRemoved") {
                        eventArgs = [data.removed || data];
                    } else if (eventName === "permissions.onAdded" || eventName === "permissions.onRemoved") {
                        eventArgs = [data.permissions || data];
                    } else if (eventName.startsWith("webRequest.")) {
                        eventArgs = [data.details || data];
                    } else if (eventName === "debugger.onEvent") {
                        eventArgs = [data.source || {}, data.method || "", data.params || {}];
                    } else if (eventName === "debugger.onDetach") {
                        eventArgs = [data.source || {}, data.reason || ""];
                    } else {
                        eventArgs = [data];
                    }
                    listeners.forEach(cb => {
                        try { cb.apply(null, eventArgs); } catch(e) { console.error("Event execution error for " + eventName, e); }
                    });
                };

                window._swiftGetExtensionContext = window._swiftGetExtensionContext || function(contextExtId) {
                    const bridgeCall = function(apiName, args) {
                        return new Promise((resolve, reject) => {
                            const cbId = "cb_" + Math.random().toString(36).substring(2, 9) + "_" + Date.now();

                            // Safety timeout (5.0 seconds) to prevent any script from freezing the runtime
                            const timeoutId = setTimeout(() => {
                                if (window._extCallbacks[cbId]) {
                                    delete window._extCallbacks[cbId];
                                    console.warn("[BRIDGE_TIMEOUT] Api '" + apiName + "' timed out after 5000ms.");
                                    reject(new Error("Timeout calling " + apiName));
                                }
                            }, 5000);

                            window._extCallbacks[cbId] = function(errObj, res) {
                                clearTimeout(timeoutId);
                                delete window._extCallbacks[cbId];
                                if (errObj) {
                                    const errMsg = errObj.error || errObj.message || String(errObj);
                                    if (window.chrome && window.chrome.runtime) {
                                        window.chrome.runtime.lastError = { message: String(errMsg) };
                                    }
                                    if (errMsg === "UNSUPPORTED_BY_ORION") {
                                        console.warn("[UNSUPPORTED_BY_ORION] Api '" + apiName + "' is not supported by Orion.");
                                    }
                                    reject(new Error(errMsg));
                                } else {
                                    if (window.chrome && window.chrome.runtime) {
                                        window.chrome.runtime.lastError = null;
                                    }
                                    resolve(res !== undefined ? res : null);
                                }
                            };
                            try {
                                const posted = window._orionPostMessage(apiName, args || [], cbId);
                                if (!posted) {
                                    clearTimeout(timeoutId);
                                    delete window._extCallbacks[cbId];
                                    reject(new Error("Extension bridge unavailable"));
                                }
                            } catch (e) {
                                clearTimeout(timeoutId);
                                delete window._extCallbacks[cbId];
                                console.error("[BRIDGE_ERROR] Error posting message for '" + apiName + "':", e);
                                reject(e);
                            }
                        });
                    };

                    const makeAsyncApi = function(apiName, argFormatter) {
                        return function() {
                            const rawArgs = Array.prototype.slice.call(arguments);
                            let callback = null;
                            if (rawArgs.length > 0 && typeof rawArgs[rawArgs.length - 1] === "function") {
                                callback = rawArgs.pop();
                            }
                            const formattedArgs = argFormatter ? argFormatter(rawArgs) : rawArgs;
                            const p = bridgeCall(apiName, formattedArgs);
                            if (callback) {
                                p.then(res => {
                                    try { callback(res); } catch(e) { console.error("Callback error in " + apiName, e); }
                                }).catch(err => {
                                    try { callback(undefined); } catch(e) { console.error("Callback error in " + apiName, e); }
                                });
                                return undefined;
                            }
                            return p;
                        };
                    };

                    const createEvent = function(extId, eventName) {
                        window._swiftExtensionEvents = window._swiftExtensionEvents || {};
                        window._swiftExtensionEvents[extId] = window._swiftExtensionEvents[extId] || {};
                        window._swiftExtensionEvents[extId][eventName] = window._swiftExtensionEvents[extId][eventName] || [];
                        window._swiftListenerMap = window._swiftListenerMap || new WeakMap();

                        return {
                            addListener: function(cb, filter, extraInfoSpec) {
                                if (typeof cb === "function") {
                                    if (!window._swiftExtensionEvents[extId][eventName].includes(cb)) {
                                        window._swiftExtensionEvents[extId][eventName].push(cb);
                                    }
                                    let listenerId = window._swiftListenerMap.get(cb);
                                    if (!listenerId) {
                                        listenerId = "list_" + Math.random().toString(36).substring(2, 9) + "_" + Date.now();
                                        window._swiftListenerMap.set(cb, listenerId);
                                    }
                                    bridgeCall("event.addListener", [eventName, listenerId, filter || null, extraInfoSpec || null]);
                                }
                            },
                            removeListener: function(cb) {
                                if (window._swiftExtensionEvents[extId][eventName]) {
                                    window._swiftExtensionEvents[extId][eventName] =
                                        window._swiftExtensionEvents[extId][eventName].filter(l => l !== cb);
                                }
                                let listenerId = window._swiftListenerMap ? window._swiftListenerMap.get(cb) : null;
                                bridgeCall("event.removeListener", [eventName, listenerId || ""]);
                            },
                            hasListener: function(cb) {
                                if (!window._swiftExtensionEvents[extId][eventName]) return false;
                                return window._swiftExtensionEvents[extId][eventName].includes(cb);
                            },
                            hasListeners: function() {
                                return (window._swiftExtensionEvents[extId][eventName] || []).length > 0;
                            }
                        };
                    };

                    const makeStorageArea = function(areaName) {
                        return {
                            get: function(keys, callback) {
                                let resolvedKeys = keys;
                                let resolvedCallback = callback;
                                if (typeof keys === "function") {
                                    resolvedCallback = keys;
                                    resolvedKeys = null;
                                }
                                const p = bridgeCall("storage.get", [areaName, resolvedKeys]);
                                if (resolvedCallback) p.then(resolvedCallback);
                                return p;
                            },
                            set: function(items, callback) {
                                let resolvedCallback = callback;
                                if (typeof items === "function") {
                                    resolvedCallback = items;
                                }
                                const p = bridgeCall("storage.set", [areaName, items]);
                                if (resolvedCallback) p.then(resolvedCallback);
                                return p;
                            },
                            remove: function(keys, callback) {
                                let resolvedKeys = keys;
                                let resolvedCallback = callback;
                                if (typeof keys === "function") {
                                    resolvedCallback = keys;
                                    resolvedKeys = null;
                                }
                                const p = bridgeCall("storage.remove", [areaName, resolvedKeys]);
                                if (resolvedCallback) p.then(resolvedCallback);
                                return p;
                            },
                            clear: function(callback) {
                                const p = bridgeCall("storage.clear", [areaName]);
                                if (callback) p.then(callback);
                                return p;
                            },
                            getBytesInUse: function(keys, callback) {
                                let resolvedKeys = keys;
                                let resolvedCallback = callback;
                                if (typeof keys === "function") {
                                    resolvedCallback = keys;
                                    resolvedKeys = null;
                                }
                                const p = bridgeCall("storage.getBytesInUse", [areaName, resolvedKeys]);
                                if (resolvedCallback) p.then(resolvedCallback);
                                return p;
                            }
                        };
                    };

                    const actionApi = {
                        setIcon: makeAsyncApi("action.setIcon"),
                        setTitle: makeAsyncApi("action.setTitle"),
                        getTitle: makeAsyncApi("action.getTitle"),
                        setPopup: makeAsyncApi("action.setPopup"),
                        getPopup: makeAsyncApi("action.getPopup"),
                        setBadgeText: makeAsyncApi("action.setBadgeText"),
                        getBadgeText: makeAsyncApi("action.getBadgeText"),
                        setBadgeBackgroundColor: makeAsyncApi("action.setBadgeBackgroundColor"),
                        getBadgeBackgroundColor: makeAsyncApi("action.getBadgeBackgroundColor"),
                        setBadgeTextColor: makeAsyncApi("action.setBadgeTextColor"),
                        getBadgeTextColor: makeAsyncApi("action.getBadgeTextColor"),
                        isEnabled: makeAsyncApi("action.isEnabled"),
                        getUserSettings: makeAsyncApi("action.getUserSettings"),
                        enable: makeAsyncApi("action.enable"),
                        disable: makeAsyncApi("action.disable"),
                        openPopup: makeAsyncApi("action.openPopup"),
                        onClicked: createEvent(contextExtId, "action.onClicked")
                    };

                    const browserActionApi = {
                        setIcon: makeAsyncApi("browserAction.setIcon"),
                        setTitle: makeAsyncApi("browserAction.setTitle"),
                        getTitle: makeAsyncApi("browserAction.getTitle"),
                        setPopup: makeAsyncApi("browserAction.setPopup"),
                        getPopup: makeAsyncApi("browserAction.getPopup"),
                        setBadgeText: makeAsyncApi("browserAction.setBadgeText"),
                        getBadgeText: makeAsyncApi("browserAction.getBadgeText"),
                        setBadgeBackgroundColor: makeAsyncApi("browserAction.setBadgeBackgroundColor"),
                        getBadgeBackgroundColor: makeAsyncApi("browserAction.getBadgeBackgroundColor"),
                        setBadgeTextColor: makeAsyncApi("browserAction.setBadgeTextColor"),
                        getBadgeTextColor: makeAsyncApi("browserAction.getBadgeTextColor"),
                        enable: makeAsyncApi("browserAction.enable"),
                        disable: makeAsyncApi("browserAction.disable"),
                        openPopup: makeAsyncApi("browserAction.openPopup"),
                        onClicked: createEvent(contextExtId, "browserAction.onClicked")
                    };

                    const pageActionApi = {
                        setIcon: makeAsyncApi("pageAction.setIcon"),
                        setTitle: makeAsyncApi("pageAction.setTitle"),
                        getTitle: makeAsyncApi("pageAction.getTitle"),
                        setPopup: makeAsyncApi("pageAction.setPopup"),
                        getPopup: makeAsyncApi("pageAction.getPopup"),
                        enable: makeAsyncApi("pageAction.enable"),
                        disable: makeAsyncApi("pageAction.disable"),
                        onClicked: createEvent(contextExtId, "pageAction.onClicked")
                    };

                    // Chrome 148+ also exposes a cross-browser `browser` namespace.
                    // Alias it to the same Orion capability object so Chrome/Firefox-style
                    // extensions share one backend without creating another API implementation.
                    try {
                        if (typeof window.browser === "undefined" || !window.browser) {
                            window.browser = window.chrome;
                        }
                    } catch (_) {}

                    const contextObj = {
                        action: actionApi,
                        browserAction: browserActionApi,
                        pageAction: pageActionApi,
                        sidePanel: {
                            setOptions: makeAsyncApi("sidePanel.setOptions"),
                            getOptions: makeAsyncApi("sidePanel.getOptions"),
                            setPanelBehavior: makeAsyncApi("sidePanel.setPanelBehavior"),
                            getPanelBehavior: makeAsyncApi("sidePanel.getPanelBehavior"),
                            open: makeAsyncApi("sidePanel.open")
                        },
                        contextMenus: {
                            create: function(createProperties, callback) {
                                const p = bridgeCall("contextMenus.create", [createProperties || {}]);
                                if (callback) p.then(callback);
                                return (createProperties && createProperties.id) || ("menu_" + Math.random().toString(36).substring(2, 9));
                            },
                            update: makeAsyncApi("contextMenus.update"),
                            remove: makeAsyncApi("contextMenus.remove"),
                            removeAll: makeAsyncApi("contextMenus.removeAll"),
                            onClicked: createEvent(contextExtId, "contextMenus.onClicked")
                        },
                        commands: {
                            getAll: makeAsyncApi("commands.getAll"),
                            onCommand: createEvent(contextExtId, "commands.onCommand")
                        },
                        omnibox: {
                            setDefaultSuggestion: makeAsyncApi("omnibox.setDefaultSuggestion"),
                            onInputStarted: createEvent(contextExtId, "omnibox.onInputStarted"),
                            onInputChanged: createEvent(contextExtId, "omnibox.onInputChanged"),
                            onInputEntered: createEvent(contextExtId, "omnibox.onInputEntered"),
                            onInputCancelled: createEvent(contextExtId, "omnibox.onInputCancelled")
                        },
                        runtime: {
                            id: contextExtId,
                            lastError: null,
                            getPlatformInfo: function(callback) {
                                const info = { os: "android", arch: "arm", nacl_arch: "arm" };
                                if (callback) callback(info);
                                return Promise.resolve(info);
                            },
                            openOptionsPage: function(callback) {
                                const manifest = contextObj.runtime.getManifest();
                                const optionsPage = manifest.options_ui ? (manifest.options_ui.page || "") : (manifest.options_page || "");
                                if (!optionsPage) {
                                    if (window.chrome && window.chrome.runtime) {
                                        window.chrome.runtime.lastError = { message: "No options page declared in manifest." };
                                    }
                                    if (callback) callback(undefined);
                                    return Promise.reject(new Error("No options page declared in manifest."));
                                }
                                const url = contextObj.runtime.getURL(optionsPage);
                                const p = contextObj.tabs.create({ url: url });
                                if (callback) p.then(callback);
                                return p;
                            },
                            reload: function() {
                                bridgeCall("runtime.reload", []);
                            },
                            getURL: function(path) {
                                if (!path) return "chrome-extension://" + contextExtId + "/";
                                if (path.startsWith("/")) path = path.substring(1);
                                return "chrome-extension://" + contextExtId + "/" + path;
                            },
                            getManifest: function() {
                                const str = window._swiftManifests[contextExtId] || "{}";
                                try { return JSON.parse(str); } catch(e) { return {}; }
                            },
                            setUninstallURL: makeAsyncApi("runtime.setUninstallURL"),
                            getBackgroundPage: function(callback) {
                                if (window._isMV2BackgroundPage) {
                                    if (callback) callback(window);
                                    return Promise.resolve(window);
                                }
                                const makeProxy = function(meta) {
                                    if (!meta || !meta.available) return undefined;
                                    return new Proxy({}, {
                                        get: function(_target, property) {
                                            if (property === "then") return undefined;
                                            if (property === "__orionBackgroundPage") return true;
                                            if (property === "location") return { href: meta.url, origin: meta.url };
                                            if (typeof property !== "string") return undefined;
                                            return function() {
                                                return bridgeCall("runtime.backgroundPageCall", [property, Array.prototype.slice.call(arguments)]);
                                            };
                                        }
                                    });
                                };
                                const p = bridgeCall("runtime.getBackgroundPage", []);
                                if (callback) {
                                    p.then(function(meta) { callback(makeProxy(meta)); })
                                     .catch(function() { callback(undefined); });
                                }
                                return p.then(makeProxy);
                            },
                            getViews: function(fetchProperties) {
                                if (window._isServiceWorkerContext) return [];
                                return [window];
                            },
                            onInstalled: createEvent(contextExtId, "runtime.onInstalled"),
                            onStartup: createEvent(contextExtId, "runtime.onStartup"),
                            onSuspend: createEvent(contextExtId, "runtime.onSuspend"),
                            onSuspendCanceled: createEvent(contextExtId, "runtime.onSuspendCanceled"),
                            onUpdateAvailable: createEvent(contextExtId, "runtime.onUpdateAvailable"),
                            onMessage: createEvent(contextExtId, "runtime.onMessage"),
                            onMessageExternal: createEvent(contextExtId, "runtime.onMessageExternal"),
                            onUserScriptMessage: createEvent(contextExtId, "runtime.onUserScriptMessage"),
                            onConnect: createEvent(contextExtId, "runtime.onConnect"),
                            onConnectExternal: createEvent(contextExtId, "runtime.onConnectExternal"),
                            onUserScriptConnect: createEvent(contextExtId, "runtime.onUserScriptConnect"),
                            sendMessage: function() {
                                let targetExtensionId = contextExtId;
                                let message = arguments[0];
                                let options = arguments[1];
                                let responseCallback = arguments[2];

                                if (typeof arguments[0] === "string" && arguments.length > 1 && typeof arguments[1] !== "function") {
                                    targetExtensionId = arguments[0];
                                    message = arguments[1];
                                    options = arguments[2];
                                    responseCallback = arguments[3];
                                }

                                if (typeof options === "function") {
                                    responseCallback = options;
                                    options = null;
                                }
                                if (typeof message === "function") {
                                    responseCallback = message;
                                    message = arguments[0];
                                }

                                const p = bridgeCall("runtime.sendMessage", [targetExtensionId, message]);
                                if (responseCallback) {
                                    p.then(res => {
                                        try { responseCallback(res); } catch(e) { console.error("runtime.sendMessage callback error", e); }
                                    });
                                }
                                return p;
                            },
                            connect: function() {
                                let targetExtensionId = contextExtId;
                                let connectInfo = null;

                                if (arguments.length === 1) {
                                    if (typeof arguments[0] === "string") {
                                        targetExtensionId = arguments[0];
                                    } else if (typeof arguments[0] === "object") {
                                        connectInfo = arguments[0];
                                    }
                                } else if (arguments.length >= 2) {
                                    targetExtensionId = arguments[0];
                                    connectInfo = arguments[1];
                                }

                                const portName = (connectInfo && connectInfo.name) || "";
                                const channelId = "port_" + Math.random().toString(36).substring(2, 9) + "_" + Date.now();

                                const port = {
                                    name: portName,
                                    disconnect: function() {
                                        bridgeCall("runtime.portDisconnect", [channelId]);
                                    },
                                    onDisconnect: {
                                        listeners: [],
                                        addListener: function(cb) { this.listeners.push(cb); },
                                        removeListener: function(cb) { this.listeners = this.listeners.filter(l => l !== cb); }
                                    },
                                    onMessage: {
                                        listeners: [],
                                        addListener: function(cb) { this.listeners.push(cb); },
                                        removeListener: function(cb) { this.listeners = this.listeners.filter(l => l !== cb); }
                                    },
                                    postMessage: function(msg) {
                                        bridgeCall("runtime.portPostMessage", [channelId, msg]);
                                    }
                                };
                                window._ports = window._ports || {};
                                window._ports[channelId] = port;
                                bridgeCall("runtime.portConnect", [targetExtensionId, channelId, portName]);
                                return port;
                            }
                        },
                        storage: {
                            onChanged: createEvent(contextExtId, "storage.onChanged"),
                            local: makeStorageArea("local"),
                            sync: makeStorageArea("sync"),
                            session: makeStorageArea("session"),
                            managed: makeStorageArea("managed")
                        },
                        tabs: {
                            onUpdated: createEvent(contextExtId, "tabs.onUpdated"),
                            onActivated: createEvent(contextExtId, "tabs.onActivated"),
                            onCreated: createEvent(contextExtId, "tabs.onCreated"),
                            onRemoved: createEvent(contextExtId, "tabs.onRemoved"),
                            get: makeAsyncApi("tabs.get"),
                            query: makeAsyncApi("tabs.query"),
                            create: makeAsyncApi("tabs.create"),
                            remove: makeAsyncApi("tabs.remove"),
                            reload: makeAsyncApi("tabs.reload"),
                            update: makeAsyncApi("tabs.update"),
                            duplicate: makeAsyncApi("tabs.duplicate"),
                            move: makeAsyncApi("tabs.move"),
                            highlight: makeAsyncApi("tabs.highlight"),
                            detectLanguage: makeAsyncApi("tabs.detectLanguage"),
                            discard: makeAsyncApi("tabs.discard"),
                            getZoomSettings: makeAsyncApi("tabs.getZoomSettings"),
                            setZoomSettings: makeAsyncApi("tabs.setZoomSettings"),
                            getZoom: makeAsyncApi("tabs.getZoom"),
                            setZoom: makeAsyncApi("tabs.setZoom"),
                            group: makeAsyncApi("tabs.group"),
                            ungroup: makeAsyncApi("tabs.ungroup"),
                            executeScript: function(tabId, details, callback) {
                                let resolvedTabId = tabId;
                                let resolvedDetails = details;
                                let resolvedCallback = callback;
                                if (typeof tabId === "object") {
                                    resolvedCallback = details;
                                    resolvedDetails = tabId;
                                    resolvedTabId = null;
                                }
                                const p = bridgeCall("tabs.executeScript", [resolvedTabId, resolvedDetails || {}]);
                                if (resolvedCallback) p.then(resolvedCallback);
                                return p;
                            },
                            insertCSS: function(tabId, details, callback) {
                                let resolvedTabId = tabId;
                                let resolvedDetails = details;
                                let resolvedCallback = callback;
                                if (typeof tabId === "object") {
                                    resolvedCallback = details;
                                    resolvedDetails = tabId;
                                    resolvedTabId = null;
                                }
                                const p = bridgeCall("tabs.insertCSS", [resolvedTabId, resolvedDetails || {}]);
                                if (resolvedCallback) p.then(resolvedCallback);
                                return p;
                            },
                            removeCSS: function(tabId, details, callback) {
                                let resolvedTabId = tabId;
                                let resolvedDetails = details;
                                let resolvedCallback = callback;
                                if (typeof tabId === "object") {
                                    resolvedCallback = details;
                                    resolvedDetails = tabId;
                                    resolvedTabId = null;
                                }
                                const p = bridgeCall("tabs.removeCSS", [resolvedTabId, resolvedDetails || {}]);
                                if (resolvedCallback) p.then(resolvedCallback);
                                return p;
                            },
                            goBack: makeAsyncApi("tabs.goBack"),
                            goForward: makeAsyncApi("tabs.goForward"),
                            captureVisibleTab: function(windowId, options, callback) {
                                let resolvedWinId = (typeof windowId === "number") ? windowId : -1;
                                let resolvedOpts = (typeof windowId === "object" && windowId !== null) ? windowId : ((typeof options === "object" && options !== null) ? options : {});
                                let cb = (typeof windowId === "function") ? windowId : ((typeof options === "function") ? options : callback);
                                const p = bridgeCall("tabs.captureVisibleTab", [resolvedWinId, resolvedOpts]);
                                if (cb) p.then(cb);
                                return p;
                            },
                            sendMessage: function(tabId, message, options, callback) {
                                const finalCb = (typeof options === "function") ? options : callback;
                                const hasFrameId = options && typeof options === "object" && Number.isInteger(options.frameId);
                                const hasDocumentId = options && typeof options === "object" &&
                                    typeof options.documentId === "string" && options.documentId.length > 0;
                                if (hasFrameId && hasDocumentId) {
                                    const rejected = Promise.reject(new Error("Cannot specify both frameId and documentId."));
                                    if (finalCb) rejected.catch(function() { finalCb(undefined); });
                                    return rejected;
                                }
                                const targetOptions = hasFrameId
                                    ? { frameId: options.frameId }
                                    : (hasDocumentId ? { documentId: options.documentId } : null);
                                const p = bridgeCall("tabs.sendMessage", [
                                    tabId != null ? tabId.toString() : "",
                                    message,
                                    targetOptions
                                ]);
                                if (finalCb) p.then(finalCb);
                                return p;
                            },
                            connect: function(tabId, connectInfo) {
                                const portName = (connectInfo && connectInfo.name) || "";
                                const channelId = "port_" + Math.random().toString(36).substring(2, 9) + "_" + Date.now();

                                const port = {
                                    name: portName,
                                    disconnect: function() {
                                        bridgeCall("runtime.portDisconnect", [channelId]);
                                    },
                                    onDisconnect: {
                                        listeners: [],
                                        addListener: function(cb) { this.listeners.push(cb); },
                                        removeListener: function(cb) { this.listeners = this.listeners.filter(l => l !== cb); }
                                    },
                                    onMessage: {
                                        listeners: [],
                                        addListener: function(cb) { this.listeners.push(cb); },
                                        removeListener: function(cb) { this.listeners = this.listeners.filter(l => l !== cb); }
                                    },
                                    postMessage: function(msg) {
                                        bridgeCall("runtime.portPostMessage", [channelId, msg]);
                                    }
                                };
                                window._ports = window._ports || {};
                                window._ports[channelId] = port;
                                const frameId = (connectInfo && typeof connectInfo === "object" && Number.isInteger(connectInfo.frameId)) ? connectInfo.frameId : null;
                                const documentId = (connectInfo && typeof connectInfo === "object" && typeof connectInfo.documentId === "string" && connectInfo.documentId.length > 0) ? connectInfo.documentId : null;
                                if (frameId !== null && documentId !== null) {
                                    throw new Error("frameId and documentId are mutually exclusive.");
                                }
                                bridgeCall("tabs.connect", [
                                    tabId != null ? tabId.toString() : "",
                                    channelId,
                                    portName,
                                    frameId,
                                    documentId
                                ]).catch(function() {
                                    if (window._extPortDisconnect) {
                                        window._extPortDisconnect(channelId);
                                    }
                                });
                                return port;
                            }
                        },
                        windows: {
                            onCreated: createEvent(contextExtId, "windows.onCreated"),
                            onRemoved: createEvent(contextExtId, "windows.onRemoved"),
                            onFocusChanged: createEvent(contextExtId, "windows.onFocusChanged"),
                            get: function(windowId, queryOptions, callback) {
                                if (typeof queryOptions === "function") {
                                    callback = queryOptions;
                                    queryOptions = null;
                                }
                                const populate = queryOptions ? !!queryOptions.populate : false;
                                const p = bridgeCall("windows.get", [windowId, populate]);
                                if (callback) p.then(callback);
                                return p;
                            },
                            getCurrent: function(queryOptions, callback) {
                                if (typeof queryOptions === "function") {
                                    callback = queryOptions;
                                    queryOptions = null;
                                }
                                const populate = queryOptions ? !!queryOptions.populate : false;
                                const p = bridgeCall("windows.getCurrent", [populate]);
                                if (callback) p.then(callback);
                                return p;
                            },
                            getLastFocused: function(queryOptions, callback) {
                                if (typeof queryOptions === "function") {
                                    callback = queryOptions;
                                    queryOptions = null;
                                }
                                const populate = queryOptions ? !!queryOptions.populate : false;
                                const p = bridgeCall("windows.getLastFocused", [populate]);
                                if (callback) p.then(callback);
                                return p;
                            },
                            getAll: function(queryOptions, callback) {
                                if (typeof queryOptions === "function") {
                                    callback = queryOptions;
                                    queryOptions = null;
                                }
                                const populate = queryOptions ? !!queryOptions.populate : false;
                                const p = bridgeCall("windows.getAll", [populate]);
                                if (callback) p.then(callback);
                                return p;
                            },
                            create: makeAsyncApi("windows.create"),
                            update: makeAsyncApi("windows.update"),
                            remove: makeAsyncApi("windows.remove")
                        },
                        tabGroups: {
                            onCreated: createEvent(contextExtId, "tabGroups.onCreated"),
                            onUpdated: createEvent(contextExtId, "tabGroups.onUpdated"),
                            onMoved: createEvent(contextExtId, "tabGroups.onMoved"),
                            onRemoved: createEvent(contextExtId, "tabGroups.onRemoved"),
                            query: function(queryInfo, callback) {
                                if (typeof queryInfo === "function") {
                                    callback = queryInfo;
                                    queryInfo = null;
                                }
                                const p = bridgeCall("tabGroups.query", [queryInfo || {}]);
                                if (callback) p.then(callback);
                                return p;
                            },
                            get: makeAsyncApi("tabGroups.get"),
                            update: function(groupId, updateProperties, callback) {
                                const p = bridgeCall("tabGroups.update", [groupId, updateProperties || {}]);
                                if (callback) p.then(callback);
                                return p;
                            },
                            move: function(groupId, moveProperties, callback) {
                                const p = bridgeCall("tabGroups.move", [groupId, moveProperties || {}]);
                                if (callback) p.then(callback);
                                return p;
                            }
                        },
                        sessions: {
                            onChanged: createEvent(contextExtId, "sessions.onChanged"),
                            restore: function(sessionId, callback) {
                                if (typeof sessionId === "function") {
                                    callback = sessionId;
                                    sessionId = null;
                                }
                                const p = bridgeCall("sessions.restore", sessionId ? [sessionId] : []);
                                if (callback) p.then(callback);
                                return p;
                            },
                            getDevices: makeAsyncApi("sessions.getDevices"),
                            getRecentlyClosed: makeAsyncApi("sessions.getRecentlyClosed")
                        },
                        scripting: {
                            executeScript: function(spec, callback) {
                                if (spec && spec.func && typeof spec.func === "function") {
                                    spec.func = spec.func.toString();
                                }
                                const p = bridgeCall("scripting.executeScript", [spec]);
                                if (callback) p.then(callback);
                                return p;
                            },
                            insertCSS: makeAsyncApi("scripting.insertCSS"),
                            removeCSS: makeAsyncApi("scripting.removeCSS"),
                            registerContentScripts: makeAsyncApi("scripting.registerContentScripts"),
                            unregisterContentScripts: makeAsyncApi("scripting.unregisterContentScripts"),
                            updateContentScripts: makeAsyncApi("scripting.updateContentScripts"),
                            getRegisteredContentScripts: makeAsyncApi("scripting.getRegisteredContentScripts")
                        },
                        cookies: {
                            get: makeAsyncApi("cookies.get"),
                            getAll: makeAsyncApi("cookies.getAll"),
                            set: makeAsyncApi("cookies.set"),
                            remove: makeAsyncApi("cookies.remove"),
                            getAllCookieStores: makeAsyncApi("cookies.getAllCookieStores"),
                            onChanged: createEvent(contextExtId, "cookies.onChanged")
                        },
                        declarativeNetRequest: {
                            updateDynamicRules: makeAsyncApi("declarativeNetRequest.updateDynamicRules"),
                            getDynamicRules: makeAsyncApi("declarativeNetRequest.getDynamicRules"),
                            updateSessionRules: makeAsyncApi("declarativeNetRequest.updateSessionRules"),
                            getSessionRules: makeAsyncApi("declarativeNetRequest.getSessionRules"),
                            getMatchedRules: makeAsyncApi("declarativeNetRequest.getMatchedRules"),
                            isRegexSupported: makeAsyncApi("declarativeNetRequest.isRegexSupported"),
                            setExtensionActionOptions: makeAsyncApi("declarativeNetRequest.setExtensionActionOptions"),
                            getEnabledRulesets: makeAsyncApi("declarativeNetRequest.getEnabledRulesets"),
                            updateEnabledRulesets: makeAsyncApi("declarativeNetRequest.updateEnabledRulesets"),
                            testMatchOutcome: makeAsyncApi("declarativeNetRequest.testMatchOutcome")
                        },
                        webRequest: {
                            onBeforeRequest: createEvent(contextExtId, "webRequest.onBeforeRequest"),
                            onBeforeSendHeaders: createEvent(contextExtId, "webRequest.onBeforeSendHeaders"),
                            onSendHeaders: createEvent(contextExtId, "webRequest.onSendHeaders"),
                            onHeadersReceived: createEvent(contextExtId, "webRequest.onHeadersReceived"),
                            onAuthRequired: createEvent(contextExtId, "webRequest.onAuthRequired"),
                            onResponseStarted: createEvent(contextExtId, "webRequest.onResponseStarted"),
                            onBeforeRedirect: createEvent(contextExtId, "webRequest.onBeforeRedirect"),
                            onCompleted: createEvent(contextExtId, "webRequest.onCompleted"),
                            onErrorOccurred: createEvent(contextExtId, "webRequest.onErrorOccurred")
                        },
                        permissions: {
                            contains: makeAsyncApi("permissions.contains"),
                            request: makeAsyncApi("permissions.request"),
                            remove: makeAsyncApi("permissions.remove"),
                            getAll: makeAsyncApi("permissions.getAll"),
                            onAdded: createEvent(contextExtId, "permissions.onAdded"),
                            onRemoved: createEvent(contextExtId, "permissions.onRemoved")
                        },
                        bookmarks: {
                            get: makeAsyncApi("bookmarks.get"),
                            getChildren: makeAsyncApi("bookmarks.getChildren"),
                            getRecent: makeAsyncApi("bookmarks.getRecent"),
                            getTree: makeAsyncApi("bookmarks.getTree"),
                            getSubTree: makeAsyncApi("bookmarks.getSubTree"),
                            search: makeAsyncApi("bookmarks.search"),
                            create: makeAsyncApi("bookmarks.create"),
                            update: makeAsyncApi("bookmarks.update"),
                            move: makeAsyncApi("bookmarks.move"),
                            remove: makeAsyncApi("bookmarks.remove"),
                            removeTree: makeAsyncApi("bookmarks.removeTree"),
                            onCreated: createEvent(contextExtId, "bookmarks.onCreated"),
                            onRemoved: createEvent(contextExtId, "bookmarks.onRemoved"),
                            onChanged: createEvent(contextExtId, "bookmarks.onChanged"),
                            onMoved: createEvent(contextExtId, "bookmarks.onMoved"),
                            onChildrenReordered: createEvent(contextExtId, "bookmarks.onChildrenReordered"),
                            onImportBegan: createEvent(contextExtId, "bookmarks.onImportBegan"),
                            onImportEnded: createEvent(contextExtId, "bookmarks.onImportEnded")
                        },
                        history: {
                            search: makeAsyncApi("history.search"),
                            getVisits: makeAsyncApi("history.getVisits"),
                            addUrl: makeAsyncApi("history.addUrl"),
                            deleteUrl: makeAsyncApi("history.deleteUrl"),
                            deleteRange: makeAsyncApi("history.deleteRange"),
                            deleteAll: makeAsyncApi("history.deleteAll"),
                            onVisited: createEvent(contextExtId, "history.onVisited"),
                            onVisitRemoved: createEvent(contextExtId, "history.onVisitRemoved")
                        },
                        downloads: {
                            download: makeAsyncApi("downloads.download"),
                            search: makeAsyncApi("downloads.search"),
                            pause: makeAsyncApi("downloads.pause"),
                            resume: makeAsyncApi("downloads.resume"),
                            cancel: makeAsyncApi("downloads.cancel"),
                            removeFile: makeAsyncApi("downloads.removeFile"),
                            erase: makeAsyncApi("downloads.erase"),
                            open: makeAsyncApi("downloads.open"),
                            show: makeAsyncApi("downloads.show"),
                            showDefaultFolder: makeAsyncApi("downloads.showDefaultFolder"),
                            setShelfEnabled: makeAsyncApi("downloads.setShelfEnabled"),
                            acceptDanger: makeAsyncApi("downloads.acceptDanger"),
                            getFileIcon: makeAsyncApi("downloads.getFileIcon"),
                            onCreated: createEvent(contextExtId, "downloads.onCreated"),
                            onErased: createEvent(contextExtId, "downloads.onErased"),
                            onChanged: createEvent(contextExtId, "downloads.onChanged")
                        },
                        notifications: {
                            create: makeAsyncApi("notifications.create"),
                            update: makeAsyncApi("notifications.update"),
                            clear: makeAsyncApi("notifications.clear"),
                            getAll: makeAsyncApi("notifications.getAll"),
                            getPermissionLevel: makeAsyncApi("notifications.getPermissionLevel"),
                            onClosed: createEvent(contextExtId, "notifications.onClosed"),
                            onClicked: createEvent(contextExtId, "notifications.onClicked"),
                            onButtonClicked: createEvent(contextExtId, "notifications.onButtonClicked")
                        },
                        idle: {
                            queryState: makeAsyncApi("idle.queryState"),
                            setDetectionInterval: makeAsyncApi("idle.setDetectionInterval"),
                            onStateChanged: createEvent(contextExtId, "idle.onStateChanged")
                        },
                        management: {
                            get: makeAsyncApi("management.get"),
                            getSelf: makeAsyncApi("management.getSelf"),
                            getAll: makeAsyncApi("management.getAll"),
                            setEnabled: makeAsyncApi("management.setEnabled"),
                            uninstall: makeAsyncApi("management.uninstall"),
                            uninstallSelf: makeAsyncApi("management.uninstallSelf"),
                            getPermissionWarningsById: makeAsyncApi("management.getPermissionWarningsById"),
                            getPermissionWarningsByManifest: makeAsyncApi("management.getPermissionWarningsByManifest"),
                            onInstalled: createEvent(contextExtId, "management.onInstalled"),
                            onUninstalled: createEvent(contextExtId, "management.onUninstalled"),
                            onEnabled: createEvent(contextExtId, "management.onEnabled"),
                            onDisabled: createEvent(contextExtId, "management.onDisabled")
                        },
                        alarms: {
                            create: function(name, alarmInfo, callback) {
                                let resolvedName = name;
                                let resolvedAlarmInfo = alarmInfo;
                                let resolvedCallback = callback;
                                if (typeof name === "object") {
                                    resolvedCallback = alarmInfo;
                                    resolvedAlarmInfo = name;
                                    resolvedName = "";
                                }
                                if (typeof name === "function") {
                                    resolvedCallback = name;
                                    resolvedAlarmInfo = {};
                                    resolvedName = "";
                                }
                                if (typeof alarmInfo === "function") {
                                    resolvedCallback = alarmInfo;
                                    resolvedAlarmInfo = {};
                                }
                                const p = bridgeCall("alarms.create", [resolvedName, resolvedAlarmInfo]);
                                if (resolvedCallback) p.then(resolvedCallback);
                                return p;
                            },
                            get: function(name, callback) {
                                let resolvedName = name;
                                let resolvedCallback = callback;
                                if (typeof name === "function") {
                                    resolvedCallback = name;
                                    resolvedName = "";
                                }
                                const p = bridgeCall("alarms.get", [resolvedName]);
                                if (resolvedCallback) p.then(resolvedCallback);
                                return p;
                            },
                            getAll: makeAsyncApi("alarms.getAll"),
                            clear: function(name, callback) {
                                let resolvedName = name;
                                let resolvedCallback = callback;
                                if (typeof name === "function") {
                                    resolvedCallback = name;
                                    resolvedName = "";
                                }
                                const p = bridgeCall("alarms.clear", [resolvedName]);
                                if (resolvedCallback) p.then(resolvedCallback);
                                return p;
                            },
                            clearAll: makeAsyncApi("alarms.clearAll"),
                            onAlarm: createEvent(contextExtId, "alarms.onAlarm")
                        },
                        system: {
                            cpu: {
                                getInfo: makeAsyncApi("system.cpu.getInfo")
                            },
                            memory: {
                                getInfo: makeAsyncApi("system.memory.getInfo")
                            },
                            storage: {
                                getInfo: makeAsyncApi("system.storage.getInfo")
                            }
                        },
                        tts: {
                            speak: function(utterance, options, callback) {
                                if (typeof options === "function") {
                                    callback = options;
                                    options = {};
                                }
                                const p = bridgeCall("tts.speak", [utterance, options || {}]);
                                if (callback) p.then(callback);
                                return p;
                            },
                            stop: makeAsyncApi("tts.stop"),
                            pause: makeAsyncApi("tts.pause"),
                            resume: makeAsyncApi("tts.resume"),
                            isSpeaking: makeAsyncApi("tts.isSpeaking"),
                            getVoices: makeAsyncApi("tts.getVoices")
                        },
                        topSites: {
                            get: makeAsyncApi("topSites.get")
                        },
                        search: {
                            query: makeAsyncApi("search.query")
                        },
                        i18n: {
                            getMessage: function(messageName, substitutions) {
                                const msgs = window._swiftExtensionMessages[contextExtId] || {};
                                const item = msgs[messageName] || msgs[messageName.toLowerCase()];
                                if (!item) return messageName;
                                let msg = item.message || "";
                                if (!msg) return messageName;

                                if (substitutions) {
                                    if (!Array.isArray(substitutions)) {
                                        substitutions = [substitutions];
                                    }
                                    substitutions.forEach((sub, i) => {
                                        const phIndex = i + 1;
                                        msg = msg.replace(new RegExp("\\$" + phIndex, "g"), String(sub));
                                    });
                                }

                                if (item.placeholders) {
                                    for (const phName in item.placeholders) {
                                        const phObj = item.placeholders[phName];
                                        const content = phObj.content || "";
                                        let resolvedContent = content;
                                        if (substitutions) {
                                            substitutions.forEach((sub, i) => {
                                                const phIndex = i + 1;
                                                resolvedContent = resolvedContent.replace(new RegExp("\\$" + phIndex, "g"), String(sub));
                                            });
                                        }
                                        msg = msg.split("$" + phName + "$").join(resolvedContent);
                                        msg = msg.split("$" + phName.toLowerCase() + "$").join(resolvedContent);
                                    }
                                }
                                return msg;
                            },
                            getAcceptLanguages: function(callback) {
                                const langs = [navigator.language || "en-US"];
                                if (callback) callback(langs);
                                return Promise.resolve(langs);
                            },
                            getUILanguage: function() {
                                return navigator.language || "en-US";
                            },
                            detectLanguage: function(text, callback) {
                                const res = { isReliable: true, languages: [{ language: "en", percentage: 100 }] };
                                if (callback) callback(res);
                                return Promise.resolve(res);
                            }
                        },
                        extension: {
                            getURL: function(path) {
                                return contextObj.runtime.getURL(path);
                            },
                            getBackgroundPage: function(callback) {
                                return contextObj.runtime.getBackgroundPage(callback);
                            },
                            getViews: function(fetchProperties) {
                                return contextObj.runtime.getViews(fetchProperties);
                            },
                            isAllowedIncognitoAccess: function(callback) {
                                const p = bridgeCall("extension.isAllowedIncognitoAccess", []);
                                if (callback) p.then(callback);
                                return p;
                            },
                            isAllowedFileSchemeAccess: function(callback) {
                                const p = bridgeCall("extension.isAllowedFileSchemeAccess", []);
                                if (callback) p.then(callback);
                                return p;
                            },
                            sendMessage: function(msg, cb) {
                                return contextObj.runtime.sendMessage(msg, cb);
                            },
                            connect: function(info) {
                                return contextObj.runtime.connect(info);
                            },
                            inIncognitoContext: false
                        },
                        webNavigation: {
                            getFrame: makeAsyncApi("webNavigation.getFrame"),
                            getAllFrames: makeAsyncApi("webNavigation.getAllFrames"),
                            onCompleted: createEvent(contextExtId, "webNavigation.onCompleted"),
                            onHistoryStateUpdated: createEvent(contextExtId, "webNavigation.onHistoryStateUpdated"),
                            onBeforeNavigate: createEvent(contextExtId, "webNavigation.onBeforeNavigate"),
                            onCommitted: createEvent(contextExtId, "webNavigation.onCommitted"),
                            onDOMContentLoaded: createEvent(contextExtId, "webNavigation.onDOMContentLoaded"),
                            onErrorOccurred: createEvent(contextExtId, "webNavigation.onErrorOccurred"),
                            onCreatedNavigationTarget: createEvent(contextExtId, "webNavigation.onCreatedNavigationTarget"),
                            onReferenceFragmentUpdated: createEvent(contextExtId, "webNavigation.onReferenceFragmentUpdated"),
                            onTabReplaced: createEvent(contextExtId, "webNavigation.onTabReplaced")
                        },
                        declarativeContent: {
                            onPageChanged: createEvent(contextExtId, "declarativeContent.onPageChanged"),
                            ShowPageAction: function() { throw new Error("declarativeContent actions are not implemented on Android WebView"); },
                            PageStateMatcher: function() { throw new Error("declarativeContent matchers are not implemented on Android WebView"); }
                        },
                        identity: {
                            getAuthToken: makeAsyncApi("identity.getAuthToken"),
                            getProfileUserInfo: makeAsyncApi("identity.getProfileUserInfo"),
                            getRedirectURL: function(path) {
                                return Promise.reject(new Error("Chrome Identity API requires a real OAuth/account broker"));
                            },
                            launchWebAuthFlow: makeAsyncApi("identity.launchWebAuthFlow"),
                            removeCachedAuthToken: makeAsyncApi("identity.removeCachedAuthToken"),
                            clearAllCachedAuthTokens: makeAsyncApi("identity.clearAllCachedAuthTokens"),
                            onSignInChanged: createEvent(contextExtId, "identity.onSignInChanged")
                        },
                        desktopCapture: {
                            chooseDesktopMedia: function(sources, targetTab, callback) {
                                const finalCb = typeof targetTab === "function" ? targetTab : callback;
                                const p = bridgeCall("desktopCapture.chooseDesktopMedia", [sources]);
                                if (finalCb) p.then(streamId => finalCb(streamId));
                                return p;
                            },
                            cancelChooseDesktopMedia: makeAsyncApi("desktopCapture.cancelChooseDesktopMedia")
                        },
                        debugger: {
                            attach: makeAsyncApi("debugger.attach"),
                            detach: makeAsyncApi("debugger.detach"),
                            sendCommand: makeAsyncApi("debugger.sendCommand"),
                            getTargets: makeAsyncApi("debugger.getTargets"),
                            onEvent: createEvent(contextExtId, "debugger.onEvent"),
                            onDetach: createEvent(contextExtId, "debugger.onDetach")
                        },
                        devtools: {
                            inspectedWindow: {
                                tabId: null,
                                eval: makeAsyncApi("devtools.inspectedWindow.eval"),
                                reload: makeAsyncApi("devtools.inspectedWindow.reload")
                            },
                            network: {
                                getHAR: makeAsyncApi("devtools.network.getHAR"),
                                onRequestFinished: createEvent(contextExtId, "devtools.network.onRequestFinished")
                            },
                            panels: {
                                create: makeAsyncApi("devtools.panels.create"),
                                elements: { createSidebarPane: makeAsyncApi("devtools.panels.elements.createSidebarPane") }
                            }
                        },
                        privacy: {
                            network: { networkPredictionEnabled: { get: makeAsyncApi("privacy.network.get"), set: makeAsyncApi("privacy.network.set") } },
                            services: { alternateErrorPagesEnabled: { get: makeAsyncApi("privacy.services.get"), set: makeAsyncApi("privacy.services.set") } },
                            websites: { thirdPartyCookiesAllowed: { get: makeAsyncApi("privacy.websites.get"), set: makeAsyncApi("privacy.websites.set") } }
                        },
                        power: {
                            requestKeepAwake: makeAsyncApi("power.requestKeepAwake"),
                            releaseKeepAwake: makeAsyncApi("power.releaseKeepAwake")
                        },
                        gcm: {
                            register: makeAsyncApi("gcm.register"),
                            unregister: makeAsyncApi("gcm.unregister"),
                            send: makeAsyncApi("gcm.send"),
                            onMessage: createEvent(contextExtId, "gcm.onMessage"),
                            onMessagesDeleted: createEvent(contextExtId, "gcm.onMessagesDeleted"),
                            onSendError: createEvent(contextExtId, "gcm.onSendError")
                        },
                        instanceID: {
                            getID: makeAsyncApi("instanceID.getID"),
                            getToken: makeAsyncApi("instanceID.getToken"),
                            deleteToken: makeAsyncApi("instanceID.deleteToken"),
                            deleteID: makeAsyncApi("instanceID.deleteID")
                        },
                        browsingData: {
                            remove: makeAsyncApi("browsingData.remove"),
                            removeCache: makeAsyncApi("browsingData.removeCache"),
                            removeCookies: makeAsyncApi("browsingData.removeCookies"),
                            removeHistory: makeAsyncApi("browsingData.removeHistory"),
                            removeDownloads: makeAsyncApi("browsingData.removeDownloads"),
                            removeBookmarks: makeAsyncApi("browsingData.removeBookmarks"),
                            settings: makeAsyncApi("browsingData.settings")
                        },
                        offscreen: {
                            createDocument: makeAsyncApi("offscreen.createDocument"),
                            closeDocument: makeAsyncApi("offscreen.closeDocument"),
                            hasDocument: makeAsyncApi("offscreen.hasDocument")
                        },
                        userScripts: {
                            register: makeAsyncApi("userScripts.register"),
                            unregister: makeAsyncApi("userScripts.unregister"),
                            getScripts: makeAsyncApi("userScripts.getScripts"),
                            execute: makeAsyncApi("userScripts.execute"),
                            configureWorld: makeAsyncApi("userScripts.configureWorld"),
                            getWorldConfigurations: makeAsyncApi("userScripts.getWorldConfigurations"),
                            resetWorldConfiguration: makeAsyncApi("userScripts.resetWorldConfiguration")
                        }
                    };

                    return contextObj;
                };

                const extContext = window._swiftGetExtensionContext(extId);
                // Both namespaces point to the exact same extension capability object.
                // This keeps promise-based Chrome APIs and browser.* compatibility on one backend.
                window.browser = extContext;
                window.chrome = extContext;
                if (typeof self !== 'undefined') {
                    self.browser = extContext;
                    self.chrome = extContext;
                }

                window._ports = window._ports || {};

                window._extPortMessage = function(channelId, msg) {
                    const port = window._ports[channelId];
                    if (port) {
                        port.onMessage.listeners.forEach(cb => {
                            try { cb(msg); } catch(e) {}
                        });
                    }
                };

                window._extPortDisconnect = function(channelId) {
                    const port = window._ports[channelId];
                    if (port) {
                        delete window._ports[channelId];
                        port.onDisconnect.listeners.forEach(cb => {
                            try { cb(); } catch(e) {}
                        });
                    }
                };

                window._extPortConnect = function(channelId, portName, senderInfo) {
                    const sender = (senderInfo && typeof senderInfo === "object")
                        ? senderInfo
                        : { id: senderInfo, extensionId: senderInfo };
                    const port = {
                        name: portName,
                        sender: sender,
                        disconnect: function() {
                            window._orionPostMessage("runtime.portDisconnect", [channelId], "dis_" + Date.now());
                        },
                        onDisconnect: {
                            listeners: [],
                            addListener: function(cb) { this.listeners.push(cb); },
                            removeListener: function(cb) { this.listeners = this.listeners.filter(l => l !== cb); }
                        },
                        onMessage: {
                            listeners: [],
                            addListener: function(cb) { this.listeners.push(cb); },
                            removeListener: function(cb) { this.listeners = this.listeners.filter(l => l !== cb); }
                        },
                        postMessage: function(msg) {
                            window._orionPostMessage("runtime.portPostMessage", [channelId, msg], "msg_" + Date.now());
                        }
                    };
                    window._ports[channelId] = port;

                    const extEvents = window._swiftExtensionEvents[extId] || {};
                    const dedicated = sender && sender.contextType === "USER_SCRIPT";
                    const connListeners = extEvents[dedicated ? "runtime.onUserScriptConnect" : "runtime.onConnect"] || [];
                    connListeners.forEach(cb => {
                        try { cb(port); } catch(e) {}
                    });
                };

                window._extOnMessage = function(targetExtId, message, sender, callbackId) {
                    if (message && message.type === "EVENT_DISPATCH") {
                        const eventName = message.eventName;
                        const data = message.data || {};
                        window._swiftDispatchEvent(targetExtId, eventName, data);
                        return;
                    }
                    const extEvents = window._swiftExtensionEvents[targetExtId] || {};
                    const dedicated = sender && sender.contextType === "USER_SCRIPT";
                    const listeners = extEvents[dedicated ? "runtime.onUserScriptMessage" : "runtime.onMessage"] || [];
                    const sendResponse = function(resp) {
                        if (callbackId) {
                            try {
                                window._orionPostMessage("runtime.response", [callbackId, resp], "resp_" + Date.now());
                            } catch(e) {
                                console.error("sendResponse error: ", e);
                            }
                        }
                    };
                    listeners.forEach(cb => {
                        try { cb(message, sender, sendResponse); } catch(e) {}
                    });
                };
            })();
        """.trimIndent()
    }
}
