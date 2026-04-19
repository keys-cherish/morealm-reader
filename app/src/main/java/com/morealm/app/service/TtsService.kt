package com.morealm.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
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
@AndroidEntryPoint
class TtsService : MediaSessionService(), AudioManager.OnAudioFocusChangeListener {

    private var mediaSession: MediaSession? = null
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var wasPlayingBeforeFocusLoss = false
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    companion object {
        const val CHANNEL_ID = "morealm_tts"
        const val NOTIFICATION_ID = 1001
        const val CMD_PREV_CHAPTER = "prev_chapter"
        const val CMD_NEXT_CHAPTER = "next_chapter"
        const val ACTION_STOP = "com.morealm.app.TTS_STOP"
    }

    override fun onCreate() {
        super.onCreate()
        // Call startForeground() ASAP to avoid ForegroundServiceDidNotStartInTimeException.
        // On some devices, if onCreate() takes too long before onStartCommand() runs,
        // the 5-second window expires.
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
                        player.updateMetadata(cmd.book, cmd.chapter)
                    }
                    is TtsEventBus.Command.SetPlaying -> {
                        val player = mediaSession?.player as? TtsPlayer ?: return@collect
                        player.setPlaying(cmd.playing)
                        if (cmd.playing) requestAudioFocus()
                    }
                    is TtsEventBus.Command.StopService -> {
                        stopSelfAndRelease()
                    }
                }
            }
        }
    }

    private fun stopSelfAndRelease() {
        abandonAudioFocus()
        stopSelf()
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
                wasPlayingBeforeFocusLoss = true
                TtsEventBus.sendEvent(TtsEventBus.Event.AudioFocusLoss)
                AppLog.info("TtsService", "Audio focus lost → pausing TTS")
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                wasPlayingBeforeFocusLoss = true
                TtsEventBus.sendEvent(TtsEventBus.Event.AudioFocusLoss)
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
        abandonAudioFocus()
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        AppLog.info("TtsService", "Service destroyed")
        super.onDestroy()
    }
}
