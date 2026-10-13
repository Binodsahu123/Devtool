package com.swift.browser.extensionengine.origin

import com.swift.browser.extensionengine.PathSanitizer
import java.net.URLEncoder

/** Parsed result of a browser extension URL. */
data class ExtensionUrlResult(
    val extensionId: String,
    val resourcePath: String,
    val canonicalUrl: String,
    val isSandbox: Boolean = false,
    val isWebViewUrl: Boolean = false
)

/**
 * Canonical logical extension URL + physical WebView URL mapping.
 *
 * WebView cannot natively navigate to chrome-extension://, so visible extension
 * pages use a synthetic HTTPS origin while the extension API continues exposing
 * the logical chrome-extension:// URL to extension code.
 */
object ExtensionUrl {
    const val WEBVIEW_SCHEME = "https"
    const val WEBVIEW_HOST_SUFFIX = ".extension.orion.invalid"

    fun toExtensionUrl(extensionId: String, path: String): String {
        val cleanId = extensionId.lowercase().trim()
        val cleanPath = PathSanitizer.sanitizeRelativePath(path)
        return "${ExtensionOrigin.SCHEME_CHROME_EXTENSION}://$cleanId/$cleanPath"
    }

    fun toWebViewUrl(extensionId: String, path: String): String {
        val cleanId = extensionId.lowercase().trim()
        require(PathSanitizer.isSafeExtensionId(cleanId)) { "Invalid extension ID" }
        val cleanPath = PathSanitizer.sanitizeRelativePath(path)
        return "https://$cleanId$WEBVIEW_HOST_SUFFIX/$cleanPath"
    }

    fun toWebViewOrigin(extensionId: String): String {
        val cleanId = extensionId.lowercase().trim()
        require(PathSanitizer.isSafeExtensionId(cleanId)) { "Invalid extension ID" }
        return "https://$cleanId$WEBVIEW_HOST_SUFFIX"
    }

    fun isWebViewExtensionUrl(urlStr: String?): Boolean {
        if (urlStr.isNullOrBlank()) return false
        return try {
            val uri = android.net.Uri.parse(urlStr)
            uri.scheme.equals(WEBVIEW_SCHEME, ignoreCase = true) &&
                uri.host?.lowercase()?.endsWith(WEBVIEW_HOST_SUFFIX) == true &&
                PathSanitizer.isSafeExtensionId(uri.host!!.removeSuffix(WEBVIEW_HOST_SUFFIX))
        } catch (_: Exception) {
            false
        }
    }

    fun parseExtensionUrl(urlStr: String?): ExtensionUrlResult? {
        if (urlStr.isNullOrBlank()) return null
        val trimmed = urlStr.trim()

        return try {
            val schemeSep = trimmed.indexOf("://")
            if (schemeSep <= 0) return null
            val scheme = trimmed.substring(0, schemeSep).lowercase()

            val isLogical = ExtensionOrigin.isValidScheme(scheme)
            val uri = android.net.Uri.parse(trimmed)
            val host = uri.host?.lowercase()?.trim().orEmpty()
            val isPhysical = scheme == WEBVIEW_SCHEME &&
                host.endsWith(WEBVIEW_HOST_SUFFIX) &&
                host.removeSuffix(WEBVIEW_HOST_SUFFIX).isNotBlank()

            if (!isLogical && !isPhysical) return null

            val extensionId = if (isPhysical) {
                host.removeSuffix(WEBVIEW_HOST_SUFFIX)
            } else {
                host
            }

            if (extensionId.isBlank() || !PathSanitizer.isSafeExtensionId(extensionId)) return null

            val rawPath = uri.encodedPath ?: "/"
            if (rawPath.contains("\\") || rawPath.startsWith("//")) return null

            // Decode once for traversal detection, then sanitize and canonicalize.
            val decodedPath = try {
                java.net.URLDecoder.decode(rawPath, Charsets.UTF_8.name())
            } catch (_: Exception) {
                rawPath
            }
            if (decodedPath.contains("..") || decodedPath.contains("\\")) return null

            val cleanPath = PathSanitizer.sanitizeRelativePath(decodedPath)
            if (!PathSanitizer.isSafeRelativePath(cleanPath)) return null

            ExtensionUrlResult(
                extensionId = extensionId,
                resourcePath = cleanPath,
                canonicalUrl = toExtensionUrl(extensionId, cleanPath),
                isSandbox = false,
                isWebViewUrl = isPhysical
            )
        } catch (_: Exception) {
            null
        }
    }

    fun isExtensionUrl(urlStr: String?): Boolean = parseExtensionUrl(urlStr) != null

    fun getExtensionId(urlStr: String?): String? = parseExtensionUrl(urlStr)?.extensionId

    fun getResourcePath(urlStr: String?): String? = parseExtensionUrl(urlStr)?.resourcePath

    fun extractOrigin(urlStr: String?): String? {
        if (urlStr.isNullOrBlank()) return null
        return try {
            val uri = android.net.Uri.parse(urlStr)
            if (uri.scheme.isNullOrBlank() || uri.host.isNullOrBlank()) null
            else "${uri.scheme}://${uri.host}"
        } catch (_: Exception) {
            null
        }
    }
}
