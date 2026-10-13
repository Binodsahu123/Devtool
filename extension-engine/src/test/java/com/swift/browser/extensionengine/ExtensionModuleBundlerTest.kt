package com.swift.browser.extensionengine

import java.nio.file.Files
import org.junit.Assert.assertTrue
import org.junit.Test

import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ExtensionModuleBundlerTest {
    @Test
    fun bundlesStaticImportsAndExportsForLegacyV8() {
        val root = Files.createTempDirectory("orion-ext-mod").toFile()
        try {
            root.resolve("worker.js").writeText("import { add } from './math.js'; export const result = add(2, 3);")
            root.resolve("math.js").writeText("export function add(a, b) { return a + b; }")

            val result = ExtensionModuleBundler.build(root, "worker.js")

            assertTrue(result.modules.contains("worker.js"))
            assertTrue(result.modules.contains("math.js"))
            assertTrue(result.source.contains("__require(\"math.js\")"))
            assertTrue(result.source.contains("exports.result = result;"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsMissingEntryModule() {
        val root = Files.createTempDirectory("orion-ext-mod-missing").toFile()
        try {
            try {
                ExtensionModuleBundler.build(root, "worker.js")
                throw AssertionError("Expected missing module failure")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message?.contains("Extension module not found") == true)
            }
        } finally {
            root.deleteRecursively()
        }
    }
}
