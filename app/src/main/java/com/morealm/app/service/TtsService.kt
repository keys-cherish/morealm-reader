package com.morealm.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.morealm.app.core.log.AppLog
import com.morealm.app.domain.db.HttpTtsDao
import com.morealm.app.domain.preference.AppPreferences
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.*

/**
 * TTS foreground service with MediaSession notification controls.
 * Provides: Play/Pause, Previous Chapter, Next Chapter in notification.
 * Listens to TtsEventBus.commands for metadata/state updates from ViewModel.
 */
@Suppress("DEPRECATION")
@UnstableApi
@AndroidEntryPoint
class TtsService : MediaSessionService(), AudioManager.OnAudioFocusChangeListener {

    @Inject lateinit var prefs: AppPreferences
    @Inject lateinit var httpTtsDao: HttpTtsDao
    @Inject lateinit var chapterContentLoader: com.morealm.app.domain.reader.ChapterContentLoader

    private var mediaSession: MediaSession? = null
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var wasPlayingBeforeFocusLoss = false
    private val serviceScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main + AppLog.coroutineExceptionHandler("TtsService")
    )

    /**
     * 自有的 provider 实例，被 Media3 注册（[setMediaNotificationProvider]）的同时
     * 也直接被 [refreshNotification] 拿来构造通知走 [NotificationManagerCompat.notify]
     * 兜底刷新 —— 解决 MIUI 14 / Android 13 上 Media3 不一定回调 Provider 的问题。
     */
    private var notifProvider: TtsNotificationProvider? = null

    /**
     * 上一次主动 notify 的关键字段快照，用于节流：仅在 isPlaying / sleepMinutes /
     * 标题 / 封面 hash 变化时才走 [refreshNotification]。否则段落级心跳会刷爆通知栏。
     */
    private data class NotifSnapshot(
        val isPlaying: Boolean,
        val sleepMinutes: Int,
        val bookTitle: String,
        val chapterTitle: String,
        val coverHash: Int,
    )
    private var lastNotifSnapshot: NotifSnapshot? = null

    /**
     * Owns the actual TTS reading state — engines, paragraph data, speakJob.
     * Lives for the whole service lifetime so playback survives ViewModel destruction.
     */
    private lateinit var engineHost: TtsEngineHost

    /**
     * 缓存的 [AppPreferences.ttsKeepCpuAwake] 当前值。
     *
     * 之所以缓存而不是每次 acquire 都 `runBlocking { prefs.first() }`：acquire 走在
     * 命令路径上（主线程），DataStore 首次读取毫秒级但仍是阻塞 IO；在用户高频
     * 切换"播放/暂停"时叠加可感知。改用 SharedFlow.collect 持续同步，写入 volatile
     * 字段，acquire 直接读字段——零阻塞、永远反映最新偏好。
     */
    @Volatile private var keepCpuAwakePref: Boolean = false

    // WakeLock: keep CPU alive when screen is off (ported from Legado)
    private val wakeLock by lazy {
        (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "morealm:TtsService")
            .apply { setReferenceCounted(false) }
    }

    // WiFi lock: keep network alive for online TTS (Edge/Http)
    private val wifiLock by lazy {
        @Suppress("DEPRECATION")
        (applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
            ?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "morealm:TtsAudio")
            ?.apply { setReferenceCounted(false) }
    }

    // Phone state listener: pause on incoming call (ported from Legado)
    private var phoneStateListener: PhoneStateListener? = null
    private var needResumeOnCallIdle = false

    // Broadcast receiver: pause when headphones unplugged
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY == intent.action) {
                AppLog.info("TtsService", "Audio becoming noisy (headphones unplugged) → pausing")
                if (::engineHost.isInitialized) engineHost.onAudioFocusLoss(resumeOnGain = false)
                TtsEventBus.sendEvent(TtsEventBus.Event.AudioFocusLoss(resumeOnGain = false))
            }
        }
    }

    companion object {
        const val CHANNEL_ID = "morealm_tts"
        const val NOTIFICATION_ID = 1001
        const val CMD_PREV_CHAPTER = "prev_chapter"
        const val CMD_NEXT_CHAPTER = "next_chapter"
        const val ACTION_STOP = "com.morealm.app.TTS_STOP"
        const val ACTION_PREV = "com.morealm.app.TTS_PREV"
        const val ACTION_NEXT = "com.morealm.app.TTS_NEXT"
        const val ACTION_PAUSE = "com.morealm.app.TTS_PAUSE"
        const val ACTION_RESUME = "com.morealm.app.TTS_RESUME"
        const val ACTION_ADD_TIMER = "com.morealm.app.TTS_ADD_TIMER"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // 占位通知：startForeground 必须在 onCreate 5s 内调一次，但此时 MediaSession
        // 还没建好，TtsNotificationProvider.createNotification 也还没机会跑。如果
        // 占位只放一行"朗读中"，用户会看到一个秃头通知；等 Media3 真正调
        // setMediaNotificationProvider 后才换成完整的 5 按钮 MediaStyle。
        //
        // 这里把占位写得尽量接近最终样子（同样的 channel / 同样的 small icon /
        // 同样的标题模板 + "正在加载…" 副标题 + 4 个 action 占位），让 Media3 后续
        // 替换通知时不至于"突变"，也避免 Media3 Provider 因 Player 状态没就绪
        // 一直不被回调时，用户永远停留在秃头版。
        val earlyNotification = buildEarlyPlaceholderNotification()
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, earlyNotification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0,
        )
        engineHost = TtsEngineHost(applicationContext, prefs, serviceScope, httpTtsDao, chapterContentLoader)
        engineHost.start()
        initMediaSession()
        // 装配自定义通知 provider。Media3 在收到首个 active media item 后才会
        // 调 createNotification。如果用户反映"通知栏永远是占位文案"，看 logcat
        // 里 TtsNotif 标签是否出现：从不出现说明 Provider 没被 Media3 调用。
        AppLog.info("TtsService", "setMediaNotificationProvider(TtsNotificationProvider)")
        notifProvider = TtsNotificationProvider(this).also {
            setMediaNotificationProvider(it)
        }
        setupAudioFocus()
        initNoisyReceiver()
        initPhoneStateListener()
        listenForCommands()
        observeKeepCpuAwakePref()
        AppLog.info("TtsService", "Service created (host-based architecture)")
    }

    /**
     * 持续把 [AppPreferences.ttsKeepCpuAwake] 同步到 [keepCpuAwakePref]。
     *
     * SharedFlow 不需要手动 cancel —— [serviceScope] 在 [onDestroy] 整体取消。
     */
    private fun observeKeepCpuAwakePref() {
        serviceScope.launch {
            prefs.ttsKeepCpuAwake.collect { keepCpuAwakePref = it }
        }
    }

    /**
     * 构造与 [TtsNotificationProvider] 一致的占位通知 —— 用户看到的不是秃头
     * "朗读中"，而是"朗读: 正在加载…"+ 4 个 action（上一章 / 暂停 / 下一章 / 停止）。
     * Media3 Provider 接管后会自动覆盖这条占位。
     */
    private fun buildEarlyPlaceholderNotification(): android.app.Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setSmallIcon(com.morealm.app.R.drawable.ic_tts_volume_up_24dp)
            .setSubText("朗读")
            .setContentTitle("朗读")
            .setContentText("正在加载…")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)

        // 4 个 action 占位，request code 与 TtsNotificationProvider 保持一致，
        // 后续 Media3 替换通知时 PendingIntent 不会重复创建。
        val flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                android.app.PendingIntent.FLAG_IMMUTABLE else 0
        fun pi(action: String, req: Int) = android.app.PendingIntent.getService(
            this, req,
            Intent(this, TtsService::class.java).apply { this.action = action },
            flags,
        )
        builder.addAction(
            com.morealm.app.R.drawable.ic_tts_skip_previous_24dp, "上一章", pi(ACTION_PREV, 1),
        )
        builder.addAction(
            com.morealm.app.R.drawable.ic_tts_pause_24dp, "暂停", pi(ACTION_PAUSE, 2),
        )
        builder.addAction(
            com.morealm.app.R.drawable.ic_tts_skip_next_24dp, "下一章", pi(ACTION_NEXT, 4),
        )
        builder.addAction(
            com.morealm.app.R.drawable.ic_tts_stop_24dp, "停止", pi(ACTION_STOP, 5),
        )
        return builder.build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelfAndRelease()
                return START_NOT_STICKY
            }
            ACTION_PREV -> {
                TtsEventBus.sendEvent(TtsEventBus.Event.PrevChapter)
                return START_NOT_STICKY
            }
            ACTION_NEXT -> {
                TtsEventBus.sendEvent(TtsEventBus.Event.NextChapter)
                return START_NOT_STICKY
            }
            ACTION_PAUSE -> {
                TtsEventBus.sendEvent(TtsEventBus.Event.PlayPause)
                return START_NOT_STICKY
            }
            ACTION_RESUME -> {
                TtsEventBus.sendEvent(TtsEventBus.Event.PlayPause)
                return START_NOT_STICKY
            }
            ACTION_ADD_TIMER -> {
                TtsEventBus.sendEvent(TtsEventBus.Event.AddTimer)
                return START_NOT_STICKY
            }
        }
        // Let MediaSessionService handle the foreground notification via TtsNotificationProvider.
        // The early notification from onCreate() covers the ANR window.
        return super.onStartCommand(intent, flags, startId)
    }

    /** Listen for commands from ViewModel and forward to the engine host. */
    private fun listenForCommands() {
        serviceScope.launch {
            TtsEventBus.commands.collect { cmd ->
                // TTS-DIAG #4 — service received a command from the bus.
                // If you see #2/#3 (ViewModel/controller send) but never see
                // this line, the service-side collector isn't wired (maybe
                // service was killed before the SharedFlow buffered the cmd,
                // or scope was cancelled). Each command logs its short form.
                AppLog.info("TTS", "Service.cmd: ${cmd::class.simpleName}")
                // Player metadata mirroring (notification needs these even for legacy commands).
                when (cmd) {
                    is TtsEventBus.Command.UpdateMeta -> {
                        val player = mediaSession?.player as? TtsPlayer
                        player?.updateMetadata(cmd.book, cmd.chapter, cmd.coverUrl)
                    }
                    is TtsEventBus.Command.LoadAndPlay -> {
                        val player = mediaSession?.player as? TtsPlayer
                        player?.updateMetadata(cmd.bookTitle, cmd.chapterTitle, cmd.coverUrl)
                        requestAudioFocus()
                        acquireWakeLocks()
                        playSilentSound()
                    }
                    is TtsEventBus.Command.Play -> {
                        requestAudioFocus()
                        acquireWakeLocks()
                        playSilentSound()
                    }
                    is TtsEventBus.Command.Pause -> {
                        releaseWakeLocks()
                    }
                    is TtsEventBus.Command.SetSleepMinutes -> {
                        (mediaSession?.player as? TtsPlayer)?.setSleepMinutes(cmd.minutes)
                    }
                    is TtsEventBus.Command.StopService -> {
                        engineHost.handleCommand(cmd)
                        stopSelfAndRelease()
                        return@collect
                    }
                    else -> { /* fall through to host */ }
                }
                engineHost.handleCommand(cmd)
                // Mirror playing state to TtsPlayer so MediaSession reflects it.
                (mediaSession?.player as? TtsPlayer)?.let { player ->
                    player.setPlaying(TtsEventBus.playbackState.value.isPlaying)
                    // 命令路径触发的状态变化也走兜底刷新，覆盖 SetSleepMinutes /
                    // Pause / Play 等不一定经 playbackState collect 的场景。
                    refreshNotificationIfChanged(player)
                }
            }
        }
        // Mirror host state changes back to TtsPlayer for notification updates.
        serviceScope.launch {
            TtsEventBus.playbackState.collect { state ->
                val player = mediaSession?.player as? TtsPlayer ?: return@collect
                player.setPlaying(state.isPlaying)
                if (state.bookTitle.isNotEmpty() || state.chapterTitle.isNotEmpty()) {
                    player.updateMetadata(state.bookTitle, state.chapterTitle, state.coverUrl)
                }
                // Bug 1 兜底：MIUI 14 / Android 13 上 Media3 不一定在 invalidateState 后
                // 回调 Provider.createNotification，导致暂停后通知栏依然显示"朗读: ..."。
                // 这里在每次 playbackState 变化时主动重发通知（节流后），强制覆盖。
                refreshNotificationIfChanged(player)
            }
        }
    }

    /**
     * 主动构造 TTS 通知并通过 [NotificationManagerCompat.notify] 重发，绕过 Media3 在
     * 部分 OEM ROM 上的不可靠 invalidate 链路。
     *
     * 节流：仅在 isPlaying / sleepMinutes / bookTitle / chapterTitle / cover 变化时
     * 才走系统 IPC，paragraphIndex 心跳不应触发刷新（否则通知栏会闪屏）。
     *
     * 失败模式：
     *  - POST_NOTIFICATIONS 权限被吊销 → notify 静默失败，沿用现有 Media3 链路
     *  - notifProvider 还没建好（onCreate 早期）→ 直接 return
     */
    private fun refreshNotificationIfChanged(player: TtsPlayer) {
        val provider = notifProvider ?: return
        val cover = player.coverBitmap
        val snapshot = NotifSnapshot(
            isPlaying = player.isPlaying,
            sleepMinutes = player.sleepMinutes,
            bookTitle = player.bookTitle,
            chapterTitle = player.chapterTitle,
            coverHash = cover?.let { System.identityHashCode(it) } ?: 0,
        )
        if (snapshot == lastNotifSnapshot) return
        lastNotifSnapshot = snapshot

        try {
            @Suppress("DEPRECATION")
            val compatToken = mediaSession?.sessionCompatToken
            val notification = provider.buildNotification(
                bookTitle = snapshot.bookTitle,
                chapterTitle = snapshot.chapterTitle,
                cover = cover,
                isPlaying = snapshot.isPlaying,
                sleepMinutes = snapshot.sleepMinutes,
                compatToken = compatToken,
            )
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
            AppLog.debug(
                "TtsService",
                "refreshNotification: playing=${snapshot.isPlaying} " +
                    "title='${snapshot.bookTitle}' chapter='${snapshot.chapterTitle}' " +
                    "sleep=${snapshot.sleepMinutes}",
            )
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS 在 Android 13+ 是 runtime 权限，被拒后 notify 抛
            // SecurityException。此场景下 Media3 自家也无法刷通知，吞掉只记 warn。
            AppLog.warn("TtsService", "notify denied (POST_NOTIFICATIONS): ${e.message}")
        } catch (e: Exception) {
            AppLog.warn("TtsService", "refreshNotification failed: ${e.message}", e)
        }
    }

    // ── WakeLock / WiFi Lock ──

    /**
     * 抢占续播所需资源。
     *
     * - **WakeLock**：仅在用户开启 [AppPreferences.ttsKeepCpuAwake] 偏好时申请。
     *   默认关闭，和 Legado 默认行为对齐——靠 audio playback 自带的 doze 豁免，
     *   不强抢 PARTIAL_WAKE_LOCK 节省电量。个别 ROM 锁屏断声问题可由用户在
     *   设置里手动启用换取稳定性。
     * - **WiFiLock**：仅在引擎类型为在线（Edge / HTTP）时申请。系统 TTS 不联网
     *   也持 `WIFI_MODE_FULL_HIGH_PERF` 没有意义，反而阻止 WiFi 进低功耗模式
     *   增加耗电。判断依据：[TtsEngineHost.currentEngineId]。
     */
    @android.annotation.SuppressLint("WakelockTimeout")
    private fun acquireWakeLocks() {
        if (keepCpuAwakePref && !wakeLock.isHeld) wakeLock.acquire()
        if (isOnlineEngineActive()) {
            wifiLock?.let { if (!it.isHeld) it.acquire() }
        }
    }

    private fun releaseWakeLocks() {
        if (wakeLock.isHeld) wakeLock.release()
        // WiFiLock 释放无条件——即使本次没 acquire（系统 TTS 路径），isHeld
        // 仍是 false，release 之前的检查会跳过；此处幂等。
        wifiLock?.let { if (it.isHeld) it.release() }
    }

    /** Edge / HTTP TTS 才需要持续网络；系统 TTS 完全本地合成，不需要 WiFiLock。 */
    private fun isOnlineEngineActive(): Boolean {
        if (!::engineHost.isInitialized) return false
        val id = engineHost.currentEngineId
        return id == "edge" || id.startsWith("http_")
    }

    /**
     * "Stupid Android 8 Oreo hack" —— 移植自 Legado-MD3 的 [MediaHelp.playSilentSound]。
     *
     * 在朗读启动瞬间播一段无声 mp3，让系统把 media button 路由（蓝牙耳机按键 / 有线耳机
     * 中键 / Android Auto 控制）锁定到本应用的 MediaSession。如果没有这一步，激进 ROM
     * （部分小米 / 华为旧版本）在 TTS 长时间合成时可能把进程当成"无媒体活动"挂起，导致
     * 暂停/继续按键失灵。
     *
     * 失败不抛 —— 资源缺失或 MediaPlayer 创建失败时只 warn，不影响朗读主流程。
     */
    private fun playSilentSound() {
        runCatching {
            val mp = MediaPlayer.create(this, com.morealm.app.R.raw.silent_sound)
                ?: run {
                    AppLog.warn("TtsService", "playSilentSound: MediaPlayer.create returned null")
                    return
                }
            mp.setOnCompletionListener { it.release() }
            mp.start()
        }.onFailure {
            AppLog.warn("TtsService", "playSilentSound failed: ${it.message}")
        }
    }

    private fun stopSelfAndRelease() {
        releaseWakeLocks()
        abandonAudioFocus()
        stopSelf()
    }

    // ── AUDIO_BECOMING_NOISY: pause when headphones unplugged ──

    private fun initNoisyReceiver() {
        val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(noisyReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(noisyReceiver, filter)
        }
    }

    // ── PhoneStateListener: pause on incoming call ──

    private fun initPhoneStateListener() {
        val tm = getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return
        phoneStateListener = object : PhoneStateListener() {
            override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                when (state) {
                    TelephonyManager.CALL_STATE_RINGING -> {
                        needResumeOnCallIdle = true
                        engineHost.onAudioFocusLoss(resumeOnGain = true)
                        TtsEventBus.sendEvent(TtsEventBus.Event.AudioFocusLoss(resumeOnGain = true))
                        AppLog.info("TtsService", "Incoming call → pausing TTS")
                    }
                    TelephonyManager.CALL_STATE_IDLE -> {
                        if (needResumeOnCallIdle) {
                            needResumeOnCallIdle = false
                            engineHost.onAudioFocusGain()
                            TtsEventBus.sendEvent(TtsEventBus.Event.AudioFocusGain)
                            AppLog.info("TtsService", "Call ended → resuming TTS")
                        }
                    }
                }
            }
        }
        try {
            tm.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE)
        } catch (e: SecurityException) {
            AppLog.warn("TtsService", "No READ_PHONE_STATE permission, skipping phone listener")
            phoneStateListener = null
        }
    }

    private fun unregisterPhoneStateListener() {
        phoneStateListener?.let { listener ->
            try {
                val tm = getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
                tm?.listen(listener, PhoneStateListener.LISTEN_NONE)
            } catch (_: Exception) {}
            phoneStateListener = null
        }
    }

    private fun setupAudioFocus() {
        audioManager = getSystemService(AudioManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setOnAudioFocusChangeListener(this)
                .setWillPauseWhenDucked(true)
                .build()
        }
    }

    /** Audio focus callback — pauses TTS via the host when calls/WeChat/QQ audio interrupts. */
    override fun onAudioFocusChange(focusChange: Int) {
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                val resumeOnGain = focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT &&
                    TtsEventBus.playbackState.value.isPlaying
                wasPlayingBeforeFocusLoss = resumeOnGain
                engineHost.onAudioFocusLoss(resumeOnGain)
                TtsEventBus.sendEvent(TtsEventBus.Event.AudioFocusLoss(resumeOnGain = resumeOnGain))
                AppLog.info("TtsService", "Audio focus lost → pausing TTS")
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                val resumeOnGain = TtsEventBus.playbackState.value.isPlaying
                wasPlayingBeforeFocusLoss = resumeOnGain
                engineHost.onAudioFocusLoss(resumeOnGain)
                TtsEventBus.sendEvent(TtsEventBus.Event.AudioFocusLoss(resumeOnGain = resumeOnGain))
                AppLog.debug("TtsService", "Audio focus ducking → pausing TTS")
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (wasPlayingBeforeFocusLoss) {
                    wasPlayingBeforeFocusLoss = false
                    engineHost.onAudioFocusGain()
                    TtsEventBus.sendEvent(TtsEventBus.Event.AudioFocusGain)
                    AppLog.info("TtsService", "Audio focus regained → resuming TTS")
                }
            }
        }
    }

    fun requestAudioFocus(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = audioFocusRequest ?: return false
            audioManager?.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            audioManager?.requestAudioFocus(
                this, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager?.abandonAudioFocus(this)
        }
    }

    private fun initMediaSession() {
        val player = TtsPlayer(this)
        mediaSession = MediaSession.Builder(this, player)
            .setCallback(TtsSessionCallback())
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    private inner class TtsSessionCallback : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            // Add custom commands for prev/next chapter
            val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(SessionCommand(CMD_PREV_CHAPTER, Bundle.EMPTY))
                .add(SessionCommand(CMD_NEXT_CHAPTER, Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(sessionCommands)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                CMD_PREV_CHAPTER -> {
                    AppLog.info("TtsService", "Prev chapter from notification")
                    TtsEventBus.sendEvent(TtsEventBus.Event.PrevChapter)
                }
                CMD_NEXT_CHAPTER -> {
                    AppLog.info("TtsService", "Next chapter from notification")
                    TtsEventBus.sendEvent(TtsEventBus.Event.NextChapter)
                }
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "朗读服务",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "TTS 朗读通知"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        if (::engineHost.isInitialized) engineHost.release()
        serviceScope.cancel()
        releaseWakeLocks()
        abandonAudioFocus()
        unregisterPhoneStateListener()
        try { unregisterReceiver(noisyReceiver) } catch (_: Exception) {}
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        AppLog.info("TtsService", "Service destroyed")
        super.onDestroy()
    }
}
