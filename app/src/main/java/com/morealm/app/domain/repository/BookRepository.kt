package com.morealm.app.domain.repository

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

    fun getBooksInFolder(folderId: String?): Flow<List<Book>> =
        bookDao.getBooksInFolder(folderId)

    fun getLastReadBook(): Flow<Book?> = bookDao.getLastReadBook()

    suspend fun getById(id: String): Book? = bookDao.getById(id)

    suspend fun insert(book: Book) = bookDao.insert(book)

    suspend fun insertAll(books: List<Book>) = bookDao.insertAll(books)

    suspend fun update(book: Book) = bookDao.update(book)

    suspend fun delete(book: Book) = bookDao.delete(book)

    suspend fun deleteById(id: String) = bookDao.deleteById(id)

    suspend fun getBooksByFolderId(folderId: String): List<Book> =
        bookDao.getBooksByFolderId(folderId)

    suspend fun searchBooks(keyword: String): List<Book> =
        bookDao.searchBooksSync(keyword)

    suspend fun findByLocalPath(localPath: String): Book? =
        bookDao.findByLocalPath(localPath)

    // Groups
    suspend fun insertGroup(group: BookGroup) = groupDao.insert(group)

    suspend fun deleteFolder(folderId: String) {
        bookDao.deleteByFolderId(folderId)
        groupDao.deleteById(folderId)
    }

    // Chapters
    fun getChapters(bookId: String): Flow<List<BookChapter>> =
        chapterDao.getChapters(bookId)

    suspend fun getChapter(bookId: String, index: Int): BookChapter? =
        chapterDao.getChapter(bookId, index)

    suspend fun saveChapters(bookId: String, chapters: List<BookChapter>) {
        chapterDao.deleteByBookId(bookId)
        chapterDao.insertAll(chapters)
    }

    // Progress
    suspend fun getProgress(bookId: String): ReadProgress? =
        progressDao.getProgress(bookId)

    suspend fun saveProgress(progress: ReadProgress) =
        progressDao.save(progress)
}
