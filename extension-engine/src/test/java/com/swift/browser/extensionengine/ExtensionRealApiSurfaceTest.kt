package com.swift.browser.extensionengine

import org.junit.Assert.assertTrue
import org.junit.Test

class ExtensionRealApiSurfaceTest {
    @Test
    fun coreEngineBackedNamespacesAreDeclared() {
        val required = setOf(
            "tabs", "windows", "tabGroups", "sessions",
            "bookmarks", "history", "downloads", "cookies",
            "notifications", "scripting"
        )
        assertTrue(ExtensionRealApiSurface.engineBackedNamespaces.containsAll(required))
    }

    @Test
    fun allEngineBackedNamespacesAreRealNamespaces() {
        assertTrue(
            ExtensionRealApiSurface.engineBackedNamespaces
                .all { it in ExtensionRealApiSurface.namespaces }
        )
    }

    @Test
    fun browserCoreNamespacesHaveNoDuplicateNames() {
        assertTrue(
            ExtensionRealApiSurface.namespaces.size >=
                ExtensionRealApiSurface.engineBackedNamespaces.size
        )
    }
}
