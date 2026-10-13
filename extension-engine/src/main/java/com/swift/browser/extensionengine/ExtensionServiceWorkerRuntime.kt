package com.swift.browser.extensionengine

/**
 * Facade and entry point for MV3 Background Service Worker Runtime architecture.
 */
class ExtensionServiceWorkerRuntime(
    val serviceWorkerRegistry: ServiceWorkerRegistry,
    val lifecycleManager: ServiceWorkerLifecycleManager,
    val wakeController: ServiceWorkerWakeController,
    val shutdownController: ServiceWorkerShutdownController,
    val eventDispatcher: ServiceWorkerEventDispatcher
) {
    val partialServiceWorkerSupport: Boolean = PARTIAL_SERVICE_WORKER_SUPPORT
    val workerRuntimeCapability: String = WORKER_RUNTIME_CAPABILITY

    fun registerServiceWorker(extension: ParsedExtension): ServiceWorkerRegistration? {
        // MV3 only. MV2 background pages/event pages are not service workers.
        if (extension.manifestVersion < 3) return null
        val scriptPath = extension.backgroundSpec.serviceWorker.trim()
        if (scriptPath.isBlank() || !PathSanitizer.isSafePath(scriptPath)) return null
        return serviceWorkerRegistry.register(extension.id, scriptPath, isMV3 = true)
    }

    fun getWorkerState(extensionId: String): ServiceWorkerState {
        return serviceWorkerRegistry.getState(extensionId)
    }

    fun getWorker(extensionId: String): ServiceWorkerRegistration? {
        return serviceWorkerRegistry.getWorker(extensionId)
    }

    fun getWorkerExecutionContext(extensionId: String): ServiceWorkerExecutionContext? {
        return serviceWorkerRegistry.getWorker(extensionId)?.executionContext
    }

    fun getAllWorkers(): List<ServiceWorkerRegistration> {
        return serviceWorkerRegistry.getAllWorkers()
    }

    fun isRegistered(extensionId: String): Boolean {
        return serviceWorkerRegistry.isRegistered(extensionId)
    }

    fun wakeWorker(extensionId: String, callback: ((Boolean) -> Unit)? = null) {
        wakeController.wakeAndExecute(extensionId, null, object : ServiceWorkerWakeController.WakeCallback {
            override fun onWoken(extensionId: String, success: Boolean) {
                callback?.invoke(success)
            }
        })
    }

    fun suspendWorker(extensionId: String): Boolean {
        return shutdownController.suspendWorker(extensionId)
    }

    /**
     * Explicit lifecycle boundary used by disable/uninstall flows.
     * This keeps worker cleanup in one place instead of leaving a hidden
     * WebView/runtime alive after an extension is disabled.
     */
    fun shutdownWorker(extensionId: String): Boolean {
        return shutdownController.suspendWorker(extensionId)
    }
}

