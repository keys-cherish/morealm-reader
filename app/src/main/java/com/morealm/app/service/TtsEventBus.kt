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
    /**
     * 当前正在朗读段落的 **章内字符区间** `[start, end)`。null = 未在朗读 / 未提供。
     *
     * 用于：
     *  - 阅读器段落高亮（`PageContentDrawer` 在画文字行时若行字符区间与此 range 相交，
     *    叠半透明强调色）
     *  - "回到朗读位置" FAB 判定（当前页是否包含 [paragraphRange.first]）
     *  - "朗读自动跟随翻页"（[chapterPosition] 不在当前页时根据用户偏好自动 gotoPage）
     *
     * 由 [TtsEngineHost] 在 paragraph 切换时根据 `paragraphPositions[i]` +
     * `paragraphLengths[i]` 计算后写入；切换章节 / 暂停 / 停止时置 null。
     */
    val paragraphRange: IntRange? = null,
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

        /**
         * 段级跳转触发的"切上一章"语义 —— 与 [PrevChapter]（用户主动按"上一章"按钮，
         * 切完从首段读）不同：用户在章首按了"上一段"，应该把朗读位置带到上一章的
         * **末段**，等同于"读完上一章末段后再继续往前"的自然延伸。
         *
         * ReaderViewModel 收到这个事件应该：
         *   1. 翻页到上一章
         *   2. 等章节内容到达时调 ttsPlay 时带 startAtLastParagraph=true，
         *      让 host 把 paragraphIndex 落在末段（再向前找第一个有内容段）。
         */
        data object PrevChapterToLast : Event()
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
         * @param bookId 当前章节所属的书籍 id；非空时 host 会缓存它，[Event.ChapterFinished]
         *               后超时未收到 ViewModel 推送的新 LoadAndPlay 时，host 用这个 id 自己
         *               从 BookRepository 加载下一章续播——这是"用户离开 Reader 后续章
         *               不断声"的核心数据。空时 host 退回旧行为（依赖 ViewModel 推章）。
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
            val bookId: String? = null,
            val chapterIndex: Int? = null,
            /**
             * 仅当本次 LoadAndPlay 是"段级跳转触发的跨章节延伸"（[Event.PrevChapterToLast]
             * 之后续接）时为 true：host 解析 paragraphs 后把 paragraphIndex 设到 lastIndex，
             * 再调 [TtsEngineHost] 的"向前找第一个有内容段"算法（避开末段是空白/标点的情况）。
             *
             * 默认 false 保持向后兼容：所有其他场景都从 paragraph 0 开始读。
             */
            val startAtLastParagraph: Boolean = false,
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

        /**
         * 重新绑定 SystemTtsEngine 到指定包名（如 "net.olekdia.multispeech"、
         * "com.google.android.tts"），不需要重启阅读器。pkg 为空 = 跟随系统默认。
         *
         * Host 收到后：cancelAndJoin 当前 speakJob → shutdown 旧 systemTtsEngine →
         * 用新包名 initialize → 若之前在朗读，自动恢复播放（系统引擎走 batch 路径）。
         *
         * 加这条命令的原因：systemTtsEngine 字段是 lazy + 一次性 init，之前用户切换
         * multitts 后必须重启阅读器才能生效，体验差。
         */
        data class RebindSystemEngine(val pkg: String) : Command()
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
