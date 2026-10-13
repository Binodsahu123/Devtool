package com.swift.browser.extensionv8

import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Canonical owner for Native V8 readiness.
 *
 * No other class should load the native library directly. Loading is demand-driven
 * by an MV3 worker start and is serialized by V8NativeBridge.
 */
class NativeV8RuntimeCoordinator(
    private val runtime: V8RuntimeInterface
) {
    private val workerGenerations = ConcurrentHashMap<String, Int>()

    @Synchronized
    fun ensureWorkerRuntime(extensionId: String, generationId: Int): Result<Unit> {
        Log.i("CRASH_BOUNDARY", "[CRASH_BOUNDARY] Stage: ensureWorkerRuntime() | Status: START | Extension: $extensionId | Generation: $generationId")
        if (generationId <= 0 || extensionId.isBlank()) {
            val err = IllegalArgumentException("Invalid worker identity")
            Log.e("CRASH_BOUNDARY", "[CRASH_BOUNDARY] Stage: ensureWorkerRuntime() | Status: ERROR | Extension: $extensionId | Generation: $generationId | Reason: ${err.message}")
            return Result.failure(err)
        }

        return runCatching {
            // Native V8 readiness is established through one canonical bridge boundary.
            if (!runtime.ensureReadyForWorker()) {
                error(runtime.backendStatus())
            }
            val previous = workerGenerations[extensionId]
            if (previous != null && previous != generationId) {
                runtime.workerManager.stopWorker(extensionId)
            }
            workerGenerations[extensionId] = generationId
            Log.i("CRASH_BOUNDARY", "[CRASH_BOUNDARY] Stage: ensureWorkerRuntime() | Status: SUCCESS | Extension: $extensionId | Generation: $generationId")
            Unit
        }.onFailure {
            Log.e("CRASH_BOUNDARY", "[CRASH_BOUNDARY] Stage: ensureWorkerRuntime() | Status: ERROR | Extension: $extensionId | Generation: $generationId | Error: ${it.message}", it)
            Log.e(TAG, "Native V8 worker runtime unavailable for $extensionId/$generationId", it)
        }
    }

    @Synchronized
    fun onWorkerStopped(extensionId: String, generationId: Int) {
        if (workerGenerations[extensionId] == generationId) {
            workerGenerations.remove(extensionId)
        }
    }

    @Synchronized
    fun onWorkerCrashed(extensionId: String, generationId: Int) {
        if (workerGenerations[extensionId] == generationId) {
            workerGenerations.remove(extensionId)
        }
        runCatching { runtime.workerManager.stopWorker(extensionId) }
    }

    fun status(): String = runtime.backendStatus()

    companion object { private const val TAG = "NativeV8Coordinator" }
}
