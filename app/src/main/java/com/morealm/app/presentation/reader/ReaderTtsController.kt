package com.morealm.app.presentation.reader

import android.content.Context
import android.content.Intent
import android.os.Build
import com.morealm.app.core.log.AppLog
import com.morealm.app.domain.db.HttpTtsDao
import com.morealm.app.domain.entity.TtsVoice
import com.morealm.app.domain.preference.AppPreferences
import com.morealm.app.domain.tts.EdgeTtsEngine
import com.morealm.app.domain.tts.SystemTtsEngine
import com.morealm.app.service.TtsEventBus
import com.morealm.app.service.TtsPlaybackState
import com.morealm.app.service.TtsService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
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
 *  - **Maintain its own per-engine voice list** (mirroring [com.morealm.app.presentation.profile.ListenViewModel]):
 *    阅读器进入时若 [TtsService] 还没启动，host 那边的 `TtsEventBus.playbackState.voices`
 *    一直是 `emptyList`，导致 TTS 面板看不到系统 TTS 的语音；并且如果用户先在
 *    "听书" tab 切到 EdgeTTS（host 把 voices 写成了 edge 600 条），切回阅读器选
 *    "系统 TTS"时，因为 service 没启 SetEngine 命令丢失 → voices 仍残留 edge。
 *    解决方式：reader 端不再从 host 读 voices，自己持有 SystemTtsEngine + EdgeTtsEngine
 *    并按 `prefs.ttsEngine` 变化主动 refresh，跟听书 tab 行为完全一致。
 *
 * **Crucially does NOT own**: paragraph data, the speak loop, the sleep timer.
 * 这些仍住在 `TtsEngineHost` inside [TtsService] 里，让播放跨 ViewModel/Activity 销毁存活。
 * 本类持有的 SystemTts/EdgeTts 实例只用于"列出可选语音"，不参与朗读发声。
 */
