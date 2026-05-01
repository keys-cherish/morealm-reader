package com.morealm.app.service

import android.content.Context
import android.speech.tts.TextToSpeech
import com.morealm.app.core.log.AppLog
import com.morealm.app.core.text.AppPattern
import com.morealm.app.domain.entity.TtsVoice
import com.morealm.app.domain.preference.AppPreferences
import com.morealm.app.domain.tts.EdgeTtsEngine
import com.morealm.app.domain.tts.SystemTtsEngine
import com.morealm.app.domain.tts.TtsEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Lives inside [TtsService] and owns the entire TTS reading state:
 * engine instances, the speak loop ([speakJob]), paragraph data, and the sleep timer.
 *
 * This replaces the previous `ReaderTtsController` ownership model where the loop ran on
 * `viewModelScope` and was cancelled the moment the user left the reader. By moving the loop
 * here, playback survives ViewModel destruction — only stopping on explicit user action,
 * sleep timer expiration, or unrecoverable audio focus loss.
 *
 * State is published via [TtsEventBus.playbackState]; commands enter via [handleCommand].
 * Chapter boundaries are signalled out via [TtsEventBus.Event.ChapterFinished].
 */
class TtsEngineHost(
    private val context: Context,
    private val prefs: AppPreferences,
    private val scope: CoroutineScope,
) {
    // ── Engines ──────────────────────────────────────────────────────────────
    private val systemTtsEngine by lazy {
        SystemTtsEngine(context).also { it.initialize() }
    }
    private val edgeTtsEngine by lazy { EdgeTtsEngine() }

    // ── Mutable state (host is single source of truth) ───────────────────────
    private var paragraphs: List<String> = emptyList()
    private var paragraphPositions: List<Int> = emptyList()
    private var paragraphIndex: Int = 0

    /**
     * 当前段内字符偏移，用于句中续读（仿 Legado paragraphStartPos）。
     * - SystemTtsEngine 批量路径下：onRangeStart 实时回写，pause 不清零；
     *   resume 时第一段切片 substring(paragraphStartPos) 后再入队。
     * - 段切换（onDone 推进 paragraphIndex）时复位为 0。
     * - 用户手动切上/下段（prevParagraph/nextParagraph）也复位为 0。
     * - 这是 Legado 没做、MoRealm 新增的「真断点续读」能力。
     */
    private var paragraphStartPos: Int = 0

    private var bookTitle: String = ""
    private var chapterTitle: String = ""
    private var coverUrl: String? = null

    private var speed: Float = 1.0f
    private var engineId: String = "system"
    private var voiceName: String = ""
    private var skipRegex: Regex? = null

    private var sleepRemainingMinutes: Int = 0

    /** True between "last paragraph finished" and "next chapter loaded" — keeps service alive briefly. */
    private var waitingForNextChapter: Boolean = false

    // ── Jobs ─────────────────────────────────────────────────────────────────
    private var speakJob: Job? = null
    private var sleepTimerJob: Job? = null
    private var oneShotJob: Job? = null

    // Serialize all (cancel + start) operations so rapid commands don't race.
    private val controlMutex = Mutex()

    /** Initialize host: load saved prefs, push initial playback state. */
    fun start() {
        scope.launch {
            speed = prefs.ttsSpeed.first()
            skipRegex = prefs.ttsSkipPattern.first().let { p ->
                if (p.isNotBlank()) runCatching { Regex(p) }.getOrNull() else null
            }
            engineId = prefs.ttsEngine.first()
            voiceName = savedVoiceForEngine(engineId)
            applyVoiceToEngine()
            val voices = loadVoicesForEngine(engineId)
            TtsEventBus.updatePlayback {
                copy(
                    speed = speed,
                    engine = engineId,
                    voiceName = voiceName,
                    voices = voices,
                )
            }
        }
    }

    fun release() {
        speakJob?.cancel()
        sleepTimerJob?.cancel()
        oneShotJob?.cancel()
        runCatching { systemTtsEngine.stop(); systemTtsEngine.shutdown() }
        runCatching { edgeTtsEngine.stop() }
    }

    /** Entry point for all [TtsEventBus.Command]s; called by [TtsService.listenForCommands]. */
    fun handleCommand(cmd: TtsEventBus.Command) {
        when (cmd) {
            is TtsEventBus.Command.UpdateMeta -> {
                bookTitle = cmd.book
                chapterTitle = cmd.chapter
                cmd.coverUrl?.let { coverUrl = it }
                TtsEventBus.updatePlayback {
                    copy(
                        bookTitle = cmd.book.ifBlank { this.bookTitle },
                        chapterTitle = cmd.chapter.ifBlank { this.chapterTitle },
                        coverUrl = cmd.coverUrl ?: this.coverUrl,
                    )
                }
            }

            is TtsEventBus.Command.LoadAndPlay -> loadAndPlay(cmd)
            is TtsEventBus.Command.Play -> resume()
            is TtsEventBus.Command.Pause -> pause()
            is TtsEventBus.Command.PrevParagraph -> prevParagraph()
            is TtsEventBus.Command.NextParagraph -> nextParagraph()
            // host 不持有章节列表 → 只是把 Command 透传成对应 Event，
            // 让 ReaderViewModel 的 Event.PrevChapter/NextChapter 处理分支接管真正的章节切换。
            is TtsEventBus.Command.PrevChapter ->
                TtsEventBus.sendEvent(TtsEventBus.Event.PrevChapter)
            is TtsEventBus.Command.NextChapter ->
                TtsEventBus.sendEvent(TtsEventBus.Event.NextChapter)
            is TtsEventBus.Command.SetSpeed -> setSpeed(cmd.speed)
            is TtsEventBus.Command.SetEngine -> setEngine(cmd.engine)
            is TtsEventBus.Command.SetVoice -> setVoice(cmd.voiceName)
            is TtsEventBus.Command.SetSkipPattern -> setSkipPattern(cmd.pattern)
            is TtsEventBus.Command.SetSleepMinutes -> setSleepMinutes(cmd.minutes)
            is TtsEventBus.Command.SpeakOneShot -> speakOneShot(cmd.text)
            is TtsEventBus.Command.StopService -> {
                // Service.onDestroy will call release(); just stop playback now.
                stopPlayback()
            }
        }
    }

    // ── Audio focus hooks (called by TtsService) ─────────────────────────────

    fun onAudioFocusLoss(resumeOnGain: Boolean) {
        if (TtsEventBus.playbackState.value.isPlaying) {
            pendingResumeOnFocusGain = resumeOnGain
            pause()
        } else {
            pendingResumeOnFocusGain = false
        }
    }

    fun onAudioFocusGain() {
        if (pendingResumeOnFocusGain) {
            pendingResumeOnFocusGain = false
            resume()
        }
    }

    private var pendingResumeOnFocusGain: Boolean = false

    // ── Core playback control ────────────────────────────────────────────────

    private fun loadAndPlay(cmd: TtsEventBus.Command.LoadAndPlay) {
        scope.launch {
            controlMutex.withLock {
                speakJob?.cancelAndJoin()
                bookTitle = cmd.bookTitle.ifBlank { bookTitle }
                chapterTitle = cmd.chapterTitle
                coverUrl = cmd.coverUrl ?: coverUrl

                // Legado-style single-source-of-truth: when the ViewModel hands
                // us authoritative paragraph offsets (computed from
                // TextChapter.getParagraphs — the same data the renderer uses
                // for `upPageAloudSpan`), slice [content] at those offsets
                // instead of independently re-parsing. That guarantees
                // `paragraphs.size == paragraphPositions.size` so the
                // [paragraphIndex → chapterPosition] lookup never falls back to
                // 0, which previously caused the highlight to freeze on the
                // first paragraph whenever the two splitters disagreed.
                val pos = cmd.paragraphPositions
                val sliceMode: String
                if (pos != null && pos.isNotEmpty()) {
                    paragraphs = paragraphsFromPositions(cmd.content, pos)
                    paragraphPositions = pos
                    sliceMode = "fromPositions(${pos.size})"
                } else {
                    paragraphs = parseParagraphs(cmd.content)
                    paragraphPositions = buildSequentialPositions(paragraphs)
                    sliceMode = "parseParagraphs"
                }
                paragraphIndex = paragraphIndexForPosition(cmd.startChapterPosition)
                paragraphStartPos = 0 // 新章节加载，句中位置归零
                waitingForNextChapter = false

                // TTS-DIAG #5 — what the host actually loaded. Sample the first
                // 60 chars of the first non-blank paragraph so we can spot
                // "content cleaned to nothing" failures on real chapters.
                val firstNonBlank = paragraphs.firstOrNull { it.isNotBlank() }
                AppLog.info(
                    "TTS",
                    "Host.loadAndPlay: contentLen=${cmd.content.length}, " +
                        "mode=$sliceMode, paragraphs=${paragraphs.size}, " +
                        "positions=${paragraphPositions.size}, startIdx=$paragraphIndex, " +
                        "engine=$engineId, voice='$voiceName', speed=$speed, " +
                        "firstPara='${firstNonBlank?.take(60) ?: "<all blank>"}'",
                )

                publishState(playing = paragraphs.isNotEmpty())
                if (paragraphs.isEmpty()) {
                    AppLog.warn("TtsHost", "LoadAndPlay with empty paragraphs")
                    TtsEventBus.sendEvent(
                        TtsEventBus.Event.Error("无法朗读：本章节无可读文本", canOpenSettings = false)
                    )
                    return@withLock
                }
                speakJob = scope.launch { speakLoop() }
            }
        }
    }

    private fun resume() {
        if (paragraphs.isEmpty()) return
        if (TtsEventBus.playbackState.value.isPlaying && speakJob?.isActive == true) return
        scope.launch {
            controlMutex.withLock {
                speakJob?.cancelAndJoin()
                publishState(playing = true)
                speakJob = scope.launch { speakLoop() }
            }
        }
    }

    private fun pause() {
        scope.launch {
            controlMutex.withLock {
                speakJob?.cancelAndJoin()
                runCatching { currentEngine().stop() }
                publishState(playing = false)
            }
        }
    }

    private fun stopPlayback() {
        scope.launch {
            controlMutex.withLock {
                speakJob?.cancelAndJoin()
                runCatching { currentEngine().stop() }
                paragraphIndex = 0
                paragraphStartPos = 0
                waitingForNextChapter = false
                TtsEventBus.updatePlayback {
                    copy(
                        isPlaying = false,
                        paragraphIndex = 0,
                        chapterPosition = -1,
                        scrollProgress = -1f,
                    )
                }
            }
        }
    }

    private fun prevParagraph() {
        if (paragraphs.isEmpty()) return
        val newIdx = (paragraphIndex - 1).coerceAtLeast(0)
        if (newIdx == paragraphIndex && !TtsEventBus.playbackState.value.isPlaying) return
        paragraphIndex = newIdx
        paragraphStartPos = 0 // 用户主动切段，断点信息作废
        if (TtsEventBus.playbackState.value.isPlaying) {
            // restart loop at new index
            scope.launch {
                controlMutex.withLock {
                    speakJob?.cancelAndJoin()
                    publishState(playing = true)
                    speakJob = scope.launch { speakLoop() }
                }
            }
        } else {
            publishState(playing = false)
        }
    }

    private fun nextParagraph() {
        if (paragraphs.isEmpty()) return
        val newIdx = (paragraphIndex + 1).coerceAtMost(paragraphs.size - 1)
        if (newIdx == paragraphIndex && !TtsEventBus.playbackState.value.isPlaying) return
        paragraphIndex = newIdx
        paragraphStartPos = 0
        if (TtsEventBus.playbackState.value.isPlaying) {
            scope.launch {
                controlMutex.withLock {
                    speakJob?.cancelAndJoin()
                    publishState(playing = true)
                    speakJob = scope.launch { speakLoop() }
                }
            }
        } else {
            publishState(playing = false)
        }
    }

    // ── The speak loop ───────────────────────────────────────────────────────

    /**
     * 入口：按引擎类型分流。
     * - [SystemTtsEngine]：走 [runBatchPlayback]（仿 Legado，一次性入队整章）。
     * - [EdgeTtsEngine] / 其他：走 [runStreamingPlayback]（callbackFlow 串行）。
     *
     * 之前的统一串行实现遇到「listener 被替换 / onDone 不触发」时整个 host 卡死
     * 在第一段 collect 上（症状：进度永远 1/N，但用户能听到第 12 段——其实是
     * 引擎回调走丢，host 状态没推进）。批量路径用全局常驻 listener + utteranceId
     * 解析回调来源，根除这个 race。
     */
    private suspend fun speakLoop() {
        val engine = currentEngine()
        AppLog.info(
            "TTS",
            "Host.speakLoop: ENTER engine=${engine.javaClass.simpleName}, " +
                "paragraphs=${paragraphs.size}, startIdx=$paragraphIndex, " +
                "paragraphStartPos=$paragraphStartPos",
        )
        if (engine is SystemTtsEngine) {
            runBatchPlayback(engine)
        } else {
            runStreamingPlayback(engine)
        }
    }

    /**
     * 仿 Legado [TTSReadAloudService.play] 的批量入队播放。
     *
     * 流程：
     * 1. 把当前段及之后所有段全部规划成 utterance 列表（长段二次切句）。
     * 2. 注册批量回调：onUtteranceStart→更新 paragraphIndex+publishState；
     *    onUtteranceDone→若是整章最后一个 utt 则 `finished.complete()`；
     *    onRangeStart→刷新 paragraphStartPos（句中位置）。
     * 3. 一次性 enqueue（首条 QUEUE_FLUSH，后续 QUEUE_ADD）。
     * 4. await finished；任何中断（pause/stop/切段）由外层 cancel speakJob → 协程
     *    在 await 处抛 CancellationException。
     *
     * 第一段如有 [paragraphStartPos] > 0（pause 时记录的句中位置），切片后再入队，
     * 实现「真断点续读」——这是 Legado 没做但合理的扩展。
     */
    private suspend fun runBatchPlayback(engine: SystemTtsEngine) {
        // 等引擎就绪
        val initRes = engine.awaitReadyResult()
        if (initRes is SystemTtsEngine.InitResult.Failed) {
            AppLog.error("TtsHost", "System TTS init failed: ${initRes.reason}")
            TtsEventBus.sendEvent(
                TtsEventBus.Event.Error(initRes.reason, canOpenSettings = true)
            )
            publishState(playing = false)
            return
        }

        engine.setSpeechRate(speed)
        applyVoiceToEngine() // 确保语音配置应用到当前 engine 实例

        // 规划 utterance 列表
        data class Utt(
            val paraIdx: Int,
            val subIdx: Int,
            val text: String,
            val isLastSubOfPara: Boolean,
        )
        val plan = ArrayList<Utt>()
        for (idx in paragraphIndex until paragraphs.size) {
            val rawText = paragraphs[idx]
            if (rawText.isBlank() || skipRegex?.containsMatchIn(rawText) == true) continue
            // 第一段从断点切片
            val text = if (idx == paragraphIndex && paragraphStartPos in 1 until rawText.length) {
                rawText.substring(paragraphStartPos)
            } else {
                rawText
            }
            val subs = splitIntoSubSentences(text)
            for ((subIdx, sub) in subs.withIndex()) {
                if (sub.isBlank()) continue
                plan.add(Utt(idx, subIdx, sub, subIdx == subs.lastIndex))
            }
        }

        AppLog.info(
            "TTS",
            "Host.runBatchPlayback: plan size=${plan.size}, startPara=$paragraphIndex, " +
                "startPos=$paragraphStartPos",
        )

        if (plan.isEmpty()) {
            AppLog.warn("TtsHost", "runBatchPlayback: nothing to read in this chapter")
            publishState(playing = false)
            // 整章空（全是空段/标点段）→ 等同于读完，触发翻章
            waitingForNextChapter = true
            TtsEventBus.sendEvent(TtsEventBus.Event.ChapterFinished)
            return
        }

        val finished = CompletableDeferred<Unit>()
        val lastUtt = plan.last()

        val cb = object : SystemTtsEngine.BatchCallback {
            override fun onUtteranceStart(utteranceId: String) {
                val parsed = parseUtteranceId(utteranceId) ?: return
                val (paraIdx, _) = parsed
                if (paraIdx != paragraphIndex) {
                    paragraphIndex = paraIdx
                    paragraphStartPos = 0 // 段切换重置句中位置
                }
                publishState(playing = true)
            }

            override fun onUtteranceDone(utteranceId: String) {
                val parsed = parseUtteranceId(utteranceId) ?: return
                val (paraIdx, subIdx) = parsed
                if (paraIdx == lastUtt.paraIdx && subIdx == lastUtt.subIdx) {
                    if (!finished.isCompleted) finished.complete(Unit)
                }
            }

            override fun onUtteranceError(utteranceId: String, errorCode: Int) {
                AppLog.warn("TtsHost", "batch utterance error id=$utteranceId code=$errorCode")
                // 单段失败：把它当作完成跳过；如果是最后一段也要 fulfill finished
                val parsed = parseUtteranceId(utteranceId)
                if (parsed != null) {
                    val (paraIdx, subIdx) = parsed
                    if (paraIdx == lastUtt.paraIdx && subIdx == lastUtt.subIdx) {
                        if (!finished.isCompleted) finished.complete(Unit)
                    }
                }
            }

            override fun onRangeStart(utteranceId: String, start: Int, end: Int) {
                val parsed = parseUtteranceId(utteranceId) ?: return
                val (paraIdx, subIdx) = parsed
                if (paraIdx == paragraphIndex) {
                    // 当 utterance 是从段断点切片来的，要把切片偏移加回原段坐标
                    val sliceOffset = if (subIdx == 0 && paraIdx == paragraphIndex) {
                        paragraphStartPosBaseForFirstSub
                    } else 0
                    paragraphStartPos = (sliceOffset + start).coerceAtLeast(0)
                }
            }
        }

        // 记下"第一段第一子句"的切片基准（用于 onRangeStart 把局部偏移还原成段内坐标）
        // 注意：plan 入队后 paragraphStartPos 会被回调改写，所以这里用一个独立字段保存初始值
        paragraphStartPosBaseForFirstSub = if (paragraphStartPos in 1 until (paragraphs.getOrNull(paragraphIndex)?.length ?: 0)) {
            paragraphStartPos
        } else 0

        engine.setBatchCallback(cb)

        // 入队
        for ((i, utt) in plan.withIndex()) {
            val mode = if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            val id = "morealm_${utt.paraIdx}_${utt.subIdx}"
            val result = engine.enqueue(utt.text, id, mode)
            if (result == TextToSpeech.ERROR) {
                AppLog.error("TtsHost", "batch enqueue ERROR at i=$i id=$id, aborting")
                engine.setBatchCallback(null)
                TtsEventBus.sendEvent(
                    TtsEventBus.Event.Error("TTS 入队失败，请重试", canOpenSettings = true)
                )
                publishState(playing = false)
                return
            }
        }

        // 等整章读完或被取消
        try {
            finished.await()
        } catch (_: CancellationException) {
            // pause/stop/切段被外层 cancel：此时 engine.stop() 已被调用（pause/stop/setEngine 都会调）
            AppLog.debug("TtsHost", "runBatchPlayback: cancelled mid-flight")
            engine.setBatchCallback(null)
            throw kotlin.coroutines.cancellation.CancellationException("batch cancelled")
        }
        engine.setBatchCallback(null)

        // 章节读完
        AppLog.info("TtsHost", "runBatchPlayback: chapter finished")
        waitingForNextChapter = true
        TtsEventBus.sendEvent(TtsEventBus.Event.ChapterFinished)
        val gotNext = withTimeoutOrNull(WAIT_NEXT_CHAPTER_MS) {
            while (waitingForNextChapter) delay(50)
            true
        }
        if (gotNext != true) {
            AppLog.info("TtsHost", "runBatchPlayback: no next chapter within ${WAIT_NEXT_CHAPTER_MS}ms, stopping")
            publishState(playing = false)
        }
    }

    /** 仅供 [onRangeStart] 把切片局部偏移还原成段内坐标用。 */
    @Volatile private var paragraphStartPosBaseForFirstSub: Int = 0

    /** 解析 utteranceId "morealm_{paraIdx}_{subIdx}" → (paraIdx, subIdx)。 */
    private fun parseUtteranceId(id: String): Pair<Int, Int>? {
        if (!id.startsWith("morealm_")) return null
        val parts = id.removePrefix("morealm_").split("_")
        if (parts.size != 2) return null
        val p = parts[0].toIntOrNull() ?: return null
        val s = parts[1].toIntOrNull() ?: return null
        return p to s
    }

    /**
     * 长段二次切句。Legado 没做这步——它的 contentList 来自排版后的 page.text，自带换行。
     * MoRealm 是按章节段 offset 切片，长段（200+ 字）整体扔给引擎会断句不自然。
     *
     * 切分点：。！？；和换行。保留分隔符在前一子句末尾。短于 [LONG_PARA_THRESHOLD]
     * 的段不切，避免把短句切碎让引擎反复重启 prosody。
     */
    private fun splitIntoSubSentences(text: String): List<String> {
        if (text.length <= LONG_PARA_THRESHOLD) return listOf(text)
        val result = ArrayList<String>()
        val sb = StringBuilder()
        for (c in text) {
            sb.append(c)
            if (c in SENTENCE_SEPARATORS) {
                if (sb.isNotBlank()) result.add(sb.toString())
                sb.clear()
            }
        }
        if (sb.isNotEmpty()) result.add(sb.toString())
        return if (result.isEmpty()) listOf(text) else result
    }

    /**
     * EdgeTts / 其他流式引擎走的老 callbackFlow 串行路径。
     * 保留旧实现是因为这些引擎一次返回一段音频流，没有 TTS 引擎层面的"队列"概念。
     */
    private suspend fun runStreamingPlayback(engine: TtsEngine) {
        var consecutiveErrors = 0
        try {
            for (idx in paragraphIndex until paragraphs.size) {
                if (!TtsEventBus.playbackState.value.isPlaying) {
                    AppLog.info("TTS", "Host.runStreamingPlayback: isPlaying=false, exit at idx=$idx")
                    return
                }
                paragraphIndex = idx
                paragraphStartPos = 0
                publishState(playing = true)

                val paragraphText = paragraphs[idx]
                if (paragraphText.isBlank() ||
                    skipRegex?.containsMatchIn(paragraphText) == true) {
                    continue
                }

                try {
                    AppLog.info(
                        "TTS",
                        "Host.runStreamingPlayback: speak idx=$idx/${paragraphs.size} " +
                            "len=${paragraphText.length}",
                    )
                    engine.speak(paragraphText, speed).collect { /* drain */ }
                    consecutiveErrors = 0
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    consecutiveErrors++
                    AppLog.warn("TtsHost", "stream speak error para $idx (consec=$consecutiveErrors)", e)
                    if (consecutiveErrors == 1) {
                        TtsEventBus.sendEvent(
                            TtsEventBus.Event.Error(
                                e.message ?: "TTS 朗读失败",
                                canOpenSettings = engineId == "system",
                            )
                        )
                    }
                    if (consecutiveErrors >= 3) {
                        if (engineId != "system") {
                            AppLog.info("TtsHost", "Stream engine fallback → system after repeated errors")
                            TtsEventBus.sendEvent(
                                TtsEventBus.Event.Error(
                                    "${engine.javaClass.simpleName} 多次失败，已自动切换为系统朗读",
                                    canOpenSettings = false,
                                )
                            )
                            withContext(NonCancellable) { prefs.setTtsEngine("system") }
                            engineId = "system"
                            voiceName = savedVoiceForEngine("system")
                            applyVoiceToEngine()
                            val voices = loadVoicesForEngine("system")
                            TtsEventBus.updatePlayback {
                                copy(engine = "system", voiceName = voiceName, voices = voices)
                            }
                            consecutiveErrors = 0
                            speakLoop() // 切到 system → 入口会进入 batch 路径
                            return
                        }
                        AppLog.error("TtsHost", "Stream engine repeatedly failing, stopping", e)
                        TtsEventBus.sendEvent(
                            TtsEventBus.Event.Error(
                                "TTS 连续 3 次朗读失败，已停止：${e.message ?: "未知错误"}",
                                canOpenSettings = true,
                            )
                        )
                        publishState(playing = false)
                        return
                    }
                    delay(200)
                }
            }
            // chapter end
            waitingForNextChapter = true
            TtsEventBus.sendEvent(TtsEventBus.Event.ChapterFinished)
            val gotNext = withTimeoutOrNull(WAIT_NEXT_CHAPTER_MS) {
                while (waitingForNextChapter) delay(50)
                true
            }
            if (gotNext != true) publishState(playing = false)
        } catch (_: CancellationException) {
            // normal cancel
        } catch (e: Exception) {
            AppLog.error("TtsHost", "runStreamingPlayback crashed", e)
            publishState(playing = false)
        }
    }

    // ── Configuration setters ────────────────────────────────────────────────

    private fun setSpeed(newSpeed: Float) {
        speed = newSpeed.coerceIn(0.3f, 4.0f)
        scope.launch { prefs.setTtsSpeed(speed) }
        TtsEventBus.updatePlayback { copy(speed = speed) }
        // Speed change takes effect on the next paragraph; no need to restart.
    }

    private fun setEngine(newEngine: String) {
        if (newEngine == engineId) return
        scope.launch {
            controlMutex.withLock {
                val wasPlaying = TtsEventBus.playbackState.value.isPlaying
                speakJob?.cancelAndJoin()
                runCatching { currentEngine().stop() }
                engineId = newEngine
                prefs.setTtsEngine(newEngine)
                voiceName = savedVoiceForEngine(newEngine)
                applyVoiceToEngine()
                val voices = loadVoicesForEngine(newEngine)
                TtsEventBus.updatePlayback {
                    copy(engine = newEngine, voiceName = voiceName, voices = voices)
                }
                if (wasPlaying && paragraphs.isNotEmpty()) {
                    publishState(playing = true)
                    speakJob = scope.launch { speakLoop() }
                }
            }
        }
    }

    private fun setVoice(newVoice: String) {
        val resolved = resolveVoiceOrEmpty(newVoice, TtsEventBus.playbackState.value.voices)
        voiceName = resolved
        applyVoiceToEngine()
        scope.launch { saveVoiceForEngine(engineId, resolved) }
        TtsEventBus.updatePlayback { copy(voiceName = resolved) }
    }

    private fun setSkipPattern(pattern: String) {
        skipRegex = if (pattern.isNotBlank()) {
            runCatching { Regex(pattern) }.getOrNull()
        } else null
        scope.launch { prefs.setTtsSkipPattern(pattern) }
    }

    private fun setSleepMinutes(minutes: Int) {
        sleepRemainingMinutes = minutes
        sleepTimerJob?.cancel()
        TtsEventBus.updatePlayback { copy(sleepMinutes = minutes) }
        if (minutes > 0) {
            sleepTimerJob = scope.launch {
                var remaining = minutes
                while (remaining > 0) {
                    delay(60_000L)
                    remaining -= 1
                    sleepRemainingMinutes = remaining
                    TtsEventBus.updatePlayback { copy(sleepMinutes = remaining) }
                }
                AppLog.info("TtsHost", "Sleep timer expired")
                stopPlayback()
                TtsEventBus.sendCommand(TtsEventBus.Command.StopService)
            }
        }
    }

    private fun speakOneShot(text: String) {
        if (text.isBlank()) return
        oneShotJob?.cancel()
        oneShotJob = scope.launch {
            try {
                val engine = currentEngine()
                if (engine is SystemTtsEngine) {
                    val initRes = engine.awaitReadyResult()
                    if (initRes is SystemTtsEngine.InitResult.Failed) {
                        AppLog.error("TtsHost", "speakOneShot: TTS init failed: ${initRes.reason}")
                        TtsEventBus.sendEvent(
                            TtsEventBus.Event.Error(initRes.reason, canOpenSettings = true)
                        )
                        return@launch
                    }
                }
                engine.speak(text, speed).collect { /* drain */ }
            } catch (_: CancellationException) {
            } catch (e: Exception) {
                AppLog.warn("TtsHost", "speakOneShot failed", e)
                // One-shot failures (試聽片段) — opening system settings rarely helps,
                // so leave canOpenSettings = false. The exception's localized message
                // (set by SystemTtsEngine.speak's IllegalStateException path) carries
                // the actionable reason already.
                TtsEventBus.sendEvent(
                    TtsEventBus.Event.Error(
                        e.message ?: "TTS 朗读失败",
                        canOpenSettings = false,
                    )
                )
            }
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun publishState(playing: Boolean) {
        val total = paragraphs.size
        val pos = paragraphPositions.getOrNull(paragraphIndex) ?: 0
        val progress = if (total > 1) paragraphIndex.toFloat() / (total - 1) else if (total == 1) 1f else -1f
        TtsEventBus.updatePlayback {
            copy(
                bookTitle = this@TtsEngineHost.bookTitle.ifBlank { this.bookTitle },
                chapterTitle = this@TtsEngineHost.chapterTitle.ifBlank { this.chapterTitle },
                coverUrl = this@TtsEngineHost.coverUrl ?: this.coverUrl,
                isPlaying = playing,
                paragraphIndex = paragraphIndex,
                totalParagraphs = total,
                chapterPosition = if (playing) pos else -1,
                scrollProgress = if (playing) progress else -1f,
                speed = speed,
                engine = engineId,
                voiceName = voiceName,
            )
        }
    }

    private fun currentEngine(): TtsEngine {
        return when {
            engineId == "edge" -> edgeTtsEngine
            else -> systemTtsEngine
        }
    }

    private fun applyVoiceToEngine() {
        when (engineId) {
            "edge" -> edgeTtsEngine.setVoice(voiceName)
            else -> systemTtsEngine.setVoice(voiceName)
        }
    }

    private suspend fun loadVoicesForEngine(engine: String): List<TtsVoice> {
        return if (engine == "edge") {
            EdgeTtsEngine.VOICES
        } else {
            // Same timeout-bounded init resolution used by speakLoop —
            // prevents the voice picker from hanging forever when the device
            // has no TTS engine bound.
            when (val initRes = systemTtsEngine.awaitReadyResult()) {
                is SystemTtsEngine.InitResult.Failed -> {
                    AppLog.warn("TtsHost", "loadVoicesForEngine: ${initRes.reason}")
                    TtsEventBus.sendEvent(
                        TtsEventBus.Event.Error(initRes.reason, canOpenSettings = true)
                    )
                    emptyList()
                }
                SystemTtsEngine.InitResult.Success -> systemTtsEngine.getChineseVoices()
            }
        }
    }

    private suspend fun savedVoiceForEngine(engine: String): String {
        val saved = if (engine == "edge") prefs.ttsEdgeVoice.first() else prefs.ttsSystemVoice.first()
        return saved.ifBlank { prefs.ttsVoice.first() }
    }

    private suspend fun saveVoiceForEngine(engine: String, voice: String) {
        if (engine == "edge") prefs.setTtsEdgeVoice(voice) else prefs.setTtsSystemVoice(voice)
        prefs.setTtsVoice(voice)
    }

    private fun resolveVoiceOrEmpty(voice: String, voices: List<TtsVoice>): String {
        if (voice.isBlank()) return ""
        return voice.takeIf { selected -> voices.any { it.id == selected } } ?: ""
    }

    private fun parseParagraphs(content: String): List<String> {
        val text = content
            .replace(AppPattern.htmlImgRegex, "")
            .replace(AppPattern.htmlSvgRegex, "")
            .replace(AppPattern.htmlDivCloseRegex, "\n")
            .replace(AppPattern.htmlBrRegex, "\n")
            .replace(AppPattern.htmlTagRegex, "")
            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&nbsp;", " ").replace("&quot;", "\"").replace("&#39;", "'")
        val paras = text.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() && it.length > 1 }
        val skip = skipRegex
        return if (skip != null) paras.filter { !skip.containsMatchIn(it) } else paras
    }

    /**
     * Slice [content] at the supplied paragraph offsets to produce a paragraph
     * list whose size and ordering exactly matches [positions]. Each slice is
     * lightly cleaned (HTML tag strip + entity decode + trim) so the engine
     * speaks readable text, but blank slices are KEPT in place — the speak
     * loop skips them at iteration time so the highlight still advances
     * across silent gaps.
     *
     * Why this exists: when the renderer hands us authoritative offsets via
     * `Command.LoadAndPlay.paragraphPositions`, re-parsing with
     * [parseParagraphs] would drop empty / single-char paragraphs and put the
     * lists out of step with the renderer — making the highlight stick at
     * paragraph 0 because `paragraphPositions.getOrNull(idx)` returns null
     * partway through. Slicing keeps the 1:1 invariant.
     */
    private fun paragraphsFromPositions(content: String, positions: List<Int>): List<String> {
        if (positions.isEmpty()) return emptyList()
        val out = ArrayList<String>(positions.size)
        for (i in positions.indices) {
            val rawStart = positions[i]
            val rawEnd = positions.getOrNull(i + 1) ?: content.length
            val start = rawStart.coerceIn(0, content.length)
            val end = rawEnd.coerceIn(start, content.length)
            val cleaned = content.substring(start, end)
                .replace(AppPattern.htmlImgRegex, "")
                .replace(AppPattern.htmlSvgRegex, "")
                .replace(AppPattern.htmlDivCloseRegex, "\n")
                .replace(AppPattern.htmlBrRegex, "\n")
                .replace(AppPattern.htmlTagRegex, "")
                .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&nbsp;", " ").replace("&quot;", "\"").replace("&#39;", "'")
                .trim()
            out.add(cleaned)
        }
        return out
    }

    private fun buildSequentialPositions(paras: List<String>): List<Int> {
        val out = ArrayList<Int>(paras.size)
        var pos = 0
        for (p in paras) { out.add(pos); pos += p.length + 1 }
        return out
    }

    private fun paragraphIndexForPosition(position: Int): Int {
        if (paragraphs.isEmpty() || position <= 0) return 0
        for (i in paragraphPositions.indices) {
            val start = paragraphPositions[i]
            val next = paragraphPositions.getOrNull(i + 1) ?: Int.MAX_VALUE
            if (position in start until next) return i
        }
        return paragraphs.lastIndex.coerceAtLeast(0)
    }

    private suspend fun Job.cancelAndJoin() {
        cancel()
        try { join() } catch (_: CancellationException) {}
    }

    companion object {
        /** How long the host waits for a LoadAndPlay after ChapterFinished before giving up. */
        private const val WAIT_NEXT_CHAPTER_MS = 5_000L

        /** 长段二次切句阈值（字符数）。<= 80 不切，避免短段被切碎。 */
        private const val LONG_PARA_THRESHOLD = 80

        /** 切句分隔符：中文标点 + 换行（结合 splitIntoSubSentences 使用）。 */
        private val SENTENCE_SEPARATORS = setOf('。', '！', '？', '；', '\n')
    }
}
