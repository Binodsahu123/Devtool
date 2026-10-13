package com.swift.browser.extensionengine.chromedebugger.test

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.swift.browser.extensionengine.BrowserCoreServices
import com.swift.browser.extensionengine.EventManager
import com.swift.browser.extensionengine.ExtensionPermissionAdapter
import com.swift.browser.extensionengine.ExtensionRegistry
import com.swift.browser.extensionengine.ExtensionRegistryImpl
import com.swift.browser.extensionengine.MessageBus
import com.swift.browser.extensionengine.MessageListener
import com.swift.browser.extensionengine.ParsedExtension
import com.swift.browser.extensionengine.PermissionManager
import com.swift.browser.extensionengine.TabIdMapper
import com.swift.browser.extensionengine.chromedebugger.ChromeDebuggerAdapter
import com.swift.browser.extensionengine.chromedebugger.ChromeDebuggerErrors
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
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
class ChromeDebuggerIntegrationTest {

    private lateinit var context: Context
    private lateinit var registry: ExtensionRegistry
    private lateinit var messageBus: MessageBus
    private lateinit var eventManager: EventManager
    private lateinit var permissionAdapter: ExtensionPermissionAdapter
    private lateinit var permissionManager: PermissionManager
    private lateinit var tabEngine: TabEngineApi
    private lateinit var debuggerAdapter: ChromeDebuggerAdapter
    private val testTransportFactory = FakeChromeDebuggerTransportFactory()

    private val extensionId = "debugger_integration_test_ext_id"

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
        val ext = ParsedExtension(
            id = extensionId,
            name = "Debugger Integration Extension",
            version = "1.0",
            manifestVersion = 3,
            description = "Extension for chrome.debugger session integration test",
            permissions = listOf("debugger", "tabs"),
            manifestJson = JSONObject().apply {
                put("name", "Debugger Integration Extension")
                put("version", "1.0")
                put("manifest_version", 3)
                put("permissions", JSONArray().put("debugger").put("tabs"))
            }.toString(),
            isEnabled = true
        )
        registry.registerExtension(ext)
    }

    @Test
    fun testDebuggerTargetSessionAndCommandFlow() = runBlocking {
        val tab = tabEngine.createTab("https://example.org", "Example Page")
        val intTabId = TabIdMapper.getIntId(tab.id)
        val debuggee = ChromeDebuggee(tabId = intTabId)

        val targets = debuggerAdapter.getTargets(extensionId)
        assertTrue("Targets list should contain opened tab", targets.any { it.tabId == intTabId })

        debuggerAdapter.attach(extensionId, debuggee, "1.3")
        val syntheticTargetId = "target_tab_${tab.id}"
        assertTrue("Target session must be active", debuggerAdapter.sessionManager.isAttached(syntheticTargetId))
        val fakeTransport = testTransportFactory.transports[syntheticTargetId]
        assertNotNull("Test-only fake must be installed by the test factory", fakeTransport)

        assertNotNull(debuggerAdapter.sendCommand(extensionId, debuggee, "Page.enable", JSONObject()))
        val navigate = debuggerAdapter.sendCommand(
            extensionId, debuggee, "Page.navigate", JSONObject().put("url", "https://example.org/dashboard")
        )
        assertTrue(navigate.has("frameId"))
        val document = debuggerAdapter.sendCommand(
            extensionId, debuggee, "DOM.getDocument", JSONObject().put("depth", 2)
        )
        assertTrue(document.has("root"))
        val css = debuggerAdapter.sendCommand(
            extensionId, debuggee, "CSS.getComputedStyleForNode", JSONObject().put("nodeId", 1)
        )
        assertTrue(css.has("computedStyle"))
        assertNotNull(debuggerAdapter.sendCommand(extensionId, debuggee, "Network.enable", JSONObject()))
        assertNotNull(debuggerAdapter.sendCommand(extensionId, debuggee, "Console.enable", JSONObject()))
        assertTrue(debuggerAdapter.sendCommand(extensionId, debuggee, "Target.getTargets", JSONObject()).has("targetInfos"))

        debuggerAdapter.detach(extensionId, debuggee)
        assertFalse("Target session must be detached", debuggerAdapter.sessionManager.isAttached(syntheticTargetId))
        assertFalse("Detaching closes test transport", fakeTransport!!.isConnected())
    }

    @Test
    fun testEventDispatchingToExtension() = runBlocking {
        val tab = tabEngine.createTab("https://example.org", "Example Page")
        val intTabId = TabIdMapper.getIntId(tab.id)
        val debuggee = ChromeDebuggee(tabId = intTabId)

        var eventDispatched = false
        messageBus.registerListener(object : MessageListener {
            override fun onMessageReceived(
                extId: String,
                senderTabId: String?,
                message: JSONObject,
                callbackId: String?,
                targetTabId: String?
            ) {
                if (message.optString("type") == "EVENT" && message.optString("eventName") == "debugger.onEvent") {
                    eventDispatched = true
                    val payload = message.optJSONObject("data")
                    assertNotNull(payload)
                    assertEquals("Console.messageAdded", payload?.optString("method"))
                }
            }

            override fun onResponseReceived(extensionId: String, callbackId: String, response: Any) {}
        })

        debuggerAdapter.attach(extensionId, debuggee, "1.3")
        debuggerAdapter.eventRouter.dispatchDebuggerEvent(
            extensionId = extensionId,
            debuggee = debuggee,
            event = com.swift.browser.extensionengine.chromedebugger.CdpEvent(
                method = "Console.messageAdded",
                params = JSONObject().apply {
                    put("message", JSONObject().apply { put("text", "Hello Debugger") })
                }
            )
        )

        assertTrue("Event must be routed to Extension message bus", eventDispatched)

        // Test onDetach dispatching
        var detachDispatched = false
        messageBus.registerListener(object : MessageListener {
            override fun onMessageReceived(
                extId: String,
                senderTabId: String?,
                message: JSONObject,
                callbackId: String?,
                targetTabId: String?
            ) {
                if (message.optString("type") == "EVENT" && message.optString("eventName") == "debugger.onDetach") {
                    detachDispatched = true
                    val payload = message.optJSONObject("data")
                    assertNotNull(payload)
                    assertEquals(ChromeDebuggerErrors.ERROR_TARGET_CLOSED, payload?.optString("reason"))
                }
            }

            override fun onResponseReceived(extensionId: String, callbackId: String, response: Any) {}
        })

        debuggerAdapter.eventRouter.dispatchDebuggerDetach(
            extensionId = extensionId,
            debuggee = debuggee,
            reason = ChromeDebuggerErrors.ERROR_TARGET_CLOSED
        )

        assertTrue("Detach notification must be routed to Extension message bus", detachDispatched)
    }


}
