package com.swift.browser.extensionengine

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.swift.browser.extensionengine.ui.ExtensionUiHost
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class ExtensionP3WiringEndToEndTest {

    private lateinit var context: Context
    private lateinit var messageBus: MessageBus
    private lateinit var eventManager: EventManager
    private lateinit var permissionManager: PermissionManager
    private lateinit var registry: ExtensionRegistryImpl
    private lateinit var actionAdapter: ExtensionActionAdapter
    private lateinit var omniboxAdapter: ExtensionOmniboxAdapter
    private lateinit var contextMenusAdapter: ExtensionContextMenusAdapter
    private lateinit var sidePanelAdapter: ExtensionSidePanelAdapter

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        messageBus = MessageBus()
        eventManager = EventManager(messageBus)
        permissionManager = PermissionManager(context, ExtensionPermissionAdapter(context))
        registry = ExtensionRegistryImpl()
        actionAdapter = ExtensionActionAdapter(permissionManager, registry, eventManager)
        omniboxAdapter = ExtensionOmniboxAdapter(permissionManager, registry, eventManager)
        contextMenusAdapter = ExtensionContextMenusAdapter(permissionManager, registry, eventManager)
        sidePanelAdapter = ExtensionSidePanelAdapter(permissionManager, registry)
    }

    private fun registerMockExtension(
        id: String = "test_ext_id",
        name: String = "Test Extension",
        manifestVersion: Int = 3,
        permissions: List<String> = listOf("contextMenus", "sidePanel", "action"),
        omniboxKeyword: String? = null
    ): ParsedExtension {
        val rootJson = JSONObject().apply {
            put("manifest_version", manifestVersion)
            put("name", name)
            put("version", "1.0.0")
            if (omniboxKeyword != null) {
                put("omnibox", JSONObject().put("keyword", omniboxKeyword))
            }
        }
        val ext = ParsedExtension(
            id = id,
            name = name,
            version = "1.0.0",
            manifestVersion = manifestVersion,
            permissions = permissions,
            manifestJson = rootJson.toString()
        )
        registry.register(ext, ExtensionState.INSTALLED_ENABLED)
        return ext
    }

    private fun getActionState(extensionId: String, tabId: String?): ExtensionActionState {
        val key = if (!tabId.isNullOrBlank()) "${extensionId}_$tabId" else null
        if (key != null && ExtensionActionAdapter.tabActionStates.containsKey(key)) {
            return ExtensionActionAdapter.tabActionStates[key]!!
        }
        return ExtensionActionAdapter.globalActionStates[extensionId] ?: ExtensionActionState(extensionId)
    }

    @Test
    fun testActionWiringAndStatePerTab() {
        val ext = registerMockExtension(id = "ext_action_1")
        val sender = ExtensionSender(extensionId = ext.id)

        // Set global badge and title
        actionAdapter.setBadgeText(sender, JSONObject().put("text", "42"))
        actionAdapter.setTitle(sender, JSONObject().put("title", "Global Title"))
        actionAdapter.setBadgeBackgroundColor(sender, JSONObject().put("color", "#FF0000"))

        val globalState = getActionState(ext.id, null)
        assertEquals("42", globalState.badgeText)
        assertEquals("Global Title", globalState.title)
        assertEquals("#FF0000", globalState.badgeBackgroundColor)
        assertTrue(globalState.enabled)

        // Set tab-specific override
        val tabSender = ExtensionSender(extensionId = ext.id, tabId = "tab_99")
        actionAdapter.setBadgeText(tabSender, JSONObject().put("text", "99").put("tabId", "tab_99"))
        actionAdapter.setTitle(tabSender, JSONObject().put("title", "Tab 99 Title").put("tabId", "tab_99"))

        val tabState = getActionState(ext.id, "tab_99")
        assertEquals("99", tabState.badgeText)
        assertEquals("Tab 99 Title", tabState.title)

        // Other tab falls back to global state
        val otherTabState = getActionState(ext.id, "tab_100")
        assertEquals("42", otherTabState.badgeText)
        assertEquals("Global Title", otherTabState.title)
    }

    @Test
    fun testOmniboxWiringAndSuggestions() {
        val ext = registerMockExtension(id = "omni_ext", omniboxKeyword = "wiki")
        val sender = ExtensionSender(extensionId = ext.id)

        // Register keyword
        val regRes = omniboxAdapter.registerKeyword(sender)
        assertEquals("success", regRes.getString("status"))
        assertEquals("wiki", regRes.getString("keyword"))

        // Set default suggestion
        omniboxAdapter.setDefaultSuggestion(sender, JSONObject().put("description", "Search Wiki for %s"))

        // Provide active suggestions
        val suggestions = JSONArray().apply {
            put(JSONObject().put("content", "wiki Kotlin").put("description", "Kotlin Language"))
            put(JSONObject().put("content", "wiki Android").put("description", "Android OS").put("deletable", true))
        }
        val sugRes = omniboxAdapter.suggest(sender, "cb1", suggestions)
        assertEquals("success", sugRes.getString("status"))
        assertEquals(2, sugRes.getInt("count"))

        // Query suggestions for keyword
        val results = ExtensionOmniboxAdapter.getSuggestionsForKeyword("wiki", "Kotlin")
        assertTrue(results.isNotEmpty())
        assertEquals("Search Wiki for Kotlin", results[0].description)
        assertTrue(results.any { it.content == "wiki Kotlin" })

        // Delete suggestion
        omniboxAdapter.deleteSuggestion(sender, JSONObject().put("text", "wiki Android"))
        val updated = ExtensionOmniboxAdapter.getSuggestionsForKeyword("wiki", "Android")
        assertFalse(updated.any { it.content == "wiki Android" })
    }

    @Test
    fun testContextMenuWiringAndRadioGroupBehavior() {
        val ext = registerMockExtension(id = "menu_ext", permissions = listOf("contextMenus"))
        val sender = ExtensionSender(extensionId = ext.id)

        // Create normal, checkbox, and radio items
        val radio1 = JSONObject().apply {
            put("id", "radio_opt_1")
            put("title", "Option 1")
            put("type", "radio")
            put("checked", true)
            put("contexts", JSONArray().put("page"))
        }
        val radio2 = JSONObject().apply {
            put("id", "radio_opt_2")
            put("title", "Option 2")
            put("type", "radio")
            put("checked", false)
            put("contexts", JSONArray().put("page"))
        }
        contextMenusAdapter.create(sender, radio1)
        contextMenusAdapter.create(sender, radio2)

        val item1 = ExtensionContextMenusAdapter.itemsMap["${ext.id}_radio_opt_1"]
        val item2 = ExtensionContextMenusAdapter.itemsMap["${ext.id}_radio_opt_2"]
        assertNotNull(item1)
        assertNotNull(item2)
        assertTrue(item1!!.checked)
        assertFalse(item2!!.checked)

        // Click on radio 2 -> radio 1 must be auto-unchecked
        contextMenusAdapter.triggerClick(
            extensionId = ext.id,
            menuItemId = "radio_opt_2",
            pageUrl = "https://example.com",
            tabId = "tab_1"
        )

        assertFalse(item1.checked)
        assertTrue(item2.checked)

        // Verify getMatchingItems matches context
        val pageItems = ExtensionContextMenusAdapter.getMatchingItems()
        assertTrue(pageItems.any { it.id == "radio_opt_1" })
        assertTrue(pageItems.any { it.id == "radio_opt_2" })
    }

    @Test
    fun testSidePanelWiringAndBehavior() {
        val ext = registerMockExtension(id = "side_ext", permissions = listOf("sidePanel"))
        val sender = ExtensionSender(extensionId = ext.id)

        // Set side panel options
        val setRes = sidePanelAdapter.setOptions(sender, JSONObject().apply {
            put("path", "panel.html")
            put("enabled", true)
        })
        assertEquals("success", setRes.getString("status"))

        val getRes = sidePanelAdapter.getOptions(sender, JSONObject())
        assertEquals("panel.html", getRes.getString("path"))
        assertTrue(getRes.getBoolean("enabled"))

        // Set panel behavior
        sidePanelAdapter.setPanelBehavior(sender, JSONObject().apply {
            put("openPanelOnActionClick", true)
        })
        val behaviorRes = sidePanelAdapter.getPanelBehavior(sender)
        assertTrue(behaviorRes.getBoolean("openPanelOnActionClick"))
    }

    @Test
    fun testExtensionUiHostSecurityGate() {
        val extId = "valid_ext_123"
        val extDir = ExtensionDirectoryResolver.getExtensionDir(context, extId)
        extDir.mkdirs()
        val popupFile = File(extDir, "popup.html")
        popupFile.writeText("<html><body>Popup</body></html>")

        // Valid resource path
        val validHost = ExtensionUiHost.ActionHost(
            extensionId = extId,
            actionUrl = "chrome-extension://$extId/popup.html"
        )
        assertTrue(validHost.validateResourceUrl(context))

        // Path traversal attempt should fail
        val traversalHost = ExtensionUiHost.ActionHost(
            extensionId = extId,
            actionUrl = "chrome-extension://$extId/../../etc/passwd"
        )
        assertFalse(traversalHost.validateResourceUrl(context))

        // Invalid origin should fail
        val invalidOriginHost = ExtensionUiHost.ActionHost(
            extensionId = extId,
            actionUrl = "chrome-extension://malicious_ext/popup.html"
        )
        assertFalse(invalidOriginHost.validateResourceUrl(context))
    }
}
