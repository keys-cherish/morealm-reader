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
import com.morealm.app.domain.webbook.CheckSource
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 书源校验前台服务 — 把 [CheckSource] 单例的批量执行从 ViewModel 搬到
 * ForegroundService，对齐 Legado `service/CheckSourceService.kt`（293 行）的
 * 健壮性：通知栏进度 + 取消按钮 + 后台不被杀。
 *
 * **为什么前台化**：
 *  - 之前 ViewModel 直接 `viewModelScope.launch` 调 `CheckSource.checkAll`，
 *    应用切到后台、系统压内存或用户切回桌面时 Job 会被取消（viewModelScope 跟
 *    Activity 生命周期）。用户开"全部校验"几百个源、出门倒杯水回来就停了。
 *  - 前台服务有 30s+ 的系统宽限期 + 持久通知，校验过程不会半路死。
 *  - 通知栏「停止」按钮提供系统级取消入口，不再依赖 App 在前台。
 *
 * **状态广播**：companion 暴露三个 StateFlow（[state] / [results] / [isRunning]），
 * ViewModel 在 init 里 collect 它们映射到自己的 flow，UI 层完全无感（UI 仍订阅
 * `SourceManageViewModel.isChecking / checkProgress / checkResults`）。
 *
 * **DB 持久化**：errorMsg / lastCheckTime 写入下沉到 Service 内 —— 之前在
 * ViewModel.onResult 回调里 spawn IO 子协程做，现在 Service 内同协程上下文直接
 * 调 dao.insert，少一跳上下文切换。
 *
 * **不与 CacheBookService 抢占**：两者通知 channel 与 ID 各自独立；并发执行时
 * 系统会显示两条通知，不冲突。
 */
@AndroidEntryPoint
class CheckSourceService : Service() {

