package com.swift.browser.extensionv8

import android.os.Build
import android.util.Log

enum class V8BridgeState {
    NOT_LOADED,
    LOADING,
    LOADED,
    ABI_INVALID,
    INITIALIZING,
    READY,
    FAILED
}

object V8NativeBridge {
    private const val TAG = "OrionV8"

    @Volatile
    private var state: V8BridgeState = V8BridgeState.NOT_LOADED
    @Volatile
    private var failureReason: String? = null
    @Volatile
    private var failureThrowable: Throwable? = null
    @Volatile
    private var librariesLoadedAttempted = false
    @Volatile
    private var fullV8Verified = false
    @Volatile
    private var cachedHeaderVersion: String? = null
    @Volatile
    private var cachedRuntimeVersion: String? = null
    @Volatile
    private var cachedV8Version: String? = null
    @Volatile
    private var cachedV8GitRevision: String? = null
    @Volatile
    private var cachedV8BuildInfo: String? = null

    private fun logD(tag: String, msg: String) {
        try { Log.d(tag, msg) } catch (_: Throwable) { println("[$tag] DEBUG: $msg") }
    }
    private fun logI(tag: String, msg: String) {
        try { Log.i(tag, msg) } catch (_: Throwable) { println("[$tag] INFO: $msg") }
    }
    private fun logW(tag: String, msg: String) {
        try { Log.w(tag, msg) } catch (_: Throwable) { println("[$tag] WARN: $msg") }
    }
    private fun logE(tag: String, msg: String, tr: Throwable? = null) {
        try { Log.e(tag, msg, tr) } catch (_: Throwable) { println("[$tag] ERROR: $msg ${tr?.message}") }
    }

