package com.swift.browser.extensionengine

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

data class OmniboxSuggestion(
    val content: String,
    val description: String,
    val deletable: Boolean = false
)

class ExtensionOmniboxAdapter(
    private val permissionManager: PermissionManager,
    private val registry: ExtensionRegistry,
    private val eventManager: EventManager
) {
    companion object {
        val registeredKeywords = ConcurrentHashMap<String, String>() // keyword -> extensionId
        val defaultSuggestions = ConcurrentHashMap<String, String>() // extensionId -> description
        val activeSuggestions = ConcurrentHashMap<String, MutableList<OmniboxSuggestion>>() // extensionId -> list of suggestions
        private val _suggestionsVersion = kotlinx.coroutines.flow.MutableStateFlow(0)
        val suggestionsVersion: kotlinx.coroutines.flow.StateFlow<Int> = _suggestionsVersion

        fun notifySuggestionsChanged() {
            _suggestionsVersion.value = _suggestionsVersion.value + 1
        }

        fun cleanupExtensionState(extensionId: String) {
            defaultSuggestions.remove(extensionId)
            activeSuggestions.remove(extensionId)
            val keysToRemove = registeredKeywords.filterValues { it == extensionId }.keys
            for (k in keysToRemove) {
                registeredKeywords.remove(k)
            }
            notifySuggestionsChanged()
        }

        fun getSuggestionsForKeyword(keyword: String, text: String): List<OmniboxSuggestion> {
            val extId = registeredKeywords[keyword] ?: return emptyList()
            val result = mutableListOf<OmniboxSuggestion>()

            defaultSuggestions[extId]?.let { desc ->
                val formattedDesc = desc.replace("%s", text)
                result.add(OmniboxSuggestion(content = text, description = formattedDesc, deletable = false))
            }

            activeSuggestions[extId]?.let { list ->
                val matching = list.filter {
                    text.isBlank() || it.content.contains(text, ignoreCase = true) || it.description.contains(text, ignoreCase = true)
                }
                result.addAll(matching)
            }

            return result
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

    private fun getOmniboxKeyword(ext: ParsedExtension): String? {
        try {
            val root = JSONObject(ext.manifestJson)
            if (root.has("omnibox")) {
                val omniObj = root.getJSONObject("omnibox")
                return omniObj.optString("keyword", null)
            }
        } catch (e: Exception) {}
        return null
    }

    fun registerKeyword(sender: ExtensionSender): JSONObject {
        val ext = validate(sender)
        val keyword = getOmniboxKeyword(ext) ?: return JSONObject().put("status", "no_keyword")
        
        if (keyword.isBlank()) {
            throw IllegalArgumentException("OMNIBOX_INVALID")
        }
        // Check alphanumeric
        if (!keyword.matches(Regex("^[a-zA-Z0-9]+$"))) {
            throw IllegalArgumentException("OMNIBOX_INVALID")
        }

        val existingOwner = registeredKeywords[keyword]
        if (existingOwner != null && existingOwner != ext.id) {
            throw IllegalArgumentException("OMNIBOX_KEYWORD_CONFLICT")
        }

        registeredKeywords[keyword] = ext.id
        return JSONObject().put("status", "success").put("keyword", keyword)
    }

    fun setDefaultSuggestion(sender: ExtensionSender, details: JSONObject): JSONObject {
        val ext = validate(sender)
        val description = details.optString("description", "")
        if (description.isBlank()) {
            throw IllegalArgumentException("OMNIBOX_INVALID")
        }
        defaultSuggestions[ext.id] = description
        notifySuggestionsChanged()
        return JSONObject().put("status", "success")
    }

    fun suggest(sender: ExtensionSender, callbackId: String, suggestions: JSONArray): JSONObject {
        val ext = validate(sender)
        val list = mutableListOf<OmniboxSuggestion>()
        for (i in 0 until suggestions.length()) {
            val s = suggestions.getJSONObject(i)
            if (!s.has("content") || !s.has("description")) {
                throw IllegalArgumentException("OMNIBOX_INVALID")
            }
            list.add(
                OmniboxSuggestion(
                    content = s.getString("content"),
                    description = s.getString("description"),
                    deletable = s.optBoolean("deletable", false)
                )
            )
        }
        activeSuggestions[ext.id] = list
        notifySuggestionsChanged()
        return JSONObject().put("status", "success").put("count", list.size)
    }

    fun deleteSuggestion(sender: ExtensionSender, details: JSONObject): JSONObject {
        val ext = validate(sender)
        val text = details.optString("text", "")
        activeSuggestions[ext.id]?.removeAll { it.content == text }
        notifySuggestionsChanged()
        val obj = JSONObject().apply {
            put("text", text)
        }
        eventManager.triggerEvent("omnibox.onDeleteSuggestion", obj)
        return JSONObject().put("status", "success")
    }

    // Event Trigger flows
    fun triggerInputStarted(extensionId: String) {
        val ext = registry.getExtension(extensionId) ?: return
        if (!registry.isExtensionEnabled(extensionId)) return
        eventManager.triggerEvent("omnibox.onInputStarted", JSONObject())
    }

    fun triggerInputChanged(extensionId: String, text: String) {
        val ext = registry.getExtension(extensionId) ?: return
        if (!registry.isExtensionEnabled(extensionId)) return
        val obj = JSONObject().apply {
            put("text", text)
        }
        eventManager.triggerEvent("omnibox.onInputChanged", obj)
    }

    fun triggerInputEntered(extensionId: String, text: String, disposition: String) {
        val ext = registry.getExtension(extensionId) ?: return
        if (!registry.isExtensionEnabled(extensionId)) return
        val obj = JSONObject().apply {
            put("text", text)
            put("disposition", disposition)
        }
        eventManager.triggerEvent("omnibox.onInputEntered", obj)
    }

    fun triggerInputCancelled(extensionId: String) {
        val ext = registry.getExtension(extensionId) ?: return
        if (!registry.isExtensionEnabled(extensionId)) return
        eventManager.triggerEvent("omnibox.onInputCancelled", JSONObject())
    }
}
