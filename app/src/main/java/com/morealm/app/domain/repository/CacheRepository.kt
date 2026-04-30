package com.morealm.app.domain.repository

import android.content.Context
import android.net.Uri
import com.morealm.app.core.log.AppLog
import com.morealm.app.domain.db.BookDao
import com.morealm.app.domain.db.CacheDao
import com.morealm.app.domain.db.ChapterDao
import com.morealm.app.domain.db.ReplaceRuleDao
import com.morealm.app.domain.entity.Book
import com.morealm.app.domain.entity.BookFormat
import com.morealm.app.domain.webbook.ContentProcessor
import com.morealm.app.service.CacheBookService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CacheRepository @Inject constructor(
    private val bookDao: BookDao,
    private val chapterDao: ChapterDao,
    private val cacheDao: CacheDao,
    private val replaceRuleDao: ReplaceRuleDao,
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

    /**
     * Export a cached web book to a plain-text TXT file at [outputUri] (chosen via SAF
     * CreateDocument by the UI). Returns the count of chapters successfully written.
     *
     * Format:
     *   <title>\n
     *   作者: <author>\n
     *   <intro>\n\n────────\n\n
     *   <chapter 1 title + body, ContentProcessor-cleaned>\n\n
     *   <chapter 2 …>
     *
     * Chapters not yet cached are skipped (logged but not aborted) — partial export
     * is more useful than failing the whole file.
     */
    suspend fun exportTxt(
        book: Book,
        outputUri: Uri,
        onProgress: (current: Int, total: Int) -> Unit = { _, _ -> },
    ): Int = withContext(Dispatchers.IO) {
        val sourceUrl = book.sourceUrl ?: return@withContext 0
        val chapters = chapterDao.getChaptersList(book.id).filter { !it.isVolume && it.url.isNotBlank() }
        if (chapters.isEmpty()) return@withContext 0
        val processor = ContentProcessor(book.title, book.originName, replaceRuleDao)

        var written = 0
        try {
            context.contentResolver.openOutputStream(outputUri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use { bw ->
                // Header
                bw.write(book.title)
                bw.newLine()
                if (book.author.isNotBlank()) {
                    bw.write("作者：${book.author}")
                    bw.newLine()
                }
                if (!book.description.isNullOrBlank()) {
                    bw.newLine()
                    bw.write(book.description.trim())
                    bw.newLine()
                }
                bw.newLine()
                bw.write("─".repeat(20))
                bw.newLine()
                bw.newLine()
                // Chapters
                for ((i, ch) in chapters.withIndex()) {
                    val key = "chapter_content_${sourceUrl}_${ch.url}"
                    val cached = cacheDao.get(key)?.value
                    if (cached.isNullOrBlank()) {
                        AppLog.warn("Export", "skip uncached chapter [${i + 1}/${chapters.size}] ${ch.title}")
                        onProgress(i + 1, chapters.size)
                        continue
                    }
                    val processed = try {
                        processor.process(ch.title, cached, useReplace = true, includeTitle = true)
                    } catch (_: Exception) {
                        // ContentProcessor failure should not kill export — write raw.
                        "${ch.title}\n\n$cached"
                    }
                    bw.write(processed)
                    bw.newLine()
                    bw.newLine()
                    written++
                    onProgress(i + 1, chapters.size)
                }
                bw.flush()
            } ?: run {
                AppLog.warn("Export", "openOutputStream returned null for $outputUri")
            }
        } catch (e: Exception) {
            AppLog.warn("Export", "TXT export failed: ${e.message?.take(160)}")
            throw e
        }
        written
    }
}
