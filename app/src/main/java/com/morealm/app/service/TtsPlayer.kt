package com.morealm.app.service

import android.content.Context
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * Minimal Player implementation for TTS MediaSession.
 * Doesn't actually play audio — the TTS engine handles that.
 * This just provides the MediaSession state for notification controls.
 */
class TtsPlayer(context: Context) : SimpleBasePlayer(Looper.getMainLooper()) {

    private var playing = false
    private var bookTitle = ""
    private var chapterTitle = ""

    fun updateMetadata(book: String, chapter: String) {
        bookTitle = book
        chapterTitle = chapter
        invalidateState()
    }

    fun setPlaying(isPlaying: Boolean) {
        playing = isPlaying
        invalidateState()
    }

    override fun getState(): State {
        val metadata = MediaMetadata.Builder()
            .setTitle(chapterTitle.ifEmpty { "朗读中" })
            .setArtist(bookTitle)
            .build()

        val mediaItem = MediaItem.Builder()
            .setMediaMetadata(metadata)
            .build()

        return State.Builder()
            .setAvailableCommands(
                Player.Commands.Builder()
                    .add(Player.COMMAND_PLAY_PAUSE)
                    .add(Player.COMMAND_SEEK_TO_NEXT)
                    .add(Player.COMMAND_SEEK_TO_PREVIOUS)
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
        // Relay notification play/pause tap back to ViewModel
        TtsEventBus.sendEvent(TtsEventBus.Event.PlayPause)
        return Futures.immediateVoidFuture()
    }
}
