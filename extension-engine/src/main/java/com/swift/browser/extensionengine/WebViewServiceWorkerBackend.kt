package com.swift.browser.extensionengine

import android.webkit.WebView

/**
 * ServiceWorkerExecutionBackend implementation wrapping ServiceWorkerJsRuntime (Android WebView backend).
 */
class WebViewServiceWorkerBackend(
    val swJsRuntime: ServiceWorkerJsRuntime
) : ServiceWorkerExecutionBackend {

    override fun getBackendType(): ServiceWorkerBackendType = ServiceWorkerBackendType.WEBVIEW

    override fun startWorker(
        parsedExtension: ParsedExtension,
        scriptPath: String,
        generationId: Int,
        bootstrapScript: String,
        isPrivate: Boolean,
        onStartupResult: (WorkerStartupResult, String?) -> Unit
    ) {
        swJsRuntime.startWorker(
            parsedExtension = parsedExtension,
            scriptPath = scriptPath,
            generationId = generationId,
            bootstrapScript = bootstrapScript,
            isPrivate = isPrivate,
            onStartupResult = onStartupResult
        )
    }

    override fun evaluateEvent(
        extensionId: String,
        generationId: Int,
        eventName: String,
        payload: Any?,
        callback: ((Boolean) -> Unit)?
    ) {
        swJsRuntime.evaluateEvent(
            extensionId = extensionId,
            generationId = generationId,
            eventName = eventName,
            payload = payload,
            callback = callback
        )
    }

    override fun stopWorker(extensionId: String) {
        swJsRuntime.stopWorker(extensionId)
    }

    override fun stopAll() {
        swJsRuntime.stopAll()
    }

    override fun isWorkerRunning(extensionId: String): Boolean {
        return swJsRuntime.isWorkerRunning(extensionId)
    }

    override fun getWorkerGeneration(extensionId: String): Int {
        return swJsRuntime.getWorkerGeneration(extensionId)
    }

    override fun setRuntimeBridgeProvider(provider: (Any) -> Any) {
        swJsRuntime.setRuntimeBridgeProvider { wv ->
            if (wv is WebView) provider(wv) else provider(wv)
        }
    }

    override fun setContextAwareRuntimeBridgeProvider(provider: (Any, ExtensionContextType) -> Any) {
        swJsRuntime.setContextAwareRuntimeBridgeProvider { wv, ctxType ->
            if (wv is WebView) provider(wv, ctxType) else provider(wv, ctxType)
        }
    }

    override fun setNetworkPermissionChecker(checker: (String, String, Boolean) -> Boolean) {
        swJsRuntime.setNetworkPermissionChecker(checker)
    }

    override fun setOnWorkerCrashListener(listener: ((String, String) -> Unit)?) {
        swJsRuntime.onWorkerCrash = listener
    }
}
