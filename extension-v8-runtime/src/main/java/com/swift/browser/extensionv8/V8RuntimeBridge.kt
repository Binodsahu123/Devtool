package com.swift.browser.extensionv8

import android.util.Log
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

object V8RuntimeBridge {
    private const val TAG = "V8RuntimeBridge"

    interface HostApiHandler {
        fun onHostApiCall(extensionId: String, generationId: Int, apiName: String, requestId: String, payloadJson: String)
    }

    interface WorkerEventListener {
        fun onWorkerStarted(extensionId: String, generationId: Int, success: Boolean)
        fun onWorkerError(extensionId: String, generationId: Int, error: String)
        fun onEventHandled(extensionId: String, generationId: Int, eventName: String, success: Boolean)
        fun onConsoleLog(extensionId: String, generationId: Int, level: String, message: String)
    }

    private var hostApiHandler: HostApiHandler? = null
    private var workerEventListener: WorkerEventListener? = null
    // Native callbacks must not synchronously re-enter the V8 isolate while it holds
    // its context mutex. Host API dispatch remains synchronous for registration ordering,
    // while lifecycle/log callbacks are serialized off the JNI stack.
    private val callbackExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "OrionV8Bridge").apply { isDaemon = true }
    }

    fun setHostApiHandler(handler: HostApiHandler?) {
        this.hostApiHandler = handler
    }

    fun setWorkerEventListener(listener: WorkerEventListener?) {
        this.workerEventListener = listener
    }

    @JvmStatic
    fun onHostApiCallFromNative(extensionId: String, generationId: Int, apiName: String, requestId: String, payloadJson: String) {
        Log.d(TAG, "Host API call from native: ext=$extensionId, gen=$generationId, api=$apiName, req=$requestId")
        hostApiHandler?.onHostApiCall(extensionId, generationId, apiName, requestId, payloadJson)
    }

    @JvmStatic
    fun onWorkerStartedFromNative(extensionId: String, generationId: Int, success: Boolean) {
        callbackExecutor.execute {
            runCatching { workerEventListener?.onWorkerStarted(extensionId, generationId, success) }
        }
    }

    @JvmStatic
    fun onWorkerErrorFromNative(extensionId: String, generationId: Int, error: String) {
        callbackExecutor.execute {
            Log.e(TAG, "Worker error from native: ext=$extensionId, gen=$generationId, err=$error")
            V8RuntimeDiagnostics.recordError(extensionId, error)
            runCatching { workerEventListener?.onWorkerError(extensionId, generationId, error) }
        }
    }

    @JvmStatic
    fun onEventHandledFromNative(extensionId: String, generationId: Int, eventName: String, success: Boolean) {
        callbackExecutor.execute {
            runCatching { workerEventListener?.onEventHandled(extensionId, generationId, eventName, success) }
        }
    }

    @JvmStatic
    fun onConsoleLogFromNative(extensionId: String, generationId: Int, level: String, message: String) {
        callbackExecutor.execute {
            Log.i(TAG, "[$level] [$extensionId:$generationId] $message")
            runCatching { workerEventListener?.onConsoleLog(extensionId, generationId, level, message) }
        }
    }
}
