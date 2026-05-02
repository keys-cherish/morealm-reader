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

    /**
     * 当前正在朗读段落的章内字符区间 [start, end)。null = 未朗读 / 引擎未提供。
     *
     * 渲染层（`PageContentDrawer`）订阅这个 Flow 给当前段加高亮；
     * 阅读器外层订阅它判断 "回到朗读位置" FAB 是否显示 / 是否自动跟随翻页。
     */
    val ttsParagraphRange: StateFlow<IntRange?> = TtsEventBus.playbackState
        .map { it.paragraphRange }
        .stateIn(scope, SharingStarted.Eagerly, null)

    // ── ViewModel callbacks (set via collectChapterEvents) ───────────────────
    // Removed: events are now consumed directly by ReaderViewModel. See `initialize`.

    private var ttsServiceStarted = false

    /**
     * Called once from `ReaderViewModel.init`.
     * Starts the service if not running. The service-side host owns the speak loop,
     * engines, and all state; this controller is just a UI-side adapter.
     *
     * **Note on events**: All [TtsEventBus.Event]s are now consumed directly by
     * `ReaderViewModel`, since most of them require chapter-loading capabilities the
     * controller doesn't have. The controller no longer subscribes to events.
     *
     * @param getBookTitle / getChapterTitle reserved for future ad-hoc metadata refresh.
     */
    fun initialize(
        @Suppress("UNUSED_PARAMETER") getBookTitle: () -> String,
        @Suppress("UNUSED_PARAMETER") getChapterTitle: () -> String,
    ) {
        // No-op for now. Kept as the canonical entry point so future per-controller
        // initialization (e.g. deferred prefs loading) has an obvious place to live.
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
        bookId: String? = null,
        chapterIndex: Int? = null,
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
            bookId = bookId,
            chapterIndex = chapterIndex,
            onChapterFinished = onChapterFinished,
        )
    }

    /**
     * Begin / resume TTS playback.
     *
     * - When [displayedContent] is non-null: load the chapter into the host and play from
     *   [startChapterPosition] (defaults to 0).
     * - When [displayedContent] is null: simply send Play (resume from current paragraph).
     *
     * @param bookId 当前书的 id；传入后 host 会缓存它，章末超时未收到 ViewModel 推章
     *               时直接从 BookRepository 加载下一章续播。**离开 Reader 后续章不断声
     *               的关键参数**——任何 reader 路径都应该尽量带上。
     * @param chapterIndex 当前章节在书目录中的下标；同样用于 host 的自动续章兜底。
     * @param onChapterFinished kept for API compatibility but ignored: chapter-end handling
     *        now lives in `ReaderViewModel`'s direct TtsEventBus listener.
     */
    fun ttsPlay(
        displayedContent: String?,
        bookTitle: String?,
        chapterTitle: String?,
        coverUrl: String? = null,
        startChapterPosition: Int? = null,
        paragraphPositions: List<Int>? = null,
        bookId: String? = null,
        chapterIndex: Int? = null,
        @Suppress("UNUSED_PARAMETER") onChapterFinished: (() -> Unit)? = null,
    ) {
        // TTS-DIAG #2 — controller received params. If we never reach the
        // sendCommand log below, ensureTtsService() failed (foreground-start
        // restriction, missing service, etc.) — see the catch in that fn.
        AppLog.info(
            "TTS",
            "Controller.ttsPlay: contentLen=${displayedContent?.length ?: -1}, " +
                "book='${bookTitle ?: ""}', chapter='${chapterTitle ?: ""}', " +
                "startPos=$startChapterPosition, positions=${paragraphPositions?.size ?: -1}, " +
                "bookId=${bookId ?: "<none>"}, chapterIndex=${chapterIndex ?: -1}",
        )
        ensureTtsService(bookTitle ?: "", chapterTitle ?: "", coverUrl)
        if (displayedContent != null) {
            AppLog.info("TTS", "Controller → sendCommand(LoadAndPlay)")
            TtsEventBus.sendCommand(
                TtsEventBus.Command.LoadAndPlay(
                    bookTitle = bookTitle ?: "",
                    chapterTitle = chapterTitle ?: "",
                    coverUrl = coverUrl,
                    content = displayedContent,
                    paragraphPositions = paragraphPositions,
                    startChapterPosition = startChapterPosition?.coerceAtLeast(0) ?: 0,
                    bookId = bookId,
                    chapterIndex = chapterIndex,
                )
            )
        } else {
            AppLog.info("TTS", "Controller → sendCommand(Play) [resume, no content]")
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
        bookId: String? = null,
        chapterIndex: Int? = null,
        onChapterFinished: (() -> Unit)?,
    ) {
        ttsPlay(
            displayedContent = displayedContent,
            bookTitle = bookTitle,
            chapterTitle = chapterTitle,
            coverUrl = coverUrl,
            startChapterPosition = startChapterPosition,
            paragraphPositions = paragraphPositions,
            bookId = bookId,
            chapterIndex = chapterIndex,
            onChapterFinished = onChapterFinished,
        )
    }

    /** Called from `ReaderViewModel.onCleared()`; service keeps running until user stops it. */
    fun shutdown() {
        // Intentionally NOT stopping the service: it owns the speak loop and should outlive
        // the ViewModel so the user can leave the reader screen without interrupting playback.
        // The user must explicitly stop via the notification, panel, or sleep timer.
    }

    // ── Internal ─────────────────────────────────────────────────────────────

    private fun ensureTtsService(bookTitle: String, chapterTitle: String, coverUrl: String? = null) {
        if (ttsServiceStarted) {
            // Just refresh metadata in case title/cover changed
            AppLog.debug("TTS", "ensureTtsService: already started, sending UpdateMeta")
            TtsEventBus.sendCommand(TtsEventBus.Command.UpdateMeta(bookTitle, chapterTitle, coverUrl))
            return
        }
        try {
            val intent = Intent(context, TtsService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                AppLog.info("TTS", "ensureTtsService: startForegroundService TtsService (SDK>=26)")
                context.startForegroundService(intent)
            } else {
                AppLog.info("TTS", "ensureTtsService: startService TtsService (SDK<26)")
                context.startService(intent)
            }
            ttsServiceStarted = true
            TtsEventBus.sendCommand(TtsEventBus.Command.UpdateMeta(bookTitle, chapterTitle, coverUrl))
            AppLog.info("TTS", "TTS service start kicked off; waiting for onCreate")
        } catch (e: Exception) {
            // Common failures here: ForegroundServiceStartNotAllowedException
            // (Android 12+, app not in a foreground-allowed state),
            // SecurityException (missing FOREGROUND_SERVICE permission),
            // or a generic IllegalStateException from the framework.
            // Surface to user via Toast — silent failure here was the #1
            // reported "TTS does nothing" symptom before this branch.
            AppLog.error("TTS", "ensureTtsService FAILED: ${e.javaClass.simpleName}: ${e.message}", e)
            TtsEventBus.sendEvent(
                TtsEventBus.Event.Error(
                    "无法启动朗读服务：${e.message ?: e.javaClass.simpleName}",
                    canOpenSettings = false,
                )
            )
        }
    }
}
