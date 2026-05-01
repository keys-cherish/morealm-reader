package com.morealm.app.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.morealm.app.core.log.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.net.URL

/**
 * Minimal Player implementation for TTS MediaSession.
 * Doesn't actually play audio — the TTS engine handles that.
 * This just provides the MediaSession state for notification controls.
 *
 * Notification shows: book title, chapter name, cover art,
 * and prev/play-pause/next/stop buttons (like Legado).
 */
class TtsPlayer(private val context: Context) : SimpleBasePlayer(Looper.getMainLooper()) {

    private var playing = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Exposed for TtsNotificationProvider to read
    var bookTitle = ""
        private set
    var chapterTitle = ""
        private set
    var coverBitmap: Bitmap? = null
        private set
    /** Remaining sleep timer minutes (0 = disabled). Triggers notification refresh on change. */
    var sleepMinutes: Int = 0
        private set
    private var coverArtBytes: ByteArray? = null

    fun updateMetadata(book: String, chapter: String, coverUrl: String? = null) {
        bookTitle = book
        chapterTitle = chapter
        if (coverUrl != null) loadCover(coverUrl)
        invalidateState()
    }

    fun setPlaying(isPlaying: Boolean) {
        playing = isPlaying
        invalidateState()
    }

    fun setSleepMinutes(minutes: Int) {
        if (sleepMinutes == minutes) return
        sleepMinutes = minutes
        invalidateState()
    }

    private fun loadCover(url: String) {
        if (url.isBlank()) return

        // 三种 cover URL 形态都要支持：
        //   1. content://...                                 — Android SAF / MediaStore
        //   2. file:///data/...                              — 显式 file URI
        //   3. /data/user/0/.../epub_covers/.../cover.jpg    — 裸绝对文件路径（EPUB 解压缓存）
        // 之前只判断 1 和 2，裸路径会 fall through 到 URL(url).openStream()
        // 抛 MalformedURLException("no protocol: /data/...") 然后被吞掉，
        // 导致通知栏 cover 静默不显示但日志里一片 Cover load failed 噪音。
        val isLocal = url.startsWith("content://") ||
            url.startsWith("file://") ||
            url.startsWith("/")

        if (isLocal) {
            try {
                val uri = if (url.startsWith("/")) {
                    Uri.fromFile(java.io.File(url))
                } else {
                    Uri.parse(url)
                }
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeStream(stream, null, opts)
                }
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                    coverBitmap = BitmapFactory.decodeStream(stream, null, opts)
                    coverArtBytes = coverBitmap?.let { bmp ->
                        java.io.ByteArrayOutputStream().also {
                            bmp.compress(Bitmap.CompressFormat.PNG, 80, it)
                        }.toByteArray()
                    }
                }
                android.os.Handler(Looper.getMainLooper()).post { invalidateState() }
            } catch (e: Exception) {
                AppLog.debug("TtsPlayer", "Local cover load failed: ${e.message}")
            }
            // 关键：local 分支无论成功失败都 return，不再 fall through 到 URL
            // 网络分支 — 裸路径喂给 URL() 必抛 MalformedURLException。
            return
        }

        scope.launch {
            try {
                val bytes = URL(url).openStream().use { it.readBytes() }
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                val scale = maxOf(1, maxOf(opts.outWidth, opts.outHeight) / 256)
                val decodeOpts = BitmapFactory.Options().apply { inSampleSize = scale }
                coverBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts)
                coverArtBytes = coverBitmap?.let { bmp ->
                    java.io.ByteArrayOutputStream().also {
                        bmp.compress(Bitmap.CompressFormat.PNG, 80, it)
                    }.toByteArray()
                }
                android.os.Handler(Looper.getMainLooper()).post { invalidateState() }
            } catch (e: Exception) {
                AppLog.debug("TtsPlayer", "Cover load failed: ${e.message}")
            }
        }
    }

    override fun getState(): State {
        val metaBuilder = MediaMetadata.Builder()
            .setTitle("墨境 · 朗读: $bookTitle")
            .setSubtitle(chapterTitle.ifEmpty { null })
            .setArtist(bookTitle)
        coverArtBytes?.let {
            metaBuilder.setArtworkData(it, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
        }
        val metadata = metaBuilder.build()

        val mediaItem = MediaItem.Builder()
            .setMediaMetadata(metadata)
            .build()

        return State.Builder()
            .setAvailableCommands(
                Player.Commands.Builder()
                    .add(Player.COMMAND_PLAY_PAUSE)
                    .add(Player.COMMAND_SEEK_TO_NEXT)
                    .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                    .add(Player.COMMAND_STOP)
                    .build()
            )
            .setPlayWhenReady(playing, PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(Player.STATE_READY)
            .setPlaylist(listOf(MediaItemData.Builder(mediaItem.hashCode().toLong())
                .setMediaItem(mediaItem)
                .setMediaMetadata(metadata)
                .build()))
            .setCurrentMediaItemIndex(0)
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        playing = playWhenReady
        // Forward to host via the new Play/Pause commands so the speak loop reacts.
        TtsEventBus.sendCommand(
            if (playWhenReady) TtsEventBus.Command.Play else TtsEventBus.Command.Pause
        )
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: Int,
    ): ListenableFuture<*> {
        // Default seek = paragraph navigation; chapter is reachable via custom commands.
        when (seekCommand) {
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> {
                TtsEventBus.sendCommand(TtsEventBus.Command.NextParagraph)
            }
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> {
                TtsEventBus.sendCommand(TtsEventBus.Command.PrevParagraph)
            }
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        TtsEventBus.sendCommand(TtsEventBus.Command.StopService)
        return Futures.immediateVoidFuture()
    }
}
