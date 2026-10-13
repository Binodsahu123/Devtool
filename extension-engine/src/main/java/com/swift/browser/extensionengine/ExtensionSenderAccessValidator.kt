package com.swift.browser.extensionengine

/**
 * Canonical authorization gate shared by extension API adapters.
 *
 * Keeps extension lookup, enablement, private-mode gating and optional API-permission
 * checks in one place. Adapters retain their historical exception style through
 * [useSecurityException] so behavior does not silently change while duplicate logic
 * disappears from every API adapter.
 */
object ExtensionSenderAccessValidator {

    fun validate(
        sender: ExtensionSender,
        registry: ExtensionRegistry,
        permissionManager: PermissionManager,
        apiPermission: String? = null,
        useSecurityException: Boolean = false
    ): ParsedExtension {
        val extId = sender.extensionId.trim().lowercase()

        val extension = registry.getExtension(extId)
            ?: fail(
                code = "EXTENSION_NOT_FOUND",
                message = "Extension $extId not found",
                useSecurityException = useSecurityException
            )

        if (!registry.isExtensionEnabled(extId)) {
            fail(
                code = "EXTENSION_DISABLED",
                message = "Extension $extId is disabled",
                useSecurityException = useSecurityException
            )
        }

        if (sender.isPrivate && !permissionManager.isAllowedInPrivate(extId)) {
            fail(
                code = "PRIVATE_MODE_DENIED",
                message = "Extension $extId is not allowed in private mode",
                useSecurityException = useSecurityException
            )
        }

        if (apiPermission != null && !permissionManager.hasApiPermission(extId, extension.permissions, apiPermission)) {
            throw SecurityException("SecurityError: Extension does not have '$apiPermission' permission")
        }

        return extension
    }

    private fun fail(
        code: String,
        message: String,
        useSecurityException: Boolean
    ): Nothing {
        if (useSecurityException) throw SecurityException(message)
        throw IllegalArgumentException(code)
    }
}
