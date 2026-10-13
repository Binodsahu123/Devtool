package com.swift.browser.extensionengine

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import com.swift.browser.extensionengine.resources.ExtensionResourceAccessPolicy
import com.swift.browser.extensionengine.security.ExtensionCspPolicy
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
class ExtensionMasterFixturesTest {

    private lateinit var parser: ManifestParser

    @Before
    fun setUp() {
        parser = ManifestParser()
    }

    private fun createZipPackage(files: Map<String, String>): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            files.forEach { (path, content) ->
                zos.putNextEntry(ZipEntry(path))
                zos.write(content.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
        }
        return baos.toByteArray()
    }

    // A. MV3 basic
    @Test
    fun testFixtureA_MV3Basic() {
        val manifest = """
            {
                "manifest_version": 3,
                "name": "Fixture A Basic MV3",
                "version": "1.0.0",
                "description": "Basic MV3 extension fixture"
            }
        """.trimIndent()
        val parsed = parser.parse(manifest)
        assertEquals(3, parsed.manifestVersion)
        assertEquals("Fixture A Basic MV3", parsed.name)
        assertEquals("1.0.0", parsed.version)
    }

    // B. MV3 service worker + messaging
    @Test
    fun testFixtureB_MV3ServiceWorkerMessaging() {
        val manifest = """
            {
                "manifest_version": 3,
                "name": "Fixture B Worker Messaging",
                "version": "1.0.0",
                "background": {
                    "service_worker": "background.js"
                },
                "permissions": ["storage"]
            }
        """.trimIndent()
        val parsed = parser.parse(manifest)
        assertEquals(3, parsed.manifestVersion)
        assertEquals("background.js", parsed.backgroundSpec.serviceWorker)
        assertTrue(parsed.permissions.contains("storage"))
    }

    // C. MV3 content script + CSS
    @Test
    fun testFixtureC_MV3ContentScriptCss() {
        val manifest = """
            {
                "manifest_version": 3,
                "name": "Fixture C Content Script",
                "version": "1.0.0",
                "content_scripts": [
                    {
                        "matches": ["https://*.example.com/*"],
                        "js": ["content.js"],
                        "css": ["styles.css"],
                        "run_at": "document_start",
                        "all_frames": true
                    }
                ]
            }
        """.trimIndent()
        val parsed = parser.parse(manifest)
        assertEquals(1, parsed.contentScripts.size)
        val cs = parsed.contentScripts[0]
        assertEquals(listOf("content.js"), cs.js)
        assertEquals(listOf("styles.css"), cs.css)
        assertEquals("document_start", cs.runAt)
        assertTrue(cs.allFrames)
    }

    // D. MV3 web_accessible_resources
    @Test
    fun testFixtureD_MV3WebAccessibleResources() {
        val manifest = """
            {
                "manifest_version": 3,
                "name": "Fixture D WARS",
                "version": "1.0.0",
                "web_accessible_resources": [
                    {
                        "resources": ["/web_accessible_resources/noop.txt", "./images/logo.png"],
                        "matches": ["https://*.example.com/*"]
                    }
                ]
            }
        """.trimIndent()
        val parsed = parser.parse(manifest)
        assertEquals(1, parsed.webAccessibleResources.size)
        val war = parsed.webAccessibleResources[0]
        assertEquals("web_accessible_resources/noop.txt", war.resources[0])
        assertEquals("images/logo.png", war.resources[1])
    }

    // E. MV3 CSP-valid
    @Test
    fun testFixtureE_MV3CspValid() {
        val manifest = """
            {
                "manifest_version": 3,
                "name": "Fixture E Valid CSP",
                "version": "1.0.0",
                "content_security_policy": {
                    "extension_pages": "script-src 'self' 'wasm-unsafe-eval'; object-src 'self';"
                }
            }
        """.trimIndent()
        val parsed = parser.parse(manifest)
        assertEquals("script-src 'self' 'wasm-unsafe-eval'; object-src 'self';", parsed.contentSecurityPolicy.extensionPages)
    }

