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

    private fun loadCover(url: String) {
        if (url.isBlank()) return
        if (url.startsWith("content://") || url.startsWith("file://")) {
            try {
                val uri = Uri.parse(url)
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeStream(stream, null, opts)
                }
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                    coverBitmap = BitmapFactory.decodeStream(stream, null, opts)
                }
                android.os.Handler(Looper.getMainLooper()).post { invalidateState() }
                return
            } catch (_: Exception) {}
        }
        scope.launch {
            try {
                val bytes = URL(url).openStream().use { it.readBytes() }
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                val scale = maxOf(1, maxOf(opts.outWidth, opts.outHeight) / 256)
                val decodeOpts = BitmapFactory.Options().apply { inSampleSize = scale }
                coverBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts)
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
        coverBitmap?.let {
            val stream = java.io.ByteArrayOutputStream()
            it.compress(Bitmap.CompressFormat.PNG, 80, stream)
            metaBuilder.setArtworkData(stream.toByteArray(), MediaMetadata.PICTURE_TYPE_FRONT_COVER)
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
            .setPlaybackState(if (playing) Player.STATE_READY else Player.STATE_IDLE)
            .setPlaylist(listOf(MediaItemData.Builder(mediaItem.hashCode().toLong())
                .setMediaItem(mediaItem)
                .setMediaMetadata(metadata)
                .build()))
            .setCurrentMediaItemIndex(0)
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        playing = playWhenReady
        TtsEventBus.sendEvent(TtsEventBus.Event.PlayPause)
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: Int,
    ): ListenableFuture<*> {
        when (seekCommand) {
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> {
                TtsEventBus.sendEvent(TtsEventBus.Event.NextChapter)
            }
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> {
                TtsEventBus.sendEvent(TtsEventBus.Event.PrevChapter)
            }
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        TtsEventBus.sendCommand(TtsEventBus.Command.StopService)
        return Futures.immediateVoidFuture()
    }
}