    @Synchronized
    fun ensureNativeLoaded(): Boolean {
        if (librariesLoadedAttempted) {
            return state == V8BridgeState.LOADED || state == V8BridgeState.INITIALIZING || state == V8BridgeState.READY
        }
        librariesLoadedAttempted = true
        state = V8BridgeState.LOADING
        logI(TAG, "V8_LOAD_REQUEST: Initiating native V8 runtime library load")
        logI(TAG, "V8_LOAD_START: Loading native runtime dependencies")

        // 1. Validate ABI support
        val supportedAbis = Build.SUPPORTED_ABIS
        val validAbis = setOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
        val isAbiSupported = supportedAbis.any { validAbis.contains(it.lowercase()) }
        logI(TAG, "V8_ABI_CHECK: Primary=${Build.SUPPORTED_ABIS.firstOrNull()}, abiSupported=$isAbiSupported")
        if (!isAbiSupported) {
            state = V8BridgeState.ABI_INVALID
            val err = IllegalStateException("Unsupported device ABI: ${supportedAbis.joinToString()}. V8 requires arm64-v8a, armeabi-v7a, x86_64, or x86.")
            failureThrowable = err
            failureReason = err.message
            logE(TAG, "V8_ABI_INVALID: $failureReason")
            logW(TAG, "V8_LOAD_FAILED: $failureReason")
            return false
        }
        logI(TAG, "V8_ABI_VALID: Device supports valid V8 ABI (${supportedAbis.firstOrNull()})")

        // 2. Load c++_shared
        try {
            System.loadLibrary("c++_shared")
        } catch (e: Throwable) {
            logD(TAG, "libc++_shared note: ${e.message}")
        }

        // 3. Load NativeScript precompiled V8 runtime
        Log.i("CRASH_BOUNDARY", "[CRASH_BOUNDARY] Stage: NativeScript library load | Status: START | Extension: * | Generation: 0")
        try {
            System.loadLibrary("NativeScript")
            Log.i("CRASH_BOUNDARY", "[CRASH_BOUNDARY] Stage: NativeScript library load | Status: SUCCESS | Extension: * | Generation: 0")
            logI(TAG, "NATIVESCRIPT_LOAD_OK: libNativeScript.so loaded successfully")
        } catch (e: Throwable) {
            state = V8BridgeState.FAILED
            failureThrowable = e
            failureReason = "Failed to load libNativeScript.so: ${e.message}"
            Log.e("CRASH_BOUNDARY", "[CRASH_BOUNDARY] Stage: NativeScript library load | Status: ERROR | Extension: * | Generation: 0 | Error: ${e.message}", e)
            logE(TAG, "V8_LOAD_FAILED: $failureReason", e)
            return false
        }

        // 4. Load Orion V8 JNI Bridge
        Log.i("CRASH_BOUNDARY", "[CRASH_BOUNDARY] Stage: Orion JNI library load | Status: START | Extension: * | Generation: 0")
        try {
            System.loadLibrary("orion_extension_v8")
            Log.i("CRASH_BOUNDARY", "[CRASH_BOUNDARY] Stage: Orion JNI library load | Status: SUCCESS | Extension: * | Generation: 0")
            logI(TAG, "ORION_V8_LIBRARY_LOAD_OK: liborion_extension_v8.so loaded successfully")
        } catch (e: Throwable) {
            state = V8BridgeState.FAILED
            failureThrowable = e
            failureReason = "Failed to load liborion_extension_v8.so: ${e.message}"
            Log.e("CRASH_BOUNDARY", "[CRASH_BOUNDARY] Stage: Orion JNI library load | Status: ERROR | Extension: * | Generation: 0 | Error: ${e.message}", e)
            logE(TAG, "V8_LOAD_FAILED: $failureReason", e)
            return false
        }

        // 5. Verify Header vs Runtime versions
        Log.i("CRASH_BOUNDARY", "[CRASH_BOUNDARY] Stage: header/runtime version validation | Status: START | Extension: * | Generation: 0")
        val headerVer = try { nativeGetHeaderVersion() } catch (_: Throwable) { "UNKNOWN" }
        val runtimeVer = try { nativeGetRuntimeVersion() } catch (_: Throwable) { "UNKNOWN" }
        logI(TAG, "V8_HEADER_VERSION: $headerVer")
        logI(TAG, "V8_RUNTIME_VERSION: $runtimeVer")

        if (headerVer.isBlank() || headerVer == "UNKNOWN" || runtimeVer.isBlank() || runtimeVer == "UNKNOWN" || headerVer != runtimeVer) {
            state = V8BridgeState.FAILED
            val err = IllegalStateException("Native V8 version contract mismatch: header=$headerVer runtime=$runtimeVer")
            failureThrowable = err
            failureReason = err.message
            Log.e("CRASH_BOUNDARY", "[CRASH_BOUNDARY] Stage: header/runtime version validation | Status: ERROR | Extension: * | Generation: 0 | Error: ${err.message}", err)
            logE(TAG, "V8_LOAD_FAILED: $failureReason", err)
            return false
        }
        cachedHeaderVersion = headerVer
        cachedRuntimeVersion = runtimeVer
        Log.i("CRASH_BOUNDARY", "[CRASH_BOUNDARY] Stage: header/runtime version validation | Status: SUCCESS | Extension: * | Generation: 0 | Header: $headerVer | Runtime: $runtimeVer")

        state = V8BridgeState.LOADED
        logI(TAG, "V8_LOAD_SUCCESS: Native V8 binaries loaded into process")
        return true
    }

    fun isLoaded(): Boolean = state == V8BridgeState.LOADED || state == V8BridgeState.INITIALIZING || state == V8BridgeState.READY

    fun isV8Initialized(): Boolean = state == V8BridgeState.READY

    val isAvailable: Boolean
        get() = state == V8BridgeState.READY

    fun getV8State(): V8BridgeState = state

    fun getV8FailureReason(): String? = failureReason

    fun getV8FailureThrowable(): Throwable? = failureThrowable

