package com.morealm.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.morealm.app.core.log.AppLog
import com.morealm.app.domain.db.BookSourceDao
import com.morealm.app.domain.db.CacheDao
import com.morealm.app.domain.db.ChapterDao
import com.morealm.app.domain.entity.Cache
import com.morealm.app.domain.webbook.WebBook
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Semaphore
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

/**
 * 离线缓存前台服务 — 后台批量下载章节内容
 *
 * 多本并行模型（2026-05 重构）：
 *  - 一个 [downloadJobs] 表（bookId → Job）替代了原来的单一 downloadJob。
 *    新启动一本书的下载不再 cancel 其它正在下载的书。
 *  - 一个 [_progresses] map 替代了原来的单一 _progress。UI 用它做：
 *      * 顶栏"全局总进度" = sum(total) / sum(done) 跨所有书
 *      * 每本书的本地进度 = progresses[bookId]
 *    两者不再冲突。
 *  - 一个 **全局** [globalSemaphore] (4 个槽)替代了原来的每书 Semaphore(3)。
 *    多本同时缓存时总请求数依然封顶 4，避免书源被 rate-limit。单本独占时
 *    速度不变。
 *  - 服务退出条件改为"所有 jobs 都完成"（之前是单 job 完成就 stopSelf）。
 */
@AndroidEntryPoint
class CacheBookService : Service() {

    companion object {
        const val CHANNEL_ID = "morealm_cache"
        const val NOTIFICATION_ID = 2001
        const val ACTION_START = "com.morealm.app.CACHE_START"
        const val ACTION_STOP = "com.morealm.app.CACHE_STOP"

        /** 全局并发槽数。多本并行时所有书共享，避免 N×3 hammer 书源。 */
        private const val GLOBAL_PARALLELISM = 4

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        /**
         * 全局进度表 — bookId → 当前进度。
         * 一本书启动时插入一条；完成后保留（让 UI 仍然能展示"已完成"状态）；
         * 直到 [ACTION_STOP] 或全部完成 stopSelf 时清空。
         */
        private val _progresses = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())
        val progresses: StateFlow<Map<String, DownloadProgress>> = _progresses.asStateFlow()

        /**
         * Legacy 单本进度兼容 — ShelfViewModel / BookDetailViewModel 仍按"单本"语义订阅。
         * 取 progresses 中第一本未完成的（无则取任意一本，再无则空进度）。
         * 多本并行时该值会"跳"，但调用方原本就是判断 bookId 是否匹配自己关心的那本，所以
         * 不会读到错误的状态 — 它只是从"任意一本"中挑了一本作为读哨。
         *
         * 长期看应迁移到订阅 [progresses] 自取所需，但保留兼容避免一次大改炸太多。
         */
        private val _progressLegacy = MutableStateFlow(DownloadProgress())
        val progress: StateFlow<DownloadProgress> = _progressLegacy.asStateFlow()

