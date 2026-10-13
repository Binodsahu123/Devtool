package com.swift.browser.extensionv8

import android.content.Context
import android.util.Log

class V8Runtime(val context: Context, override val config: V8RuntimeConfig = V8RuntimeConfig()) : V8RuntimeInterface {
    private val tag = "OrionV8"
    override val workerManager by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        V8WorkerManager(context, config)
    }

    override fun ensureInitialized(): Boolean {
        return V8NativeBridge.initializeV8()
    }

    override fun ensureReadyForWorker(): Boolean {
        return V8NativeBridge.ensureReadyForWorker()
    }

    override fun hasOrionNativeV8(): Boolean = V8NativeBridge.isV8Initialized()
    override fun isNativeV8Available(): Boolean = V8NativeBridge.isV8Initialized()
    override fun backendStatus(): String = when (V8NativeBridge.getV8State()) {
        V8BridgeState.READY -> "NATIVE_V8_READY"
        V8BridgeState.LOADED -> "NATIVE_V8_LOADED"
        V8BridgeState.LOADING -> "NATIVE_V8_LOADING"
        V8BridgeState.INITIALIZING -> "NATIVE_V8_INITIALIZING"
        V8BridgeState.ABI_INVALID -> "NATIVE_V8_ABI_INVALID"
        V8BridgeState.FAILED -> "NATIVE_V8_FAILED"
        V8BridgeState.NOT_LOADED -> "NATIVE_V8_STANDBY"
    }
    override fun isV8Supported(): Boolean {
        val state = V8NativeBridge.getV8State()
        return state != V8BridgeState.FAILED && state != V8BridgeState.ABI_INVALID
    }
    override fun isV8Ready(): Boolean {
        val state = V8NativeBridge.getV8State()
        return state == V8BridgeState.READY
    }
    override fun isV8BackendAvailable(): Boolean {
        // Pure readiness query. Never load/initialize native V8 while merely selecting a backend.
        return isV8Ready()
    }
    override fun getV8Version(): String = V8NativeBridge.getV8Version()
    override fun getRuntimeVersion(): String = V8NativeBridge.getRuntimeVersion()
    override fun getHeaderVersion(): String = V8NativeBridge.getHeaderVersion()
    override fun getV8GitRevision(): String = V8NativeBridge.getV8GitRevision()
    override fun getV8BuildInfo(): String = V8NativeBridge.getV8BuildInfo()
    override fun shutdown() {
        if (V8NativeBridge.isV8Initialized()) {
            workerManager.stopAll()
        }
    }
}
