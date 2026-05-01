package com.morealm.app.service

import com.morealm.app.domain.entity.TtsVoice
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/** Shared TTS playback state — single source of truth, owned by TtsEngineHost in TtsService. */
data class TtsPlaybackState(
    val bookTitle: String = "",
    val chapterTitle: String = "",
    val coverUrl: String? = null,
    val isPlaying: Boolean = false,
    val paragraphIndex: Int = 0,
    val totalParagraphs: Int = 0,
    /** Character offset in chapter for the current paragraph; -1 = inactive. */
    val chapterPosition: Int = -1,
    /** Linear progress 0..1 across paragraphs in current chapter; -1 = inactive. */
    val scrollProgress: Float = -1f,
    val speed: Float = 1.0f,
    /** "system" | "edge" | "http_<id>" */
    val engine: String = "system",
    /** Selected voice id (engine-scoped). */
    val voiceName: String = "",
    /** Available voices for the current engine. */
    val voices: List<TtsVoice> = emptyList(),
    /** Remaining sleep timer minutes (0 = disabled). */
    val sleepMinutes: Int = 0,
)

/**
 * Bidirectional event bus for TTS communication.
 * - events: Service → ViewModel (notification actions, audio focus, chapter finished)
 * - commands: ViewModel → Service (load chapter, play/pause, navigate paragraphs, configure)
 * - playbackState: shared observable state, written by TtsEngineHost only.
 */
object TtsEventBus {
    /** Service → ViewModel */
    sealed class Event {
        data object PrevChapter : Event()
        data object NextChapter : Event()
        /** User toggled play/pause from notification or media button. */
        data object PlayPause : Event()
        data class AudioFocusLoss(val resumeOnGain: Boolean) : Event()
        data object AudioFocusGain : Event()
        /** Notification "+10 minutes" timer button pressed. */
        data object AddTimer : Event()
        /** Service finished reading the last paragraph; ViewModel should advance to next chapter. */
        data object ChapterFinished : Event()

        /**
         * TTS engine failure surfaced to the UI as a toast/snackbar.
         *
         * @param message Chinese, user-facing reason (e.g. "系统未识别到可用的 TTS 引擎...").
         * @param canOpenSettings if true, UI may render an action button that launches
         *                        `Intent("com.android.settings.TTS_SETTINGS")` (with a
         *                        fallback to `Settings.ACTION_VOICE_INPUT_SETTINGS`).
         *                        Set false for transient errors where opening settings
         *                        wouldn't help (e.g. one-off speak() failure on a single paragraph).
         */
        data class Error(
            val message: String,
            val canOpenSettings: Boolean = false,
        ) : Event()
    }

    /** ViewModel → Service */
    sealed class Command {
        /**
         * Update notification metadata (book/chapter title, cover) without affecting playback.
         * For ad-hoc title updates only; LoadAndPlay supersedes this when starting a new chapter.
         */
        data class UpdateMeta(
            val book: String,
            val chapter: String,
            val coverUrl: String? = null,
        ) : Command()

        /**
         * Load a chapter's content into the host and start playback.
         * Sent on initial play, after chapter switch, or when resuming from a specific position.
         *
         * @param paragraphPositions optional pre-computed character offsets for each paragraph;
         *                           when null, host computes them sequentially from paragraph lengths.
         * @param startChapterPosition character offset to begin reading from (0 = chapter start).
         */
        data class LoadAndPlay(
            val bookTitle: String,
            val chapterTitle: String,
            val coverUrl: String?,
            val content: String,
            val paragraphPositions: List<Int>?,
            val startChapterPosition: Int,
        ) : Command()

        /** Resume playback from current paragraph (no content reload). */
        data object Play : Command()
        /** Pause playback; does not abandon audio focus. */
        data object Pause : Command()
        /** Stop and tear down the service. */
        data object StopService : Command()

        data object PrevParagraph : Command()
        data object NextParagraph : Command()

        /**
         * Listen-tab 的「上/下一章」按钮直发 Command。host 收到后转发为对应 Event，
         * 真正的章节加载仍由订阅 Event 的 ReaderViewModel 完成（host 不持有书籍/章节列表）。
         * 这层包装让 UI 调用方保持 sendCommand 的一致抽象。
         */
        data object PrevChapter : Command()
        data object NextChapter : Command()

        data class SetSpeed(val speed: Float) : Command()
        /** Engine id: "system" | "edge" | "http_<id>" */
        data class SetEngine(val engine: String) : Command()
        data class SetVoice(val voiceName: String) : Command()
        data class SetSkipPattern(val pattern: String) : Command()
        data class SetSleepMinutes(val minutes: Int) : Command()

        /** One-shot speech (selected text); does not affect main playback loop. */
        data class SpeakOneShot(val text: String) : Command()
    }

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 8)
    val events = _events.asSharedFlow()

    private val _commands = MutableSharedFlow<Command>(extraBufferCapacity = 8)
    val commands = _commands.asSharedFlow()

    private val _playbackState = MutableStateFlow(TtsPlaybackState())
    val playbackState: StateFlow<TtsPlaybackState> = _playbackState.asStateFlow()

    fun sendEvent(event: Event) { _events.tryEmit(event) }
    fun sendCommand(command: Command) { _commands.tryEmit(command) }

    fun updatePlayback(transform: TtsPlaybackState.() -> TtsPlaybackState) {
        _playbackState.value = _playbackState.value.transform()
    }
}
