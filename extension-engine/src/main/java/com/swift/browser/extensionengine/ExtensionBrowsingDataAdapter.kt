package com.swift.browser.extensionengine

import android.content.Context
import android.webkit.WebStorage
import android.webkit.WebView
import com.swift.browser.cookieengine.CookieEngine
import com.swift.browser.cookieengine.CookieEngineApi
import com.swift.browser.historyengine.HistoryEngine
import com.swift.browser.bookmarkengine.api.BookmarkEngineApi
import com.swift.browser.downloadengine.DownloadEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlin.coroutines.resume
import org.json.JSONObject
import java.io.File

/** Real browsingData operations over Orion's canonical persistence engines. */
class ExtensionBrowsingDataAdapter(
    private val context: Context,
    private val permissionManager: PermissionManager,
    private val registry: ExtensionRegistry,
    private val history: HistoryEngine,
    private val bookmarks: BookmarkEngineApi,
    private val downloads: DownloadEngine,
    private val cookies: CookieEngine = CookieEngineApi.getInstance(context)
) {
    private fun verify(sender: ExtensionSender) {
        val ext = registry.getExtension(sender.extensionId)
            ?: throw SecurityException("SecurityError: extension not found")
        if (!permissionManager.hasApiPermission(sender.extensionId, ext.permissions, "browsingData")) {
            throw SecurityException("SecurityError: browsingData permission required")
        }
    }

    suspend fun remove(sender: ExtensionSender, options: JSONObject): JSONObject {
        verify(sender)
        val since = options.optLong("since", 0L).coerceAtLeast(0L)
        val data = options.optJSONObject("dataToRemove") ?: JSONObject()
        val preserve = options.optJSONObject("dataToPreserve") ?: JSONObject()

        if (data.optBoolean("history", false) && !preserve.optBoolean("history", false)) {
            if (since == 0L) history.clearAllHistory() else history.deleteHistorySince(since)
        }
        if (data.optBoolean("downloads", false) && !preserve.optBoolean("downloads", false)) {
            val items = downloads.getDownloadsFlow().first()
            items.filter { since == 0L || it.timestamp >= since }.forEach { downloads.deleteDownload(it.id) }
        }
        if (data.optBoolean("bookmarks", false) && !preserve.optBoolean("bookmarks", false)) {
            bookmarks.deleteAllBookmarks()
        }
        if (data.optBoolean("cookies", false) && !preserve.optBoolean("cookies", false)) {
            if (since != 0L) throw IllegalArgumentException("Cookie time-filtered removal is not supported by Android WebView CookieManager")
            suspendCancellableCoroutine<Unit> { cont ->
                cookies.removeAllCookies { if (cont.isActive) cont.resume(Unit) }
                cont.invokeOnCancellation { }
            }
        }
        if (data.optBoolean("cache", false) || data.optBoolean("appcache", false) ||
            data.optBoolean("serviceWorkers", false) || data.optBoolean("fileSystems", false) ||
            data.optBoolean("webSQL", false) || data.optBoolean("indexedDB", false)) {
            withContext(Dispatchers.Main) {
                try {
                    val tempWv = WebView(context)
                    tempWv.clearCache(true)
                    tempWv.destroy()
                } catch (_: Throwable) {}
                try {
                    WebStorage.getInstance().deleteAllData()
                } catch (_: Throwable) {}
            }
            withContext(Dispatchers.IO) {
                try {
                    val cacheDir = context.cacheDir
                    cacheDir.listFiles()?.forEach { file ->
                        if (file.name.contains("webview", ignoreCase = true) || file.name.contains("http", ignoreCase = true)) {
                            file.deleteRecursively()
                        }
                    }
                } catch (_: Throwable) {}
            }
        }
        return JSONObject().put("status", "success")
    }

    suspend fun removeHistory(sender: ExtensionSender, options: JSONObject): JSONObject {
        val wrapped = JSONObject().apply {
            put("since", options.optLong("since", 0L))
            put("dataToRemove", JSONObject().put("history", true))
        }
        return remove(sender, wrapped)
    }

    suspend fun removeDownloads(sender: ExtensionSender, options: JSONObject): JSONObject {
        val wrapped = JSONObject().apply {
            put("since", options.optLong("since", 0L))
            put("dataToRemove", JSONObject().put("downloads", true))
        }
        return remove(sender, wrapped)
    }

    suspend fun removeCookies(sender: ExtensionSender, options: JSONObject): JSONObject {
        val wrapped = JSONObject().apply {
            put("since", options.optLong("since", 0L))
            put("dataToRemove", JSONObject().put("cookies", true))
        }
        return remove(sender, wrapped)
    }

    suspend fun removeBookmarks(sender: ExtensionSender, options: JSONObject): JSONObject {
        val wrapped = JSONObject().apply {
            put("since", options.optLong("since", 0L))
            put("dataToRemove", JSONObject().put("bookmarks", true))
        }
        return remove(sender, wrapped)
    }

    suspend fun removeCache(sender: ExtensionSender, options: JSONObject): JSONObject {
        verify(sender)
        val wrapped = JSONObject().apply {
            put("since", options.optLong("since", 0L))
            put("dataToRemove", JSONObject().put("cache", true))
        }
        return remove(sender, wrapped)
    }

    fun settings(sender: ExtensionSender): JSONObject {
        verify(sender)
        val dataTypes = JSONObject().apply {
            put("history", true)
            put("downloads", true)
            put("cookies", true)
            put("cache", true)
            put("formData", false)
            put("passwords", false)
        }
        return JSONObject().apply {
            put("options", JSONObject().put("since", 0))
            put("dataToRemove", dataTypes)
            put("dataRemovalPermitted", dataTypes)
        }
    }
}
