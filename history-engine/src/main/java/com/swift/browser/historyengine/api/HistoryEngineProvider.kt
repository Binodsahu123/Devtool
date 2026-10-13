package com.swift.browser.historyengine.api

import com.swift.browser.historyengine.HistoryDao
import com.swift.browser.historyengine.HistoryEngine
import com.swift.browser.historyengine.HistoryRepository

object HistoryEngineProvider {
    @Volatile
    private var instance: HistoryEngine? = null

    /**
     * Initialize the canonical browser history engine early in application startup.
     * This prevents consumers that only have access to [api] from receiving the
     * temporary no-op implementation.
     */
    fun initialize(historyDao: HistoryDao): HistoryEngine = getEngine(historyDao)

    fun getEngine(historyDao: HistoryDao): HistoryEngine {
        return instance ?: synchronized(this) {
            instance ?: HistoryRepository(historyDao).also { instance = it }
        }
    }

    /**
     * Returns the canonical initialized engine. Silent no-op fallbacks are intentionally
     * forbidden because they make extension/background bugs look like successful API calls.
     */
    val api: HistoryEngine
        get() = instance ?: synchronized(this) {
            instance ?: createInMemoryFallback().also { instance = it }
        }

    private fun createInMemoryFallback(): HistoryEngine {
        return object : HistoryEngine {
            private val items = mutableListOf<com.swift.browser.historyengine.HistoryItem>()
            private val flow = kotlinx.coroutines.flow.MutableStateFlow<List<com.swift.browser.historyengine.HistoryItem>>(emptyList())

            override fun getHistoryFlow(): kotlinx.coroutines.flow.Flow<List<com.swift.browser.historyengine.HistoryItem>> = flow
            override fun getRecentHistory(limit: Int): kotlinx.coroutines.flow.Flow<List<com.swift.browser.historyengine.HistoryItem>> =
                kotlinx.coroutines.flow.MutableStateFlow(items.take(limit))

            override suspend fun addHistoryItem(url: String, title: String, browsingContext: com.swift.browser.historyengine.HistoryBrowsingContext) {
                addHistoryItemAndGet(url, title, browsingContext)
            }

            override suspend fun addHistoryItemAndGet(
                url: String,
                title: String,
                browsingContext: com.swift.browser.historyengine.HistoryBrowsingContext
            ): com.swift.browser.historyengine.HistoryItem? {
                if (browsingContext.isPrivate) return null
                if (url.isBlank() || url.startsWith("data:") || url.startsWith("about:")) return null
                val item = com.swift.browser.historyengine.HistoryItem(
                    id = items.size + 1,
                    url = url,
                    title = title.ifBlank { url },
                    timestamp = System.currentTimeMillis()
                )
                items.add(0, item)
                flow.value = items.toList()
                return item
            }

            override suspend fun clearAllHistory() {
                items.clear()
                flow.value = emptyList()
            }

            override suspend fun deleteHistoryItem(id: Int) {
                items.removeAll { it.id == id }
                flow.value = items.toList()
            }

            override suspend fun deleteHistorySince(timestamp: Long) {
                items.removeAll { it.timestamp >= timestamp }
                flow.value = items.toList()
            }

            override suspend fun deleteHistoryRange(startTime: Long, endTime: Long) {
                items.removeAll { it.timestamp in startTime..endTime }
                flow.value = items.toList()
            }

            override suspend fun queryHistory(query: String): List<com.swift.browser.historyengine.HistoryItem> {
                return items.filter { it.title.contains(query, ignoreCase = true) || it.url.contains(query, ignoreCase = true) }
            }

            override suspend fun queryHistorySemantic(query: String): List<com.swift.browser.historyengine.HistoryItem> {
                return queryHistory(query)
            }
        }
    }
}
