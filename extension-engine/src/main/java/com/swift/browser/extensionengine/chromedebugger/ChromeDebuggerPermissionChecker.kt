package com.swift.browser.extensionengine.chromedebugger

import com.swift.browser.extensionengine.ExtensionPermissionAdapter
import com.swift.browser.extensionengine.ExtensionRegistry
import com.swift.browser.extensionengine.PermissionManager
import org.json.JSONObject

/**
 * Validates 'debugger' permission and security boundaries for chrome.debugger API.
 * Delegates to canonical Permission Engine and ExtensionRegistry.
 */
class ChromeDebuggerPermissionChecker(
    private val permissionManager: PermissionManager,
    private val permissionAdapter: ExtensionPermissionAdapter,
    private val registry: ExtensionRegistry
) {

    /**
     * Verifies that the extension has explicit 'debugger' permission in its manifest or granted permissions.
     */
    fun verifyDebuggerPermission(extensionId: String) {
        val ext = registry.getExtension(extensionId)
            ?: throw SecurityException(ChromeDebuggerErrors.ERROR_NO_DEBUGGER_PERMISSION)

        if (!registry.isExtensionEnabled(extensionId)) {
            throw SecurityException("SecurityError: Extension $extensionId is disabled")
        }

        // Check canonical PermissionAdapter
        if (permissionAdapter.hasApiPermission(extensionId, "debugger")) {
            return
        }

        // Check manifest permissions array directly as authoritative fallback
        val hasManifestPerm = try {
            val manifest = JSONObject(ext.manifestJson)
            val pArr = manifest.optJSONArray("permissions")
            var found = false
            if (pArr != null) {
                for (i in 0 until pArr.length()) {
                    if (pArr.optString(i, "").equals("debugger", ignoreCase = true)) {
                        found = true
                        break
                    }
                }
            }
            if (!found) {
                val optArr = manifest.optJSONArray("optional_permissions")
                if (optArr != null) {
                    for (i in 0 until optArr.length()) {
                        if (optArr.optString(i, "").equals("debugger", ignoreCase = true)) {
                            found = permissionAdapter.hasApiPermission(extensionId, "debugger")
                            break
                        }
                    }
                }
            }
            found
        } catch (_: Exception) {
            false
        }

        if (!hasManifestPerm) {
            throw SecurityException(ChromeDebuggerErrors.ERROR_NO_DEBUGGER_PERMISSION)
        }
    }

    /**
     * Verifies target URL restriction rules (e.g. Chrome Web Store, internal settings, sensitive schemes).
     */
    fun isTargetAttachable(extensionId: String, targetUrl: String, isPrivateTab: Boolean): Boolean {
        if (isPrivateTab && !permissionManager.isAllowedInPrivate(extensionId)) {
            return false
        }

        val urlClean = targetUrl.trim().lowercase()

        // Sensitive browser internal schemes that extensions cannot attach to
        if (urlClean.startsWith("swift://settings") ||
            urlClean.startsWith("swift://flags") ||
            urlClean.startsWith("chrome://settings") ||
            urlClean.startsWith("chrome://flags") ||
            urlClean.startsWith("devtools://")
        ) {
            return false
        }

        // Extension pages of other extensions: only allow if extension has host permissions or debugger
        if (urlClean.startsWith("chrome-extension://")) {
            val targetExtId = urlClean.removePrefix("chrome-extension://").substringBefore("/")
            if (targetExtId != extensionId.lowercase()) {
                // Cannot attach to another extension unless specifically allowed
                if (!permissionAdapter.hasHostPermission(extensionId, "chrome-extension://$targetExtId/*")) {
                    return false
                }
            }
        }

        return true
    }
}
