package com.morealm.app.presentation.reader

import android.net.Uri
import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.morealm.app.domain.entity.Book
import com.morealm.app.domain.entity.Bookmark
import com.morealm.app.domain.entity.BookChapter
import com.morealm.app.domain.entity.ReadProgress
import com.morealm.app.domain.entity.ReadStats
import com.morealm.app.domain.preference.AppPreferences
import com.morealm.app.domain.repository.BookRepository
import com.morealm.app.domain.repository.BookmarkRepository
import com.morealm.app.domain.repository.ReadStatsRepository
import com.morealm.app.domain.repository.ReplaceRuleRepository
import com.morealm.app.domain.repository.SourceRepository
import com.morealm.app.domain.parser.LocalBookParser
import com.morealm.app.domain.webbook.CacheBook
import com.morealm.app.domain.webbook.ChapterResult
import com.morealm.app.domain.webbook.WebBook
import com.morealm.app.core.text.AppPattern
import com.morealm.app.core.text.stripHtml
import com.morealm.app.core.text.stripHtmlTags
import com.morealm.app.core.text.todayString
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

data class PreloadedReaderChapter(
    val index: Int,
    val title: String,
    val content: String,
)

data class RenderedReaderChapter(
    val index: Int = 0,
    val title: String = "",
    val content: String = "",
    val initialProgress: Int = 0,
)

data class VisibleReaderPage(
    val chapterIndex: Int = 0,
    val title: String = "",
    val readProgress: String = "0.0%",
)

