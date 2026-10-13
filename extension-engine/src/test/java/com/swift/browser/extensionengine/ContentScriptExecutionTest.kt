package com.swift.browser.extensionengine

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ContentScriptExecutionTest {

    private lateinit var context: Context
    private lateinit var permissionManager: PermissionManager
    private lateinit var registry: ExtensionRegistry

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        permissionManager = PermissionManager(context, ExtensionPermissionAdapter(context))
        registry = ExtensionRegistryImpl()
    }

    private fun createExtensionWithContentScript(
        id: String = "cs_ext",
        matches: List<String> = listOf("https://*.example.com/*"),
        excludeMatches: List<String> = emptyList(),
        includeGlobs: List<String> = emptyList(),
        excludeGlobs: List<String> = emptyList(),
        allFrames: Boolean = false,
        matchAboutBlank: Boolean = false,
        matchOriginAsFallback: Boolean = false,
        runAt: String = "document_idle",
        world: String = "ISOLATED"
    ): ParsedExtension {
        val csSpec = ContentScriptSpec(
            matches = matches,
            js = listOf("content.js"),
            css = listOf("styles.css"),
            runAt = runAt,
            allFrames = allFrames,
            matchAboutBlank = matchAboutBlank,
            excludeMatches = excludeMatches,
            includeGlobs = includeGlobs,
            excludeGlobs = excludeGlobs,
            matchOriginAsFallback = matchOriginAsFallback,
            world = world
        )

        return ParsedExtension(
            id = id,
            name = "Content Script Extension",
            version = "1.0.0",
            description = "Test Content Script Extension",
            manifestVersion = 3,
            permissions = listOf("storage"),
            hostPermissions = matches,
            backgroundScripts = emptyList(),
            isServiceWorker = false,
            contentScripts = listOf(csSpec),
            actionPopup = "",
            optionsPage = "",
            manifestJson = """{"manifest_version": 3, "name": "Content Script Extension", "version": "1.0.0"}""",
            allowedInPrivate = false
        )
    }

    @Test
    fun testIsolatedWorldUnavailableConstant() {
        assertEquals("ISOLATED_WORLD_UNAVAILABLE", ISOLATED_WORLD_UNAVAILABLE)
    }

    @Test
    fun testURLMatching() {
        val ext = createExtensionWithContentScript(matches = listOf("https://*.example.com/*"))
        val spec = ext.contentScripts.first()

        assertTrue(spec.matchesUrl("https://foo.example.com/page.html"))
        assertTrue(spec.matchesUrl("https://example.com/index.html"))
        assertFalse(spec.matchesUrl("http://example.com/page.html"))
        assertFalse(spec.matchesUrl("https://otherdomain.com/page.html"))
    }

    @Test
    fun testExcludedURL() {
        val ext = createExtensionWithContentScript(
            matches = listOf("https://*.example.com/*"),
            excludeMatches = listOf("https://*.example.com/admin/*")
        )
        val spec = ext.contentScripts.first()

        assertTrue(spec.matchesUrl("https://foo.example.com/public/page.html"))
        assertFalse("Admin path should be excluded by exclude_matches", spec.matchesUrl("https://foo.example.com/admin/dashboard"))
    }

    @Test
    fun testIncludeAndExcludeGlobs() {
        val ext = createExtensionWithContentScript(
            matches = listOf("https://*.example.com/*"),
            includeGlobs = listOf("*article*"),
            excludeGlobs = listOf("*draft*")
        )
        val spec = ext.contentScripts.first()

        assertTrue(spec.matchesUrl("https://foo.example.com/article123.html"))
        assertFalse("Drafts should be excluded", spec.matchesUrl("https://foo.example.com/article_draft.html"))
        assertFalse("Non-article should be excluded", spec.matchesUrl("https://foo.example.com/about.html"))
    }

    @Test
    fun testAllFramesFlag() {
        val mainFrameExt = createExtensionWithContentScript(allFrames = false)
        val allFramesExt = createExtensionWithContentScript(allFrames = true)

        assertFalse(mainFrameExt.contentScripts.first().allFrames)
        assertTrue(allFramesExt.contentScripts.first().allFrames)
    }

    @Test
    fun testRunAtPhases() {
        val startExt = createExtensionWithContentScript(runAt = "document_start")
        val endExt = createExtensionWithContentScript(runAt = "document_end")
        val idleExt = createExtensionWithContentScript(runAt = "document_idle")

        assertEquals("document_start", startExt.contentScripts.first().runAt)
        assertEquals("document_end", endExt.contentScripts.first().runAt)
        assertEquals("document_idle", idleExt.contentScripts.first().runAt)
    }

    @Test
    fun testMatchAboutBlankAndOriginFallback() {
        val normalSpec = createExtensionWithContentScript(matchAboutBlank = false).contentScripts.first()
        val aboutBlankSpec = createExtensionWithContentScript(matchAboutBlank = true).contentScripts.first()

        assertFalse("normalSpec should not match about:blank without origin", normalSpec.matchesUrl("about:blank"))
        assertTrue("aboutBlankSpec should match about:blank when origin matches", aboutBlankSpec.matchesUrl("about:blank", "https://foo.example.com"))
    }

    @Test
    fun testDisabledExtensionPreInjectionCheck() {
        val ext = createExtensionWithContentScript()
        registry.register(ext, ExtensionState.INSTALLED_ENABLED)
        assertTrue(registry.isExtensionEnabled(ext.id))

        registry.transitionState(ext.id, ExtensionState.INSTALLED_DISABLED)
        assertFalse("Disabled extension must fail pre-injection check", registry.isExtensionEnabled(ext.id))
    }

    @Test
    fun testPrivateModePolicyPreInjectionCheck() {
        val ext = createExtensionWithContentScript()

        assertFalse("Extension allowedInPrivate is false by default", ext.allowedInPrivate)
        assertFalse("PermissionManager allows in private is false by default", permissionManager.isAllowedInPrivate(ext.id))

        permissionManager.setAllowedInPrivate(ext.id, true)
        assertTrue("PermissionManager allows in private after explicit grant", permissionManager.isAllowedInPrivate(ext.id))
    }

    @Test
    fun testHostPermissionDeniedPreInjectionCheck() {
        val ext = createExtensionWithContentScript(matches = listOf("https://secure.example.com/*"))

        val targetUrl = "https://unauthorized.domain.com/index.html"
        val hasPermission = permissionManager.hasHostPermission(ext.id, ext.hostPermissions, ext.permissions, targetUrl)

        assertFalse("Host permission must be denied for unauthorized domain", hasPermission)
    }

    @Test
    fun testWorldSpecification() {
        val mainWorldExt = createExtensionWithContentScript(world = "MAIN")
        val isolatedWorldExt = createExtensionWithContentScript(world = "ISOLATED")

        assertEquals("MAIN", mainWorldExt.contentScripts.first().world)
        assertEquals("ISOLATED", isolatedWorldExt.contentScripts.first().world)
    }

    @Test
    fun testDynamicContentScriptLifecycle() {
        val manager = ContentScriptManager(context, permissionManager, extensionRegistry = registry)
        var changeNotified = false
        manager.onContentScriptsChanged = { changeNotified = true }

        val dynamicSpec = DynamicContentScriptSpec(
            id = "dyn_rule_1",
            matches = listOf("https://dynamic.example.com/*"),
            js = listOf("dyn.js"),
            css = emptyList(),
            runAt = "document_idle",
            world = "ISOLATED"
        )

        manager.registerContentScripts("dyn_ext", listOf(dynamicSpec))
        assertTrue("onContentScriptsChanged should fire on registration", changeNotified)

        val registered = manager.getRegisteredContentScripts("dyn_ext")
        assertEquals(1, registered.size)
        assertEquals("dyn_rule_1", registered.first().id)
        assertEquals(listOf("https://dynamic.example.com/*"), registered.first().matches)

        // Unregister
        changeNotified = false
        manager.unregisterContentScripts("dyn_ext", listOf("dyn_rule_1"))
        assertTrue("onContentScriptsChanged should fire on unregistration", changeNotified)
        assertTrue(manager.getRegisteredContentScripts("dyn_ext").isEmpty())
    }

    @Test
    fun testDiagnosticsReporting() {
        val manager = ContentScriptManager(context, permissionManager, extensionRegistry = registry)
        val diag = ContentScriptDiagnosticReport(
            extensionId = "test_ext",
            scriptId = "script_1",
            phase = "document_idle",
            mode = InjectionMode.FALLBACK_APPROXIMATION,
            timingNote = "Degraded fallback: scheduled via idle callback/post",
            world = "ISOLATED",
            isNativeWorld = false,
            url = "https://example.com"
        )

        manager.recordDiagnostic(diag)
        val last = manager.getLastDiagnostic()
        assertNotNull(last)
        assertEquals("test_ext", last?.extensionId)
        assertEquals(InjectionMode.FALLBACK_APPROXIMATION, last?.mode)
        assertEquals("ISOLATED", last?.world)
        assertFalse(last!!.isNativeWorld)
        assertTrue(last.timingNote.contains("fallback"))
    }

    @Test
    fun testScriptingCapabilitiesMatrix() {
        // Verification of capability methods
        assertTrue(OrionWebViewScriptingCapabilities.canTargetFrame(0, false))
        assertTrue(OrionWebViewScriptingCapabilities.canTargetWorld("MAIN"))
        // ISOLATED world requires frame-and-world platform support
        val canIsolated = OrionWebViewScriptingCapabilities.canTargetWorld("ISOLATED")
        assertEquals(OrionWebViewScriptingCapabilities.isFrameAndWorldInjectionSupported(), canIsolated)
    }

    @Test
    fun testExternalWebPageMessagingPolicy() {
        val ext = ParsedExtension(
            id = "target_ext",
            name = "Target Ext",
            version = "1.0.0",
            description = "Target",
            manifestVersion = 3,
            permissions = emptyList(),
            hostPermissions = emptyList(),
            backgroundScripts = emptyList(),
            isServiceWorker = false,
            contentScripts = emptyList(),
            actionPopup = "",
            optionsPage = "",
            manifestJson = """{"manifest_version": 3, "name": "Target Ext", "version": "1.0.0"}""",
            externallyConnectable = ExternallyConnectableSpec(
                matches = listOf("https://allowed.example.com/*"),
                ids = emptyList(),
                acceptsTlsChannelId = false
            )
        )
        registry.register(ext, ExtensionState.INSTALLED_ENABLED)

        val allowedSender = ExtensionSender(
            extensionId = "",
            contextType = ExtensionContextType.WEB_PAGE,
            url = "https://allowed.example.com/page.html",
            origin = "https://allowed.example.com"
        )
        val deniedSender = ExtensionSender(
            extensionId = "",
            contextType = ExtensionContextType.WEB_PAGE,
            url = "https://disallowed.example.com/page.html",
            origin = "https://disallowed.example.com"
        )

        val allowedResult = ExtensionMessagingValidator.validateCrossExtension(
            sender = allowedSender,
            targetExtensionId = "target_ext",
            registry = registry,
            permissionManager = permissionManager
        )
        assertTrue("Declared externally_connectable domain should be allowed", allowedResult is ExtensionMessagingValidator.ValidationResult.Allowed)

        val deniedResult = ExtensionMessagingValidator.validateCrossExtension(
            sender = deniedSender,
            targetExtensionId = "target_ext",
            registry = registry,
            permissionManager = permissionManager
        )
        assertTrue("Undeclared web page domain must be denied", deniedResult is ExtensionMessagingValidator.ValidationResult.Denied)
    }
}
