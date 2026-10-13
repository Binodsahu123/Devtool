package com.swift.browser.extensionv8

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Coordinates Native V8 MV3 workers.
 *
 * Production execution has a single backend: V8NativeBridge -> C++ V8IsolateManager.
 * There is intentionally no JVM/WebView worker fallback here; MV3 must fail closed when
 * the native runtime cannot be loaded instead of silently switching engines.
 */
class V8WorkerManager(
    private val context: Context,
    private val config: V8RuntimeConfig = V8RuntimeConfig()
) {
    companion object { private const val TAG = "V8WorkerManager" }

    private val activeNativeGenerations = ConcurrentHashMap<String, Int>()
    private val startingGenerations = ConcurrentHashMap<String, Int>()
    private val nativeTimerHandles = ConcurrentHashMap<String, MutableMap<Int, Int>>()
    private val pendingStartCallbacks = ConcurrentHashMap<String, MutableList<(Boolean, String?) -> Unit>>()
    private val taskScheduler = V8TaskScheduler()

    var onHostApiCall: ((String, Int, String, String, String) -> Unit)? = null
    var onWorkerCrash: ((String, String) -> Unit)? = null
    var onConsoleLog: ((String, Int, String, String) -> Unit)? = null

    init {
        V8RuntimeBridge.setHostApiHandler(object : V8RuntimeBridge.HostApiHandler {
            override fun onHostApiCall(extensionId: String, generationId: Int, apiName: String, requestId: String, payloadJson: String) {
                onHostApiCall?.invoke(extensionId, generationId, apiName, requestId, payloadJson)
            }
        })
        V8RuntimeBridge.setWorkerEventListener(object : V8RuntimeBridge.WorkerEventListener {
            override fun onWorkerStarted(extensionId: String, generationId: Int, success: Boolean) {
                // Final worker-start diagnostics are recorded by this manager only after both
                // bootstrap and module/script execution have succeeded. Do not double count here.
            }
            override fun onWorkerError(extensionId: String, generationId: Int, error: String) {
                Log.e(TAG, "Worker error: ext=$extensionId gen=$generationId error=$error")
                V8RuntimeDiagnostics.recordError(extensionId, error)
                // Ignore stale callbacks after a worker has already been stopped/replaced.
                // During startup the startup task itself owns rollback/crash notification,
                // so native lifecycle callbacks never produce duplicate failure handling.
                val activeGeneration = activeNativeGenerations[extensionId]
                if (activeGeneration == generationId && startingGenerations[extensionId] != generationId) {
                    onWorkerCrash?.invoke(extensionId, error)
                }
            }
            override fun onEventHandled(extensionId: String, generationId: Int, eventName: String, success: Boolean) {
                V8RuntimeDiagnostics.recordEventDispatched(extensionId)
            }
            override fun onConsoleLog(extensionId: String, generationId: Int, level: String, message: String) {
                if (config.logVerbose) Log.d(TAG, "[$level] [$extensionId] $message")
                this@V8WorkerManager.onConsoleLog?.invoke(extensionId, generationId, level, message)
            }
        })
    }

    private fun startKey(extensionId: String, generationId: Int): String = "$extensionId#$generationId"

    private fun addStartCallback(extensionId: String, generationId: Int, callback: (Boolean, String?) -> Unit) {
        val key = startKey(extensionId, generationId)
        synchronized(pendingStartCallbacks) {
            pendingStartCallbacks.getOrPut(key) { mutableListOf() }.add(callback)
        }
    }

    private fun completeStart(extensionId: String, generationId: Int, success: Boolean, error: String?) {
        val key = startKey(extensionId, generationId)
        val callbacks = synchronized(pendingStartCallbacks) {
            pendingStartCallbacks.remove(key)?.toList() ?: emptyList()
        }
        callbacks.forEach { callback ->
            try { callback(success, error) } catch (t: Throwable) {
                Log.w(TAG, "Worker startup callback failed for $extensionId gen=$generationId", t)
            }
        }
    }

    fun startWorker(
        extensionId: String,
        scriptPath: String,
        generationId: Int,
        bootstrapScript: String,
        scriptContent: String,
        isModule: Boolean = false,
        isPrivate: Boolean = false,
        moduleSourcesBlob: String = "",
        extensionRootPath: String = "",
        onStartupResult: (Boolean, String?) -> Unit
    ) {
        if (extensionId.isBlank() || generationId <= 0) {
            onStartupResult(false, "Invalid worker identity: ext=$extensionId gen=$generationId")
            return
        }
        if (!V8NativeBridge.isAvailable) {
            onStartupResult(false, "Native V8 bridge is not ready: state=${V8NativeBridge.getV8State()}")
            return
        }
        val existingGeneration = activeNativeGenerations[extensionId]
        if (existingGeneration == generationId) {
            if (startingGenerations[extensionId] == generationId) {
                addStartCallback(extensionId, generationId, onStartupResult)
            } else {
                onStartupResult(true, null)
            }
            return
        }
        addStartCallback(extensionId, generationId, onStartupResult)
        if (existingGeneration != null && existingGeneration != generationId) {
            activeNativeGenerations.remove(extensionId, existingGeneration)
            nativeTimerHandles.remove(extensionId)
            taskScheduler.postTask(extensionId) {
                try { V8NativeBridge.stopWorker(extensionId) } catch (_: Throwable) {}
            }
        }
        // Native V8 readiness is established exactly once by NativeV8RuntimeCoordinator
        // through V8ServiceWorkerBackend before this execution primitive is entered.
        // Publish generation before createWorker so synchronous chrome.* calls during bootstrap
        // can route back to Kotlin while the isolate is initializing.
        activeNativeGenerations[extensionId] = generationId
        startingGenerations[extensionId] = generationId
        taskScheduler.postTask(extensionId) {
            try {
                if (!V8NativeBridge.isAvailable) {
                    throw IllegalStateException("Native V8 bridge is not ready: state=${V8NativeBridge.getV8State()}")
                }
                val created = V8NativeBridge.createWorker(
                    extensionId, generationId, scriptPath, bootstrapScript, isModule, moduleSourcesBlob,
                    extensionRootPath, config.maxHeapMemoryMb
                )
                if (!created) throw IllegalStateException("Native V8 worker creation failed")
                if (!isModule && scriptContent.isNotBlank()) {
                    Log.i("CRASH_BOUNDARY", "[CRASH_BOUNDARY] Stage: worker script compilation/evaluation | Status: START | Extension: $extensionId | Generation: $generationId | Bytes: ${scriptContent.length}")
                    val scriptOk = V8NativeBridge.executeScript(extensionId, generationId, scriptContent)
                    if (!scriptOk) {
                        Log.e("CRASH_BOUNDARY", "[CRASH_BOUNDARY] Stage: worker script compilation/evaluation | Status: ERROR | Extension: $extensionId | Generation: $generationId | Reason: executeScript returned false")
                        throw IllegalStateException("Native V8 worker script execution failed")
                    }
                    Log.i("CRASH_BOUNDARY", "[CRASH_BOUNDARY] Stage: worker script compilation/evaluation | Status: SUCCESS | Extension: $extensionId | Generation: $generationId")
                }
                if (activeNativeGenerations[extensionId] != generationId) {
                    startingGenerations.remove(extensionId, generationId)
                    nativeTimerHandles.remove(extensionId)
                    runCatching { V8NativeBridge.stopWorker(extensionId) }
                    completeStart(extensionId, generationId, false, "Worker generation superseded during startup")
                    return@postTask
                }
                startingGenerations.remove(extensionId, generationId)
                V8RuntimeDiagnostics.recordWorkerStarted(extensionId)
                completeStart(extensionId, generationId, true, null)
            } catch (t: Throwable) {
                startingGenerations.remove(extensionId, generationId)
                activeNativeGenerations.remove(extensionId, generationId)
                nativeTimerHandles.remove(extensionId)
                try { V8NativeBridge.stopWorker(extensionId) } catch (_: Throwable) {}
                V8RuntimeDiagnostics.recordError(extensionId, t.message ?: "Native V8 startup failure")
                Log.e(TAG, "Native V8 worker startup failed for $extensionId", t)
                // Do not invoke onWorkerCrash here: the startup callback below is the single
                // lifecycle owner for a failed start and will enter crash/backoff exactly once.
                completeStart(extensionId, generationId, false, t.message)
            }
        }
    }

    fun evaluateEvent(
        extensionId: String,
        generationId: Int,
        eventName: String,
        payload: Any?,
        callback: ((Boolean) -> Unit)? = null
    ) {
        if (extensionId.isBlank() || generationId <= 0 || !V8NativeBridge.isAvailable) {
            callback?.invoke(false)
            return
        }
        if (activeNativeGenerations[extensionId] != generationId) {
            callback?.invoke(false)
            return
        }
        val payloadJson = when (payload) {
            is JSONObject -> payload.toString()
            is String -> if (payload.startsWith("{") || payload.startsWith("[")) payload else JSONObject.quote(payload)
            null -> "null"
            else -> JSONObject.wrap(payload)?.toString() ?: payload.toString()
        }
        taskScheduler.postTask(extensionId) {
            if (activeNativeGenerations[extensionId] != generationId || !V8NativeBridge.isAvailable) {
                callback?.invoke(false)
                return@postTask
            }
            try {
                val ok = V8NativeBridge.dispatchEvent(extensionId, generationId, eventName, payloadJson)
                if (!ok) {
                    // Publish the crash before the delivery callback so the browser-process
                    // queue observes CRASHED/RESTART_PENDING and retains the event for the next
                    // native generation instead of immediately retrying a dead isolate.
                    onWorkerCrash?.invoke(extensionId, "Native V8 event dispatch failed: $eventName")
                }
                callback?.invoke(ok)
            } catch (t: Throwable) {
                onWorkerCrash?.invoke(extensionId, t.message ?: "Native V8 event error")
                callback?.invoke(false)
            }
        }
    }

    fun scheduleNativeTimer(extensionId: String, generationId: Int, isInterval: Boolean, delayMs: Long, timerId: Int): Boolean {
        if (extensionId.isBlank() || generationId <= 0 || !V8NativeBridge.isAvailable) return false
        if (activeNativeGenerations[extensionId] != generationId) return false
        val handles = nativeTimerHandles.getOrPut(extensionId) { ConcurrentHashMap() }
        handles[timerId]?.let { taskScheduler.clearInterval(extensionId, it) }
        val handle = if (isInterval) {
            taskScheduler.scheduleInterval(extensionId, delayMs) {
                taskScheduler.postTask(extensionId) {
                    if (activeNativeGenerations[extensionId] == generationId && V8NativeBridge.isAvailable) {
                        val ok = runCatching { V8NativeBridge.fireTimer(extensionId, generationId, timerId) }.getOrDefault(false)
                        if (!ok) onWorkerCrash?.invoke(extensionId, "Native V8 timer dispatch failed: $timerId")
                    }
                }
            }
        } else {
            taskScheduler.scheduleTimeout(extensionId, delayMs) {
                handles.remove(timerId)
                taskScheduler.postTask(extensionId) {
                    if (activeNativeGenerations[extensionId] == generationId && V8NativeBridge.isAvailable) {
                        val ok = runCatching { V8NativeBridge.fireTimer(extensionId, generationId, timerId) }.getOrDefault(false)
                        if (!ok) onWorkerCrash?.invoke(extensionId, "Native V8 timer dispatch failed: $timerId")
                    }
                }
            }
        }
        handles[timerId] = handle
        return true
    }

    fun clearNativeTimer(extensionId: String, generationId: Int, timerId: Int): Boolean {
        if (extensionId.isBlank() || generationId <= 0) return false
        if (activeNativeGenerations[extensionId] != generationId) return false
        nativeTimerHandles[extensionId]?.remove(timerId)?.let { taskScheduler.clearInterval(extensionId, it) }
        return true
    }

    fun resolveHostCall(
        extensionId: String,
        generationId: Int,
        requestId: String,
        resultJson: String,
        errorMsg: String,
        onDelivered: ((Boolean) -> Unit)? = null
    ) {
        if (extensionId.isBlank() || generationId <= 0 || !V8NativeBridge.isAvailable) {
            onDelivered?.invoke(false)
            return
        }
        if (activeNativeGenerations[extensionId] != generationId) {
            onDelivered?.invoke(false)
            return
        }
        taskScheduler.postTask(extensionId) {
            if (activeNativeGenerations[extensionId] != generationId || !V8NativeBridge.isAvailable) {
                onDelivered?.invoke(false)
                return@postTask
            }
            var delivered = false
            try {
                delivered = V8NativeBridge.resolveHostApiCall(
                    extensionId, generationId, requestId, resultJson, errorMsg
                )
            } catch (t: Throwable) {
                Log.w(TAG, "Host response failed for $extensionId/$requestId", t)
            } finally {
                onDelivered?.invoke(delivered)
            }
        }
    }

    fun stopWorker(extensionId: String) {
        if (extensionId.isBlank()) return
        val generation = activeNativeGenerations.remove(extensionId) ?: return
        startingGenerations.remove(extensionId, generation)
        nativeTimerHandles.remove(extensionId)
        taskScheduler.postTask(extensionId) {
            if (activeNativeGenerations[extensionId] == null) {
                if (V8NativeBridge.isAvailable) {
                    try { V8NativeBridge.stopWorker(extensionId) } catch (_: Throwable) {}
                }
                taskScheduler.stopWorkerScheduler(extensionId)
                V8RuntimeDiagnostics.recordWorkerStopped(extensionId)
            }
        }
    }

    fun stopAll() {
        // Process/app shutdown must not enqueue a stop task and then immediately destroy the
        // scheduler: that used to leave native isolates alive or leave queued V8 calls behind.
        // Remove Java-side ownership first, synchronously stop each native isolate (C++ uses
        // its own V8 Locker), then tear down all HandlerThreads/timers.
        val ids = activeNativeGenerations.keys.toList()
        for (extensionId in ids) {
            val generation = activeNativeGenerations.remove(extensionId)
            startingGenerations.remove(extensionId)
            nativeTimerHandles.remove(extensionId)
            if (generation != null) {
                if (V8NativeBridge.isAvailable) {
                    runCatching { V8NativeBridge.stopWorker(extensionId) }
                        .onFailure { Log.w(TAG, "Native V8 stopAll failed for $extensionId", it) }
                }
                V8RuntimeDiagnostics.recordWorkerStopped(extensionId)
            }
        }
        taskScheduler.stopAll()
    }

    fun isWorkerRunning(extensionId: String): Boolean = activeNativeGenerations.containsKey(extensionId)

    fun getWorkerGeneration(extensionId: String): Int = activeNativeGenerations[extensionId] ?: 0

    fun getHeapUsage(extensionId: String): Long {
        if (extensionId.isBlank() || !V8NativeBridge.isAvailable || !activeNativeGenerations.containsKey(extensionId)) return 0L
        return runCatching { V8NativeBridge.getHeapUsage(extensionId) }.getOrDefault(0L)
    }

    fun isV8Available(): Boolean = V8NativeBridge.isV8Initialized()
}