    @Synchronized
    fun initializeV8(): Boolean {
        if (state == V8BridgeState.READY) return true
        if (state == V8BridgeState.FAILED || state == V8BridgeState.ABI_INVALID) return false
        if (!ensureNativeLoaded()) return false
        if (state == V8BridgeState.READY) return true
        if (state == V8BridgeState.FAILED || state == V8BridgeState.ABI_INVALID) return false

        state = V8BridgeState.INITIALIZING
        logI(TAG, "V8_INIT_START: Requesting explicit native V8 engine initialization")
        Log.i("CRASH_BOUNDARY", "[CRASH_BOUNDARY] Stage: nativeInitializeV8() | Status: START | Extension: * | Generation: 0")

        return try {
            val ok = nativeInitializeV8()
            if (ok) {
                Log.i("CRASH_BOUNDARY", "[CRASH_BOUNDARY] Stage: nativeInitializeV8() | Status: SUCCESS | Extension: * | Generation: 0")
            } else {
                Log.e("CRASH_BOUNDARY", "[CRASH_BOUNDARY] Stage: nativeInitializeV8() | Status: ERROR | Extension: * | Generation: 0 | Reason: nativeInitializeV8 returned false")
            }
            if (ok) {
                state = V8BridgeState.READY
                cachedV8Version = try { nativeGetV8Version() } catch (_: Throwable) { V8BuildContract.V8_VERSION }
                cachedV8GitRevision = try { nativeGetV8GitRevision() } catch (_: Throwable) { "orion-main" }
                cachedV8BuildInfo = try { nativeGetV8BuildInfo() } catch (_: Throwable) { "V8 ${V8BuildContract.V8_VERSION} (NativeScript 9.1.1)" }
                logI(TAG, "V8_INIT_SUCCESS: Native V8 platform initialized and ready")
                true
            } else {
                state = V8BridgeState.FAILED
                val reason = nativeGetV8FailureReason().ifBlank { "Native V8 initialization failed" }
                val err = IllegalStateException(reason)
                failureThrowable = err
                failureReason = reason
                logE(TAG, "V8_INIT_FAILED: $failureReason")
                false
            }
        } catch (e: Throwable) {
            state = V8BridgeState.FAILED
            failureThrowable = e
            failureReason = "Exception during nativeInitializeV8: ${e.message}"
            Log.e("CRASH_BOUNDARY", "[CRASH_BOUNDARY] Stage: nativeInitializeV8() | Status: ERROR | Extension: * | Generation: 0 | Error: ${e.message}", e)
            logE(TAG, "V8_INIT_FAILED: $failureReason", e)
            false
        }
    }

    /**
     * Canonical boundary for worker demand. Only this path may move the bridge from
     * LOADED/NOT_LOADED into INITIALIZING and finally READY.
     */
    @Synchronized
    fun ensureReadyForWorker(): Boolean {
        return when (state) {
            V8BridgeState.READY -> true
            V8BridgeState.LOADED,
            V8BridgeState.INITIALIZING -> initializeV8()
            V8BridgeState.NOT_LOADED -> ensureNativeLoaded() && initializeV8()
            V8BridgeState.LOADING -> false
            V8BridgeState.ABI_INVALID,
            V8BridgeState.FAILED -> false
        }
    }

    private fun readyForNativeOperation(): Boolean {
        if (state != V8BridgeState.READY) {
            logW(TAG, "V8_OPERATION_REJECTED: native operation requested while state=$state")
            return false
        }
        return true
    }

    /**
     * Explicit diagnostic/test operation only. Creates a temporary isolate to evaluate a basic JS expression.
     * Must never be invoked automatically during worker startup, extension installation, backend selection, or normal readiness queries.
     */
    fun hasFullV8(): Boolean {
        if (state != V8BridgeState.READY && state != V8BridgeState.INITIALIZING) return false
        if (fullV8Verified) return true
        Log.i("CRASH_BOUNDARY", "[CRASH_BOUNDARY] Stage: nativeHasFullV8() [Explicit Diagnostic] | Status: START | Extension: * | Generation: 0")
        return try {
            val ok = nativeHasFullV8()
            if (ok) {
                fullV8Verified = true
                Log.i("CRASH_BOUNDARY", "[CRASH_BOUNDARY] Stage: nativeHasFullV8() [Explicit Diagnostic] | Status: SUCCESS | Extension: * | Generation: 0")
            } else {
                Log.e("CRASH_BOUNDARY", "[CRASH_BOUNDARY] Stage: nativeHasFullV8() [Explicit Diagnostic] | Status: ERROR | Extension: * | Generation: 0 | Reason: nativeHasFullV8 returned false")
            }
            ok
        } catch (t: Throwable) {
            Log.e("CRASH_BOUNDARY", "[CRASH_BOUNDARY] Stage: nativeHasFullV8() [Explicit Diagnostic] | Status: ERROR | Extension: * | Generation: 0 | Error: ${t.message}", t)
            false
        }
    }

