package com.swift.browser.tabengine.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Chromium-aligned Browser Controls Offset Manager.
 *
 * Models browser-controls visibility as a continuous ratio in [0f, 1f]:
 * - 0f = fully visible (shown)
 * - 1f = fully hidden
 *
 * During active scrolling (drag or fling), the progress is updated 1:1 with the
 * WebView scroll delta without threshold dead zones or continuous animation loops.
 * Settling to the nearest endpoint occurs only after scrolling has become idle.
 */
class ScrollChromeController(private val scope: CoroutineScope) {
    private val _hideProgress = MutableStateFlow(0f)
    val hideProgress: StateFlow<Float> = _hideProgress.asStateFlow()

    private var currentAnimationJob: Job? = null
    private var pendingSettleJob: Job? = null
    private var interactionToken: Long = 0L

    @Volatile
    private var isUserTouching = false

    @Volatile
    private var toolbarHeightPx = 160f

    val collapseDistance: Float
        get() = toolbarHeightPx

    fun updateCollapseDistance(distancePx: Float) {
        if (distancePx.isFinite() && distancePx > 10f) {
            toolbarHeightPx = distancePx
        }
    }

    /**
     * Called when user touches the screen (ACTION_DOWN).
     */
    fun onTouchStart() {
        isUserTouching = true
        cancelSettle()
    }

    /**
     * Consume WebView scroll delta continuously.
     * Positive dy = scrolling down (finger swiping up) -> collapse chrome.
     * Negative dy = scrolling up (finger swiping down) -> reveal chrome.
     */
    fun onScroll(dy: Int, isAtTop: Boolean) {
        if (!scope.isActive) return

        if (isAtTop) {
            cancelSettle()
            _hideProgress.value = 0f
            return
        }

        if (dy == 0) return

        cancelSettle()

        val deltaProgress = dy.toFloat() / collapseDistance
        val current = _hideProgress.value
        val newProgress = (current + deltaProgress).coerceIn(0f, 1f)
        _hideProgress.value = newProgress

        // If user is not touching, WebView is flinging. Debounce settle until fling stops.
        if (!isUserTouching) {
            scheduleSettleCheck(delayMs = 120L)
        }
    }

    /**
     * Called when user releases touch (ACTION_UP / ACTION_CANCEL).
     */
    fun onTouchRelease() {
        isUserTouching = false
        // Settle after a brief delay to allow fling scroll callbacks to arrive first
        scheduleSettleCheck(delayMs = 80L)
    }

    private fun cancelSettle() {
        interactionToken++
        pendingSettleJob?.cancel()
        pendingSettleJob = null
        currentAnimationJob?.cancel()
        currentAnimationJob = null
    }

    private fun scheduleSettleCheck(delayMs: Long) {
        if (!scope.isActive) return
        pendingSettleJob?.cancel()
        val token = ++interactionToken

        pendingSettleJob = scope.launch {
            if (delayMs > 0) {
                delay(delayMs)
            }
            if (token != interactionToken || !isActive) return@launch
            settleToNearestEndpoint(token)
        }
    }

    private fun settleToNearestEndpoint(token: Long) {
        val current = _hideProgress.value
        val target = if (current >= 0.5f) 1f else 0f

        if (abs(current - target) < 0.01f) {
            if (token == interactionToken) {
                _hideProgress.value = target
            }
            return
        }

        animateToInternal(target, token = token, durationMillis = 160)
    }

    fun animateToRestState(show: Boolean) {
        val target = if (show) 0f else 1f
        cancelSettle()
        val token = ++interactionToken
        animateToInternal(target, token = token, durationMillis = 180)
    }

    fun reset() {
        cancelSettle()
        _hideProgress.value = 0f
    }

    fun setVisible(visible: Boolean) {
        val target = if (visible) 0f else 1f
        cancelSettle()
        val token = ++interactionToken
        animateToInternal(target, token = token, durationMillis = 140)
    }

    private fun animateToInternal(target: Float, token: Long, durationMillis: Int) {
        if (!scope.isActive) return
        val clampedTarget = target.coerceIn(0f, 1f)
        val startVal = _hideProgress.value

        currentAnimationJob?.cancel()
        currentAnimationJob = scope.launch {
            val anim = Animatable(startVal)
            try {
                anim.animateTo(
                    targetValue = clampedTarget,
                    animationSpec = tween(durationMillis = durationMillis, easing = FastOutSlowInEasing)
                ) {
                    if (token == interactionToken) {
                        _hideProgress.value = value.coerceIn(0f, 1f)
                    }
                }
                if (token == interactionToken) {
                    _hideProgress.value = clampedTarget
                }
            } catch (_: CancellationException) {
                // Interrupted by new scroll gesture; do not overwrite
            } catch (_: Throwable) {
                if (token == interactionToken) {
                    _hideProgress.value = clampedTarget
                }
            } finally {
                if (currentAnimationJob == coroutineContext[Job]) {
                    currentAnimationJob = null
                }
            }
        }
    }

    fun dispose() {
        cancelSettle()
    }
}

