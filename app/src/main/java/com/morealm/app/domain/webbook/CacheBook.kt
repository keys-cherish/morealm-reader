package com.morealm.app.domain.webbook

import com.morealm.app.domain.db.CacheDao
import com.morealm.app.domain.entity.Cache
import com.morealm.app.domain.entity.BookSource
import com.morealm.app.core.log.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 网络书籍章节内容缓存 — 离线阅读核心
 *
 * 缓存策略：
 * - key = "chapter_content_{sourceUrl}_{chapterUrl}"
 * - 无过期时间（章节内容不变）
 * - 预加载：当前章节加载后自动缓存前后各N章
 */
object CacheBook {

    private lateinit var cacheDao: CacheDao

    fun init(dao: CacheDao) {
        cacheDao = dao
    }

    private fun chapterKey(sourceUrl: String, chapterUrl: String): String =
        "chapter_content_${sourceUrl}_$chapterUrl"

    /**
     * 获取缓存的章节内容
     */
    suspend fun getContent(sourceUrl: String, chapterUrl: String): String? =
        withContext(Dispatchers.IO) {
            try {
                cacheDao.get(chapterKey(sourceUrl, chapterUrl))?.value
            } catch (_: Exception) { null }
        }

    /**
     * 缓存章节内容
     */
    suspend fun putContent(sourceUrl: String, chapterUrl: String, content: String) =
        withContext(Dispatchers.IO) {
            try {
                cacheDao.insert(Cache(chapterKey(sourceUrl, chapterUrl), content, 0L))
            } catch (_: Exception) {}
        }

    /**
     * 检查章节是否已缓存
     */
    suspend fun isCached(sourceUrl: String, chapterUrl: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                cacheDao.get(chapterKey(sourceUrl, chapterUrl)) != null
            } catch (_: Exception) { false }
        }

    /**
     * 删除指定书源的所有章节缓存
     */
    suspend fun clearBook(sourceUrl: String) = withContext(Dispatchers.IO) {
        try {
            cacheDao.deleteByPrefix("chapter_content_${sourceUrl}_")
        } catch (_: Exception) {}
    }

    /**
     * 预加载章节内容（前后各preloadCount章）
     */
    suspend fun preload(
        bookSource: BookSource,
        chapters: List<ChapterResult>,
        currentIndex: Int,
        preloadCount: Int = 3,
    ) = withContext(Dispatchers.IO) {
        val sourceUrl = bookSource.bookSourceUrl
        val start = (currentIndex - preloadCount).coerceAtLeast(0)
        val end = (currentIndex + preloadCount + 1).coerceAtMost(chapters.size)

        for (i in start until end) {
            val ch = chapters[i]
            if (isCached(sourceUrl, ch.url)) continue
            try {
                val content = WebBook.getContentAwait(bookSource, ch.url, chapters.getOrNull(i + 1)?.url)
                if (content.isNotBlank()) {
                    putContent(sourceUrl, ch.url, content)
                    AppLog.debug("CacheBook", "Preloaded chapter $i: ${ch.title}")
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 用户离开阅读器或翻到下一窗口时正常 cancel — 不算错误，必须重抛
                // 让上层协程感知到取消（CancellationException 不能被吞）。
                throw e
            } catch (e: Exception) {
                AppLog.warn("CacheBook", "Preload chapter $i failed: ${e.message}")
            }
        }
    }

    /**
     * 批量下载所有章节（离线缓存整本书）
     */
    suspend fun downloadAll(
        bookSource: BookSource,
        chapters: List<ChapterResult>,
        onProgress: (current: Int, total: Int) -> Unit = { _, _ -> },
    ): Int = withContext(Dispatchers.IO) {
        val sourceUrl = bookSource.bookSourceUrl
        var cached = 0
        for ((i, ch) in chapters.withIndex()) {
            if (isCached(sourceUrl, ch.url)) {
                cached++
                onProgress(i + 1, chapters.size)
                continue
            }
            try {
                val content = WebBook.getContentAwait(bookSource, ch.url, chapters.getOrNull(i + 1)?.url)
                if (content.isNotBlank()) {
                    putContent(sourceUrl, ch.url, content)
                    cached++
                }
            } catch (e: Exception) {
                AppLog.warn("CacheBook", "Download chapter $i failed: ${e.message}")
            }
            onProgress(i + 1, chapters.size)
        }
        cached
    }
}
