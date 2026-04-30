package com.morealm.app.presentation.reader

import android.content.Context
import android.content.Intent
import android.os.Build
import com.morealm.app.core.log.AppLog
import com.morealm.app.domain.entity.TtsVoice
import com.morealm.app.domain.preference.AppPreferences
import com.morealm.app.service.TtsEventBus
import com.morealm.app.service.TtsPlaybackState
import com.morealm.app.service.TtsService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * UI-side adapter for the TTS service.
 *
 * Responsibilities:
 *  - Forward UI actions to [TtsService] via [TtsEventBus.sendCommand]
 *  - Expose [TtsEventBus.playbackState]-derived [StateFlow]s for Compose to observe
 *  - Bridge [TtsEventBus.Event]s back to ViewModel callbacks (chapter navigation)
 *
 * **Crucially does NOT own**: engines, paragraph data, the speak loop, the sleep timer.
 * All of that lives in `TtsEngineHost` inside [TtsService] so playback survives
 * ViewModel/Activity destruction.
 */
class ReaderTtsController(
    private val context: Context,
    private val prefs: AppPreferences,
    private val scope: CoroutineScope,
) {
    // ── Derived state (single source = TtsEventBus.playbackState) ────────────
    val ttsPlaying: StateFlow<Boolean> = TtsEventBus.playbackState
        .map { it.isPlaying }
        .stateIn(scope, SharingStarted.Eagerly, false)

    val ttsSpeed: StateFlow<Float> = TtsEventBus.playbackState
        .map { it.speed }
        .stateIn(scope, SharingStarted.Eagerly, 1.0f)

    val ttsEngine: StateFlow<String> = TtsEventBus.playbackState
        .map { it.engine }
        .stateIn(scope, SharingStarted.Eagerly, "system")

    val ttsParagraphIndex: StateFlow<Int> = TtsEventBus.playbackState
        .map { it.paragraphIndex }
        .stateIn(scope, SharingStarted.Eagerly, 0)

    val ttsTotalParagraphs: StateFlow<Int> = TtsEventBus.playbackState
        .map { it.totalParagraphs }
        .stateIn(scope, SharingStarted.Eagerly, 0)

    val ttsSleepMinutes: StateFlow<Int> = TtsEventBus.playbackState
        .map { it.sleepMinutes }
        .stateIn(scope, SharingStarted.Eagerly, 0)

    val ttsVoices: StateFlow<List<TtsVoice>> = TtsEventBus.playbackState
        .map { it.voices }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val ttsVoiceName: StateFlow<String> = TtsEventBus.playbackState
        .map { it.voiceName }
        .stateIn(scope, SharingStarted.Eagerly, "")

    val ttsScrollProgress: StateFlow<Float> = TtsEventBus.playbackState
        .map { it.scrollProgress }
        .stateIn(scope, SharingStarted.Eagerly, -1f)

    val ttsChapterPosition: StateFlow<Int> = TtsEventBus.playbackState
        .map { it.chapterPosition }
        .stateIn(scope, SharingStarted.Eagerly, -1)

    // ── ViewModel callbacks (set via collectChapterEvents) ───────────────────
    private var onPrevChapter: (() -> Unit)? = null
    private var onNextChapter: (() -> Unit)? = null
    private var onChapterFinished: (() -> Unit)? = null

    private var ttsServiceStarted = false

    /**
     * Called once from `ReaderViewModel.init`.
     * Starts the service if not running and wires up event listeners.
     *
     * @param getBookTitle / getChapterTitle reserved for future ad-hoc metadata refresh.
     */
    fun initialize(
        getBookTitle: () -> String,
        getChapterTitle: () -> String,
    ) {
        scope.launch {
            TtsEventBus.events.collect { event ->
                when (event) {
                    is TtsEventBus.Event.PlayPause -> {
                        // Notification toggled play/pause — flip current state.
                        if (TtsEventBus.playbackState.value.isPlaying) {
                            TtsEventBus.sendCommand(TtsEventBus.Command.Pause)
                        } else {
                            TtsEventBus.sendCommand(TtsEventBus.Command.Play)
                        }
                    }
                    is TtsEventBus.Event.PrevChapter -> onPrevChapter?.invoke()
                    is TtsEventBus.Event.NextChapter -> onNextChapter?.invoke()
                    is TtsEventBus.Event.ChapterFinished -> onChapterFinished?.invoke()
                    is TtsEventBus.Event.AudioFocusLoss,
                    is TtsEventBus.Event.AudioFocusGain -> {
                        // Service-side host already handled this; just let UI observe state.
                    }
                    is TtsEventBus.Event.AddTimer -> addTtsSleepTimer()
                }
            }
        }
    }

    /** Wire the chapter navigation callbacks. Called from `ReaderViewModel.init`. */
    fun collectChapterEvents(
        onPrev: () -> Unit,
        onNext: () -> Unit,
        onChapterEnd: () -> Unit = onNext,
    ) {
        onPrevChapter = onPrev
        onNextChapter = onNext
        onChapterFinished = onChapterEnd
    }

    /**
     * Kept for ABI compatibility with ReaderViewModel; resetting paragraph index now
     * lives inside the host and happens automatically on LoadAndPlay.
     */
    fun resetParagraphIndex() { /* no-op — host resets on LoadAndPlay */ }

    // ── Commands forwarded to service ────────────────────────────────────────

    fun ttsPlayPause(
        displayedContent: String? = null,
        bookTitle: String? = null,
        chapterTitle: String? = null,
        coverUrl: String? = null,
        startChapterPosition: Int? = null,
        paragraphPositions: List<Int>? = null,
        onChapterFinished: (() -> Unit)? = null,
    ) {
        if (ttsPlaying.value) {
            ttsPause()
            return
        }
        ttsPlay(
            displayedContent = displayedContent,
            bookTitle = bookTitle,
            chapterTitle = chapterTitle,
            coverUrl = coverUrl,
            startChapterPosition = startChapterPosition,
            paragraphPositions = paragraphPositions,
            onChapterFinished = onChapterFinished,
        )
    }

    /**
     * Begin / resume TTS playback.
     *
     * - When [displayedContent] is non-null: load the chapter into the host and play from
     *   [startChapterPosition] (defaults to 0).
     * - When [displayedContent] is null: simply send Play (resume from current paragraph).
     */
    fun ttsPlay(
        displayedContent: String?,
        bookTitle: String?,
        chapterTitle: String?,
        coverUrl: String? = null,
        startChapterPosition: Int? = null,
        paragraphPositions: List<Int>? = null,
        onChapterFinished: (() -> Unit)? = null,
    ) {
        ensureTtsService(bookTitle ?: "", chapterTitle ?: "", coverUrl)
        if (onChapterFinished != null) {
            this.onChapterFinished = onChapterFinished
        }
        if (displayedContent != null) {
            TtsEventBus.sendCommand(
                TtsEventBus.Command.LoadAndPlay(
                    bookTitle = bookTitle ?: "",
                    chapterTitle = chapterTitle ?: "",
                    coverUrl = coverUrl,
                    content = displayedContent,
                    paragraphPositions = paragraphPositions,
                    startChapterPosition = startChapterPosition?.coerceAtLeast(0) ?: 0,
                )
            )
        } else {
            TtsEventBus.sendCommand(TtsEventBus.Command.Play)
        }
    }

    fun ttsPause() {
        TtsEventBus.sendCommand(TtsEventBus.Command.Pause)
    }

    fun ttsStop() {
        TtsEventBus.sendCommand(TtsEventBus.Command.StopService)
        ttsServiceStarted = false
    }

    fun ttsPrevParagraph() {
        TtsEventBus.sendCommand(TtsEventBus.Command.PrevParagraph)
    }

    fun ttsNextParagraph() {
        TtsEventBus.sendCommand(TtsEventBus.Command.NextParagraph)
    }

    fun setTtsSpeed(speed: Float) {
        TtsEventBus.sendCommand(TtsEventBus.Command.SetSpeed(speed))
    }

    fun setTtsEngine(engine: String) {
        TtsEventBus.sendCommand(TtsEventBus.Command.SetEngine(engine))
    }

    fun setTtsVoice(voiceName: String) {
        TtsEventBus.sendCommand(TtsEventBus.Command.SetVoice(voiceName))
    }

    fun setTtsSleepTimer(minutes: Int) {
        TtsEventBus.sendCommand(TtsEventBus.Command.SetSleepMinutes(minutes))
    }

    /**
     * Increment sleep timer by 10 minutes (Legado-style cycle).
     * 0 → 10, 10..170 → +10 (cap 180), 180 → 0.
     */
    fun addTtsSleepTimer() {
        val current = ttsSleepMinutes.value
        val next = when {
            current >= 180 -> 0
            current <= 0 -> 10
            else -> (current + 10).coerceAtMost(180)
        }
        AppLog.info("TTS", "Sleep timer: $current → $next minutes")
        setTtsSleepTimer(next)
    }

    /** Speak text one-shot (selected text); does not affect main playback loop. */
    fun speakSelectedText(text: String) {
        if (text.isBlank()) return
        ensureTtsService("", "")
        TtsEventBus.sendCommand(TtsEventBus.Command.SpeakOneShot(text))
    }

    /** Begin reading from a specific chapter position (e.g. user double-tapped a paragraph). */
    fun readAloudFrom(
        displayedContent: String,
        bookTitle: String,
        chapterTitle: String,
        coverUrl: String? = null,
        startChapterPosition: Int,
        paragraphPositions: List<Int>? = null,
        onChapterFinished: (() -> Unit)?,
    ) {
        ttsPlay(
            displayedContent = displayedContent,
            bookTitle = bookTitle,
            chapterTitle = chapterTitle,
            coverUrl = coverUrl,
            startChapterPosition = startChapterPosition,
            paragraphPositions = paragraphPositions,
            onChapterFinished = onChapterFinished,
        )
    }

    /** Called from `ReaderViewModel.onCleared()`; service keeps running until user stops it. */
    fun shutdown() {
        // Intentionally NOT stopping the service: it owns the speak loop and should outlive
        // the ViewModel so the user can leave the reader screen without interrupting playback.
        // The user must explicitly stop via the notification, panel, or sleep timer.
        onPrevChapter = null
        onNextChapter = null
        onChapterFinished = null
    }

    // ── Internal ─────────────────────────────────────────────────────────────

    private fun ensureTtsService(bookTitle: String, chapterTitle: String, coverUrl: String? = null) {
        if (ttsServiceStarted) {
            // Just refresh metadata in case title/cover changed
            TtsEventBus.sendCommand(TtsEventBus.Command.UpdateMeta(bookTitle, chapterTitle, coverUrl))
            return
        }
        try {
            val intent = Intent(context, TtsService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            ttsServiceStarted = true
            TtsEventBus.sendCommand(TtsEventBus.Command.UpdateMeta(bookTitle, chapterTitle, coverUrl))
            AppLog.info("TTS", "TTS service started")
        } catch (e: Exception) {
            AppLog.error("TTS", "Failed to start TTS service", e)
        }
    }
}
