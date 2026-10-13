package com.swift.browser.extensionengine

import org.json.JSONObject

class ExtensionSearchAdapter(
    private val permissionManager: PermissionManager,
    private val registry: ExtensionRegistry
) {
    private fun validate(sender: ExtensionSender): ParsedExtension =
        ExtensionSenderAccessValidator.validate(
            sender = sender,
            registry = registry,
            permissionManager = permissionManager,
            apiPermission = "search",
            useSecurityException = true
        )

    fun query(sender: ExtensionSender, options: JSONObject, delegate: BrowserDelegate?): JSONObject {
        validate(sender)
        val text = options.optString("text", "")
        val disposition = options.optString("disposition", "CURRENT_TAB")
        val tabId = if (options.has("tabId")) options.optString("tabId") else sender.tabId

        if (text.isBlank()) {
            throw IllegalArgumentException("Query text cannot be blank")
        }

        val searchUrl = "https://www.google.com/search?q=${android.net.Uri.encode(text)}"

        when (disposition.uppercase()) {
            "NEW_TAB" -> delegate?.createTab(searchUrl, true)
            "NEW_WINDOW" -> delegate?.createTab(searchUrl, true)
            else -> {
                if (tabId != null) {
                    delegate?.updateTab(tabId, searchUrl)
                } else {
                    delegate?.createTab(searchUrl, true)
                }
            }
        }

        return JSONObject().apply {
            put("status", "success")
            put("query", text)
            put("url", searchUrl)
        }
    }
}
