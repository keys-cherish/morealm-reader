package com.morealm.app.presentation.reader

import android.content.Context
import android.net.Uri
import com.morealm.app.domain.entity.Book
import com.morealm.app.domain.entity.BookChapter
import com.morealm.app.domain.parser.LocalBookParser
import com.morealm.app.domain.repository.BookRepository
import com.morealm.app.domain.repository.ReplaceRuleRepository
import com.morealm.app.domain.repository.SourceRepository
import com.morealm.app.domain.webbook.CacheBook
import com.morealm.app.domain.webbook.ChapterResult
import com.morealm.app.domain.webbook.WebBook
import com.morealm.app.core.log.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apache.commons.text.StringEscapeUtils
import java.util.Locale

private const val TEXT_BOOK_SOURCE_TYPE = 0
private const val NON_TEXT_WEB_CONTENT_MESSAGE = "\uff08\u8be5\u4e66\u6e90\u8fd4\u56de\u7684\u662f\u97f3\u9891\u3001\u56fe\u7247\u3001\u89c6\u9891\u6216\u4e34\u65f6\u5a92\u4f53\u94fe\u63a5\uff0c\u4e0d\u662f\u53ef\u9605\u8bfb\u7684\u6587\u672c\u5185\u5bb9\uff09"
private const val READER_ERROR_CHAPTER_URL_PREFIX = "morealm:error:"

/**
 * Manages chapter loading, preloading, web book support, and replace rules.
 * Extracted from ReaderViewModel.
 */
