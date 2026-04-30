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

                paragraphs = parseParagraphs(cmd.content)
                paragraphPositions = cmd.paragraphPositions ?: buildSequentialPositions(paragraphs)
                paragraphIndex = paragraphIndexForPosition(cmd.startChapterPosition)
                waitingForNextChapter = false

                publishState(playing = paragraphs.isNotEmpty())
                if (paragraphs.isEmpty()) {
                    AppLog.warn("TtsHost", "LoadAndPlay with empty paragraphs")
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
            if (engine is SystemTtsEngine) engine.awaitReady()

            for (idx in paragraphIndex until paragraphs.size) {
                if (!TtsEventBus.playbackState.value.isPlaying) return
                paragraphIndex = idx
                publishState(playing = true)

                try {
                    engine.speak(paragraphs[idx], speed).collect { /* drain */ }
                    consecutiveErrors = 0
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    consecutiveErrors++
                    AppLog.warn("TtsHost", "speak error on para $idx (consec=$consecutiveErrors)", e)
                    if (consecutiveErrors >= 3) {
                        if (engineId != "system") {
                            AppLog.info("TtsHost", "Falling back to system engine after repeated errors")
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
                if (engine is SystemTtsEngine) engine.awaitReady()
                engine.speak(text, speed).collect { /* drain */ }
            } catch (_: CancellationException) {
            } catch (e: Exception) {
                AppLog.warn("TtsHost", "speakOneShot failed", e)
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
            systemTtsEngine.awaitReady()
            systemTtsEngine.getChineseVoices()
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