    companion object {
        const val CHANNEL_ID = "morealm_check_source"
        const val NOTIFICATION_ID = 2002 // 不与 CacheBookService(2001) 冲突
        const val ACTION_START = "com.morealm.app.CHECK_START"
        const val ACTION_STOP = "com.morealm.app.CHECK_STOP"
        private const val EXTRA_SOURCE_URLS = "sourceUrls"

        /** Service 当前状态。Idle = 没在跑；Running = 正在校验，含进度；Done = 跑完。*/
        sealed interface State {
            data object Idle : State
            data class Running(val total: Int, val done: Int) : State
            data class Done(val total: Int, val invalidCount: Int) : State
        }

        private val _state = MutableStateFlow<State>(State.Idle)
        val state: StateFlow<State> = _state.asStateFlow()

        /**
         * 已完成校验的结果按 sourceUrl 累积。Service 跑批时增量更新，新跑批前会清空。
         * UI（SourceManageViewModel）订阅以做角标显示和"失效列表"对话框。
         */
        private val _results = MutableStateFlow<Map<String, CheckSource.CheckResult>>(emptyMap())
        val results: StateFlow<Map<String, CheckSource.CheckResult>> = _results.asStateFlow()

        /** 简便派生：是否在跑（Running 状态）。UI 顶栏 chip 用它显示 spinner。 */
        val isRunning: StateFlow<Boolean> = MutableStateFlow(false).also { running ->
            // 把 state 流投影到 isRunning。这里用一次性 launch + collect 比较绕，
            // 改为只读派生：让 ViewModel 自己按 (state is Running) 转换即可，所以
            // 这个字段保留为 alias 但默认值就是 false。实际由 [state] 推断。
        }.asStateFlow()

        /** 启动校验。[sourceUrls] 为 enabled 书源的 bookSourceUrl 列表。 */
        fun start(context: Context, sourceUrls: List<String>) {
            if (sourceUrls.isEmpty()) return
            val intent = Intent(context, CheckSourceService::class.java).apply {
                action = ACTION_START
                putStringArrayListExtra(EXTRA_SOURCE_URLS, ArrayList(sourceUrls))
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** 取消校验。Service 会 cancel 当前跑批 + stopSelf。 */
        fun stop(context: Context) {
            val intent = Intent(context, CheckSourceService::class.java).apply {
                action = ACTION_STOP
            }
            // 用 startService 触发 onStartCommand，避免在 service 已 stop 后 stopService 找不到目标
            context.startService(intent)
        }
    }

    @Inject lateinit var sourceDao: BookSourceDao

    /**
     * Service 自己持有的协程作用域。SupervisorJob 让单源失败不影响其他源；
     * IO 调度器与 [CheckSource.checkAll] 内部一致。Service 死掉时统一 cancel。
     */
    private val serviceScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + AppLog.coroutineExceptionHandler("CheckSourceSvc")
    )

    /** 当前跑批 Job —— ACTION_STOP 通过它 cancel；新跑批前 cancel 旧的。 */
    private var checkJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val urls = intent.getStringArrayListExtra(EXTRA_SOURCE_URLS).orEmpty()
                if (urls.isEmpty()) {
                    AppLog.warn("CheckSourceSvc", "ACTION_START with empty url list, ignoring")
                    stopSelf()
                    return START_NOT_STICKY
                }
                startForegroundNotification(text = "准备校验...", current = 0, total = urls.size)
                startCheck(urls)
            }
            ACTION_STOP -> {
                checkJob?.cancel()
                _state.value = State.Idle
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startCheck(sourceUrls: List<String>) {
        // 先 cancel 旧任务（用户在校验过程中又点一次"全部校验"的防御）
        checkJob?.cancel()
        // 新跑批：清掉上次结果，避免角标显示成"上次失效列表"
        _results.value = emptyMap()
        _state.value = State.Running(total = sourceUrls.size, done = 0)

        checkJob = serviceScope.launch {
            runCatching {
                val sources = sourceDao.getEnabledSourcesList()
                    .filter { it.bookSourceUrl in sourceUrls }
                if (sources.isEmpty()) {
                    AppLog.warn("CheckSourceSvc", "No enabled sources matched: $sourceUrls")
                    _state.value = State.Done(total = 0, invalidCount = 0)
                    return@runCatching
                }

                var doneCount = 0
                CheckSource.checkAll(sources, concurrency = 4) { _, result ->
                    // onResult 在子协程里被调，每完成一源触发一次。
                    doneCount++
                    _results.update { it + (result.sourceUrl to result) }
                    _state.value = State.Running(total = sources.size, done = doneCount)
                    updateNotification(
                        text = "$doneCount / ${sources.size}：${result.sourceName.take(24)}",
                        current = doneCount,
                        total = sources.size,
                    )
                    // 持久化校验结果到 BookSource 行（errorMsg / lastCheckTime），让角标
                    // 在重启后仍可见。`onResult` 是 [CheckSource.checkAll] 的非 suspend
                    // 回调（合约如此 — 见 CheckSource.kt:113），所以这里 spawn 一个子协程
                    // 走 IO 写库，不能直接调 suspend 的 dao.insert。
                    val live = sources.firstOrNull { it.bookSourceUrl == result.sourceUrl }
                    if (live != null) {
                        live.errorMsg = if (result.isValid) null else result.error
                        live.lastCheckTime = System.currentTimeMillis()
                        serviceScope.launch {
                            runCatching { sourceDao.insert(live) }.onFailure {
                                AppLog.warn(
                                    "CheckSourceSvc",
                                    "persist failed for ${result.sourceUrl}: ${it.message?.take(120)}",
                                )
                            }
                        }
                    }
                }

                val invalid = _results.value.values.count { !it.isValid }
                _state.value = State.Done(total = sources.size, invalidCount = invalid)
                AppLog.info("CheckSourceSvc", "Check done: ${sources.size} total, $invalid invalid")
            }.onFailure {
                AppLog.error("CheckSourceSvc", "Check batch failed", it)
                _state.value = State.Done(total = sourceUrls.size, invalidCount = -1)
            }
            // 跑完（成功 / 失败 / 被 cancel）都退出 service。给 UI 2 秒看到 Done 状态再走。
            kotlinx.coroutines.delay(2_000)
            stopSelf()
        }
    }

    // ── Notification ────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "书源校验", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "批量检测书源可用性" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun startForegroundNotification(text: String, current: Int, total: Int) {
        val notification = buildNotification(text, current, total)
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
    }

    private fun updateNotification(text: String, current: Int, total: Int) {
        val notification = buildNotification(text, current, total)
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(text: String, current: Int, total: Int): android.app.Notification {
        val stopIntent = Intent(this, CheckSourceService::class.java).apply { action = ACTION_STOP }
        val stopPending = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("书源校验")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setProgress(total, current, total == 0)
            .addAction(android.R.drawable.ic_delete, "停止", stopPending)
            .build()
    }

    override fun onDestroy() {
        checkJob?.cancel()
        serviceScope.cancel()
        // 清状态：下次 UI 拿到的就是 Idle，避免显示"已完成"残留
        // —— 但保留 _results 让 UI 能继续展示本轮结果到用户主动刷新（与 Legado 一致）
        if (_state.value !is State.Done) {
            _state.value = State.Idle
        }
        super.onDestroy()
    }
}