class ReaderChapterController(
    private val bookId: String,
    private val bookRepo: BookRepository,
    private val sourceRepo: SourceRepository,
    private val replaceRuleRepo: ReplaceRuleRepository,
    private val prefs: com.morealm.app.domain.preference.AppPreferences,
    private val context: Context,
    private val scope: CoroutineScope,
    /** Lazily provide the chinese convert mode from settings */
    private val chineseConvertMode: () -> Int,
    /** Lazily provide the page turn mode from settings */
    private val pageTurnMode: () -> PageTurnMode,
    /** Reset TTS paragraph index on chapter load */
    private val resetTtsParagraphIndex: () -> Unit,
    /** Save progress after chapter loads */
    private val onChapterLoaded: () -> Unit,
    /** Notify progress controller to suppress next save */
    private val setSuppressNextProgressSave: (Boolean) -> Unit,
) {
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

    private val _nextPreloadedChapter = MutableStateFlow<PreloadedReaderChapter?>(null)
    val nextPreloadedChapter: StateFlow<PreloadedReaderChapter?> = _nextPreloadedChapter.asStateFlow()

    private val _prevPreloadedChapter = MutableStateFlow<PreloadedReaderChapter?>(null)
    val prevPreloadedChapter: StateFlow<PreloadedReaderChapter?> = _prevPreloadedChapter.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    @Volatile
    var nextChapterCache: String? = null
    @Volatile
    var prevChapterCache: String? = null
    var chapterLoadJob: kotlinx.coroutines.Job? = null
    var chapterLoadToken: Int = 0
    var lastPreCacheCenter: Int = -1

    // ── Replace rules cache ──
    var cachedReplaceRules: List<com.morealm.app.domain.entity.ReplaceRule> = emptyList()
    private val regexCache = HashMap<String, Regex>(16)

    private fun getCachedRegex(pattern: String): Regex {
        return regexCache.getOrPut(pattern) { Regex(pattern) }
    }

    /** Provided by the progress controller for coordinated state updates */
    internal lateinit var visiblePageState: MutableStateFlow<VisibleReaderPage>
    internal lateinit var scrollProgressState: MutableStateFlow<Int>
    internal lateinit var navigateDirectionState: MutableStateFlow<Int>
    internal lateinit var linkedBooksState: MutableStateFlow<List<Book>>

    fun isWebBook(book: Book): Boolean {
        return book.format == com.morealm.app.domain.entity.BookFormat.WEB ||
            (book.localPath == null && book.sourceUrl != null)
    }

    suspend fun initReplaceRules() {
        cachedReplaceRules = replaceRuleRepo.getRulesForBook(bookId)
    }

    // ── Book Loading ──

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
        visiblePageState.value = VisibleReaderPage(0, title, "0.0%", 0)
        scrollProgressState.value = 0
        navigateDirectionState.value = 0
        _loading.value = false
    }

    suspend fun loadBook() {
        _loading.value = true
        try {
            val book = bookRepo.getById(bookId)
            if (book == null) {
                AppLog.error("Chapter", "Book not found: $bookId")
                _loading.value = false
                return
            }
            _book.value = book
            AppLog.info("Chapter", "Opened: ${book.title} (${book.format})")

            val isWebBook = isWebBook(book)

            // For web books, try to load cached chapters from DB first for instant display
            if (isWebBook) {
                val cachedChapters = withContext(Dispatchers.IO) {
                    bookRepo.getChaptersList(bookId)
                }
                if (cachedChapters.isNotEmpty()) {
                    _chapters.value = cachedChapters
                    AppLog.info("Chapter", "Loaded ${cachedChapters.size} cached chapters from DB")

                    // Show chapters immediately, load first chapter
                    val progress = bookRepo.getProgress(bookId)
                    val startIndex = (progress?.chapterIndex ?: book.lastReadChapter)
                        .coerceIn(0, (cachedChapters.size - 1).coerceAtLeast(0))
                    lastPreCacheCenter = startIndex
                    val savedScrollProgress = progress?.scrollProgress ?: estimateChapterProgress(book, startIndex, cachedChapters.size)
                    val savedChapterPosition = progress?.chapterPosition ?: book.lastReadPosition
                    scrollProgressState.value = savedScrollProgress
                    loadChapter(startIndex, restoreProgress = savedScrollProgress, restoreChapterPosition = savedChapterPosition)

                    // Refresh chapters in background (non-blocking)
                    scope.launch(Dispatchers.IO) {
                        try {
                            val freshChapters = loadWebBookChapters(book)
                            if (freshChapters.isNotEmpty() && freshChapters.size != cachedChapters.size) {
                                _chapters.value = freshChapters
                                bookRepo.saveChapters(bookId, freshChapters)
                                if (book.totalChapters != freshChapters.size) {
                                    bookRepo.update(book.copy(totalChapters = freshChapters.size))
                                }
                                AppLog.info("Chapter", "Refreshed chapters: ${freshChapters.size}")
                            }
                        } catch (e: Exception) {
                            AppLog.warn("Chapter", "Background chapter refresh failed: ${e.message}")
                        }
                    }

                    if (book.folderId != null) {
                        val folderBooks = bookRepo.getBooksByFolderId(book.folderId!!)
                            .sortedBy { it.title }
                        linkedBooksState.value = folderBooks.filter { it.id != bookId }
                    }
                    return
                }
            }

            var chapters: List<BookChapter> = if (isWebBook) {
                try {
                    loadWebBookChapters(book)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLog.error("Chapter", "Failed to load web chapters", e)
                    publishReaderError(
                        title = "\u4e66\u6e90\u52a0\u8f7d\u5931\u8d25",
                        detail = webReaderErrorDetail(
                            book,
                            e.readerErrorMessage("\u76ee\u5f55\u89e3\u6790\u5931\u8d25"),
                        ),
                    )
                    return
                }
            } else {
                val localPath = book.localPath ?: run {
                    AppLog.warn("Chapter", "No local path for book ${book.id}")
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
                    scope.launch(Dispatchers.IO) {
                        try {
                            com.morealm.app.domain.parser.EpubParser.preCacheChapters(context, uri, mapped)
                            AppLog.info("Chapter", "EPUB chapters pre-cached")
                        } catch (e: Exception) {
                            AppLog.warn("Chapter", "EPUB pre-cache failed", e)
                        }
                    }
                }
                if (book.format == com.morealm.app.domain.entity.BookFormat.CBZ) {
                    scope.launch(Dispatchers.IO) {
                        try {
                            com.morealm.app.domain.parser.CbzParser.preCacheImages(context, uri, mapped)
                            AppLog.info("Chapter", "CBZ images pre-cached")
                        } catch (e: Exception) {
                            AppLog.warn("Chapter", "CBZ pre-cache failed", e)
                        }
                    }
                }
                mapped
            }

            if (chapters.isEmpty()) {
                AppLog.warn("Chapter", "No chapters found for book ${book.id}")
                if (isWebBook) {
                    // Fallback: create a single chapter from the book URL so content can still be fetched
                    val fallbackUrl = book.tocUrl?.takeIf { it.isNotBlank() } ?: book.bookUrl
                    if (fallbackUrl.isNotBlank()) {
                        AppLog.info("Chapter", "No TOC, creating fallback chapter from bookUrl")
                        chapters = listOf(
                            BookChapter(
                                id = "${bookId}_0",
                                bookId = bookId,
                                index = 0,
                                title = book.title,
                                url = fallbackUrl,
                            )
                        )
                    } else {
                        publishReaderError(
                            title = "\u4e66\u6e90\u65e0\u7ae0\u8282",
                            detail = webReaderErrorDetail(book, "\u8be5\u4e66\u6e90\u6ca1\u6709\u89e3\u6790\u5230\u7ae0\u8282\u76ee\u5f55"),
                        )
                        return
                    }
                } else {
                    _loading.value = false
                    return
                }
            }

            _chapters.value = chapters
            bookRepo.saveChapters(bookId, chapters)
            AppLog.info("Chapter", "Parsed ${chapters.size} chapters")

            if (book.totalChapters != chapters.size) {
                bookRepo.update(book.copy(totalChapters = chapters.size))
            }

            val progress = bookRepo.getProgress(bookId)
            val startIndex = (progress?.chapterIndex ?: book.lastReadChapter)
                .coerceIn(0, (chapters.size - 1).coerceAtLeast(0))
            lastPreCacheCenter = startIndex

            val savedScrollProgress = progress?.scrollProgress ?: estimateChapterProgress(book, startIndex, chapters.size)
            val savedChapterPosition = progress?.chapterPosition ?: book.lastReadPosition
            scrollProgressState.value = savedScrollProgress
            loadChapter(startIndex, restoreProgress = savedScrollProgress, restoreChapterPosition = savedChapterPosition)

            if (book.folderId != null) {
                val folderBooks = bookRepo.getBooksByFolderId(book.folderId!!)
                    .sortedBy { it.title }
                linkedBooksState.value = folderBooks.filter { it.id != bookId }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.error("Chapter", "Failed to load book", e)
            _book.value?.takeIf { isWebBook(it) }?.let { book ->
                publishReaderError(
                    title = "\u4e66\u6e90\u52a0\u8f7d\u5931\u8d25",
                    detail = webReaderErrorDetail(
                        book,
                        e.readerErrorMessage("\u4e66\u7c4d\u52a0\u8f7d\u5931\u8d25"),
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
        resetTtsParagraphIndex()
        val chapter = chapterList[index]
        val book = _book.value ?: run {
            _loading.value = false
            return
        }
        val isWebBook = isWebBook(book)

        chapterLoadJob = scope.launch(Dispatchers.IO) {
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
                        com.morealm.app.core.text.ChineseConverter.convert(replaced, chineseConvertMode())
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
                scrollProgressState.value = targetProgress
                visiblePageState.value = visiblePageState.value.copy(
                    chapterIndex = index,
                    title = chapter.title,
                    chapterPosition = targetChapterPosition,
                )
                setSuppressNextProgressSave(targetProgress > 0 || targetChapterPosition > 0)

                AppLog.debug("Chapter", buildString {
                    append("loadChapter completed")
                    append(" | chapter=$index/${chapterList.size}")
                    append(" | title=${chapter.title.take(20)}")
                    append(" | mode=${pageTurnMode()}")
                    append(" | progress=$targetProgress")
                    append(" | position=$targetChapterPosition")
                    append(" | source=${if (isWebBook) "web" else "local"}")
                    append(" | nextCache=${nextChapterCache != null}")
                    append(" | prevCache=${prevChapterCache != null}")
                })
                // Don't reset navigateDirection here — let CanvasRenderer consume it
                // for startFromLastPage before resetting after progress restoration.
                if (targetProgress == 0 && targetChapterPosition == 0) onChapterLoaded()
                preloadNextChapter(index + 1)
                preloadPrevChapter(index - 1)
                maybeRetriggerPreCache(index)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (loadToken != chapterLoadToken) return@launch
                AppLog.error("Chapter", "Failed to load chapter $index", e)
                val title = if (isWebBook) "\u6b63\u6587\u52a0\u8f7d\u5931\u8d25" else "\u52a0\u8f7d\u5931\u8d25"
                val detail = if (isWebBook) {
                    webReaderErrorDetail(
                        book,
                        e.readerErrorMessage("\u6b63\u6587\u89e3\u6790\u5931\u8d25"),
                    )
                } else {
                    e.readerErrorMessage("\u7ae0\u8282\u8bfb\u53d6\u5931\u8d25")
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
                visiblePageState.value = VisibleReaderPage(index, chapter.title.ifBlank { title }, "0.0%", 0)
                scrollProgressState.value = 0
                navigateDirectionState.value = 0
            } finally {
                if (loadToken == chapterLoadToken) {
                    _loading.value = false
                }
            }
        }
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
                val converted = com.morealm.app.core.text.ChineseConverter.convert(replaced, chineseConvertMode())
                nextChapterCache = converted
                _nextPreloadedChapter.value = PreloadedReaderChapter(nextIndex, chapterList[nextIndex].title, converted)
            }
        } catch (e: Exception) {
            AppLog.warn("Chapter", "Preload next chapter $nextIndex failed", e)
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
                val converted = com.morealm.app.core.text.ChineseConverter.convert(replaced, chineseConvertMode())
                prevChapterCache = converted
                _prevPreloadedChapter.value = PreloadedReaderChapter(prevIndex, chapterList[prevIndex].title, converted)
            }
        } catch (e: Exception) {
            AppLog.warn("Chapter", "Preload prev chapter $prevIndex failed", e)
        }
    }

    /**
     * Re-trigger windowed pre-cache when user navigates far from the last pre-cache center.
     */
    fun maybeRetriggerPreCache(currentIndex: Int) {
        val book = _book.value ?: return
        val distance = kotlin.math.abs(currentIndex - lastPreCacheCenter)
        if (distance < 10) return
        lastPreCacheCenter = currentIndex

        val isWebBook = isWebBook(book)
        if (isWebBook) {
            val sourceUrl = book.sourceUrl ?: return
            scope.launch(Dispatchers.IO) {
                try {
                    val source = sourceRepo.getByUrl(sourceUrl) ?: return@launch
                    val webChapters = _chapters.value.map { ch ->
                        ChapterResult(title = ch.title, url = ch.url)
                    }
                    CacheBook.preload(source, webChapters, currentIndex, preloadCount = 5)
                    AppLog.debug("Chapter", "Web book pre-cache around chapter $currentIndex")
                } catch (e: Exception) {
                    AppLog.warn("Chapter", "Web pre-cache failed", e)
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

        scope.launch(Dispatchers.IO) {
            try {
                when (format) {
                    com.morealm.app.domain.entity.BookFormat.EPUB ->
                        com.morealm.app.domain.parser.EpubParser.preCacheChapters(context, uri, chapters, currentIndex)
                    com.morealm.app.domain.entity.BookFormat.CBZ ->
                        com.morealm.app.domain.parser.CbzParser.preCacheImages(context, uri, chapters, currentIndex)
                    else -> {}
                }
                AppLog.debug("Chapter", "Re-triggered pre-cache around chapter $currentIndex")
            } catch (e: Exception) {
                AppLog.warn("Chapter", "Pre-cache re-trigger failed", e)
            }
        }
    }

    // ── Web Book Support ──

    suspend fun loadWebBookChapters(book: Book): List<BookChapter> {
        val sourceUrl = book.sourceUrl ?: return emptyList()
        val source = withContext(Dispatchers.IO) {
            sourceRepo.getByUrl(sourceUrl)
        } ?: return emptyList()
        if (source.bookSourceType != TEXT_BOOK_SOURCE_TYPE) {
            AppLog.warn("Chapter", "Blocked non-text source chapters: ${source.bookSourceName} type=${source.bookSourceType}")
            return listOf(
                BookChapter(
                    id = "${book.id}_0",
                    bookId = book.id,
                    index = 0,
                    title = "\u975e\u6587\u672c\u4e66\u6e90",
                    url = book.bookUrl,
                )
            )
        }
        if (book.bookUrl.isBlank()) return emptyList()

        var tocUrl = book.tocUrl?.takeIf { it.isNotBlank() } ?: book.bookUrl
        if (tocUrl == book.bookUrl && !book.hasDetail) {
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
                val updated = book.copy(
                    tocUrl = detailed.tocUrl.ifBlank { null },
                    description = detailed.intro?.ifBlank { book.description } ?: book.description,
                    coverUrl = detailed.coverUrl ?: book.coverUrl,
                    hasDetail = true,
                )
                bookRepo.update(updated)
                _book.value = updated
                AppLog.info("Chapter", "Fetched book info, tocUrl=${detailed.tocUrl}")
            } catch (e: Exception) {
                AppLog.warn("Chapter", "Failed to fetch book info: ${e.message}")
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

    suspend fun loadWebChapterContent(book: Book, chapter: BookChapter, index: Int): String {
        if (chapter.url.startsWith(READER_ERROR_CHAPTER_URL_PREFIX)) {
            return chapter.variable ?: readerErrorContent(chapter.title, "\u5f53\u524d\u4e66\u6e90\u6ca1\u6709\u8fd4\u56de\u53ef\u9605\u8bfb\u5185\u5bb9\u3002")
        }
        val sourceUrl = book.sourceUrl ?: return "\uff08\u65e0\u4e66\u6e90\uff09"
        val source = withContext(Dispatchers.IO) {
            sourceRepo.getByUrl(sourceUrl)
        } ?: run {
            val cached = CacheBook.getContent(sourceUrl, chapter.url)
            return cached?.let(::sanitizeWebChapterContent) ?: "\uff08\u4e66\u6e90\u672a\u627e\u5230\uff09"
        }
        if (source.bookSourceType != TEXT_BOOK_SOURCE_TYPE) {
            AppLog.warn("Chapter", "Blocked non-text source content: ${source.bookSourceName} type=${source.bookSourceType}")
            return NON_TEXT_WEB_CONTENT_MESSAGE
        }

        val cached = CacheBook.getContent(sourceUrl, chapter.url)
        if (cached != null) return sanitizeWebChapterContent(cached)

        val nextUrl = _chapters.value.getOrNull(index + 1)?.url
        val content = WebBook.getContentAwait(source, chapter.url, nextUrl)
        val sanitized = sanitizeWebChapterContent(content)
        if (content.isNotBlank() && sanitized == content) {
            CacheBook.putContent(sourceUrl, chapter.url, content)
        }
        return sanitized
    }

    fun sanitizeWebChapterContent(content: String): String {
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
            AppLog.warn("Chapter", "Blocked media/token URL from WEB content")
            NON_TEXT_WEB_CONTENT_MESSAGE
        } else {
            content
        }
    }

    // ── Replace Rules ──

    suspend fun applyReplaceRules(content: String, isTitle: Boolean = false): String {
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

    fun applyLoadedReplaceRulesSync(content: String, isTitle: Boolean = false): String {
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

    // ── Error Content Helpers ──

    fun readerErrorContent(title: String, detail: String): String {
        val readableDetail = wrapLongErrorText(
            applyLoadedReplaceRulesSync(StringEscapeUtils.unescapeHtml4(detail.ifBlank { "\u5f53\u524d\u4e66\u6e90\u6ca1\u6709\u8fd4\u56de\u53ef\u9605\u8bfb\u5185\u5bb9\u3002" })),
        )
        return buildString {
            append(title)
            append("\n\n")
            append(readableDetail)
            append("\n\n")
            append("\u53ef\u4ee5\u8fd4\u56de\u641c\u7d22\u9875\u6362\u4e00\u4e2a\u4e66\u6e90\uff0c\u6216\u7a0d\u540e\u91cd\u8bd5\u3002")
        }
    }

    private fun Throwable.readerErrorMessage(fallback: String): String {
        return localizedMessage
            ?.takeIf { it.isNotBlank() }
            ?.take(240)
            ?: fallback
    }

    fun webReaderErrorDetail(book: Book, reason: String): String {
        val sourceName = StringEscapeUtils.unescapeHtml4(book.originName.ifBlank { book.sourceUrl ?: "\u672a\u77e5\u4e66\u6e90" })
        val title = StringEscapeUtils.unescapeHtml4(book.title)
        return "\u4e66\u540d\uff1a$title\n\u6765\u6e90\uff1a$sourceName\n\u539f\u56e0\uff1a$reason"
    }

    fun wrapLongErrorText(text: String, segmentLength: Int = 48): String {
        return text.lineSequence().joinToString("\n") { line ->
            line.split(' ').joinToString(" ") { token ->
                if (token.length <= segmentLength) token else token.chunked(segmentLength).joinToString("\n")
            }
        }
    }

    fun estimateChapterProgress(book: Book, chapterIndex: Int, chapterCount: Int): Int {
        if (chapterCount <= 0 || book.readProgress <= 0f) return 0
        val chapterFloat = book.readProgress.coerceIn(0f, 1f) * chapterCount
        val inChapter = chapterFloat - chapterIndex
        return (inChapter * 100f).toInt().coerceIn(0, 100)
    }

    fun onScrollNearBottom() {
        val nextIdx = _currentChapterIndex.value + 1
        if (nextIdx < _chapters.value.size && _nextPreloadedChapter.value?.index != nextIdx) {
            scope.launch(Dispatchers.IO) {
                preloadNextChapter(nextIdx)
            }
        }
    }
}
