package com.morealm.app.domain.repository

import android.content.Context
import com.morealm.app.domain.db.BookDao
import com.morealm.app.domain.db.CacheDao
import com.morealm.app.domain.db.ChapterDao
import com.morealm.app.domain.entity.Book
import com.morealm.app.domain.entity.BookFormat
import com.morealm.app.service.CacheBookService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CacheRepository @Inject constructor(
    private val bookDao: BookDao,
    private val chapterDao: ChapterDao,
    private val cacheDao: CacheDao,
    @ApplicationContext private val context: Context,
) {
    fun getWebBooks(): Flow<List<Book>> = bookDao.getAllBooks()
        .map { books -> books.filter { it.format == BookFormat.WEB && !it.sourceUrl.isNullOrBlank() } }

    val isDownloading: StateFlow<Boolean> = CacheBookService.isRunning
    val downloadProgress: StateFlow<CacheBookService.DownloadProgress> = CacheBookService.progress

    suspend fun getCacheStat(bookId: String, sourceUrl: String): Pair<Int, Int> {
        val chapters = chapterDao.getChaptersList(bookId)
        var cached = 0
        for (ch in chapters) {
            if (ch.url.isBlank() || ch.isVolume) continue
            if (cacheDao.get("chapter_content_${sourceUrl}_${ch.url}") != null) cached++
        }
        return chapters.size to cached
    }

    suspend fun clearCache(sourceUrl: String) {
        cacheDao.deleteByPrefix("chapter_content_${sourceUrl}_")
    }

    fun startDownload(bookId: String, sourceUrl: String, startIndex: Int = 0, endIndex: Int = -1) {
        CacheBookService.start(context, bookId, sourceUrl, startIndex, endIndex)
    }

    fun stopDownload() {
        CacheBookService.stop(context)
    }
}
