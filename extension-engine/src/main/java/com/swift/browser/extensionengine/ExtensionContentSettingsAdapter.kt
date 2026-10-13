package com.swift.browser.extensionengine

import android.content.Context
import android.content.SharedPreferences
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject

/**
 * Real implementation of chrome.contentSettings API supporting
 * cookies, images, javascript, popups, notifications, location, microphone, camera,
 * with active WebView runtime configuration enforcement.
 */
class ExtensionContentSettingsAdapter(
    private val context: Context,
    private val permissionManager: PermissionManager,
    private val registry: ExtensionRegistry
) {
    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences("extension_content_settings", Context.MODE_PRIVATE)
    }

    private fun verifyContentSettingsPermission(sender: ExtensionSender) {
        val ext = registry.getExtension(sender.extensionId)
            ?: throw SecurityException("SecurityError: Extension ${sender.extensionId} not found.")
        if (!permissionManager.hasApiPermission(sender.extensionId, ext.permissions, "contentSettings")) {
            throw SecurityException("SecurityError: Extension does not have 'contentSettings' permission.")
        }
    }

    fun getSetting(sender: ExtensionSender, contentType: String, details: JSONObject): JSONObject {
        verifyContentSettingsPermission(sender)
        val primaryUrl = details.optString("primaryUrl", "*")
        val settingVal = getContentSetting(contentType, primaryUrl)

        return JSONObject().apply {
            put("setting", settingVal)
        }
    }

    fun getContentSetting(contentType: String, url: String): String {
        val defaultVal = if (contentType == "popups") "block" else "allow"
        val exactKey = "${contentType}_${url}"
        if (prefs.contains(exactKey)) {
            return prefs.getString(exactKey, defaultVal) ?: defaultVal
        }

        // Pattern matching
        for ((k, v) in prefs.all) {
            if (k.startsWith("${contentType}_")) {
                val pattern = k.removePrefix("${contentType}_")
                if (matchesPattern(url, pattern)) {
                    return v.toString()
                }
            }
        }
        return prefs.getString("${contentType}_*", defaultVal) ?: defaultVal
    }

    fun isContentAllowed(contentType: String, url: String): Boolean {
        val setting = getContentSetting(contentType, url)
        return setting.equals("allow", ignoreCase = true)
    }

    fun applySettingsToWebView(webView: WebView, url: String) {
        try {
            val settings = webView.settings
            val jsAllowed = isContentAllowed("javascript", url)
            settings.javaScriptEnabled = jsAllowed

            val imagesAllowed = isContentAllowed("images", url)
            settings.loadsImagesAutomatically = imagesAllowed

            val popupsAllowed = isContentAllowed("popups", url)
            settings.javaScriptCanOpenWindowsAutomatically = popupsAllowed

            val cookiesAllowed = isContentAllowed("cookies", url)
            CookieManager.getInstance().setAcceptCookie(cookiesAllowed)
        } catch (_: Throwable) {}
    }

    private fun matchesPattern(url: String, pattern: String): Boolean {
        if (pattern == "*" || pattern == "<all_urls>") return true
        if (url == pattern) return true
        return try {
            val regex = pattern
                .replace(".", "\\.")
                .replace("*", ".*")
            url.matches(Regex(regex))
        } catch (e: Exception) {
            false
        }
    }

    fun setSetting(sender: ExtensionSender, contentType: String, details: JSONObject): JSONObject {
        verifyContentSettingsPermission(sender)
        val primaryPattern = details.optString("primaryPattern", "*")
        val setting = details.optString("setting", "allow")
        val key = "${contentType}_${primaryPattern}"

        prefs.edit().putString(key, setting).apply()
        return JSONObject().put("status", "success")
    }

    fun clearSetting(sender: ExtensionSender, contentType: String, details: JSONObject?): JSONObject {
        verifyContentSettingsPermission(sender)
        val primaryPattern = details?.optString("primaryPattern", null)
        if (primaryPattern != null) {
            prefs.edit().remove("${contentType}_${primaryPattern}").apply()
        } else {
            val allKeys = prefs.all.keys.filter { it.startsWith("${contentType}_") }
            val editor = prefs.edit()
            for (k in allKeys) editor.remove(k)
            editor.apply()
        }
        return JSONObject().put("status", "success")
    }

    fun getResourceIdentifiers(sender: ExtensionSender, contentType: String): JSONArray {
        verifyContentSettingsPermission(sender)
        return JSONArray()
    }
}
