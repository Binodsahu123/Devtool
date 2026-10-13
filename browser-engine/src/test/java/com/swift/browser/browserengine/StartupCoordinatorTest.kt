package com.swift.browser.browserengine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class StartupCoordinatorTest {

    private lateinit var coordinator: StartupCoordinator

    @Before
    fun setUp() {
        coordinator = StartupCoordinator.instance
        coordinator.onApplicationStarted()
    }

    @Test
    fun testInitialStateIsStarting() {
        assertEquals(StartupState.STARTING, coordinator.startupState.value)
        assertNull(coordinator.startupError.value)
    }

    @Test
    fun testFirstFrameTransitionToBrowserReady() {
        coordinator.onFirstFrameRendered()
        assertEquals(StartupState.BROWSER_READY, coordinator.startupState.value)
    }

    @Test
    fun testMarkReadyTransitionsToReady() {
        coordinator.onFirstFrameRendered()
        coordinator.markReady()
        assertEquals(StartupState.READY, coordinator.startupState.value)
    }

    @Test
    fun testMarkFailedPreservesFailureAndPreventsReady() {
        val testException = IllegalStateException("WebView Startup Exception")
        coordinator.markFailed(testException)

        assertEquals(StartupState.FAILED, coordinator.startupState.value)
        assertEquals(testException, coordinator.startupError.value)

        // Attempting to mark ready while failed must not overwrite failure
        coordinator.markReady()
        assertEquals(StartupState.FAILED, coordinator.startupState.value)
        assertEquals(testException, coordinator.startupError.value)
    }

    @Test
    fun testRetryResetsStateToStarting() {
        val testException = RuntimeException("Fatal engine crash")
        coordinator.markFailed(testException)
        assertEquals(StartupState.FAILED, coordinator.startupState.value)

        coordinator.retryStartup()
        assertEquals(StartupState.STARTING, coordinator.startupState.value)
        assertNull(coordinator.startupError.value)

        coordinator.onFirstFrameRendered()
        assertEquals(StartupState.BROWSER_READY, coordinator.startupState.value)

        coordinator.markReady()
        assertEquals(StartupState.READY, coordinator.startupState.value)
    }
}
