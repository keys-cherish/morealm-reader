package com.morealm.app.presentation.reader

import android.content.Context
import android.content.Intent
import android.os.Build
import com.morealm.app.domain.preference.AppPreferences
import com.morealm.app.core.text.AppPattern
import com.morealm.app.core.text.stripHtml
import com.morealm.app.service.TtsEventBus
import com.morealm.app.service.TtsPlaybackState
import com.morealm.app.service.TtsService
import com.morealm.app.core.log.AppLog
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * Manages all TTS (Text-to-Speech) functionality for the reader.
 * Extracted from ReaderViewModel to keep it focused.
 */
class ReaderTtsController(
    private val context: Context,
    private val prefs: AppPreferences,
    private val scope: CoroutineScope,
) {
    // ── State ──
    private val _ttsPlaying = MutableStateFlow(false)
    val ttsPlaying: StateFlow<Boolean> = _ttsPlaying.asStateFlow()

    private val _ttsSpeed = MutableStateFlow(1.0f)
    val ttsSpeed: StateFlow<Float> = _ttsSpeed.asStateFlow()

    private val _ttsEngine = MutableStateFlow("system")
    val ttsEngine: StateFlow<String> = _ttsEngine.asStateFlow()

    private val _ttsParagraphIndex = MutableStateFlow(0)
    val ttsParagraphIndex: StateFlow<Int> = _ttsParagraphIndex.asStateFlow()

    private val _ttsTotalParagraphs = MutableStateFlow(0)
    val ttsTotalParagraphs: StateFlow<Int> = _ttsTotalParagraphs.asStateFlow()

    private val _ttsSleepMinutes = MutableStateFlow(0)
    val ttsSleepMinutes: StateFlow<Int> = _ttsSleepMinutes.asStateFlow()

    private val _ttsVoices = MutableStateFlow<List<com.morealm.app.domain.entity.TtsVoice>>(emptyList())
    val ttsVoices: StateFlow<List<com.morealm.app.domain.entity.TtsVoice>> = _ttsVoices.asStateFlow()

    private val _ttsVoiceName = MutableStateFlow("")
    val ttsVoiceName: StateFlow<String> = _ttsVoiceName.asStateFlow()

    /** TTS scroll progress: 0.0 to 1.0, used to auto-scroll WebView during TTS */
    private val _ttsScrollProgress = MutableStateFlow(-1f)
    val ttsScrollProgress: StateFlow<Float> = _ttsScrollProgress.asStateFlow()

    private val _ttsChapterPosition = MutableStateFlow(-1)
    val ttsChapterPosition: StateFlow<Int> = _ttsChapterPosition.asStateFlow()

    private var ttsParagraphs: List<String> = emptyList()
    private var ttsParagraphPositions: List<Int> = emptyList()
    private var ttsJob: Job? = null
    private var sleepTimerJob: Job? = null
    private var ttsServiceStarted = false
    private var ttsPausedByFocusLoss = false
    private var ttsSkipRegex: Regex? = null
    private var onPrevChapter: (() -> Unit)? = null
    private var onNextChapter: (() -> Unit)? = null

    private val systemTtsEngine by lazy {
        com.morealm.app.domain.tts.SystemTtsEngine(context).also { it.initialize() }
    }

    private val edgeTtsEngine by lazy {
        com.morealm.app.domain.tts.EdgeTtsEngine()
    }

    private fun currentTtsEngine(): com.morealm.app.domain.tts.TtsEngine {
        return if (_ttsEngine.value == "edge") edgeTtsEngine else systemTtsEngine
    }

    private fun voiceEngineId(engine: String): String =
        if (engine == "edge") "edge" else "system"

    private suspend fun voicesForEngine(engineId: String): List<com.morealm.app.domain.entity.TtsVoice> {
        return if (engineId == "edge") {
            com.morealm.app.domain.tts.EdgeTtsEngine.VOICES
        } else {
            systemTtsEngine.awaitReady()
            systemTtsEngine.getChineseVoices()
        }
    }

    private suspend fun savedVoiceForEngine(engineId: String): String {
        val engineVoice = if (engineId == "edge") {
            prefs.ttsEdgeVoice.first()
        } else {
            prefs.ttsSystemVoice.first()
        }
        return engineVoice.ifBlank { prefs.ttsVoice.first() }
    }

    private suspend fun saveVoiceForEngine(engineId: String, voiceName: String) {
        if (engineId == "edge") {
            prefs.setTtsEdgeVoice(voiceName)
        } else {
            prefs.setTtsSystemVoice(voiceName)
        }
        // Keep the legacy key updated for older code paths and existing backups.
        prefs.setTtsVoice(voiceName)
    }

    private fun validVoiceOrDefault(
        voiceName: String,
        voices: List<com.morealm.app.domain.entity.TtsVoice>,
    ): String {
        if (voiceName.isBlank()) return ""
        return voiceName.takeIf { selected -> voices.any { it.id == selected } } ?: ""
    }

    private fun applyTtsVoice(engineId: String, voiceName: String) {
        if (engineId == "edge") {
            edgeTtsEngine.setVoice(voiceName)
        } else {
            systemTtsEngine.setVoice(voiceName)
        }
    }

    private suspend fun refreshVoicesForEngine(engine: String, preferredVoice: String? = null) {
        val engineId = voiceEngineId(engine)
        val voices = voicesForEngine(engineId)
        val resolvedVoice = validVoiceOrDefault(
            voiceName = preferredVoice ?: savedVoiceForEngine(engineId),
            voices = voices,
        )
        _ttsVoices.value = voices
        _ttsVoiceName.value = resolvedVoice
        applyTtsVoice(engineId, resolvedVoice)
    }

    /** Initialize TTS preferences and event listeners. Call from ViewModel init. */
    fun initialize(
        getBookTitle: () -> String,
        getChapterTitle: () -> String,
    ) {
        scope.launch {
            prefs.ttsSpeed.first().let { _ttsSpeed.value = it }
        }
        scope.launch {
            prefs.ttsSkipPattern.first().let { pattern ->
                ttsSkipRegex = if (pattern.isNotBlank()) {
                    try { Regex(pattern) } catch (_: Exception) { null }
                } else null
            }
        }
        scope.launch {
            val savedEngine = prefs.ttsEngine.first()
            _ttsEngine.value = savedEngine
            refreshVoicesForEngine(savedEngine)
        }
        scope.launch {
            TtsEventBus.events.collect { event ->
                when (event) {
                    is TtsEventBus.Event.PlayPause -> ttsPlayPause()
                    is TtsEventBus.Event.PrevChapter -> onPrevChapter?.invoke()
                    is TtsEventBus.Event.NextChapter -> onNextChapter?.invoke()
                    is TtsEventBus.Event.AudioFocusLoss -> {
                        if (_ttsPlaying.value) {
                            ttsPausedByFocusLoss = event.resumeOnGain
                            ttsPause()
                        } else {
                            ttsPausedByFocusLoss = false
                        }
                    }
                    is TtsEventBus.Event.AudioFocusGain -> {
                        if (ttsPausedByFocusLoss) {
                            ttsPausedByFocusLoss = false
                            ttsPlay(null, null, null, onChapterFinished = null)
                        }
                    }
                }
            }
        }
    }

    /** Collect TTS events that need ViewModel-level handling (chapter navigation). */
    fun collectChapterEvents(onPrev: () -> Unit, onNext: () -> Unit) {
        onPrevChapter = onPrev
        onNextChapter = onNext
    }

    fun resetParagraphIndex() {
        _ttsParagraphIndex.value = 0
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
        val paragraphs = text.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() && it.length > 1 }
        val skip = ttsSkipRegex
        return if (skip != null) {
            paragraphs.filter { !skip.containsMatchIn(it) }
        } else paragraphs
    }

    private fun ensureTtsService(bookTitle: String, chapterTitle: String, coverUrl: String? = null) {
        if (ttsServiceStarted) return
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

    private fun pushTtsState(playing: Boolean, bookTitle: String = "", chapterTitle: String = "", coverUrl: String? = null) {
        TtsEventBus.sendCommand(TtsEventBus.Command.UpdateMeta(bookTitle, chapterTitle, coverUrl))
        TtsEventBus.sendCommand(TtsEventBus.Command.SetPlaying(playing))
        TtsEventBus.updatePlayback {
            copy(
                bookTitle = bookTitle.ifBlank { this.bookTitle },
                chapterTitle = chapterTitle.ifBlank { this.chapterTitle },
                isPlaying = playing,
                speed = _ttsSpeed.value,
                engine = _ttsEngine.value,
            )
        }
    }

    fun ttsPlayPause(
        displayedContent: String? = null,
        bookTitle: String? = null,
        chapterTitle: String? = null,
        coverUrl: String? = null,
        startChapterPosition: Int? = null,
        paragraphPositions: List<Int>? = null,
        onChapterFinished: (() -> Unit)? = null,
    ) {
        if (_ttsPlaying.value) ttsPause()
        else ttsPlay(displayedContent, bookTitle, chapterTitle, coverUrl, startChapterPosition, paragraphPositions, onChapterFinished)
    }

    /**
     * Start TTS playback.
     * @param displayedContent current chapter content (null = resume from current paragraphs)
     * @param bookTitle book title for notification
     * @param chapterTitle chapter title for notification
     * @param coverUrl book cover URL for notification artwork
     * @param onChapterFinished callback when chapter finishes (for auto-advance)
     */
    fun ttsPlay(
        displayedContent: String?,
        bookTitle: String?,
        chapterTitle: String?,
        coverUrl: String? = null,
        startChapterPosition: Int? = null,
        paragraphPositions: List<Int>? = null,
        onChapterFinished: (() -> Unit)?,
    ) {
        ensureTtsService(bookTitle ?: "", chapterTitle ?: "", coverUrl)
        _ttsPlaying.value = true
        pushTtsState(true, bookTitle ?: "", chapterTitle ?: "", coverUrl)

        ttsJob?.cancel()
        ttsJob = scope.launch {
            try {
                if (displayedContent != null) {
                    ttsParagraphs = parseParagraphs(displayedContent)
                    ttsParagraphPositions = paragraphPositions ?: buildSequentialParagraphPositions(ttsParagraphs)
                    _ttsTotalParagraphs.value = ttsParagraphs.size
                    startChapterPosition?.let { position ->
                        _ttsParagraphIndex.value = paragraphIndexForChapterPosition(position)
                        _ttsChapterPosition.value = position.coerceAtLeast(0)
                    }
                }
                if (ttsParagraphs.isEmpty()) {
                    _ttsPlaying.value = false
                    pushTtsState(false)
                    return@launch
                }

                val startIdx = _ttsParagraphIndex.value.coerceIn(0, ttsParagraphs.size - 1)
                val engine = currentTtsEngine()
                if (engine is com.morealm.app.domain.tts.SystemTtsEngine) {
                    engine.awaitReady()
                }

                var consecutiveErrors = 0
                for (idx in startIdx until ttsParagraphs.size) {
                    if (!_ttsPlaying.value) break
                    _ttsParagraphIndex.value = idx
                    _ttsChapterPosition.value = chapterPositionForParagraph(idx)
                    TtsEventBus.updatePlayback {
                        copy(paragraphIndex = idx, totalParagraphs = ttsParagraphs.size)
                    }
                    _ttsScrollProgress.value = if (ttsParagraphs.size > 1) {
                        idx.toFloat() / (ttsParagraphs.size - 1)
                    } else 1f
                    try {
                        engine.speak(ttsParagraphs[idx], _ttsSpeed.value).collect { }
                        consecutiveErrors = 0
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        consecutiveErrors++
                        AppLog.warn("TTS", "TTS speak error on paragraph $idx", e)
                        if (consecutiveErrors >= 3) {
                            if (_ttsEngine.value != "system") {
                                AppLog.info("TTS", "Edge TTS failing, falling back to system TTS")
                                _ttsEngine.value = "system"
                                systemTtsEngine.awaitReady()
                                consecutiveErrors = 0
                                continue
                            }
                            AppLog.error("TTS", "TTS engine failing repeatedly, stopping")
                            _ttsPlaying.value = false
                            pushTtsState(false)
                            return@launch
                        }
                        delay(200)
                    }
                }
                // Chapter finished
                if (_ttsPlaying.value) {
                    onChapterFinished?.invoke()
                }
            } catch (_: CancellationException) {
            } catch (e: Exception) {
                AppLog.error("TTS", "TTS error", e)
                _ttsPlaying.value = false
                pushTtsState(false)
            }
        }
    }

    fun ttsPause() {
        _ttsPlaying.value = false
        _ttsScrollProgress.value = -1f
        _ttsChapterPosition.value = -1
        ttsJob?.cancel()
        currentTtsEngine().stop()
        pushTtsState(false)
    }

    fun ttsStop() {
        ttsPause()
        _ttsParagraphIndex.value = 0
        _ttsChapterPosition.value = -1
        TtsEventBus.updatePlayback { copy(isPlaying = false, paragraphIndex = 0) }
        if (ttsServiceStarted) {
            TtsEventBus.sendCommand(TtsEventBus.Command.StopService)
            ttsServiceStarted = false
        }
    }

    fun ttsPrevParagraph() {
        val newIdx = (_ttsParagraphIndex.value - 1).coerceAtLeast(0)
        _ttsParagraphIndex.value = newIdx
        _ttsChapterPosition.value = chapterPositionForParagraph(newIdx)
        if (_ttsPlaying.value) {
            currentTtsEngine().stop()
            ttsJob?.cancel()
            ttsPlay(null, null, null, onChapterFinished = null)
        }
    }

    fun ttsNextParagraph() {
        val newIdx = (_ttsParagraphIndex.value + 1).coerceAtMost(ttsParagraphs.size - 1)
        _ttsParagraphIndex.value = newIdx
        _ttsChapterPosition.value = chapterPositionForParagraph(newIdx)
        if (_ttsPlaying.value) {
            currentTtsEngine().stop()
            ttsJob?.cancel()
            ttsPlay(null, null, null, onChapterFinished = null)
        }
    }

    fun setTtsSpeed(speed: Float) {
        _ttsSpeed.value = speed
        scope.launch { prefs.setTtsSpeed(speed) }
        TtsEventBus.updatePlayback { copy(speed = speed) }
    }

    fun setTtsEngine(engine: String) {
        val wasPlaying = _ttsPlaying.value
        if (wasPlaying) ttsPause()

        _ttsEngine.value = engine
        scope.launch {
            prefs.setTtsEngine(engine)
            refreshVoicesForEngine(engine)
            if (wasPlaying) ttsPlay(null, null, null, onChapterFinished = null)
        }
    }

    fun setTtsVoice(voiceName: String) {
        val engineId = voiceEngineId(_ttsEngine.value)
        val resolvedVoice = validVoiceOrDefault(voiceName, _ttsVoices.value)
        _ttsVoiceName.value = resolvedVoice
        applyTtsVoice(engineId, resolvedVoice)
        scope.launch { saveVoiceForEngine(engineId, resolvedVoice) }
    }

    fun setTtsSleepTimer(minutes: Int) {
        _ttsSleepMinutes.value = minutes
        sleepTimerJob?.cancel()
        if (minutes > 0) {
            sleepTimerJob = scope.launch {
                delay(minutes * 60_000L)
                ttsStop()
                _ttsSleepMinutes.value = 0
                AppLog.info("TTS", "TTS sleep timer expired")
            }
        }
    }

    /** Speak text one-shot (doesn't affect TTS state) */
    fun speakSelectedText(text: String) {
        if (text.isBlank()) return
        scope.launch {
            val engine = currentTtsEngine()
            if (engine is com.morealm.app.domain.tts.SystemTtsEngine) engine.awaitReady()
            engine.speak(text, _ttsSpeed.value).collect { }
        }
    }

    fun readAloudFrom(
        displayedContent: String,
        bookTitle: String,
        chapterTitle: String,
        coverUrl: String? = null,
        startChapterPosition: Int,
        paragraphPositions: List<Int>? = null,
        onChapterFinished: (() -> Unit)?,
    ) {
        ttsPause()
        ttsPlay(displayedContent, bookTitle, chapterTitle, coverUrl, startChapterPosition, paragraphPositions, onChapterFinished)
    }

    private fun chapterPositionForParagraph(index: Int): Int {
        return ttsParagraphPositions.getOrNull(index) ?: 0
    }

    private fun paragraphIndexForChapterPosition(position: Int): Int {
        if (ttsParagraphs.isEmpty() || position <= 0) return 0
        for (index in ttsParagraphPositions.indices) {
            val start = ttsParagraphPositions[index]
            val next = ttsParagraphPositions.getOrNull(index + 1) ?: Int.MAX_VALUE
            if (position in start until next) return index
        }
        return ttsParagraphs.lastIndex.coerceAtLeast(0)
    }

    private fun buildSequentialParagraphPositions(paragraphs: List<String>): List<Int> {
        val result = arrayListOf<Int>()
        var position = 0
        for (paragraph in paragraphs) {
            result.add(position)
            position += paragraph.length + 1
        }
        return result
    }

    fun shutdown() {
        ttsJob?.cancel()
        sleepTimerJob?.cancel()
        systemTtsEngine.stop()
        systemTtsEngine.shutdown()
        edgeTtsEngine.stop()
        if (ttsServiceStarted) {
            TtsEventBus.sendCommand(TtsEventBus.Command.StopService)
            ttsServiceStarted = false
        }
    }
}
