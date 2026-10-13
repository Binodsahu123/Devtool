package com.swift.browser.extensionengine

import android.content.Context
import com.swift.browser.bookmarkengine.Bookmark
import com.swift.browser.bookmarkengine.api.BookmarkEngineApi
import org.json.JSONArray
import org.json.JSONObject

/** Chrome bookmarks adapter backed by Orion's canonical bookmark engine plus persistent tree metadata. */
class ExtensionBookmarksAdapter(
    context: Context,
    private val permissionManager: PermissionManager,
    private val registry: ExtensionRegistry,
    private val eventManager: EventManager,
    private val bookmarkApi: BookmarkEngineApi
) {
    private val treeStore = BookmarkTreeStore(context)
    private val allBookmarks: List<Bookmark> get() = bookmarkApi.bookmarks.value

    private fun verify(sender: ExtensionSender) {
        val ext = registry.getExtension(sender.extensionId)
            ?: throw SecurityException("SecurityError: Extension ${sender.extensionId} not found.")
        if (!permissionManager.hasApiPermission(sender.extensionId, ext.permissions, "bookmarks")) {
            throw SecurityException("SecurityError: Extension does not have 'bookmarks' permission in manifest.")
        }
    }

    private fun validUrl(url: String): Boolean = runCatching {
        val u = android.net.Uri.parse(url)
        u.scheme != null && u.host != null
    }.getOrDefault(false)

    private fun syncMissingBookmarks() {
        allBookmarks.forEach { bm ->
            treeStore.ensureBookmark(bm.id.toString(), bm.title, bm.url, dateAdded = bm.timestamp)
        }
    }

    private fun nodeJson(id: String): JSONObject {
        syncMissingBookmarks()
        val n = treeStore.get(id) ?: throw IllegalArgumentException("BOOKMARK_NOT_FOUND")
        return JSONObject().apply {
            put("id", n.id)
            n.parentId?.let { put("parentId", it) }
            put("index", n.index)
            put("title", n.title)
            if (n.url != null) put("url", n.url) else put("children", childrenJson(n.id))
            put("dateAdded", n.dateAdded)
        }
    }

    private fun childrenJson(parentId: String): JSONArray = JSONArray().apply {
        treeStore.children(parentId).forEach { put(nodeJson(it.id)) }
    }

    fun get(sender: ExtensionSender, idOrIds: Any): JSONArray {
        verify(sender); syncMissingBookmarks()
        val ids = when (idOrIds) {
            is String -> listOf(idOrIds)
            is Number -> listOf(idOrIds.toString())
            is JSONArray -> (0 until idOrIds.length()).map { idOrIds.get(it).toString() }
            else -> emptyList()
        }
        return JSONArray().apply { ids.forEach { put(nodeJson(it)) } }
    }

    fun getChildren(sender: ExtensionSender, id: String): JSONArray {
        verify(sender); syncMissingBookmarks()
        val normalized = if (id == "root") "0" else id
        if (treeStore.get(normalized) == null) throw IllegalArgumentException("BOOKMARK_NOT_FOUND")
        return childrenJson(normalized)
    }

    fun getRecent(sender: ExtensionSender, numberOfItems: Int): JSONArray {
        verify(sender); syncMissingBookmarks()
        val limit = numberOfItems.coerceAtLeast(0)
        return JSONArray().apply {
            allBookmarks.sortedByDescending { it.timestamp }.take(limit).forEach { put(nodeJson(it.id.toString())) }
        }
    }

    fun getTree(sender: ExtensionSender): JSONArray {
        verify(sender); syncMissingBookmarks()
        return JSONArray().put(nodeJson("0"))
    }

    fun getSubTree(sender: ExtensionSender, id: String): JSONArray {
        verify(sender); syncMissingBookmarks()
        val normalized = if (id == "root") "0" else id
        if (treeStore.get(normalized) == null) throw IllegalArgumentException("BOOKMARK_NOT_FOUND")
        return JSONArray().put(nodeJson(normalized))
    }

    suspend fun search(sender: ExtensionSender, queryInput: Any): JSONArray {
        verify(sender); syncMissingBookmarks()
        val q = when (queryInput) {
            is String -> queryInput
            is JSONObject -> queryInput.optString("query", queryInput.optString("title", queryInput.optString("url", "")))
            else -> ""
        }
        val matches = if (q.isBlank()) allBookmarks else bookmarkApi.searchBookmarks(q, 100)
        return JSONArray().apply { matches.forEach { put(nodeJson(it.id.toString())) } }
    }

    suspend fun create(sender: ExtensionSender, data: JSONObject): JSONObject {
        verify(sender); syncMissingBookmarks()
        val title = data.optString("title", "")
        val url = data.optString("url", "")
        val parentId = if (data.optString("parentId", "1") == "root") "0" else data.optString("parentId", "1")
        val index = data.optInt("index", Int.MAX_VALUE)
        if (treeStore.get(parentId) == null || treeStore.get(parentId)?.url != null) throw IllegalArgumentException("BOOKMARK_PARENT_NOT_FOUND")

        val node = if (url.isBlank()) {
            if (title.isBlank()) throw IllegalArgumentException("BOOKMARK_TITLE_REQUIRED")
            treeStore.createFolder(title, parentId, index)
        } else {
            if (!validUrl(url)) throw IllegalArgumentException("BOOKMARK_INVALID_URL")
            val bm = bookmarkApi.addBookmarkAndGet(url, title)
            treeStore.ensureBookmark(bm.id.toString(), bm.title, bm.url, parentId, index, bm.timestamp)
            treeStore.get(bm.id.toString())!!
        }
        val json = nodeJson(node.id)
        eventManager.triggerEvent("bookmarks.onCreated", JSONObject().put("id", node.id).put("bookmark", json))
        return json
    }

    suspend fun update(sender: ExtensionSender, id: String, changes: JSONObject): JSONObject {
        verify(sender); syncMissingBookmarks()
        val node = treeStore.get(id) ?: throw IllegalArgumentException("BOOKMARK_NOT_FOUND")
        if (node.isFolder) {
            if (changes.has("url")) throw IllegalArgumentException("BOOKMARK_INVALID_URL")
            val title = if (changes.has("title")) changes.getString("title") else node.title
            treeStore.updateBookmark(id, title = title)
        } else {
            val existing = allBookmarks.find { it.id.toString() == id } ?: throw IllegalArgumentException("BOOKMARK_NOT_FOUND")
            val title = if (changes.has("title")) changes.getString("title") else existing.title
            val url = if (changes.has("url")) changes.getString("url") else existing.url
            if (!validUrl(url)) throw IllegalArgumentException("BOOKMARK_INVALID_URL")
            val updated = bookmarkApi.updateBookmark(existing.copy(title = title, url = url))
            treeStore.updateBookmark(id, title = updated.title, url = updated.url)
        }
        val result = nodeJson(id)
        eventManager.triggerEvent("bookmarks.onChanged", JSONObject().put("id", id).put("changeInfo", changes))
        return result
    }

    fun move(sender: ExtensionSender, id: String, destination: JSONObject): JSONObject {
        verify(sender); syncMissingBookmarks()
        val parentId = if (destination.optString("parentId", "1") == "root") "0" else destination.optString("parentId", "1")
        val moved = treeStore.move(id, parentId, destination.optInt("index", Int.MAX_VALUE))
        val info = JSONObject().put("parentId", moved.parentId).put("index", moved.index)
        eventManager.triggerEvent("bookmarks.onMoved", JSONObject().put("id", id).put("moveInfo", info))
        return nodeJson(id)
    }

    private fun collectDescendants(parentId: String): Set<String> {
        val result = linkedSetOf<String>()
        fun walk(id: String) {
            treeStore.children(id).forEach { child ->
                if (result.add(child.id)) walk(child.id)
            }
        }
        walk(parentId)
        return result
    }

    suspend fun remove(sender: ExtensionSender, id: String): JSONObject {
        verify(sender); syncMissingBookmarks()
        val node = treeStore.get(id) ?: throw IllegalArgumentException("BOOKMARK_NOT_FOUND")
        val parentId = node.parentId ?: "0"
        val index = node.index
        val subtreeIds = treeStore.allNodes().filter { candidate ->
            candidate.id == id || candidate.id in collectDescendants(id)
        }.map { it.id }.toSet()
        allBookmarks.filter { it.id.toString() in subtreeIds }.forEach { bookmarkApi.deleteBookmark(it) }
        treeStore.remove(id)
        eventManager.triggerEvent("bookmarks.onRemoved", JSONObject().put("id", id).put("removeInfo", JSONObject().put("parentId", parentId).put("index", index)))
        return JSONObject().put("id", id)
    }

    suspend fun removeTree(sender: ExtensionSender, id: String): JSONObject = remove(sender, id)
}
