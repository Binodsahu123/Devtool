package com.swift.browser.extensionengine

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

/**
 * Canonical MV3 lifecycle policy owner.
 *
 * Responsibilities are deliberately narrow:
 *  - allocate worker generations;
 *  - record lifecycle timestamps/state metadata;
 *  - serialize crash/backoff decisions;
 *  - schedule recovery only through the injected wake callback.
 *
 * Worker execution remains owned by ServiceWorkerWakeController and the selected backend.
 * No WebView or V8 object is created here.
 */
class Mv3ServiceWorkerLifecycleCoordinator(
    private val registry: ServiceWorkerRegistry
) {
    private val restartAttempts = ConcurrentHashMap<String, Int>()
    private val restartTokens = ConcurrentHashMap<String, Long>()
    private val main = Handler(Looper.getMainLooper())
    private val stabilityTokens = ConcurrentHashMap<String, Long>()

    /** Set once by ExtensionEngineImpl after the wake controller has been constructed. */
    @Volatile
    private var restartCallback: ((String) -> Unit)? = null

    data class WakePlan(
        val wakeId: String,
        val generation: Int,
        val shouldStart: Boolean,
        val shouldDispatch: Boolean
    )

    data class FailureDecision(
        val extensionId: String,
        val generation: Int,
        val retryDelayMs: Long,
        val retryScheduled: Boolean,
        val terminal: Boolean
    )

    fun setRestartCallback(callback: ((String) -> Unit)?) {
        restartCallback = callback
    }

    @Synchronized
    fun planWake(extensionId: String, reason: String): WakePlan? {
        val worker = registry.getWorker(extensionId) ?: return null
        if (!worker.isMV3) return null
        if (!worker.enabled || worker.state.isTerminalLifecycleState()) return null

        val now = System.currentTimeMillis()
        if (worker.retryNotBefore > now) return null

        val start = !worker.state.isRunningLike()
        val wakeId = "${extensionId}_${now}_${UUID.randomUUID()}"
        worker.lastWakeId = wakeId
        worker.lastWakeAt = now
        worker.wakeReason = reason

        val generation = if (start) {
            registry.incrementWorkerGeneration(extensionId)
        } else {
            worker.workerGenerationId
        }

        return WakePlan(
            wakeId = wakeId,
            generation = generation,
            shouldStart = start,
            shouldDispatch = true
        )
    }

    @Synchronized
    fun markStarted(extensionId: String, generation: Int): Boolean {
        val worker = registry.getWorker(extensionId) ?: return false
        if (!worker.isMV3 || worker.workerGenerationId != generation) return false

        val now = System.currentTimeMillis()
        worker.nativeRuntimeReady = true
        worker.lastStartAt = now
        worker.retryNotBefore = 0L
        restartTokens.remove(extensionId)

        // A worker that crashes immediately after start must still count toward the consecutive
        // failure budget. Reset the budget only after one full stability window.
        val token = System.nanoTime()
        stabilityTokens[extensionId] = token
        main.postDelayed({
            synchronized(this) {
                if (stabilityTokens[extensionId] != token) return@synchronized
                stabilityTokens.remove(extensionId)
                val current = registry.getWorker(extensionId) ?: return@synchronized
                if (current.workerGenerationId != generation || !current.nativeRuntimeReady) return@synchronized
                current.crashCount = 0
                restartAttempts.remove(extensionId)
            }
        }, STABILITY_WINDOW_MS)
        return true
    }

    @Synchronized
    fun markSuspended(extensionId: String) {
        registry.getWorker(extensionId)?.apply {
            nativeRuntimeReady = false
            lastSuspendAt = System.currentTimeMillis()
        }
        restartTokens.remove(extensionId)
    }

    /**
     * Single crash/failure entry point for both startup failures and post-start runtime crashes.
     * Duplicate notifications while the same worker is already being recovered do not increment
     * the crash budget a second time.
     */
    @Synchronized
    fun handleRuntimeFailure(extensionId: String, generation: Int, reason: String): FailureDecision {
        val worker = registry.getWorker(extensionId)
            ?: return FailureDecision(extensionId, generation, Long.MAX_VALUE, false, true)
        if (!worker.isMV3 || worker.enabled.not()) {
            return FailureDecision(extensionId, generation, Long.MAX_VALUE, false, true)
        }
        if (worker.state.isTerminalLifecycleState()) {
            return FailureDecision(extensionId, generation, Long.MAX_VALUE, false, true)
        }

        // Ignore stale crash callbacks from an isolate that is no longer authoritative.
        if (generation > 0 && worker.workerGenerationId != generation) {
            return FailureDecision(extensionId, generation, 0L, false, false)
        }

        val alreadyRecovering = worker.state == ServiceWorkerState.CRASHED ||
            worker.state == ServiceWorkerState.RESTART_PENDING ||
            worker.state == ServiceWorkerState.RESTARTING

        if (alreadyRecovering) {
            val remaining = (worker.retryNotBefore - System.currentTimeMillis()).coerceAtLeast(0L)
            return FailureDecision(
                extensionId = extensionId,
                generation = worker.workerGenerationId,
                retryDelayMs = remaining,
                retryScheduled = remaining > 0L,
                terminal = worker.state == ServiceWorkerState.FAILED
            )
        }

        val attempt = (restartAttempts[extensionId] ?: 0) + 1
        restartAttempts[extensionId] = attempt
        worker.crashCount = attempt
        worker.nativeRuntimeReady = false
        worker.lastCrashAt = System.currentTimeMillis()

        // 500ms, 1s, 2s, 4s, ... capped at one minute.
        val delay = min(60_000L, 500L shl min(attempt - 1, 7))
        worker.retryNotBefore = System.currentTimeMillis() + delay
        worker.restartCount += 1

        registry.transitionState(extensionId, ServiceWorkerState.CRASHED)

        val terminal = attempt >= MAX_CONSECUTIVE_FAILURES
        if (terminal) {
            registry.transitionState(extensionId, ServiceWorkerState.FAILED)
            Log.e(TAG, "MV3 worker disabled after $attempt consecutive failures: $extensionId reason=$reason")
            return FailureDecision(extensionId, worker.workerGenerationId, delay, false, true)
        }

        registry.transitionState(extensionId, ServiceWorkerState.RESTART_PENDING)
        val scheduled = scheduleRecovery(extensionId, delay)
        Log.e(
            TAG,
            "MV3 worker failure extension=$extensionId generation=${worker.workerGenerationId} attempt=$attempt retryInMs=$delay scheduled=$scheduled reason=$reason"
        )

        return FailureDecision(extensionId, worker.workerGenerationId, delay, scheduled, false)
    }

    @Synchronized
    fun reset(extensionId: String) {
        restartAttempts.remove(extensionId)
        restartTokens.remove(extensionId)
        stabilityTokens.remove(extensionId)
        registry.getWorker(extensionId)?.apply {
            retryNotBefore = 0L
            crashCount = 0
            restartCount = 0
            nativeRuntimeReady = false
        }
    }

    private fun scheduleRecovery(extensionId: String, delayMs: Long): Boolean {
        val worker = registry.getWorker(extensionId) ?: return false
        // Do not restart an otherwise idle service worker merely because it crashed. A worker
        // that has pending events must be recovered; future browser events can wake dormant workers.
        if (worker.pendingEvents.isEmpty()) {
            worker.retryNotBefore = 0L
            worker.nativeRuntimeReady = false
            registry.transitionState(extensionId, ServiceWorkerState.DORMANT)
            return false
        }

        val token = System.nanoTime()
        restartTokens[extensionId] = token
        main.postDelayed({
            synchronized(this) {
                if (restartTokens[extensionId] != token) return@synchronized
                restartTokens.remove(extensionId)
                val current = registry.getWorker(extensionId) ?: return@synchronized
                if (!current.enabled || current.isMV3.not()) return@synchronized
                if (current.state != ServiceWorkerState.RESTART_PENDING) return@synchronized
                if (current.pendingEvents.isEmpty()) {
                    current.retryNotBefore = 0L
                    current.nativeRuntimeReady = false
                    registry.transitionState(extensionId, ServiceWorkerState.DORMANT)
                    return@synchronized
                }
                if (System.currentTimeMillis() < current.retryNotBefore) return@synchronized
                restartCallback?.invoke(extensionId)
            }
        }, delayMs.coerceAtLeast(1L))
        return true
    }

    companion object {
        private const val TAG = "MV3Lifecycle"
        private const val MAX_CONSECUTIVE_FAILURES = 5
        private const val STABILITY_WINDOW_MS = 10_000L
    }
}
