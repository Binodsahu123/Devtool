package com.swift.browser.extensionengine

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.swift.browser.tabengine.api.TabEngineApi
import com.swift.browser.tabengine.engine.TabEngine
import com.swift.browser.tabengine.model.TabModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ChromeApiEngineIntegrationTest {

    private lateinit var context: Context
    private lateinit var registry: ExtensionRegistry
    private lateinit var messageBus: MessageBus
    private lateinit var eventManager: EventManager
    private lateinit var permissionManager: PermissionManager
    private lateinit var permissionAdapter: ExtensionPermissionAdapter
    private lateinit var storageBackend: ExtensionStorageBackend
    private lateinit var tabEngine: TabEngineApi
    private lateinit var mockCookieEngine: ExtensionCookieAdapterTest.FakeCookieEngine
    private lateinit var tabsAdapter: ExtensionTabsAdapter
    private lateinit var windowsAdapter: ExtensionWindowsAdapter
    private lateinit var tabGroupsAdapter: ExtensionTabGroupsAdapter
    private lateinit var sessionsAdapter: ExtensionSessionsAdapter
    private lateinit var cookieAdapter: ExtensionCookieAdapter
    private lateinit var bookmarksAdapter: ExtensionBookmarksAdapter
    private lateinit var historyAdapter: ExtensionHistoryAdapter
    private lateinit var downloadsAdapter: ExtensionDownloadsAdapter
    private lateinit var dnrAdapter: ExtensionDnrAdapter
    private lateinit var webRequestAdapter: ExtensionWebRequestAdapter
    private lateinit var contentScriptManager: ContentScriptManager
    private lateinit var scriptingAdapter: ExtensionScriptingAdapter
    private lateinit var actionAdapter: ExtensionActionAdapter
    private lateinit var contextMenusAdapter: ExtensionContextMenusAdapter
    private lateinit var commandsAdapter: ExtensionCommandsAdapter
    private lateinit var omniboxAdapter: ExtensionOmniboxAdapter
    private lateinit var sidePanelAdapter: ExtensionSidePanelAdapter
    private lateinit var managementAdapter: ExtensionManagementAdapter
    private lateinit var topSitesAdapter: ExtensionTopSitesAdapter
    private lateinit var idleAdapter: ExtensionIdleAdapter
    private lateinit var ttsAdapter: ExtensionTtsAdapter
    private lateinit var searchAdapter: ExtensionSearchAdapter
    private lateinit var alarmsAdapter: ExtensionAlarmsAdapter
    private lateinit var systemAdapter: ExtensionSystemAdapter
    private lateinit var notificationsAdapter: ExtensionNotificationsAdapter
    private lateinit var webNavigationAdapter: ExtensionWebNavigationAdapter
    private lateinit var router: ExtensionJsBridgeRouter

    private val testExtId = "abcdefghijklmnopqrstuvwxyz123456"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        registry = ExtensionRegistryImpl()
        messageBus = MessageBus()
        eventManager = EventManager(messageBus)

        permissionAdapter = ExtensionPermissionAdapter(context)
        permissionManager = PermissionManager(context, permissionAdapter)
        permissionManager.setRegistry(registry)
        permissionAdapter.apply {
            setRegistry(registry)
            setEventManager(eventManager)
        }

        val database = ExtensionDatabase.getInstance(context)
        val storageManager = StorageManager(database, context, registry)
        storageBackend = ExtensionStorageBackend(storageManager)

        val coreServices = BrowserCoreServices.get(context)
        coreServices.initializeCoreEngines()
        tabEngine = coreServices.tabs

        tabsAdapter = ExtensionTabsAdapter(tabEngine, registry, permissionManager, messageBus, PortManager(messageBus))
        windowsAdapter = ExtensionWindowsAdapter(tabEngine, tabsAdapter, registry, permissionManager)
        tabGroupsAdapter = ExtensionTabGroupsAdapter(tabEngine, registry, permissionManager)
        sessionsAdapter = ExtensionSessionsAdapter(context, tabEngine, tabsAdapter, registry, permissionManager)

        mockCookieEngine = ExtensionCookieAdapterTest.FakeCookieEngine()
        cookieAdapter = ExtensionCookieAdapter(context, permissionAdapter, registry, eventManager, tabEngine, mockCookieEngine)
        bookmarksAdapter = ExtensionBookmarksAdapter(context, permissionManager, registry, eventManager, coreServices.bookmarks)
        historyAdapter = ExtensionHistoryAdapter(permissionManager, registry, eventManager, coreServices.history)
        downloadsAdapter = ExtensionDownloadsAdapter(context, permissionManager, registry, eventManager, coreServices.downloads)
        dnrAdapter = ExtensionDnrAdapter(permissionAdapter, registry)
        webRequestAdapter = ExtensionWebRequestAdapter(permissionAdapter, registry, eventManager, dnrAdapter)
        val scriptInjector = ScriptInjector()
        val cssInjector = CssInjector()
        contentScriptManager = ContentScriptManager(context, permissionManager, scriptInjector, cssInjector, registry)
        val worldAwareScriptExecutor = WorldAwareScriptExecutor()
        scriptingAdapter = ExtensionScriptingAdapter(permissionAdapter, registry, contentScriptManager, scriptInjector, cssInjector, worldAwareScriptExecutor, permissionManager)
        actionAdapter = ExtensionActionAdapter(permissionManager, registry, eventManager)
        contextMenusAdapter = ExtensionContextMenusAdapter(permissionManager, registry, eventManager)
        commandsAdapter = ExtensionCommandsAdapter(permissionManager, registry, eventManager)
        omniboxAdapter = ExtensionOmniboxAdapter(permissionManager, registry, eventManager)
        sidePanelAdapter = ExtensionSidePanelAdapter(permissionManager, registry)
        managementAdapter = ExtensionManagementAdapter(permissionManager, registry, eventManager)
        topSitesAdapter = ExtensionTopSitesAdapter(permissionManager, registry)
        idleAdapter = ExtensionIdleAdapter(permissionManager, registry)
        ttsAdapter = ExtensionTtsAdapter(permissionManager, registry)
        searchAdapter = ExtensionSearchAdapter(permissionManager, registry)
        alarmsAdapter = ExtensionAlarmsAdapter(permissionManager, registry, eventManager)
        systemAdapter = ExtensionSystemAdapter(permissionManager, registry)
        notificationsAdapter = ExtensionNotificationsAdapter(context, permissionManager, registry, eventManager)
        webNavigationAdapter = ExtensionWebNavigationAdapter(permissionAdapter, registry, eventManager, tabEngine)

        router = ExtensionJsBridgeRouter(
            registry = registry,
            permissionAdapter = permissionAdapter,
            storageBackend = storageBackend,
            messageBus = messageBus,
            eventManager = eventManager,
            tabsAdapter = tabsAdapter,
            windowsAdapter = windowsAdapter,
            tabGroupsAdapter = tabGroupsAdapter,
            sessionsAdapter = sessionsAdapter,
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
            browsingDataAdapter = null,
            context = context,
            delegate = null,
            webNavigationAdapter = webNavigationAdapter
        )

        // Register test extension
        val ext = ParsedExtension(
            id = testExtId,
            name = "Test Integration Extension",
            version = "1.0",
            isEnabled = true,
            permissions = listOf("tabs", "cookies", "webNavigation", "scripting", "storage", "history", "downloads", "bookmarks"),
            hostPermissions = listOf("<all_urls>")
        )
        registry.register(ext)
        permissionAdapter.onExtensionRegistered(ext)
    }

    @Test
    fun testTabsApiCreateQueryUpdateRemove() = runBlocking {
        val sender = ExtensionSender(extensionId = testExtId, url = "chrome-extension://$testExtId/background.js")

        // 1. Create tab
        val createArgs = JSONArray().put(JSONObject().apply {
            put("url", "https://example.com")
            put("active", true)
        })
        val createRes = router.handleCall(sender, "tabs.create", createArgs) as JSONObject
        assertTrue(createRes.has("id"))
        assertEquals("https://example.com", createRes.optString("url"))
        val tabIntId = createRes.getInt("id")

        // 2. Query tabs
        val queryArgs = JSONArray().put(JSONObject().apply {
            put("active", true)
        })
        val queryRes = router.handleCall(sender, "tabs.query", queryArgs) as JSONArray
        assertTrue(queryRes.length() >= 1)

        // 3. Update tab
        val updateArgs = JSONArray().put(tabIntId).put(JSONObject().apply {
            put("url", "https://example.org")
        })
        val updateRes = router.handleCall(sender, "tabs.update", updateArgs) as JSONObject
        assertEquals("https://example.org", updateRes.optString("url"))

        // 4. Remove tab
        val removeArgs = JSONArray().put(tabIntId)
        val removeRes = router.handleCall(sender, "tabs.remove", removeArgs)
        assertNotNull(removeRes)
    }

    @Test
    fun testWebNavigationGetFrameAndGetAllFrames() = runBlocking {
        val tab = tabEngine.createTab("https://example.com", "Example", false)
        val intId = TabIdMapper.getIntId(tab.id)
        val sender = ExtensionSender(extensionId = testExtId, url = "chrome-extension://$testExtId/background.js")

        // 1. getFrame
        val getFrameArgs = JSONArray().put(JSONObject().apply {
            put("tabId", intId)
            put("frameId", 0)
        })
        val frameRes = router.handleCall(sender, "webNavigation.getFrame", getFrameArgs) as JSONObject
        assertEquals(0, frameRes.getInt("frameId"))
        assertEquals("https://example.com", frameRes.optString("url"))

        // 2. getAllFrames
        val getAllArgs = JSONArray().put(JSONObject().apply {
            put("tabId", intId)
        })
        val allFramesRes = router.handleCall(sender, "webNavigation.getAllFrames", getAllArgs) as JSONArray
        assertEquals(1, allFramesRes.length())
        assertEquals(0, allFramesRes.getJSONObject(0).getInt("frameId"))
    }

    @Test
    fun testCookiesApiGetSetRemoveWithProfileIsolation() = runBlocking {
        val sender = ExtensionSender(extensionId = testExtId, url = "chrome-extension://$testExtId/background.js")

        // Set cookie
        val setArgs = JSONArray().put(JSONObject().apply {
            put("url", "https://example.com")
            put("name", "sessionToken")
            put("value", "abc123xyz")
        })
        val setRes = router.handleCall(sender, "cookies.set", setArgs) as JSONObject
        assertEquals("sessionToken", setRes.optString("name"))

        // Get cookie
        val getArgs = JSONArray().put(JSONObject().apply {
            put("url", "https://example.com")
            put("name", "sessionToken")
        })
        val getRes = router.handleCall(sender, "cookies.get", getArgs) as JSONObject
        assertEquals("sessionToken", getRes.optString("name"))

        // Attempt private profile cookie without incognito permission should fail or return null
        val getPrivateArgs = JSONArray().put(JSONObject().apply {
            put("url", "https://example.com")
            put("name", "sessionToken")
            put("storeId", "1") // incognito store ID
        })
        try {
            val res = router.handleCall(sender, "cookies.get", getPrivateArgs)
            assertNull(res)
        } catch (e: Exception) {
            assertTrue(e.message?.contains("incognito") == true || e is SecurityException)
        }
    }

    @Test
    fun testPermissionsApiQueries() = runBlocking {
        val sender = ExtensionSender(extensionId = testExtId, url = "chrome-extension://$testExtId/background.js")

        // Contains tabs permission
        val containsArgs = JSONArray().put(JSONObject().apply {
            put("permissions", JSONArray().put("tabs"))
        })
        val containsRes = router.handleCall(sender, "permissions.contains", containsArgs)
        assertEquals(true, containsRes)

        // GetAll permissions
        val getAllRes = router.handleCall(sender, "permissions.getAll", JSONArray()) as JSONObject
        assertTrue(getAllRes.has("permissions"))
        assertTrue(getAllRes.has("origins"))
    }

    @Test
    fun testCapabilityMatrixIntegrity() {
        assertTrue(ExtensionCapabilityMatrix.isSupported("tabs"))
        assertTrue(ExtensionCapabilityMatrix.isSupported("webNavigation"))
        assertTrue(ExtensionCapabilityMatrix.isSupported("cookies"))
        assertTrue(ExtensionCapabilityMatrix.isSupported("debugger"))
        assertFalse(ExtensionCapabilityMatrix.isSupported("devtools"))
        assertEquals(CapabilityStatus.FULL, ExtensionCapabilityMatrix.getStatus("webNavigation"))
        assertEquals(CapabilityStatus.FULL, ExtensionCapabilityMatrix.getStatus("debugger"))
    }
}
