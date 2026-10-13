package com.swift.browser.extensionengine

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ManifestParserTest {

    private lateinit var parser: ManifestParser

    @Before
    fun setUp() {
        parser = ManifestParser()
    }

    @Test
    fun testValidMv3ManifestParsing() {
        val json = """
            {
                "manifest_version": 3,
                "name": "Test MV3 Extension",
                "version": "1.0.0",
                "description": "A test extension for MV3",
                "permissions": ["storage", "activeTab"],
                "host_permissions": ["https://*.example.com/*"],
                "action": {
                    "default_popup": "popup.html",
                    "default_title": "Test Popup"
                },
                "background": {
                    "service_worker": "background.js"
                }
            }
        """.trimIndent()

        val parsed = parser.parse(json)

        assertEquals(3, parsed.manifestVersion)
        assertEquals("Test MV3 Extension", parsed.name)
        assertEquals("1.0.0", parsed.version)
        assertTrue(parsed.isServiceWorker)
        assertEquals("background.js", parsed.backgroundPath)
        assertEquals("popup.html", parsed.popupPath)
        assertTrue(parsed.permissions.contains("storage"))
        assertTrue(parsed.hostPermissions.contains("https://*.example.com/*"))
    }

    @Test
    fun testValidMv2ManifestParsing() {
        val json = """
            {
                "manifest_version": 2,
                "name": "Test MV2 Extension",
                "version": "2.1.4",
                "permissions": ["tabs", "<all_urls>"],
                "browser_action": {
                    "default_popup": "index.html"
                },
                "background": {
                    "scripts": ["bg.js"]
                }
            }
        """.trimIndent()

        val parsed = parser.parse(json)

        assertEquals(2, parsed.manifestVersion)
        assertEquals("Test MV2 Extension", parsed.name)
        assertFalse(parsed.isServiceWorker)
        assertEquals("bg.js", parsed.backgroundPath)
        assertEquals("index.html", parsed.popupPath)
    }

    @Test(expected = ExtensionError.ManifestError.MissingRequiredField::class)
    fun testMissingNameRejection() {
        val json = """
            {
                "manifest_version": 3,
                "version": "1.0.0"
            }
        """.trimIndent()

        parser.parse(json)
    }

    @Test(expected = ExtensionError.ManifestError.UnsupportedVersion::class)
    fun testUnsupportedManifestVersionRejection() {
        val json = """
            {
                "manifest_version": 1,
                "name": "Legacy App",
                "version": "1.0"
            }
        """.trimIndent()

        parser.parse(json)
    }

    @Test(expected = ExtensionError.ManifestError.InvalidVersionFormat::class)
    fun testInvalidSemverVersionRejection() {
        val json = """
            {
                "manifest_version": 3,
                "name": "Bad Version App",
                "version": "invalid_version_str"
            }
        """.trimIndent()

        parser.parse(json)
    }


    @Test
    fun rejectsInvalidChromeVersionComponent() {
        val json = """
            {"manifest_version":3,"name":"Bad","version":"1.000000","action":{}}
        """.trimIndent()
        try {
            parser.parse(json)
            fail("Expected invalid version")
        } catch (e: ExtensionError.ManifestError.InvalidVersionFormat) {
            // expected
        }
    }

    @Test
    fun acceptsFileMatchPattern() {
        val json = """
            {
              "manifest_version": 3,
              "name": "File Pattern",
              "version": "1.0",
              "content_scripts": [{
                "matches": ["file:///home/*"],
                "js": ["inject.js"]
              }]
            }
        """.trimIndent()
        val parsed = parser.parse(json)
        assertEquals("file:///home/*", parsed.contentScripts.single().matches.single())
    }

    @Test
    fun rejectsUnsafeContentScriptPath() {
        val json = """
            {
              "manifest_version": 3,
              "name": "Unsafe",
              "version": "1.0",
              "content_scripts": [{
                "matches": ["https://example.com/*"],
                "js": ["../inject.js"]
              }]
            }
        """.trimIndent()
        try {
            parser.parse(json)
            fail("Expected unsafe path rejection")
        } catch (e: ExtensionError.ManifestError.InvalidContentScriptSpec) {
            // expected
        }
    }

    @Test
    fun rejectsConflictingActionKeys() {
        val json = """
            {
              "manifest_version": 3,
              "name": "Conflict",
              "version": "1.0",
              "action": {},
              "browser_action": {}
            }
        """.trimIndent()
        try {
            parser.parse(json)
            fail("Expected conflicting action keys rejection")
        } catch (e: ExtensionError.ManifestError.InvalidFieldValue) {
            assertEquals("action", e.fieldName)
        }
    }

    @Test
    fun parsesDnrRuleResources() {
        val json = """
            {
              "manifest_version": 3,
              "name": "DNR",
              "version": "1.0",
              "declarative_net_request": {
                "rule_resources": [
                  {"id":"rules_1","enabled":true,"path":"rules.json"}
                ]
              }
            }
        """.trimIndent()
        val parsed = parser.parse(json)
        assertEquals("rules_1", parsed.declarativeNetRequest.rulesets.single().id)
        assertEquals("rules.json", parsed.declarativeNetRequest.rulesets.single().path)
    }

    @Test
    fun acceptsWildcardSchemeHostPermission() {
        val json = """
            {
              "manifest_version": 3,
              "name": "Wildcard Host Test",
              "version": "1.0",
              "host_permissions": [
                "*://*.youtube.com/*"
              ]
            }
        """.trimIndent()

        val parsed = parser.parse(json)

        assertTrue(
            parsed.hostPermissions.contains("*://*.youtube.com/*")
        )
    }

    @Test
    fun acceptsWildcardSchemeContentScript() {
        val json = """
            {
              "manifest_version": 3,
              "name": "YouTube Content Script",
              "version": "1.0",
              "content_scripts": [
                {
                  "matches": [
                    "*://*.youtube.com/*"
                  ],
                  "js": [
                    "content.js"
                  ]
                }
              ]
            }
        """.trimIndent()

        val parsed = parser.parse(json)

        assertEquals(
            "*://*.youtube.com/*",
            parsed.contentScripts.single().matches.single()
        )
    }

    @Test
    fun acceptsUniversalWildcardScheme() {
        val json = """
            {
              "manifest_version": 3,
              "name": "Universal Test",
              "version": "1.0",
              "host_permissions": [
                "*://*/*"
              ]
            }
        """.trimIndent()

        val parsed = parser.parse(json)

        assertTrue(
            parsed.hostPermissions.contains("*://*/*")
        )
    }

    @Test
    fun rejectsInvalidHostPermissionWithExactName() {
        val json = """
            {
              "manifest_version": 3,
              "name": "Invalid Host Test",
              "version": "1.0",
              "host_permissions": [
                "invalid-scheme://youtube.com/*"
              ]
            }
        """.trimIndent()

        try {
            parser.parse(json)
            fail("Expected invalid host permission rejection")
        } catch (e: ExtensionError.ManifestError.InvalidFieldValue) {
            assertTrue("Expected fieldName to start with host_permissions but was ${e.fieldName}", e.fieldName.startsWith("host_permissions"))
            assertTrue("Expected error to contain offending pattern but got: ${e.message}", e.detail.contains("invalid-scheme://youtube.com/*"))
        }
    }

    @Test
    fun acceptsValidChromeMatchPatterns() {
        assertTrue(ManifestParser.isValidMatchPattern("<all_urls>"))
        assertTrue(ManifestParser.isValidMatchPattern("*://*.youtube.com/*"))
        assertTrue(ManifestParser.isValidMatchPattern("*://*/*"))
        assertTrue(ManifestParser.isValidMatchPattern("https://*.google.com/search*"))
        assertTrue(ManifestParser.isValidMatchPattern("http://127.0.0.1:8080/*"))
        assertTrue(ManifestParser.isValidMatchPattern("file:///sdcard/test.html"))

        assertFalse(ManifestParser.isValidMatchPattern(""))
        assertFalse(ManifestParser.isValidMatchPattern("not a pattern"))
        assertFalse(ManifestParser.isValidMatchPattern("ftp://"))
        assertFalse(ManifestParser.isValidMatchPattern("https://*.example.*/*"))
        assertFalse(ManifestParser.isValidMatchPattern("*://"))
    }

    @Test
    fun rejectsInvalidContentScriptMatchPattern() {
        val json = """
            {
              "manifest_version": 3,
              "name": "Invalid Match",
              "version": "1.0",
              "content_scripts": [
                {
                  "matches": [
                    "*://bad*domain.com/*"
                  ],
                  "js": [
                    "content.js"
                  ]
                }
              ]
            }
        """.trimIndent()

        try {
            parser.parse(json)
            fail("Expected invalid match pattern rejection")
        } catch (e: ExtensionError.ManifestError.InvalidContentScriptSpec) {
            assertTrue("Expected error to contain offending pattern but got: ${e.message}", e.reason.contains("*://bad*domain.com/*"))
        }
    }
}
