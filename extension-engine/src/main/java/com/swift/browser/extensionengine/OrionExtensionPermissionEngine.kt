package com.swift.browser.extensionengine

import android.content.Context
import android.Manifest
import com.swift.browser.permissionengine.AndroidRuntimePermissionManager

data class ExtensionPermissionInfo(
    val name: String,
    val description: String,
    val riskLevel: String, // "Low", "Medium", "High"
    val requiredAndroidPermission: String? = null
)

interface ExtensionPermissionPromptHandler {
    fun showPrompt(extId: String, extName: String, permission: String, onResult: (String) -> Unit)
}

object SwiftExtensionPermissionEngine {
    var promptHandler: ExtensionPermissionPromptHandler? = null
    
    val permissionsCatalog = mapOf(
        "tabs" to ExtensionPermissionInfo("Tabs Access", "Access browser tabs, URLs, and page titles.", "Medium"),
        "activeTab" to ExtensionPermissionInfo("Active Tab", "Access the active tab context temporarily.", "Low"),
        "storage" to ExtensionPermissionInfo("Local Storage", "Store data locally in an isolated sandbox.", "Low"),
        "downloads" to ExtensionPermissionInfo("Downloads Manager", "Initiate and manage file downloads.", "Medium"),
        "notifications" to ExtensionPermissionInfo("Notifications", "Show push notifications to the user.", "Low"),
        "clipboard" to ExtensionPermissionInfo("Clipboard Access", "Read and write data to the system clipboard.", "Medium"),
        "microphone" to ExtensionPermissionInfo("Microphone Access", "Record audio from your device microphone.", "High", Manifest.permission.RECORD_AUDIO),
        "camera" to ExtensionPermissionInfo("Camera Access", "Take pictures and record videos with your camera.", "High", Manifest.permission.CAMERA),
        "location" to ExtensionPermissionInfo("Location Tracking", "Access your physical location using GPS.", "High", Manifest.permission.ACCESS_FINE_LOCATION),
        "scripting" to ExtensionPermissionInfo("Scripting Engine", "Inject custom JavaScript and CSS into websites.", "High"),
        "runtime" to ExtensionPermissionInfo("Runtime Context", "Manage background tasks and messages.", "Low"),
        "background" to ExtensionPermissionInfo("Background Agent", "Run background scripts while browser is open.", "Medium"),
        "cookies" to ExtensionPermissionInfo("Cookies Manager", "Read, modify, or block website cookies.", "High"),
        "webRequest" to ExtensionPermissionInfo("Network Interceptor", "Intercept, inspect, and modify network requests.", "High"),
        "bookmarks" to ExtensionPermissionInfo("Bookmarks", "Read, create, or edit bookmarks.", "Medium"),
        "history" to ExtensionPermissionInfo("Browsing History", "Read, clean, or search browsing history.", "High"),
        "file_access" to ExtensionPermissionInfo("File System Access", "Read or manage local workspace files.", "High"),
        "host_permissions" to ExtensionPermissionInfo("Website Hosts Control", "Interact with and run scripts on specified websites.", "High")
    )

    private fun repository(context: Context) =
        com.swift.browser.permissionengine.PermissionEngineProvider.get(context)
            .getExtensionPermissionRepository()

    // Compatibility facade. Persisted extension decisions are owned by the canonical
    // permission-engine repository; this class no longer maintains a second SharedPreferences store.
    fun getPermissionDecision(context: Context, extId: String, permission: String): String =
        repository(context).getApiDecision(extId, permission, false)

    fun setPermissionDecision(context: Context, extId: String, permission: String, decision: String) {
        repository(context).setApiDecision(extId, permission, decision, false)
    }

    fun resetExtensionPermissions(context: Context, extId: String) {
        repository(context).removeAllForExtension(extId)
    }

    // Host decision compatibility facade backed by the canonical repository.
    fun getHostDecision(context: Context, extId: String, hostPattern: String): String =
        repository(context).getHostDecision(extId, hostPattern, false)

    fun setHostDecision(context: Context, extId: String, hostPattern: String, decision: String) {
        repository(context).setHostDecision(extId, hostPattern, decision, false)
    }

    // Reset permissions for extension
    // Compatibility checks are delegated to the canonical permission repository.
    fun isPermissionGranted(context: Context, extId: String, permission: String): Boolean {
        val decision = getPermissionDecision(context, extId, permission)
        if (decision == "BLOCK" || decision == "DEFAULT") return false
        val info = permissionsCatalog[permission]
        return info?.requiredAndroidPermission?.let {
            AndroidRuntimePermissionManager.hasPermission(context, it)
        } ?: true
    }

    fun isHostPermissionGranted(context: Context, extId: String, url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        return repository(context).hasHostPermission(extId, url, false)
    }

    fun matchesPattern(pattern: String, url: String): Boolean {
        val p = pattern.trim()
        if (p == "<all_urls>" || p == "*://*/*" || p == "*://*" || p == "*" || p == "http://*/*" || p == "https://*/*") {
            return true
        }
        return try {
            val glob = p
            var cleanGlob = glob
            val schemeRegex = when {
                cleanGlob.startsWith("*://") -> {
                    cleanGlob = cleanGlob.substring(4)
                    "https?://"
                }
                cleanGlob.startsWith("http://") -> {
                    cleanGlob = cleanGlob.substring(7)
                    "http://"
                }
                cleanGlob.startsWith("https://") -> {
                    cleanGlob = cleanGlob.substring(8)
                    "https://"
                }
                else -> "https?://"
            }
            val parts = cleanGlob.split("/", limit = 2)
            var host = parts[0]
            val path = if (parts.size > 1) parts[1] else "*"
            
            val escapedHost = if (host.startsWith("*.")) {
                val domain = host.substring(2).replace(".", "\\.")
                "(.*\\.)?$domain"
            } else {
                host.replace(".", "\\.").replace("*", ".*")
            }
            val escapedPath = path.replace(".", "\\.").replace("*", ".*").replace("?", "\\?")
            val fullRegex = "^$schemeRegex$escapedHost/$escapedPath$"
            val compiled = java.util.regex.Pattern.compile(fullRegex, java.util.regex.Pattern.CASE_INSENSITIVE)
            compiled.matcher(url).matches()
        } catch (e: Exception) {
            false
        }
    }
}
