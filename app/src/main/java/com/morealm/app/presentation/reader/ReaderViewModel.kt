package com.morealm.app.presentation.reader

import android.net.Uri
import android.content.Context
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

    // ── Delegates ──
    val tts = ReaderTtsController(context, prefs, viewModelScope)
    val settings = ReaderSettingsController(prefs, viewModelScope, context, readerStyleDao)

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
    val readerEngine get() = settings.readerEngine
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

    fun ttsPlayPause() = tts.ttsPlayPause()
    fun ttsStop() { tts.ttsStop(); _showTtsPanel.value = false }
    fun ttsPrevParagraph() = tts.ttsPrevParagraph()
    fun ttsNextParagraph() = tts.ttsNextParagraph()
    fun setTtsSpeed(speed: Float) = tts.setTtsSpeed(speed)
    fun setTtsEngine(engine: String) = tts.setTtsEngine(engine)
    fun setTtsVoice(voiceName: String) = tts.setTtsVoice(voiceName)
    fun setTtsSleepTimer(minutes: Int) = tts.setTtsSleepTimer(minutes)

    fun setPageTurnMode(mode: PageTurnMode) = settings.setPageTurnMode(mode)
    fun setFontFamily(family: String) = settings.setFontFamily(family)
    fun setFontSize(size: Float) = settings.setFontSize(size)
    fun setLineHeight(height: Float) = settings.setLineHeight(height)
    fun importCustomFont(uri: Uri, name: String) = settings.importCustomFont(uri, name)
    fun clearCustomFont() = settings.clearCustomFont()
    fun setScreenOrientation(value: Int) = settings.setScreenOrientation(value)
    fun setTextSelectable(enabled: Boolean) = settings.setTextSelectable(enabled)
    fun setReaderEngine(engine: String) = settings.setReaderEngine(engine)
    fun setChineseConvertMode(mode: Int) = settings.setChineseConvertMode(mode)
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

    private val _navigateDirection = MutableStateFlow(0)
    val navigateDirection: StateFlow<Int> = _navigateDirection.asStateFlow()

    private val _linkedBooks = MutableStateFlow<List<Book>>(emptyList())
    val linkedBooks: StateFlow<List<Book>> = _linkedBooks.asStateFlow()

    private val _nextBookPrompt = MutableStateFlow<Book?>(null)
    val nextBookPrompt: StateFlow<Book?> = _nextBookPrompt.asStateFlow()

    fun dismissNextBookPrompt() { _nextBookPrompt.value = null }

    // ── Bookmarks ──
    val bookmarks: StateFlow<List<Bookmark>> = bookmarkDao.getBookmarks(bookId)
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

    // Reading time tracking
    private var readingStartTime: Long = 0L
    private var accumulatedReadMs: Long = 0L
    private var lastStatsSaveTime: Long = 0L

    private var navigateToBookCallback: ((String) -> Unit)? = null

    fun setNavigateToBookCallback(callback: (String) -> Unit) {
        navigateToBookCallback = callback
    }

    init {
        viewModelScope.launch { loadBook() }
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
            cachedReplaceRules = replaceRuleDao.getRulesForBook(bookId)
        }
    }

    override fun onCleared() {
        super.onCleared()
        tts.shutdown()
        com.morealm.app.domain.parser.EpubParser.releaseCache()
        val sessionMs = System.currentTimeMillis() - readingStartTime + accumulatedReadMs
        @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
        kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
            saveProgress()
            if (sessionMs > 5000) saveReadingStats(sessionMs)
        }
    }

    // ── Reading Stats ──

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
        _scrollProgress.value = pct.coerceIn(0, 100)
        if (Math.abs(pct - old) >= 10) {
            viewModelScope.launch { saveProgress() }
        }
    }

    // ── Book Loading ──

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
            val chapters = rawChapters.map { ch ->
                if (ch.bookId != bookId) ch.copy(id = "${bookId}_${ch.index}", bookId = bookId) else ch
            }
            _chapters.value = chapters
            bookRepo.saveChapters(bookId, chapters)
            AppLog.info("Reader", "Parsed ${chapters.size} chapters")

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
        tts.resetParagraphIndex()
        val chapter = chapterList[index]
        val book = _book.value ?: return
        val localPath = book.localPath ?: return

        viewModelScope.launch {
            try {
                _loading.value = true
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

                val isScrollMode = pageTurnMode.value == PageTurnMode.SCROLL
                if (isScrollMode) {
                    continuousContent = StringBuilder()
                    continuousContent.append(buildChapterBlock(chapter.title, content, index))
                    _loadedChapterRange.value = index..index
                    _chapterContent.value = continuousContent.toString()
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
                    .replace(Regex("^#{1,6}\\s*"), "")
                    .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                if (t.isNotEmpty()) "<p>$t</p>" else ""
            }
        }
        val parts = escapedTitle.split(Regex("\\s+"), limit = 2)
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

    fun onScrollNearBottom() {
        val range = _loadedChapterRange.value
        if (range.isEmpty()) return
        val nextIdx = range.last + 1
        if (nextIdx < _chapters.value.size) {
            appendNextChapterForScroll(nextIdx)
        } else {
            val linked = _linkedBooks.value
            val callback = navigateToBookCallback
            if (linked.isNotEmpty() && callback != null) {
                AppLog.info("Reader", "Scroll auto-advancing to next linked book: ${linked.first().title}")
                callback(linked.first().id)
            }
        }
    }

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

    // ── Navigation ──

    fun nextChapter() {
        val nextIdx = _currentChapterIndex.value + 1
        if (nextIdx < _chapters.value.size) {
            _navigateDirection.value = 1
            loadChapter(nextIdx)
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
            loadChapter(prevIdx)
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

    // ── Full Text Search ──

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

    private fun applyReplaceRules(content: String, isTitle: Boolean = false): String {
        if (cachedReplaceRules.isEmpty()) return content
        var result = content
        for (rule in cachedReplaceRules) {
            if (!rule.enabled || !rule.isValid()) continue
            if (isTitle && !rule.scopeTitle) continue
            if (!isTitle && !rule.scopeContent) continue
            try {
                result = if (rule.isRegex) {
                    val future = java.util.concurrent.ForkJoinPool.commonPool().submit<String> {
                        result.replace(Regex(rule.pattern), rule.replacement)
                    }
                    try {
                        future.get(rule.timeoutMs.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
                    } catch (_: java.util.concurrent.TimeoutException) {
                        AppLog.warn("Reader", "Regex timeout on rule '${rule.name}', skipping")
                        future.cancel(true)
                        result
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

    // ── Export ──

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

    // ── Progress ──

    fun saveProgressNow() {
        viewModelScope.launch(Dispatchers.IO) { saveProgress() }
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
