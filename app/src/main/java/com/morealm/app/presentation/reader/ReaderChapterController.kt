package com.morealm.app.presentation.reader

import android.content.Context
import android.net.Uri
import com.morealm.app.core.text.sortedNaturalBy
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
 * Friendly placeholder shown when a web chapter ends up with an empty body
 * (server returned 200-empty, parsing rule didn't match, network failed silently…).
 *
 * Without this the reader was rendering literally nothing and the user only saw the
 * floating day/night button — they had no clue the menu was reachable by tapping the
 * screen center. The placeholder explains the failure modes and prompts the menu.
 *
 * NOTE: kept as plain readable Chinese (not encoded) so a future regex maintainer can
 * grep "本章内容为空" easily and bump the message in one place.
 */
internal const val EMPTY_CONTENT_PLACEHOLDER =
    "⚠ 本章内容为空，无法显示\n\n" +
        "可能原因：\n" +
        "• 服务器返回了空响应\n" +
        "• 当前书源的正文规则不适配此章节\n" +
        "• 网络超时或被拦截\n\n" +
        "请尝试：\n" +
        "• 点击屏幕中央 → 顶栏「换源」选择其他书源\n" +
        "• 或退回详情页后重新打开"

/** True when the rendered chapter body is the placeholder above (avoid mistreating it as real content). */
internal fun isEmptyContentPlaceholder(text: String?): Boolean =
    text != null && text.startsWith("⚠ 本章内容为空")

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
    /**
     * 首次章节加载完成（RenderedReaderChapter + visiblePage 全部刷新完毕）时触发。
     * ReaderViewModel 用它把 ReaderProgressController.initialLoadComplete 置 true，
     * 解除「启动时 combine collector 初始 emit 把 (0,0,0) 刷进 DB」的闸门。
     *
     * 幂等：loadChapter 每次成功都会调，但 progress controller 那边只看第一次。
     * 默认 no-op 保证单测 / 旧调用方零迁移。
     */
    private val onInitialChapterLoaded: () -> Unit = {},
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

    // ── Three-chapter cache (Legado MD3 ReadBook 模型对齐) ──
    //
    // 对齐参考: legado-with-MD3 io.legado.app.model.ReadBook
    //   var prevTextChapter: TextChapter? = null
    //   var curTextChapter: TextChapter? = null
    //   var nextTextChapter: TextChapter? = null
    //   fun moveToNextChapter(upContent: Boolean): Boolean {
    //     prevTextChapter = curTextChapter
    //     curTextChapter = nextTextChapter
    //     nextTextChapter = null
    //     ...
    //   }
    //
    // MoRealm 采用 StateFlow 而非 var：StateFlow.value = newValue 在主线程同步生效，
    // 与 Legado 的同步赋值语义等价；Compose collectAsState 会在下一帧触发重组。
    //
    // 三个 flow 的赋值路径：
    //   - publishCurTextChapter / Prev / Next：CanvasRenderer 在 layoutChapterAsync
    //     完成时回调（onTextChapterReady prop）→ 把已排版的 TextChapter 推回这里。
    //     idx 校验保证错章节的排版结果不会污染 cur（用户快速跨章时的迟到回调）。
    //   - commitChapterShiftNext / Prev：ScrollRenderer onChapterCommit 触发，
    //     在调用栈内同步腾挪三个 flow，**这是无缝跨章的核心**。
    private val _prevTextChapter = MutableStateFlow<com.morealm.app.domain.render.TextChapter?>(null)
    val prevTextChapter: StateFlow<com.morealm.app.domain.render.TextChapter?> = _prevTextChapter.asStateFlow()

    private val _curTextChapter = MutableStateFlow<com.morealm.app.domain.render.TextChapter?>(null)
    val curTextChapter: StateFlow<com.morealm.app.domain.render.TextChapter?> = _curTextChapter.asStateFlow()

    private val _nextTextChapter = MutableStateFlow<com.morealm.app.domain.render.TextChapter?>(null)
    val nextTextChapter: StateFlow<com.morealm.app.domain.render.TextChapter?> = _nextTextChapter.asStateFlow()

    /**
     * 由 CanvasRenderer 在 layoutChapterAsync.onCompleted (或 onPageReady index=0)
     * 回调时调用。idx 必须等于当前 currentChapterIndex 才覆写，否则丢弃——
     * 防止用户快速跨章后旧章节的迟到 layout 结果污染新 cur。
     *
     * 对齐 Legado: ReadBook.contentLoadFinish 中 `curTextChapter = textChapter`。
     */
    fun publishCurTextChapter(idx: Int, ch: com.morealm.app.domain.render.TextChapter) {
        if (idx == _currentChapterIndex.value) {
            _curTextChapter.value = ch
            AppLog.debug("ReadBook", "publishCurTextChapter idx=$idx pages=${ch.pageSize} completed=${ch.isCompleted}")
        } else {
            AppLog.debug(
                "ReadBook",
                "publishCurTextChapter REJECT stale: requested idx=$idx but cur=${_currentChapterIndex.value}",
            )
        }
    }

    /**
     * prev 章节预排版完成后由 CanvasRenderer 调用。idx 校验为 cur-1。
     * 对齐 Legado: ReadBook.contentLoadFinish 中 prevTextChapter 赋值。
     */
    fun publishPrevTextChapter(idx: Int, ch: com.morealm.app.domain.render.TextChapter) {
        if (idx == _currentChapterIndex.value - 1) {
            _prevTextChapter.value = ch
            AppLog.debug("ReadBook", "publishPrevTextChapter idx=$idx pages=${ch.pageSize}")
        }
    }

    /**
     * next 章节预排版完成后由 CanvasRenderer 调用。idx 校验为 cur+1。
     * 对齐 Legado: ReadBook.contentLoadFinish 中 nextTextChapter 赋值。
     */
    fun publishNextTextChapter(idx: Int, ch: com.morealm.app.domain.render.TextChapter) {
        if (idx == _currentChapterIndex.value + 1) {
            _nextTextChapter.value = ch
            AppLog.debug("ReadBook", "publishNextTextChapter idx=$idx pages=${ch.pageSize}")
        }
    }

    /**
     * 同步指针腾挪 NEXT 路径：prev = cur; cur = next; next = null。
     *
     * 对齐 Legado [io.legado.app.model.ReadBook.moveToNextChapter] 的精神——在调用栈
     * 内完成所有相关 StateFlow 的赋值，下一帧 Compose 重组立即看到新章节，
     * **不存在异步窗口**。
     *
     * **原子同步腾挪的 StateFlow 集合**（缺一不可）：
     *   1. `_prevTextChapter` ← 旧 `_curTextChapter`（旧 cur 沉为 prev，
     *      ScrollRenderer 下一帧 viewport 顶部用旧 cur 的 last page 填充）
     *   2. `_curTextChapter` ← 旧 `_nextTextChapter`（已排好版的预下章瞬间转为 cur）
     *   3. `_nextTextChapter` ← null（异步重填新 next）
     *   4. `_currentChapterIndex` ← curIdx + 1
     *   5. `_chapterContent` ← 缓存的 nextContent（**关键**：CanvasRenderer 接 content
     *      prop 同源驱动 layoutChapterAsync；若 _chapterContent 不同步，CanvasRenderer
     *      重组时会发现 content/chapterIndex 不匹配，触发不必要的重排，丢弃已就绪的 next 排版）
     *   6. `_renderedChapter` ← 新章节 metadata（携带 restoreToken=nanoTime 供 CanvasRenderer
     *      progress 恢复路径感知）
     *   7. `_prevPreloadedChapter` ← (curIdx, oldCurTitle, oldCurContent)：让
     *      CanvasRenderer.prevChapterTitle/Content 派生的 prelayoutCache cacheKey 命中
     *      已有 prev TextChapter
     *   8. `_nextPreloadedChapter` ← null：旧 next 已转 cur，等异步预加载新 next
     *
     * **前置条件**：`_nextTextChapter` 已就绪（prelayout 完成）+ next 章节 content
     * 已缓存（nextChapterCache 或 _nextPreloadedChapter）。任一缺失则返回 false，
     * 调用方回退到老 [loadChapter] 异步路径。
     *
     * **调用线程**：必须在主线程调用，所有 StateFlow.value = ... 同帧生效。
     *
     * @return true 腾挪成功；false 表示前置条件不满足，调用方回退老路径。
     */
    fun commitChapterShiftNext(): Boolean {
        val curIdx = _currentChapterIndex.value
        val nextIdx = curIdx + 1
        val chapterList = _chapters.value
        if (nextIdx >= chapterList.size) {
            AppLog.debug("ReadBook", "commitChapterShiftNext REJECT at last chapter $curIdx/${chapterList.size}")
            return false
        }
        val nextCh = _nextTextChapter.value ?: run {
            AppLog.warn("ReadBook", "commitChapterShiftNext REJECT _nextTextChapter not ready (cur=$curIdx)")
            return false
        }
        // next content 必须可取到——否则 _chapterContent 同步无源。
        // 优先从 _nextPreloadedChapter（公开 StateFlow）取，其次 nextChapterCache（@Volatile）。
        val nextPreloaded = _nextPreloadedChapter.value
        val nextContent: String = when {
            nextPreloaded != null && nextPreloaded.index == nextIdx -> nextPreloaded.content
            nextChapterCache != null -> nextChapterCache!!
            else -> {
                AppLog.warn("ReadBook", "commitChapterShiftNext REJECT next content not cached (cur=$curIdx)")
                return false
            }
        }
        // 保存旧 cur 信息——同步赋值会覆盖 _chapterContent，必须先快照。
        val oldCurContent = _chapterContent.value
        val oldCurTitle = chapterList[curIdx].title

        AppLog.info("ReadBook", "commitChapterShiftNext $curIdx → $nextIdx | sync moveToNextChapter")

        // ── 原子同步腾挪（主线程当帧）——以下 8 个赋值视为「单帧不可分」 ──
        _prevTextChapter.value = _curTextChapter.value
        _curTextChapter.value = nextCh
        _nextTextChapter.value = null
        _currentChapterIndex.value = nextIdx
        _chapterContent.value = nextContent
        _renderedChapter.value = RenderedReaderChapter(
            index = nextIdx,
            title = chapterList[nextIdx].title,
            content = nextContent,
            initialProgress = 0,
            initialChapterPosition = 0,
            restoreToken = System.nanoTime(),
        )
        _prevPreloadedChapter.value = PreloadedReaderChapter(curIdx, oldCurTitle, oldCurContent)
        prevChapterCache = oldCurContent
        _nextPreloadedChapter.value = null
        nextChapterCache = null
        // visible state 同步——避免 progress controller 看到 stale chapterIndex 导致进度错配
        if (::scrollProgressState.isInitialized) scrollProgressState.value = 0
        if (::visiblePageState.isInitialized) {
            visiblePageState.value = visiblePageState.value.copy(
                chapterIndex = nextIdx,
                title = chapterList[nextIdx].title,
                chapterPosition = 0,
            )
        }
        if (::navigateDirectionState.isInitialized) navigateDirectionState.value = 1
        clearHitTracking()

        // 异步预加载新 next（curIdx+2），不阻塞返回
        scope.launch(Dispatchers.IO) {
            preloadNextChapter(nextIdx + 1)
        }
        // 异步保存进度
        onChapterLoaded()
        return true
    }

    /**
     * 同步指针腾挪 PREV 路径：next = cur; cur = prev; prev = null。
     * 对齐 Legado [io.legado.app.model.ReadBook.moveToPrevChapter]。
     *
     * 同步腾挪集合与 [commitChapterShiftNext] 对称：
     *   - `_nextTextChapter` ← 旧 `_curTextChapter`
     *   - `_curTextChapter` ← 旧 `_prevTextChapter`
     *   - `_prevTextChapter` ← null（异步重填）
     *   - `_currentChapterIndex` ← curIdx - 1
     *   - `_chapterContent` ← prevContent（来自 _prevPreloadedChapter / prevChapterCache）
     *   - `_renderedChapter` ← 新章 metadata，**initialChapterPosition = 末尾**让
     *     CanvasRenderer 启动到末页（PREV 跨章对齐 Legado moveToPrevChapter 行为）
     *   - `_nextPreloadedChapter` ← (curIdx, oldCurTitle, oldCurContent)
     *   - `_prevPreloadedChapter` ← null
     */
    fun commitChapterShiftPrev(): Boolean {
        val curIdx = _currentChapterIndex.value
        val prevIdx = curIdx - 1
        val chapterList = _chapters.value
        if (prevIdx < 0) {
            AppLog.debug("ReadBook", "commitChapterShiftPrev REJECT at first chapter")
            return false
        }
        val prevCh = _prevTextChapter.value ?: run {
            AppLog.warn("ReadBook", "commitChapterShiftPrev REJECT _prevTextChapter not ready (cur=$curIdx)")
            return false
        }
        val prevPreloaded = _prevPreloadedChapter.value
        val prevContent: String = when {
            prevPreloaded != null && prevPreloaded.index == prevIdx -> prevPreloaded.content
            prevChapterCache != null -> prevChapterCache!!
            else -> {
                AppLog.warn("ReadBook", "commitChapterShiftPrev REJECT prev content not cached (cur=$curIdx)")
                return false
            }
        }
        val oldCurContent = _chapterContent.value
        val oldCurTitle = chapterList[curIdx].title

        AppLog.info("ReadBook", "commitChapterShiftPrev $curIdx → $prevIdx | sync moveToPrevChapter")

        _nextTextChapter.value = _curTextChapter.value
        _curTextChapter.value = prevCh
        _prevTextChapter.value = null
        _currentChapterIndex.value = prevIdx
        _chapterContent.value = prevContent
        _renderedChapter.value = RenderedReaderChapter(
            index = prevIdx,
            title = chapterList[prevIdx].title,
            content = prevContent,
            initialProgress = 100,
            initialChapterPosition = 0,
            restoreToken = System.nanoTime(),
        )
        _nextPreloadedChapter.value = PreloadedReaderChapter(curIdx, oldCurTitle, oldCurContent)
        nextChapterCache = oldCurContent
        _prevPreloadedChapter.value = null
        prevChapterCache = null
        if (::scrollProgressState.isInitialized) scrollProgressState.value = 100
        if (::visiblePageState.isInitialized) {
            visiblePageState.value = visiblePageState.value.copy(
                chapterIndex = prevIdx,
                title = chapterList[prevIdx].title,
                chapterPosition = 0,
            )
        }
        if (::navigateDirectionState.isInitialized) navigateDirectionState.value = -1
        clearHitTracking()

        scope.launch(Dispatchers.IO) {
            preloadPrevChapter(prevIdx - 1)
        }
        onChapterLoaded()
        return true
    }

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
            restoreToken = System.nanoTime(),
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
                    val savedScrollProgress = estimateChapterProgress(book, startIndex, cachedChapters.size)
                    // DB 容灾：progress?.chapterPosition 可能被旧 bug 刷成 0（ViewModel init
                    // 阶段 combine collector 初始 emit 抢跑），此时回退到 book.lastReadPosition
                    // 作为兜底——后者由 saveProgress 同步写入 book 表，不受 reading_progress 表
                    // 被冲的影响。两者都为 0 时才是真正的章首。
                    val savedChapterPosition = run {
                        val fromProgress = progress?.chapterPosition ?: 0
                        if (fromProgress > 0) fromProgress else book.lastReadPosition
                    }
                    AppLog.info(
                        "BookmarkDebug",
                        "loadBook ENTRY (web) bookId=$bookId startIndex=$startIndex" +
                            " savedScrollProgress=$savedScrollProgress savedChapterPosition=$savedChapterPosition" +
                            " bookLastReadChapter=${book.lastReadChapter}" +
                            " bookLastReadPosition=${book.lastReadPosition}" +
                            " dbProgress.chapterIndex=${progress?.chapterIndex}" +
                            " dbProgress.chapterPosition=${progress?.chapterPosition}",
                    )
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
                            .sortedNaturalBy { it.title }
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

            val savedScrollProgress = estimateChapterProgress(book, startIndex, chapters.size)
            // DB 容灾（同 web 路径注释）：progress?.chapterPosition 被旧 bug 刷 0 时
            // 回退到 book.lastReadPosition。
            val savedChapterPosition = run {
                val fromProgress = progress?.chapterPosition ?: 0
                if (fromProgress > 0) fromProgress else book.lastReadPosition
            }
            AppLog.info(
                "BookmarkDebug",
                "loadBook ENTRY (local) bookId=$bookId startIndex=$startIndex" +
                    " savedScrollProgress=$savedScrollProgress savedChapterPosition=$savedChapterPosition" +
                    " bookLastReadChapter=${book.lastReadChapter}" +
                    " bookLastReadPosition=${book.lastReadPosition}" +
                    " dbProgress.chapterIndex=${progress?.chapterIndex}" +
                    " dbProgress.chapterPosition=${progress?.chapterPosition}",
            )
            scrollProgressState.value = savedScrollProgress
            loadChapter(startIndex, restoreProgress = savedScrollProgress, restoreChapterPosition = savedChapterPosition)

            if (book.folderId != null) {
                val folderBooks = bookRepo.getBooksByFolderId(book.folderId!!)
                    .sortedNaturalBy { it.title }
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
        // Phase 2 一致性防线：loadChapter 是「跳跃式」加载（任意 index，可能与同步
        // 腾挪状态不连贯）。重置三个真值流，避免后续 commitChapterShift 看到错章节
        // 的残留 _prev/_nextTextChapter 误判为已就绪，污染 cur 渲染。
        // _curTextChapter 会被本次 layoutChapterAsync 完成后 publishCurTextChapter
        // 重新填充；_prev/_nextTextChapter 等 prelayoutCache 完成后 publishPrev/Next 重填。
        _prevTextChapter.value = null
        _curTextChapter.value = null
        _nextTextChapter.value = null
        // EffectiveReplacesDialog: hit tracking is per-chapter, reset before this chapter starts processing.
        if (prevIndex != index) clearHitTracking()
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
                // Track which cache path was used so we can defer clearing preloaded
                // chapter state until AFTER _renderedChapter is published — avoids a
                // frame where the UI sees null preloaded data but hasn't received the
                // new chapter content yet, which causes a visible page-0 flash.
                var usedNextCache = false
                var usedPrevCache = false
                val content = when {
                    nextCached != null && index == prevIndex + 1 -> {
                        nextChapterCache = null
                        usedNextCache = true
                        nextCached
                    }
                    prevCached != null && index == prevIndex - 1 -> {
                        prevChapterCache = null
                        usedPrevCache = true
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

                // Publish new chapter content FIRST, before clearing old preloaded data.
                // This ensures the UI always has valid content to display during the
                // transition, preventing the page-0 flash on backward navigation.
                _chapterContent.value = content
                _renderedChapter.value = RenderedReaderChapter(
                    index = index,
                    title = chapter.title,
                    content = content,
                    initialProgress = targetProgress,
                    initialChapterPosition = targetChapterPosition,
                    restoreToken = System.nanoTime(),
                )
                _currentChapterIndex.value = index

                // NOW safe to clear old preloaded chapter data — the new chapter is
                // already published so the UI won't see a gap.
                if (usedNextCache) _nextPreloadedChapter.value = null
                if (usedPrevCache) _prevPreloadedChapter.value = null

                scrollProgressState.value = targetProgress
                visiblePageState.value = visiblePageState.value.copy(
                    chapterIndex = index,
                    title = chapter.title,
                    chapterPosition = targetChapterPosition,
                )
                setSuppressNextProgressSave(targetProgress > 0 || targetChapterPosition > 0)

                AppLog.info("Chapter", "loadChapter #$index/${chapterList.size} \"${chapter.title.take(20)}\" prog=$targetProgress pos=$targetChapterPosition ${if (isWebBook) "web" else "local"}")
                AppLog.info("ChapterIdxDebug", "loadChapter idx=$index title=\"${chapter.title}\" url=${chapter.url}")
                // BookmarkDebug: 同步打到书签调试 tag 方便抓链路（addBookmark →
                // jumpToBookmark → loadChapter → RenderedReaderChapter.initialChapterPosition
                // → CanvasRenderer.restoreProgress）。
                AppLog.info(
                    "BookmarkDebug",
                    "loadChapter #$index prog=$targetProgress pos=$targetChapterPosition" +
                        " renderedInitialChapPos=$targetChapterPosition",
                )
                // Don't reset navigateDirection here — let CanvasRenderer consume it
                // for startFromLastPage before resetting after progress restoration.
                if (targetProgress == 0 && targetChapterPosition == 0) onChapterLoaded()
                onInitialChapterLoaded()
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
                    restoreToken = System.nanoTime(),
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
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 正常的：用户翻页时上一次 preload 协程会被 cancel，不是错误。
            // 不记 log，但必须重抛 — CancellationException 一旦被吞，结构化并发的
            // 取消传递就断了，上层 launch 会看到这个协程"成功完成"。
            throw e
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
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 同 preloadNextChapter — 翻页时上一轮 preload 被 cancel 是正常的。
            throw e
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
        AppLog.info("ChapterIdxDebug", "loadWebChapterContent ENTRY idx=$index title=\"${chapter.title}\" url=${chapter.url}")
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
        AppLog.info(
            "ChapterIdxDebug",
            "loadWebChapterContent FETCHED idx=$index title=\"${chapter.title}\"" +
                " bodyLen=${content.length} bodyHead=\"${content.take(80).replace('\n', ' ')}\"",
        )
        val sanitized = sanitizeWebChapterContent(content)
        // Empty body / parse-failure → return a readable placeholder instead of "" so
        // the reader has something to render and the user is told how to recover.
        // Don't cache the placeholder — next attempt may succeed.
        if (sanitized.isBlank()) {
            AppLog.warn("Chapter", "empty content for ${book?.title}@${chapter.title} url=${chapter.url}")
            return EMPTY_CONTENT_PLACEHOLDER
        }
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

    /**
     * 当前章「真命中」规则集合 — 在 [applyReplaceRules] / [applyLoadedReplaceRulesSync] 内
     * 当 result != input 时记录该 rule。EffectiveReplacesDialog 通过 [hitContentRules] /
     * [hitTitleRules] 暴露给 UI。
     *
     * 「真命中」语义：rule.replace 真的改变了内容才算（含正则全局替换零次匹配 → 不算命中）。
     * 这与 Legado curTextChapter.effectiveReplaceRules 等价。
     *
     * 切章时：在 setChapterIndex / loadCurrentChapter 头部调用 [clearHitTracking] 重置。
     */
    private val hitContentRulesSet = java.util.Collections.synchronizedSet(linkedSetOf<com.morealm.app.domain.entity.ReplaceRule>())
    private val hitTitleRulesSet = java.util.Collections.synchronizedSet(linkedSetOf<com.morealm.app.domain.entity.ReplaceRule>())

    private val _hitContentRules = MutableStateFlow<List<com.morealm.app.domain.entity.ReplaceRule>>(emptyList())
    val hitContentRules: StateFlow<List<com.morealm.app.domain.entity.ReplaceRule>> = _hitContentRules.asStateFlow()

    private val _hitTitleRules = MutableStateFlow<List<com.morealm.app.domain.entity.ReplaceRule>>(emptyList())
    val hitTitleRules: StateFlow<List<com.morealm.app.domain.entity.ReplaceRule>> = _hitTitleRules.asStateFlow()

    /** Reset hit-tracking sets — must be called when current chapter changes. */
    fun clearHitTracking() {
        hitContentRulesSet.clear()
        hitTitleRulesSet.clear()
        _hitContentRules.value = emptyList()
        _hitTitleRules.value = emptyList()
    }

    /**
     * 清空 next/prev 章节的所有缓存（@Volatile 字段 + StateFlow）。
     *
     * 设计目的：当影响"章节内容呈现"的全局开关切换时（如繁简转换 mode、替换规则启用/禁用），
     * 已缓存的内容是用旧规则转换出来的，必须丢弃，否则会出现：切繁简模式后翻到下一章看到的
     * 还是用旧 mode 转过的字（"反效果"现象）；同步翻页路径 commitChapterShiftNext/Prev
     * 也会直接消费旧 PreloadedReaderChapter。
     *
     * 不清 _chapterContent（当前章），调用方负责后续 loadChapter 重排版。
     */
    fun clearPreloadedChapters() {
        nextChapterCache = null
        prevChapterCache = null
        _nextPreloadedChapter.value = null
        _prevPreloadedChapter.value = null
    }

    /** Re-pull rules from db (called after EffectiveReplacesDialog disables/edits a rule). */
    suspend fun refreshReplaceRules() {
        cachedReplaceRules = replaceRuleRepo.getRulesForBook(bookId)
        // 不在此处 clear hits — 重渲染时会自然刷新
    }

    private fun publishHits() {
        _hitContentRules.value = hitContentRulesSet.toList()
        _hitTitleRules.value = hitTitleRulesSet.toList()
    }

    suspend fun applyReplaceRules(content: String, isTitle: Boolean = false): String {
        if (cachedReplaceRules.isEmpty()) return content
        var result = content
        var anyHit = false
        for (rule in cachedReplaceRules) {
            if (!rule.enabled || !rule.isValid()) continue
            if (isTitle && !rule.scopeTitle) continue
            if (!isTitle && !rule.scopeContent) continue
            val before = result
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
            if (result != before) {
                if (isTitle) hitTitleRulesSet.add(rule) else hitContentRulesSet.add(rule)
                anyHit = true
            }
        }
        if (anyHit) publishHits()
        return result
    }

    fun applyLoadedReplaceRulesSync(content: String, isTitle: Boolean = false): String {
        if (cachedReplaceRules.isEmpty()) return content
        var result = content
        var anyHit = false
        for (rule in cachedReplaceRules) {
            if (!rule.enabled || !rule.isValid()) continue
            if (isTitle && !rule.scopeTitle) continue
            if (!isTitle && !rule.scopeContent) continue
            val before = result
            try {
                result = if (rule.isRegex) {
                    result.replace(getCachedRegex(rule.pattern), rule.replacement)
                } else {
                    result.replace(rule.pattern, rule.replacement)
                }
            } catch (_: Exception) {
            }
            if (result != before) {
                if (isTitle) hitTitleRulesSet.add(rule) else hitContentRulesSet.add(rule)
                anyHit = true
            }
        }
        if (anyHit) publishHits()
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

    // ── SCROLL 模式专用接口（supplied for ChapterWindowSource） ──
    //
    // 这两个函数是 SCROLL 重架的「桥」：让独立 [ChapterWindowSource] 能复用
    // ReaderChapterController 已有的 fetch + replace rule + 繁简转换管线，又
    // 不会触发 [loadChapter] 的副作用风暴（清空三 flow / coordinator REBUILD /
    // restoreProgress JUMP）。
    //
    // 见 docs（temp/solution.txt）的「废除运行时强行 JUMP」与「LazyColumn 直接 addAll」
    // 现代化原则：SCROLL 模式滑动窗口仅扩展段落集合，不切换 cur 章。

    /**
     * 仅取章节正文文本：用 web book 走 [loadWebChapterContent]，本地书走
     * [LocalBookParser.readChapter]，再过 [applyReplaceRules] + 繁简转换。
     *
     * 与 [loadChapter] 的关键区别：
     * - **不**写 [_chapterContent] / [_renderedChapter] / [_currentChapterIndex] 任何 state
     * - **不**清空 prev/cur/next flow，**不**触发 preload neighbors
     * - **不**与 [chapterLoadJob] / [chapterLoadToken] 冲突（独立 IO 协程）
     *
     * 返回 null 表示加载失败（例如越界、book 为空、IO 异常）；调用方应自行处理
     * 占位 / 重试。同步异常会被吞 + 走 [AppLog.warn]。
     *
     * @param index 目标章节索引
     */
    suspend fun fetchAndPrepareChapter(index: Int): String? {
        val chapterList = _chapters.value
        if (index !in chapterList.indices) return null
        val book = _book.value ?: return null
        val chapter = chapterList[index]
        return try {
            withContext(Dispatchers.IO) {
                val raw = if (isWebBook(book)) {
                    loadWebChapterContent(book, chapter, index)
                } else {
                    val localPath = book.localPath ?: return@withContext null
                    LocalBookParser.readChapter(context, Uri.parse(localPath), book.format, chapter)
                }
                val replaced = applyReplaceRules(raw)
                com.morealm.app.core.text.ChineseConverter.convert(replaced, chineseConvertMode())
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 结构化并发的取消传递必须重抛，不能吞，否则上层 launch 会以为该协程「成功完成」
            throw e
        } catch (e: Exception) {
            AppLog.warn("Chapter", "fetchAndPrepareChapter($index) failed: ${e.message}", e)
            null
        }
    }

    /**
     * SCROLL 模式下视口中心段所属章节漂移到新值时，由 [ChapterWindowSource]（debounced 300ms）
     * 调用，仅同步 [_currentChapterIndex]，让进度系统 / TTS / TOC 高亮等下游 collect 到正确
     * 章索引。
     *
     * **关键**：不调 [loadChapter]、不清空 prev/cur/next flow、不触发 preload。
     * 视口里的段落 (paragraphs) 由 [ChapterWindowSource] 自己管，cur 章的"切换"对
     * SCROLL 模式来说只是 UI 派生量。
     *
     * 同 idx 重复调用时短路（避免 StateFlow 触发不必要的下游重组）。
     */
    fun setCurrentChapterIndexFromScroll(index: Int) {
        if (index < 0 || index >= _chapters.value.size) return
        if (_currentChapterIndex.value == index) return
        _currentChapterIndex.value = index
    }

    /** 给 ChapterWindowSource 用的章节标题查询 —— 本身就是 [chapters] flow 的薄包装。 */
    fun chapterTitleAt(index: Int): String =
        _chapters.value.getOrNull(index)?.title.orEmpty()

    /** 章节总数 —— 边界检查用。 */
    fun chaptersSize(): Int = _chapters.value.size
}
