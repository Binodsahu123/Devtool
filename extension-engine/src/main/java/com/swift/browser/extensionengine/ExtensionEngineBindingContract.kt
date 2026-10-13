package com.swift.browser.extensionengine

/**
 * Canonical map of the browser engines an extension API is allowed to use.
 * No extension adapter should instantiate a second database/repository for these domains.
 */
object ExtensionEngineBindingContract {
    val canonicalNamespaces: Map<String, String> = mapOf(
        "tabs" to "BrowserCoreServices.tabs",
        "bookmarks" to "BrowserCoreServices.bookmarks",
        "history" to "BrowserCoreServices.history",
        "downloads" to "BrowserCoreServices.downloads"
    )

    fun isCanonical(namespace: String): Boolean = canonicalNamespaces.containsKey(namespace)
}