    fun markInitialized(success: Boolean) {
        if (success) {
            state = V8BridgeState.READY
        } else if (state != V8BridgeState.NOT_LOADED) {
            state = V8BridgeState.FAILED
        }
    }

    fun getV8Version(): String = if (state == V8BridgeState.READY) {
        cachedV8Version ?: try { nativeGetV8Version().also { cachedV8Version = it } } catch (_: Throwable) { V8BuildContract.V8_VERSION }
    } else "UNAVAILABLE"

    fun getRuntimeVersion(): String = if (state == V8BridgeState.READY || state == V8BridgeState.LOADED) {
        cachedRuntimeVersion ?: try { nativeGetRuntimeVersion().also { cachedRuntimeVersion = it } } catch (_: Throwable) { V8BuildContract.V8_VERSION }
    } else "UNAVAILABLE"

    fun getHeaderVersion(): String = if (state == V8BridgeState.READY || state == V8BridgeState.LOADED) {
        cachedHeaderVersion ?: try { nativeGetHeaderVersion().also { cachedHeaderVersion = it } } catch (_: Throwable) { V8BuildContract.V8_VERSION }
    } else "UNAVAILABLE"

    fun getV8GitRevision(): String = if (state == V8BridgeState.READY) {
        cachedV8GitRevision ?: try { nativeGetV8GitRevision().also { cachedV8GitRevision = it } } catch (_: Throwable) { "orion-main" }
    } else "UNAVAILABLE"

    fun getV8BuildInfo(): String = if (state == V8BridgeState.READY) {
        cachedV8BuildInfo ?: try { nativeGetV8BuildInfo().also { cachedV8BuildInfo = it } } catch (_: Throwable) { "V8 ${V8BuildContract.V8_VERSION} (NativeScript 9.1.1)" }
    } else "UNAVAILABLE"

    fun createWorker(
        extensionId: String,
        generationId: Int,
        scriptPath: String,
        bootstrapScript: String,
        isModule: Boolean,
        moduleSourcesBlob: String,
        extensionRootPath: String = "",
        maxHeapMemoryMb: Int = 64
    ): Boolean {
        if (extensionId.isBlank() || generationId <= 0) return false
        if (!readyForNativeOperation()) return false
        Log.i("CRASH_BOUNDARY", "[CRASH_BOUNDARY] Stage: native worker creation | Status: START | Extension: $extensionId | Generation: $generationId | ScriptPath: $scriptPath | IsModule: $isModule")
        return try {
            val ok = nativeCreateWorker(
                extensionId, generationId, scriptPath, bootstrapScript, isModule, moduleSourcesBlob,
                extensionRootPath, maxHeapMemoryMb
            )
            if (ok) {
                Log.i("CRASH_BOUNDARY", "[CRASH_BOUNDARY] Stage: native worker creation | Status: SUCCESS | Extension: $extensionId | Generation: $generationId")
            } else {
                Log.e("CRASH_BOUNDARY", "[CRASH_BOUNDARY] Stage: native worker creation | Status: ERROR | Extension: $extensionId | Generation: $generationId | Reason: nativeCreateWorker returned false")
            }
            ok
        } catch (e: Throwable) {
            Log.e("CRASH_BOUNDARY", "[CRASH_BOUNDARY] Stage: native worker creation | Status: ERROR | Extension: $extensionId | Generation: $generationId | Error: ${e.message}", e)
            logE(TAG, "createWorker crashed safely: ${e.message}", e)
            false
        }
    }

    fun executeScript(extensionId: String, generationId: Int, scriptContent: String): Boolean {
        if (extensionId.isBlank() || generationId <= 0) return false
        if (!readyForNativeOperation()) return false
        return try {
            nativeExecuteScript(extensionId.trim(), generationId, scriptContent)
        } catch (e: Throwable) {
            logE(TAG, "executeScript crashed safely: ${e.message}", e)
            false
        }
    }

    fun executeScriptResult(extensionId: String, generationId: Int, scriptContent: String): String? {
        if (extensionId.isBlank() || generationId <= 0) return null
        if (!readyForNativeOperation()) return null
        return try {
            nativeExecuteScriptResult(extensionId.trim(), generationId, scriptContent)
        } catch (e: Throwable) {
            logE(TAG, "executeScriptResult crashed safely: ${e.message}", e)
            null
        }
    }

