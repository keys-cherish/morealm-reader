package com.morealm.app.domain.repository

import androidx.paging.PagingSource
import com.morealm.app.domain.db.BookDao
import com.morealm.app.domain.db.BookGroupDao
import com.morealm.app.domain.db.ChapterDao
import com.morealm.app.domain.db.ReadProgressDao
import com.morealm.app.domain.entity.Book
import com.morealm.app.domain.entity.BookChapter
import com.morealm.app.domain.entity.BookGroup
import com.morealm.app.domain.entity.ReadProgress
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BookRepository @Inject constructor(
    private val bookDao: BookDao,
    private val chapterDao: ChapterDao,
    private val progressDao: ReadProgressDao,
    private val groupDao: BookGroupDao,
) {
    fun getAllBooks(): Flow<List<Book>> = bookDao.getAllBooks()

    /** Reset the "N 新" chapter-update badge — called when user opens the book. */
    suspend fun clearLastCheckCount(bookId: String) = bookDao.clearLastCheckCount(bookId)

    fun getBooksPaging(sortMode: String): PagingSource<Int, Book> = when (sortMode) {
        "recent" -> bookDao.getAllBooksByRecent()
        "addTime" -> bookDao.getAllBooksByAddTime()
        "format" -> bookDao.getAllBooksByFormat()
        else -> bookDao.getAllBooksPaging()
    }

    fun getUngroupedBooksPaging(sortMode: String): PagingSource<Int, Book> = when (sortMode) {
        "recent" -> bookDao.getUngroupedBooksByRecent()
        "addTime" -> bookDao.getUngroupedBooksByAddTime()
        "format" -> bookDao.getUngroupedBooksByFormat()
        else -> bookDao.getUngroupedBooksPaging()
    }

    fun countByFolderId(folderId: String): Flow<Int> = bookDao.countByFolderId(folderId)

    fun getBooksPagingByFolder(folderId: String, sortMode: String): PagingSource<Int, Book> = when (sortMode) {
        "recent" -> bookDao.getBooksByFolderByRecent(folderId)
        "addTime" -> bookDao.getBooksByFolderByAddTime(folderId)
        "format" -> bookDao.getBooksByFolderByFormat(folderId)
        else -> bookDao.getBooksByFolderPaging(folderId)
    }

    fun getBooksInFolder(folderId: String?): Flow<List<Book>> =
        bookDao.getBooksInFolder(folderId)

    fun getLastReadBook(): Flow<Book?> = bookDao.getLastReadBook()

    suspend fun getById(id: String): Book? = bookDao.getById(id)

    suspend fun insert(book: Book) = bookDao.insert(book)

    suspend fun insertAll(books: List<Book>) = bookDao.insertAll(books)

    /**
     * 批量 insert，chunked 防止 SQLite 单 SQL 过大。每 chunk 一次 @Insert 事务。
     * 1000 文件导入：原 1000 次 transaction → 20 次（chunk=50），开销降 50x。
     */
    suspend fun bulkInsert(books: List<Book>, batchSize: Int = 50) {
        if (books.isEmpty()) return
        books.chunked(batchSize).forEach { bookDao.insertAll(it) }
    }

    /**
     * 批量 update（每 chunk 一个 @Transaction，内部 forEach update）。
     * 用于 Phase 2 enrichment 并发完成后回写 metadata/cover/isComic。
     */
    suspend fun bulkUpdate(books: List<Book>, batchSize: Int = 50) {
        if (books.isEmpty()) return
        books.chunked(batchSize).forEach { bookDao.updateAll(it) }
    }

    suspend fun update(book: Book) = bookDao.update(book)

    /** 显式加入 / 移出书架（只翻 inBookshelf 标志，不动其它字段）。 */
    suspend fun setInBookshelf(id: String, inShelf: Boolean) = bookDao.setInBookshelf(id, inShelf)

    suspend fun delete(book: Book) = bookDao.delete(book)

    suspend fun deleteById(id: String) = bookDao.deleteById(id)

    suspend fun getBooksByFolderId(folderId: String): List<Book> =
        bookDao.getBooksByFolderId(folderId)

    suspend fun searchBooks(keyword: String): List<Book> =
        bookDao.searchBooksSync(keyword)

    suspend fun findByLocalPath(localPath: String): Book? =
        bookDao.findByLocalPath(localPath)

    suspend fun findByBookUrl(bookUrl: String, sourceUrl: String): Book? =
        bookDao.findByBookUrl(bookUrl, sourceUrl)

    suspend fun countLogicalBooks(): Int = bookDao.countLogicalBooks()

    suspend fun getAllBooksSync(): List<Book> = bookDao.getAllBooksSync()

    // Groups
    suspend fun insertGroup(group: BookGroup) = groupDao.insert(group)

    suspend fun deleteFolder(folderId: String) {
        bookDao.deleteByFolderId(folderId)
        groupDao.deleteById(folderId)
    }

    // Chapters
    fun getChapters(bookId: String): Flow<List<BookChapter>> =
        chapterDao.getChapters(bookId)

    suspend fun getChaptersList(bookId: String): List<BookChapter> =
        chapterDao.getChaptersList(bookId)

    suspend fun getChapter(bookId: String, index: Int): BookChapter? =
        chapterDao.getChapter(bookId, index)

    suspend fun saveChapters(bookId: String, chapters: List<BookChapter>) {
        // 防御：禁止空 list 覆盖已有缓存 —— 任何调用方传空都视为 no-op。
        // 历史 bug（2026-05-17 用户日志 12:48）：fallback 1-chapter placeholder 被
        // 误 save 到 DB → 下次启动 cached 是 1 章 placeholder → 用户看到的"章节没了"。
        // 此 guard 在 BookRepository 层兜底，无论上游调用方是否做 isNotEmpty 检查都安全。
        if (chapters.isEmpty()) {
            android.util.Log.w("BookRepository", "saveChapters skipped: empty list for bookId=$bookId (防止 wipe 已有 cache)")
            return
        }
        chapterDao.deleteByBookId(bookId)
        chapterDao.insertAll(chapters)
    }

    // Progress
    suspend fun getProgress(bookId: String): ReadProgress? =
        progressDao.getProgress(bookId)

    suspend fun saveProgress(progress: ReadProgress) =
        progressDao.save(progress)
}
