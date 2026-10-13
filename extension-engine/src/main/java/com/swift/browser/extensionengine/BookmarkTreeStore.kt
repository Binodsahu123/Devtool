package com.swift.browser.extensionengine

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Persistent tree metadata for chrome.bookmarks compatibility.
 * The bookmark-engine remains the canonical store for bookmark URL/title records;
 * this store only owns Chrome's parent/index/folder topology, which the legacy
 * bookmark-engine schema does not model.
 */
class BookmarkTreeStore(context: Context) {
    data class Node(
        val id: String,
        var parentId: String?,
        var index: Int,
        var title: String,
        var url: String? = null,
        var dateAdded: Long = System.currentTimeMillis()
    ) {
        val isFolder: Boolean get() = url == null
    }

    private val prefs = context.applicationContext.getSharedPreferences("extension_bookmark_tree_v1", Context.MODE_PRIVATE)
    private val lock = Any()
    private val nodes = ConcurrentHashMap<String, Node>()
    private val nextFolderId = AtomicLong(-10L)

    init { load() }

    private fun load() {
        synchronized(lock) {
            nodes.clear()
            val raw = prefs.getString(KEY_NODES, null)
            if (!raw.isNullOrBlank()) {
                runCatching {
                    val arr = JSONArray(raw)
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        val n = Node(
                            id = o.getString("id"),
                            parentId = if (o.isNull("parentId")) null else o.optString("parentId", null),
                            index = o.optInt("index", 0),
                            title = o.optString("title", ""),
                            url = if (o.isNull("url")) null else o.optString("url", null),
                            dateAdded = o.optLong("dateAdded", System.currentTimeMillis())
                        )
                        nodes[n.id] = n
                        n.id.toLongOrNull()?.let { if (it < nextFolderId.get()) nextFolderId.set(it - 1) }
                    }
                }
            }
            ensureRootsLocked()
            normalizeLocked()
            persistLocked()
        }
    }

    private fun ensureRootsLocked() {
        if (!nodes.containsKey("0")) nodes["0"] = Node("0", null, 0, "")
        if (!nodes.containsKey("1")) nodes["1"] = Node("1", "0", 0, "Bookmarks Bar")
        if (!nodes.containsKey("2")) nodes["2"] = Node("2", "0", 1, "Other Bookmarks")
    }

    private fun persistLocked() {
        val arr = JSONArray()
        nodes.values.sortedWith(compareBy<Node> { it.parentId ?: "" }.thenBy { it.index }.thenBy { it.id }).forEach { n ->
            arr.put(JSONObject().apply {
                put("id", n.id)
                if (n.parentId == null) put("parentId", JSONObject.NULL) else put("parentId", n.parentId)
                put("index", n.index)
                put("title", n.title)
                if (n.url == null) put("url", JSONObject.NULL) else put("url", n.url)
                put("dateAdded", n.dateAdded)
            })
        }
        prefs.edit().putString(KEY_NODES, arr.toString()).apply()
    }

    private fun normalizeLocked() {
        val childrenByParent = nodes.values.filter { it.parentId != null }.groupBy { it.parentId }
        childrenByParent.values.forEach { children ->
            children.sortedWith(compareBy<Node> { it.index }.thenBy { it.dateAdded }.thenBy { it.id })
                .forEachIndexed { idx, node -> node.index = idx }
        }
    }

    fun ensureBookmark(id: String, title: String, url: String, parentId: String = "1", index: Int = Int.MAX_VALUE, dateAdded: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            ensureRootsLocked()
            val existing = nodes[id]
            if (existing == null) {
                nodes[id] = Node(id, validParentLocked(parentId), index, title, url, dateAdded)
            } else {
                existing.title = title
                existing.url = url
                existing.dateAdded = dateAdded
                if (existing.parentId == null) existing.parentId = validParentLocked(parentId)
            }
            normalizeLocked(); persistLocked()
        }
    }

    fun createFolder(title: String, parentId: String = "1", index: Int = Int.MAX_VALUE): Node = synchronized(lock) {
        ensureRootsLocked()
        val id = nextFolderId.getAndDecrement().toString()
        val node = Node(id, validParentLocked(parentId), index, title, null)
        nodes[id] = node
        normalizeLocked(); persistLocked()
        node
    }

    fun get(id: String): Node? = synchronized(lock) { nodes[id]?.copy() }

    fun children(parentId: String): List<Node> = synchronized(lock) {
        ensureRootsLocked()
        nodes.values.filter { it.parentId == parentId }.sortedWith(compareBy<Node> { it.index }.thenBy { it.id }).map { it.copy() }
    }

    fun updateBookmark(id: String, title: String? = null, url: String? = null) {
        synchronized(lock) {
            nodes[id]?.let {
                if (title != null) it.title = title
                if (url != null) it.url = url
            }
            persistLocked()
        }
    }

    fun move(id: String, parentId: String, index: Int): Node = synchronized(lock) {
        ensureRootsLocked()
        val node = nodes[id] ?: throw IllegalArgumentException("BOOKMARK_NOT_FOUND")
        val targetParent = validParentLocked(parentId)
        if (id == "0" || targetParent == id || isDescendantLocked(targetParent, id)) {
            throw IllegalArgumentException("BOOKMARK_INVALID_PARENT")
        }
        node.parentId = targetParent
        node.index = index.coerceAtLeast(0)
        normalizeLocked(); persistLocked()
        node.copy()
    }

    fun remove(id: String): List<String> = synchronized(lock) {
        if (id == "0") throw IllegalArgumentException("BOOKMARK_CANNOT_REMOVE_ROOT")
        if (!nodes.containsKey(id)) throw IllegalArgumentException("BOOKMARK_NOT_FOUND")
        val removed = mutableListOf<String>()
        fun walk(current: String) {
            nodes.values.filter { it.parentId == current }.map { it.id }.forEach(::walk)
            if (nodes.remove(current) != null) removed += current
        }
        walk(id)
        normalizeLocked(); persistLocked(); removed
    }

    fun tree(): Node = synchronized(lock) {
        ensureRootsLocked()
        nodes["0"]?.copy() ?: Node("0", null, 0, "")
    }

    fun allNodes(): List<Node> = synchronized(lock) { nodes.values.map { it.copy() } }

    private fun validParentLocked(parentId: String): String {
        val candidate = if (parentId == "root") "0" else parentId
        if (!nodes.containsKey(candidate) || nodes[candidate]?.url != null) throw IllegalArgumentException("BOOKMARK_PARENT_NOT_FOUND")
        return candidate
    }

    private fun isDescendantLocked(candidate: String, ancestor: String): Boolean {
        var current = candidate
        repeat(nodes.size + 1) {
            if (current == ancestor) return true
            current = nodes[current]?.parentId ?: return false
        }
        return false
    }

    companion object { private const val KEY_NODES = "nodes" }
}
