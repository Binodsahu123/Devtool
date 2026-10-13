package com.swift.browser.extensionengine

import android.content.Context
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Browser-process source of truth for MV3 worker registrations, generations and queued events.
 *
 * Registration metadata is deliberately separate from worker execution. This registry never
 * loads WebView/V8 itself; it only records state that the lifecycle and execution coordinators
 * consume.
 */
class ServiceWorkerRegistry(
    private var context: Context? = null,
    private var extensionRegistry: ExtensionRegistry? = null
) {
    /** Compatibility constructor retained for older call sites that supplied a legacy policy. */
    constructor(context: Context?, extensionRegistry: ExtensionRegistry?, @Suppress("UNUSED_PARAMETER") legacy: Any?) :
        this(context, extensionRegistry)

    private val workers = ConcurrentHashMap<String, ServiceWorkerRegistration>()
    private val generations = ConcurrentHashMap<String, AtomicInteger>()
    @Volatile
    private var lifecycleEventStore: ServiceWorkerLifecycleEventStore? = null

    private val requestTimeouts = ScheduledThreadPoolExecutor(1) { runnable ->
        Thread(runnable, "sw-request-timeouts").apply { isDaemon = true }
    }

    @Volatile
    var onRequestTerminated: ((ServiceWorkerRequestRecord, String) -> Unit)? = null

    fun setContextAndRegistry(context: Context, extensionRegistry: ExtensionRegistry) {
        this.context = context
        this.extensionRegistry = extensionRegistry
    }

    /** Installs the single durable MV3 lifecycle-event owner. */
    fun setLifecycleEventStore(store: ServiceWorkerLifecycleEventStore?) {
        lifecycleEventStore = store
    }

    @Synchronized
    fun restorePersistedLifecycleEvents(id: String): Int {
        val worker = workers[id] ?: return 0
        val persisted = lifecycleEventStore?.pending(id).orEmpty()
        var restored = 0
        persisted.forEach { event ->
            if (worker.pendingEvents.none { it.eventId == event.eventId }) {
                event.extensionId = id
                event.deliveryState = WorkerEventDeliveryState.QUEUED
                worker.pendingEvents.add(event)
                restored++
            }
        }
        return restored
    }

    /** Compatibility overload retained without creating another lifecycle owner. */
    fun setContextAndRegistry(
        context: Context,
        extensionRegistry: ExtensionRegistry,
        @Suppress("UNUSED_PARAMETER") legacy: Any?
    ) {
        setContextAndRegistry(context, extensionRegistry)
    }

    @Synchronized
    fun register(
        id: String,
        path: String,
        isMV3: Boolean = true
    ): ServiceWorkerRegistration {
        require(path.isNotBlank()) { "SERVICE_WORKER_REGISTRATION_FAILED" }
        require(PathSanitizer.isSafePath(path)) { "SERVICE_WORKER_REGISTRATION_FAILED" }

        val existing = workers[id]
        if (
            existing != null &&
            existing.scriptPath == path &&
            existing.enabled &&
            existing.state != ServiceWorkerState.UNINSTALLED
        ) {
            return existing
        }

        val generation = generations
            .computeIfAbsent(id) { AtomicInteger(0) }
            .incrementAndGet()

        val extension = extensionRegistry?.getExtension(id)
        val registration = ServiceWorkerRegistration(
            extensionId = id,
            scriptPath = path,
            isMV3 = isMV3,
            type = extension?.backgroundSpec?.type?.ifBlank { "classic" } ?: "classic",
            enabled = extensionRegistry?.isExtensionEnabled(id) ?: true,
            privateAllowed = extension?.allowedInPrivate ?: false,
            workerGenerationId = generation
        )
        workers[id] = registration
        return registration
    }

    @Synchronized
    fun unregister(id: String): ServiceWorkerRegistration? {
        workers[id]?.pendingRequests?.values?.forEach { request ->
            onRequestTerminated?.invoke(request, "EXTENSION_UNINSTALLED")
        }
        workers[id]?.terminateRuntime("WORKER_STOPPED")
        lifecycleEventStore?.removeExtension(id)
        return workers.remove(id)
    }

    fun getWorker(id: String): ServiceWorkerRegistration? = workers[id]

    fun getWorkerGeneration(id: String): Int =
        workers[id]?.workerGenerationId ?: 0

    fun getState(id: String): ServiceWorkerState =
        workers[id]?.state ?: ServiceWorkerState.UNREGISTERED

    @Synchronized
    fun incrementWorkerGeneration(id: String): Int {
        val worker = workers[id] ?: return 0
        val next = generations
            .computeIfAbsent(id) { AtomicInteger(worker.workerGenerationId) }
            .incrementAndGet()
        worker.workerGenerationId = next
        return next
    }

    @Synchronized
    fun transitionState(id: String, target: ServiceWorkerState): Boolean {
        val worker = workers[id] ?: return false
        if (!worker.state.canTransitionTo(target)) return false

        worker.state = target
        when {
            target.isRunningLike() -> worker.lastActiveTimestamp = System.currentTimeMillis()
            target.isStoppedLike() -> worker.lastStopAt = System.currentTimeMillis()
        }
        worker.executionContext?.state = target
        return true
    }

    @Synchronized
    fun enqueueEvent(
        id: String,
        event: QueuedServiceWorkerEvent,
        maxQueueSize: Int = DEFAULT_MAX_EVENT_QUEUE
    ): Boolean {
        val worker = workers[id] ?: return false
        if (!worker.enabled || worker.state.isTerminalLifecycleState()) return false
        if (worker.pendingEvents.any { it.eventId == event.eventId }) return true
        if (worker.pendingEvents.size >= maxQueueSize) return false

        event.extensionId = id
        event.deliveryState = WorkerEventDeliveryState.QUEUED
        if (event.sourceContext == "lifecycle") {
            val persisted = lifecycleEventStore?.put(event)
            if (persisted == false) return false
        }
        worker.pendingEvents.add(event)
        return true
    }

    @Synchronized
    fun addTimedKeepalive(
        id: String,
        reasonType: String,
        handle: String,
        timeoutMs: Long,
        owner: String = "service-worker",
        requestId: String? = null,
        eventId: String? = null
    ): Boolean {
        val worker = workers[id] ?: return false
        if (!worker.enabled || worker.state == ServiceWorkerState.UNINSTALLED) return false
        worker.addKeepalive(reasonType, handle, owner, requestId, eventId)
        val bounded = timeoutMs.coerceIn(1_000L, 120_000L)
        requestTimeouts.schedule({
            val current = workers[id] ?: return@schedule
            val keepalive = current.keepaliveReasons[handle] ?: return@schedule
            if (keepalive.timestamp + bounded <= System.currentTimeMillis()) {
                current.removeKeepalive(handle)
            }
        }, bounded, TimeUnit.MILLISECONDS)
        return true
    }

    @Synchronized
    fun registerRequest(
        id: String,
        requestId: String,
        generation: Int,
        timeoutMs: Long,
        owner: String
    ): Boolean {
        val worker = workers[id] ?: return false
        if (
            worker.workerGenerationId != generation ||
            !worker.enabled ||
            worker.state == ServiceWorkerState.UNINSTALLED
        ) {
            return false
        }

        val record = ServiceWorkerRequestRecord(
            requestId = requestId,
            extensionId = id,
            workerGeneration = generation,
            timeoutAt = System.currentTimeMillis() + timeoutMs,
            owner = owner
        )
        worker.pendingRequests[requestId] = record
        worker.addKeepalive(
            t = "API_REQUEST",
            h = requestId,
            owner = owner,
            requestId = requestId
        )

        requestTimeouts.schedule({
            val pending = workers[id]?.pendingRequests?.get(requestId)
            if (pending != null && pending.state == ServiceWorkerRequestState.PENDING) {
                pending.state = ServiceWorkerRequestState.EXPIRED
                workers[id]?.pendingRequests?.remove(requestId)
                workers[id]?.removeKeepalive(requestId)
                onRequestTerminated?.invoke(pending, "REQUEST_TIMEOUT")
            }
        }, timeoutMs.coerceAtLeast(1L), TimeUnit.MILLISECONDS)

        return true
    }

    @Synchronized
    fun completeRequest(id: String, requestId: String, ok: Boolean = true) {
        val worker = workers[id] ?: return
        val request = worker.pendingRequests.remove(requestId) ?: return
        request.state = if (ok) {
            ServiceWorkerRequestState.COMPLETED
        } else {
            ServiceWorkerRequestState.FAILED
        }
        worker.removeKeepalive(requestId)
    }

    @Synchronized
    fun terminatePendingRequests(
        id: String,
        reason: String,
        generation: Int? = null
    ) {
        val worker = workers[id] ?: return
        val doomed = worker.pendingRequests.values.filter {
            generation == null || it.workerGeneration == generation
        }

        doomed.forEach { request ->
            request.state = ServiceWorkerRequestState.FAILED
            worker.pendingRequests.remove(request.requestId)
            worker.removeKeepalive(request.requestId)
            onRequestTerminated?.invoke(request, reason)
        }

        if (generation == null) {
            worker.activeCallbacks.clear()
            worker.keepaliveReasons.clear()
        }
    }

    fun getAllWorkers(): List<ServiceWorkerRegistration> = workers.values.toList()

    @Synchronized
    fun persistLifecycleEvent(event: QueuedServiceWorkerEvent): Boolean {
        if (event.sourceContext != "lifecycle") return true
        return lifecycleEventStore?.update(event) ?: true
    }

    @Synchronized
    fun markEventDelivered(id: String, eventId: String): Boolean {
        val worker = workers[id] ?: return false
        val removed = worker.pendingEvents.removeIf { it.eventId == eventId }
        if (removed) {
            worker.lastEventAt = System.currentTimeMillis()
            lifecycleEventStore?.remove(id, eventId)
        }
        return removed
    }

    fun isRegistered(id: String): Boolean = workers.containsKey(id)

    @Synchronized
    fun clear() {
        workers.values.forEach { worker ->
            terminatePendingRequests(worker.extensionId, "WORKER_STOPPED")
            worker.terminateRuntime("WORKER_STOPPED")
        }
        workers.clear()
        generations.clear()
        requestTimeouts.shutdownNow()
    }

    companion object {
        private const val DEFAULT_MAX_EVENT_QUEUE = 100
    }
}