    fun dispatchEvent(extensionId: String, generationId: Int, eventName: String, payloadJson: String): Boolean {
        if (extensionId.isBlank() || generationId <= 0 || eventName.isBlank()) return false
        if (!readyForNativeOperation()) return false
        return try {
            nativeDispatchEvent(extensionId.trim(), generationId, eventName.trim(), payloadJson)
        } catch (e: Throwable) {
            logE(TAG, "dispatchEvent crashed safely: ${e.message}", e)
            false
        }
    }

    fun fireTimer(extensionId: String, generationId: Int, timerId: Int): Boolean {
        if (extensionId.isBlank() || generationId <= 0 || timerId <= 0) return false
        if (!readyForNativeOperation()) return false
        return try {
            nativeFireTimer(extensionId.trim(), generationId, timerId)
        } catch (e: Throwable) {
            logE(TAG, "fireTimer crashed safely: ${e.message}", e)
            false
        }
    }

    fun resolveHostApiCall(extensionId: String, generationId: Int, requestId: String, resultJson: String, errorMsg: String): Boolean {
        if (extensionId.isBlank() || generationId <= 0 || requestId.isBlank()) return false
        if (!readyForNativeOperation()) return false
        return try {
            nativeResolveHostApiCall(extensionId.trim(), generationId, requestId.trim(), resultJson, errorMsg)
            true
        } catch (e: Throwable) {
            logE(TAG, "resolveHostApiCall crashed safely: ${e.message}", e)
            false
        }
    }

    fun stopWorker(extensionId: String): Boolean {
        if (extensionId.isBlank()) return false
        if (!readyForNativeOperation()) return false
        return try {
            nativeStopWorker(extensionId.trim())
        } catch (e: Throwable) {
            logE(TAG, "stopWorker crashed safely: ${e.message}", e)
            false
        }
    }

    fun stopWorker(extensionId: String, generationId: Int): Boolean {
        if (extensionId.isBlank() || generationId <= 0) return false
        return stopWorker(extensionId)
    }

    fun stopAllWorkers() {
        if (!readyForNativeOperation()) return
        try {
            nativeStopAllWorkers()
        } catch (e: Throwable) {
            logE(TAG, "stopAllWorkers crashed safely: ${e.message}", e)
        }
    }

    fun getHeapUsage(extensionId: String): Long {
        if (extensionId.isBlank()) return 0L
        if (!readyForNativeOperation()) return 0L
        return try {
            nativeGetHeapUsage(extensionId.trim())
        } catch (_: Throwable) {
            0L
        }
    }

    fun getHeapUsage(extensionId: String, generationId: Int): Long {
        if (extensionId.isBlank() || generationId <= 0) return 0L
        return getHeapUsage(extensionId)
    }

    external fun nativeInitializeV8(): Boolean
    external fun nativeGetV8State(): Int
    external fun nativeGetV8FailureReason(): String
    external fun nativeGetHeaderVersion(): String
    external fun nativeGetRuntimeVersion(): String
    external fun nativeHasFullV8(): Boolean
    external fun nativeGetV8Version(): String
    external fun nativeGetV8GitRevision(): String
    external fun nativeGetV8BuildInfo(): String
    external fun nativeCreateWorker(
        extensionId: String, generationId: Int, scriptPath: String, bootstrapScript: String,
        isModule: Boolean, moduleSourcesBlob: String, extensionRootPath: String, maxHeapMemoryMb: Int
    ): Boolean
    external fun nativeExecuteScript(extensionId: String, generationId: Int, scriptContent: String): Boolean
    external fun nativeExecuteScriptResult(extensionId: String, generationId: Int, scriptContent: String): String?
    external fun nativeDispatchEvent(extensionId: String, generationId: Int, eventName: String, payloadJson: String): Boolean
    external fun nativeFireTimer(extensionId: String, generationId: Int, timerId: Int): Boolean
    external fun nativeResolveHostApiCall(extensionId: String, generationId: Int, requestId: String, resultJson: String, errorMsg: String)
    external fun nativeStopWorker(extensionId: String): Boolean
    external fun nativeStopAllWorkers()
    external fun nativeGetHeapUsage(extensionId: String): Long
}
