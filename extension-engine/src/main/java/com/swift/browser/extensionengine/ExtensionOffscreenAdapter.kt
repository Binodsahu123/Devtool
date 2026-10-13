package com.swift.browser.extensionengine

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Real implementation of Chrome MV3 offscreen API.
 * Manages offscreen WebViews for audio playback, DOM parsing, clipboard access,
 * and blob management without visual UI obstruction.
 */
class ExtensionOffscreenAdapter(
    private val context: Context,
    private val permissionManager: PermissionManager,
    private val registry: ExtensionRegistry
) {
    data class OffscreenDocument(
        val extensionId: String,
        val url: String,
        val reasons: List<String>,
        val justification: String,
        var webView: WebView? = null
    )

    private val activeDocuments = ConcurrentHashMap<String, OffscreenDocument>()
    private val mainHandler = Handler(Looper.getMainLooper())

    private fun verifyOffscreenPermission(sender: ExtensionSender) {
        val ext = registry.getExtension(sender.extensionId)
            ?: throw SecurityException("SecurityError: Extension ${sender.extensionId} not found.")
        if (!permissionManager.hasApiPermission(sender.extensionId, ext.permissions, "offscreen")) {
            throw SecurityException("SecurityError: Extension does not have 'offscreen' permission.")
        }
    }

    suspend fun createDocument(sender: ExtensionSender, parameters: JSONObject): JSONObject {
        verifyOffscreenPermission(sender)
        val extId = sender.extensionId
        if (activeDocuments.containsKey(extId)) {
            throw IllegalStateException("Only a single offscreen document may be active per extension.")
        }

        val url = parameters.optString("url", "")
        if (url.isBlank()) throw IllegalArgumentException("Missing required parameter: url")

        val reasonsArray = parameters.optJSONArray("reasons") ?: JSONArray()
        if (reasonsArray.length() == 0) throw IllegalArgumentException("At least one reason must be specified")
        val reasons = mutableListOf<String>()
        for (i in 0 until reasonsArray.length()) reasons.add(reasonsArray.getString(i))

        val justification = parameters.optString("justification", "")
        if (justification.isBlank()) throw IllegalArgumentException("Missing required parameter: justification")

        val ext = registry.getExtension(extId) ?: throw IllegalStateException("Extension not found")
        if (url.startsWith("http://") || url.startsWith("https://")) {
            throw IllegalArgumentException("Offscreen documents must load internal extension resources and cannot load remote URLs: $url")
        }

        val fullUrl = if (url.startsWith("chrome-extension://")) {
            if (!url.startsWith("chrome-extension://$extId/")) {
                throw SecurityException("Offscreen document URL must belong to extension $extId")
            }
            url
        } else {
            "chrome-extension://$extId/${url.removePrefix("/")}"
        }

        val doc = OffscreenDocument(
            extensionId = extId,
            url = fullUrl,
            reasons = reasons,
            justification = justification
        )

        kotlinx.coroutines.suspendCancellableCoroutine<Unit> { cont ->
            mainHandler.post {
                try {
                    val webView = WebView(context)
                    webView.settings.javaScriptEnabled = true
                    webView.settings.domStorageEnabled = true
                    webView.settings.mediaPlaybackRequiresUserGesture = false
                    webView.loadUrl(fullUrl)
                    doc.webView = webView
                    activeDocuments[extId] = doc
                    if (cont.isActive) cont.resume(Unit, onCancellation = null)
                } catch (e: Exception) {
                    if (cont.isActive) cont.resumeWith(Result.failure(e))
                }
            }
        }

        return JSONObject().put("status", "success")
    }

    suspend fun closeDocument(sender: ExtensionSender): JSONObject {
        verifyOffscreenPermission(sender)
        val extId = sender.extensionId
        val doc = activeDocuments.remove(extId)
            ?: throw IllegalStateException("No active offscreen document found for extension $extId")

        kotlinx.coroutines.suspendCancellableCoroutine<Unit> { cont ->
            mainHandler.post {
                try {
                    doc.webView?.stopLoading()
                    doc.webView?.destroy()
                    doc.webView = null
                } catch (_: Exception) {}
                if (cont.isActive) cont.resume(Unit, onCancellation = null)
            }
        }

        return JSONObject().put("status", "success")
    }

    fun hasDocument(sender: ExtensionSender): Boolean {
        verifyOffscreenPermission(sender)
        return activeDocuments.containsKey(sender.extensionId)
    }

    fun cleanupExtension(extensionId: String) {
        val doc = activeDocuments.remove(extensionId) ?: return
        mainHandler.post {
            try {
                doc.webView?.stopLoading()
                doc.webView?.destroy()
            } catch (_: Exception) {}
        }
    }
}
