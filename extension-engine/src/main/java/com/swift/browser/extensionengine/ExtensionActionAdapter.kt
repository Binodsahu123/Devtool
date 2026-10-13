package com.swift.browser.extensionengine

import android.content.Context
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

data class ExtensionActionState(
    val extensionId: String,
    var enabled: Boolean = true,
    var title: String = "",
    var popupPath: String = "",
    var badgeText: String = "",
    var badgeBackgroundColor: String = "",
    var badgeTextColor: String = "#FFFFFF",
    var icon: String = "",
    var visible: Boolean = true,
    var tabScope: String? = null,
    var windowScope: String? = null
)

class ExtensionActionAdapter(
    private val permissionManager: PermissionManager,
    private val registry: ExtensionRegistry,
    private val eventManager: EventManager,
    private val context: Context? = null
) {
    companion object {
        val globalActionStates = ConcurrentHashMap<String, ExtensionActionState>()
        val tabActionStates = ConcurrentHashMap<String, ExtensionActionState>() // key: "extId_tabId"

        fun cleanupTabState(tabId: String) {
            val keysToRemove = tabActionStates.keys().toList().filter { it.endsWith("_$tabId") }
            for (k in keysToRemove) {
                tabActionStates.remove(k)
            }
        }

        fun cleanupExtensionState(extensionId: String, ctx: Context? = null) {
            globalActionStates.remove(extensionId)
            val keysToRemove = tabActionStates.keys().toList().filter { it.startsWith("${extensionId}_") }
            for (k in keysToRemove) {
                tabActionStates.remove(k)
            }
            ctx?.getSharedPreferences("swift_extension_action_states", Context.MODE_PRIVATE)
                ?.edit()?.remove(extensionId)?.apply()
        }
    }

    private fun persistGlobalState(extensionId: String, state: ExtensionActionState) {
        val ctx = context ?: return
        try {
            val json = JSONObject().apply {
                put("extensionId", state.extensionId)
                put("enabled", state.enabled)
                put("title", state.title)
                put("popupPath", state.popupPath)
                put("badgeText", state.badgeText)
                put("badgeBackgroundColor", state.badgeBackgroundColor)
                put("badgeTextColor", state.badgeTextColor)
                put("icon", state.icon)
                put("visible", state.visible)
            }
            ctx.getSharedPreferences("swift_extension_action_states", Context.MODE_PRIVATE)
                .edit()
                .putString(extensionId, json.toString())
                .apply()
        } catch (_: Exception) {}
    }

    private fun loadGlobalState(extensionId: String): ExtensionActionState? {
        val ctx = context ?: return null
        return try {
            val prefs = ctx.getSharedPreferences("swift_extension_action_states", Context.MODE_PRIVATE)
            val jsonStr = prefs.getString(extensionId, null) ?: return null
            val json = JSONObject(jsonStr)
            ExtensionActionState(
                extensionId = json.optString("extensionId", extensionId),
                enabled = json.optBoolean("enabled", true),
                title = json.optString("title", ""),
                popupPath = json.optString("popupPath", ""),
                badgeText = json.optString("badgeText", ""),
                badgeBackgroundColor = json.optString("badgeBackgroundColor", ""),
                badgeTextColor = json.optString("badgeTextColor", "#FFFFFF"),
                icon = json.optString("icon", ""),
                visible = json.optBoolean("visible", true)
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun validate(sender: ExtensionSender): ParsedExtension =
        ExtensionSenderAccessValidator.validate(
            sender = sender,
            registry = registry,
            permissionManager = permissionManager,
            apiPermission = null,
            useSecurityException = false
        )

    private fun validateResourcePath(context: Context, ext: ParsedExtension, path: String): String {
        if (path.startsWith("file://") || path.contains("..")) {
            throw IllegalArgumentException("SecurityError: Unsafe path or file:// schema is forbidden")
        }
        val cleanPath = path.removePrefix("/").removePrefix("./")
        if (!PathSanitizer.isSafeRelativePath(cleanPath)) {
            throw IllegalArgumentException("SecurityError: Path traversal or unsafe path detected")
        }
        val extensionDir = ExtensionDirectoryResolver.getExtensionDir(context, ext.id)
        val file = ExtensionDirectoryResolver.findFileCaseInsensitive(extensionDir, cleanPath)
        if (file == null || !file.exists() || !file.isFile) {
            throw IllegalArgumentException("RESOURCE_NOT_FOUND")
        }
        return cleanPath
    }

    private fun getStateKey(extensionId: String, tabId: String?): String? {
        return if (!tabId.isNullOrBlank()) "${extensionId}_$tabId" else null
    }

    private fun String?.isNullOrBlank(): Boolean = this == null || this.trim().isEmpty()

    private fun getOrCreateState(extensionId: String, tabId: String?): ExtensionActionState {
        val ext = registry.getExtension(extensionId)
        val defaultTitle = ext?.actionSpec?.defaultTitle?.takeIf { it.isNotBlank() } ?: ext?.name ?: ""
        val defaultPopup = ext?.actionSpec?.defaultPopup?.takeIf { it.isNotBlank() } ?: ext?.actionPopup ?: ""
        val defaultIcon = ext?.actionSpec?.defaultIconMap?.values?.firstOrNull()?.takeIf { it.isNotBlank() } ?: ext?.iconPath ?: ""

        val key = getStateKey(extensionId, tabId)
        val state = if (key != null) {
            tabActionStates.getOrPut(key) {
                val parentState = globalActionStates.getOrPut(extensionId) {
                    loadGlobalState(extensionId) ?: ExtensionActionState(
                        extensionId = extensionId,
                        title = defaultTitle,
                        popupPath = defaultPopup,
                        icon = defaultIcon
                    )
                }
                parentState.copy(tabScope = tabId)
            }
        } else {
            globalActionStates.getOrPut(extensionId) {
                loadGlobalState(extensionId) ?: ExtensionActionState(
                    extensionId = extensionId,
                    title = defaultTitle,
                    popupPath = defaultPopup,
                    icon = defaultIcon
                )
            }
        }

        // Self-heal defaults if state was initialized while extension or defaults were empty
        if (state.popupPath.isBlank() && defaultPopup.isNotBlank()) {
            state.popupPath = defaultPopup
        }
        if (state.title.isBlank() && defaultTitle.isNotBlank()) {
            state.title = defaultTitle
        }
        if (state.icon.isBlank() && defaultIcon.isNotBlank()) {
            state.icon = defaultIcon
        }

        return state
    }

    private fun maybePersist(extId: String, tabId: String?, state: ExtensionActionState) {
        if (tabId.isNullOrBlank()) {
            persistGlobalState(extId, state)
        }
    }

    fun setPopup(sender: ExtensionSender, details: JSONObject, context: Context? = null): JSONObject {
        val ext = validate(sender)
        val popup = details.optString("popup", "")
        if (popup.isNotBlank() && context != null) {
            try {
                validateResourcePath(context, ext, popup)
            } catch (e: IllegalArgumentException) {
                if (e.message == "RESOURCE_NOT_FOUND") {
                    throw IllegalArgumentException("ACTION_POPUP_INVALID")
                }
                throw e
            }
        }
        val tabId = if (details.has("tabId") && details.optString("tabId").isNotBlank()) details.optString("tabId") else null
        val state = getOrCreateState(ext.id, tabId)
        state.popupPath = popup
        maybePersist(ext.id, tabId, state)
        return JSONObject().put("status", "success").put("popup", popup)
    }

    fun getPopup(sender: ExtensionSender, details: JSONObject): JSONObject {
        val ext = validate(sender)
        val tabId = if (details.has("tabId") && details.optString("tabId").isNotBlank()) details.optString("tabId") else null
        val key = getStateKey(ext.id, tabId)
        val path = if (key != null && tabActionStates.containsKey(key)) {
            tabActionStates[key]?.popupPath ?: ext.actionPopup
        } else {
            getOrCreateState(ext.id, null).popupPath.takeIf { it.isNotBlank() } ?: ext.actionPopup
        }
        return JSONObject().put("popup", path)
    }

    fun setBadgeText(sender: ExtensionSender, details: JSONObject): JSONObject {
        val ext = validate(sender)
        val text = details.optString("text", "")
        val tabId = if (details.has("tabId") && details.optString("tabId").isNotBlank()) details.optString("tabId") else null
        val state = getOrCreateState(ext.id, tabId)
        state.badgeText = text
        maybePersist(ext.id, tabId, state)
        return JSONObject().put("status", "success").put("text", text)
    }

    fun getBadgeText(sender: ExtensionSender, details: JSONObject): JSONObject {
        val ext = validate(sender)
        val tabId = if (details.has("tabId") && details.optString("tabId").isNotBlank()) details.optString("tabId") else null
        val key = getStateKey(ext.id, tabId)
        val text = if (key != null && tabActionStates.containsKey(key)) {
            tabActionStates[key]?.badgeText ?: ""
        } else {
            getOrCreateState(ext.id, null).badgeText
        }
        return JSONObject().put("text", text)
    }

    fun setTitle(sender: ExtensionSender, details: JSONObject): JSONObject {
        val ext = validate(sender)
        val tabId = if (details.has("tabId") && details.optString("tabId").isNotBlank()) details.optString("tabId") else null
        val title = details.optString("title", "")
        val state = getOrCreateState(ext.id, tabId)
        state.title = title
        maybePersist(ext.id, tabId, state)
        return JSONObject().put("status", "success").put("title", title)
    }

    fun getTitle(sender: ExtensionSender, details: JSONObject): JSONObject {
        val ext = validate(sender)
        val tabId = if (details.has("tabId") && details.optString("tabId").isNotBlank()) details.optString("tabId") else null
        val key = getStateKey(ext.id, tabId)
        val title = if (key != null && tabActionStates.containsKey(key)) {
            tabActionStates[key]?.title ?: ext.name
        } else {
            getOrCreateState(ext.id, null).title.takeIf { it.isNotBlank() } ?: ext.name
        }
        return JSONObject().put("title", title)
    }

    fun setIcon(sender: ExtensionSender, details: JSONObject, context: Context? = null): JSONObject {
        val ext = validate(sender)
        if (details.has("imageData") || details.has("imageDataMap")) {
            throw IllegalArgumentException("ACTION_ICON_DATA_UNSUPPORTED")
        }
        val path = details.optString("path", details.optJSONObject("path")?.optString("16") ?: "")
        if (path.isNotBlank() && context != null) {
            try {
                validateResourcePath(context, ext, path)
            } catch (e: IllegalArgumentException) {
                if (e.message == "RESOURCE_NOT_FOUND") {
                    throw IllegalArgumentException("ACTION_ICON_INVALID")
                }
                throw e
            }
        }
        val tabId = if (details.has("tabId") && details.optString("tabId").isNotBlank()) details.optString("tabId") else null
        val state = getOrCreateState(ext.id, tabId)
        state.icon = path
        maybePersist(ext.id, tabId, state)
        return JSONObject().put("status", "success").put("path", path)
    }

    fun setBadgeBackgroundColor(sender: ExtensionSender, details: JSONObject): JSONObject {
        val ext = validate(sender)
        val color = details.optString("color", "")
        val tabId = if (details.has("tabId") && details.optString("tabId").isNotBlank()) details.optString("tabId") else null
        val state = getOrCreateState(ext.id, tabId)
        state.badgeBackgroundColor = color
        maybePersist(ext.id, tabId, state)
        return JSONObject().put("status", "success").put("color", color)
    }

    fun getBadgeBackgroundColor(sender: ExtensionSender, details: JSONObject): JSONObject {
        val ext = validate(sender)
        val tabId = if (details.has("tabId") && details.optString("tabId").isNotBlank()) details.optString("tabId") else null
        val key = getStateKey(ext.id, tabId)
        val color = if (key != null && tabActionStates.containsKey(key)) {
            tabActionStates[key]?.badgeBackgroundColor ?: ""
        } else {
            getOrCreateState(ext.id, null).badgeBackgroundColor
        }
        return JSONObject().put("color", color)
    }

    fun triggerOnClicked(extensionId: String, tabId: String? = null) {
        val ext = registry.getExtension(extensionId) ?: return
        if (!registry.isExtensionEnabled(extensionId)) return
        val tabObj = JSONObject().apply {
            if (!tabId.isNullOrBlank()) {
                val numericId = TabIdMapper.getIntId(tabId!!)
                put("id", numericId)
            }
            put("active", true)
        }
        val actionType = ext.actionSpec.actionType
        when (actionType) {
            "browser_action" -> eventManager.triggerEvent("browserAction.onClicked", tabObj)
            "page_action" -> eventManager.triggerEvent("pageAction.onClicked", tabObj)
            else -> eventManager.triggerEvent("action.onClicked", tabObj)
        }
    }

    fun enable(sender: ExtensionSender, details: JSONObject): JSONObject {
        val ext = validate(sender)
        val tabId = if (details.has("tabId") && details.optString("tabId").isNotBlank()) details.optString("tabId") else null
        val state = getOrCreateState(ext.id, tabId)
        state.enabled = true
        maybePersist(ext.id, tabId, state)
        return JSONObject().put("status", "success")
    }

    fun disable(sender: ExtensionSender, details: JSONObject): JSONObject {
        val ext = validate(sender)
        val tabId = if (details.has("tabId") && details.optString("tabId").isNotBlank()) details.optString("tabId") else null
        val state = getOrCreateState(ext.id, tabId)
        state.enabled = false
        maybePersist(ext.id, tabId, state)
        return JSONObject().put("status", "success")
    }

    fun openPopup(sender: ExtensionSender, options: JSONObject? = null, delegate: BrowserDelegate? = null): JSONObject {
        val ext = validate(sender)
        val tabId = options?.optString("tabId")?.takeIf { it.isNotBlank() }
        val key = getStateKey(ext.id, tabId)
        val tabState = if (key != null) tabActionStates[key] else null
        val globalState = getOrCreateState(ext.id, null)
        val popupPath = options?.optString("popup")?.takeIf { it.isNotBlank() }
            ?: tabState?.popupPath?.takeIf { it.isNotBlank() }
            ?: globalState.popupPath.takeIf { it.isNotBlank() }
            ?: ext.actionPopup.takeIf { it.isNotBlank() }
            ?: ext.popupPath.takeIf { it.isNotBlank() }
            ?: ext.actionSpec.defaultPopup.takeIf { it.isNotBlank() }
        if (popupPath.isNullOrBlank()) {
            throw IllegalArgumentException("ACTION_POPUP_NOT_FOUND")
        }
        val cleanPath = popupPath!!.removePrefix("/").removePrefix("./")
        val fullUrl = "chrome-extension://${ext.id}/$cleanPath"
        val host = context ?: throw UnsupportedOperationException("ACTION_ANDROID_HOST_UNAVAILABLE")
        ExtensionEngineApi.getInstance(host).openPopup(ext.id, tabId ?: sender.tabId)
        return JSONObject().put("status", "success").put("url", fullUrl).put("opened", true)
    }

    fun setBadgeTextColor(sender: ExtensionSender, details: JSONObject): JSONObject {
        val ext = validate(sender)
        val color = details.optString("color", "#FFFFFF")
        val tabId = if (details.has("tabId") && details.optString("tabId").isNotBlank()) details.optString("tabId") else null
        val state = getOrCreateState(ext.id, tabId)
        state.badgeTextColor = color
        maybePersist(ext.id, tabId, state)
        return JSONObject().put("status", "success").put("color", color)
    }

    fun getBadgeTextColor(sender: ExtensionSender, details: JSONObject): JSONObject {
        val ext = validate(sender)
        val tabId = if (details.has("tabId") && details.optString("tabId").isNotBlank()) details.optString("tabId") else null
        val key = getStateKey(ext.id, tabId)
        val color = if (key != null && tabActionStates.containsKey(key)) {
            tabActionStates[key]?.badgeTextColor ?: "#FFFFFF"
        } else {
            getOrCreateState(ext.id, null).badgeTextColor
        }
        return JSONObject().put("color", color)
    }

    fun isEnabled(sender: ExtensionSender, details: JSONObject): Boolean {
        val ext = validate(sender)
        val tabId = if (details.has("tabId") && details.optString("tabId").isNotBlank()) details.optString("tabId") else null
        val key = getStateKey(ext.id, tabId)
        return if (key != null && tabActionStates.containsKey(key)) {
            tabActionStates[key]?.enabled ?: true
        } else {
            getOrCreateState(ext.id, null).enabled
        }
    }

    fun getUserSettings(sender: ExtensionSender): JSONObject {
        validate(sender)
        return JSONObject().put("isOnToolbar", true)
    }
}
