package com.swift.browser.extensionengine

object ExtensionMessagingValidator {

    sealed class ValidationResult {
        object Allowed : ValidationResult()
        data class Denied(val reason: String) : ValidationResult()
    }

    fun validateSender(
        sender: ExtensionSender,
        registry: ExtensionRegistry,
        permissionManager: PermissionManager
    ): ValidationResult {
        val cleanId = sender.extensionId.lowercase().trim()
        if (cleanId.isBlank()) {
            return ValidationResult.Denied("Blank extension ID")
        }

        // Web pages cannot claim extension privileges or impersonate an extension sender
        if (sender.contextType == ExtensionContextType.WEB_PAGE) {
            val url = sender.url ?: ""
            if (!com.swift.browser.extensionengine.origin.ExtensionUrl.isExtensionUrl(url) && !url.startsWith("sw-extension://")) {
                return ValidationResult.Denied("Web page ($url) cannot impersonate extension sender '$cleanId'")
            }
        }

        val ext = registry.getExtension(cleanId)
            ?: return ValidationResult.Denied("Extension '$cleanId' is not registered")

        if (!registry.isExtensionEnabled(cleanId)) {
            return ValidationResult.Denied("Extension '$cleanId' is disabled")
        }

        if (sender.isPrivate && !permissionManager.isAllowedInPrivate(cleanId)) {
            return ValidationResult.Denied("Extension '$cleanId' is not allowed in private mode")
        }

        // Content scripts running on untrusted pages must have valid host permissions
        if (sender.contextType == ExtensionContextType.CONTENT_SCRIPT && !sender.url.isNull_or_blank()) {
            val pageUrl = sender.url
            if (!permissionManager.hasHostPermission(cleanId, ext.hostPermissions, ext.permissions, pageUrl)) {
                return ValidationResult.Denied("Content script lacks host permission for page: $pageUrl")
            }
        }

        return ValidationResult.Allowed
    }

    fun validateCrossExtension(
        sender: ExtensionSender,
        targetExtensionId: String,
        registry: ExtensionRegistry,
        permissionManager: PermissionManager
    ): ValidationResult {
        val targetClean = targetExtensionId.lowercase().trim()
        val targetExt = registry.getExtension(targetClean)
            ?: return ValidationResult.Denied("Target extension '$targetClean' not found")

        if (!registry.isExtensionEnabled(targetClean)) {
            return ValidationResult.Denied("Target extension '$targetClean' is disabled")
        }

        if (sender.isPrivate && !permissionManager.isAllowedInPrivate(targetClean)) {
            return ValidationResult.Denied("Target extension '$targetClean' is not allowed in private mode")
        }

        if (sender.contextType == ExtensionContextType.WEB_PAGE) {
            val senderUrl = sender.url ?: sender.origin ?: ""
            if (senderUrl.isBlank()) {
                return ValidationResult.Denied("Web page sender URL is missing")
            }
            val matches = targetExt.externallyConnectable.matches
            if (matches.isNotEmpty() && ExtensionMatchPattern.matchesAny(senderUrl, matches)) {
                return ValidationResult.Allowed
            }
            return ValidationResult.Denied("Untrusted web page '$senderUrl' is not allowed in externally_connectable for '$targetClean'")
        }

        val senderVal = validateSender(sender, registry, permissionManager)
        if (senderVal is ValidationResult.Denied) return senderVal

        // If target extension is different from sender, check if cross-extension messaging is permitted
        if (!targetClean.equals(sender.extensionId, ignoreCase = true)) {
            val allowedIds = targetExt.externallyConnectable.ids
            if (allowedIds.isNotEmpty()) {
                val matchesId = allowedIds.any { it == "*" || it.equals(sender.extensionId, ignoreCase = true) }
                if (!matchesId) {
                    return ValidationResult.Denied("Extension '${sender.extensionId}' is not listed in externally_connectable.ids for '$targetClean'")
                }
            }
        }

        return ValidationResult.Allowed
    }
}

private fun String?.isNull_or_blank(): Boolean = this == null || this.isBlank()
