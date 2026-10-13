package com.swift.browser.extensionv8

import android.util.Log
import org.json.JSONObject

enum class V8WorkerState {
    CREATED,
    INITIALIZING,
    RUNNING,
    FAILED,
    STOPPED
}

data class V8WorkerExceptionInfo(
    val message: String,
    val stackTrace: String,
    val sourceName: String,
    val lineNumber: Int,
    val startColumn: Int,
    val endColumn: Int
) {
    fun toFormattedString(): String = buildString {
        append(message)
        if (sourceName.isNotBlank() || lineNumber > 0) {
            append(" at ").append(if (sourceName.isNotBlank()) sourceName else "unknown")
            if (lineNumber > 0) append(":").append(lineNumber)
            if (startColumn > 0) append(":").append(startColumn)
        }
        if (stackTrace.isNotBlank()) {
            append("\n").append(stackTrace)
        }
    }
}

class V8Worker(
    val extensionId: String,
    val generationId: Int,
    val scriptPath: String,
    val isModule: Boolean = false,
    val isPrivate: Boolean = false,
    val startTime: Long = System.currentTimeMillis(),
    val extensionRootPath: String = "",
    val maxHeapMemoryMb: Int = 128
) {
    companion object {
        private const val TAG = "V8Worker"

        fun buildDefaultBootstrapScript(extensionId: String, manifestJson: String = "{}"): String {
            return V8WorkerBootstrap.buildDefaultBootstrapScript(extensionId, manifestJson)
        }
    }

    var state: V8WorkerState = V8WorkerState.CREATED
    var lastException: V8WorkerExceptionInfo? = null
        private set
    var heapUsageBytes: Long = 0L

    val pendingEvents = mutableListOf<Triple<String, Any?, ((Boolean) -> Unit)?>>()

    fun addPendingEvent(eventName: String, payload: Any?, callback: ((Boolean) -> Unit)?) {
        synchronized(pendingEvents) {
            pendingEvents.add(Triple(eventName, payload, callback))
        }
    }

    fun drainPendingEvents(): List<Triple<String, Any?, ((Boolean) -> Unit)?>> {
        synchronized(pendingEvents) {
            val copy = ArrayList(pendingEvents)
            pendingEvents.clear()
            return copy
        }
    }

    val isInitialized: Boolean
        get() = state == V8WorkerState.RUNNING

    fun initialize(
        bootstrapScript: String,
        hostApiHandler: (apiName: String, requestId: String, payloadJson: String) -> Unit,
        consoleHandler: (level: String, message: String) -> Unit,
        timerSetHandler: (isInterval: Boolean, delayMs: Long, timerId: Int) -> Unit,
        timerClearHandler: (timerId: Int) -> Unit
    ): Boolean {
        if (extensionId.isBlank() || generationId <= 0 || !V8NativeBridge.isAvailable) {
            state = V8WorkerState.FAILED
            return false
        }
        state = V8WorkerState.INITIALIZING
        return try {
            val baseBootstrap = buildDefaultBootstrapScript(extensionId)
            val fullBootstrap = if (bootstrapScript.isNotBlank() && bootstrapScript != baseBootstrap) {
                baseBootstrap + "\n" + bootstrapScript
            } else {
                baseBootstrap
            }

            val ok = V8NativeBridge.createWorker(
                extensionId = extensionId,
                generationId = generationId,
                scriptPath = scriptPath,
                bootstrapScript = fullBootstrap,
                isModule = isModule,
                moduleSourcesBlob = "",
                extensionRootPath = extensionRootPath,
                maxHeapMemoryMb = maxHeapMemoryMb
            )

            if (ok) {
                state = V8WorkerState.RUNNING
                heapUsageBytes = estimateHeapUsage()
                true
            } else {
                state = V8WorkerState.FAILED
                false
            }
        } catch (e: Throwable) {
            captureException(e, "Bootstrap initialization")
            state = V8WorkerState.FAILED
            false
        }
    }

    fun executeScript(scriptContent: String, scriptName: String = scriptPath): Pair<Boolean, Any?> {
        if (state != V8WorkerState.RUNNING || !V8NativeBridge.isAvailable) {
            return Pair(false, "V8 runtime is not running (state=$state)")
        }

        return try {
            val wrappedScript = if (isModule) {
                wrapModuleScript(scriptContent)
            } else {
                scriptContent
            }
            val ok = V8NativeBridge.executeScript(extensionId, generationId, wrappedScript)
            drainMicrotasks()
            heapUsageBytes = estimateHeapUsage()
            Pair(ok, if (ok) null else "Native script execution returned false")
        } catch (e: Throwable) {
            val err = captureException(e, scriptName)
            state = V8WorkerState.FAILED
            Pair(false, err.toFormattedString())
        }
    }

    fun dispatchEvent(eventName: String, payloadJson: String): Boolean {
        if (state != V8WorkerState.RUNNING || !V8NativeBridge.isAvailable) {
            return false
        }

        return try {
            val ok = V8NativeBridge.dispatchEvent(extensionId, generationId, eventName, payloadJson)
            drainMicrotasks()
            ok
        } catch (e: Throwable) {
            captureException(e, "dispatchEvent($eventName)")
            false
        }
    }

    fun resolveHostCall(requestId: String, resultJson: String, errorMsg: String) {
        if (state != V8WorkerState.RUNNING || !V8NativeBridge.isAvailable) return

        try {
            V8NativeBridge.resolveHostApiCall(extensionId, generationId, requestId, resultJson, errorMsg)
            drainMicrotasks()
        } catch (e: Throwable) {
            captureException(e, "resolveHostCall($requestId)")
        }
    }

    fun fireTimer(timerId: Int) {
        if (state != V8WorkerState.RUNNING || !V8NativeBridge.isAvailable) return

        try {
            V8NativeBridge.fireTimer(extensionId, generationId, timerId)
            drainMicrotasks()
        } catch (e: Throwable) {
            captureException(e, "fireTimer($timerId)")
        }
    }

    fun drainMicrotasks() {
        // Native V8 handles microtasks during isolate execution
    }

    fun stop() {
        state = V8WorkerState.STOPPED
        try {
            if (V8NativeBridge.isAvailable) {
                V8NativeBridge.stopWorker(extensionId, generationId)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Error stopping native V8 worker for $extensionId: ${e.message}")
        }
    }

    private fun estimateHeapUsage(): Long {
        if (!V8NativeBridge.isAvailable) return (scriptPath.length * 2L) + 512 * 1024L
        return try {
            val usage = V8NativeBridge.getHeapUsage(extensionId, generationId)
            if (usage > 0L) usage else (scriptPath.length * 2L) + 512 * 1024L
        } catch (_: Throwable) {
            (scriptPath.length * 2L) + 512 * 1024L
        }
    }

    private fun captureException(e: Throwable, source: String): V8WorkerExceptionInfo {
        val info = V8WorkerExceptionInfo(
            message = e.message ?: "Unknown Native V8 Exception",
            stackTrace = e.stackTraceToString(),
            sourceName = source,
            lineNumber = 0,
            startColumn = 0,
            endColumn = 0
        )
        lastException = info
        Log.e(TAG, "V8 Worker Exception for $extensionId in $source: ${info.toFormattedString()}")
        return info
    }

    private fun wrapModuleScript(script: String): String {
        return """
            (function() {
                ${script.replace(Regex("""^\s*import\s+.*?from\s+['"].*?['"];?""", RegexOption.MULTILINE), "// [imported module]")}
            })();
        """.trimIndent()
    }
}
