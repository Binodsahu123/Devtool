package com.swift.browser.extensionengine

import android.content.Context
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

data class SidePanelOptions(
    var path: String = "",
    var enabled: Boolean = true,
    var tabId: String? = null
)

class ExtensionSidePanelAdapter(
    private val permissionManager: PermissionManager,
    private val registry: ExtensionRegistry,
    private val context: Context? = null
) {
    companion object {
        val globalOptions = ConcurrentHashMap<String, SidePanelOptions>()
        val tabOptions = ConcurrentHashMap<String, SidePanelOptions>() // "extId_tabId"
        val panelBehaviors = ConcurrentHashMap<String, JSONObject>()

        fun cleanupTabState(tabId: String) {
            val keysToRemove = tabOptions.keys().toList().filter { it.endsWith("_$tabId") }
            for (k in keysToRemove) {
                tabOptions.remove(k)
            }
        }

        fun cleanupExtensionState(extensionId: String) {
            globalOptions.remove(extensionId)
            panelBehaviors.remove(extensionId)
            val keysToRemove = tabOptions.keys().toList().filter { it.startsWith("${extensionId}_") }
            for (k in keysToRemove) {
                tabOptions.remove(k)
            }
        }
    }

    private fun validate(sender: ExtensionSender): ParsedExtension =
        ExtensionSenderAccessValidator.validate(
            sender = sender,
            registry = registry,
            permissionManager = permissionManager,
            apiPermission = "sidePanel",
            useSecurityException = false
        )

    private fun validateResourcePath(context: Context?, ext: ParsedExtension, path: String): String {
        if (path.startsWith("file://") || path.contains("..")) {
            throw IllegalArgumentException("SecurityError: Unsafe path or file:// schema is forbidden")
        }
        val cleanPath = path.removePrefix("/").removePrefix("./")
        if (!PathSanitizer.isSafeRelativePath(cleanPath)) {
            throw IllegalArgumentException("SecurityError: Path traversal or unsafe path detected")
        }
        if (context != null) {
            val extensionDir = ExtensionDirectoryResolver.getExtensionDir(context, ext.id)
            val file = ExtensionDirectoryResolver.findFileCaseInsensitive(extensionDir, cleanPath)
            if (file == null || !file.exists() || !file.isFile) {
                throw IllegalArgumentException("SIDEPANEL_RESOURCE_INVALID")
            }
        }
        return cleanPath
    }

    fun setOptions(sender: ExtensionSender, options: JSONObject, context: Context? = null): JSONObject {
        val ext = validate(sender)
        val path = options.optString("path", "")
        val enabled = options.optBoolean("enabled", true)
        val tabId = if (options.has("tabId")) options.optString("tabId") else null

        if (tabId != null && tabId.isBlank()) {
            throw IllegalArgumentException("SIDEPANEL_SCOPE_UNSUPPORTED")
        }

        if (path.isNotBlank()) {
            validateResourcePath(context, ext, path)
        }

        val opts = SidePanelOptions(path = path, enabled = enabled, tabId = tabId)
        if (tabId != null) {
            tabOptions["${ext.id}_$tabId"] = opts
        } else {
            globalOptions[ext.id] = opts
        }
        return JSONObject().put("status", "success").put("path", path)
    }

    fun getOptions(sender: ExtensionSender, details: JSONObject): JSONObject {
        val ext = validate(sender)
        val tabId = if (details.has("tabId")) details.optString("tabId") else null
        if (tabId != null && tabId.isBlank()) {
            throw IllegalArgumentException("SIDEPANEL_SCOPE_UNSUPPORTED")
        }
        val opts = if (tabId != null && tabOptions.containsKey("${ext.id}_$tabId")) {
            tabOptions["${ext.id}_$tabId"]!!
        } else {
            globalOptions.getOrDefault(ext.id, SidePanelOptions())
        }
        return JSONObject().apply {
            put("path", opts.path)
            put("enabled", opts.enabled)
            opts.tabId?.let { put("tabId", it) }
        }
    }

    fun setPanelBehavior(sender: ExtensionSender, behavior: JSONObject): JSONObject {
        val ext = validate(sender)
        panelBehaviors[ext.id] = behavior
        return JSONObject().put("status", "success")
    }

    fun getPanelBehavior(sender: ExtensionSender): JSONObject {
        val ext = validate(sender)
        return panelBehaviors.getOrDefault(ext.id, JSONObject().put("openPanelOnActionClick", false))
    }

    fun open(sender: ExtensionSender, options: JSONObject, context: Context? = null): JSONObject {
        val ext = validate(sender)
        val optTab = if (options.has("tabId")) {
            options.opt("tabId")?.let {
                if (it == JSONObject.NULL) null else it.toString().takeIf { s -> s.isNotBlank() }
            }
        } else null
        val tabId = optTab ?: sender.tabId ?: (if (context != null) {
            try { ExtensionEngineApi.getInstance(context).getActiveTabId() } catch (_: Throwable) { null }
        } else null)
        val path = options.optString("path", "")

        if (path.isNotBlank() && context != null) {
            validateResourcePath(context, ext, path)
            setOptions(sender, JSONObject().put("path", path).put("tabId", tabId), context)
        }

        val hostContext = context ?: throw UnsupportedOperationException("SIDEPANEL_ANDROID_HOST_UNAVAILABLE")
        val surface = ExtensionSurfaceResolver.resolveSidePanelSurface(hostContext, ext, tabId)
        if (surface.surfaceType != ExtensionSurfaceType.SIDE_PANEL) {
            throw UnsupportedOperationException("SIDEPANEL_ANDROID_HOST_UNAVAILABLE")
        }
        ExtensionEngineApi.getInstance(hostContext).openSidePanel(ext.id, tabId)
        return JSONObject().put("status", "success").put("path", surface.relativePath).put("opened", true)
    }
}
