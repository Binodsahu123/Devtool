package com.swift.browser.extensionengine

enum class ServiceWorkerBackendType {
    WEBVIEW,
    V8
}

/**
 * Abstract execution backend interface for MV3 Extension Service Worker background execution.
 * Allows switching between WebView Service Worker Backend (fallback) and Native V8 Backend (primary).
 */
interface ServiceWorkerExecutionBackend {
    fun getBackendType(): ServiceWorkerBackendType

    fun startWorker(
        parsedExtension: ParsedExtension,
        scriptPath: String,
        generationId: Int,
        bootstrapScript: String,
        isPrivate: Boolean = false,
        onStartupResult: (WorkerStartupResult, String?) -> Unit
    )

    fun evaluateEvent(
        extensionId: String,
        generationId: Int,
        eventName: String,
        payload: Any?,
        callback: ((Boolean) -> Unit)? = null
    )

    fun stopWorker(extensionId: String)

    fun stopAll()

    fun isWorkerRunning(extensionId: String): Boolean

    fun getWorkerGeneration(extensionId: String): Int

    fun setRuntimeBridgeProvider(provider: (Any) -> Any)

    fun setContextAwareRuntimeBridgeProvider(provider: (Any, ExtensionContextType) -> Any)

    fun setNetworkPermissionChecker(checker: (String, String, Boolean) -> Boolean)

    fun setOnWorkerCrashListener(listener: ((extensionId: String, reason: String) -> Unit)?)
}
