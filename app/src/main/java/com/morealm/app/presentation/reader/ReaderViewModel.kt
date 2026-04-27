package com.morealm.app.presentation.reader

import android.net.Uri
import android.content.Context
import android.widget.Toast
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
import org.apache.commons.text.StringEscapeUtils
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

private const val TEXT_BOOK_SOURCE_TYPE = 0
private const val NON_TEXT_WEB_CONTENT_MESSAGE = "（该书源返回的是音频、图片、视频或临时媒体链接，不是可阅读的文本内容）"
private const val READER_ERROR_CHAPTER_URL_PREFIX = "morealm:error:"

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
    val initialChapterPosition: Int = 0,
)

data class VisibleReaderPage(
    val chapterIndex: Int = 0,
    val title: String = "",
    val readProgress: String = "0.0%",
    val chapterPosition: Int = 0,
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

    // UI accesses settings/tts state directly: viewModel.settings.fontSize, viewModel.tts.ttsPlaying

    private val _readAloudPageTurn = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    val readAloudPageTurn = _readAloudPageTurn.asSharedFlow()

    fun ttsPlayPause() = tts.ttsPlayPause(
        displayedContent = _chapterContent.value,
        bookTitle = _book.value?.title ?: "",
        chapterTitle = _chapters.value.getOrNull(_currentChapterIndex.value)?.title ?: "",
        startChapterPosition = visibleReadAloudChapterPosition
            .takeIf { tts.ttsPlaying.value.not() && it >= 0 },
        paragraphPositions = readAloudParagraphPositions,
        onChapterFinished = { _readAloudPageTurn.tryEmit(1) },
    )
    // Methods with extra ViewModel logic — not pure forwards
    fun ttsStop() { tts.ttsStop(); _showTtsPanel.value = false }
    fun setChineseConvertMode(mode: Int) {
        if (mode == settings.chineseConvertMode.value) return
        settings.setChineseConvertMode(mode)
        nextChapterCache = null
        prevChapterCache = null
        _nextPreloadedChapter.value = null
        _prevPreloadedChapter.value = null
        loadChapter(
            _currentChapterIndex.value,
            restoreProgress = _scrollProgress.value,
            restoreChapterPosition = _visiblePage.value.chapterPosition,
        )
    }
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

    private var suppressNextProgressSave = false
    private var lastQueuedProgressChapterIndex = -1
    private var lastQueuedScrollProgress = -1
    private var lastQueuedVisibleReadProgress = ""
    private var lastQueuedChapterPosition = -1

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
    data class SearchResult(
        val chapterIndex: Int,
        val chapterTitle: String,
        val snippet: String,
        val query: String = "",
        val queryIndexInChapter: Int = -1,
        val queryLength: Int = 0,
    )

    data class SearchSelection(
        val chapterIndex: Int,
        val queryIndexInChapter: Int,
        val queryLength: Int,
    )

    private val _searchResults = MutableStateFlow<List<SearchResult>>(emptyList())
    val searchResults: StateFlow<List<SearchResult>> = _searchResults.asStateFlow()
    private val _pendingSearchSelection = MutableStateFlow<SearchSelection?>(null)
    val pendingSearchSelection: StateFlow<SearchSelection?> = _pendingSearchSelection.asStateFlow()
    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()

    // ── Auto page turn ──
    private val _autoPageInterval = MutableStateFlow(0)
    val autoPageInterval: StateFlow<Int> = _autoPageInterval.asStateFlow()

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

    @Volatile private var nextChapterCache: String? = null
    @Volatile private var prevChapterCache: String? = null
    private var chapterLoadJob: kotlinx.coroutines.Job? = null
    private var chapterLoadToken: Int = 0
    private var lastPreCacheCenter: Int = -1
    private var readAloudParagraphPositions: List<Int>? = null
    private var visibleReadAloudChapterPosition: Int = -1

    // Reading time tracking
    private var readingStartTime: Long = 0L
    // Removed accumulatedReadMs — each flush saves only the incremental elapsed time
    private var lastStatsSaveTime: Long = 0L

    private var navigateToBookCallback: ((String) -> Unit)? = null

    fun setNavigateToBookCallback(callback: (String) -> Unit) {
        navigateToBookCallback = callback
    }

    init {
        viewModelScope.launch(Dispatchers.IO) {
            cachedReplaceRules = replaceRuleRepo.getRulesForBook(bookId)
            loadBook()
        }
        readingStartTime = System.currentTimeMillis()
        lastStatsSaveTime = readingStartTime
        settings.initialize()
        tts.initialize(
            getBookTitle = { _book.value?.title ?: "" },
            getChapterTitle = { _chapters.value.getOrNull(_currentChapterIndex.value)?.title ?: "" },
        )
        tts.collectChapterEvents(
            onPrev = { _readAloudPageTurn.tryEmit(-1) },
            onNext = { _readAloudPageTurn.tryEmit(1) },
        )
    }

    override fun onCleared() {
        super.onCleared()
        tts.shutdown()
        val sessionMs = System.currentTimeMillis() - readingStartTime
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
            readingStartTime = now
            viewModelScope.launch(Dispatchers.IO) {
                saveReadingStats(elapsed)
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
        if (next != old) {
            queueProgressSave()
        }
    }

    private fun queueProgressSave(force: Boolean = false) {
        val chapterIndex = _currentChapterIndex.value
        val scrollProgress = _scrollProgress.value
        val visibleReadProgress = _visiblePage.value.readProgress
        val chapterPosition = _visiblePage.value.chapterPosition
        if (!force &&
            chapterIndex == lastQueuedProgressChapterIndex &&
            scrollProgress == lastQueuedScrollProgress &&
            visibleReadProgress == lastQueuedVisibleReadProgress &&
            chapterPosition == lastQueuedChapterPosition
        ) {
            return
        }
        lastQueuedProgressChapterIndex = chapterIndex
        lastQueuedScrollProgress = scrollProgress
        lastQueuedVisibleReadProgress = visibleReadProgress
        lastQueuedChapterPosition = chapterPosition
        viewModelScope.launch(Dispatchers.IO) { saveProgress() }
    }

    // ── Book Loading ──

    private fun isWebBook(book: Book): Boolean {
        return book.format == com.morealm.app.domain.entity.BookFormat.WEB ||
            (book.localPath == null && book.sourceUrl != null)
    }

    private fun readerErrorContent(title: String, detail: String): String {
        val readableDetail = wrapLongErrorText(
            applyLoadedReplaceRulesSync(StringEscapeUtils.unescapeHtml4(detail.ifBlank { "当前书源没有返回可阅读内容。" })),
        )
        return buildString {
            append(title)
            append("\n\n")
            append(readableDetail)
            append("\n\n")
            append("可以返回搜索页换一个书源，或稍后重试。")
        }
    }

    private fun Throwable.readerErrorMessage(fallback: String): String {
        return localizedMessage
            ?.takeIf { it.isNotBlank() }
            ?.take(240)
            ?: fallback
    }

    private fun webReaderErrorDetail(book: Book, reason: String): String {
        val sourceName = StringEscapeUtils.unescapeHtml4(book.originName.ifBlank { book.sourceUrl ?: "未知书源" })
        val title = StringEscapeUtils.unescapeHtml4(book.title)
        return "书名：$title\n来源：$sourceName\n原因：$reason"
    }

    private fun wrapLongErrorText(text: String, segmentLength: Int = 48): String {
        return text.lineSequence().joinToString("\n") { line ->
            line.split(' ').joinToString(" ") { token ->
                if (token.length <= segmentLength) token else token.chunked(segmentLength).joinToString("\n")
            }
        }
    }

    private fun applyLoadedReplaceRulesSync(content: String, isTitle: Boolean = false): String {
        if (cachedReplaceRules.isEmpty()) return content
        var result = content
        for (rule in cachedReplaceRules) {
            if (!rule.enabled || !rule.isValid()) continue
            if (isTitle && !rule.scopeTitle) continue
            if (!isTitle && !rule.scopeContent) continue
            try {
                result = if (rule.isRegex) {
                    result.replace(getCachedRegex(rule.pattern), rule.replacement)
                } else {
                    result.replace(rule.pattern, rule.replacement)
                }
            } catch (_: Exception) {
            }
        }
        return result
    }

    private fun publishReaderError(title: String, detail: String) {
        val content = readerErrorContent(title, detail)
        val errorChapter = BookChapter(
            id = "${bookId}_reader_error",
            bookId = bookId,
            index = 0,
            title = title,
            url = READER_ERROR_CHAPTER_URL_PREFIX,
            variable = content,
        )
        chapterLoadJob?.cancel()
        chapterLoadToken++
        nextChapterCache = null
        prevChapterCache = null
        _nextPreloadedChapter.value = null
        _prevPreloadedChapter.value = null
        _chapters.value = listOf(errorChapter)
        _currentChapterIndex.value = 0
        _chapterContent.value = content
        _renderedChapter.value = RenderedReaderChapter(
            index = 0,
            title = title,
            content = content,
            initialProgress = 0,
        )
        _visiblePage.value = VisibleReaderPage(0, title, "0.0%", 0)
        _scrollProgress.value = 0
        _navigateDirection.value = 0
        _loading.value = false
    }

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

            val isWebBook = isWebBook(book)

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
                    val savedChapterPosition = progress?.chapterPosition ?: book.lastReadPosition
                    _scrollProgress.value = savedScrollProgress
                    loadChapter(startIndex, restoreProgress = savedScrollProgress, restoreChapterPosition = savedChapterPosition)

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
                try {
                    loadWebBookChapters(book)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLog.error("Reader", "Failed to load web chapters", e)
                    publishReaderError(
                        title = "书源加载失败",
                        detail = webReaderErrorDetail(
                            book,
                            e.readerErrorMessage("目录解析失败"),
                        ),
                    )
                    return
                }
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
                if (isWebBook) {
                    publishReaderError(
                        title = "书源无章节",
                        detail = webReaderErrorDetail(book, "该书源没有解析到章节目录"),
                    )
                    return
                }
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
            val savedChapterPosition = progress?.chapterPosition ?: book.lastReadPosition
            _scrollProgress.value = savedScrollProgress
            loadChapter(startIndex, restoreProgress = savedScrollProgress, restoreChapterPosition = savedChapterPosition)

            if (book.folderId != null) {
                val folderBooks = bookRepo.getBooksByFolderId(book.folderId!!)
                    .sortedBy { it.title }
                _linkedBooks.value = folderBooks.filter { it.id != bookId }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.error("Reader", "Failed to load book", e)
            _book.value?.takeIf { isWebBook(it) }?.let { book ->
                publishReaderError(
                    title = "书源加载失败",
                    detail = webReaderErrorDetail(
                        book,
                        e.readerErrorMessage("书籍加载失败"),
                    ),
                )
            }
        } finally {
            _loading.value = false
        }
    }

    fun loadChapter(index: Int, restoreProgress: Int = 0, restoreChapterPosition: Int = 0) {
        val chapterList = _chapters.value
        if (index < 0 || index >= chapterList.size) return

        val prevIndex = _currentChapterIndex.value
        chapterLoadJob?.cancel()
        val loadToken = ++chapterLoadToken
        _loading.value = true
        val targetProgress = restoreProgress.coerceIn(0, 100)
        val targetChapterPosition = restoreChapterPosition.coerceAtLeast(0)
        tts.resetParagraphIndex()
        val chapter = chapterList[index]
        val book = _book.value ?: run {
            _loading.value = false
            return
        }
        val isWebBook = isWebBook(book)

        chapterLoadJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                // Capture cache to local val for thread safety (cache is @Volatile)
                val nextCached = nextChapterCache
                val prevCached = prevChapterCache
                val content = when {
                    nextCached != null && index == prevIndex + 1 -> {
                        nextChapterCache = null
                        _nextPreloadedChapter.value = null
                        nextCached
                    }
                    prevCached != null && index == prevIndex - 1 -> {
                        prevChapterCache = null
                        _prevPreloadedChapter.value = null
                        prevCached
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
                        com.morealm.app.core.text.ChineseConverter.convert(replaced, settings.chineseConvertMode.value)
                    }
                }

                if (loadToken != chapterLoadToken) return@launch

                _chapterContent.value = content
                _renderedChapter.value = RenderedReaderChapter(
                    index = index,
                    title = chapter.title,
                    content = content,
                    initialProgress = targetProgress,
                    initialChapterPosition = targetChapterPosition,
                )
                _currentChapterIndex.value = index
                _scrollProgress.value = targetProgress
                _visiblePage.value = _visiblePage.value.copy(
                    chapterIndex = index,
                    title = chapter.title,
                    chapterPosition = targetChapterPosition,
                )
                suppressNextProgressSave = targetProgress > 0 || targetChapterPosition > 0

                AppLog.debug("Reader", buildString {
                    append("loadChapter completed")
                    append(" | chapter=$index/${chapterList.size}")
                    append(" | title=${chapter.title.take(20)}")
                    append(" | mode=${settings.pageTurnMode.value}")
                    append(" | progress=$targetProgress")
                    append(" | position=$targetChapterPosition")
                    append(" | source=${if (isWebBook) "web" else "local"}")
                    append(" | nextCache=${nextChapterCache != null}")
                    append(" | prevCache=${prevChapterCache != null}")
                })
                _navigateDirection.value = 0
                if (targetProgress == 0 && targetChapterPosition == 0) saveProgress()
                preloadNextChapter(index + 1)
                preloadPrevChapter(index - 1)
                maybeRetriggerPreCache(index)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (loadToken != chapterLoadToken) return@launch
                AppLog.error("Reader", "Failed to load chapter $index", e)
                val title = if (isWebBook) "正文加载失败" else "加载失败"
                val detail = if (isWebBook) {
                    webReaderErrorDetail(
                        book,
                        e.readerErrorMessage("正文解析失败"),
                    )
                } else {
                    e.readerErrorMessage("章节读取失败")
                }
                val errorContent = readerErrorContent(title, detail)
                _chapterContent.value = errorContent
                _renderedChapter.value = RenderedReaderChapter(
                    index = index,
                    title = chapter.title.ifBlank { title },
                    content = errorContent,
                    initialProgress = 0,
                    initialChapterPosition = 0,
                )
                _currentChapterIndex.value = index
                _visiblePage.value = VisibleReaderPage(index, chapter.title.ifBlank { title }, "0.0%", 0)
                _scrollProgress.value = 0
                _navigateDirection.value = 0
            } finally {
                if (loadToken == chapterLoadToken) {
                    _loading.value = false
                }
            }
        }
    }

    fun onScrollNearBottom() {
        val nextIdx = _currentChapterIndex.value + 1
        if (nextIdx < _chapters.value.size && _nextPreloadedChapter.value?.index != nextIdx) {
            viewModelScope.launch(Dispatchers.IO) {
                preloadNextChapter(nextIdx)
            }
        }
    }

    fun onScrollReachedBottom() {
        if (_currentChapterIndex.value < _chapters.value.lastIndex) {
            AppLog.debug(
                "Reader",
                "Scroll reached temporary chapter bottom at ${_currentChapterIndex.value}; " +
                    "chapter boundary must be committed by ReaderPageFactory",
            )
            onScrollNearBottom()
            return
        }
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

    fun onVisiblePageChanged(index: Int, title: String, readProgress: String, chapterPosition: Int = 0) {
        if (index !in _chapters.value.indices) return
        val oldVisiblePage = _visiblePage.value
        val visibleChanged = oldVisiblePage.chapterIndex != index ||
            oldVisiblePage.title != title ||
            oldVisiblePage.readProgress != readProgress ||
            oldVisiblePage.chapterPosition != chapterPosition
        _visiblePage.value = VisibleReaderPage(index, title, readProgress, chapterPosition)
        val chapterChanged = index != _currentChapterIndex.value
        val scrollBoundaryPreview = settings.pageTurnMode.value == PageTurnMode.SCROLL && chapterChanged
        if (!scrollBoundaryPreview && !chapterChanged && visibleChanged) {
            queueProgressSave()
        }
    }

    fun onVisibleChapterChanged(index: Int) {
        val chapter = _chapters.value.getOrNull(index) ?: return
        onVisiblePageChanged(index, chapter.title, _visiblePage.value.readProgress, _visiblePage.value.chapterPosition)
    }

    private suspend fun preloadNextChapter(nextIndex: Int) {
        val chapterList = _chapters.value
        if (nextIndex >= chapterList.size) return
        val book = _book.value ?: return
        try {
            withContext(Dispatchers.IO) {
                val raw = if (isWebBook(book)) {
                    loadWebChapterContent(book, chapterList[nextIndex], nextIndex)
                } else {
                    val localPath = book.localPath ?: return@withContext
                    LocalBookParser.readChapter(context, Uri.parse(localPath), book.format, chapterList[nextIndex])
                }
                val replaced = applyReplaceRules(raw)
                val converted = com.morealm.app.core.text.ChineseConverter.convert(replaced, settings.chineseConvertMode.value)
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
                val raw = if (isWebBook(book)) {
                    loadWebChapterContent(book, chapterList[prevIndex], prevIndex)
                } else {
                    val localPath = book.localPath ?: return@withContext
                    LocalBookParser.readChapter(context, Uri.parse(localPath), book.format, chapterList[prevIndex])
                }
                val replaced = applyReplaceRules(raw)
                val converted = com.morealm.app.core.text.ChineseConverter.convert(replaced, settings.chineseConvertMode.value)
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

        val isWebBook = isWebBook(book)
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
        AppLog.debug("Reader", "nextChapter | from=${_currentChapterIndex.value} | to=$nextIdx | total=${_chapters.value.size}")
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
        AppLog.debug("Reader", "prevChapter | from=${_currentChapterIndex.value} | to=$prevIdx")
        if (prevIdx >= 0) {
            _navigateDirection.value = -1
            loadChapter(prevIdx, restoreProgress = 100)
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
                val isWebBook = isWebBook(book)
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
                        results.add(
                            SearchResult(
                                chapterIndex = ch.index,
                                chapterTitle = ch.title,
                                snippet = snippet,
                                query = query,
                                queryIndexInChapter = idx,
                                queryLength = query.length,
                            ),
                        )
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

    fun openSearchResult(result: SearchResult) {
        _pendingSearchSelection.value = SearchSelection(
            chapterIndex = result.chapterIndex,
            queryIndexInChapter = result.queryIndexInChapter,
            queryLength = result.queryLength,
        )
        loadChapter(result.chapterIndex, restoreChapterPosition = result.queryIndexInChapter)
    }

    fun consumeSearchSelection() {
        _pendingSearchSelection.value = null
    }

    // ── Auto Page Turn ──

    fun setAutoPageInterval(seconds: Int) {
        _autoPageInterval.value = seconds
    }

    fun stopAutoPage() {
        _autoPageInterval.value = 0
    }

    // ── Text Selection ──

    fun onTextSelected(text: String) { _selectedText.value = text }

    fun copyTextToClipboard(text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("MoRealm", text))
        Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
    }

    fun clearSelectedText() { _selectedText.value = "" }

    fun speakSelectedText() {
        val text = _selectedText.value
        tts.speakSelectedText(text)
        _selectedText.value = ""
    }

    fun readAloudFromPosition(chapterPosition: Int) {
        tts.readAloudFrom(
            displayedContent = _chapterContent.value,
            bookTitle = _book.value?.title ?: "",
            chapterTitle = _chapters.value.getOrNull(_currentChapterIndex.value)?.title ?: "",
            startChapterPosition = chapterPosition.coerceAtLeast(0),
            paragraphPositions = readAloudParagraphPositions,
            onChapterFinished = { _readAloudPageTurn.tryEmit(1) },
        )
        _showTtsPanel.value = true
    }

    fun updateReadAloudParagraphPositions(positions: List<Int>) {
        readAloudParagraphPositions = positions.takeIf { it.isNotEmpty() }
    }

    fun updateVisibleReadAloudPosition(chapterIndex: Int, chapterPosition: Int) {
        if (chapterIndex == _currentChapterIndex.value) {
            visibleReadAloudChapterPosition = chapterPosition.coerceAtLeast(0)
        }
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
        val isWebBook = isWebBook(book)

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

    suspend fun saveProgressNowAndWait() {
        withContext(Dispatchers.IO) { saveProgress() }
    }

    // ── Web Book Support ──

    private suspend fun loadWebBookChapters(book: Book): List<BookChapter> {
        val sourceUrl = book.sourceUrl ?: return emptyList()
        val source = withContext(Dispatchers.IO) {
            sourceRepo.getByUrl(sourceUrl)
        } ?: return emptyList()
        if (source.bookSourceType != TEXT_BOOK_SOURCE_TYPE) {
            AppLog.warn("Reader", "Blocked non-text source chapters: ${source.bookSourceName} type=${source.bookSourceType}")
            return listOf(
                BookChapter(
                    id = "${book.id}_0",
                    bookId = book.id,
                    index = 0,
                    title = "非文本书源",
                    url = book.bookUrl,
                )
            )
        }
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
        if (chapter.url.startsWith(READER_ERROR_CHAPTER_URL_PREFIX)) {
            return chapter.variable ?: readerErrorContent(chapter.title, "当前书源没有返回可阅读内容。")
        }
        val sourceUrl = book.sourceUrl ?: return "（无书源）"
        val source = withContext(Dispatchers.IO) {
            sourceRepo.getByUrl(sourceUrl)
        } ?: run {
            val cached = CacheBook.getContent(sourceUrl, chapter.url)
            return cached?.let(::sanitizeWebChapterContent) ?: "（书源未找到）"
        }
        if (source.bookSourceType != TEXT_BOOK_SOURCE_TYPE) {
            AppLog.warn("Reader", "Blocked non-text source content: ${source.bookSourceName} type=${source.bookSourceType}")
            return NON_TEXT_WEB_CONTENT_MESSAGE
        }

        // Try CacheBook after source type is confirmed. Old caches may contain media URLs, so sanitize them too.
        val cached = CacheBook.getContent(sourceUrl, chapter.url)
        if (cached != null) return sanitizeWebChapterContent(cached)

        val nextUrl = _chapters.value.getOrNull(index + 1)?.url
        val content = WebBook.getContentAwait(source, chapter.url, nextUrl)
        val sanitized = sanitizeWebChapterContent(content)
        // Cache for offline
        if (content.isNotBlank() && sanitized == content) {
            CacheBook.putContent(sourceUrl, chapter.url, content)
        }
        return sanitized
    }

    private fun sanitizeWebChapterContent(content: String): String {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) return content
        val lower = trimmed.lowercase(Locale.ROOT)
        val nonBlankLines = trimmed.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .take(4)
            .toList()
        val looksLikeOnlyUrls = nonBlankLines.isNotEmpty() &&
            nonBlankLines.size <= 3 &&
            nonBlankLines.all { it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true) }
        val looksLikeMediaToken = lower.startsWith("#extm3u") ||
            lower.contains(".m3u8") ||
            lower.contains(".mp3") ||
            lower.contains(".m4a") ||
            lower.contains(".mp4") ||
            lower.contains("sound_id=") ||
            lower.contains("expire_time=") ||
            lower.contains("token=")
        return if (looksLikeOnlyUrls && looksLikeMediaToken) {
            AppLog.warn("Reader", "Blocked media/token URL from WEB content")
            NON_TEXT_WEB_CONTENT_MESSAGE
        } else {
            content
        }
    }

    private suspend fun saveProgress() {
        try {
            val book = _book.value ?: return
            val chapterCount = _chapters.value.size
            val visible = _visiblePage.value
            val currentIndex = _currentChapterIndex.value
            val visibleIndex = visible.chapterIndex
            val chapterIdx = when {
                visibleIndex !in 0 until chapterCount -> currentIndex
                else -> visibleIndex
            }.coerceIn(0, (chapterCount - 1).coerceAtLeast(0))
            val chapterPosition = visible.chapterPosition.coerceAtLeast(0)
            val scrollPct = _scrollProgress.value / 100f
            val totalProgress = if (chapterCount > 0) {
                (chapterIdx.toFloat() + scrollPct) / chapterCount
            } else 0f
            val progress = ReadProgress(
                bookId = book.id,
                chapterIndex = chapterIdx,
                chapterPosition = chapterPosition,
                totalProgress = totalProgress.coerceIn(0f, 1f),
                scrollProgress = _scrollProgress.value,
            )
            AppLog.debug("Reader", buildString {
                append("saveProgress")
                append(" | chapter=$chapterIdx/$chapterCount")
                append(" | position=$chapterPosition")
                append(" | scroll=${_scrollProgress.value}%")
                append(" | total=${String.format("%.4f", totalProgress)}")
            })
            bookRepo.saveProgress(progress)
            bookRepo.update(book.copy(
                lastReadChapter = chapterIdx,
                lastReadPosition = chapterPosition,
                lastReadAt = System.currentTimeMillis(),
                readProgress = progress.totalProgress,
                totalChapters = chapterCount,
            ))
            lastQueuedProgressChapterIndex = chapterIdx
            lastQueuedScrollProgress = progress.scrollProgress
            lastQueuedVisibleReadProgress = _visiblePage.value.readProgress
            lastQueuedChapterPosition = chapterPosition
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
