package com.swift.browser.extensionengine

import android.os.Handler
import android.os.Looper
import java.util.concurrent.ConcurrentHashMap
import com.swift.browser.extensionv8.V8RuntimeConfig

/**
 * Owns worker suspension/termination only.
 *
 * The caller must inject the canonical Mv3ServiceWorkerLifecycleCoordinator used by
 * ServiceWorkerWakeController. No second lifecycle policy is constructed here.
 */
class ServiceWorkerShutdownController(
    private val serviceWorkerRegistry: ServiceWorkerRegistry,
    private val messageBus: MessageBus,
    private val portManager: PortManager? = null,
    private val swJsRuntime: ServiceWorkerJsRuntime? = null,
    private val executionBackend: ServiceWorkerExecutionBackend? = null,
    private val v8Backend: ServiceWorkerExecutionBackend? = null,
    private val extensionRegistry: ExtensionRegistry? = null,
    private val lifecyclePolicy: Mv3ServiceWorkerLifecycleCoordinator
) {
    private val main = Handler(Looper.getMainLooper())
    private val tokens = ConcurrentHashMap<String, Long>()
    private val idle = Runnable { checkAndSuspendIdleWorkers() }

    private val runtimeIdleTimeoutMs: Long = V8RuntimeConfig().idleSuspendTimeoutMs

    @Volatile
    private var monitoring = false

    interface SuspendListener {
        fun onWorkerSuspended(extensionId: String)
    }

    private val listeners = mutableListOf<SuspendListener>()

    @Synchronized
    fun addSuspendListener(listener: SuspendListener) {
        listeners += listener
    }

    @Synchronized
    fun removeSuspendListener(listener: SuspendListener) {
        listeners -= listener
    }

    fun startIdleMonitor() {
        if (monitoring) return
        monitoring = true
        main.removeCallbacks(idle)
        main.postDelayed(idle, IDLE_POLL_MS)
    }

    fun stopIdleMonitor() {
        monitoring = false
        main.removeCallbacks(idle)
    }

    fun cancelSuspension(id: String): Boolean {
        val token = tokens.remove(id) ?: return false
        if (serviceWorkerRegistry.getState(id) == ServiceWorkerState.STOPPING) {
            serviceWorkerRegistry.transitionState(id, ServiceWorkerState.RUNNING)
        }
        return token != 0L
    }

    fun suspendWorker(
        id: String,
        notifyOnSuspend: Boolean = true,
        terminalState: ServiceWorkerState = ServiceWorkerState.STOPPED,
        onComplete: (() -> Unit)? = null
    ): Boolean {
        val worker = serviceWorkerRegistry.getWorker(id) ?: return false

        if (worker.state.isTerminalLifecycleState()) {
            onComplete?.invoke()
            return true
        }

        if (worker.state.isStoppedLike()) {
            if (terminalState.isTerminalLifecycleState()) {
                serviceWorkerRegistry.transitionState(id, terminalState)
            }
            onComplete?.invoke()
            return true
        }

        val terminal = terminalState.isTerminalLifecycleState()
        if (!terminal) {
            val hasActivity = worker.activeCallbacks.isNotEmpty() ||
                worker.hasActiveKeepalives() ||
                worker.pendingEvents.isNotEmpty() ||
                portManager?.hasActiveForExtension(id) == true
            if (hasActivity) return false
        }

        val generation = worker.workerGenerationId
        val token = System.nanoTime()
        tokens[id] = token

        if (!serviceWorkerRegistry.transitionState(id, ServiceWorkerState.STOPPING)) {
            tokens.remove(id, token)
            return false
        }

        main.post {
            try {
                if (tokens[id] != token) return@post

                if (notifyOnSuspend && !terminal) {
                    runCatching {
                        messageBus.broadcastMessage(
                            id,
                            null,
                            org.json.JSONObject()
                                .put("type", "MV3_WORKER_SUSPENDING")
                                .put("extensionId", id)
                                .put("generationId", generation)
                        )
                    }
                }

                portManager?.cleanupForExtension(id)
                serviceWorkerRegistry.terminatePendingRequests(
                    id,
                    "WORKER_STOPPED",
                    generation
                )
                swJsRuntime?.stopWorker(id)

                val ext = extensionRegistry?.getExtension(id)
                if (ext?.manifestVersion ?: 0 >= 3) {
                    v8Backend?.stopWorker(id)
                } else {
                    executionBackend?.stopWorker(id)
                }
            } finally {
                tokens.remove(id, token)
                serviceWorkerRegistry.transitionState(id, terminalState)
                lifecyclePolicy.markSuspended(id)
                worker.executionContext?.state = terminalState

                synchronized(listeners) {
                    listeners.toList()
                }.forEach { listener ->
                    runCatching { listener.onWorkerSuspended(id) }
                }

                onComplete?.invoke()
            }
        }

        return true
    }

    fun checkAndSuspendIdleWorkers(maxIdleMs: Long = runtimeIdleTimeoutMs) {
        val now = System.currentTimeMillis()
        var nextDelay: Long? = null

        serviceWorkerRegistry.getAllWorkers().forEach { worker ->
            if (
                worker.state.isRunningLike() &&
                worker.activeCallbacks.isEmpty() &&
                !worker.hasActiveKeepalives() &&
                portManager?.hasActiveForExtension(worker.extensionId) != true &&
                worker.pendingEvents.isEmpty()
            ) {
                val age = now - worker.lastActiveTimestamp
                if (age >= maxIdleMs) {
                    suspendWorker(worker.extensionId)
                } else {
                    val remaining = (maxIdleMs - age).coerceAtLeast(MIN_MONITOR_DELAY_MS)
                    nextDelay = minOf(nextDelay ?: remaining, remaining)
                }
            }
        }

        if (monitoring) {
            main.removeCallbacks(idle)
            main.postDelayed(
                idle,
                (nextDelay ?: IDLE_POLL_MS).coerceAtLeast(MIN_MONITOR_DELAY_MS)
            )
        }
    }

    companion object {
        private const val IDLE_POLL_MS = 5_000L
        private const val MIN_MONITOR_DELAY_MS = 250L

        // State groups are centralized in ServiceWorkerLifecycleState.
    }
}
