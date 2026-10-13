package com.swift.browser.extensionengine

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
class ExtensionPipelineEndToEndTest {

    private lateinit var parser: ManifestParser

    @Before
    fun setUp() {
        parser = ManifestParser()
    }

    @Test
    fun testValidCanonicalMatchPatternsInManifest() {
        val manifestJson = """
            {
                "manifest_version": 3,
                "name": "Match Pattern Suite",
                "version": "1.0.0",
                "host_permissions": [
                    "*://*.youtube.com/*",
                    "<all_urls>",
                    "https://*/*",
                    "*://*/*",
                    "http://127.0.0.1/*",
                    "file:///path/*"
                ],
                "content_scripts": [
                    {
                        "matches": ["*://*.google.com/*", "https://*.github.com/*"],
                        "js": ["content.js"]
                    }
                ]
            }
        """.trimIndent()

        val parsed = parser.parse(manifestJson)
        assertEquals("Match Pattern Suite", parsed.name)
        assertEquals(6, parsed.hostPermissions.size)
        assertTrue(parsed.hostPermissions.contains("*://*.youtube.com/*"))
        assertTrue(parsed.hostPermissions.contains("<all_urls>"))
        assertEquals(1, parsed.contentScripts.size)
        assertEquals(2, parsed.contentScripts[0].matches.size)
    }

    @Test
    fun testInvalidMatchPatternsRejectedInManifest() {
        val invalidPatterns = listOf(
            "javascript:void(0)",
            "data:text/html,test",
            "http://",
            "https://",
            "https://*foo.com/*",
            "*",
            "http://*.*.com/*"
        )

        for (pattern in invalidPatterns) {
            val manifestJson = """
                {
                    "manifest_version": 3,
                    "name": "Invalid Pattern Extension",
                    "version": "1.0.0",
                    "host_permissions": ["$pattern"]
                }
            """.trimIndent()

            var rejected = false
            try {
                parser.parse(manifestJson)
            } catch (e: ExtensionError.ManifestError) {
                rejected = true
            } catch (e: Exception) {
                rejected = true
            }
            assertTrue("Expected pattern '$pattern' to be rejected", rejected)
        }
    }

    @Test
    fun testZipArchiveValidationAndExtraction() {
        val manifest = """
            {
                "manifest_version": 3,
                "name": "Mock Ext",
                "version": "1.0.0"
            }
        """.trimIndent()

        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            zos.putNextEntry(ZipEntry("manifest.json"))
            zos.write(manifest.toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("background.js"))
            zos.write("console.log('bg');".toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }

        val zipBytes = baos.toByteArray()
        val pkg = ExtensionPackage.parseAndValidate(zipBytes, "test.zip")
        assertEquals(HeaderValidationResult.PLAIN_ZIP, pkg.headerValidation)
        assertEquals(PackageSourceType.ZIP, pkg.sourceType)
        assertTrue(pkg.zipPayloadBytes.isNotEmpty())
    }
}
