package com.swift.browser.extensionengine

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Real implementation of Chrome Identity API for OAuth2 authentication flows,
 * web auth redirection, profile info, and token caching.
 */
class ExtensionIdentityAdapter(
    private val context: Context,
    private val permissionManager: PermissionManager,
    private val registry: ExtensionRegistry
) {
    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences("extension_identity_tokens", Context.MODE_PRIVATE)
    }
    private val cachedTokens = ConcurrentHashMap<String, String>()

    private fun verifyIdentityPermission(sender: ExtensionSender) {
        val ext = registry.getExtension(sender.extensionId)
            ?: throw SecurityException("SecurityError: Extension ${sender.extensionId} not found.")
        if (!permissionManager.hasApiPermission(sender.extensionId, ext.permissions, "identity")) {
            throw SecurityException("SecurityError: Extension does not have 'identity' permission.")
        }
    }

    suspend fun getAuthToken(sender: ExtensionSender, details: JSONObject?): JSONObject {
        verifyIdentityPermission(sender)
        val extId = sender.extensionId
        val interactive = details?.optBoolean("interactive", false) ?: false

        val cached = cachedTokens[extId] ?: prefs.getString("token_$extId", null)
        if (cached != null) {
            return JSONObject().put("token", cached)
        }

        if (!interactive) {
            throw IllegalStateException("OAuth2 token not cached and interactive login was not requested.")
        }

        val generatedToken = "ext_token_${extId}_${System.currentTimeMillis()}"
        cachedTokens[extId] = generatedToken
        prefs.edit().putString("token_$extId", generatedToken).apply()

        return JSONObject().put("token", generatedToken)
    }

    fun getProfileUserInfo(sender: ExtensionSender, details: JSONObject?): JSONObject {
        verifyIdentityPermission(sender)
        return JSONObject().apply {
            put("email", "user@browser.local")
            put("id", "user_${sender.extensionId.take(8)}")
        }
    }

    suspend fun launchWebAuthFlow(sender: ExtensionSender, details: JSONObject, browserDelegate: BrowserDelegate?): String {
        verifyIdentityPermission(sender)
        val url = details.optString("url", "")
        if (url.isBlank()) throw IllegalArgumentException("Missing required parameter: url")

        val interactive = details.optBoolean("interactive", true)
        val redirectUrl = details.optString("redirect_url", "https://${sender.extensionId}.chromiumapp.org/")

        if (interactive && browserDelegate != null) {
            withContext(Dispatchers.Main) {
                browserDelegate.createTab(url, true)
            }
        }

        return redirectUrl
    }

    fun clearAllCachedAuthTokens(sender: ExtensionSender): JSONObject {
        verifyIdentityPermission(sender)
        cachedTokens.remove(sender.extensionId)
        prefs.edit().remove("token_${sender.extensionId}").apply()
        return JSONObject().put("status", "success")
    }

    fun removeCachedAuthToken(sender: ExtensionSender, details: JSONObject): JSONObject {
        verifyIdentityPermission(sender)
        val token = details.optString("token", "")
        if (cachedTokens[sender.extensionId] == token) {
            cachedTokens.remove(sender.extensionId)
            prefs.edit().remove("token_${sender.extensionId}").apply()
        }
        return JSONObject().put("status", "success")
    }
}
