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
import com.morealm.app.R
import com.morealm.app.core.log.AppLog

/**
 * Custom notification provider for the TTS service — ported visual semantics from
 * Legado's [`BaseReadAloudService.createNotification`]:
 *
 *  - **Title** uses Legado's tri-state template:
 *      - playing: `朗读: <book>`
 *      - paused: `朗读暂停: <book>`
 *      - timer running: `朗读定时 N 分钟: <book>`
 *  - **Subtitle** = chapter title (no decorative quotes; falls back to "正在加载…")
 *  - **SubText** = "朗读" (channel hint shown in the small badge)
 *  - **Small icon** = volume_up (matches Legado `ic_volume_up`)
 *  - **Large icon** = book cover bitmap when available
 *  - **Actions** = prev / play-pause / next / stop / +10-minute timer (5 buttons,
 *    same order and semantics as Legado)
 *  - **Compact view** shows actions [0..2] = prev / play-pause / next
 *  - **Tap target** opens the launcher activity ([`Class.forName`-resolved] to avoid
 *    a hard import cycle into UI code)
 *  - Vibration / sound / lights all suppressed for an unobtrusive media notification.
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

        @Suppress("DEPRECATION")
        val compatToken = mediaSession.sessionCompatToken

        AppLog.debug(
            "TtsNotif",
            "createNotification(via Media3): book='$bookTitle', chapter='$chapterTitle', " +
                "isPlaying=$isPlaying, sleepMin=$sleepMinutes",
        )

        val notification = buildNotification(
            bookTitle = bookTitle,
            chapterTitle = chapterTitle,
            cover = cover,
            isPlaying = isPlaying,
            sleepMinutes = sleepMinutes,
            compatToken = compatToken,
        )
        return MediaNotification(TtsService.NOTIFICATION_ID, notification)
    }

    /**
     * 构造完整的 TTS 通知 —— 抽出来给 [TtsService] 直接走 [NotificationManagerCompat.notify]
     * 兜底刷新用，绕过 Media3 在某些 OEM ROM（MIUI 14、Android 13 部分机型）上
     * `invalidateState()` 之后不一定回调 [createNotification] 的不可靠链路。
     *
     * 调用方负责节流（仅在 isPlaying / sleepMinutes / 标题 / 封面 变化时刷），
     * 否则段落级的 paragraphIndex 心跳会刷爆通知栏。
     */
    fun buildNotification(
        bookTitle: String,
        chapterTitle: String,
        cover: android.graphics.Bitmap?,
        isPlaying: Boolean,
        sleepMinutes: Int,
        compatToken: android.support.v4.media.session.MediaSessionCompat.Token?,
    ): android.app.Notification {
        // Legado-style title template
        val statePrefix = when {
            !isPlaying -> "朗读暂停"
            sleepMinutes > 0 -> "朗读定时 $sleepMinutes 分钟"
            else -> "朗读"
        }
        val title = if (bookTitle.isNotBlank()) "$statePrefix: $bookTitle" else statePrefix
        val subtitle = chapterTitle.ifBlank { "正在加载…" }

        val builder = NotificationCompat.Builder(service, TtsService.CHANNEL_ID)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setSmallIcon(R.drawable.ic_tts_volume_up_24dp)
            .setSubText("朗读")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentTitle(title)
            .setContentText(subtitle)
            .setContentIntent(buildContentIntent())
            .setVibrate(null)
            .setSound(null)
            .setLights(0, 0, 0)

        cover?.let { builder.setLargeIcon(it) }

        // Action 0: prev chapter
        builder.addAction(
            R.drawable.ic_tts_skip_previous_24dp,
            "上一章",
            buildActionIntent(TtsService.ACTION_PREV, REQ_PREV),
        )
        // Action 1: play / pause (mutually exclusive, drawable changes by state)
        if (isPlaying) {
            builder.addAction(
                R.drawable.ic_tts_pause_24dp,
                "暂停",
                buildActionIntent(TtsService.ACTION_PAUSE, REQ_PAUSE),
            )
        } else {
            builder.addAction(
                R.drawable.ic_tts_play_arrow_24dp,
                "继续",
                buildActionIntent(TtsService.ACTION_RESUME, REQ_RESUME),
            )
        }
        // Action 2: next chapter
        builder.addAction(
            R.drawable.ic_tts_skip_next_24dp,
            "下一章",
            buildActionIntent(TtsService.ACTION_NEXT, REQ_NEXT),
        )
        // Action 3: stop service
        builder.addAction(
            R.drawable.ic_tts_stop_24dp,
            "停止",
            buildActionIntent(TtsService.ACTION_STOP, REQ_STOP),
        )
        // Action 4: +10-minute sleep timer (label shows current remainder when active)
        builder.addAction(
            R.drawable.ic_tts_time_add_24dp,
            if (sleepMinutes > 0) "+10 (${sleepMinutes})" else "定时 +10",
            buildActionIntent(TtsService.ACTION_ADD_TIMER, REQ_TIMER),
        )

        // MediaStyle: collapsed view shows prev / play-pause / next
        builder.setStyle(
            androidx.media.app.NotificationCompat.MediaStyle()
                .setShowActionsInCompactView(0, 1, 2)
                .setMediaSession(compatToken),
        )

        return builder.build()
    }

    override fun handleCustomCommand(
        session: MediaSession,
        action: String,
        extras: Bundle,
    ): Boolean = false

    /**
     * PendingIntent that brings the user back to the launcher activity when the user
     * taps the notification body (matches Legado's `activityPendingIntent<ReadBookActivity>`).
     *
     * We resolve the launcher class by querying [PackageManager] so this provider doesn't
     * need a compile-time dependency on the UI module.
     */
    private fun buildContentIntent(): PendingIntent? {
        val pm = service.packageManager
        val launchIntent = pm.getLaunchIntentForPackage(service.packageName) ?: return null
        launchIntent.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        return PendingIntent.getActivity(service, REQ_CONTENT, launchIntent, flags)
    }

    private fun buildActionIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(service, TtsService::class.java).apply {
            this.action = action
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        return PendingIntent.getService(service, requestCode, intent, flags)
    }

    companion object {
        // Distinct request codes per action so PendingIntents don't collide.
        private const val REQ_CONTENT = 0
        private const val REQ_PREV = 1
        private const val REQ_PAUSE = 2
        private const val REQ_RESUME = 3
        private const val REQ_NEXT = 4
        private const val REQ_STOP = 5
        private const val REQ_TIMER = 6
    }
}
