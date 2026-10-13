package com.swift.browser

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebViewStartupStateTest {

    @Test
    fun testWebViewStartupStateEnumValues() {
        val states = BrowserApplication.WebViewStartupState.values().map { it.name }
        assertTrue(states.contains("NOT_STARTED"))
        assertTrue(states.contains("STARTING"))
        assertTrue(states.contains("READY"))
        assertTrue(states.contains("FAILED"))
    }

    @Test
    fun testInitialState() {
        assertFalse(BrowserApplication.isWebViewFailed())
    }
}
