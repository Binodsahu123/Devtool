package com.swift.browser.extensionv8

/** Canonical Native-V8 worker runtime policy shared by Kotlin and JNI boundaries. */
data class V8RuntimeConfig(
    val enableJit: Boolean = true,
    val enableWasm: Boolean = true,
    val maxHeapMemoryMb: Int = 256,
    /** Idle period after which the Android-side worker scheduler may suspend a worker. */
    val idleSuspendTimeoutMs: Long = 30_000L,
    /** Maximum lifetime for one browser -> native host API transaction. */
    val hostCallTimeoutMs: Long = 30_000L,
    /** Maximum keepalive window used while an extension event promise is outstanding. */
    val eventKeepaliveTimeoutMs: Long = 30_000L,
    val maxEventQueueSize: Int = 100,
    val preferredBackend: String = "NATIVE_V8",
    val logVerbose: Boolean = false
) {
    init {
        require(maxHeapMemoryMb in 32..1024) { "maxHeapMemoryMb must be between 32 and 1024 MiB" }
        require(idleSuspendTimeoutMs >= 1_000L) { "idleSuspendTimeoutMs must be >= 1 second" }
        require(hostCallTimeoutMs >= 1_000L) { "hostCallTimeoutMs must be >= 1 second" }
        require(eventKeepaliveTimeoutMs >= 1_000L) { "eventKeepaliveTimeoutMs must be >= 1 second" }
        require(maxEventQueueSize in 1..10_000) { "maxEventQueueSize out of range" }
    }
}
