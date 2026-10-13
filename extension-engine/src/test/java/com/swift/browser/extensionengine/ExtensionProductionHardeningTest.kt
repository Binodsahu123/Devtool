package com.swift.browser.extensionengine

import com.swift.browser.extensionengine.origin.ExtensionUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtensionProductionHardeningTest {
    @Test
    fun webViewOriginIsCanonicalAndValidated() {
        val id = "abcdefghijklmnopabcdefghijklmnop"
        assertEquals("https://$id.extension.orion.invalid", ExtensionUrl.toWebViewOrigin(id))
        assertThrowsIllegalArgument { ExtensionUrl.toWebViewOrigin("../bad") }
    }

    @Test
    fun updateUrlsRequireHttps() {
        assertEquals("https", ExtensionNetworkSecurity.validateUpdateUrl("https://example.com/update.xml").protocol)
        assertThrowsIllegalArgument { ExtensionNetworkSecurity.validateUpdateUrl("http://example.com/update.xml") }
        assertThrowsIllegalArgument { ExtensionNetworkSecurity.validateUpdateUrl("https://user:pass@example.com/update.xml") }
    }

    @Test
    fun invalidUpdateEntriesAreRejected() {
        val parser = ExtensionUpdateManifestParser()
        val invalidId = """{"id":"not-an-extension-id","version":"1.0.1","url":"https://example.com/a.crx"}"""
        val insecure = """{"id":"abcdefghijklmnopabcdefghijklmnop","version":"1.0.1","url":"http://example.com/a.crx"}"""
        assertTrue(parser.parseUpdateManifest(invalidId).isEmpty())
        assertTrue(parser.parseUpdateManifest(insecure).isEmpty())
    }

    private fun assertThrowsIllegalArgument(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }
}
