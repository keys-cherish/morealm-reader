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

    /**
     * 批量清缓存：对每个 sourceUrl 调 [clearCache]。事务级一次性删除。返回受影响 source 数。
     */
    suspend fun clearCacheBatch(sourceUrls: Collection<String>): Int {
        var n = 0
        for (url in sourceUrls) {
            if (url.isBlank()) continue
            cacheDao.deleteByPrefix("chapter_content_${url}_")
            n++
        }
        return n
    }

    /**
     * 清理无效缓存（孤儿）— 任务 #4。
     *
     * 「无效」 = cache 表里某个 sourceUrl 已经没有任何 Book 在用。覆盖三种情况：
     *  1. 用户从书架删了书，BookSource 还在；
     *  2. 用户给某本书换了源，旧源缓存留下；
     *  3. 用户彻底删了 BookSource，cache 还残留。
     *
     * 算法：拉所有书的 sourceUrl 集合 → 扫描 chapter_content_* 全部 key → 对每个 key
     * 检查是否有任何 active sourceUrl 是它的前缀；不匹配就删。
     *
     * 注意：sourceUrl 中可能含 `_`（如 `https://book_x.com`），所以不用从 key 反推
     * sourceUrl，而是用 startsWith 暴力匹配。一次扫描 O(N×M)，N=cache 条目数，
     * M=活跃源数，量级万级×百级仍可秒级完成。
     *
     * @return 删除的孤儿条目数。
     */
    suspend fun clearOrphanedCache(): Int = withContext(Dispatchers.IO) {
        val activeSourceUrls = bookDao.getAllBooksSync()
            .mapNotNull { it.sourceUrl?.takeIf { url -> url.isNotBlank() } }
            .toSet()
        // 即使一本书都没有也得扫描（删全部）—— 允许返回正数代表确实清了东西。
        val keys = cacheDao.getAllChapterContentKeys()
        var deleted = 0
        for (key in keys) {
            // key 形如 chapter_content_<sourceUrl>_<chapterUrl>。
            val isOrphan = activeSourceUrls.none { src ->
                key.startsWith("chapter_content_${src}_")
            }
            if (isOrphan) {
                cacheDao.delete(key)
                deleted++
            }
        }
        AppLog.info("CacheCleanup", "Cleared $deleted orphaned cache entries (${activeSourceUrls.size} active sources)")
        deleted
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
