package com.swift.browser.extensionengine.chromedebugger.test

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.swift.browser.extensionengine.BrowserCoreServices
import com.swift.browser.extensionengine.EventManager
import com.swift.browser.extensionengine.ExtensionPermissionAdapter
import com.swift.browser.extensionengine.ExtensionRegistry
import com.swift.browser.extensionengine.ExtensionRegistryImpl
import com.swift.browser.extensionengine.ExtensionSender
import com.swift.browser.extensionengine.MessageBus
import com.swift.browser.extensionengine.ParsedExtension
import com.swift.browser.extensionengine.PermissionManager
import com.swift.browser.extensionengine.TabIdMapper
import com.swift.browser.extensionengine.chromedebugger.ChromeDebuggerAdapter
import com.swift.browser.extensionengine.chromedebugger.ChromeDebuggerErrors
import com.swift.browser.extensionengine.chromedebugger.ChromeDebuggerException
import com.swift.browser.extensionengine.chromedebugger.ChromeDebuggee
import com.swift.browser.tabengine.api.TabEngineApi
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ChromeDebuggerApiTest {

    private lateinit var context: Context
    private lateinit var registry: ExtensionRegistry
    private lateinit var messageBus: MessageBus
    private lateinit var eventManager: EventManager
    private lateinit var permissionAdapter: ExtensionPermissionAdapter
    private lateinit var permissionManager: PermissionManager
    private lateinit var tabEngine: TabEngineApi
    private lateinit var debuggerAdapter: ChromeDebuggerAdapter
    private val testTransportFactory = FakeChromeDebuggerTransportFactory()

    private val testExtIdWithPerm = "test_ext_with_debugger_perm"
    private val testExtIdWithoutPerm = "test_ext_without_debugger_perm"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        registry = ExtensionRegistryImpl()
        messageBus = MessageBus()
        eventManager = EventManager(messageBus, context)
        permissionAdapter = ExtensionPermissionAdapter(context)
        permissionManager = PermissionManager(context, permissionAdapter)
        permissionManager.setRegistry(registry)
        permissionAdapter.apply {
            setRegistry(registry)
            setEventManager(eventManager)
        }

        val coreServices = BrowserCoreServices.get(context)
        coreServices.initializeCoreEngines()
        tabEngine = coreServices.tabs

        testTransportFactory.transports.clear()
        debuggerAdapter = ChromeDebuggerAdapter(
            tabEngine = tabEngine,
            registry = registry,
            permissionManager = permissionManager,
            permissionAdapter = permissionAdapter,
            eventManager = eventManager,
            transportFactory = testTransportFactory
        )

        // Register extension with debugger permission
        val extWithPerm = ParsedExtension(
            id = testExtIdWithPerm,
            name = "Debugger Tool Extension",
            version = "1.0",
            manifestVersion = 3,
            description = "Extension with chrome.debugger permission",
            permissions = listOf("debugger", "tabs"),
            manifestJson = JSONObject().apply {
                put("name", "Debugger Tool Extension")
                put("version", "1.0")
                put("manifest_version", 3)
                put("permissions", JSONArray().put("debugger").put("tabs"))
            }.toString(),
            isEnabled = true
        )
        registry.registerExtension(extWithPerm)

        // Register extension without debugger permission
        val extWithoutPerm = ParsedExtension(
            id = testExtIdWithoutPerm,
            name = "Regular Extension",
            version = "1.0",
            manifestVersion = 3,
            description = "Extension without debugger permission",
            permissions = listOf("tabs"),
            manifestJson = JSONObject().apply {
                put("name", "Regular Extension")
                put("version", "1.0")
                put("manifest_version", 3)
                put("permissions", JSONArray().put("tabs"))
            }.toString(),
            isEnabled = true
        )
        registry.registerExtension(extWithoutPerm)
    }

    @Test
    fun testPermissionChecker_RejectsExtensionWithoutDebuggerPermission() = runBlocking {
        var threwSecurity = false
        try {
            debuggerAdapter.attach(
                extensionId = testExtIdWithoutPerm,
                target = ChromeDebuggee(tabId = 1),
                requiredVersion = "1.3"
            )
        } catch (e: SecurityException) {
            threwSecurity = true
        } catch (e: Exception) {
            assertTrue("Expected SecurityException or permission error, got: $e", e.message?.contains("permission") == true)
            threwSecurity = true
        }
        assertTrue("Extension without debugger permission must be rejected", threwSecurity)
    }

    @Test
    fun testGetTargets_ReturnsOpenTabsAndExtensions() = runBlocking {
        // Create a tab in tab engine
        val tab = tabEngine.createTab("https://example.com", "Example Domain")
        val intTabId = TabIdMapper.getIntId(tab.id)

        val targets = debuggerAdapter.getTargets(testExtIdWithPerm)
        assertNotNull(targets)
        assertTrue(targets.isNotEmpty())

        val pageTarget = targets.find { it.tabId == intTabId }
        assertNotNull("Should contain the created tab target", pageTarget)
        assertEquals("page", pageTarget?.type)
        assertEquals("https://example.com", pageTarget?.url)
        assertFalse(pageTarget!!.attached)
    }

    @Test
    fun testAttachAndDetach_TabTarget() = runBlocking {
        val tab = tabEngine.createTab("https://example.com", "Example Domain")
        val intTabId = TabIdMapper.getIntId(tab.id)
        val debuggee = ChromeDebuggee(tabId = intTabId)

        // Attach
        debuggerAdapter.attach(testExtIdWithPerm, debuggee, "1.3")
        assertTrue(debuggerAdapter.sessionManager.isAttached("target_tab_${tab.id}"))

        // Single-debugger-per-target rule: second attach must fail
        var secondAttachFailed = false
        try {
            debuggerAdapter.attach(testExtIdWithPerm, debuggee, "1.3")
        } catch (e: ChromeDebuggerException) {
            secondAttachFailed = true
            assertEquals(ChromeDebuggerErrors.ERROR_ALREADY_ATTACHED, e.errorCode)
        }
        assertTrue("Second attach to same target must fail with ERROR_ALREADY_ATTACHED", secondAttachFailed)

        // Detach
        debuggerAdapter.detach(testExtIdWithPerm, debuggee)
        assertFalse(debuggerAdapter.sessionManager.isAttached("target_tab_${tab.id}"))
    }

    @Test
    fun testSendCommand_WhenNotAttached_ThrowsError() = runBlocking {
        val tab = tabEngine.createTab("https://example.com", "Example Domain")
        val intTabId = TabIdMapper.getIntId(tab.id)
        val debuggee = ChromeDebuggee(tabId = intTabId)

        var failed = false
        try {
            debuggerAdapter.sendCommand(testExtIdWithPerm, debuggee, "Runtime.enable", JSONObject())
        } catch (e: ChromeDebuggerException) {
            failed = true
            assertEquals(ChromeDebuggerErrors.ERROR_NOT_ATTACHED, e.errorCode)
        }
        assertTrue("Sending command when not attached must fail", failed)
    }

    @Test
    fun testBridgeCallRouting_ViaMessageRouter() = runBlocking {
        val tab = tabEngine.createTab("https://example.com", "Example Domain")
        val intTabId = TabIdMapper.getIntId(tab.id)

        val sender = ExtensionSender(
            extensionId = testExtIdWithPerm,
            origin = "chrome-extension://$testExtIdWithPerm",
            tabId = null
        )

        // 1. debugger.getTargets
        val targetsResult = debuggerAdapter.handleBridgeCall(
            sender = sender,
            api = "debugger.getTargets",
            args = JSONArray()
        )
        assertTrue(targetsResult is JSONArray)
        val targetsArr = targetsResult as JSONArray
        assertTrue(targetsArr.length() > 0)

        // 2. debugger.attach
        val attachArgs = JSONArray().apply {
            put(JSONObject().apply { put("tabId", intTabId) })
            put("1.3")
        }
        val attachResult = debuggerAdapter.handleBridgeCall(
            sender = sender,
            api = "debugger.attach",
            args = attachArgs
        )
        assertNotNull(attachResult)

        // 3. debugger.detach
        val detachArgs = JSONArray().apply {
            put(JSONObject().apply { put("tabId", intTabId) })
        }
        val detachResult = debuggerAdapter.handleBridgeCall(
            sender = sender,
            api = "debugger.detach",
            args = detachArgs
        )
        assertNotNull(detachResult)
    }

    @Test
    fun testTabClosure_AutomaticallyDetachesDebugger() = runBlocking {
        val tab = tabEngine.createTab("https://example.com", "Example Domain")
        val intTabId = TabIdMapper.getIntId(tab.id)
        val debuggee = ChromeDebuggee(tabId = intTabId)

        debuggerAdapter.attach(testExtIdWithPerm, debuggee, "1.3")
        assertTrue(debuggerAdapter.sessionManager.isAttached("target_tab_${tab.id}"))

        // Simulate Tab Engine closing the tab
        debuggerAdapter.onTabClosed(tab.id)
        assertFalse(debuggerAdapter.sessionManager.isAttached("target_tab_${tab.id}"))
    }
}
