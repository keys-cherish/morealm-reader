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
 * 缓存策略（双层 L1 + L2，L3 = 网络由 [WebBook] 接手）：
 * - **L1 内存**：[ChapterMemoryCache]（50 MB LRU），命中 0.x ms
 * - **L2 磁盘**：CacheDao（SQLite），命中 ~10-50 ms
 * - key = "chapter_content_{sourceUrl}_{chapterUrl}"
 * - 无过期时间（章节内容不变）
 * - 预加载：当前章节加载后自动缓存前后各N章
 *
 * 写入路径同时落 L1 + L2；读取路径优先 L1，miss 再查 L2 并回填 L1。
 * 这样连续阅读会话内来回切前后章基本都在内存命中，DB 只负责"跨会话/重启后"的
 * 持久化。L1 满了由 LRU 自动淘汰最久未访问的章节（详见 [ChapterMemoryCache]）。
 */
object CacheBook {

    private lateinit var cacheDao: CacheDao

    fun init(dao: CacheDao) {
        cacheDao = dao
    }

    private fun chapterKey(sourceUrl: String, chapterUrl: String): String =
        "chapter_content_${sourceUrl}_$chapterUrl"

    /**
     * 获取缓存的章节内容。L1 内存命中 → 直接返回；L1 miss → 查 L2 磁盘 → 命中后
     * 回填 L1，下次同章访问就走内存。
     */
    suspend fun getContent(sourceUrl: String, chapterUrl: String): String? {
        // L1: 同步读，命中即走 — 不需要进入 IO dispatcher 切线程开销
        ChapterMemoryCache.get(sourceUrl, chapterUrl)?.let { return it }
        // L2: 切到 IO 跑 SQLite
        return withContext(Dispatchers.IO) {
            try {
                val raw = cacheDao.get(chapterKey(sourceUrl, chapterUrl))?.value
                if (raw != null) {
                    // 回填 L1，让后续同章访问走内存。空串不写（与 putContent 行为一致）。
                    ChapterMemoryCache.put(sourceUrl, chapterUrl, raw)
                }
                raw
            } catch (_: Exception) { null }
        }
    }

    /**
     * 缓存章节内容。同时写 L1 内存（同步、零延迟）+ L2 磁盘（IO）。
     * 若磁盘写失败，内存仍持有 — 当前会话仍能命中，下次冷启动会走网络重抓，
     * 与"完全没缓存"的行为对齐。
     */
    suspend fun putContent(sourceUrl: String, chapterUrl: String, content: String) {
        // L1 同步写
        ChapterMemoryCache.put(sourceUrl, chapterUrl, content)
        withContext(Dispatchers.IO) {
            try {
                cacheDao.insert(Cache(chapterKey(sourceUrl, chapterUrl), content, 0L))
            } catch (_: Exception) {}
        }
    }

    /**
     * 检查章节是否已缓存。优先 L1 内存查（避免一次 SQLite IO），miss 再过 DB。
     * 不回填 L1：仅"是否存在"语义，没拿到 value，下次 getContent 仍会走完整路径。
     */
    suspend fun isCached(sourceUrl: String, chapterUrl: String): Boolean {
        if (ChapterMemoryCache.get(sourceUrl, chapterUrl) != null) return true
        return withContext(Dispatchers.IO) {
            try {
                cacheDao.get(chapterKey(sourceUrl, chapterUrl)) != null
            } catch (_: Exception) { false }
        }
    }

    /**
     * 删除指定书源的所有章节缓存（L1 + L2 同步清）。
     */
    suspend fun clearBook(sourceUrl: String) = withContext(Dispatchers.IO) {
        // L1 内存清前缀匹配（同步），再清 L2 磁盘
        ChapterMemoryCache.clearBook(sourceUrl)
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
