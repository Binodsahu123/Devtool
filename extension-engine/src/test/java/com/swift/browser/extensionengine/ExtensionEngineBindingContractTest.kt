package com.swift.browser.extensionengine

import org.junit.Assert.assertTrue
import org.junit.Test

class ExtensionEngineBindingContractTest {
    @Test
    fun core_extension_domains_have_one_canonical_owner() {
        listOf("tabs", "bookmarks", "history", "downloads").forEach {
            assertTrue(ExtensionEngineBindingContract.isCanonical(it))
        }
    }

    @Test
    fun every_canonical_domain_has_a_browser_core_binding() {
        assertTrue(
            ExtensionEngineBindingContract.canonicalNamespaces.values.all {
                it.startsWith("BrowserCoreServices.")
            }
        )
    }
}