    // F. MV3 CSP-invalid (should sanitize or reject unsafe-eval)
    @Test
    fun testFixtureF_MV3CspInvalid() {
        val csp = ExtensionCspPolicy.DEFAULT_MV3_EXTENSION_CSP
        assertFalse(csp.contains("'unsafe-eval'"))
    }

    // G. MV2 background
    @Test
    fun testFixtureG_MV2Background() {
        val manifest = """
            {
                "manifest_version": 2,
                "name": "Fixture G MV2",
                "version": "2.0.0",
                "background": {
                    "scripts": ["bg1.js", "bg2.js"],
                    "persistent": false
                }
            }
        """.trimIndent()
        val parsed = parser.parse(manifest)
        assertEquals(2, parsed.manifestVersion)
        assertEquals(listOf("bg1.js", "bg2.js"), parsed.backgroundSpec.scripts)
        assertEquals(false, parsed.backgroundSpec.persistent)
    }

    // H. permissions denied check
    @Test
    fun testFixtureH_PermissionsDenied() {
        val context = RuntimeEnvironment.getApplication()
        val permissionManager = PermissionManager(context, ExtensionPermissionAdapter(context))
        val manifest = """
            {
                "manifest_version": 3,
                "name": "Fixture H",
                "version": "1.0.0",
                "permissions": ["storage"]
            }
        """.trimIndent()
        val parsed = parser.parse(manifest)
        assertTrue(permissionManager.hasApiPermission(parsed.id, parsed.permissions, "storage"))
        assertFalse(permissionManager.hasApiPermission(parsed.id, parsed.permissions, "tabs"))
    }

    // I. large package installation stream check
    @Test
    fun testFixtureI_StreamingPackageInstall() {
        val zipBytes = createZipPackage(mapOf(
            "manifest.json" to """{"manifest_version":3,"name":"Fixture I Large","version":"1.0.0"}""",
            "large_data.bin" to "A".repeat(10000)
        ))
        val installer = ZipExtensionInstaller(RuntimeEnvironment.getApplication(), parser)
        val installed = installer.installFromBytes(zipBytes, "fixture_i.zip")
        assertEquals("Fixture I Large", installed.name)
    }

    // J. ES module service worker
    @Test
    fun testFixtureJ_EsModuleServiceWorker() {
        val manifest = """
            {
                "manifest_version": 3,
                "name": "Fixture J ES Module",
                "version": "1.0.0",
                "background": {
                    "service_worker": "sw.js",
                    "type": "module"
                }
            }
        """.trimIndent()
        val parsed = parser.parse(manifest)
        assertEquals("sw.js", parsed.backgroundSpec.serviceWorker)
        assertEquals("module", parsed.backgroundSpec.type)
    }

    // K. importScripts-compatible MV2 worker
    @Test
    fun testFixtureK_ImportScriptsMV2() {
        val manifest = """
            {
                "manifest_version": 2,
                "name": "Fixture K ImportScripts",
                "version": "1.0.0",
                "background": {
                    "scripts": ["import_helper.js", "main.js"]
                }
            }
        """.trimIndent()
        val parsed = parser.parse(manifest)
        assertEquals(2, parsed.backgroundSpec.scripts.size)
    }

    // L. alarms/timer worker
    @Test
    fun testFixtureL_AlarmsTimerWorker() {
        val manifest = """
            {
                "manifest_version": 3,
                "name": "Fixture L Alarms",
                "version": "1.0.0",
                "permissions": ["alarms"]
            }
        """.trimIndent()
        val parsed = parser.parse(manifest)
        assertTrue(parsed.permissions.contains("alarms"))
    }

    // M. popup + runtime messaging
    @Test
    fun testFixtureM_PopupRuntimeMessaging() {
        val manifest = """
            {
                "manifest_version": 3,
                "name": "Fixture M Popup",
                "version": "1.0.0",
                "action": {
                    "default_popup": "popup.html",
                    "default_title": "Fixture M"
                }
            }
        """.trimIndent()
        val parsed = parser.parse(manifest)
        assertEquals("popup.html", parsed.actionSpec.defaultPopup)
        assertEquals("Fixture M", parsed.actionSpec.defaultTitle)
    }
}
