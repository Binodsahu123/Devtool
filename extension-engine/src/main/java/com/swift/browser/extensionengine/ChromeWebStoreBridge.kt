package com.swift.browser.extensionengine

import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * JavaScript interface injected into Chrome Web Store and related extension pages.
 * Enables the webpage to query extension status and trigger native installation dialogs.
 */
class ChromeWebStoreBridge(
    private val context: Context,
    private val api: ExtensionEngineApi,
    private val webView: WebView?
) {
    private val mainScope = CoroutineScope(Dispatchers.Main)

    @JavascriptInterface
    fun promptInstall(extensionId: String, name: String?, iconUrl: String?) {
        val cleanId = ExtensionWebStoreManager.extractExtensionId(extensionId) ?: extensionId.trim().lowercase()
        if (cleanId.length == 32) {
            mainScope.launch {
                api.promptWebStoreInstall(
                    extensionId = cleanId,
                    name = name?.takeIf { it.isNotBlank() } ?: "Chrome Extension",
                    iconUrl = iconUrl
                )
            }
        }
    }

    @JavascriptInterface
    fun isInstalled(extensionId: String): Boolean {
        val cleanId = ExtensionWebStoreManager.extractExtensionId(extensionId) ?: extensionId.trim().lowercase()
        return api.uiState.value.installedExtensions.any { it.id.equals(cleanId, ignoreCase = true) }
    }

    @JavascriptInterface
    fun getInstalledExtensionsJson(): String {
        val array = org.json.JSONArray()
        api.uiState.value.installedExtensions.forEach {
            array.put(it.id)
        }
        return array.toString()
    }
}
