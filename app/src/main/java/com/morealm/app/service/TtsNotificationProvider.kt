package com.morealm.app.service

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList

/**
 * Custom notification provider for TTS service.
 * Builds a Legado-style media notification with:
 * - Book title + chapter name
 * - Cover art as large icon
 * - 4 action buttons: prev chapter, play/pause, next chapter, stop
 * - MediaStyle with compact view showing first 3 buttons
 */
@UnstableApi
class TtsNotificationProvider(private val service: TtsService) : MediaNotification.Provider {

    override fun createNotification(
        mediaSession: MediaSession,
        customLayout: ImmutableList<CommandButton>,
        actionFactory: MediaNotification.ActionFactory,
        onNotificationChangedCallback: MediaNotification.Provider.Callback,
    ): MediaNotification {
        val player = mediaSession.player as? TtsPlayer
        val bookTitle = player?.bookTitle ?: ""
        val chapterTitle = player?.chapterTitle ?: ""
        val cover = player?.coverBitmap
        val isPlaying = player?.isPlaying == true
        val sleepMinutes = player?.sleepMinutes ?: 0

        val stateLabel = when {
            !isPlaying -> "暂停"
            sleepMinutes > 0 -> "朗读 · 定时 ${sleepMinutes} 分钟"
            else -> "朗读"
        }
        val title = "墨境 · $stateLabel: $bookTitle"
        val subtitle = chapterTitle.ifEmpty { "准备中…" }

        val builder = NotificationCompat.Builder(service, TtsService.CHANNEL_ID)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setSubText("朗读")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentTitle(title)
            .setContentText("《$subtitle》")
            .setVibrate(null)
            .setSound(null)
            .setLights(0, 0, 0)

        cover?.let { builder.setLargeIcon(it) }

        // Action: prev chapter
        builder.addAction(
            android.R.drawable.ic_media_previous,
            "上一章",
            buildActionIntent(TtsService.ACTION_PREV, 1),
        )
        // Action: play/pause
        if (isPlaying) {
            builder.addAction(
                android.R.drawable.ic_media_pause,
                "暂停",
                buildActionIntent(TtsService.ACTION_PAUSE, 2),
            )
        } else {
            builder.addAction(
                android.R.drawable.ic_media_play,
                "继续",
                buildActionIntent(TtsService.ACTION_RESUME, 3),
            )
        }
        // Action: next chapter
        builder.addAction(
            android.R.drawable.ic_media_next,
            "下一章",
            buildActionIntent(TtsService.ACTION_NEXT, 4),
        )
        // Action: stop
        builder.addAction(
            android.R.drawable.ic_delete,
            "停止",
            buildActionIntent(TtsService.ACTION_STOP, 5),
        )
        // Action: +10 minutes sleep timer
        builder.addAction(
            android.R.drawable.ic_menu_add,
            if (sleepMinutes > 0) "+10 (${sleepMinutes})" else "定时 +10",
            buildActionIntent(TtsService.ACTION_ADD_TIMER, 6),
        )

        // MediaStyle: show prev, play/pause, next in compact view
        @Suppress("DEPRECATION")
        val compatToken = mediaSession.sessionCompatToken
        builder.setStyle(
            androidx.media.app.NotificationCompat.MediaStyle()
                .setShowActionsInCompactView(0, 1, 2)
                .setMediaSession(compatToken)
        )

        return MediaNotification(TtsService.NOTIFICATION_ID, builder.build())
    }

    override fun handleCustomCommand(
        session: MediaSession,
        action: String,
        extras: Bundle,
    ): Boolean = false

    private fun buildActionIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(service, TtsService::class.java).apply {
            this.action = action
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        return PendingIntent.getService(service, requestCode, intent, flags)
    }
}
