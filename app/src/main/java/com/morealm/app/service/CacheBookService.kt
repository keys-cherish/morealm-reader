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
import kotlinx.coroutines.sync.Semaphore
import javax.inject.Inject

/**
 * 离线缓存前台服务 — 后台批量下载章节内容
 */
@AndroidEntryPoint
class CacheBookService : Service() {

    companion object {
        const val CHANNEL_ID = "morealm_cache"
        const val NOTIFICATION_ID = 2001
        const val ACTION_START = "com.morealm.app.CACHE_START"
        const val ACTION_STOP = "com.morealm.app.CACHE_STOP"

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        private val _progress = MutableStateFlow(DownloadProgress())
        val progress: StateFlow<DownloadProgress> = _progress.asStateFlow()

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
    ) {
        val isComplete get() = completed + failed + cached >= total && total > 0
    }

    @Inject lateinit var chapterDao: ChapterDao
    @Inject lateinit var sourceDao: BookSourceDao
    @Inject lateinit var cacheDao: CacheDao

    private val serviceScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + AppLog.coroutineExceptionHandler("CacheService")
    )
    private var downloadJob: Job? = null

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
                stopDownload()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startDownload(bookId: String, sourceUrl: String, startIndex: Int, endIndex: Int) {
        downloadJob?.cancel()
        _isRunning.value = true
        _progress.value = DownloadProgress(bookId = bookId)

        downloadJob = serviceScope.launch {
            try {
                val source = sourceDao.getByUrl(sourceUrl)
                if (source == null) {
                    AppLog.error("CacheService", "Book source not found: $sourceUrl")
                    return@launch
                }

                val chapters = withContext(Dispatchers.IO) {
                    chapterDao.getChaptersList(bookId)
                }
                if (chapters.isEmpty()) {
                    AppLog.warn("CacheService", "No chapters for book: $bookId")
                    return@launch
                }

                val end = if (endIndex < 0) chapters.size - 1 else endIndex.coerceAtMost(chapters.size - 1)
                val targetChapters = chapters.filter { it.index in startIndex..end && it.url.isNotBlank() && !it.isVolume }

                _progress.value = DownloadProgress(bookId = bookId, total = targetChapters.size)

                val semaphore = Semaphore(3)
                val completedCount = java.util.concurrent.atomic.AtomicInteger(0)
                val failedCount = java.util.concurrent.atomic.AtomicInteger(0)
                val cachedCount = java.util.concurrent.atomic.AtomicInteger(0)

                val jobs = targetChapters.map { chapter ->
                    launch {
                        semaphore.acquire()
                        try {
                            ensureActive()
                            val cacheKey = chapterCacheKey(sourceUrl, chapter.url)
                            // Skip already cached
                            if (cacheDao.get(cacheKey) != null) {
                                cachedCount.incrementAndGet()
                                updateProgress(bookId, targetChapters.size, completedCount.get(), failedCount.get(), cachedCount.get())
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
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            failedCount.incrementAndGet()
                            AppLog.warn("CacheService", "Download ch${chapter.index} failed: ${e.message}")
                        } finally {
                            semaphore.release()
                            updateProgress(bookId, targetChapters.size, completedCount.get(), failedCount.get(), cachedCount.get())
                        }
                    }
                }
                jobs.joinAll()

                AppLog.info("CacheService", "Download complete: ${completedCount.get()} ok, ${failedCount.get()} failed, ${cachedCount.get()} cached")
            } catch (e: CancellationException) {
                AppLog.info("CacheService", "Download cancelled")
            } catch (e: Exception) {
                AppLog.error("CacheService", "Download error", e)
            } finally {
                _isRunning.value = false
                delay(2000) // Let UI see final state
                stopSelf()
            }
        }
    }

    private fun updateProgress(bookId: String, total: Int, completed: Int, failed: Int, cached: Int) {
        _progress.value = DownloadProgress(bookId, total, completed, failed, cached)
        updateNotification(completed + failed + cached, total)
    }

    private fun stopDownload() {
        downloadJob?.cancel()
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

    private fun updateNotification(current: Int, total: Int) {
        val notification = buildNotification("下载中 $current/$total", current, total)
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
            .addAction(android.R.drawable.ic_delete, "停止", stopPending)
            .build()
    }

    override fun onDestroy() {
        downloadJob?.cancel()
        serviceScope.cancel()
        _isRunning.value = false
        super.onDestroy()
    }
}