class ReaderTtsController(
    private val context: Context,
    private val prefs: AppPreferences,
    private val scope: CoroutineScope,
    private val httpTtsDao: HttpTtsDao,
) {
    // 自家的 voice 加载用的引擎实例 —— 只用来列音色 + getInstalledEngines；不参与朗读。
    // host 里另有一份独立实例做实际播放，互不干扰。
    private val systemTtsEngine = SystemTtsEngine(context)
    private val edgeTtsEngine by lazy { EdgeTtsEngine(context) }

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

    /**
     * 阅读器面板里"语音"下拉用的列表。**不再走 [TtsEventBus.playbackState.voices]**——
     * 那条路径要求 [TtsService] 已经 start 才会被填充，但用户打开 TTS 面板时 service
     * 通常还没启动。改为 reader 自己 collectLatest [AppPreferences.ttsEngine] 重新
     * 加载（参考 [com.morealm.app.presentation.profile.ListenViewModel.refreshVoices]）。
     */
    private val _voices = MutableStateFlow<List<TtsVoice>>(emptyList())
    val ttsVoices: StateFlow<List<TtsVoice>> = _voices.asStateFlow()

    /**
     * 当前选中的 voice id。读自 prefs（按引擎分别保存：edge 走 ttsEdgeVoice，
     * 其他走 ttsSystemVoice，没有时回落 ttsVoice）。引擎切换时跟随更新。
     */
    val ttsVoiceName: StateFlow<String> = kotlinx.coroutines.flow.combine(
        prefs.ttsEngine,
        prefs.ttsSystemVoice,
        prefs.ttsEdgeVoice,
        prefs.ttsVoice,
    ) { engine, sysVoice, edgeVoice, legacy ->
        when {
            engine == "edge" -> edgeVoice.ifBlank { legacy }
            engine == "system" -> sysVoice.ifBlank { legacy }
            else -> ""
        }
    }.stateIn(scope, SharingStarted.Eagerly, "")

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

    // 旧版本本地 Boolean 旗标 `ttsServiceStarted` 已替换为查 [TtsService.isRunning]
    // 静态 AtomicBoolean —— 服务被系统强杀后本地 bool 不复位，会卡死「点播放没声音」。
    // 见 [TtsService.isRunning] 注释。

    init {
        // 异步 init system TTS（它内部 onInit 完成后会填 cachedVoices）。Edge 是纯 HTTP，
        // 不需要 init。HttpTts 走 dao 查源也是即时的。
        systemTtsEngine.initialize()
        // 监听 prefs.ttsEngine 变化即刻刷一次 voices。collectLatest 保证用户连点切引擎
        // 时只跑最新一次（前一次未完成的 fetchRemoteVoices 会被 cancel）。
        scope.launch {
            prefs.ttsEngine.collectLatest { engine ->
                refreshVoicesForEngine(engine)
            }
        }
    }

    /**
     * 按引擎重新拉取可选音色列表。完整复刻 [ListenViewModel.refreshVoices] 逻辑：
     * - "edge"：fetchRemoteVoices() 拿 600+ 条远程音色，失败回退硬编码 21 条 zh；
     * - "http_*"：从 dao 读对应配置，把它当作单元素音色返回（http 源没有多音色概念）；
     * - 其他（含 "system"）：等 system TTS init ready，没装中文语音包时报错并返回空列表。
     */
    private suspend fun refreshVoicesForEngine(engine: String) {
        _voices.value = when {
            engine == "edge" -> {
                runCatching { edgeTtsEngine.fetchRemoteVoices() }
                    .getOrNull()?.takeIf { it.isNotEmpty() }
                    ?: EdgeTtsEngine.VOICES
            }
            engine.startsWith("http_") -> {
                val id = engine.removePrefix("http_").toLongOrNull()
                val cfg = id?.let { runCatching { httpTtsDao.getById(it) }.getOrNull() }
                if (cfg != null) {
                    listOf(TtsVoice(id = cfg.name, name = cfg.name, language = "custom", engine = "http"))
                } else emptyList()
            }
            else -> {
                when (val r = systemTtsEngine.awaitReadyResult()) {
                    is SystemTtsEngine.InitResult.Failed -> {
                        AppLog.warn("TTS", "ReaderTtsController.refreshVoices: system init failed: ${r.reason}")
                        emptyList()
                    }
                    SystemTtsEngine.InitResult.Success -> systemTtsEngine.getChineseVoices()
                }
            }
        }
    }

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
        /**
         * 段级跨章触发的"切上一章并落到末段读"专用旗标。默认 false 走原行为
         * （从 [startChapterPosition] 或章首读）。仅由 ReaderViewModel 在收到
         * [TtsEventBus.Event.PrevChapterToLast] 后下一次推章节内容时设 true。
         */
        startAtLastParagraph: Boolean = false,
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
                "bookId=${bookId ?: "<none>"}, chapterIndex=${chapterIndex ?: -1}, " +
                "startAtLastParagraph=$startAtLastParagraph",
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
                    startAtLastParagraph = startAtLastParagraph,
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
        // 不再写本地旗标 —— 服务 onDestroy 会自己置 [TtsService.isRunning] = false。
        // 这里同步置 false 反而会引入竞态：StopService 命令异步处理，期间用户立刻
        // 再点播放会触发重启路径，而服务可能还没真正销毁，导致 startForegroundService
        // 在 onCreate 里看到 isRunning 已经是 true（旧实例还活着）。让服务自己定夺。
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
        //
        // 但本类持有的 voice 加载用引擎实例必须释放：systemTtsEngine 内部抓了一个
        // [android.speech.tts.TextToSpeech] 实例，未 shutdown 会泄漏 binder 连接。
        runCatching { systemTtsEngine.shutdown() }
        runCatching { edgeTtsEngine.stop() }
    }

    // ── Internal ─────────────────────────────────────────────────────────────

    private fun ensureTtsService(bookTitle: String, chapterTitle: String, coverUrl: String? = null) {
        // 真实状态查 [TtsService.isRunning]：服务进程被系统杀掉后字段会随进程一起重置，
        // 不会像旧版本本地 Boolean 旗标那样卡死在 true。
        if (TtsService.isRunning.get()) {
            // Just refresh metadata in case title/cover changed
            AppLog.debug("TTS", "ensureTtsService: already running, sending UpdateMeta")
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
            // 不预置 isRunning = true —— 让 service 的 onCreate 自己置位。这里乐观更新
            // 反而会让短暂窗口里 (start kicked off → onCreate 之前) 的二次调用走错
            // 分支。startForegroundService 后立刻发的 UpdateMeta 命令会进 SharedFlow
            // buffer（capacity=8），等 service onCreate 起来 collect 时取出。
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
