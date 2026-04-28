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
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.morealm.app.core.log.AppLog
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*

/**
 * TTS foreground service with MediaSession notification controls.
 * Provides: Play/Pause, Previous Chapter, Next Chapter in notification.
 * Listens to TtsEventBus.commands for metadata/state updates from ViewModel.
 */
@Suppress("DEPRECATION")
@AndroidEntryPoint
class TtsService : MediaSessionService(), AudioManager.OnAudioFocusChangeListener {

    private var mediaSession: MediaSession? = null
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var wasPlayingBeforeFocusLoss = false
    private val serviceScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main + AppLog.coroutineExceptionHandler("TtsService")
    )

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
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val earlyNotification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("朗读中")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setSilent(true)
            .build()
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, earlyNotification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0,
        )
        initMediaSession()
        setupAudioFocus()
        initNoisyReceiver()
        initPhoneStateListener()
        listenForCommands()
        AppLog.info("TtsService", "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelfAndRelease()
            return START_NOT_STICKY
        }
        // Post foreground notification immediately to avoid 5-second ANR crash.
        // MediaSessionService will replace this with the proper media notification.
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("朗读中")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setSilent(true)
            .build()
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0,
        )
        return super.onStartCommand(intent, flags, startId)
    }

    /** Listen for commands from ViewModel via TtsEventBus */
    private fun listenForCommands() {
        serviceScope.launch {
            TtsEventBus.commands.collect { cmd ->
                when (cmd) {
                    is TtsEventBus.Command.UpdateMeta -> {
                        val player = mediaSession?.player as? TtsPlayer ?: return@collect
                        player.updateMetadata(cmd.book, cmd.chapter, cmd.coverUrl)
                    }
                    is TtsEventBus.Command.SetPlaying -> {
                        val player = mediaSession?.player as? TtsPlayer ?: return@collect
                        player.setPlaying(cmd.playing)
                        if (cmd.playing) {
                            requestAudioFocus()
                            acquireWakeLocks()
                        } else {
                            releaseWakeLocks()
                        }
                    }
                    is TtsEventBus.Command.StopService -> {
                        stopSelfAndRelease()
                    }
                }
            }
        }
    }

    // ── WakeLock / WiFi Lock ──

    @android.annotation.SuppressLint("WakelockTimeout")
    private fun acquireWakeLocks() {
        if (!wakeLock.isHeld) wakeLock.acquire()
        wifiLock?.let { if (!it.isHeld) it.acquire() }
    }

    private fun releaseWakeLocks() {
        if (wakeLock.isHeld) wakeLock.release()
        wifiLock?.let { if (it.isHeld) it.release() }
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
                        TtsEventBus.sendEvent(TtsEventBus.Event.AudioFocusLoss(resumeOnGain = true))
                        AppLog.info("TtsService", "Incoming call → pausing TTS")
                    }
                    TelephonyManager.CALL_STATE_IDLE -> {
                        if (needResumeOnCallIdle) {
                            needResumeOnCallIdle = false
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

    /** Audio focus callback — pauses TTS when calls/WeChat/QQ audio interrupts */
    override fun onAudioFocusChange(focusChange: Int) {
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                val resumeOnGain = focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT &&
                    TtsEventBus.playbackState.value.isPlaying
                wasPlayingBeforeFocusLoss = resumeOnGain
                TtsEventBus.sendEvent(TtsEventBus.Event.AudioFocusLoss(resumeOnGain = resumeOnGain))
                AppLog.info("TtsService", "Audio focus lost → pausing TTS")
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                val resumeOnGain = TtsEventBus.playbackState.value.isPlaying
                wasPlayingBeforeFocusLoss = resumeOnGain
                TtsEventBus.sendEvent(TtsEventBus.Event.AudioFocusLoss(resumeOnGain = resumeOnGain))
                AppLog.debug("TtsService", "Audio focus ducking → pausing TTS")
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (wasPlayingBeforeFocusLoss) {
                    wasPlayingBeforeFocusLoss = false
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
