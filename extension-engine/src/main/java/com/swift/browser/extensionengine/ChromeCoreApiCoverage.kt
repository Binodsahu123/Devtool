package com.swift.browser.extensionengine

/**
 * Core WebExtension API contract implemented against real Orion browser engines.
 *
 * This registry describes the engine-backed subset whose ownership is explicit:
 * history, bookmarks, downloads and tabs. It is used by diagnostics/tests to prevent
 * an adapter from silently switching to an in-memory or duplicate implementation.
 */
object ChromeCoreApiCoverage {
    data class Entry(val namespace: String, val engine: String, val methods: Set<String>)

    val entries: List<Entry> = listOf(
        Entry(
            "history",
            "history-engine",
            setOf("search", "getVisits", "addUrl", "deleteUrl", "deleteRange", "deleteAll")
        ),
        Entry(
            "bookmarks",
            "bookmark-engine",
            setOf("get", "getChildren", "getRecent", "getTree", "getSubTree", "search", "create", "update", "move", "remove", "removeTree")
        ),
        Entry(
            "downloads",
            "download-engine",
            setOf("download", "search", "pause", "resume", "cancel", "removeFile", "erase", "open")
        ),
        Entry(
            "tabs",
            "tab-engine",
            setOf("get", "query", "create", "update", "remove", "reload", "sendMessage", "connect")
        )
    )

    fun entry(namespace: String): Entry? =
        entries.firstOrNull { it.namespace.equals(namespace, ignoreCase = true) }
}
