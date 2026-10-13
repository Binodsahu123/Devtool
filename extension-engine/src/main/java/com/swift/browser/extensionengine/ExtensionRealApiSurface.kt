package com.swift.browser.extensionengine

/**
 * Canonical inventory of extension APIs that are backed by real Orion adapters.
 *
 * This catalog is deliberately separate from the Chrome API reference: it only lists
 * namespaces for which Orion has a concrete adapter and routing entry. Stubs and
 * simulated APIs are not included, so a future change cannot accidentally advertise
 * a fake implementation as a real capability.
 */
object ExtensionRealApiSurface {
    val namespaces: Set<String> = setOf(
        "runtime", "storage", "tabs", "windows", "tabGroups", "sessions",
        "cookies", "bookmarks", "history", "downloads",
        "declarativeNetRequest", "action", "browserAction", "pageAction",
        "contextMenus", "commands", "omnibox", "sidePanel", "management",
        "topSites", "idle", "tts", "search", "alarms", "system", "notifications",
        "scripting", "permissions", "webNavigation"
    )

    val engineBackedNamespaces: Set<String> = setOf(
        "tabs", "windows", "tabGroups", "sessions",
        "bookmarks", "history", "downloads", "cookies",
        "notifications", "scripting"
    )

    fun isEngineBacked(namespace: String): Boolean = namespace in engineBackedNamespaces
}
