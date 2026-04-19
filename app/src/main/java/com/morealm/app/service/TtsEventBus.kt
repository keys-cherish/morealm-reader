package com.morealm.app.service

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Bidirectional event bus for TTS communication.
 * - events: Service → ViewModel (notification actions, audio focus)
 * - commands: ViewModel → Service (metadata, play state, stop)
 */
object TtsEventBus {
    /** Service → ViewModel */
    sealed class Event {
        data object PrevChapter : Event()
        data object NextChapter : Event()
        data object PlayPause : Event()
        data object AudioFocusLoss : Event()
        data object AudioFocusGain : Event()
    }

    /** ViewModel → Service */
    sealed class Command {
        data class UpdateMeta(val book: String, val chapter: String) : Command()
        data class SetPlaying(val playing: Boolean) : Command()
        data object StopService : Command()
    }

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 4)
    val events = _events.asSharedFlow()

    private val _commands = MutableSharedFlow<Command>(extraBufferCapacity = 4)
    val commands = _commands.asSharedFlow()

    fun sendEvent(event: Event) { _events.tryEmit(event) }
    fun sendCommand(command: Command) { _commands.tryEmit(command) }
}
