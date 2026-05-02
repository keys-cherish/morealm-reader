package com.morealm.app.domain.repository

import com.morealm.app.domain.db.BookmarkDao
import com.morealm.app.domain.entity.Bookmark
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BookmarkRepository @Inject constructor(
    private val dao: BookmarkDao,
) {
    fun getBookmarks(bookId: String): Flow<List<Bookmark>> = dao.getBookmarks(bookId)

    /** 全局所有书签的 Flow。BookmarksScreen 订阅；删除/添加自动刷新。 */
    fun getAll(): Flow<List<Bookmark>> = dao.getAll()

    suspend fun insert(bookmark: Bookmark) = dao.insert(bookmark)

    suspend fun deleteById(id: String) = dao.deleteById(id)

    suspend fun deleteByBookId(bookId: String) = dao.deleteByBookId(bookId)

    suspend fun getAllSync(): List<Bookmark> = dao.getAllSync()
}
