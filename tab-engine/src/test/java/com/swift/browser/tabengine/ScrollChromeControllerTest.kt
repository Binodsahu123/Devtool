package com.swift.browser.tabengine

import com.swift.browser.tabengine.ui.ScrollChromeController
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ScrollChromeControllerTest {
    @Test
    fun scrollFollowsDeltaWithoutWaitingForThreshold() = runTest {
        val controller = ScrollChromeController(this)
        controller.updateCollapseDistance(100f)

        controller.onScroll(25, false)

        assertEquals(0.25f, controller.hideProgress.value, 0.001f)
    }

    @Test
    fun reverseScrollRevealsToolbarImmediately() = runTest {
        val controller = ScrollChromeController(this)
        controller.updateCollapseDistance(100f)

        controller.onScroll(80, false)
        controller.onScroll(-20, false)

        assertEquals(0.60f, controller.hideProgress.value, 0.001f)
    }

    @Test
    fun releaseAboveMidpointSettlesToHidden() = runTest {
        val controller = ScrollChromeController(this)
        controller.updateCollapseDistance(100f)

        controller.onScroll(60, false)
        controller.onTouchRelease()
        advanceUntilIdle()

        assertEquals(1f, controller.hideProgress.value, 0.001f)
        assertTrue(controller.hideProgress.value >= 0.99f)
    }

    @Test
    fun releaseBelowMidpointSettlesToShown() = runTest {
        val controller = ScrollChromeController(this)
        controller.updateCollapseDistance(100f)

        controller.onScroll(40, false)
        controller.onTouchRelease()
        advanceUntilIdle()

        assertEquals(0f, controller.hideProgress.value, 0.001f)
    }

    @Test
    fun reachingTopForcesToolbarVisible() = runTest {
        val controller = ScrollChromeController(this)
        controller.updateCollapseDistance(100f)

        controller.onScroll(90, false)
        controller.onScroll(0, true)
        advanceUntilIdle()

        assertEquals(0f, controller.hideProgress.value, 0.001f)
    }

    @Test
    fun newGestureCancelsOngoingSettleWithoutStaleOverwrite() = runTest {
        val controller = ScrollChromeController(this)
        controller.updateCollapseDistance(100f)

        controller.onScroll(60, false)
        controller.onTouchRelease()
        // New scroll comes in before settle finishes
        controller.onTouchStart()
        controller.onScroll(-30, false)

        assertEquals(0.30f, controller.hideProgress.value, 0.001f)

        advanceUntilIdle()
        // Since touch is still active (no onTouchRelease called after the new touch),
        // progress remains at 0.30f and does NOT get overwritten to 1.0f by the old settle job
        assertEquals(0.30f, controller.hideProgress.value, 0.001f)
    }

    @Test
    fun boundaryClamping() = runTest {
        val controller = ScrollChromeController(this)
        controller.updateCollapseDistance(100f)

        controller.onScroll(500, false)
        assertEquals(1f, controller.hideProgress.value, 0.001f)

        controller.onScroll(-800, false)
        assertEquals(0f, controller.hideProgress.value, 0.001f)
    }

    @Test
    fun resetForcesZeroImmediately() = runTest {
        val controller = ScrollChromeController(this)
        controller.updateCollapseDistance(100f)

        controller.onScroll(80, false)
        controller.reset()

        assertEquals(0f, controller.hideProgress.value, 0.001f)
    }
}
