package com.morealm.app.service

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/** Shared TTS playback state for cross-screen observation (ListenScreen etc.) */
data class TtsPlaybackState(
    val bookTitle: String = "",
    val chapterTitle: String = "",
    val isPlaying: Boolean = false,
    val paragraphIndex: Int = 0,
    val totalParagraphs: Int = 0,
    val speed: Float = 1.0f,
    val engine: String = "system",
)

/**
 * Bidirectional event bus for TTS communication.
 * - events: Service → ViewModel (notification actions, audio focus)
 * - commands: ViewModel → Service (metadata, play state, stop)
 * - playbackState: shared observable state for any screen
 */
object TtsEventBus {
    /** Service → ViewModel */
    sealed class Event {
        data object PrevChapter : Event()
        data object NextChapter : Event()
        data object PlayPause : Event()
        data class AudioFocusLoss(val resumeOnGain: Boolean) : Event()
        data object AudioFocusGain : Event()
    }

    /** ViewModel → Service */
    sealed class Command {
        data class UpdateMeta(val book: String, val chapter: String, val coverUrl: String? = null) : Command()
        data class SetPlaying(val playing: Boolean) : Command()
        data object StopService : Command()
    }

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 4)
    val events = _events.asSharedFlow()

    private val _commands = MutableSharedFlow<Command>(extraBufferCapacity = 4)
    val commands = _commands.asSharedFlow()

    private val _playbackState = MutableStateFlow(TtsPlaybackState())
    val playbackState: StateFlow<TtsPlaybackState> = _playbackState.asStateFlow()

    fun sendEvent(event: Event) { _events.tryEmit(event) }
    fun sendCommand(command: Command) { _commands.tryEmit(command) }

    fun updatePlayback(transform: TtsPlaybackState.() -> TtsPlaybackState) {
        _playbackState.value = _playbackState.value.transform()
    }
}
