package com.swift.browser.extensionengine

import android.content.Context

/**
 * Single source of truth for browser engines consumed by WebExtension adapters.
 *
 * Adapters must receive their engines from this registry; they must not create databases,
 * repositories, or provider instances of their own.
 */
data class CanonicalBrowserEngineBindings(
    val services: BrowserCoreServices
) {
    val tabs get() = services.tabs
    val bookmarks get() = services.bookmarks
    val history get() = services.history
    val downloads get() = services.downloads

    fun assertCanonical() {
        check(tabs === services.tabs) { "Tab engine is not canonical" }
        check(bookmarks === services.bookmarks) { "Bookmark engine is not canonical" }
        check(history === services.history) { "History engine is not canonical" }
        check(downloads === services.downloads) { "Download engine is not canonical" }
    }

    companion object {
        fun get(context: Context): CanonicalBrowserEngineBindings =
            CanonicalBrowserEngineBindings(BrowserCoreServices.get(context))
    }
}
