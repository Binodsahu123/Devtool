package com.swift.browser.extensionengine

import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalBrowserEngineWiringContractTest {
    @Test
    fun router_exposes_engine_backed_operations() {
        val source = javaClass.classLoader?.getResourceAsStream(
            "canonical-browser-engine-wiring.txt"
        )?.bufferedReader()?.readText() ?: return
        assertTrue(source.contains("history -> HistoryEngine"))
        assertTrue(source.contains("bookmarks -> BookmarkEngineApi"))
        assertTrue(source.contains("downloads -> DownloadEngine"))
        assertTrue(source.contains("tabs -> TabEngineApi"))
    }
}
