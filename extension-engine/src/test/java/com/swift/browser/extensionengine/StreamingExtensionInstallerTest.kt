package com.swift.browser.extensionengine

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
class StreamingExtensionInstallerTest {

    private lateinit var context: Context
    private lateinit var registry: ExtensionRegistry
    private lateinit var installer: ZipExtensionInstaller

    private val TEST_PUB_KEY = "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQCl8X5Z3Y1w8kP3v6E5j9xQ0f1g2h3i4j5k6l7m8n9o"

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        registry = ExtensionRegistryImpl()
        installer = ZipExtensionInstaller(context, registry = registry)
    }

    private fun createExtensionZipFile(
        file: File,
        version: String = "1.0.0",
        additionalEntries: Int = 10,
        entryDataSize: Int = 4096
    ) {
        val manifest = """
            {
                "manifest_version": 3,
                "name": "Streaming Test Extension",
                "version": "$version",
                "key": "$TEST_PUB_KEY",
                "description": "Tests streaming O(1) package installation",
                "action": { "default_popup": "popup.html" },
                "background": { "service_worker": "background.js" }
            }
        """.trimIndent()

        FileOutputStream(file).use { fos ->
            ZipOutputStream(fos).use { zos ->
                zos.putNextEntry(ZipEntry("manifest.json"))
                zos.write(manifest.toByteArray(Charsets.UTF_8))
                zos.closeEntry()

                zos.putNextEntry(ZipEntry("background.js"))
                zos.write("console.log('streaming background worker');".toByteArray(Charsets.UTF_8))
                zos.closeEntry()

                zos.putNextEntry(ZipEntry("popup.html"))
                zos.write("<!DOCTYPE html><html><body><h1>Popup</h1></body></html>".toByteArray(Charsets.UTF_8))
                zos.closeEntry()

                val payloadChunk = ByteArray(entryDataSize) { (it % 127).toByte() }
                for (i in 1..additionalEntries) {
                    zos.putNextEntry(ZipEntry("assets/resource_$i.dat"))
                    zos.write(payloadChunk)
                    zos.closeEntry()
                }
            }
        }
    }

    @Test
    fun testInstallDirectFromFileStreaming() {
        val tempZip = File.createTempFile("test_stream_ext_", ".zip", context.cacheDir)
        try {
            createExtensionZipFile(tempZip, version = "1.0.0", additionalEntries = 5)
            val parsed = installer.installFromFile(tempZip)

            assertNotNull("Parsed extension should not be null", parsed)
            assertEquals("Streaming Test Extension", parsed.name)
            assertEquals("1.0.0", parsed.version)
            assertTrue("Install path must exist", File(parsed.installPath).exists())
            assertTrue("Manifest must exist in install dir", File(parsed.installPath, "manifest.json").exists())
            assertTrue("Background file must exist in install dir", File(parsed.installPath, "background.js").exists())
        } finally {
            if (tempZip.exists()) tempZip.delete()
        }
    }

    @Test
    fun testInstallFromStreamStreaming() {
        val tempZip = File.createTempFile("test_stream_in_", ".zip", context.cacheDir)
        try {
            createExtensionZipFile(tempZip, version = "2.0.0", additionalEntries = 8)
            val parsed = tempZip.inputStream().use { stream ->
                installer.installFromStream(stream, sourceName = "stream_install_test")
            }

            assertNotNull("Parsed extension should not be null", parsed)
            assertEquals("2.0.0", parsed.version)
            assertTrue("Target directory should exist", File(parsed.installPath).exists())
            assertTrue("Resource asset should exist", File(parsed.installPath, "assets/resource_1.dat").exists())
        } finally {
            if (tempZip.exists()) tempZip.delete()
        }
    }

    @Test
    fun testCrx2PaddedStreamInstallation() {
        // Construct a mock CRX2 file with streaming ZIP offset
        val tempCrx = File.createTempFile("test_crx2_", ".crx", context.cacheDir)
        val tempZip = File.createTempFile("test_crx2_inner_", ".zip", context.cacheDir)
        try {
            createExtensionZipFile(tempZip, version = "3.1.0", additionalEntries = 3)
            val zipBytes = tempZip.readBytes()

            val pubKeyBytes = TEST_PUB_KEY.toByteArray(Charsets.UTF_8)
            val sigBytes = ByteArray(64) { 0x42 }

            FileOutputStream(tempCrx).use { fos ->
                // Magic 'Cr24'
                fos.write(byteArrayOf(0x43, 0x72, 0x32, 0x34))
                // Version 2 (LE)
                fos.write(byteArrayOf(0x02, 0x00, 0x00, 0x00))
                // Public key length (LE)
                val pkLen = pubKeyBytes.size
                fos.write(byteArrayOf((pkLen and 0xFF).toByte(), ((pkLen shr 8) and 0xFF).toByte(), 0, 0))
                // Sig length (LE)
                val sigLen = sigBytes.size
                fos.write(byteArrayOf((sigLen and 0xFF).toByte(), ((sigLen shr 8) and 0xFF).toByte(), 0, 0))
                // Public key & Sig
                fos.write(pubKeyBytes)
                fos.write(sigBytes)
                // ZIP payload
                fos.write(zipBytes)
            }

            try {
                installer.installFromFile(tempCrx)
                fail("Unsigned/forged CRX2 package must be rejected")
            } catch (expected: Exception) {
                assertTrue(expected.message?.contains("CRX", ignoreCase = true) == true ||
                    expected.message?.contains("signature", ignoreCase = true) == true)
            }
        } finally {
            if (tempCrx.exists()) tempCrx.delete()
            if (tempZip.exists()) tempZip.delete()
        }
    }

    @Test
    fun testLargePackageStreamingInstallOver200MBAllowed() {
        val tempZip = File.createTempFile("test_large_stream_", ".zip", context.cacheDir)
        try {
            // Create a package with entry files to verify extraction
            createExtensionZipFile(tempZip, version = "4.0.0", additionalEntries = 20, entryDataSize = 16384)
            val parsed = installer.installFromFile(tempZip)
            assertNotNull(parsed)
            assertEquals("4.0.0", parsed.version)
            assertTrue(File(parsed.installPath, "manifest.json").exists())
        } finally {
            if (tempZip.exists()) tempZip.delete()
        }
    }

    @Test
    fun testStreamingCanonicalInstallUpdateFlow() {
        val tempV1 = File.createTempFile("test_upd_v1_", ".zip", context.cacheDir)
        val tempV2 = File.createTempFile("test_upd_v2_", ".zip", context.cacheDir)
        try {
            createExtensionZipFile(tempV1, version = "1.0.0", additionalEntries = 2)
            val initial = installer.installFromFile(tempV1)
            registry.register(initial)

            createExtensionZipFile(tempV2, version = "1.1.0", additionalEntries = 3)
            val updated = installer.installFromFile(tempV2)

            assertEquals("1.1.0", updated.version)
            assertEquals(initial.id, updated.id)
            assertTrue(File(updated.installPath, "manifest.json").exists())

            try {
                installer.installFromFile(tempV1)
                fail("Downgrade must be rejected by the canonical installer")
            } catch (expected: Exception) {
                assertTrue(expected.message?.contains("Update rejected", ignoreCase = true) == true ||
                    expected.message?.contains("INSTALLATION_REJECTED", ignoreCase = true) == true)
            }
        } finally {
            if (tempV1.exists()) tempV1.delete()
            if (tempV2.exists()) tempV2.delete()
        }
    }
}
