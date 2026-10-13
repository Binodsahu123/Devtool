package com.swift.browser.extensionv8

import android.util.Log
import java.util.concurrent.atomic.AtomicLong

/**
 * Logging decorator for [V8RuntimeInterface] that intercepts initialization and worker readiness events,
 * tracking success vs failure counts, timing performance, and logging bridge state changes.
 */
class LoggingV8RuntimeDecorator(
    private val delegate: V8RuntimeInterface,
    private val tag: String = TAG
) : V8RuntimeInterface {

    override val workerManager: V8WorkerManager
        get() = delegate.workerManager

    override val config: V8RuntimeConfig
        get() = delegate.config

    private val initAttempts = AtomicLong(0)
    private val initSuccesses = AtomicLong(0)
    private val initFailures = AtomicLong(0)

    private val workerReadyAttempts = AtomicLong(0)
    private val workerReadySuccesses = AtomicLong(0)
    private val workerReadyFailures = AtomicLong(0)

    val totalInitAttempts: Long get() = initAttempts.get()
    val successfulInits: Long get() = initSuccesses.get()
    val failedInits: Long get() = initFailures.get()

    val totalWorkerReadyAttempts: Long get() = workerReadyAttempts.get()
    val successfulWorkerReadyChecks: Long get() = workerReadySuccesses.get()
    val failedWorkerReadyChecks: Long get() = workerReadyFailures.get()

    override fun ensureInitialized(): Boolean {
        val attemptNum = initAttempts.incrementAndGet()
        Log.d(tag, "V8 native bridge init attempt #$attemptNum starting...")
        val startTime = System.currentTimeMillis()

        val success = try {
            delegate.ensureInitialized()
        } catch (t: Throwable) {
            val duration = System.currentTimeMillis() - startTime
            initFailures.incrementAndGet()
            Log.e(
                tag,
                "V8 native bridge init attempt #$attemptNum THREW EXCEPTION after ${duration}ms: ${t.message}",
                t
            )
            V8RuntimeDiagnostics.recordError("v8_bridge_init", "Init exception: ${t.message}")
            return false
        }

        val duration = System.currentTimeMillis() - startTime
        val status = delegate.backendStatus()

        if (success) {
            initSuccesses.incrementAndGet()
            Log.i(
                tag,
                "V8 native bridge init attempt #$attemptNum SUCCESS in ${duration}ms [status=$status, v8Version=${delegate.getV8Version()}]"
            )
        } else {
            initFailures.incrementAndGet()
            Log.w(
                tag,
                "V8 native bridge init attempt #$attemptNum FAILED in ${duration}ms [status=$status]"
            )
            V8RuntimeDiagnostics.recordError("v8_bridge_init", "Init failed with status $status")
        }

        return success
    }

    override fun ensureReadyForWorker(): Boolean {
        val attemptNum = workerReadyAttempts.incrementAndGet()
        Log.d(tag, "V8 worker runtime readiness check #$attemptNum starting...")
        val startTime = System.currentTimeMillis()

        val success = try {
            delegate.ensureReadyForWorker()
        } catch (t: Throwable) {
            val duration = System.currentTimeMillis() - startTime
            workerReadyFailures.incrementAndGet()
            Log.e(
                tag,
                "V8 worker readiness check #$attemptNum THREW EXCEPTION after ${duration}ms: ${t.message}",
                t
            )
            V8RuntimeDiagnostics.recordError("v8_worker_readiness", "Readiness exception: ${t.message}")
            return false
        }

        val duration = System.currentTimeMillis() - startTime
        val status = delegate.backendStatus()

        if (success) {
            workerReadySuccesses.incrementAndGet()
            Log.i(
                tag,
                "V8 worker readiness check #$attemptNum SUCCESS in ${duration}ms [status=$status, totalSuccesses=${workerReadySuccesses.get()}]"
            )
        } else {
            workerReadyFailures.incrementAndGet()
            Log.w(
                tag,
                "V8 worker readiness check #$attemptNum FAILED in ${duration}ms [status=$status, totalFailures=${workerReadyFailures.get()}]"
            )
            V8RuntimeDiagnostics.recordError("v8_worker_readiness", "Readiness failed with status $status")
        }

        return success
    }

    override fun hasOrionNativeV8(): Boolean = delegate.hasOrionNativeV8()
    override fun isNativeV8Available(): Boolean = delegate.isNativeV8Available()
    override fun backendStatus(): String = delegate.backendStatus()
    override fun isV8Supported(): Boolean = delegate.isV8Supported()
    override fun isV8Ready(): Boolean = delegate.isV8Ready()
    override fun isV8BackendAvailable(): Boolean = delegate.isV8BackendAvailable()
    override fun getV8Version(): String = delegate.getV8Version()
    override fun getRuntimeVersion(): String = delegate.getRuntimeVersion()
    override fun getHeaderVersion(): String = delegate.getHeaderVersion()
    override fun getV8GitRevision(): String = delegate.getV8GitRevision()
    override fun getV8BuildInfo(): String = delegate.getV8BuildInfo()

    override fun shutdown() {
        Log.i(
            tag,
            "Shutting down V8 runtime [initSuccesses=${initSuccesses.get()}, initFailures=${initFailures.get()}, workerReadySuccesses=${workerReadySuccesses.get()}, workerReadyFailures=${workerReadyFailures.get()}]"
        )
        delegate.shutdown()
    }

    /**
     * Returns a map of stability metrics for native bridge initialization and readiness.
     */
    fun getStabilityMetrics(): Map<String, Any> = mapOf(
        "initAttempts" to initAttempts.get(),
        "initSuccesses" to initSuccesses.get(),
        "initFailures" to initFailures.get(),
        "workerReadyAttempts" to workerReadyAttempts.get(),
        "workerReadySuccesses" to workerReadySuccesses.get(),
        "workerReadyFailures" to workerReadyFailures.get(),
        "backendStatus" to backendStatus(),
        "isV8Supported" to isV8Supported(),
        "isV8Ready" to isV8Ready()
    )

    companion object {
        private const val TAG = "LoggingV8Runtime"
    }
}

/**
 * Extension function to wrap any [V8RuntimeInterface] with a [LoggingV8RuntimeDecorator].
 */
fun V8RuntimeInterface.withLogging(tag: String = "LoggingV8Runtime"): LoggingV8RuntimeDecorator {
    return if (this is LoggingV8RuntimeDecorator) this else LoggingV8RuntimeDecorator(this, tag)
}
