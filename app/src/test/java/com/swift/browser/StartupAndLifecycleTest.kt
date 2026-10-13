package com.swift.browser

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.swift.browser.browserengine.StartupCoordinator
import com.swift.browser.browserengine.StartupState
import com.swift.browser.BrowserApplication.WebViewStartupState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class StartupAndLifecycleTest {

    private lateinit var coordinator: StartupCoordinator

    @Before
    fun setUp() {
        coordinator = StartupCoordinator.instance
        coordinator.onApplicationStarted()
    }

    @Test
    fun testInitialStartupStateIsStarting() {
        assertEquals(StartupState.STARTING, coordinator.startupState.value)
        assertTrue(coordinator.isBlockingSplash)
    }

    @Test
    fun testFirstFrameRenderedTransitionsToBrowserReady() {
        coordinator.onFirstFrameRendered()
        assertEquals(StartupState.BROWSER_READY, coordinator.startupState.value)
        assertFalse(coordinator.isBlockingSplash)
    }

    @Test
    fun testMarkReadyTransitionsToReady() {
        coordinator.onFirstFrameRendered()
        coordinator.markReady()
        assertEquals(StartupState.READY, coordinator.startupState.value)
    }

    @Test
    fun testStartupFailureTransitionsToFailedAndRetainsError() {
        val testException = IllegalStateException("WebView startup failed test")
        coordinator.markFailed(testException)
        assertEquals(StartupState.FAILED, coordinator.startupState.value)
        assertEquals(testException, coordinator.startupError.value)

        // Mark ready must not overwrite FAILED state
        coordinator.markReady()
        assertEquals(StartupState.FAILED, coordinator.startupState.value)
    }

    @Test
    fun testRetryStartupResetsFromFailedToStarting() {
        val testException = IllegalStateException("Failure")
        coordinator.markFailed(testException)
        assertEquals(StartupState.FAILED, coordinator.startupState.value)

        coordinator.retryStartup()
        assertEquals(StartupState.STARTING, coordinator.startupState.value)
        assertEquals(null, coordinator.startupError.value)
    }

    @Test
    fun testWebViewStartupStateEnum() {
        assertEquals(4, WebViewStartupState.values().size)
        assertTrue(WebViewStartupState.values().contains(WebViewStartupState.NOT_STARTED))
        assertTrue(WebViewStartupState.values().contains(WebViewStartupState.STARTING))
        assertTrue(WebViewStartupState.values().contains(WebViewStartupState.READY))
        assertTrue(WebViewStartupState.values().contains(WebViewStartupState.FAILED))
    }
}
