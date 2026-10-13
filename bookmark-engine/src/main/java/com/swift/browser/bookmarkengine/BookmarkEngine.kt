package com.swift.browser.bookmarkengine

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "bookmarks")
data class Bookmark(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val url: String,
    val title: String,
    val timestamp: Long = System.currentTimeMillis()
)

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM bookmarks ORDER BY timestamp DESC")
    fun getAllBookmarks(): Flow<List<Bookmark>>

    @Query("SELECT * FROM bookmarks WHERE url = :url LIMIT 1")
    suspend fun getBookmarkByUrl(url: String): Bookmark?

    @Query("SELECT * FROM bookmarks WHERE id = :id LIMIT 1")
    suspend fun getBookmarkById(id: Int): Bookmark?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBookmark(bookmark: Bookmark)

    @Update
    suspend fun updateBookmark(bookmark: Bookmark)

    @Delete
    suspend fun deleteBookmark(bookmark: Bookmark)

    @Query("DELETE FROM bookmarks")
    suspend fun deleteAllBookmarks()

    @Query("DELETE FROM bookmarks WHERE url = :url")
    suspend fun deleteBookmarkByUrl(url: String)

    @Query("SELECT * FROM bookmarks WHERE title LIKE '%' || :query || '%' OR url LIKE '%' || :query || '%' LIMIT :limit")
    suspend fun searchBookmarks(query: String, limit: Int): List<Bookmark>
}

interface BookmarkEngine {
    fun getBookmarksFlow(): Flow<List<Bookmark>>
    suspend fun addBookmark(url: String, title: String)
    suspend fun addBookmarkAndGet(url: String, title: String): Bookmark
    suspend fun updateBookmark(bookmark: Bookmark): Bookmark
    suspend fun getBookmarkById(id: Int): Bookmark?
    suspend fun deleteBookmark(bookmark: Bookmark)
    suspend fun deleteBookmarkByUrl(url: String)
    suspend fun deleteAllBookmarks()
    suspend fun searchBookmarks(query: String, limit: Int): List<Bookmark>
    suspend fun isBookmarked(url: String): Boolean
    suspend fun toggleBookmark(url: String, title: String): Boolean
}

class BookmarkRepository(private val bookmarkDao: BookmarkDao) : BookmarkEngine {
    override fun getBookmarksFlow(): Flow<List<Bookmark>> {
        return bookmarkDao.getAllBookmarks()
    }

    override suspend fun addBookmark(url: String, title: String) {
        bookmarkDao.insertBookmark(Bookmark(url = url, title = title))
    }

    override suspend fun addBookmarkAndGet(url: String, title: String): Bookmark {
        val existing = bookmarkDao.getBookmarkByUrl(url)
        if (existing != null) return existing.copy(title = title).also { bookmarkDao.updateBookmark(it) }
        val item = Bookmark(url = url, title = title)
        bookmarkDao.insertBookmark(item)
        return bookmarkDao.getBookmarkByUrl(url) ?: item
    }

    override suspend fun updateBookmark(bookmark: Bookmark): Bookmark {
        bookmarkDao.updateBookmark(bookmark)
        return bookmarkDao.getBookmarkById(bookmark.id) ?: bookmark
    }

    override suspend fun getBookmarkById(id: Int): Bookmark? = bookmarkDao.getBookmarkById(id)

    override suspend fun deleteBookmark(bookmark: Bookmark) {
        bookmarkDao.deleteBookmark(bookmark)
    }

    override suspend fun deleteBookmarkByUrl(url: String) {
        bookmarkDao.deleteBookmarkByUrl(url)
    }

    override suspend fun deleteAllBookmarks() {
        bookmarkDao.deleteAllBookmarks()
    }
    
    override suspend fun searchBookmarks(query: String, limit: Int): List<Bookmark> {
        return bookmarkDao.searchBookmarks(query, limit)
    }
    
    override suspend fun isBookmarked(url: String): Boolean {
        return bookmarkDao.getBookmarkByUrl(url) != null
    }

    override suspend fun toggleBookmark(url: String, title: String): Boolean {
        return if (isBookmarked(url)) {
            deleteBookmarkByUrl(url)
            false
        } else {
            addBookmark(url, title)
            true
        }
    }
}

class BookmarkFolders {
    fun getDefaultFolders(): List<String> = listOf("Bookmarks Bar", "Mobile Bookmarks", "Other Bookmarks")
}
