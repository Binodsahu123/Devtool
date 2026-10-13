package com.swift.browser.extensionengine

import android.content.Context
import android.util.Log

/**
 * Owns backend selection only; lifecycle policy stays in Mv3ServiceWorkerLifecycleCoordinator.
 *
 * MV3 service workers are Native-V8-only in Orion.
 * MV2 background/event pages remain owned by BackgroundScriptManager and use WebView.
 */
class ServiceWorkerBackendSelector(
    private val context: Context,
    val webViewBackend: WebViewServiceWorkerBackend,
    val v8Backend: V8ServiceWorkerBackend? = null
) {
    companion object {
        private const val TAG = "ServiceWorkerBackendSelector"
    }

    fun selectBackendForExtension(parsedExtension: ParsedExtension): ServiceWorkerExecutionBackend {
        require(parsedExtension.manifestVersion >= 3) {
            "selectBackendForExtension() is MV3-only; MV2 must use BackgroundScriptManager/WebView"
        }

        val backend = v8Backend ?: throw IllegalStateException("MV3 service workers requires Native V8 backend, but v8Backend is null")
        val isModuleWorker = parsedExtension.backgroundSpec.type.equals("module", ignoreCase = true)
        val mode = if (isModuleWorker) "Native V8 (ES Module)" else "Native V8"
        Log.i(TAG, "Extension ${parsedExtension.id} is MV3; selecting $mode service-worker backend")
        return backend
    }

    /**
     * Explicit legacy route for Manifest V2.
     *
     * MV2 background pages/event pages are not service workers and remain owned by
     * BackgroundScriptManager. The WebViewServiceWorkerBackend is only the legacy
     * execution adapter used by that existing path.
     */
    fun selectLegacyBackendForMv2(parsedExtension: ParsedExtension): ServiceWorkerExecutionBackend {
        require(parsedExtension.manifestVersion < 3) {
            "selectLegacyBackendForMv2() is MV2-only"
        }
        Log.i(TAG, "Extension ${parsedExtension.id} is MV2; selecting WebView legacy background backend")
        return webViewBackend
    }
}
