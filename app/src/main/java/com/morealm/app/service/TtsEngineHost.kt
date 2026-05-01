package com.morealm.app.service

import android.content.Context
import com.morealm.app.core.log.AppLog
import com.morealm.app.core.text.AppPattern
import com.morealm.app.domain.entity.TtsVoice
import com.morealm.app.domain.preference.AppPreferences
import com.morealm.app.domain.tts.EdgeTtsEngine
import com.morealm.app.domain.tts.SystemTtsEngine
import com.morealm.app.domain.tts.TtsEngine
import kotlinx.coroutines.CancellationException
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

    private suspend fun speakLoop() {
        var consecutiveErrors = 0
        try {
            val engine = currentEngine()
            // TTS-DIAG #6 — speak loop entered. If you see #5 but never #6,
            // the speakJob coroutine never actually got scheduled.
            AppLog.info(
                "TTS",
                "Host.speakLoop: ENTER engine=${engine.javaClass.simpleName}, " +
                    "paragraphs=${paragraphs.size}, startIdx=$paragraphIndex",
            )
            if (engine is SystemTtsEngine) {
                // Resolve init with a hard timeout (4s default inside SystemTtsEngine).
                // On failure, surface a user-facing reason and bail out cleanly instead
                // of letting the speak loop deadlock on a never-ready engine.
                val initRes = engine.awaitReadyResult()
                AppLog.info("TTS", "Host.speakLoop: SystemTtsEngine awaitReadyResult=$initRes")
                if (initRes is SystemTtsEngine.InitResult.Failed) {
                    AppLog.error("TtsHost", "System TTS init failed: ${initRes.reason}")
                    TtsEventBus.sendEvent(
                        TtsEventBus.Event.Error(initRes.reason, canOpenSettings = true)
                    )
                    publishState(playing = false)
                    return
                }
            }

            for (idx in paragraphIndex until paragraphs.size) {
                if (!TtsEventBus.playbackState.value.isPlaying) {
                    AppLog.info("TTS", "Host.speakLoop: isPlaying flipped to false, exiting at idx=$idx")
                    return
                }
                paragraphIndex = idx
                publishState(playing = true)

                val paragraphText = paragraphs[idx]
                // Legado parity: skip blank / punctuation-only paragraphs but
                // still advance the highlight so the user can see TTS scrolling
                // through silent gaps (chapter dividers, image-only lines).
                if (paragraphText.isBlank() ||
                    skipRegex?.containsMatchIn(paragraphText) == true) {
                    AppLog.debug("TTS", "Host.speakLoop: skip idx=$idx (blank or matches skipRegex)")
                    continue
                }

                try {
                    AppLog.info(
                        "TTS",
                        "Host.speakLoop: speak idx=$idx/${paragraphs.size} " +
                            "len=${paragraphText.length} text='${paragraphText.take(40)}'",
                    )
                    engine.speak(paragraphText, speed).collect { /* drain */ }
                    AppLog.debug("TTS", "Host.speakLoop: speak idx=$idx returned normally")
                    consecutiveErrors = 0
                } catch (ce: CancellationException) {
                    AppLog.debug("TTS", "Host.speakLoop: cancelled at idx=$idx")
                    throw ce
                } catch (e: Exception) {
                    consecutiveErrors++
                    AppLog.warn("TtsHost", "speak error on para $idx (consec=$consecutiveErrors)", e)
                    // Recovery path A — when the system engine reports a
                    // synchronous ERROR (most often a corrupted voice binding
                    // after the language pack changed under us), tearing down
                    // and rebinding TextToSpeech almost always clears the
                    // problem. We do this on the FIRST failure, before the
                    // 3-strikes terminal stop, mirroring Legado's recovery in
                    // TTSReadAloudService.
                    if (consecutiveErrors == 1 && engine is SystemTtsEngine) {
                        AppLog.info(
                            "TtsHost",
                            "speak error is recoverable — will reInit SystemTtsEngine and retry para $idx",
                        )
                        runCatching { engine.reInit() }
                            .onFailure { AppLog.warn("TtsHost", "reInit threw: ${it.message}", it) }
                        // Re-apply the user's voice selection on the fresh
                        // binding; otherwise the rebinding could have dropped
                        // back to system default.
                        applyVoiceToEngine()
                        // Retry the SAME paragraph by stepping idx back one —
                        // the for-loop will increment again on next iteration.
                        // We DON'T emit the user-facing toast yet; if the retry
                        // succeeds the user never sees a stutter.
                        delay(150)
                        AppLog.info(
                            "TtsHost",
                            "reInit complete; retrying paragraph $idx (silent recovery, no Toast)",
                        )
                        // Loop will re-attempt this paragraph because we
                        // continue here — but the for-loop's idx has already
                        // advanced; explicitly retry by recursing into a tail
                        // call of speakLoop with paragraphIndex = idx.
                        paragraphIndex = idx
                        speakLoop()
                        return
                    }
                    // Surface the very first failure of a streak immediately so
                    // the user gets a Toast instead of staring at a silent
                    // reader. TtsErrorPresenter dedupes within 3s, so a retry
                    // storm won't produce a flood of toasts.
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
                            AppLog.info("TtsHost", "Falling back to system engine after repeated errors")
                            // Tell the user *why* the voice just changed —
                            // previously this was a silent fallback and users
                            // assumed the engine setting had been forgotten.
                            TtsEventBus.sendEvent(
                                TtsEventBus.Event.Error(
                                    "Edge TTS 多次失败，已自动切换为系统朗读",
                                    canOpenSettings = false,
                                )
                            )
                            // Persist the fallback so it survives next launch.
                            withContext(NonCancellable) { prefs.setTtsEngine("system") }
                            engineId = "system"
                            voiceName = savedVoiceForEngine("system")
                            applyVoiceToEngine()
                            val voices = loadVoicesForEngine("system")
                            TtsEventBus.updatePlayback {
                                copy(engine = "system", voiceName = voiceName, voices = voices)
                            }
                            consecutiveErrors = 0
                            // restart the loop with system engine
                            speakLoop()
                            return
                        }
                        AppLog.error("TtsHost", "Engine repeatedly failing, stopping playback", e)
                        // Terminal stop after 3 strikes on the system engine.
                        // Without this Error event the user previously just saw
                        // the play button toggle off with no explanation.
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
            // Reached the end of paragraphs in this chapter.
            waitingForNextChapter = true
            TtsEventBus.sendEvent(TtsEventBus.Event.ChapterFinished)
            // Wait briefly for ViewModel to send LoadAndPlay; if nothing arrives, stop.
            val gotNext = withTimeoutOrNull(WAIT_NEXT_CHAPTER_MS) {
                while (waitingForNextChapter) delay(50)
                true
            }
            if (gotNext != true) {
                AppLog.info("TtsHost", "No next chapter received within ${WAIT_NEXT_CHAPTER_MS}ms; stopping")
                publishState(playing = false)
            }
        } catch (_: CancellationException) {
            // normal cancel, leave isPlaying as set by caller
        } catch (e: Exception) {
            AppLog.error("TtsHost", "speakLoop crashed", e)
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
    }
}
