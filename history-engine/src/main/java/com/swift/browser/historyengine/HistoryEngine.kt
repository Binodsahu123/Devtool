package com.swift.browser.historyengine

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "history")
data class HistoryItem(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val url: String,
    val title: String,
    val timestamp: Long,
    val visitCount: Int = 1
)

/**
 * Encapsulates browsing context and privacy state for history operations.
 */
data class HistoryBrowsingContext(
    val isPrivate: Boolean = false,
    val sessionId: String? = null
) {
    companion object {
        val NORMAL = HistoryBrowsingContext(isPrivate = false)
        val PRIVATE = HistoryBrowsingContext(isPrivate = true)
        fun private(sessionId: String? = null) = HistoryBrowsingContext(isPrivate = true, sessionId = sessionId)
    }
}

@Dao
interface HistoryDao {
    @Query("SELECT * FROM history ORDER BY timestamp DESC")
    fun getAllHistory(): Flow<List<HistoryItem>>

    @Query("SELECT * FROM history ORDER BY timestamp DESC LIMIT :limit OFFSET :offset")
    suspend fun getHistoryPaged(limit: Int, offset: Int): List<HistoryItem>

    @Query("SELECT * FROM history ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentHistory(limit: Int): Flow<List<HistoryItem>>

    @Query("SELECT * FROM history WHERE url = :url LIMIT 1")
    suspend fun getHistoryByUrl(url: String): HistoryItem?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHistory(historyItem: HistoryItem)

    @Update
    suspend fun updateHistory(historyItem: HistoryItem)

    @Query("DELETE FROM history")
    suspend fun clearAllHistory()

    @Query("DELETE FROM history WHERE id = :id")
    suspend fun deleteHistoryItem(id: Int)

    @Query("DELETE FROM history WHERE timestamp >= :timestamp")
    suspend fun deleteHistorySince(timestamp: Long)

    @Query("DELETE FROM history WHERE timestamp >= :startTime AND timestamp <= :endTime")
    suspend fun deleteHistoryRange(startTime: Long, endTime: Long)

    @Query("""
        SELECT * FROM history
        WHERE (:query = '' OR title LIKE '%' || :query || '%' OR url LIKE '%' || :query || '%')
          AND timestamp >= :startTime
          AND timestamp <= :endTime
        ORDER BY timestamp DESC
        LIMIT :limit
    """)
    suspend fun searchHistory(query: String, startTime: Long, endTime: Long, limit: Int): List<HistoryItem>
}

interface HistoryEngine {
    fun getHistoryFlow(): Flow<List<HistoryItem>>
    fun getRecentHistory(limit: Int): Flow<List<HistoryItem>>
    suspend fun addHistoryItem(url: String, title: String) {
        addHistoryItem(url, title, HistoryBrowsingContext.NORMAL)
    }
    suspend fun addHistoryItem(url: String, title: String, isPrivate: Boolean) {
        addHistoryItem(url, title, HistoryBrowsingContext(isPrivate = isPrivate))
    }
    suspend fun addHistoryItem(url: String, title: String, browsingContext: HistoryBrowsingContext)
    suspend fun addHistoryItemAndGet(url: String, title: String, browsingContext: HistoryBrowsingContext): HistoryItem?
    suspend fun clearAllHistory()
    suspend fun deleteHistoryItem(id: Int)
    suspend fun deleteHistorySince(timestamp: Long)
    suspend fun deleteHistoryRange(startTime: Long, endTime: Long)
    suspend fun queryHistory(query: String): List<HistoryItem>
    suspend fun queryHistory(query: String, limit: Int = 100): List<HistoryItem> {
        return queryHistory(query)
    }
    suspend fun queryHistorySemantic(query: String): List<HistoryItem>
}

class HistoryRepository(private val historyDao: HistoryDao) : HistoryEngine {
    override fun getHistoryFlow(): Flow<List<HistoryItem>> {
        return historyDao.getAllHistory()
    }
    
    override fun getRecentHistory(limit: Int): Flow<List<HistoryItem>> {
        return historyDao.getRecentHistory(limit)
    }

    override suspend fun addHistoryItem(url: String, title: String) {
        addHistoryItem(url, title, HistoryBrowsingContext.NORMAL)
    }

    override suspend fun addHistoryItem(url: String, title: String, isPrivate: Boolean) {
        addHistoryItem(url, title, HistoryBrowsingContext(isPrivate = isPrivate))
    }

    override suspend fun addHistoryItem(url: String, title: String, browsingContext: HistoryBrowsingContext) {
        addHistoryItemAndGet(url, title, browsingContext)
    }

