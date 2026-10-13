package com.swift.browser.extensionengine

import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Browser-process MV3 wake coordinator.
 *
 * Canonical flow:
 * browser event -> persistent/listener gate -> queued event -> lifecycle policy ->
 * backend start -> native event dispatch -> queue completion.
 *
 * MV3 is Native-V8-only. MV2 remains owned by BackgroundScriptManager.
 */
class ServiceWorkerWakeController(
    private val serviceWorkerRegistry: ServiceWorkerRegistry,
    private val executionBackend: ServiceWorkerExecutionBackend,
    private val extensionRegistry: ExtensionRegistry,
    private val bootstrapScriptProvider: (String) -> String,
    private val permissionAdapter: ExtensionPermissionAdapter? = null,
    private val backendSelector: ServiceWorkerBackendSelector? = null,
    private val lifecyclePolicy: Mv3ServiceWorkerLifecycleCoordinator
) {
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val locks = ConcurrentHashMap<String, Any>()

    private data class Awaiter(
        val callback: WakeCallback,
        val eventId: String?
    )

    private data class Flight(
        val awaiters: MutableList<Awaiter> = mutableListOf()
    )

    private val flights = ConcurrentHashMap<String, Flight>()

    @Volatile
    private var cancelStop: ((String) -> Boolean)? = null

    interface WakeCallback {
        fun onWoken(extensionId: String, success: Boolean)
    }

    private fun notifyDirect(id: String, callback: WakeCallback?, ok: Boolean) {
        if (callback == null) return
        runCatching { callback.onWoken(id, ok) }
            .onFailure { Log.w(TAG, "Wake callback failed for $id", it) }
    }

    private fun addAwaiter(id: String, callback: WakeCallback?, eventId: String?) {
        if (callback == null) return
        val flight = flights.computeIfAbsent(id) { Flight() }
        synchronized(flight) {
            flight.awaiters += Awaiter(callback, eventId)
        }
    }

    private fun completeWorkerReady(id: String, ok: Boolean) {
        val flight = flights[id] ?: return
        val complete = mutableListOf<Awaiter>()
        synchronized(flight) {
            val iterator = flight.awaiters.iterator()
            while (iterator.hasNext()) {
                val awaiter = iterator.next()
                if (awaiter.eventId == null || !ok) {
                    complete += awaiter
                    iterator.remove()
                }
            }
        }
        notifyAwaiters(id, complete, ok)
        removeFlightIfEmpty(id, flight)
    }

    private fun completeEvent(id: String, eventId: String, ok: Boolean) {
        val flight = flights[id] ?: return
        val complete = mutableListOf<Awaiter>()
        synchronized(flight) {
            val iterator = flight.awaiters.iterator()
            while (iterator.hasNext()) {
                val awaiter = iterator.next()
                if (!ok || awaiter.eventId == eventId) {
                    complete += awaiter
                    iterator.remove()
                }
            }
        }
        notifyAwaiters(id, complete, ok)
        removeFlightIfEmpty(id, flight)
    }

    private fun failAll(id: String) {
        val flight = flights.remove(id) ?: return
        val complete = synchronized(flight) {
            val copy = flight.awaiters.toList()
            flight.awaiters.clear()
            copy
        }
        notifyAwaiters(id, complete, false)
    }

    private fun notifyAwaiters(
        id: String,
        awaiters: List<Awaiter>,
        ok: Boolean
    ) {
        awaiters.forEach { awaiter ->
            runCatching { awaiter.callback.onWoken(id, ok) }
                .onFailure { Log.w(TAG, "Wake callback failed for $id", it) }
        }
    }

    private fun removeFlightIfEmpty(id: String, flight: Flight) {
        synchronized(flight) {
            if (flight.awaiters.isEmpty()) {
                flights.remove(id, flight)
            }
        }
    }

    fun setShutdownCancellation(f: (String) -> Boolean) {
        cancelStop = f
    }

    fun wakeAndExecute(
        id: String,
        event: QueuedServiceWorkerEvent?,
        callback: WakeCallback? = null,
        isPrivate: Boolean = false
    ) {
        val currentGen = serviceWorkerRegistry.getWorkerGeneration(id)
        Log.i("CRASH_BOUNDARY", "[CRASH_BOUNDARY] Stage: worker wake request | Status: START | Extension: $id | Generation: $currentGen | Event: ${event?.eventName ?: "none"}")
        try {
            val worker = serviceWorkerRegistry.getWorker(id)
            val ext = extensionRegistry.getExtension(id)

            if (
                worker == null ||
                ext == null ||
                !extensionRegistry.isExtensionEnabled(id)
            ) {
                Log.w("CRASH_BOUNDARY", "[CRASH_BOUNDARY] Stage: worker wake request | Status: ERROR | Extension: $id | Generation: $currentGen | Reason: not enabled or missing")
                notifyDirect(id, callback, false)
                return
            }

            if (
                isPrivate &&
                !(permissionAdapter?.isAllowedInPrivate(id) ?: ext.allowedInPrivate)
            ) {
                Log.w("CRASH_BOUNDARY", "[CRASH_BOUNDARY] Stage: worker wake request | Status: ERROR | Extension: $id | Generation: $currentGen | Reason: not allowed in private")
                notifyDirect(id, callback, false)
                return
            }

            // Enqueue only at the public entry point. If STOPPING is encountered,
            // continueWake() reuses the already-queued object and never enqueues again.
            if (event != null && !serviceWorkerRegistry.enqueueEvent(id, event)) {
                Log.w("CRASH_BOUNDARY", "[CRASH_BOUNDARY] Stage: worker wake request | Status: ERROR | Extension: $id | Generation: $currentGen | Reason: failed to enqueue event")
                notifyDirect(id, callback, false)
                return
            }

            worker.wakeReason = event?.eventName ?: worker.wakeReason.ifBlank { "EXTERNAL_WAKE" }
            worker.lastActiveTimestamp = System.currentTimeMillis()
            addAwaiter(id, callback, event?.eventId)

            Log.i("CRASH_BOUNDARY", "[CRASH_BOUNDARY] Stage: worker wake request | Status: SUCCESS | Extension: $id | Generation: $currentGen | Reason: ${worker.wakeReason}")
            runWakeDecision(
                id = id,
                event = event,
                isPrivate = isPrivate
            )
        } catch (t: Throwable) {
            Log.e("CRASH_BOUNDARY", "[CRASH_BOUNDARY] Stage: worker wake request | Status: ERROR | Extension: $id | Generation: $currentGen | Error: ${t.message}", t)
            Log.e(TAG, "Error in wakeAndExecute for $id", t)
            failAll(id)
        }
    }

    private fun continueWake(
        id: String,
        event: QueuedServiceWorkerEvent?,
        isPrivate: Boolean
    ) {
        runWakeDecision(
            id = id,
            event = event,
            isPrivate = isPrivate
        )
    }

    private fun runWakeDecision(
        id: String,
        event: QueuedServiceWorkerEvent?,
        isPrivate: Boolean
    ) {
        try {
            val worker = serviceWorkerRegistry.getWorker(id)
            val ext = extensionRegistry.getExtension(id)
            if (worker == null || ext == null) {
                failAll(id)
                return
            }

            val lock = locks.computeIfAbsent(id) { Any() }
            synchronized(lock) {
                val state = serviceWorkerRegistry.getState(id)

                if (state == ServiceWorkerState.STOPPING) {
                    val cancelled = cancelStop?.invoke(id) == true
                    if (!cancelled) {
                        failAll(id)
                        return
                    }
                    main.post {
                        continueWake(id, event, isPrivate)
                    }
                    return
                }

                if (state == ServiceWorkerState.STARTING || state == ServiceWorkerState.WAKE) {
                    event?.generationId = worker.workerGenerationId
                    return
                }

                if (state.isRunningLike()) {
                    event?.generationId = worker.workerGenerationId
                    if (event != null) {
                        dispatch(id)
                    } else {
                        completeWorkerReady(id, true)
                    }
                    return
                }

                if (!state.isStartable()) {
                    failAll(id)
                    return
                }

                // A recovery backoff is already scheduled by the canonical lifecycle policy.
                // Keep the newly queued event and its awaiter intact; the scheduled recovery
                // will re-enter this controller against the same queue.
                if (worker.retryNotBefore > System.currentTimeMillis()) {
                    return
                }

                val plan = lifecyclePolicy.planWake(
                    extensionId = id,
                    reason = worker.wakeReason.ifBlank { event?.eventName ?: "EXTERNAL_WAKE" }
                ) ?: run {
                    failAll(id)
                    return
                }

                if (!plan.shouldStart) {
                    event?.generationId = plan.generation
                    if (event != null) dispatch(id) else completeWorkerReady(id, true)
                    return
                }

                beginStart(
                    id = id,
                    ext = ext,
                    worker = worker,
                    generation = plan.generation,
                    isPrivate = isPrivate,
                    lock = lock
                )
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Error deciding wake for $id", t)
            failAll(id)
        }
    }

    private fun beginStart(
        id: String,
        ext: ParsedExtension,
        worker: ServiceWorkerRegistration,
        generation: Int,
        isPrivate: Boolean,
        lock: Any
    ) {
        // Generation is allocated by lifecyclePolicy exactly once. Every queued event
        // that belongs to this newly-created native isolate is rebound here.
        worker.pendingEvents.forEach { queued ->
            if (queued.deliveryState != WorkerEventDeliveryState.DELIVERED) {
                queued.generationId = generation
            }
        }

        worker.executionContext = ServiceWorkerExecutionContext(
            extensionId = id,
            manifestVersion = ext.manifestVersion,
            workerGenerationId = generation,
            wakeReason = worker.wakeReason,
            isPrivate = isPrivate,
            state = ServiceWorkerState.STARTING
        )

        if (!serviceWorkerRegistry.transitionState(id, ServiceWorkerState.STARTING)) {
            failAll(id)
            return
        }

        val backend = try {
            selectBackend(ext)
        } catch (t: Throwable) {
            Log.e(TAG, "Cannot select backend for $id", t)
            runCatching { serviceWorkerRegistry.transitionState(id, ServiceWorkerState.FAILED) }
            failAll(id)
            return
        }

        scope.launch {
            val boot = try {
                bootstrapScriptProvider(id)
            } catch (t: Throwable) {
                main.post {
                    runCatching {
                        serviceWorkerRegistry.transitionState(id, ServiceWorkerState.FAILED)
                    }
                    failAll(id)
                }
                return@launch
            }

            try {
                backend.startWorker(
                    parsedExtension = ext,
                    scriptPath = ext.backgroundSpec.serviceWorker.ifBlank { worker.scriptPath },
                    generationId = generation,
                    bootstrapScript = boot,
                    isPrivate = isPrivate
                ) { result, error ->
                    main.post {
                        synchronized(lock) {
                            try {
                                if (serviceWorkerRegistry.getWorkerGeneration(id) != generation) {
                                    failAll(id)
                                    return@synchronized
                                }

                                if (result == WorkerStartupResult.SUCCESS) {
                                    lifecyclePolicy.markStarted(id, generation)
                                    if (
                                        !serviceWorkerRegistry.transitionState(
                                            id,
                                            ServiceWorkerState.RUNNING
                                        )
                                    ) {
                                        failAll(id)
                                        return@synchronized
                                    }
                                    worker.executionContext?.state = ServiceWorkerState.RUNNING

                                    // A callback without an event is complete at worker-ready.
                                    // Event callbacks complete only after their own event is delivered.
                                    dispatch(id)
                                    completeWorkerReady(id, true)
                                } else {
                                    val decision = lifecyclePolicy.handleRuntimeFailure(
                                        extensionId = id,
                                        generation = generation,
                                        reason = error ?: "worker startup failed"
                                    )
                                    if (decision.terminal || !decision.retryScheduled) {
                                        failAll(id)
                                    }
                                }
                            } catch (t: Throwable) {
                                Log.e(TAG, "Error in startWorker callback for $id", t)
                                runCatching {
                                    serviceWorkerRegistry.transitionState(
                                        id,
                                        ServiceWorkerState.FAILED
                                    )
                                }
                                failAll(id)
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Error starting worker for $id", t)
                main.post {
                    val decision = lifecyclePolicy.handleRuntimeFailure(
                        extensionId = id,
                        generation = generation,
                        reason = t.message ?: "worker startup exception"
                    )
                    if (decision.terminal || !decision.retryScheduled) {
                        failAll(id)
                    }
                }
            }
        }
    }

    private fun selectBackend(ext: ParsedExtension): ServiceWorkerExecutionBackend {
        val currentGen = serviceWorkerRegistry.getWorkerGeneration(ext.id)
        Log.i("CRASH_BOUNDARY", "[CRASH_BOUNDARY] Stage: backend selection | Status: START | Extension: ${ext.id} | Generation: $currentGen | ManifestVersion: ${ext.manifestVersion}")
        val backend = if (ext.manifestVersion >= 3) {
            checkNotNull(backendSelector) {
                "MV3 extension ${ext.id} requires the Native V8 backend selector"
            }
            backendSelector.selectBackendForExtension(ext)
        } else {
            backendSelector?.selectLegacyBackendForMv2(ext) ?: executionBackend
        }
        Log.i("CRASH_BOUNDARY", "[CRASH_BOUNDARY] Stage: backend selection | Status: SUCCESS | Extension: ${ext.id} | Generation: $currentGen | Backend: ${backend.javaClass.simpleName}")
        return backend
    }

    /**
     * Single queue owner. Only this method mutates event delivery/removal.
     */
    private fun dispatch(id: String) {
        try {
            val worker = serviceWorkerRegistry.getWorker(id) ?: return
            val ext = extensionRegistry.getExtension(id) ?: return
            val backend = selectBackend(ext)
            val generation = worker.workerGenerationId
            val event = worker.pendingEvents.firstOrNull() ?: run {
                worker.lastActiveTimestamp = System.currentTimeMillis()
                return
            }

            if (event.deliveryState == WorkerEventDeliveryState.DELIVERING) return

            if (event.generationId != 0 && event.generationId != generation) {
                event.generationId = generation
            }

            event.deliveryState = WorkerEventDeliveryState.DELIVERING
            event.deliveryAttempts += 1
            serviceWorkerRegistry.persistLifecycleEvent(event)

            val keepaliveHandle = "event:${event.eventId}"
            worker.addKeepalive(
                event.keepaliveRequirement ?: "EVENT",
                keepaliveHandle,
                eventId = event.eventId
            )

            Log.i("CRASH_BOUNDARY", "[CRASH_BOUNDARY] Stage: first lifecycle event dispatch | Status: START | Extension: $id | Generation: $generation | Event: ${event.eventName} | EventId: ${event.eventId}")
            backend.evaluateEvent(
                extensionId = id,
                generationId = generation,
                eventName = event.eventName,
                payload = event.payload
            ) { ok ->
                Log.i("CRASH_BOUNDARY", "[CRASH_BOUNDARY] Stage: first lifecycle event dispatch | Status: ${if (ok) "SUCCESS" else "ERROR"} | Extension: $id | Generation: $generation | Event: ${event.eventName} | Success: $ok")
                main.post {
                    try {
                        worker.removeKeepalive(keepaliveHandle)

                        if (ok) {
                            event.deliveryState = WorkerEventDeliveryState.DELIVERED
                            serviceWorkerRegistry.markEventDelivered(id, event.eventId)
                            completeEvent(id, event.eventId, true)
                        } else {
                            val crashedOrRestarting = serviceWorkerRegistry.getState(id).isCrashOrRestarting()

                            if (crashedOrRestarting) {
                                // Native V8 crash handling owns restart/backoff. Keep this event
                                // queued so the next genuine worker generation can replay it.
                                event.deliveryState = WorkerEventDeliveryState.QUEUED
                                serviceWorkerRegistry.persistLifecycleEvent(event)
                            } else if (
                                event.deliveryAttempts >= MAX_EVENT_DELIVERY_ATTEMPTS
                            ) {
                                event.deliveryState = WorkerEventDeliveryState.EXPIRED
                                // markEventDelivered also removes the durable lifecycle record.
                                serviceWorkerRegistry.markEventDelivered(id, event.eventId)
                                completeEvent(id, event.eventId, false)
                            } else {
                                event.deliveryState = WorkerEventDeliveryState.QUEUED
                                serviceWorkerRegistry.persistLifecycleEvent(event)
                            }
                        }

                        // Never immediately retry against a worker that the crash handler has
                        // already marked dead. The next worker generation will consume the queue.
                        if (
                            serviceWorkerRegistry.getWorkerGeneration(id) == generation &&
                            !serviceWorkerRegistry.getState(id).isCrashOrRestarting()
                        ) {
                            dispatch(id)
                        }
                    } catch (t: Throwable) {
                        Log.e(
                            TAG,
                            "Error in evaluateEvent completion callback for $id",
                            t
                        )
                        completeEvent(id, event.eventId, false)
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Error in dispatch for $id", t)
        }
    }

    fun flushPendingEvents(id: String): List<QueuedServiceWorkerEvent> =
        serviceWorkerRegistry.getWorker(id)?.let { ArrayList(it.pendingEvents) }
            ?: emptyList()

    fun shutdown() {
        scope.cancel()
        flights.keys.toList().forEach { failAll(it) }
        locks.clear()
    }

    companion object {
        private const val TAG = "ServiceWorkerWakeController"
        private const val MAX_EVENT_DELIVERY_ATTEMPTS = 3
    }
}
