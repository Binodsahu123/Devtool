package com.swift.browser.extensionv8

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

object V8RuntimeDiagnostics {
    const val STATUS_NATIVE_V8_UNAVAILABLE = "NATIVE_V8_UNAVAILABLE"
    const val STATUS_RUNTIME_VERIFIED = "RUNTIME_VERIFIED"
    const val STATUS_PENDING_EXTERNAL_BUILD = "PENDING_EXTERNAL_BUILD"
    const val STATUS_DEVICE_VERIFIED = "DEVICE_VERIFIED"
    const val STATUS_EXTENSION_VERIFIED = "EXTENSION_VERIFIED"
    const val PINNED_NDK_VERSION = "29.0.14206865"
    private val activeWorkers = AtomicLong(0)
    private val totalExecutions = AtomicLong(0)
    private val totalEventsDispatched = AtomicLong(0)
    private val totalErrorsEncountered = AtomicLong(0)
    private val memoryUsageByExtension = ConcurrentHashMap<String, Long>()
    fun recordWorkerStarted(extensionId: String) { activeWorkers.incrementAndGet(); totalExecutions.incrementAndGet() }
    fun recordWorkerStopped(extensionId: String) { activeWorkers.decrementAndGet(); memoryUsageByExtension.remove(extensionId) }
    fun recordEventDispatched(extensionId: String) { totalEventsDispatched.incrementAndGet() }
    fun recordError(extensionId: String, error: String) { totalErrorsEncountered.incrementAndGet() }
    fun updateHeapUsage(extensionId: String, heapBytes: Long) { memoryUsageByExtension[extensionId] = heapBytes }
    fun getRuntimeStatus(): String = when {
        V8NativeBridge.isV8Initialized() -> STATUS_RUNTIME_VERIFIED
        V8NativeBridge.isLoaded() -> STATUS_DEVICE_VERIFIED
        else -> STATUS_NATIVE_V8_UNAVAILABLE
    }
    fun getDiagnosticsSummary(includeExplicitSmokeTest: Boolean = false): Map<String, Any> = mapOf(
        "status" to getRuntimeStatus(),
        "runtimeProvider" to V8BuildContract.RUNTIME_PROVIDER,
        "nativeScriptPackage" to V8BuildContract.NATIVESCRIPT_PACKAGE,
        "nativeScriptVersion" to V8BuildContract.NATIVESCRIPT_VERSION,
        "nativeArtifact" to "libNativeScript.so",
        "runtimeV8Version" to V8NativeBridge.getV8Version(),
        "runtimeV8Revision" to V8NativeBridge.getV8GitRevision(),
        "runtimeBuildInfo" to V8NativeBridge.getV8BuildInfo(),
        "ndkVersion" to PINNED_NDK_VERSION,
        "supportedAbis" to listOf(V8BuildContract.TARGET_ABI),
        "targetOs" to V8BuildContract.TARGET_OS,
        "targetCpu" to V8BuildContract.TARGET_CPU,
        "isNativeLibraryLoaded" to V8NativeBridge.isLoaded(),
        "isV8Initialized" to V8NativeBridge.isV8Initialized(),
        "hasFullV8" to if (includeExplicitSmokeTest) V8NativeBridge.hasFullV8() else V8NativeBridge.isV8Initialized(),
        "activeWorkers" to activeWorkers.get(),
        "totalExecutions" to totalExecutions.get(),
        "totalEventsDispatched" to totalEventsDispatched.get(),
        "totalErrorsEncountered" to totalErrorsEncountered.get(),
        "memoryByExtension" to HashMap(memoryUsageByExtension)
    )

    /**
     * Explicit diagnostic operation that performs an on-demand temporary-isolate smoke test.
     */
    fun runExplicitSmokeTest(): Boolean = V8NativeBridge.hasFullV8()
}
