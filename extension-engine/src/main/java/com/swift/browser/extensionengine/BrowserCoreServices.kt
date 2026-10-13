package com.swift.browser.extensionengine

import android.content.Context
import com.swift.browser.bookmarkengine.api.BookmarkEngineApi
import com.swift.browser.bookmarkengine.api.BookmarkEngineProvider
import com.swift.browser.downloadengine.DownloadEngine
import com.swift.browser.downloadengine.DownloadEngineProvider
import com.swift.browser.historyengine.HistoryEngine
import com.swift.browser.historyengine.api.HistoryEngineProvider
import com.swift.browser.tabengine.api.TabEngineApi
import com.swift.browser.tabengine.api.TabEngineProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Canonical browser-core capability registry used by both the browser UI and
 * the WebExtension layer.  It intentionally returns the singleton engine
 * providers rather than constructing duplicate repositories/databases.
 */
class BrowserCoreServices private constructor(
    private val applicationContext: Context,
    private val scope: CoroutineScope
) {
    @Volatile
    private var initialized = false

    val tabs: TabEngineApi by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        TabEngineProvider.getEngine(applicationContext, scope)
    }

    val bookmarks: BookmarkEngineApi by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        BookmarkEngineProvider.getEngine(applicationContext, scope)
    }

    val history: HistoryEngine by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        HistoryEngineProvider.api
    }

    val downloads: DownloadEngine by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        DownloadEngineProvider.getEngine(applicationContext)
    }

    /**
     * Deterministically initializes the four browser engines used by extension adapters.
     * The calls are idempotent because each underlying Provider owns its process singleton.
     */
    @Synchronized
    fun initializeCoreEngines() {
        if (initialized) return
        BookmarkEngineProvider.initialize(applicationContext, scope)
        DownloadEngineProvider.initialize(applicationContext)
        TabEngineProvider.initialize(applicationContext, scope)
        initialized = true
    }

    companion object {
        @Volatile private var instance: BrowserCoreServices? = null

        fun get(context: Context, scope: CoroutineScope? = null): BrowserCoreServices {
            val app = context.applicationContext
            return instance ?: synchronized(this) {
                instance ?: BrowserCoreServices(
                    applicationContext = app,
                    scope = scope ?: CoroutineScope(SupervisorJob() + Dispatchers.Default)
                ).also { instance = it }
            }
        }
    }
}
