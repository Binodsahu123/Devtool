package com.swift.browser.extensionv8

/**
 * Common contract for the Native V8 execution runtime.
 */
interface V8RuntimeInterface {
    val workerManager: V8WorkerManager
    val config: V8RuntimeConfig

    fun ensureInitialized(): Boolean
    fun ensureReadyForWorker(): Boolean
    fun hasOrionNativeV8(): Boolean
    fun isNativeV8Available(): Boolean
    fun backendStatus(): String
    fun isV8Supported(): Boolean
    fun isV8Ready(): Boolean
    fun isV8BackendAvailable(): Boolean
    fun getV8Version(): String
    fun getRuntimeVersion(): String
    fun getHeaderVersion(): String
    fun getV8GitRevision(): String
    fun getV8BuildInfo(): String
    fun shutdown()
}