    override suspend fun addHistoryItemAndGet(url: String, title: String, browsingContext: HistoryBrowsingContext): HistoryItem? {
        // Enforce boundary write policy: private visits NEVER reach Room persistence
        if (browsingContext.isPrivate) return null
        if (url.isBlank() || url.startsWith("data:") || url.startsWith("about:")) return null
        val existing = historyDao.getHistoryByUrl(url)
        val item = if (existing != null) {
            existing.copy(
                timestamp = System.currentTimeMillis(),
                visitCount = existing.visitCount + 1,
                title = title.ifBlank { existing.title.ifBlank { url } }
            )
        } else {
            HistoryItem(
                url = url,
                title = title.ifBlank { url },
                timestamp = System.currentTimeMillis(),
                visitCount = 1
            )
        }
        if (existing != null) historyDao.updateHistory(item) else historyDao.insertHistory(item)
        return if (existing != null) item else historyDao.getHistoryByUrl(url) ?: item
    }

    override suspend fun clearAllHistory() {
        historyDao.clearAllHistory()
    }
    
    override suspend fun deleteHistoryItem(id: Int) {
        historyDao.deleteHistoryItem(id)
    }

    override suspend fun deleteHistorySince(timestamp: Long) {
        historyDao.deleteHistorySince(timestamp)
    }

    override suspend fun deleteHistoryRange(startTime: Long, endTime: Long) {
        historyDao.deleteHistoryRange(startTime, endTime)
    }

    override suspend fun queryHistory(query: String): List<HistoryItem> {
        return historyDao.searchHistory(query, 0L, Long.MAX_VALUE, 50)
    }

    override suspend fun queryHistory(query: String, limit: Int): List<HistoryItem> {
        return historyDao.searchHistory(query, 0L, Long.MAX_VALUE, limit.coerceIn(0, 10_000))
    }

    suspend fun queryHistory(
        query: String,
        startTime: Long,
        endTime: Long,
        limit: Int
    ): List<HistoryItem> {
        val safeStart = startTime.coerceAtLeast(0L)
        val safeEnd = endTime.coerceAtLeast(safeStart)
        return historyDao.searchHistory(
            query = query,
            startTime = safeStart,
            endTime = safeEnd,
            limit = limit.coerceIn(0, 10_000)
        )
    }

    override suspend fun queryHistorySemantic(query: String): List<HistoryItem> {
        val basic = queryHistory(query).toMutableList()
        val semanticMatch = HistorySearch.classifySemanticIntent(query)
        if (semanticMatch.isNotEmpty()) {
            val allHistory = getHistoryPagedList()
            for (item in allHistory) {
                if (basic.none { it.id == item.id }) {
                    for (keyword in semanticMatch) {
                        if (item.url.contains(keyword, ignoreCase = true) || item.title.contains(keyword, ignoreCase = true)) {
                            basic.add(item)
                            break
                        }
                    }
                }
            }
        }
        return basic
    }

    private suspend fun getHistoryPagedList(): List<HistoryItem> {
        return try {
            historyDao.getHistoryPaged(100, 0)
        } catch (e: Exception) {
            emptyList()
        }
    }
}

object HistorySearch {
    fun classifySemanticIntent(query: String): List<String> {
        val normalized = query.trim().lowercase()
        return when {
            normalized.contains("tech") || normalized.contains("code") || normalized.contains("developer") ->
                listOf("github", "stackoverflow", "medium", "reddit", "kotlin", "google")
            normalized.contains("social") || normalized.contains("chat") || normalized.contains("friends") ->
                listOf("twitter", "facebook", "instagram", "linkedin", "reddit", "tiktok")
            normalized.contains("finance") || normalized.contains("banking") || normalized.contains("money") ->
                listOf("paypal", "visa", "bank", "stripe", "chase", "finance")
            normalized.contains("mail") || normalized.contains("workspace") || normalized.contains("office") ->
                listOf("gmail", "yahoo", "outlook", "mail", "drive", "docs")
            normalized.contains("video") || normalized.contains("watch") || normalized.contains("movie") || normalized.contains("stream") ->
                listOf("netflix", "twitch", "vimeo", "disney", "prime")
            else -> emptyList()
        }
    }
    
    fun rankSuggestions(history: List<HistoryItem>, query: String): List<HistoryItem> {
        val direct = history.filter { it.title.contains(query, ignoreCase = true) || it.url.contains(query, ignoreCase = true) }
        val semantic = classifySemanticIntent(query)
        if (semantic.isEmpty()) return direct
        val items = direct.toMutableList()
        for (item in history) {
            if (items.none { it.id == item.id }) {
                if (semantic.any { item.url.contains(it, ignoreCase = true) }) {
                    items.add(item)
                }
            }
        }
        return items
    }
}
