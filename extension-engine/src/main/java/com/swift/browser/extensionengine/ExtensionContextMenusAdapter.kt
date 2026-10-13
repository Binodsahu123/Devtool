package com.swift.browser.extensionengine

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

data class ContextMenuItem(
    val id: String,
    val extensionId: String,
    var title: String,
    var contexts: List<String>,
    var type: String,
    var checked: Boolean,
    var parentId: String?,
    var targetUrlPatterns: List<String>,
    var documentUrlPatterns: List<String> = emptyList(),
    var enabled: Boolean,
    var visible: Boolean = true
)

class ExtensionContextMenusAdapter(
    private val permissionManager: PermissionManager,
    private val registry: ExtensionRegistry,
    private val eventManager: EventManager
) {
    companion object {
        val itemsMap = ConcurrentHashMap<String, ContextMenuItem>() // key: "extId_itemId"
        private val autoIdGenerator = AtomicInteger(1)

        fun cleanupExtensionState(extensionId: String) {
            val keysToRemove = itemsMap.keys().toList().filter { it.startsWith("${extensionId}_") }
            for (k in keysToRemove) {
                itemsMap.remove(k)
            }
        }

        fun getMatchingItems(
            isImage: Boolean = false,
            isImageLink: Boolean = false,
            hasLink: Boolean = false,
            hasSelection: Boolean = false,
            isEditable: Boolean = false,
            isVideo: Boolean = false,
            isAudio: Boolean = false,
            isFrame: Boolean = false,
            pageUrl: String = "",
            targetUrl: String = ""
        ): List<ContextMenuItem> {
            val activeContexts = mutableSetOf("all", "page")
            if (isImage || isImageLink) activeContexts.add("image")
            if (hasLink || isImageLink) activeContexts.add("link")
            if (hasSelection) activeContexts.add("selection")
            if (isEditable) activeContexts.add("editable")
            if (isVideo) activeContexts.add("video")
            if (isAudio) activeContexts.add("audio")
            if (isFrame) activeContexts.add("frame")

            return itemsMap.values.filter { item ->
                item.enabled && item.visible && item.contexts.any { activeContexts.contains(it) } &&
                    (item.documentUrlPatterns.isEmpty() || ExtensionMatchPattern.matchesAny(pageUrl, item.documentUrlPatterns)) &&
                    (item.targetUrlPatterns.isEmpty() || ExtensionMatchPattern.matchesAny(targetUrl.ifBlank { pageUrl }, item.targetUrlPatterns))
            }.sortedBy { it.id }
        }
    }

    private fun validate(sender: ExtensionSender): ParsedExtension =
        ExtensionSenderAccessValidator.validate(
            sender = sender,
            registry = registry,
            permissionManager = permissionManager,
            apiPermission = "contextMenus",
            useSecurityException = false
        )

    fun create(sender: ExtensionSender, createProperties: JSONObject): JSONObject {
        val ext = validate(sender)
        
        val itemId = if (createProperties.has("id")) {
            createProperties.getString("id")
        } else {
            "item_${autoIdGenerator.getAndIncrement()}"
        }

        val key = "${ext.id}_$itemId"
        if (itemsMap.containsKey(key)) {
            throw IllegalArgumentException("CONTEXT_MENU_DUPLICATE_ID")
        }

        val title = createProperties.optString("title", "")
        val type = createProperties.optString("type", "normal")
        val checked = createProperties.optBoolean("checked", false)
        val parentId = if (createProperties.has("parentId")) createProperties.getString("parentId") else null
        val enabled = createProperties.optBoolean("enabled", true)
        val visible = createProperties.optBoolean("visible", true)

        val supportedTypes = listOf("normal", "checkbox", "radio", "separator")
        if (type.isNotBlank() && !supportedTypes.contains(type.lowercase())) {
            throw IllegalArgumentException("CONTEXT_MENU_UNSUPPORTED")
        }

        if (parentId != null) {
            val parentKey = "${ext.id}_$parentId"
            if (!itemsMap.containsKey(parentKey)) {
                throw IllegalArgumentException("CONTEXT_MENU_NOT_FOUND")
            }
        }

        val contextsArr = createProperties.optJSONArray("contexts")
        val contexts = ArrayList<String>()
        val validContexts = listOf("all", "page", "selection", "link", "editable", "image", "video", "audio", "frame")
        if (contextsArr != null) {
            for (i in 0 until contextsArr.length()) {
                val ctx = contextsArr.getString(i).lowercase()
                if (!validContexts.contains(ctx)) {
                    throw IllegalArgumentException("CONTEXT_MENU_CONTEXT_UNAVAILABLE")
                }
                contexts.add(ctx)
            }
        } else {
            contexts.add("page")
        }

        val patterns = readPatterns(createProperties.optJSONArray("targetUrlPatterns"))
        val documentPatterns = readPatterns(createProperties.optJSONArray("documentUrlPatterns"))

        val item = ContextMenuItem(
            id = itemId,
            extensionId = ext.id,
            title = title,
            contexts = contexts,
            type = type,
            checked = checked,
            parentId = parentId,
            targetUrlPatterns = patterns,
            documentUrlPatterns = documentPatterns,
            enabled = enabled,
            visible = visible
        )
        itemsMap[key] = item
        return JSONObject().put("status", "success").put("id", itemId)
    }

    fun update(sender: ExtensionSender, id: String, updateProperties: JSONObject): JSONObject {
        val ext = validate(sender)
        val key = "${ext.id}_$id"
        val existing = itemsMap[key] ?: throw IllegalArgumentException("CONTEXT_MENU_NOT_FOUND")

        if (updateProperties.has("title")) existing.title = updateProperties.getString("title")
        if (updateProperties.has("type")) {
            val t = updateProperties.getString("type")
            val supportedTypes = listOf("normal", "checkbox", "radio", "separator")
            if (!supportedTypes.contains(t.lowercase())) {
                throw IllegalArgumentException("CONTEXT_MENU_UNSUPPORTED")
            }
            existing.type = t
        }
        if (updateProperties.has("checked")) existing.checked = updateProperties.getBoolean("checked")
        if (updateProperties.has("enabled")) existing.enabled = updateProperties.getBoolean("enabled")
        if (updateProperties.has("visible")) existing.visible = updateProperties.getBoolean("visible")
        if (updateProperties.has("targetUrlPatterns")) {
            existing.targetUrlPatterns = readPatterns(updateProperties.optJSONArray("targetUrlPatterns"))
        }
        if (updateProperties.has("documentUrlPatterns")) {
            existing.documentUrlPatterns = readPatterns(updateProperties.optJSONArray("documentUrlPatterns"))
        }

        if (updateProperties.has("parentId")) {
            val pId = updateProperties.optString("parentId", null)
            if (pId != null) {
                val parentKey = "${ext.id}_$pId"
                if (!itemsMap.containsKey(parentKey)) {
                    throw IllegalArgumentException("CONTEXT_MENU_NOT_FOUND")
                }
            }
            existing.parentId = pId
        }

        return JSONObject().put("status", "success")
    }

    fun remove(sender: ExtensionSender, menuItemId: String): JSONObject {
        val ext = validate(sender)
        val key = "${ext.id}_$menuItemId"
        if (!itemsMap.containsKey(key)) {
            throw IllegalArgumentException("CONTEXT_MENU_NOT_FOUND")
        }
        itemsMap.remove(key)
        return JSONObject().put("status", "success")
    }

    fun removeAll(sender: ExtensionSender): JSONObject {
        val ext = validate(sender)
        val keysToRemove = itemsMap.keys().toList().filter { it.startsWith("${ext.id}_") }
        for (k in keysToRemove) {
            itemsMap.remove(k)
        }
        return JSONObject().put("status", "success")
    }

    private fun readPatterns(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val pattern = array.optString(i, "").trim()
                if (pattern.isBlank()) throw IllegalArgumentException("CONTEXT_MENU_PATTERN_INVALID")
                ExtensionMatchPattern(pattern)
                add(pattern)
            }
        }.distinct()
    }

    fun triggerClick(
        extensionId: String,
        menuItemId: String,
        pageUrl: String? = null,
        tabId: String? = null,
        selectionText: String? = null,
        linkUrl: String? = null,
        srcUrl: String? = null,
        frameUrl: String? = null,
        isEditable: Boolean = false,
        mediaType: String? = null,
        frameId: Int = 0,
        documentId: String? = null
    ) {
        val ext = registry.getExtension(extensionId) ?: return
        if (!registry.isExtensionEnabled(extensionId)) return

        val itemKey = "${extensionId}_$menuItemId"
        val item = itemsMap[itemKey]

        var wasChecked = false
        var isChecked = false
        if (item != null) {
            wasChecked = item.checked
            if (item.type == "checkbox") {
                item.checked = !item.checked
                isChecked = item.checked
            } else if (item.type == "radio") {
                itemsMap.values.filter { 
                    it.extensionId == extensionId && it.parentId == item.parentId && it.type == "radio" && it.id != item.id 
                }.forEach {
                    it.checked = false
                }
                item.checked = true
                isChecked = true
            } else {
                isChecked = item.checked
            }
        }

        val info = JSONObject().apply {
            put("menuItemId", menuItemId)
            put("parentMenuItemId", item?.parentId ?: JSONObject.NULL)
            put("pageUrl", pageUrl ?: JSONObject.NULL)
            put("selectionText", selectionText ?: JSONObject.NULL)
            put("linkUrl", linkUrl ?: JSONObject.NULL)
            put("srcUrl", srcUrl ?: JSONObject.NULL)
            put("frameUrl", frameUrl ?: JSONObject.NULL)
            put("editable", isEditable)
            put("mediaType", mediaType ?: JSONObject.NULL)
            put("frameId", frameId)
            put("documentId", documentId ?: JSONObject.NULL)
            if (item?.type == "checkbox" || item?.type == "radio") {
                put("checked", isChecked)
                put("wasChecked", wasChecked)
            }
        }

        val tab = JSONObject().apply {
            val intId = if (!tabId.isNullOrBlank()) TabIdMapper.getIntId(tabId) else JSONObject.NULL
            put("id", intId)
            pageUrl?.let { put("url", it) }
        }

        eventManager.triggerEvent("contextMenus.onClicked", JSONObject().apply {
            put("info", info)
            put("tab", tab)
        })
    }
}
