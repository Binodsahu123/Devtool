package com.swift.browser.bookmarkengine.api

import android.content.Context
import com.swift.browser.bookmarkengine.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

interface BookmarkEngineApi {
    val bookmarks: StateFlow<List<Bookmark>>
    fun init(context: Context)
    fun addBookmark(url: String, title: String)
    suspend fun addBookmarkAndGet(url: String, title: String): Bookmark
    suspend fun updateBookmark(bookmark: Bookmark): Bookmark
    suspend fun getBookmarkById(id: Int): Bookmark?
    fun deleteBookmark(bookmark: Bookmark)
    fun deleteBookmarkByUrl(url: String)
    fun deleteAllBookmarks()
    suspend fun isBookmarked(url: String): Boolean
    suspend fun toggleBookmark(url: String, title: String): Boolean
    suspend fun searchBookmarks(query: String, limit: Int = 20): List<Bookmark>
}

class BookmarkEngineImpl(
    context: Context,
    private val scope: CoroutineScope
) : BookmarkEngineApi {
    private val database = BookmarkDatabase.getDatabase(context)
    private val repository = BookmarkRepository(database.bookmarkDao())

    override val bookmarks: StateFlow<List<Bookmark>> = repository.getBookmarksFlow()
        .stateIn(scope, SharingStarted.Lazily, emptyList())

    override fun init(context: Context) {
        BookmarkDatabase.getDatabase(context)
    }

    override fun addBookmark(url: String, title: String) {
        scope.launch(Dispatchers.IO) {
            repository.addBookmark(url, title)
        }
    }

    override suspend fun addBookmarkAndGet(url: String, title: String): Bookmark =
        repository.addBookmarkAndGet(url, title)

    override suspend fun updateBookmark(bookmark: Bookmark): Bookmark =
        repository.updateBookmark(bookmark)

    override suspend fun getBookmarkById(id: Int): Bookmark? =
        repository.getBookmarkById(id)

    override fun deleteBookmark(bookmark: Bookmark) {
        scope.launch(Dispatchers.IO) {
            repository.deleteBookmark(bookmark)
        }
    }

    override fun deleteBookmarkByUrl(url: String) {
        scope.launch(Dispatchers.IO) {
            repository.deleteBookmarkByUrl(url)
        }
    }

    override fun deleteAllBookmarks() {
        scope.launch(Dispatchers.IO) {
            repository.deleteAllBookmarks()
        }
    }

    override suspend fun isBookmarked(url: String): Boolean {
        return repository.isBookmarked(url)
    }

    override suspend fun toggleBookmark(url: String, title: String): Boolean {
        return repository.toggleBookmark(url, title)
    }

    override suspend fun searchBookmarks(query: String, limit: Int): List<Bookmark> {
        return repository.searchBookmarks(query, limit)
    }
}

object BookmarkEngineProvider {
    @Volatile
    private var instance: BookmarkEngineApi? = null

    fun initialize(context: Context, scope: CoroutineScope? = null): BookmarkEngineApi =
        getEngine(context, scope)

    fun getEngine(context: Context, scope: CoroutineScope? = null): BookmarkEngineApi {
        val safeScope = scope ?: CoroutineScope(kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob())
        return instance ?: synchronized(this) {
            instance ?: BookmarkEngineImpl(context.applicationContext, safeScope).also { instance = it }
        }
    }

    /** Canonical initialized engine; fail-fast instead of silently dropping bookmark operations. */
    val api: BookmarkEngineApi
        get() = instance ?: throw IllegalStateException(
            "BookmarkEngineProvider is not initialized. Initialize BrowserCoreServices/BrowserApplication first."
        )
}
