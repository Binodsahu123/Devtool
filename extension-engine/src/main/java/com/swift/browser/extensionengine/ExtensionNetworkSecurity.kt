package com.swift.browser.extensionengine

import java.net.URI
import java.net.URL

/** Security policy for extension metadata and package downloads. */
object ExtensionNetworkSecurity {
    private const val MAX_REDIRECTS = 5

    fun validateUpdateUrl(rawUrl: String, allowLocalDebug: Boolean = false): URL {
        val uri = try { URI(rawUrl.trim()) } catch (e: Exception) {
            throw IllegalArgumentException("Invalid extension update URL", e)
        }
        require(uri.isAbsolute && uri.host != null && uri.userInfo == null && uri.fragment == null) {
            "Invalid extension update URL"
        }
        val scheme = uri.scheme.lowercase()
        val localHost = uri.host.equals("localhost", true) || uri.host == "127.0.0.1" || uri.host == "::1"
        require(scheme == "https" || (allowLocalDebug && scheme == "http" && localHost)) {
            "Extension updates require HTTPS"
        }
        return uri.toURL()
    }

    fun resolveRedirect(current: URL, location: String, redirectCount: Int, allowLocalDebug: Boolean = false): URL {
        require(redirectCount < MAX_REDIRECTS) { "Too many extension update redirects" }
        return validateUpdateUrl(current.toURI().resolve(location).toString(), allowLocalDebug)
    }
}
