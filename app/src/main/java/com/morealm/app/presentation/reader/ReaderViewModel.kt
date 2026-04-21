package com.morealm.app.presentation.reader

import android.content.Intent
import android.net.Uri
import android.content.Context
import android.os.Build
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.morealm.app.domain.db.ReadStatsDao
import com.morealm.app.domain.entity.Book
import com.morealm.app.domain.entity.Bookmark
import com.morealm.app.domain.entity.BookChapter
import com.morealm.app.domain.entity.ReadProgress
import com.morealm.app.domain.entity.ReadStats
import com.morealm.app.domain.preference.AppPreferences
import com.morealm.app.domain.repository.BookRepository
import com.morealm.app.domain.db.BookmarkDao
import com.morealm.app.domain.db.ReplaceRuleDao
import com.morealm.app.domain.parser.LocalBookParser
import com.morealm.app.service.TtsEventBus
import com.morealm.app.service.TtsService
import com.morealm.app.core.log.AppLog
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * Page turn modes:
 * - "scroll"   : vertical scroll (WebView native, default)
 * - "tap_zone" : left 30% prev / right 30% next / center menu (classic 3-zone)
 * - "fullscreen": tap anywhere = next page, long press = menu, swipe right = back
 */
enum class PageTurnMode(val key: String, val label: String) {
    SCROLL("scroll", "上下滚动"),
    SWIPE_LR("swipe_lr", "左右滑动翻页"),
    TAP_ZONE("tap_zone", "点击翻页（三区域）"),
    FULLSCREEN("fullscreen", "全屏点击翻页"),
}