@HiltViewModel
class ReaderViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val bookRepo: BookRepository,
    private val prefs: AppPreferences,
    private val readStatsRepo: ReadStatsRepository,
    private val bookmarkRepo: BookmarkRepository,
    private val replaceRuleRepo: ReplaceRuleRepository,
    private val styleRepo: com.morealm.app.domain.repository.ReaderStyleRepository,
    private val sourceRepo: SourceRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val bookId: String = savedStateHandle["bookId"] ?: ""

    // ── Delegates ──
    val tts = ReaderTtsController(context, prefs, viewModelScope)
    val settings = ReaderSettingsController(prefs, viewModelScope, context, styleRepo)

    // ── Forwarding (keep UI API stable) ──
    val ttsPlaying get() = tts.ttsPlaying
    val ttsSpeed get() = tts.ttsSpeed
    val ttsEngine get() = tts.ttsEngine
    val ttsParagraphIndex get() = tts.ttsParagraphIndex
    val ttsTotalParagraphs get() = tts.ttsTotalParagraphs
    val ttsSleepMinutes get() = tts.ttsSleepMinutes
    val ttsVoices get() = tts.ttsVoices
    val ttsVoiceName get() = tts.ttsVoiceName
    val ttsScrollProgress get() = tts.ttsScrollProgress

    val pageTurnMode get() = settings.pageTurnMode
    val fontFamily get() = settings.fontFamily
    val fontSize get() = settings.fontSize
    val lineHeight get() = settings.lineHeight
    val customFontUri get() = settings.customFontUri
    val customFontName get() = settings.customFontName
    val volumeKeyPage get() = settings.volumeKeyPage
    val screenTimeout get() = settings.screenTimeout
    val showChapterName get() = settings.showChapterName
    val showTimeBattery get() = settings.showTimeBattery
    val showStatusBar get() = settings.showStatusBar
    val tapLeftAction get() = settings.tapLeftAction
    val pageAnim get() = settings.pageAnim
    val screenOrientation get() = settings.screenOrientation
    val textSelectable get() = settings.textSelectable
    val chineseConvertMode get() = settings.chineseConvertMode
    val paragraphSpacing get() = settings.paragraphSpacing
    val marginHorizontal get() = settings.marginHorizontal
    val marginTop get() = settings.marginTop
    val marginBottom get() = settings.marginBottom
    val customCss get() = settings.customCss
    val customBgImage get() = settings.customBgImage
    val tapActionTopLeft get() = settings.tapActionTopLeft
    val tapActionTopRight get() = settings.tapActionTopRight
    val tapActionBottomLeft get() = settings.tapActionBottomLeft
    val tapActionBottomRight get() = settings.tapActionBottomRight
    val headerLeft get() = settings.headerLeft
    val headerCenter get() = settings.headerCenter
    val headerRight get() = settings.headerRight
    val footerLeft get() = settings.footerLeft
    val footerCenter get() = settings.footerCenter
    val footerRight get() = settings.footerRight
    val allStyles get() = settings.allStyles
    val activeStyleId get() = settings.activeStyleId
    val activeStyle get() = settings.activeStyle

    fun ttsPlayPause() = tts.ttsPlayPause(
        displayedContent = _chapterContent.value,
        bookTitle = _book.value?.title ?: "",
        chapterTitle = _chapters.value.getOrNull(_currentChapterIndex.value)?.title ?: "",
        onChapterFinished = { nextChapter() },
    )
    fun ttsStop() { tts.ttsStop(); _showTtsPanel.value = false }
    fun ttsPrevParagraph() = tts.ttsPrevParagraph()
    fun ttsNextParagraph() = tts.ttsNextParagraph()
    fun setTtsSpeed(speed: Float) = tts.setTtsSpeed(speed)
    fun setTtsEngine(engine: String) = tts.setTtsEngine(engine)
    fun setTtsVoice(voiceName: String) = tts.setTtsVoice(voiceName)
    fun setTtsSleepTimer(minutes: Int) = tts.setTtsSleepTimer(minutes)

    fun setPageTurnMode(mode: PageTurnMode) = settings.setPageTurnMode(mode)
    fun setPageAnim(anim: String) = settings.setPageAnim(anim)
    fun setFontFamily(family: String) = settings.setFontFamily(family)
    fun setFontSize(size: Float) = settings.setFontSize(size)
    fun setLineHeight(height: Float) = settings.setLineHeight(height)
    fun importCustomFont(uri: Uri, name: String) = settings.importCustomFont(uri, name)
    fun clearCustomFont() = settings.clearCustomFont()
    fun setScreenOrientation(value: Int) = settings.setScreenOrientation(value)
    fun setTextSelectable(enabled: Boolean) = settings.setTextSelectable(enabled)
    fun setChineseConvertMode(mode: Int) {
        if (mode == chineseConvertMode.value) return
        settings.setChineseConvertMode(mode)
        nextChapterCache = null
        prevChapterCache = null
        _nextPreloadedChapter.value = null
        _prevPreloadedChapter.value = null
        continuousContent = StringBuilder()
        _loadedChapterRange.value = IntRange.EMPTY
        loadChapter(_currentChapterIndex.value, restoreProgress = _scrollProgress.value)
    }
    fun setTapAction(zone: String, action: String) = settings.setTapAction(zone, action)
    fun setHeaderFooter(slot: String, value: String) = settings.setHeaderFooter(slot, value)
    fun switchStyle(styleId: String) = settings.switchStyle(styleId)
    fun saveCurrentStyle(style: com.morealm.app.domain.entity.ReaderStyle) = settings.saveCurrentStyle(style)
    fun deleteStyle(styleId: String) = settings.deleteStyle(styleId)
    fun setParagraphSpacing(value: Float) = settings.setParagraphSpacing(value)
    fun setMarginHorizontal(value: Int) = settings.setMarginHorizontal(value)
    fun setMarginTop(value: Int) = settings.setMarginTop(value)
    fun setMarginBottom(value: Int) = settings.setMarginBottom(value)
    fun setCustomCss(css: String) = settings.setCustomCss(css)
    fun setCustomBgImage(uri: String) = settings.setCustomBgImage(uri)
    fun setReaderBrightness(value: Float) { _readerBrightness.value = value }

    // ── Core State ──
    private val _book = MutableStateFlow<Book?>(null)
    val book: StateFlow<Book?> = _book.asStateFlow()

    private val _chapters = MutableStateFlow<List<BookChapter>>(emptyList())
    val chapters: StateFlow<List<BookChapter>> = _chapters.asStateFlow()

    private val _currentChapterIndex = MutableStateFlow(0)
    val currentChapterIndex: StateFlow<Int> = _currentChapterIndex.asStateFlow()

    private val _chapterContent = MutableStateFlow("")
    val chapterContent: StateFlow<String> = _chapterContent.asStateFlow()

    private val _renderedChapter = MutableStateFlow(RenderedReaderChapter())
    val renderedChapter: StateFlow<RenderedReaderChapter> = _renderedChapter.asStateFlow()

    private val _visiblePage = MutableStateFlow(VisibleReaderPage())
    val visiblePage: StateFlow<VisibleReaderPage> = _visiblePage.asStateFlow()

    private val _nextPreloadedChapter = MutableStateFlow<PreloadedReaderChapter?>(null)
    val nextPreloadedChapter: StateFlow<PreloadedReaderChapter?> = _nextPreloadedChapter.asStateFlow()

    private val _prevPreloadedChapter = MutableStateFlow<PreloadedReaderChapter?>(null)
    val prevPreloadedChapter: StateFlow<PreloadedReaderChapter?> = _prevPreloadedChapter.asStateFlow()

    private val _loadedChapterRange = MutableStateFlow(IntRange.EMPTY)
    private var continuousContent = StringBuilder()
    private var isAppendingChapter = false
    private var pendingAppendChapterIndex: Int? = null
    private var suppressNextProgressSave = false

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

    private val _navigateDirection = MutableStateFlow(0)
    val navigateDirection: StateFlow<Int> = _navigateDirection.asStateFlow()

    private val _linkedBooks = MutableStateFlow<List<Book>>(emptyList())
    val linkedBooks: StateFlow<List<Book>> = _linkedBooks.asStateFlow()

    private val _nextBookPrompt = MutableStateFlow<Book?>(null)
    val nextBookPrompt: StateFlow<Book?> = _nextBookPrompt.asStateFlow()

    fun dismissNextBookPrompt() { _nextBookPrompt.value = null }

    // ── Bookmarks ──
    val bookmarks: StateFlow<List<Bookmark>> = bookmarkRepo.getBookmarks(bookId)
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    // ── Brightness ──
    private val _readerBrightness = MutableStateFlow(-1f)
    val readerBrightness: StateFlow<Float> = _readerBrightness.asStateFlow()

    // ── Full text search ──
    data class SearchResult(val chapterIndex: Int, val chapterTitle: String, val snippet: String)
    private val _searchResults = MutableStateFlow<List<SearchResult>>(emptyList())
    val searchResults: StateFlow<List<SearchResult>> = _searchResults.asStateFlow()
    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()

    // ── Auto page turn ──
    private val _autoPageInterval = MutableStateFlow(0)
    val autoPageInterval: StateFlow<Int> = _autoPageInterval.asStateFlow()
    private var autoPageJob: kotlinx.coroutines.Job? = null

    // ── Text selection ──
    private val _selectedText = MutableStateFlow("")
    val selectedText: StateFlow<String> = _selectedText.asStateFlow()

    // ── Image viewer ──
    private val _viewingImageSrc = MutableStateFlow<String?>(null)
    val viewingImageSrc: StateFlow<String?> = _viewingImageSrc.asStateFlow()

    // ── Replace rules cache ──
    private var cachedReplaceRules: List<com.morealm.app.domain.entity.ReplaceRule> = emptyList()

    // ── Content editing ──
    private val _editingContent = MutableStateFlow(false)
    val editingContent: StateFlow<Boolean> = _editingContent.asStateFlow()

    private var nextChapterCache: String? = null
    private var prevChapterCache: String? = null
    private var chapterLoadJob: kotlinx.coroutines.Job? = null
    private var chapterLoadToken: Int = 0
    private var lastPreCacheCenter: Int = -1

    // Reading time tracking
    private var readingStartTime: Long = 0L
    private var accumulatedReadMs: Long = 0L
    private var lastStatsSaveTime: Long = 0L

    private var navigateToBookCallback: ((String) -> Unit)? = null

    fun setNavigateToBookCallback(callback: (String) -> Unit) {
        navigateToBookCallback = callback
    }

    init {
        viewModelScope.launch(Dispatchers.IO) { loadBook() }
        readingStartTime = System.currentTimeMillis()
        lastStatsSaveTime = readingStartTime
        settings.initialize()
        tts.initialize(
            getBookTitle = { _book.value?.title ?: "" },
            getChapterTitle = { _chapters.value.getOrNull(_currentChapterIndex.value)?.title ?: "" },
        )
        tts.collectChapterEvents(
            onPrev = { prevChapter() },
            onNext = { nextChapter() },
        )
        viewModelScope.launch(Dispatchers.IO) {
            cachedReplaceRules = replaceRuleRepo.getRulesForBook(bookId)
        }
    }

    override fun onCleared() {
        super.onCleared()
        tts.shutdown()
        val sessionMs = System.currentTimeMillis() - readingStartTime + accumulatedReadMs
        @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
        kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
            // Heavy cleanup off main thread to avoid ANR
            com.morealm.app.domain.parser.EpubParser.releaseCache()
            com.morealm.app.domain.parser.PdfParser.releaseCache()
            com.morealm.app.domain.parser.MobiParser.releaseCache()
            com.morealm.app.domain.parser.UmdParser.releaseCache()
            com.morealm.app.domain.parser.LocalBookParser.releaseTxtBuffer()
            saveProgress()
            if (sessionMs > 5000) saveReadingStats(sessionMs)
        }
    }

    // ── Reading Stats ──

    private suspend fun saveReadingStats(durationMs: Long) {
        try {
            val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
            val existing = readStatsRepo.getByDate(today)
            val stats = (existing ?: ReadStats(date = today)).copy(
                readDurationMs = (existing?.readDurationMs ?: 0L) + durationMs,
                pagesRead = (existing?.pagesRead ?: 0) + 1,
            )
            readStatsRepo.save(stats)
        } catch (_: Exception) {}
    }

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

    fun updateScrollProgress(pct: Int) {
        val old = _scrollProgress.value
        val next = pct.coerceIn(0, 100)
        _scrollProgress.value = next
        if (suppressNextProgressSave) {
            suppressNextProgressSave = false
            return
        }
        if (Math.abs(next - old) >= 5) {
            viewModelScope.launch(Dispatchers.IO) { saveProgress() }
        }
    }

    // ── Book Loading ──

    private suspend fun loadBook() {
        _loading.value = true
        try {
            val book = bookRepo.getById(bookId)
            if (book == null) {
                AppLog.error("Reader", "Book not found: $bookId")
                _loading.value = false
                return
            }
            _book.value = book
            AppLog.info("Reader", "Opened: ${book.title} (${book.format})")

            val isWebBook = book.format == com.morealm.app.domain.entity.BookFormat.WEB
                || (book.localPath == null && book.sourceUrl != null)

            // For web books, try to load cached chapters from DB first for instant display
            if (isWebBook) {
                val cachedChapters = withContext(Dispatchers.IO) {
                    bookRepo.getChaptersList(bookId)
                }
                if (cachedChapters.isNotEmpty()) {
                    _chapters.value = cachedChapters
                    AppLog.info("Reader", "Loaded ${cachedChapters.size} cached chapters from DB")

                    // Show chapters immediately, load first chapter
                    val progress = bookRepo.getProgress(bookId)
                    val startIndex = (progress?.chapterIndex ?: book.lastReadChapter)
                        .coerceIn(0, (cachedChapters.size - 1).coerceAtLeast(0))
                    lastPreCacheCenter = startIndex
                    val savedScrollProgress = progress?.scrollProgress ?: estimateChapterProgress(book, startIndex, cachedChapters.size)
                    _scrollProgress.value = savedScrollProgress
                    loadChapter(startIndex, restoreProgress = savedScrollProgress)

                    // Refresh chapters in background (non-blocking)
                    viewModelScope.launch(Dispatchers.IO) {
                        try {
                            val freshChapters = loadWebBookChapters(book)
                            if (freshChapters.isNotEmpty() && freshChapters.size != cachedChapters.size) {
                                _chapters.value = freshChapters
                                bookRepo.saveChapters(bookId, freshChapters)
                                if (book.totalChapters != freshChapters.size) {
                                    bookRepo.update(book.copy(totalChapters = freshChapters.size))
                                }
                                AppLog.info("Reader", "Refreshed chapters: ${freshChapters.size}")
                            }
                        } catch (e: Exception) {
                            AppLog.warn("Reader", "Background chapter refresh failed: ${e.message}")
                        }
                    }

                    if (book.folderId != null) {
                        val folderBooks = bookRepo.getBooksByFolderId(book.folderId!!)
                            .sortedBy { it.title }
                        _linkedBooks.value = folderBooks.filter { it.id != bookId }
                    }
                    return
                }
            }

            val chapters: List<BookChapter> = if (isWebBook) {
                loadWebBookChapters(book)
            } else {
                val localPath = book.localPath ?: run {
                    AppLog.warn("Reader", "No local path for book ${book.id}")
                    _loading.value = false
                    return
                }
                val uri = Uri.parse(localPath)
                val customTxtRegex = prefs.customTxtChapterRegex.first()
                val rawChapters = LocalBookParser.parseChapters(context, uri, book.format, customTxtRegex)
                val mapped = rawChapters.map { ch ->
                    if (ch.bookId != bookId) ch.copy(id = "${bookId}_${ch.index}", bookId = bookId) else ch
                }

                if (book.format == com.morealm.app.domain.entity.BookFormat.EPUB) {
                    viewModelScope.launch(Dispatchers.IO) {
                        try {
                            com.morealm.app.domain.parser.EpubParser.preCacheChapters(context, uri, mapped)
                            AppLog.info("Reader", "EPUB chapters pre-cached")
                        } catch (e: Exception) {
                            AppLog.warn("Reader", "EPUB pre-cache failed", e)
                        }
                    }
                }
                if (book.format == com.morealm.app.domain.entity.BookFormat.CBZ) {
                    viewModelScope.launch(Dispatchers.IO) {
                        try {
                            com.morealm.app.domain.parser.CbzParser.preCacheImages(context, uri, mapped)
                            AppLog.info("Reader", "CBZ images pre-cached")
                        } catch (e: Exception) {
                            AppLog.warn("Reader", "CBZ pre-cache failed", e)
                        }
                    }
                }
                mapped
            }

            if (chapters.isEmpty()) {
                AppLog.warn("Reader", "No chapters found for book ${book.id}")
                _loading.value = false
                return
            }

            _chapters.value = chapters
            bookRepo.saveChapters(bookId, chapters)
            AppLog.info("Reader", "Parsed ${chapters.size} chapters")

            if (book.totalChapters != chapters.size) {
                bookRepo.update(book.copy(totalChapters = chapters.size))
            }

            val progress = bookRepo.getProgress(bookId)
            val startIndex = (progress?.chapterIndex ?: book.lastReadChapter)
                .coerceIn(0, (chapters.size - 1).coerceAtLeast(0))
            lastPreCacheCenter = startIndex

            val savedScrollProgress = progress?.scrollProgress ?: estimateChapterProgress(book, startIndex, chapters.size)
            _scrollProgress.value = savedScrollProgress
            loadChapter(startIndex, restoreProgress = savedScrollProgress)

            if (book.folderId != null) {
                val folderBooks = bookRepo.getBooksByFolderId(book.folderId!!)
                    .sortedBy { it.title }
                _linkedBooks.value = folderBooks.filter { it.id != bookId }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.error("Reader", "Failed to load book", e)
        } finally {
            _loading.value = false
        }
    }

    fun loadChapter(index: Int, restoreProgress: Int = 0) {
        val chapterList = _chapters.value
        if (index < 0 || index >= chapterList.size) return

        val prevIndex = _currentChapterIndex.value
        chapterLoadJob?.cancel()
        val loadToken = ++chapterLoadToken
        _loading.value = true
        val targetProgress = restoreProgress.coerceIn(0, 100)
        tts.resetParagraphIndex()
        val chapter = chapterList[index]
        val book = _book.value ?: run {
            _loading.value = false
            return
        }
        val isWebBook = book.format == com.morealm.app.domain.entity.BookFormat.WEB
            || (book.localPath == null && book.sourceUrl != null)

        chapterLoadJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val content = when {
                    nextChapterCache != null && index == prevIndex + 1 -> {
                        val cached = nextChapterCache!!
                        nextChapterCache = null
                        _nextPreloadedChapter.value = null
                        cached
                    }
                    prevChapterCache != null && index == prevIndex - 1 -> {
                        val cached = prevChapterCache!!
                        prevChapterCache = null
                        _prevPreloadedChapter.value = null
                        cached
                    }
                    else -> {
                        nextChapterCache = null
                        prevChapterCache = null
                        _nextPreloadedChapter.value = null
                        _prevPreloadedChapter.value = null
                        val raw = if (isWebBook) {
                            loadWebChapterContent(book, chapter, index)
                        } else {
                            val localPath = book.localPath ?: ""
                            LocalBookParser.readChapter(context, Uri.parse(localPath), book.format, chapter)
                        }
                        val replaced = applyReplaceRules(raw)
                        com.morealm.app.core.text.ChineseConverter.convert(replaced, chineseConvertMode.value)
                    }
                }

                if (loadToken != chapterLoadToken) return@launch

                val isScrollMode = pageTurnMode.value == PageTurnMode.SCROLL
                if (isScrollMode) {
                    continuousContent = StringBuilder()
                    continuousContent.append(buildChapterBlock(chapter.title, content, index))
                    _loadedChapterRange.value = index..index
                    val renderedContent = continuousContent.toString()
                    _chapterContent.value = renderedContent
                    _renderedChapter.value = RenderedReaderChapter(
                        index = index,
                        title = chapter.title,
                        content = renderedContent,
                        initialProgress = targetProgress,
                    )
                    appendNextChapterForScroll(index + 1)
                } else {
                    _chapterContent.value = content
                    _renderedChapter.value = RenderedReaderChapter(
                        index = index,
                        title = chapter.title,
                        content = content,
                        initialProgress = targetProgress,
                    )
                }
                _currentChapterIndex.value = index
                _scrollProgress.value = targetProgress
                suppressNextProgressSave = targetProgress > 0

                AppLog.debug("Reader", "Loaded chapter $index: ${chapter.title}")
                _navigateDirection.value = 0
                if (targetProgress == 0) saveProgress()
                preloadNextChapter(index + 1)
                preloadPrevChapter(index - 1)
                maybeRetriggerPreCache(index)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (loadToken != chapterLoadToken) return@launch
                AppLog.error("Reader", "Failed to load chapter $index", e)
                _chapterContent.value = "加载失败: ${e.message}"
                _navigateDirection.value = 0
            } finally {
                if (loadToken == chapterLoadToken) {
                    _loading.value = false
                }
            }
        }
    }

    private fun buildChapterBlock(title: String, content: String, index: Int): String {
        val escapedTitle = title.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        val trimmed = content.trimStart()
        val isHtml = trimmed.startsWith("<") && (trimmed.contains("<p") || trimmed.contains("<div") || trimmed.contains("<img"))
        val bodyHtml = if (isHtml) {
            content
        } else {
            val lines = content.lines().filter { it.isNotBlank() }
            val bodyLines = if (lines.isNotEmpty() && lines[0].trim() == title.trim()) lines.drop(1) else lines
            bodyLines.joinToString("\n") { line ->
                val t = line.trim()
                    .replace(AppPattern.markdownHeadingRegex, "")
                    .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                if (t.isNotEmpty()) "<p>$t</p>" else ""
            }
        }
        val parts = escapedTitle.split(AppPattern.whitespaceRegex, limit = 2)
        val titleBlock = if (parts.size == 2) {
            "<div class=\"chapter-title-block\"><div class=\"chapter-num\">${parts[0]}</div>" +
            "<div class=\"chapter-sub\">${parts[1]}</div></div>"
        } else {
            "<div class=\"chapter-title-block\"><div class=\"chapter-num\">$escapedTitle</div></div>"
        }
        return "<div class=\"chapter-block\" data-index=\"$index\">$titleBlock\n$bodyHtml</div>\n"
    }

    private fun appendNextChapterForScroll(nextIndex: Int) {
        val chapterList = _chapters.value
        if (nextIndex >= chapterList.size) return
        if (isAppendingChapter) {
            pendingAppendChapterIndex = listOfNotNull(pendingAppendChapterIndex, nextIndex).minOrNull()
            return
        }
        val range = _loadedChapterRange.value
        if (nextIndex <= range.last) return

        isAppendingChapter = true
        val book = _book.value ?: run {
            isAppendingChapter = false
            return
        }
        val isWebBook = book.format == com.morealm.app.domain.entity.BookFormat.WEB
            || (book.localPath == null && book.sourceUrl != null)

        viewModelScope.launch {
            try {
                val chapter = chapterList[nextIndex]
                val raw = withContext(Dispatchers.IO) {
                    if (isWebBook) {
                        loadWebChapterContent(book, chapter, nextIndex)
                    } else {
                        val localPath = book.localPath ?: return@withContext ""
                        LocalBookParser.readChapter(context, Uri.parse(localPath), book.format, chapter)
                    }
                }
                val replaced = applyReplaceRules(raw)
                val content = com.morealm.app.core.text.ChineseConverter.convert(replaced, chineseConvertMode.value)
                continuousContent.append(buildChapterBlock(chapter.title, content, nextIndex))
                _loadedChapterRange.value = range.first..nextIndex
                val renderedContent = continuousContent.toString()
                _chapterContent.value = renderedContent
                _renderedChapter.value = _renderedChapter.value.copy(content = renderedContent)
                AppLog.debug("Reader", "Appended chapter $nextIndex for continuous scroll")
            } catch (e: Exception) {
                AppLog.error("Reader", "Failed to append chapter $nextIndex", e)
            } finally {
                isAppendingChapter = false
                pendingAppendChapterIndex?.let { pending ->
                    pendingAppendChapterIndex = null
                    appendNextChapterForScroll(pending)
                }
            }
        }
    }

    fun onScrollNearBottom() {
        val range = _loadedChapterRange.value
        if (range.isEmpty()) return
        val nextIdx = range.last + 1
        if (nextIdx < _chapters.value.size) {
            appendNextChapterForScroll(nextIdx)
        }
    }

    fun onScrollReachedBottom() {
        val range = _loadedChapterRange.value
        if (range.isEmpty() || range.last < _chapters.value.lastIndex) return
        val progress = _scrollProgress.value
        if (progress < 98) return

        val linked = _linkedBooks.value
        if (linked.isNotEmpty()) {
            _nextBookPrompt.value = linked.first()
        }
    }

    fun openNextLinkedBook() {
        _nextBookPrompt.value?.let { book ->
            val linked = _linkedBooks.value
            val callback = navigateToBookCallback
            if (linked.any { it.id == book.id } && callback != null) {
                _nextBookPrompt.value = null
                AppLog.info("Reader", "Opening linked book: ${book.title}")
                callback(book.id)
            }
        }
    }

    fun onVisiblePageChanged(index: Int, title: String, readProgress: String) {
        if (index !in _chapters.value.indices) return
        _visiblePage.value = VisibleReaderPage(index, title, readProgress)
        if (index != _currentChapterIndex.value) {
            _currentChapterIndex.value = index
            viewModelScope.launch(Dispatchers.IO) { saveProgress() }
        }
    }

    fun onVisibleChapterChanged(index: Int) {
        val chapter = _chapters.value.getOrNull(index) ?: return
        onVisiblePageChanged(index, chapter.title, _visiblePage.value.readProgress)
    }

    private suspend fun preloadNextChapter(nextIndex: Int) {
        val chapterList = _chapters.value
        if (nextIndex >= chapterList.size) return
        val book = _book.value ?: return
        try {
            withContext(Dispatchers.IO) {
                val raw = if (book.format == com.morealm.app.domain.entity.BookFormat.WEB
                    || (book.localPath == null && book.sourceUrl != null)) {
                    loadWebChapterContent(book, chapterList[nextIndex], nextIndex)
                } else {
                    val localPath = book.localPath ?: return@withContext
                    LocalBookParser.readChapter(context, Uri.parse(localPath), book.format, chapterList[nextIndex])
                }
                val replaced = applyReplaceRules(raw)
                val converted = com.morealm.app.core.text.ChineseConverter.convert(replaced, chineseConvertMode.value)
                nextChapterCache = converted
                _nextPreloadedChapter.value = PreloadedReaderChapter(nextIndex, chapterList[nextIndex].title, converted)
            }
        } catch (e: Exception) {
            AppLog.warn("Reader", "Preload next chapter $nextIndex failed", e)
        }
    }

    private suspend fun preloadPrevChapter(prevIndex: Int) {
        if (prevIndex < 0) return
        val chapterList = _chapters.value
        val book = _book.value ?: return
        try {
            withContext(Dispatchers.IO) {
                val raw = if (book.format == com.morealm.app.domain.entity.BookFormat.WEB
                    || (book.localPath == null && book.sourceUrl != null)) {
                    loadWebChapterContent(book, chapterList[prevIndex], prevIndex)
                } else {
                    val localPath = book.localPath ?: return@withContext
                    LocalBookParser.readChapter(context, Uri.parse(localPath), book.format, chapterList[prevIndex])
                }
                val replaced = applyReplaceRules(raw)
                val converted = com.morealm.app.core.text.ChineseConverter.convert(replaced, chineseConvertMode.value)
                prevChapterCache = converted
                _prevPreloadedChapter.value = PreloadedReaderChapter(prevIndex, chapterList[prevIndex].title, converted)
            }
        } catch (e: Exception) {
            AppLog.warn("Reader", "Preload prev chapter $prevIndex failed", e)
        }
    }

    /**
     * Re-trigger windowed pre-cache when user navigates far from the last pre-cache center.
     * For large EPUB/CBZ (1GB+), only ~20-30 items are cached at a time around the reading position.
     * For web books, triggers CacheBook.preload to pre-fetch surrounding chapters.
     */
    private fun maybeRetriggerPreCache(currentIndex: Int) {
        val book = _book.value ?: return
        val distance = kotlin.math.abs(currentIndex - lastPreCacheCenter)
        if (distance < 10) return // still within the cached window
        lastPreCacheCenter = currentIndex

        val isWebBook = book.format == com.morealm.app.domain.entity.BookFormat.WEB
            || (book.localPath == null && book.sourceUrl != null)
        if (isWebBook) {
            val sourceUrl = book.sourceUrl ?: return
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val source = sourceRepo.getByUrl(sourceUrl) ?: return@launch
                    val webChapters = _chapters.value.map { ch ->
                        ChapterResult(title = ch.title, url = ch.url)
                    }
                    CacheBook.preload(source, webChapters, currentIndex, preloadCount = 5)
                    AppLog.debug("Reader", "Web book pre-cache around chapter $currentIndex")
                } catch (e: Exception) {
                    AppLog.warn("Reader", "Web pre-cache failed", e)
                }
            }
            return
        }

        val localPath = book.localPath ?: return
        val format = book.format
        if (format != com.morealm.app.domain.entity.BookFormat.EPUB
            && format != com.morealm.app.domain.entity.BookFormat.CBZ) return

        val chapters = _chapters.value
        val uri = Uri.parse(localPath)

        viewModelScope.launch(Dispatchers.IO) {
            try {
                when (format) {
                    com.morealm.app.domain.entity.BookFormat.EPUB ->
                        com.morealm.app.domain.parser.EpubParser.preCacheChapters(context, uri, chapters, currentIndex)
                    com.morealm.app.domain.entity.BookFormat.CBZ ->
                        com.morealm.app.domain.parser.CbzParser.preCacheImages(context, uri, chapters, currentIndex)
                    else -> {}
                }
                AppLog.debug("Reader", "Re-triggered pre-cache around chapter $currentIndex")
            } catch (e: Exception) {
                AppLog.warn("Reader", "Pre-cache re-trigger failed", e)
            }
        }
    }

    // ── Navigation ──

    fun nextChapter() {
        val nextIdx = _currentChapterIndex.value + 1
        if (nextIdx < _chapters.value.size) {
            _navigateDirection.value = 1
            loadChapter(nextIdx, restoreProgress = 0)
        } else {
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
            loadChapter(prevIdx, restoreProgress = 0)
        }
    }

    fun toggleControls() { _showControls.value = !_showControls.value }
    fun hideControls() { _showControls.value = false }
    fun toggleTtsPanel() { _showTtsPanel.value = !_showTtsPanel.value }
    fun hideTtsPanel() { _showTtsPanel.value = false }
    fun toggleSettingsPanel() { _showSettingsPanel.value = !_showSettingsPanel.value }
    fun hideSettingsPanel() { _showSettingsPanel.value = false }

    // ── Content Editing ──

    fun startEditContent() { _editingContent.value = true }
    fun cancelEditContent() { _editingContent.value = false }

    fun saveEditedContent(newContent: String) {
        _chapterContent.value = newContent
        _editingContent.value = false
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

    // ── Bookmarks ──

    fun addBookmark() {
        val chapterIdx = _currentChapterIndex.value
        val chapter = _chapters.value.getOrNull(chapterIdx) ?: return
        val content = _chapterContent.value
        val snippet = content.stripHtml().take(80).trim()
        val bookmark = Bookmark(
            id = "${bookId}_bm_${System.currentTimeMillis()}",
            bookId = bookId,
            chapterIndex = chapterIdx,
            chapterTitle = chapter.title,
            content = snippet,
            scrollProgress = _scrollProgress.value,
        )
        viewModelScope.launch(Dispatchers.IO) {
            bookmarkRepo.insert(bookmark)
        }
    }

    fun deleteBookmark(id: String) {
        viewModelScope.launch(Dispatchers.IO) { bookmarkRepo.deleteById(id) }
    }

    fun jumpToBookmark(bookmark: Bookmark) {
        loadChapter(bookmark.chapterIndex)
    }

    // ── Full Text Search ──

    fun searchFullText(query: String) {
        if (query.isBlank()) { _searchResults.value = emptyList(); return }
        _searching.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val book = _book.value ?: return@launch
                val isWebBook = book.format == com.morealm.app.domain.entity.BookFormat.WEB
            || (book.localPath == null && book.sourceUrl != null)
                val chapterList = _chapters.value
                val results = mutableListOf<SearchResult>()
                val lowerQuery = query.lowercase()

                for (ch in chapterList) {
                    val content = if (isWebBook) {
                        loadWebChapterContent(book, ch, ch.index)
                    } else {
                        val localPath = book.localPath ?: break
                        LocalBookParser.readChapter(context, Uri.parse(localPath), book.format, ch)
                    }
                    val plainText = content.stripHtml()
                    val idx = plainText.lowercase().indexOf(lowerQuery)
                    if (idx >= 0) {
                        val start = (idx - 20).coerceAtLeast(0)
                        val end = (idx + query.length + 30).coerceAtMost(plainText.length)
                        val snippet = (if (start > 0) "..." else "") +
                            plainText.substring(start, end).trim() +
                            (if (end < plainText.length) "..." else "")
                        results.add(SearchResult(ch.index, ch.title, snippet))
                    }
                    if (results.size >= 50) break
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

    // ── Auto Page Turn ──

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

    // ── Text Selection ──

    fun onTextSelected(text: String) { _selectedText.value = text }

    fun copyTextToClipboard(text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("MoRealm", text))
    }

    fun clearSelectedText() { _selectedText.value = "" }

    fun speakSelectedText() {
        val text = _selectedText.value
        tts.speakSelectedText(text)
        _selectedText.value = ""
    }

    // ── Image Viewer ──

    fun onImageClick(src: String) { _viewingImageSrc.value = src }
    fun dismissImageViewer() { _viewingImageSrc.value = null }

    // ── Replace Rules ──

    private val regexCache = HashMap<String, Regex>(16)

    private fun getCachedRegex(pattern: String): Regex {
        return regexCache.getOrPut(pattern) { Regex(pattern) }
    }

    private suspend fun applyReplaceRules(content: String, isTitle: Boolean = false): String {
        if (cachedReplaceRules.isEmpty()) return content
        var result = content
        for (rule in cachedReplaceRules) {
            if (!rule.enabled || !rule.isValid()) continue
            if (isTitle && !rule.scopeTitle) continue
            if (!isTitle && !rule.scopeContent) continue
            try {
                result = if (rule.isRegex) {
                    try {
                        kotlinx.coroutines.withTimeout(rule.timeoutMs.toLong()) {
                            withContext(Dispatchers.Default) {
                                result.replace(getCachedRegex(rule.pattern), rule.replacement)
                            }
                        }
                    } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                        result
                    }
                } else {
                    result.replace(rule.pattern, rule.replacement)
                }
            } catch (_: Exception) {}
        }
        return result
    }

    // ── Export ──

    fun exportAsTxt(outputUri: Uri) {
        val book = _book.value ?: return
        val chapterList = _chapters.value
        if (chapterList.isEmpty()) return
        val isWebBook = book.format == com.morealm.app.domain.entity.BookFormat.WEB
            || (book.localPath == null && book.sourceUrl != null)

        viewModelScope.launch(Dispatchers.IO) {
            try {
                context.contentResolver.openOutputStream(outputUri)?.use { out ->
                    val writer = out.bufferedWriter(Charsets.UTF_8)
                    writer.appendLine(book.title)
                    if (book.author.isNotBlank()) writer.appendLine("作者：${book.author}")
                    writer.appendLine()

                    for (ch in chapterList) {
                        writer.appendLine(ch.title)
                        writer.appendLine()
                        val content = if (isWebBook) {
                            loadWebChapterContent(book, ch, ch.index)
                        } else {
                            val localPath = book.localPath ?: break
                            LocalBookParser.readChapter(context, Uri.parse(localPath), book.format, ch)
                        }
                        writer.appendLine(content.stripHtml().trim())
                        writer.appendLine()
                    }
                    writer.flush()
                }
                AppLog.info("Reader", "Exported ${chapterList.size} chapters to TXT")
            } catch (e: Exception) {
                AppLog.error("Reader", "Export failed", e)
            }
        }
    }

    // ── Progress ──

    fun saveProgressNow() {
        viewModelScope.launch(Dispatchers.IO) { saveProgress() }
    }

    // ── Web Book Support ──

    private suspend fun loadWebBookChapters(book: Book): List<BookChapter> {
        val sourceUrl = book.sourceUrl ?: return emptyList()
        val source = withContext(Dispatchers.IO) {
            sourceRepo.getByUrl(sourceUrl)
        } ?: return emptyList()
        if (book.bookUrl.isBlank()) return emptyList()

        // tocUrl may be empty from search results — fetch book info to get it
        var tocUrl = book.tocUrl?.takeIf { it.isNotBlank() } ?: book.bookUrl
        if (tocUrl == book.bookUrl && !book.hasDetail) {
            // Try to get detailed info (which often provides the real tocUrl)
            try {
                val searchBook = com.morealm.app.domain.entity.SearchBook(
                    bookUrl = book.bookUrl,
                    origin = sourceUrl,
                    originName = book.originName,
                    name = book.title,
                    author = book.author,
                    tocUrl = book.tocUrl ?: "",
                )
                val detailed = WebBook.getBookInfoAwait(source, searchBook)
                if (detailed.tocUrl.isNotBlank()) {
                    tocUrl = detailed.tocUrl
                }
                // Update book with fetched info
                val updated = book.copy(
                    tocUrl = detailed.tocUrl.ifBlank { null },
                    description = detailed.intro?.ifBlank { book.description } ?: book.description,
                    coverUrl = detailed.coverUrl ?: book.coverUrl,
                    hasDetail = true,
                )
                bookRepo.update(updated)
                _book.value = updated
                AppLog.info("Reader", "Fetched book info, tocUrl=${detailed.tocUrl}")
            } catch (e: Exception) {
                AppLog.warn("Reader", "Failed to fetch book info: ${e.message}")
            }
        }

        val webChapters = WebBook.getChapterListAwait(source, book.bookUrl, tocUrl)
        return webChapters.mapIndexed { i, ch ->
            BookChapter(
                id = "${bookId}_$i", bookId = bookId,
                index = i, title = ch.title, url = ch.url,
            )
        }
    }

    private suspend fun loadWebChapterContent(book: Book, chapter: BookChapter, index: Int): String {
        val sourceUrl = book.sourceUrl ?: return "（无书源）"
        // Try CacheBook first
        val cached = CacheBook.getContent(sourceUrl, chapter.url)
        if (cached != null) return cached

        val source = withContext(Dispatchers.IO) {
            sourceRepo.getByUrl(sourceUrl)
        } ?: return "（书源未找到）"
        val nextUrl = _chapters.value.getOrNull(index + 1)?.url
        val content = WebBook.getContentAwait(source, chapter.url, nextUrl)
        // Cache for offline
        if (content.isNotBlank()) {
            CacheBook.putContent(sourceUrl, chapter.url, content)
        }
        return content
    }

    private suspend fun saveProgress() {
        try {
            val book = _book.value ?: return
            val chapterCount = _chapters.value.size
            val chapterIdx = _currentChapterIndex.value
            val scrollPct = _scrollProgress.value / 100f
            val totalProgress = if (chapterCount > 0) {
                (chapterIdx.toFloat() + scrollPct) / chapterCount
            } else 0f
            val progress = ReadProgress(
                bookId = book.id,
                chapterIndex = chapterIdx,
                totalProgress = totalProgress.coerceIn(0f, 1f),
                scrollProgress = _scrollProgress.value,
            )
            bookRepo.saveProgress(progress)
            bookRepo.update(book.copy(
                lastReadChapter = chapterIdx,
                lastReadAt = System.currentTimeMillis(),
                readProgress = progress.totalProgress,
                totalChapters = chapterCount,
            ))
            flushReadingStats()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.error("Reader", "Failed to save progress", e)
        }
    }

    private fun estimateChapterProgress(book: Book, chapterIndex: Int, chapterCount: Int): Int {
        if (chapterCount <= 0 || book.readProgress <= 0f) return 0
        val chapterFloat = book.readProgress.coerceIn(0f, 1f) * chapterCount
        val inChapter = chapterFloat - chapterIndex
        return (inChapter * 100f).toInt().coerceIn(0, 100)
    }
}