        fun start(context: Context, bookId: String, sourceUrl: String, startIndex: Int, endIndex: Int) {
            val intent = Intent(context, CacheBookService::class.java).apply {
                action = ACTION_START
                putExtra("bookId", bookId)
                putExtra("sourceUrl", sourceUrl)
                putExtra("startIndex", startIndex)
                putExtra("endIndex", endIndex)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, CacheBookService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    data class DownloadProgress(
        val bookId: String = "",
        val total: Int = 0,
        val completed: Int = 0,
        val failed: Int = 0,
        val cached: Int = 0,
        val message: String = "",
    ) {
        val isComplete get() = completed + failed + cached >= total && total > 0
    }

    @Inject lateinit var chapterDao: ChapterDao
    @Inject lateinit var sourceDao: BookSourceDao
    @Inject lateinit var cacheDao: CacheDao

    private val serviceScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + AppLog.coroutineExceptionHandler("CacheService")
    )

    /** 每本书一个 Job — bookId 作为 key，方便 startDownload 时只 cancel 对应那本。 */
    private val downloadJobs = ConcurrentHashMap<String, Job>()

    /**
     * 全局共享并发槽。所有书的下载协程都从这里 acquire 一个槽位。
     * 单本独占时等价于"该书 4 并发"；N 本并行时所有书一起最多 4 并发。
     */
    private val globalSemaphore = Semaphore(GLOBAL_PARALLELISM)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val bookId = intent.getStringExtra("bookId") ?: return START_NOT_STICKY
                val sourceUrl = intent.getStringExtra("sourceUrl") ?: return START_NOT_STICKY
                val startIndex = intent.getIntExtra("startIndex", 0)
                val endIndex = intent.getIntExtra("endIndex", -1)
                startForegroundNotification()
                startDownload(bookId, sourceUrl, startIndex, endIndex)
            }
            ACTION_STOP -> {
                stopAllDownloads()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    /**
     * 启动一本书的下载。
     *
     * 重要：**不再 cancel 其它书的 jobs**。每本书有独立 Job；只 cancel 同 bookId
     * 的旧 Job（如果该书已经在缓存中又点了「全部缓存」，旧任务会被替换）。
     *
     * 注意：上层 ViewModel 已经会做"重复点击"检测并 toast 提示，理论上这里
     * 不会进到 cancel-old 分支；但保留作为防御性处理。
     */
    private fun startDownload(bookId: String, sourceUrl: String, startIndex: Int, endIndex: Int) {
        // 仅 cancel 同一本书的旧 job — 其它书的下载不受影响。
        downloadJobs[bookId]?.cancel()

        // 标记 service 整体在跑（任意一本书在跑就 true）。Compose UI 用这个判断顶栏是否显示。
        _isRunning.value = true
        // 在 progresses map 里登记这本书的初始进度，UI 立刻能看到"准备中"。
        updateProgressMap(bookId) { DownloadProgress(bookId = bookId) }

        val job = serviceScope.launch {
            try {
                val source = sourceDao.getByUrl(sourceUrl)
                if (source == null) {
                    AppLog.error("CacheService", "Book source not found: $sourceUrl")
                    updateProgressMap(bookId) {
                        DownloadProgress(bookId = bookId, failed = 1, message = "书源不存在或已禁用")
                    }
                    return@launch
                }

                val chapters = withContext(Dispatchers.IO) {
                    chapterDao.getChaptersList(bookId)
                }
                if (chapters.isEmpty()) {
                    AppLog.warn("CacheService", "No chapters for book: $bookId")
                    updateProgressMap(bookId) {
                        DownloadProgress(bookId = bookId, failed = 1, message = "书源无章节，无法缓存")
                    }
                    return@launch
                }

                val end = if (endIndex < 0) chapters.size - 1 else endIndex.coerceAtMost(chapters.size - 1)
                val targetChapters = chapters.filter {
                    it.index in startIndex..end && it.url.isNotBlank() && !it.isVolume
                }

                updateProgressMap(bookId) {
                    DownloadProgress(bookId = bookId, total = targetChapters.size)
                }
                if (targetChapters.isEmpty()) {
                    AppLog.warn("CacheService", "No downloadable chapters for book: $bookId")
                    updateProgressMap(bookId) {
                        DownloadProgress(
                            bookId = bookId,
                            total = 0,
                            failed = 1,
                            message = "没有可缓存章节：目录为空、章节链接为空或当前选择范围没有正文章节",
                        )
                    }
                    return@launch
                }

                val completedCount = java.util.concurrent.atomic.AtomicInteger(0)
                val failedCount = java.util.concurrent.atomic.AtomicInteger(0)
                val cachedCount = java.util.concurrent.atomic.AtomicInteger(0)
                val lastError = java.util.concurrent.atomic.AtomicReference("")

                val jobs = targetChapters.map { chapter ->
                    launch {
                        // 全局信号量：所有书共享同一个 semaphore，防止 N×3 hammer。
                        globalSemaphore.acquire()
                        try {
                            ensureActive()
                            val cacheKey = chapterCacheKey(sourceUrl, chapter.url)
                            // Skip already cached
                            if (cacheDao.get(cacheKey) != null) {
                                cachedCount.incrementAndGet()
                                pushProgress(
                                    bookId, targetChapters.size,
                                    completedCount.get(), failedCount.get(), cachedCount.get(),
                                )
                                return@launch
                            }

                            val nextUrl = chapters.getOrNull(chapter.index + 1)?.url
                            val content = withTimeout(30_000L) {
                                WebBook.getContentAwait(source, chapter.url, nextUrl)
                            }
                            if (content.isNotBlank()) {
                                cacheDao.insert(Cache(cacheKey, content, 0L))
                                completedCount.incrementAndGet()
                            } else {
                                failedCount.incrementAndGet()
                                lastError.compareAndSet(
                                    "",
                                    "第 ${chapter.index + 1} 章返回空内容：${chapter.title.ifBlank { chapter.url }}",
                                )
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            failedCount.incrementAndGet()
                            val message = readableError(e)
                            lastError.compareAndSet("", "第 ${chapter.index + 1} 章失败：$message")
                            AppLog.warn("CacheService", "Download ch${chapter.index} failed: $message")
                        } finally {
                            globalSemaphore.release()
                            pushProgress(
                                bookId, targetChapters.size,
                                completedCount.get(), failedCount.get(), cachedCount.get(),
                            )
                        }
                    }
                }
                jobs.joinAll()

                if (failedCount.get() > 0) {
                    val detail = lastError.get().ifBlank { "请换源或稍后重试" }
                    updateProgressMap(bookId) {
                        (it ?: DownloadProgress(bookId = bookId)).copy(message = "缓存失败：$detail")
                    }
                }
                AppLog.info(
                    "CacheService",
                    "Download complete bookId=$bookId: ${completedCount.get()} ok, " +
                        "${failedCount.get()} failed, ${cachedCount.get()} cached",
                )
            } catch (e: CancellationException) {
                AppLog.info("CacheService", "Download cancelled bookId=$bookId")
            } catch (e: Exception) {
                AppLog.error("CacheService", "Download error bookId=$bookId", e)
            } finally {
                downloadJobs.remove(bookId)
                // 当且仅当所有 jobs 都收尾后才退出 service。多本并行时其它书还在跑就不能退。
                if (downloadJobs.isEmpty()) {
                    _isRunning.value = false
                    delay(2000) // 给 UI 一点时间看到最终态
                    if (downloadJobs.isEmpty()) {
                        // 二次确认（delay 期间可能又有新任务进来）
                        _progresses.value = emptyMap()
                        stopSelf()
                    }
                }
            }
        }
        downloadJobs[bookId] = job
    }

    /** 把单本进度推到全局 progresses map + 更新通知聚合文案。 */
    private fun pushProgress(bookId: String, total: Int, completed: Int, failed: Int, cached: Int) {
        val currentMessage = _progresses.value[bookId]?.message.orEmpty()
        updateProgressMap(bookId) {
            DownloadProgress(bookId, total, completed, failed, cached, currentMessage)
        }
        updateNotification()
    }

    /** 用 transform 函数原子更新某 bookId 的进度。transform 拿到当前值（可能为 null）。 */
    private fun updateProgressMap(
        bookId: String,
        transform: (DownloadProgress?) -> DownloadProgress,
    ) {
        _progresses.update { current ->
            current + (bookId to transform(current[bookId]))
        }
        // 同步刷新 legacy 单本进度兼容字段。
        syncLegacyProgress()
    }

    /** Legacy 兼容：把任意一本未完成（或最近一本）写到 [_progressLegacy]，方便老订阅者读。 */
    private fun syncLegacyProgress() {
        val all = _progresses.value
        val pick = all.values.firstOrNull { !it.isComplete }
            ?: all.values.lastOrNull()
            ?: DownloadProgress()
        _progressLegacy.value = pick
    }

    private fun readableError(e: Throwable): String {
        val raw = e.localizedMessage ?: e.message ?: e::class.java.simpleName
        return raw
            .replace('\n', ' ')
            .replace('\r', ' ')
            .trim()
            .ifBlank { e::class.java.simpleName }
            .take(180)
    }

    /** 停止所有下载（顶栏「停止」按钮）。 */
    private fun stopAllDownloads() {
        downloadJobs.values.forEach { it.cancel() }
        downloadJobs.clear()
        _isRunning.value = false
    }

    private fun chapterCacheKey(sourceUrl: String, chapterUrl: String): String =
        "chapter_content_${sourceUrl}_$chapterUrl"

    // ── Notification ──

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "离线缓存", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "小说章节离线下载" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun startForegroundNotification() {
        val notification = buildNotification("准备下载...", 0, 0)
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
    }

    /**
     * 通知文案聚合所有书：「3 本进行中 · 234/980 章」。
     * 进度条用全局 done/total，给用户单一进度感（避免每本一条通知）。
     */
    private fun updateNotification() {
        val all = _progresses.value.values
        val activeBooks = all.count { !it.isComplete }
        val total = all.sumOf { it.total }
        val done = all.sumOf { it.completed + it.failed + it.cached }
        val text = if (activeBooks > 1) {
            "$activeBooks 本进行中 · $done/$total 章"
        } else {
            "下载中 $done/$total"
        }
        val notification = buildNotification(text, done, total)
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(text: String, current: Int, total: Int): android.app.Notification {
        val stopIntent = Intent(this, CacheBookService::class.java).apply { action = ACTION_STOP }
        val stopPending = PendingIntent.getService(
            this, 0, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("离线缓存")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setProgress(total, current, total == 0)
            .addAction(android.R.drawable.ic_delete, "停止全部", stopPending)
            .build()
    }

    override fun onDestroy() {
        downloadJobs.values.forEach { it.cancel() }
        downloadJobs.clear()
        serviceScope.cancel()
        _isRunning.value = false
        _progresses.value = emptyMap()
        super.onDestroy()
    }
}
