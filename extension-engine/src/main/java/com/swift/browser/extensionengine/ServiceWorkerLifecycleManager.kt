package com.swift.browser.extensionengine

import org.json.JSONObject
import java.util.UUID

import android.util.Log

/**
 * Lifecycle Manager governing MV3 extension service worker lifecycle transitions.
 */
class ServiceWorkerLifecycleManager(
    val serviceWorkerRegistry: ServiceWorkerRegistry,
    val wakeController: ServiceWorkerWakeController,
    val shutdownController: ServiceWorkerShutdownController,
    val extensionRegistry: ExtensionRegistry,
    val permissionAdapter: ExtensionPermissionAdapter? = null,
    val eventManager: EventManager? = null
) {
    fun onExtensionInstalled(
        extension: ParsedExtension,
        reason: String = "install",
        previousVersion: String = ""
    ) {
        val currentGen = serviceWorkerRegistry.getWorkerGeneration(extension.id)
        Log.i("CRASH_BOUNDARY", "[CRASH_BOUNDARY] Stage: onExtensionInstalled() | Status: START | Extension: ${extension.id} | Generation: $currentGen | Reason: $reason")
        try {
            // This manager is MV3-only. MV2 background pages/event pages are owned by
            // BackgroundScriptManager and must never be registered as service workers.
            if (extension.manifestVersion < 3) return
            val serviceWorkerPath = extension.backgroundSpec.serviceWorker.trim()
            if (serviceWorkerPath.isBlank() || !PathSanitizer.isSafePath(serviceWorkerPath)) return

            val existing = serviceWorkerRegistry.getWorker(extension.id)
            if (existing != null) {
                try { shutdownController.suspendWorker(extension.id, notifyOnSuspend = false) } catch (_: Throwable) {}
                serviceWorkerRegistry.unregister(extension.id)
            }

            serviceWorkerRegistry.register(extension.id, serviceWorkerPath, isMV3 = extension.manifestVersion >= 3)
            serviceWorkerRegistry.transitionState(extension.id, ServiceWorkerState.DORMANT)

            // Chromium extension-worker installation order is: install -> runtime.onInstalled -> activate.
            // Queue all three before the first wake; the dispatcher delivers them strictly in order.
            val installationDetails = JSONObject().apply {
                put("reason", reason)
                if (previousVersion.isNotBlank()) put("previousVersion", previousVersion)
            }
            val installEvent = QueuedServiceWorkerEvent(
                eventId = "install_${System.currentTimeMillis()}_${UUID.randomUUID()}",
                eventName = "install",
                payload = JSONObject(),
                sourceContext = "lifecycle"
            )
            val onInstalledEvent = QueuedServiceWorkerEvent(
                eventId = "installed_${System.currentTimeMillis()}_${UUID.randomUUID()}",
                eventName = "runtime.onInstalled",
                payload = installationDetails,
                sourceContext = "lifecycle"
            )
            val activateEvent = QueuedServiceWorkerEvent(
                eventId = "activate_${System.currentTimeMillis()}_${UUID.randomUUID()}",
                eventName = "activate",
                payload = JSONObject(),
                sourceContext = "lifecycle"
            )
            if (!serviceWorkerRegistry.enqueueEvent(extension.id, installEvent) ||
                !serviceWorkerRegistry.enqueueEvent(extension.id, onInstalledEvent) ||
                !serviceWorkerRegistry.enqueueEvent(extension.id, activateEvent)) {
                throw IllegalStateException("Unable to queue complete MV3 installation lifecycle")
            }

            // The installation lifecycle itself is an event demand. Start the worker once so
            // install/onInstalled/activate can be delivered, then let the normal idle policy
            // return the worker to a dormant state. This keeps native startup out of unrelated
            // browser/extension-engine initialization while preserving the required MV3 lifecycle.
            wakeController.wakeAndExecute(extension.id, event = null)
            val postGen = serviceWorkerRegistry.getWorkerGeneration(extension.id)
            Log.i("CRASH_BOUNDARY", "[CRASH_BOUNDARY] Stage: onExtensionInstalled() | Status: SUCCESS | Extension: ${extension.id} | Generation: $postGen")
        } catch (t: Throwable) {
            Log.e("CRASH_BOUNDARY", "[CRASH_BOUNDARY] Stage: onExtensionInstalled() | Status: ERROR | Extension: ${extension.id} | Generation: $currentGen | Error: ${t.message}", t)
            Log.e("SWLifecycleManager", "Error in onExtensionInstalled for ${extension.id}", t)
        }
    }

    fun onExtensionStartup(extension: ParsedExtension) {
        try {
            if (extension.manifestVersion < 3 || extension.backgroundSpec.serviceWorker.isBlank()) return
            val worker = serviceWorkerRegistry.getWorker(extension.id) ?: return
            if (!extensionRegistry.isExtensionEnabled(extension.id)) return

            if (worker.state == ServiceWorkerState.DORMANT || worker.state == ServiceWorkerState.SUSPEND || worker.state == ServiceWorkerState.SUSPENDED || worker.state == ServiceWorkerState.STOPPED || worker.state == ServiceWorkerState.REGISTERED) {
                val startupEvent = QueuedServiceWorkerEvent(
                    eventId = "startup_${System.currentTimeMillis()}_${extension.id}_${UUID.randomUUID()}",
                    eventName = "runtime.onStartup",
                    payload = JSONObject(),
                    sourceContext = "lifecycle"
                )
                wakeController.wakeAndExecute(extension.id, startupEvent)
            }
        } catch (t: Throwable) {
            Log.e("SWLifecycleManager", "Error in onExtensionStartup for ${extension.id}", t)
        }
    }

    fun onExtensionDisabled(extensionId: String) {
        try {
            shutdownController.suspendWorker(extensionId, notifyOnSuspend = false, terminalState = ServiceWorkerState.DISABLED)
        } catch (t: Throwable) {
            Log.e("SWLifecycleManager", "Error in onExtensionDisabled for $extensionId", t)
        }
    }

    fun onExtensionUninstalled(extensionId: String) {
        try {
            shutdownController.suspendWorker(extensionId, notifyOnSuspend = false, terminalState = ServiceWorkerState.UNINSTALLED)
            serviceWorkerRegistry.unregister(extensionId)
        } catch (t: Throwable) {
            Log.e("SWLifecycleManager", "Error in onExtensionUninstalled for $extensionId", t)
        }
    }

    fun onProcessRestart() {
        try {
            val allExts = extensionRegistry.getAllRegisteredExtensions()
            val startupExtensions = mutableListOf<ParsedExtension>()
            for (info in allExts) {
                val ext = info.extension
                if (ext.manifestVersion >= 3 && ext.backgroundSpec.serviceWorker.isNotBlank()) {
                    val swPath = ext.backgroundSpec.serviceWorker
                    serviceWorkerRegistry.register(ext.id, swPath, isMV3 = true)
                    serviceWorkerRegistry.transitionState(ext.id, ServiceWorkerState.DORMANT)
                    // Restore any unfinished install/startup lifecycle events before adding the
                    // current process's onStartup event. The registry remains the only queue owner.
                    serviceWorkerRegistry.restorePersistedLifecycleEvents(ext.id)
                    startupExtensions.add(ext)
                }
            }

            // Chrome keeps extension service workers dormant until an event requires them.
            // Queue onStartup without creating/waking every worker during browser startup.
            startupExtensions.forEach { ext ->
                try {
                    if (extensionRegistry.isExtensionEnabled(ext.id)) {
                        val startupEvent = QueuedServiceWorkerEvent(
                            eventId = "startup_${System.currentTimeMillis()}_${ext.id}_${UUID.randomUUID()}",
                            eventName = "runtime.onStartup",
                            payload = JSONObject().put("reason", "process_restart"),
                            sourceContext = "lifecycle"
                        )
                        wakeController.wakeAndExecute(ext.id, startupEvent)
                    }
                } catch (t: Throwable) {
                    Log.e("SWLifecycleManager", "Error queueing startup event for ${ext.id}", t)
                }
            }
        } catch (t: Throwable) {
            Log.e("SWLifecycleManager", "Error in onProcessRestart", t)
        }
    }

    fun onTabClose(tabId: String) {
        try {
            // Browser-originated events must pass through EventManager so MV3 listener gating
            // decides which extensions are eligible for wake. The lifecycle manager does not
            // wake every installed worker merely because a tab closed.
            val manager = eventManager
            if (manager != null) {
                manager.triggerEvent(
                    eventName = "tabs.onRemoved",
                    params = JSONObject().apply { put("tabId", tabId) }
                )
                return
            }

            // Legacy constructor compatibility: when no EventManager is injected, avoid the
            // old all-workers wake behavior and do nothing rather than violating listener gates.
            Log.w("SWLifecycleManager", "tabs.onRemoved dropped because EventManager is not wired")
        } catch (t: Throwable) {
            Log.e("SWLifecycleManager", "Error in onTabClose for tab $tabId", t)
        }
    }

    fun onPrivateModeToggle(extensionId: String, isPrivate: Boolean) {
        try {
            if (isPrivate) {
                val allowed = permissionAdapter?.isAllowedInPrivate(extensionId)
                    ?: extensionRegistry.getExtension(extensionId)?.allowedInPrivate
                    ?: false
                if (!allowed) {
                    shutdownController.suspendWorker(extensionId, notifyOnSuspend = false)
                }
            }
        } catch (t: Throwable) {
            Log.e("SWLifecycleManager", "Error in onPrivateModeToggle for $extensionId", t)
        }
    }
}
