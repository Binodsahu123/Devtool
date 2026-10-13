package com.swift.browser.extensionengine

import org.junit.Assert.*
import org.junit.Test

class ExtensionEngineUnitTest {

    @Test
    fun testContentScriptSpecMatching() {
        val spec = ContentScriptSpec(
            matches = listOf("https://*.example.com/*"),
            js = listOf("inject.js"),
            css = emptyList()
        )

        assertEquals(1, spec.matches.size)
        assertEquals("inject.js", spec.js.first())
        assertEquals("document_idle", spec.runAt)
    }

    @Test
    fun testPathSafetyAndIdGeneration() {
        assertTrue(PathSanitizer.isSafeRelativePath("scripts/content.js"))
        assertTrue(PathSanitizer.isSafeRelativePath("assets/icons/icon_48.png"))
        assertFalse(PathSanitizer.isSafeRelativePath("../etc/passwd"))
        assertFalse(PathSanitizer.isSafeRelativePath("scripts/../../secret.txt"))

        val id1 = ExtensionIdGenerator.generateOrionLocalIdentity("MyExtension", "").id
        val id2 = ExtensionIdGenerator.generateOrionLocalIdentity("MyExtension", "").id
        val id3 = ExtensionIdGenerator.generateOrionLocalIdentity("OtherExtension", "").id

        assertEquals(32, id1.length)
        assertEquals(id1, id2)
        assertNotEquals(id1, id3)
    }


    @Test
    fun registryPreservesGenerationAndRejectsIdentityChange() {
        val registry = ExtensionRegistryImpl()
        val id = "abcdefghijklmnopqrstuvwxzyabcdef".take(32)
        val first = ParsedExtension(
            id = id,
            name = "Test",
            version = "1.0",
            identity = ExtensionIdentity(id, IdentityType.ORION_LOCAL_IDENTITY)
        )
        registry.register(first, ExtensionState.INSTALLED_ENABLED)
        assertEquals(1, registry.getExtensionGeneration(id))
        assertTrue(registry.isExtensionEnabled(id))

        val second = first.copy(version = "2.0")
        registry.register(second, ExtensionState.INSTALLED_ENABLED)
        assertEquals(2, registry.getExtensionGeneration(id))

        val signedIdentity = ExtensionIdentity(
            id = id,
            identityType = IdentityType.CHROME_COMPATIBLE_IDENTITY,
            publicKeyBytes = byteArrayOf(1, 2, 3)
        )
        try {
            registry.register(second.copy(identity = signedIdentity), ExtensionState.INSTALLED_ENABLED)
            fail("Expected identity continuity rejection")
        } catch (e: ExtensionError.RegistryError.ExtensionAlreadyExists) {
            // expected
        }
    }

}