@HiltViewModel
class ReaderViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val bookRepo: BookRepository,
    private val prefs: AppPreferences,
    private val readStatsDao: ReadStatsDao,
    private val bookmarkDao: BookmarkDao,
    private val replaceRuleDao: ReplaceRuleDao,
    private val readerStyleDao: com.morealm.app.domain.db.ReaderStyleDao,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val bookId: String = savedStateHandle["bookId"] ?: ""

    private val _book = MutableStateFlow<Book?>(null)
    val book: StateFlow<Book?> = _book.asStateFlow()

    private val _chapters = MutableStateFlow<List<BookChapter>>(emptyList())
    val chapters: StateFlow<List<BookChapter>> = _chapters.asStateFlow()

    private val _currentChapterIndex = MutableStateFlow(0)
    val currentChapterIndex: StateFlow<Int> = _currentChapterIndex.asStateFlow()

    private val _chapterContent = MutableStateFlow("")
    val chapterContent: StateFlow<String> = _chapterContent.asStateFlow()

    /** Tracks which chapter indices are currently loaded in continuous scroll mode */
    private val _loadedChapterRange = MutableStateFlow(IntRange.EMPTY)
    private var continuousContent = StringBuilder()
    private var isAppendingChapter = false

    private val _showControls = MutableStateFlow(false)
    val showControls: StateFlow<Boolean> = _showControls.asStateFlow()

    private val _showTtsPanel = MutableStateFlow(false)
    val showTtsPanel: StateFlow<Boolean> = _showTtsPanel.asStateFlow()

    private val _showSettingsPanel = MutableStateFlow(false)
    val showSettingsPanel: StateFlow<Boolean> = _showSettingsPanel.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _scrollProgress = MutableStateFlow(0)
    val scrollProgress: StateFlow<Int> = _scrollProgress.asStateFlow()

    /** Tracks navigation direction: -1 = went to prev chapter, 0 = normal, 1 = next */
    private val _navigateDirection = MutableStateFlow(0)
    val navigateDirection: StateFlow<Int> = _navigateDirection.asStateFlow()

    /** Books in the same folder, for auto-linking chapter list */
    private val _linkedBooks = MutableStateFlow<List<Book>>(emptyList())
    val linkedBooks: StateFlow<List<Book>> = _linkedBooks.asStateFlow()

    /** When current book ends, prompt to read next book */
    private val _nextBookPrompt = MutableStateFlow<Book?>(null)
    val nextBookPrompt: StateFlow<Book?> = _nextBookPrompt.asStateFlow()

    fun dismissNextBookPrompt() { _nextBookPrompt.value = null }

    // ── TTS State ──
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

    private var ttsParagraphs: List<String> = emptyList()
    private var ttsJob: kotlinx.coroutines.Job? = null
    private var sleepTimerJob: kotlinx.coroutines.Job? = null

    // ── Bookmarks ──
    val bookmarks: StateFlow<List<Bookmark>> = bookmarkDao.getBookmarks(bookId)
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    // ── Brightness ──
    private val _readerBrightness = MutableStateFlow(-1f) // -1 = follow system
    val readerBrightness: StateFlow<Float> = _readerBrightness.asStateFlow()

    // ── Full text search ──
    data class SearchResult(val chapterIndex: Int, val chapterTitle: String, val snippet: String)
    private val _searchResults = MutableStateFlow<List<SearchResult>>(emptyList())
    val searchResults: StateFlow<List<SearchResult>> = _searchResults.asStateFlow()
    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()

    // ── Auto page turn ──
    private val _autoPageInterval = MutableStateFlow(0) // 0 = off, else seconds
    val autoPageInterval: StateFlow<Int> = _autoPageInterval.asStateFlow()
    private var autoPageJob: kotlinx.coroutines.Job? = null

    // ── Paragraph spacing ──
    val paragraphSpacing: StateFlow<Float> = prefs.paragraphSpacing
        .stateIn(viewModelScope, SharingStarted.Eagerly, 1.4f)

    val marginHorizontal: StateFlow<Int> = prefs.readerMargin
        .stateIn(viewModelScope, SharingStarted.Eagerly, 24)

    val marginTop: StateFlow<Int> = prefs.marginTop
        .stateIn(viewModelScope, SharingStarted.Eagerly, 24)

    val marginBottom: StateFlow<Int> = prefs.marginBottom
        .stateIn(viewModelScope, SharingStarted.Eagerly, 24)

    // ── Custom CSS ──
    val customCss: StateFlow<String> = prefs.customCss
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    // ── Custom background image ──
    val customBgImage: StateFlow<String> = prefs.customBgImage
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    // ── Text selection ──
    private val _selectedText = MutableStateFlow("")
    val selectedText: StateFlow<String> = _selectedText.asStateFlow()

    // ── Image viewer ──
    private val _viewingImageSrc = MutableStateFlow<String?>(null)
    val viewingImageSrc: StateFlow<String?> = _viewingImageSrc.asStateFlow()

    // ── Replace rules cache ──
    private var cachedReplaceRules: List<com.morealm.app.domain.entity.ReplaceRule> = emptyList()

    // ── TTS voice selection ──
    private val _ttsVoices = MutableStateFlow<List<com.morealm.app.domain.entity.TtsVoice>>(emptyList())
    val ttsVoices: StateFlow<List<com.morealm.app.domain.entity.TtsVoice>> = _ttsVoices.asStateFlow()

    private val _ttsVoiceName = MutableStateFlow("")
    val ttsVoiceName: StateFlow<String> = _ttsVoiceName.asStateFlow()

    /** TTS scroll progress: 0.0 to 1.0, used to auto-scroll WebView during TTS */
    private val _ttsScrollProgress = MutableStateFlow(-1f) // -1 = not active
    val ttsScrollProgress: StateFlow<Float> = _ttsScrollProgress.asStateFlow()

    private val systemTtsEngine by lazy {
        com.morealm.app.domain.tts.SystemTtsEngine(context).also { it.initialize() }
    }

    private val edgeTtsEngine by lazy {
        com.morealm.app.domain.tts.EdgeTtsEngine()
    }

    /** Get the currently selected TTS engine instance */
    private fun currentTtsEngine(): com.morealm.app.domain.tts.TtsEngine {
        return if (_ttsEngine.value == "edge") edgeTtsEngine else systemTtsEngine
    }

    val pageTurnMode: StateFlow<PageTurnMode> = prefs.pageTurnMode
        .map { key -> PageTurnMode.entries.find { it.key == key } ?: PageTurnMode.SCROLL }
        .stateIn(viewModelScope, SharingStarted.Eagerly, PageTurnMode.SCROLL)

    val fontFamily: StateFlow<String> = prefs.readerFontFamily
        .stateIn(viewModelScope, SharingStarted.Eagerly, "noto_serif_sc")

    val fontSize: StateFlow<Float> = prefs.readerFontSize
        .stateIn(viewModelScope, SharingStarted.Eagerly, 17f)

    val lineHeight: StateFlow<Float> = prefs.readerLineHeight
        .stateIn(viewModelScope, SharingStarted.Eagerly, 2.0f)

    val customFontUri: StateFlow<String> = prefs.customFontUri
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val customFontName: StateFlow<String> = prefs.customFontName
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val volumeKeyPage: StateFlow<Boolean> = prefs.volumeKeyPage
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val screenTimeout: StateFlow<Int> = prefs.screenTimeout
        .stateIn(viewModelScope, SharingStarted.Eagerly, -1)

    val showChapterName: StateFlow<Boolean> = prefs.showChapterName
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val showTimeBattery: StateFlow<Boolean> = prefs.showTimeBattery
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val showStatusBar: StateFlow<Boolean> = prefs.showStatusBar
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val tapLeftAction: StateFlow<String> = prefs.tapLeftAction
        .stateIn(viewModelScope, SharingStarted.Eagerly, "prev")

    val pageAnim: StateFlow<String> = prefs.pageAnim
        .stateIn(viewModelScope, SharingStarted.Eagerly, "none")

    val screenOrientation: StateFlow<Int> = prefs.screenOrientation
        .stateIn(viewModelScope, SharingStarted.Eagerly, -1)

    val textSelectable: StateFlow<Boolean> = prefs.textSelectable
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    fun setScreenOrientation(value: Int) {
        viewModelScope.launch { prefs.setScreenOrientation(value) }
    }

    fun setTextSelectable(enabled: Boolean) {
        viewModelScope.launch { prefs.setTextSelectable(enabled) }
    }

    val chineseConvertMode: StateFlow<Int> = prefs.chineseConvertMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    val readerEngine: StateFlow<String> = prefs.readerEngine
        .stateIn(viewModelScope, SharingStarted.Eagerly, "canvas")

    fun setReaderEngine(engine: String) {
        viewModelScope.launch { prefs.setReaderEngine(engine) }
    }

    fun setChineseConvertMode(mode: Int) {
        viewModelScope.launch { prefs.setChineseConvertMode(mode) }
    }

    // ── Tap zone customization ──
    val tapActionTopLeft: StateFlow<String> = prefs.tapActionTopLeft.stateIn(viewModelScope, SharingStarted.Eagerly, "prev")
    val tapActionTopRight: StateFlow<String> = prefs.tapActionTopRight.stateIn(viewModelScope, SharingStarted.Eagerly, "next")
    val tapActionBottomLeft: StateFlow<String> = prefs.tapActionBottomLeft.stateIn(viewModelScope, SharingStarted.Eagerly, "prev")
    val tapActionBottomRight: StateFlow<String> = prefs.tapActionBottomRight.stateIn(viewModelScope, SharingStarted.Eagerly, "next")

    fun setTapAction(zone: String, action: String) {
        val key = when (zone) {
            "topLeft" -> com.morealm.app.domain.preference.AppPreferences.Keys.TAP_ACTION_TOP_LEFT
            "topRight" -> com.morealm.app.domain.preference.AppPreferences.Keys.TAP_ACTION_TOP_RIGHT
            "bottomLeft" -> com.morealm.app.domain.preference.AppPreferences.Keys.TAP_ACTION_BOTTOM_LEFT
            "bottomRight" -> com.morealm.app.domain.preference.AppPreferences.Keys.TAP_ACTION_BOTTOM_RIGHT
            else -> return
        }
        viewModelScope.launch { prefs.setTapAction(key, action) }
    }

    // ── Header/footer customization ──
    val headerLeft: StateFlow<String> = prefs.headerLeft.stateIn(viewModelScope, SharingStarted.Eagerly, "time")
    val headerCenter: StateFlow<String> = prefs.headerCenter.stateIn(viewModelScope, SharingStarted.Eagerly, "none")
    val headerRight: StateFlow<String> = prefs.headerRight.stateIn(viewModelScope, SharingStarted.Eagerly, "battery")
    val footerLeft: StateFlow<String> = prefs.footerLeft.stateIn(viewModelScope, SharingStarted.Eagerly, "chapter")
    val footerCenter: StateFlow<String> = prefs.footerCenter.stateIn(viewModelScope, SharingStarted.Eagerly, "none")
    val footerRight: StateFlow<String> = prefs.footerRight.stateIn(viewModelScope, SharingStarted.Eagerly, "progress")

    fun setHeaderFooter(slot: String, value: String) {
        val key = when (slot) {
            "headerLeft" -> com.morealm.app.domain.preference.AppPreferences.Keys.HEADER_LEFT
            "headerCenter" -> com.morealm.app.domain.preference.AppPreferences.Keys.HEADER_CENTER
            "headerRight" -> com.morealm.app.domain.preference.AppPreferences.Keys.HEADER_RIGHT
            "footerLeft" -> com.morealm.app.domain.preference.AppPreferences.Keys.FOOTER_LEFT
            "footerCenter" -> com.morealm.app.domain.preference.AppPreferences.Keys.FOOTER_CENTER
            "footerRight" -> com.morealm.app.domain.preference.AppPreferences.Keys.FOOTER_RIGHT
            else -> return
        }
        viewModelScope.launch { prefs.setHeaderFooter(key, value) }
    }

    // ── Reader Style Presets ──
    val allStyles: StateFlow<List<com.morealm.app.domain.entity.ReaderStyle>> =
        readerStyleDao.getAll()
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _activeStyleId = MutableStateFlow("preset_paper")
    val activeStyleId: StateFlow<String> = _activeStyleId.asStateFlow()

    val activeStyle: StateFlow<com.morealm.app.domain.entity.ReaderStyle?> =
        kotlinx.coroutines.flow.combine(allStyles, _activeStyleId) { styles, id ->
            styles.find { it.id == id } ?: styles.firstOrNull()
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private var nextChapterCache: String? = null
    private var prevChapterCache: String? = null

    // Reading time tracking
    private var readingStartTime: Long = 0L
    private var accumulatedReadMs: Long = 0L
    private var lastStatsSaveTime: Long = 0L

    fun updateScrollProgress(pct: Int) {
        val old = _scrollProgress.value
        _scrollProgress.value = pct.coerceIn(0, 100)
        // Save progress when scroll changes significantly (every 10%)
        if (Math.abs(pct - old) >= 10) {
            viewModelScope.launch { saveProgress() }
        }
    }

    private var ttsServiceStarted = false
    private var ttsPausedByFocusLoss = false

    init {
        viewModelScope.launch { loadBook() }
        readingStartTime = System.currentTimeMillis()
        lastStatsSaveTime = readingStartTime
        // Seed default reader styles if empty
        viewModelScope.launch(Dispatchers.IO) {
            if (readerStyleDao.count() == 0) {
                readerStyleDao.upsertAll(com.morealm.app.domain.entity.ReaderStyle.defaults())
            }
        }
        // Load active reader style ID
        viewModelScope.launch {
            _activeStyleId.value = prefs.activeReaderStyle.first()
        }
        // Load persisted TTS speed
        viewModelScope.launch {
            prefs.ttsSpeed.first().let { _ttsSpeed.value = it }
        }
        // Load replace rules
        viewModelScope.launch(Dispatchers.IO) {
            cachedReplaceRules = replaceRuleDao.getRulesForBook(bookId)
        }
        // Load TTS skip pattern
        viewModelScope.launch {
            prefs.ttsSkipPattern.first().let { pattern ->
                ttsSkipRegex = if (pattern.isNotBlank()) {
                    try { Regex(pattern) } catch (_: Exception) { null }
                } else null
            }
        }
        // Load TTS voice preference and available voices
        viewModelScope.launch {
            val savedEngine = prefs.ttsEngine.first()
            _ttsEngine.value = savedEngine
            val voiceName = prefs.ttsVoice.first()
            _ttsVoiceName.value = voiceName
            if (savedEngine == "edge") {
                _ttsVoices.value = com.morealm.app.domain.tts.EdgeTtsEngine.VOICES
                if (voiceName.isNotBlank()) edgeTtsEngine.setVoice(voiceName)
            } else {
                // Wait for system TTS engine to be ready, then load voices
                systemTtsEngine.awaitReady()
                _ttsVoices.value = systemTtsEngine.getChineseVoices()
                if (voiceName.isNotBlank()) systemTtsEngine.setVoice(voiceName)
            }
        }
        // Listen for TTS events from service (notification actions, audio focus)
        viewModelScope.launch {
            TtsEventBus.events.collect { event ->
                when (event) {
                    is TtsEventBus.Event.PrevChapter -> prevChapter()
                    is TtsEventBus.Event.NextChapter -> nextChapter()
                    is TtsEventBus.Event.PlayPause -> ttsPlayPause()
                    is TtsEventBus.Event.AudioFocusLoss -> {
                        if (_ttsPlaying.value) {
                            ttsPausedByFocusLoss = true
                            ttsPause()
                        }
                    }
                    is TtsEventBus.Event.AudioFocusGain -> {
                        if (ttsPausedByFocusLoss) {
                            ttsPausedByFocusLoss = false
                            ttsPlay()
                        }
                    }
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        ttsJob?.cancel()
        sleepTimerJob?.cancel()
        systemTtsEngine.stop()
        systemTtsEngine.shutdown()
        edgeTtsEngine.stop()
        com.morealm.app.domain.parser.EpubParser.releaseCache()
        if (ttsServiceStarted) {
            TtsEventBus.sendCommand(TtsEventBus.Command.StopService)
            ttsServiceStarted = false
        }
        // Save progress and reading time — use GlobalScope since viewModelScope is cancelled
        val sessionMs = System.currentTimeMillis() - readingStartTime + accumulatedReadMs
        @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
        kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
            saveProgress()
            if (sessionMs > 5000) saveReadingStats(sessionMs)
        }
    }

    /** Save reading stats to DB. Called periodically and on exit. */
    private suspend fun saveReadingStats(durationMs: Long) {
        try {
            val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
            val existing = readStatsDao.getByDate(today)
            val stats = (existing ?: ReadStats(date = today)).copy(
                readDurationMs = (existing?.readDurationMs ?: 0L) + durationMs,
                pagesRead = (existing?.pagesRead ?: 0) + 1,
            )
            readStatsDao.save(stats)
        } catch (_: Exception) {}
    }

    /** Periodically flush reading time to DB so it's not lost on crash */
    private fun flushReadingStats() {
        val now = System.currentTimeMillis()
        val elapsed = now - readingStartTime
        if (elapsed > 30_000 && now - lastStatsSaveTime > 60_000) {
            lastStatsSaveTime = now
            val toSave = elapsed + accumulatedReadMs
            accumulatedReadMs += elapsed
            readingStartTime = now
            viewModelScope.launch(Dispatchers.IO) {
                saveReadingStats(toSave)
            }
        }
    }

    private suspend fun loadBook() {
        _loading.value = true
        try {
            val book = bookRepo.getById(bookId)
            if (book == null) {
                AppLog.error("Reader", "Book not found: $bookId")
                return
            }
            _book.value = book
            AppLog.info("Reader", "Opened: ${book.title} (${book.format})")

            val localPath = book.localPath ?: run {
                AppLog.warn("Reader", "No local path for book ${book.id}")
                return
            }

            val uri = Uri.parse(localPath)
            val customTxtRegex = prefs.customTxtChapterRegex.first()
            val rawChapters = LocalBookParser.parseChapters(context, uri, book.format, customTxtRegex)
            // Ensure all chapters have the correct bookId
            val chapters = rawChapters.map { ch ->
                if (ch.bookId != bookId) ch.copy(id = "${bookId}_${ch.index}", bookId = bookId) else ch
            }
            _chapters.value = chapters
            bookRepo.saveChapters(bookId, chapters)
            AppLog.info("Reader", "Parsed ${chapters.size} chapters")

            // Pre-cache EPUB chapters in background for faster subsequent reads
            if (book.format == com.morealm.app.domain.entity.BookFormat.EPUB) {
                viewModelScope.launch(Dispatchers.IO) {
                    try {
                        com.morealm.app.domain.parser.EpubParser.preCacheChapters(context, uri, chapters)
                        AppLog.info("Reader", "EPUB chapters pre-cached")
                    } catch (e: Exception) {
                        AppLog.warn("Reader", "EPUB pre-cache failed", e)
                    }
                }
            }
            // Pre-cache CBZ images in background
            if (book.format == com.morealm.app.domain.entity.BookFormat.CBZ) {
                viewModelScope.launch(Dispatchers.IO) {
                    try {
                        com.morealm.app.domain.parser.CbzParser.preCacheImages(context, uri, chapters)
                        AppLog.info("Reader", "CBZ images pre-cached")
                    } catch (e: Exception) {
                        AppLog.warn("Reader", "CBZ pre-cache failed", e)
                    }
                }
            }

            if (book.totalChapters != chapters.size) {
                bookRepo.update(book.copy(totalChapters = chapters.size))
            }

            val progress = bookRepo.getProgress(bookId)
            val startIndex = (progress?.chapterIndex ?: book.lastReadChapter)
                .coerceIn(0, (chapters.size - 1).coerceAtLeast(0))
            loadChapter(startIndex)

            // Load books in same folder for auto-linking chapter list
            if (book.folderId != null) {
                val folderBooks = bookRepo.getBooksByFolderId(book.folderId!!)
                    .sortedBy { it.title }
                _linkedBooks.value = folderBooks.filter { it.id != bookId }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.error("Reader", "Failed to load book", e)
            _loading.value = false
        }
    }

    fun loadChapter(index: Int) {
        val chapterList = _chapters.value
        if (index < 0 || index >= chapterList.size) return

        val prevIndex = _currentChapterIndex.value
        _currentChapterIndex.value = index
        _scrollProgress.value = 0
        // Reset TTS paragraph index when chapter changes to avoid stale position
        _ttsParagraphIndex.value = 0
        val chapter = chapterList[index]
        val book = _book.value ?: return
        val localPath = book.localPath ?: return

        viewModelScope.launch {
            try {
                _loading.value = true
                // Use cache only if navigating sequentially
                val content = when {
                    nextChapterCache != null && index == prevIndex + 1 -> {
                        val cached = nextChapterCache!!
                        nextChapterCache = null
                        cached
                    }
                    prevChapterCache != null && index == prevIndex - 1 -> {
                        val cached = prevChapterCache!!
                        prevChapterCache = null
                        cached
                    }
                    else -> {
                        nextChapterCache = null
                        prevChapterCache = null
                        val raw = LocalBookParser.readChapter(context, Uri.parse(localPath), book.format, chapter)
                        val replaced = applyReplaceRules(raw)
                        com.morealm.app.core.text.ChineseConverter.convert(replaced, chineseConvertMode.value)
                    }
                }

                // For scroll mode: build continuous content starting from this chapter
                val isScrollMode = pageTurnMode.value == PageTurnMode.SCROLL
                if (isScrollMode) {
                    continuousContent = StringBuilder()
                    continuousContent.append(buildChapterBlock(chapter.title, content, index))
                    _loadedChapterRange.value = index..index
                    _chapterContent.value = continuousContent.toString()
                    // Pre-append next chapter for seamless scrolling
                    appendNextChapterForScroll(index + 1)
                } else {
                    _chapterContent.value = content
                }

                AppLog.debug("Reader", "Loaded chapter $index: ${chapter.title}")
                _navigateDirection.value = 0
                saveProgress()
                preloadNextChapter(index + 1)
                preloadPrevChapter(index - 1)
            } catch (e: Exception) {
                AppLog.error("Reader", "Failed to load chapter $index", e)
                _chapterContent.value = "加载失败: ${e.message}"
                _navigateDirection.value = 0
            } finally {
                _loading.value = false
            }
        }
    }

    /** Build an HTML block for one chapter in continuous scroll mode */
    private fun buildChapterBlock(title: String, content: String, index: Int): String {
        val escapedTitle = title.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        // Check if content is already HTML
        val trimmed = content.trimStart()
        val isHtml = trimmed.startsWith("<") && (trimmed.contains("<p") || trimmed.contains("<div") || trimmed.contains("<img"))
        val bodyHtml = if (isHtml) {
            content
        } else {
            // Format plain text as paragraphs
            val lines = content.lines().filter { it.isNotBlank() }
            // Skip first line if it matches the title
            val bodyLines = if (lines.isNotEmpty() && lines[0].trim() == title.trim()) lines.drop(1) else lines
            bodyLines.joinToString("\n") { line ->
                val t = line.trim()
                    .replace(Regex("^#{1,6}\\s*"), "")  // Strip markdown headings
                    .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                if (t.isNotEmpty()) "<p>$t</p>" else ""
            }
        }
        // Title layout: chapter number on top (large), subtitle below (smaller)
        // e.g. "第一章 惊蛰" → "第一章" + "惊蛰"
        val parts = escapedTitle.split(Regex("\\s+"), limit = 2)
        val titleBlock = if (parts.size == 2) {
            "<div class=\"chapter-title-block\"><div class=\"chapter-num\">${parts[0]}</div>" +
            "<div class=\"chapter-sub\">${parts[1]}</div></div>"
        } else {
            "<div class=\"chapter-title-block\"><div class=\"chapter-num\">$escapedTitle</div></div>"
        }
        return "<div class=\"chapter-block\" data-index=\"$index\">$titleBlock\n$bodyHtml</div>\n"
    }

    /** Append next chapter content for continuous scroll mode */
    private fun appendNextChapterForScroll(nextIndex: Int) {
        val chapterList = _chapters.value
        if (nextIndex >= chapterList.size || isAppendingChapter) return
        val range = _loadedChapterRange.value
        if (nextIndex <= range.last) return

        isAppendingChapter = true
        val book = _book.value ?: return
        val localPath = book.localPath ?: return

        viewModelScope.launch {
            try {
                val chapter = chapterList[nextIndex]
                val content = withContext(Dispatchers.IO) {
                    LocalBookParser.readChapter(context, Uri.parse(localPath), book.format, chapter)
                }
                continuousContent.append(buildChapterBlock(chapter.title, content, nextIndex))
                _loadedChapterRange.value = range.first..nextIndex
                _chapterContent.value = continuousContent.toString()
                AppLog.debug("Reader", "Appended chapter $nextIndex for continuous scroll")
            } catch (e: Exception) {
                AppLog.error("Reader", "Failed to append chapter $nextIndex", e)
            } finally {
                isAppendingChapter = false
            }
        }
    }

    /** Called from JS when user scrolls near bottom in scroll mode */
    fun onScrollNearBottom() {
        val range = _loadedChapterRange.value
        if (range.isEmpty()) return
        val nextIdx = range.last + 1
        if (nextIdx < _chapters.value.size) {
            appendNextChapterForScroll(nextIdx)
        } else {
            // At last chapter — auto-navigate to next linked book for seamless multi-TXT
            val linked = _linkedBooks.value
            val callback = navigateToBookCallback
            if (linked.isNotEmpty() && callback != null) {
                AppLog.info("Reader", "Scroll auto-advancing to next linked book: ${linked.first().title}")
                callback(linked.first().id)
            }
        }
    }

    /** Called from JS with the visible chapter index in scroll mode */
    fun onVisibleChapterChanged(index: Int) {
        if (index != _currentChapterIndex.value && index >= 0 && index < _chapters.value.size) {
            _currentChapterIndex.value = index
            viewModelScope.launch { saveProgress() }
        }
    }

    private suspend fun preloadNextChapter(nextIndex: Int) {
        val chapterList = _chapters.value
        if (nextIndex >= chapterList.size) return
        val book = _book.value ?: return
        val localPath = book.localPath ?: return

        try {
            withContext(Dispatchers.IO) {
                nextChapterCache = LocalBookParser.readChapter(
                    context, Uri.parse(localPath), book.format, chapterList[nextIndex]
                )
            }
        } catch (e: Exception) {
            AppLog.warn("Reader", "Preload next chapter $nextIndex failed", e)
        }
    }

    private suspend fun preloadPrevChapter(prevIndex: Int) {
        if (prevIndex < 0) return
        val chapterList = _chapters.value
        val book = _book.value ?: return
        val localPath = book.localPath ?: return

        try {
            withContext(Dispatchers.IO) {
                prevChapterCache = LocalBookParser.readChapter(
                    context, Uri.parse(localPath), book.format, chapterList[prevIndex]
                )
            }
        } catch (e: Exception) {
            AppLog.warn("Reader", "Preload prev chapter $prevIndex failed", e)
        }
    }

    /** Callback for auto-navigating to next linked book (set by ReaderScreen) */
    private var navigateToBookCallback: ((String) -> Unit)? = null

    fun setNavigateToBookCallback(callback: (String) -> Unit) {
        navigateToBookCallback = callback
    }

    fun nextChapter() {
        val nextIdx = _currentChapterIndex.value + 1
        if (nextIdx < _chapters.value.size) {
            _navigateDirection.value = 1
            loadChapter(nextIdx)
        } else {
            // Last chapter — auto-advance to next linked book for seamless multi-TXT reading
            val linked = _linkedBooks.value
            if (linked.isNotEmpty()) {
                val nextBook = linked.first()
                val callback = navigateToBookCallback
                if (callback != null) {
                    AppLog.info("Reader", "Auto-advancing to next linked book: ${nextBook.title}")
                    callback(nextBook.id)
                } else {
                    _nextBookPrompt.value = nextBook
                }
            }
        }
    }

    fun prevChapter() {
        val prevIdx = _currentChapterIndex.value - 1
        if (prevIdx >= 0) {
            _navigateDirection.value = -1
            loadChapter(prevIdx)
        }
    }

    fun toggleControls() { _showControls.value = !_showControls.value }
    fun hideControls() { _showControls.value = false }
    fun toggleTtsPanel() { _showTtsPanel.value = !_showTtsPanel.value }
    fun hideTtsPanel() { _showTtsPanel.value = false }
    fun toggleSettingsPanel() { _showSettingsPanel.value = !_showSettingsPanel.value }
    fun hideSettingsPanel() { _showSettingsPanel.value = false }

    // ── Content editing ──
    private val _editingContent = MutableStateFlow(false)
    val editingContent: StateFlow<Boolean> = _editingContent.asStateFlow()

    fun startEditContent() { _editingContent.value = true }
    fun cancelEditContent() { _editingContent.value = false }

    fun saveEditedContent(newContent: String) {
        _chapterContent.value = newContent
        _editingContent.value = false
        // Persist to chapter cache so it survives reload
        val book = _book.value ?: return
        val chapter = _chapters.value.getOrNull(_currentChapterIndex.value) ?: return
        val localPath = book.localPath ?: return
        if (book.format == com.morealm.app.domain.entity.BookFormat.EPUB) {
            viewModelScope.launch(Dispatchers.IO) {
                val uri = android.net.Uri.parse(localPath)
                val cacheDir = java.io.File(context.cacheDir, "epub_chapters/${uri.hashCode()}")
                cacheDir.mkdirs()
                val href = chapter.url.substringBeforeLast("#")
                val cacheFile = java.io.File(cacheDir, href.replace('/', '_') + ".html")
                cacheFile.writeText(newContent)
                AppLog.info("Reader", "Saved edited content for chapter ${chapter.index}")
            }
        }
    }

    // ── Reader Style Preset Controls ──

    fun switchStyle(styleId: String) {
        _activeStyleId.value = styleId
        viewModelScope.launch { prefs.setActiveReaderStyle(styleId) }
    }

    fun saveCurrentStyle(style: com.morealm.app.domain.entity.ReaderStyle) {
        viewModelScope.launch(Dispatchers.IO) { readerStyleDao.upsert(style) }
    }

    fun deleteStyle(styleId: String) {
        if (styleId.startsWith("preset_")) return // don't delete builtins
        viewModelScope.launch(Dispatchers.IO) {
            readerStyleDao.deleteById(styleId)
            if (_activeStyleId.value == styleId) {
                _activeStyleId.value = "preset_paper"
                prefs.setActiveReaderStyle("preset_paper")
            }
        }
    }

    fun setPageTurnMode(mode: PageTurnMode) {
        viewModelScope.launch {
            prefs.setPageTurnMode(mode.key)
            AppLog.info("Reader", "Page turn mode: ${mode.label}")
        }
    }

    fun setFontFamily(family: String) {
        viewModelScope.launch {
            prefs.setReaderFontFamily(family)
            AppLog.info("Reader", "Font family: $family")
        }
    }

    fun setFontSize(size: Float) {
        viewModelScope.launch {
            prefs.setReaderFontSize(size)
            AppLog.info("Reader", "Font size: $size")
        }
    }

    fun setLineHeight(height: Float) {
        viewModelScope.launch {
            prefs.setReaderLineHeight(height)
            AppLog.info("Reader", "Line height: $height")
        }
    }

    fun importCustomFont(uri: android.net.Uri, name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Take persistable permission
                context.contentResolver.takePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                prefs.setCustomFont(uri.toString(), name)
                prefs.setReaderFontFamily("custom")
                AppLog.info("Reader", "Imported custom font: $name")
            } catch (e: Exception) {
                AppLog.error("Reader", "Failed to import font", e)
            }
        }
    }

    fun clearCustomFont() {
        viewModelScope.launch {
            prefs.clearCustomFont()
            prefs.setReaderFontFamily("noto_serif_sc")
        }
    }

    // ── TTS Controls ──

    // ── TTS skip pattern (cached) ──
    private var ttsSkipRegex: Regex? = null

    private fun parseParagraphs(content: String): List<String> {
        val text = content
            // Remove image elements entirely (including src paths that would be read aloud)
            .replace(Regex("<img[^>]*>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("<svg[\\s\\S]*?</svg>", RegexOption.IGNORE_CASE), "")
            // Convert block boundaries to newlines
            .replace(Regex("</p>|</div>|</li>|</h[1-6]>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<[^>]+>"), "")
            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&nbsp;", " ").replace("&quot;", "\"").replace("&#39;", "'")
        val paragraphs = text.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() && it.length > 1 }
        // Apply TTS skip pattern
        val skip = ttsSkipRegex
        return if (skip != null) {
            paragraphs.filter { !skip.containsMatchIn(it) }
        } else paragraphs
    }

    /** Start TTS foreground service for notification controls */
    private fun ensureTtsService() {
        if (ttsServiceStarted) return
        try {
            val intent = Intent(context, TtsService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            ttsServiceStarted = true
            // Push initial metadata
            val bookTitle = _book.value?.title ?: ""
            val chapterTitle = _chapters.value.getOrNull(_currentChapterIndex.value)?.title ?: ""
            TtsEventBus.sendCommand(TtsEventBus.Command.UpdateMeta(bookTitle, chapterTitle))
            AppLog.info("Reader", "TTS service started")
        } catch (e: Exception) {
            AppLog.error("Reader", "Failed to start TTS service", e)
        }
    }

    /** Push current state to the notification service */
    private fun pushTtsState(playing: Boolean) {
        val bookTitle = _book.value?.title ?: ""
        val chapterTitle = _chapters.value.getOrNull(_currentChapterIndex.value)?.title ?: ""
        TtsEventBus.sendCommand(TtsEventBus.Command.UpdateMeta(bookTitle, chapterTitle))
        TtsEventBus.sendCommand(TtsEventBus.Command.SetPlaying(playing))
    }

    fun ttsPlayPause() {
        if (_ttsPlaying.value) {
            ttsPause()
        } else {
            ttsPlay()
        }
    }

    private fun ttsPlay() {
        val book = _book.value ?: return
        val localPath = book.localPath ?: return
        val chapterList = _chapters.value
        val chapterIdx = _currentChapterIndex.value
        if (chapterIdx >= chapterList.size) return

        // Start service and request audio focus
        ensureTtsService()

        _ttsPlaying.value = true
        pushTtsState(true)

        ttsJob?.cancel()
        ttsJob = viewModelScope.launch {
            try {
                // Parse paragraphs from the DISPLAYED content (after replace rules + Chinese conversion)
                // so TTS reads exactly what the user sees on screen
                val displayedContent = _chapterContent.value
                ttsParagraphs = parseParagraphs(displayedContent)
                _ttsTotalParagraphs.value = ttsParagraphs.size
                if (ttsParagraphs.isEmpty()) {
                    _ttsPlaying.value = false
                    pushTtsState(false)
                    return@launch
                }

                val startIdx = _ttsParagraphIndex.value.coerceIn(0, ttsParagraphs.size - 1)
                val engine = currentTtsEngine()
                // Wait for system TTS engine to be ready before starting playback
                if (engine is com.morealm.app.domain.tts.SystemTtsEngine) {
                    engine.awaitReady()
                }

                var consecutiveErrors = 0
                for (idx in startIdx until ttsParagraphs.size) {
                    if (!_ttsPlaying.value) break
                    _ttsParagraphIndex.value = idx
                    _ttsScrollProgress.value = if (ttsParagraphs.size > 1) {
                        idx.toFloat() / (ttsParagraphs.size - 1)
                    } else 1f
                    try {
                        engine.speak(ttsParagraphs[idx], _ttsSpeed.value).collect { }
                        consecutiveErrors = 0
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e // Don't count cancellation as an error
                    } catch (e: Exception) {
                        consecutiveErrors++
                        AppLog.warn("Reader", "TTS speak error on paragraph $idx", e)
                        if (consecutiveErrors >= 3) {
                            // Auto-fallback to system TTS if current engine keeps failing
                            if (_ttsEngine.value != "system") {
                                AppLog.info("Reader", "Edge TTS failing, falling back to system TTS")
                                _ttsEngine.value = "system"
                                systemTtsEngine.awaitReady()
                                consecutiveErrors = 0
                                continue // Retry this paragraph with system TTS
                            }
                            AppLog.error("Reader", "TTS engine failing repeatedly, stopping")
                            _ttsPlaying.value = false
                            pushTtsState(false)
                            return@launch
                        }
                        kotlinx.coroutines.delay(200)
                    }
                }
                // Chapter finished — auto-advance
                if (_ttsPlaying.value) {
                    val nextIdx = chapterIdx + 1
                    if (nextIdx < chapterList.size) {
                        _navigateDirection.value = 1
                        loadChapter(nextIdx)
                        kotlinx.coroutines.delay(800)
                        _ttsParagraphIndex.value = 0
                        // Update notification metadata for new chapter
                        pushTtsState(true)
                        ttsPlay()
                    } else {
                        // Try auto-advance to next linked book
                        val linked = _linkedBooks.value
                        val callback = navigateToBookCallback
                        if (linked.isNotEmpty() && callback != null) {
                            AppLog.info("Reader", "TTS auto-advancing to next linked book: ${linked.first().title}")
                            _ttsPlaying.value = false
                            pushTtsState(false)
                            callback(linked.first().id)
                        } else {
                            _ttsPlaying.value = false
                            pushTtsState(false)
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Normal cancellation
            } catch (e: Exception) {
                AppLog.error("Reader", "TTS error", e)
                _ttsPlaying.value = false
                pushTtsState(false)
            }
        }
    }

    private fun ttsPause() {
        _ttsPlaying.value = false
        _ttsScrollProgress.value = -1f
        ttsJob?.cancel()
        currentTtsEngine().stop()
        pushTtsState(false)
    }

    fun ttsStop() {
        ttsPause()
        _ttsParagraphIndex.value = 0
        _showTtsPanel.value = false
        // Stop the service entirely
        if (ttsServiceStarted) {
            TtsEventBus.sendCommand(TtsEventBus.Command.StopService)
            ttsServiceStarted = false
        }
    }

    fun ttsPrevParagraph() {
        val newIdx = (_ttsParagraphIndex.value - 1).coerceAtLeast(0)
        _ttsParagraphIndex.value = newIdx
        if (_ttsPlaying.value) {
            currentTtsEngine().stop()
            ttsJob?.cancel()
            ttsPlay()
        }
    }

    fun ttsNextParagraph() {
        val newIdx = (_ttsParagraphIndex.value + 1).coerceAtMost(ttsParagraphs.size - 1)
        _ttsParagraphIndex.value = newIdx
        if (_ttsPlaying.value) {
            currentTtsEngine().stop()
            ttsJob?.cancel()
            ttsPlay()
        }
    }

    fun setTtsSpeed(speed: Float) {
        _ttsSpeed.value = speed
        // Persist speed preference
        viewModelScope.launch { prefs.setTtsSpeed(speed) }
    }

    fun setTtsEngine(engine: String) {
        // Stop current playback when switching engines
        val wasPlaying = _ttsPlaying.value
        if (wasPlaying) ttsPause()

        _ttsEngine.value = engine
        viewModelScope.launch {
            prefs.setTtsEngine(engine)
            // Update voice list for the new engine
            if (engine == "edge") {
                _ttsVoices.value = com.morealm.app.domain.tts.EdgeTtsEngine.VOICES
                // Apply saved voice or default to first Edge voice
                val savedVoice = _ttsVoiceName.value
                val edgeVoice = com.morealm.app.domain.tts.EdgeTtsEngine.VOICES.find { it.id == savedVoice }
                if (edgeVoice == null) {
                    val defaultVoice = com.morealm.app.domain.tts.EdgeTtsEngine.VOICES.first().id
                    _ttsVoiceName.value = defaultVoice
                    edgeTtsEngine.setVoice(defaultVoice)
                } else {
                    edgeTtsEngine.setVoice(savedVoice)
                }
            } else {
                systemTtsEngine.awaitReady()
                _ttsVoices.value = systemTtsEngine.getChineseVoices()
                val savedVoice = _ttsVoiceName.value
                systemTtsEngine.setVoice(savedVoice)
            }
            // Resume if was playing
            if (wasPlaying) ttsPlay()
        }
    }

    fun setTtsVoice(voiceName: String) {
        _ttsVoiceName.value = voiceName
        if (_ttsEngine.value == "edge") {
            edgeTtsEngine.setVoice(voiceName)
        } else {
            systemTtsEngine.setVoice(voiceName)
        }
        viewModelScope.launch { prefs.setTtsVoice(voiceName) }
    }

    fun setTtsSleepTimer(minutes: Int) {
        _ttsSleepMinutes.value = minutes
        sleepTimerJob?.cancel()
        if (minutes > 0) {
            sleepTimerJob = viewModelScope.launch {
                kotlinx.coroutines.delay(minutes * 60_000L)
                ttsStop()
                _ttsSleepMinutes.value = 0
                AppLog.info("Reader", "TTS sleep timer expired")
            }
        }
    }

    // ── Bookmarks ──

    fun addBookmark() {
        val chapterIdx = _currentChapterIndex.value
        val chapter = _chapters.value.getOrNull(chapterIdx) ?: return
        val content = _chapterContent.value
        val snippet = content.replace(Regex("<[^>]+>"), "")
            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .take(80).trim()
        val bookmark = Bookmark(
            id = "${bookId}_bm_${System.currentTimeMillis()}",
            bookId = bookId,
            chapterIndex = chapterIdx,
            chapterTitle = chapter.title,
            content = snippet,
            scrollProgress = _scrollProgress.value,
        )
        viewModelScope.launch {
            bookmarkDao.insert(bookmark)
            AppLog.info("Reader", "Bookmark added at chapter $chapterIdx")
        }
    }

    fun deleteBookmark(id: String) {
        viewModelScope.launch { bookmarkDao.deleteById(id) }
    }

    fun jumpToBookmark(bookmark: Bookmark) {
        loadChapter(bookmark.chapterIndex)
    }

    // ── Brightness ──

    fun setReaderBrightness(value: Float) {
        _readerBrightness.value = value
    }

    // ── Full text search ──

    fun searchFullText(query: String) {
        if (query.isBlank()) { _searchResults.value = emptyList(); return }
        _searching.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val book = _book.value ?: return@launch
                val localPath = book.localPath ?: return@launch
                val uri = Uri.parse(localPath)
                val chapterList = _chapters.value
                val results = mutableListOf<SearchResult>()
                val lowerQuery = query.lowercase()

                for (ch in chapterList) {
                    val content = LocalBookParser.readChapter(context, uri, book.format, ch)
                    val plainText = content.replace(Regex("<[^>]+>"), "")
                        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                        .replace("&nbsp;", " ")
                    val idx = plainText.lowercase().indexOf(lowerQuery)
                    if (idx >= 0) {
                        val start = (idx - 20).coerceAtLeast(0)
                        val end = (idx + query.length + 30).coerceAtMost(plainText.length)
                        val snippet = (if (start > 0) "..." else "") +
                            plainText.substring(start, end).trim() +
                            (if (end < plainText.length) "..." else "")
                        results.add(SearchResult(ch.index, ch.title, snippet))
                    }
                    if (results.size >= 50) break // Limit results
                }
                _searchResults.value = results
            } catch (e: Exception) {
                AppLog.error("Reader", "Full text search failed", e)
            } finally {
                _searching.value = false
            }
        }
    }

    fun clearSearchResults() { _searchResults.value = emptyList() }

    // ── Auto page turn ──

    fun setAutoPageInterval(seconds: Int) {
        _autoPageInterval.value = seconds
        autoPageJob?.cancel()
        if (seconds > 0) {
            autoPageJob = viewModelScope.launch {
                while (true) {
                    kotlinx.coroutines.delay(seconds * 1000L)
                    if (_autoPageInterval.value <= 0) break
                    nextChapter()
                }
            }
        }
    }

    fun stopAutoPage() {
        _autoPageInterval.value = 0
        autoPageJob?.cancel()
    }

    // ── Paragraph spacing / margins ──

    fun setParagraphSpacing(value: Float) {
        viewModelScope.launch { prefs.setParagraphSpacing(value) }
    }

    fun setMarginHorizontal(value: Int) {
        viewModelScope.launch { prefs.setReaderMargin(value) }
    }

    fun setMarginTop(value: Int) {
        viewModelScope.launch { prefs.setMarginTop(value) }
    }

    fun setMarginBottom(value: Int) {
        viewModelScope.launch { prefs.setMarginBottom(value) }
    }

    // ── Custom CSS ──

    fun setCustomCss(css: String) {
        viewModelScope.launch { prefs.setCustomCss(css) }
    }

    fun setCustomBgImage(uri: String) {
        viewModelScope.launch { prefs.setCustomBgImage(uri) }
    }

    // ── Text selection ──

    fun onTextSelected(text: String) {
        _selectedText.value = text
    }

    fun copyTextToClipboard(text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("MoRealm", text))
    }

    fun clearSelectedText() {
        _selectedText.value = ""
    }

    /** Speak selected text via TTS (one-shot, doesn't affect TTS state) */
    fun speakSelectedText() {
        val text = _selectedText.value
        if (text.isBlank()) return
        viewModelScope.launch {
            val engine = currentTtsEngine()
            if (engine is com.morealm.app.domain.tts.SystemTtsEngine) engine.awaitReady()
            engine.speak(text, _ttsSpeed.value).collect { }
        }
        _selectedText.value = ""
    }

    // ── Image viewer ──

    fun onImageClick(src: String) {
        _viewingImageSrc.value = src
    }

    fun dismissImageViewer() {
        _viewingImageSrc.value = null
    }

    // ── Replace rules ──

    /** Apply replace rules to content */
    private fun applyReplaceRules(content: String, isTitle: Boolean = false): String {
        if (cachedReplaceRules.isEmpty()) return content
        var result = content
        for (rule in cachedReplaceRules) {
            if (!rule.enabled || !rule.isValid()) continue
            if (isTitle && !rule.scopeTitle) continue
            if (!isTitle && !rule.scopeContent) continue
            try {
                result = if (rule.isRegex) {
                    // Timeout protection: run regex in a thread with deadline
                    val future = java.util.concurrent.ForkJoinPool.commonPool().submit<String> {
                        result.replace(Regex(rule.pattern), rule.replacement)
                    }
                    try {
                        future.get(rule.timeoutMs.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
                    } catch (_: java.util.concurrent.TimeoutException) {
                        AppLog.warn("Reader", "Regex timeout on rule '${rule.name}', skipping")
                        future.cancel(true)
                        result // Return unchanged
                    }
                } else {
                    result.replace(rule.pattern, rule.replacement)
                }
            } catch (e: Exception) {
                AppLog.warn("Reader", "Replace rule '${rule.name}' failed: ${e.message}")
            }
        }
        return result
    }

    // ── Export book ──

    /** Export all chapters as a single TXT file */
    fun exportAsTxt(outputUri: Uri) {
        val book = _book.value ?: return
        val localPath = book.localPath ?: return
        val chapterList = _chapters.value
        if (chapterList.isEmpty()) return

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val uri = Uri.parse(localPath)
                val sb = StringBuilder()
                sb.appendLine(book.title)
                if (book.author.isNotBlank()) sb.appendLine("作者：${book.author}")
                sb.appendLine()

                for (ch in chapterList) {
                    sb.appendLine(ch.title)
                    sb.appendLine()
                    val content = LocalBookParser.readChapter(context, uri, book.format, ch)
                    // Strip HTML tags
                    val plain = content
                        .replace(Regex("<br\\s*/?>"), "\n")
                        .replace(Regex("<p[^>]*>"), "\n")
                        .replace(Regex("</p>"), "")
                        .replace(Regex("<[^>]+>"), "")
                        .replace("&amp;", "&").replace("&lt;", "<")
                        .replace("&gt;", ">").replace("&nbsp;", " ")
                        .replace("&quot;", "\"")
                    sb.appendLine(plain.trim())
                    sb.appendLine()
                }

                context.contentResolver.openOutputStream(outputUri)?.use { out ->
                    out.write(sb.toString().toByteArray(Charsets.UTF_8))
                }
                AppLog.info("Reader", "Exported ${chapterList.size} chapters to TXT")
            } catch (e: Exception) {
                AppLog.error("Reader", "Export failed", e)
            }
        }
    }

    /** Called from ReaderScreen's ON_PAUSE lifecycle — saves immediately */
    fun saveProgressNow() {
        viewModelScope.launch(Dispatchers.IO) { saveProgress() }
    }

    private suspend fun saveProgress() {
        try {
            val book = _book.value ?: return
            val chapterCount = _chapters.value.size
            val chapterIdx = _currentChapterIndex.value
            val scrollPct = _scrollProgress.value / 100f
            // Linear progress: chapter position + scroll within chapter
            val totalProgress = if (chapterCount > 0) {
                (chapterIdx.toFloat() + scrollPct) / chapterCount
            } else 0f
            val progress = ReadProgress(
                bookId = book.id,
                chapterIndex = chapterIdx,
                totalProgress = totalProgress.coerceIn(0f, 1f),
            )
            bookRepo.saveProgress(progress)
            bookRepo.update(book.copy(
                lastReadChapter = chapterIdx,
                lastReadAt = System.currentTimeMillis(),
                readProgress = progress.totalProgress,
                totalChapters = chapterCount,
            ))
            flushReadingStats()
        } catch (e: Exception) {
            AppLog.error("Reader", "Failed to save progress", e)
        }
    }
}
