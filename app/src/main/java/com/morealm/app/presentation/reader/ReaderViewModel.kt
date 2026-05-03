package com.morealm.app.presentation.reader

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.morealm.app.domain.entity.Book
import com.morealm.app.domain.entity.Bookmark
import com.morealm.app.domain.entity.BookChapter
import com.morealm.app.domain.preference.AppPreferences
import com.morealm.app.domain.repository.BookRepository
import com.morealm.app.domain.repository.BookmarkRepository
import com.morealm.app.domain.repository.ReadStatsRepository
import com.morealm.app.domain.repository.ReplaceRuleRepository
import com.morealm.app.domain.repository.SourceRepository
import com.morealm.app.core.log.AppLog
import com.morealm.app.service.TtsEventBus
import com.morealm.app.widget.WidgetUpdater
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.morealm.app.core.text.cleanContentForTts
import javax.inject.Inject

/**
 * Page turn modes:
 * - "scroll"   : vertical scroll (WebView native, default)
 * - "tap_zone" : left 30% prev / right 30% next / center menu (classic 3-zone)
 * - "fullscreen": tap anywhere = next page, long press = menu, swipe right = back
 */
enum class PageTurnMode(val key: String, val label: String) {
    SCROLL("scroll", "\u4e0a\u4e0b\u6eda\u52a8"),
    SWIPE_LR("swipe_lr", "\u5de6\u53f3\u6ed1\u52a8\u7ffb\u9875"),
    TAP_ZONE("tap_zone", "\u70b9\u51fb\u7ffb\u9875\uff08\u4e09\u533a\u57df\uff09"),
    FULLSCREEN("fullscreen", "\u5168\u5c4f\u70b9\u51fb\u7ffb\u9875"),
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
    val initialChapterPosition: Int = 0,
    /**
     * 每次 loadChapter 赋一个新值（System.nanoTime()），让 CanvasRenderer 的
     * restoreProgress LaunchedEffect 仅在"真正发起了新的恢复请求"时触发。
     *
     * 解决的 bug：initialChapterPosition 作为 data class 字段持久存活在 StateFlow
     * 里，用户翻页后 Compose 重组（前后台 / renderPageCount 变化）时 key 没变
     * 但 progressRestored 被别的 LaunchedEffect 清掉 → 幽灵恢复。
     *
     * 对齐 Legado 精神：恢复是命令（token 变 = 一次新命令），不是状态订阅。
     */
    val restoreToken: Long = 0L,
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
    private val highlightRepo: com.morealm.app.domain.repository.HighlightRepository,
    private val replaceRuleRepo: ReplaceRuleRepository,
    private val styleRepo: com.morealm.app.domain.repository.ReaderStyleRepository,
    private val sourceRepo: SourceRepository,
    private val progressSync: com.morealm.app.domain.sync.WebDavBookProgressSync,
    private val fontRepo: com.morealm.app.domain.font.FontRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val bookId: String = savedStateHandle["bookId"] ?: ""

    // ── Delegates (existing) ──
    val tts = ReaderTtsController(context, prefs, viewModelScope)
    val settings = ReaderSettingsController(prefs, viewModelScope, context, styleRepo, fontRepo)

    // ── Extracted Controllers ──
    val chapter = ReaderChapterController(
        bookId = bookId,
        bookRepo = bookRepo,
        sourceRepo = sourceRepo,
        replaceRuleRepo = replaceRuleRepo,
        prefs = prefs,
        context = context,
        scope = viewModelScope,
        chineseConvertMode = { settings.chineseConvertMode.value },
        pageTurnMode = { settings.pageTurnMode.value },
        resetTtsParagraphIndex = { tts.resetParagraphIndex() },
        onChapterLoaded = { viewModelScope.launch(Dispatchers.IO) { progress.saveProgress() } },
        setSuppressNextProgressSave = { progress.suppressNextProgressSave = it },
    )

    val progress = ReaderProgressController(
        bookRepo = bookRepo,
        readStatsRepo = readStatsRepo,
        scope = viewModelScope,
        pageTurnMode = { settings.pageTurnMode.value },
        // P1-B WebDav progress sync hook — `maybeUpload` self-throttles on
        // chapter-index change so scroll-only progress saves don't hit the
        // network, and is a no-op when the user has the toggle off.
        onProgressSaved = { book, p -> progressSync.maybeUpload(book, p) },
    )

    val navigation = ReaderNavigationController(
        chapter = chapter,
        progress = progress,
    )

    val search = ReaderSearchController(
        scope = viewModelScope,
        chapter = chapter,
        context = context,
    )

    val bookmark = ReaderBookmarkController(
        bookId = bookId,
        bookmarkRepo = bookmarkRepo,
        scope = viewModelScope,
        chapter = chapter,
        progress = progress,
    )

    val highlight = ReaderHighlightController(
        bookId = bookId,
        highlightRepo = highlightRepo,
        scope = viewModelScope,
        chapter = chapter,
    )

    val contentEdit = ReaderContentEditController(
        context = context,
        scope = viewModelScope,
        chapter = chapter,
    )

    // ── Wire shared state flows between controllers ──
    init {
        // Chapter controller needs mutable access to progress/nav state flows
        chapter.visiblePageState = progress._visiblePage
        chapter.scrollProgressState = progress._scrollProgress
        chapter.navigateDirectionState = navigation._navigateDirection
        chapter.linkedBooksState = navigation._linkedBooks
        // Progress controller needs chapter controller reference
        progress.chapterController = chapter

        // Legado-parity：进入阅读器即清除"N 新"徽章。
        // Legado 是在 ReadBook.saveRead() 每次保存进度时清，我们这里在 init 一次性清掉，
        // 因为：1) 即使用户不滑动，他也已经"看到"这本书；2) 避免依赖 saveProgress 的调用频率；
        // 3) 失败容错（DB 异常不影响阅读功能）。新加的 web 书 lastCheckCount 默认 0，所以 IO 也只是
        // 一次主键 UPDATE 命中行为零的查询，开销可忽略。
        if (bookId.isNotBlank()) {
            viewModelScope.launch(Dispatchers.IO) {
                runCatching { bookRepo.clearLastCheckCount(bookId) }
            }
        }
    }

    // ── Forwarded StateFlows (for backward compatibility with ReaderScreen) ──
    val book: StateFlow<Book?> = chapter.book
    val chapters: StateFlow<List<BookChapter>> = chapter.chapters
    val currentChapterIndex: StateFlow<Int> = chapter.currentChapterIndex
    val chapterContent: StateFlow<String> = chapter.chapterContent
    val renderedChapter: StateFlow<RenderedReaderChapter> = chapter.renderedChapter
    val nextPreloadedChapter: StateFlow<PreloadedReaderChapter?> = chapter.nextPreloadedChapter
    val prevPreloadedChapter: StateFlow<PreloadedReaderChapter?> = chapter.prevPreloadedChapter
    val loading: StateFlow<Boolean> = chapter.loading
    val scrollProgress: StateFlow<Int> = progress.scrollProgress
    val visiblePage: StateFlow<VisibleReaderPage> = progress.visiblePage
    val navigateDirection: StateFlow<Int> = navigation.navigateDirection
    val linkedBooks: StateFlow<List<Book>> = navigation.linkedBooks
    val nextBookPrompt: StateFlow<Book?> = navigation.nextBookPrompt
    val bookmarks: StateFlow<List<Bookmark>> = bookmark.bookmarks
    val highlights: StateFlow<List<com.morealm.app.domain.entity.Highlight>> = highlight.forCurrentChapter
    val searchResults: StateFlow<List<ReaderSearchController.SearchResult>> = search.searchResults
    val pendingSearchSelection: StateFlow<ReaderSearchController.SearchSelection?> = search.pendingSearchSelection
    val searching: StateFlow<Boolean> = search.searching
    val editingContent: StateFlow<Boolean> = contentEdit.editingContent

    // ── EffectiveReplacesDialog forwards (#5) ──
    /** 当前章里 result≠input 的 content 规则集合（真命中），由 ReaderChapterController 跟踪。 */
    val hitContentRules: StateFlow<List<com.morealm.app.domain.entity.ReplaceRule>> = chapter.hitContentRules
    /** 同上，title 路径。 */
    val hitTitleRules: StateFlow<List<com.morealm.app.domain.entity.ReplaceRule>> = chapter.hitTitleRules

    // ── UI-only state (stays in ViewModel) ──
    private val _showControls = MutableStateFlow(false)
    val showControls: StateFlow<Boolean> = _showControls.asStateFlow()

    private val _showTtsPanel = MutableStateFlow(false)
    val showTtsPanel: StateFlow<Boolean> = _showTtsPanel.asStateFlow()

    private val _showSettingsPanel = MutableStateFlow(false)
    val showSettingsPanel: StateFlow<Boolean> = _showSettingsPanel.asStateFlow()

    /** EffectiveReplacesDialog (#5) 显示状态。toggle by Reader 顶栏按钮。 */
    private val _showEffectiveReplacesDialog = MutableStateFlow(false)
    val showEffectiveReplacesDialog: StateFlow<Boolean> = _showEffectiveReplacesDialog.asStateFlow()

    fun showEffectiveReplacesDialog() { _showEffectiveReplacesDialog.value = true }
    fun hideEffectiveReplacesDialog() { _showEffectiveReplacesDialog.value = false }

    /**
     * EffectiveReplacesDialog 内禁用某条规则 — 写库 + 刷新缓存（不立即重渲染，等 dialog 关闭统一来）。
     */
    fun disableReplaceRule(ruleId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            replaceRuleRepo.setEnabled(ruleId, false)
            chapter.refreshReplaceRules()
        }
    }

    /** 关闭繁简转换 — 等价于 Legado 占位条目的 ✕ 操作。 */
    fun disableChineseConvert() {
        viewModelScope.launch(Dispatchers.IO) { prefs.setChineseConvertMode(0) }
    }

    /**
     * 用户在 EffectiveReplacesDialog 内做了任何修改（禁用 / 编辑 / 改繁简） → dismiss 时调一次，
     * 重拉规则缓存并请求重渲染当前章。Legado 等价 viewModel.replaceRuleChanged()。
     */
    fun refreshAfterReplaceRulesChanged() {
        viewModelScope.launch(Dispatchers.IO) {
            chapter.refreshReplaceRules()
            // 重新加载当前章 — 走 loadChapter 同款路径，会清 hit 集合并重跑 applyReplaceRules。
            val idx = chapter.currentChapterIndex.value
            withContext(Dispatchers.Main) {
                chapter.loadChapter(idx)
            }
        }
    }

    private val _readerBrightness = MutableStateFlow(-1f)
    val readerBrightness: StateFlow<Float> = _readerBrightness.asStateFlow()

    private val _autoPageInterval = MutableStateFlow(0)
    val autoPageInterval: StateFlow<Int> = _autoPageInterval.asStateFlow()

    private val _selectedText = MutableStateFlow("")
    val selectedText: StateFlow<String> = _selectedText.asStateFlow()

    private val _viewingImageSrc = MutableStateFlow<String?>(null)
    val viewingImageSrc: StateFlow<String?> = _viewingImageSrc.asStateFlow()

    // ── TTS integration state ──
    private val _readAloudPageTurn = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    val readAloudPageTurn = _readAloudPageTurn.asSharedFlow()
    private var readAloudParagraphPositions: List<Int>? = null
    private var visibleReadAloudChapterPosition: Int = -1

    /**
     * Set when a TTS-driven chapter transition is in flight: either the host finished
     * the current chapter ([TtsEventBus.Event.ChapterFinished]) or the user pressed
     * prev/next on the notification while playing. When `chapterContent` next emits a
     * non-blank value, the ViewModel forwards it to the host via `Command.LoadAndPlay`
     * so playback continues seamlessly across the boundary (Legado-equivalent behavior).
     */
    private var pendingTtsResumeOnNewChapter: Boolean = false

    /**
     * 段级跨章触发的"切上一章续读末段"标记。在收到 [TtsEventBus.Event.PrevChapterToLast]
     * 时与 [pendingTtsResumeOnNewChapter] 一并置 true；在 chapterContent observer 调
     * [tts.ttsPlay] 时透传给 [TtsEventBus.Command.LoadAndPlay.startAtLastParagraph]，让
     * host 把朗读位置落在末段而非首段。次性标志，消费后立即归零。
     */
    private var pendingTtsStartAtLastParagraph: Boolean = false

    // ── Cross-controller coordination ──

    fun ttsPlayPause() {
        // TTS-DIAG #1 — entry point. If this line never appears in the log,
        // the click never reached the VM; check the Reader UI button wiring.
        val content = chapter.chapterContent.value
        val title = chapter.chapters.value.getOrNull(chapter.currentChapterIndex.value)?.title ?: ""
        AppLog.info(
            "TTS",
            "VM.ttsPlayPause: isPlaying=${tts.ttsPlaying.value}, " +
                "book='${chapter.book.value?.title ?: ""}', chapter='$title', " +
                "contentLen=${content.length}, positions=${readAloudParagraphPositions?.size ?: -1}, " +
                "startPos=${visibleReadAloudChapterPosition}",
        )
        tts.ttsPlayPause(
            displayedContent = content.cleanContentForTts(),
            bookTitle = chapter.book.value?.title ?: "",
            chapterTitle = title,
            coverUrl = chapter.book.value?.coverUrl,
            startChapterPosition = visibleReadAloudChapterPosition
                .takeIf { tts.ttsPlaying.value.not() && it >= 0 },
            paragraphPositions = readAloudParagraphPositions,
            bookId = chapter.book.value?.id,
            chapterIndex = chapter.currentChapterIndex.value,
            onChapterFinished = { _readAloudPageTurn.tryEmit(1) },
        )
    }

    fun ttsStop() { tts.ttsStop(); _showTtsPanel.value = false }

    fun setChineseConvertMode(mode: Int) {
        if (mode == settings.chineseConvertMode.value) return
        settings.setChineseConvertMode(mode)
        chapter.nextChapterCache = null
        chapter.prevChapterCache = null
        chapter.loadChapter(
            chapter.currentChapterIndex.value,
            restoreProgress = progress.scrollProgress.value,
            restoreChapterPosition = progress.visiblePage.value.chapterPosition,
        )
    }

    fun setReaderBrightness(value: Float) { _readerBrightness.value = value }

    // ── Forwarded functions (thin delegations) ──

    fun loadChapter(index: Int, restoreProgress: Int = 0, restoreChapterPosition: Int = 0) =
        chapter.loadChapter(index, restoreProgress, restoreChapterPosition)

    fun updateScrollProgress(pct: Int) = progress.updateScrollProgress(pct)
    fun onVisiblePageChanged(index: Int, title: String, readProgress: String, chapterPosition: Int = 0) =
        progress.onVisiblePageChanged(index, title, readProgress, chapterPosition)
    fun onVisibleChapterChanged(index: Int) = progress.onVisibleChapterChanged(index)
    fun saveProgressNow() = progress.saveProgressNow()
    suspend fun saveProgressNowAndWait() = progress.saveProgressNowAndWait()

    fun nextChapter() = navigation.nextChapter()
    fun prevChapter() = navigation.prevChapter()

    /**
     * Phase 2 MD3 同步腾挪入口 — 由 ReaderScreen 在 onChapterCommit 调用。
     *
     * 优先尝试 [ReaderChapterController.commitChapterShiftNext]（同步路径）；
     * 失败（next 未就绪 / content 未缓存）回退到老路径 [navigation.nextChapter]
     * （loadChapter 异步加载）。返回值供调用方决定是否继续触发 onNextChapter。
     *
     * @return true 走了同步路径；false 已回退到异步 nextChapter()
     */
    fun commitChapterShiftNext(): Boolean {
        val ok = chapter.commitChapterShiftNext()
        if (!ok) {
            AppLog.debug("ReadBook", "commitChapterShiftNext fallback to async nextChapter()")
            navigation.nextChapter()
        }
        return ok
    }

    /** 同 [commitChapterShiftNext] 但走 PREV 路径。 */
    fun commitChapterShiftPrev(): Boolean {
        val ok = chapter.commitChapterShiftPrev()
        if (!ok) {
            AppLog.debug("ReadBook", "commitChapterShiftPrev fallback to async prevChapter()")
            navigation.prevChapter()
        }
        return ok
    }
    fun clearNavigateDirection() { navigation._navigateDirection.value = 0 }
    fun openNextLinkedBook() = navigation.openNextLinkedBook()
    fun dismissNextBookPrompt() = navigation.dismissNextBookPrompt()
    fun setNavigateToBookCallback(callback: (String) -> Unit) = navigation.setNavigateToBookCallback(callback)
    fun onScrollNearBottom() = chapter.onScrollNearBottom()
    fun onScrollReachedBottom() = navigation.onScrollReachedBottom()

    fun searchFullText(query: String) = search.searchFullText(query)
    fun clearSearchResults() = search.clearSearchResults()
    fun openSearchResult(result: ReaderSearchController.SearchResult) = search.openSearchResult(result)
    fun consumeSearchSelection() = search.consumeSearchSelection()

    fun addBookmark() = bookmark.addBookmark()
    fun deleteBookmark(id: String) = bookmark.deleteBookmark(id)
    fun jumpToBookmark(bm: Bookmark) = bookmark.jumpToBookmark(bm)

    fun startEditContent() = contentEdit.startEditContent()
    fun cancelEditContent() = contentEdit.cancelEditContent()
    fun saveEditedContent(newContent: String) = contentEdit.saveEditedContent(newContent)
    fun exportAsTxt(outputUri: Uri) = contentEdit.exportAsTxt(outputUri)

    // ── UI toggles ──
    fun toggleControls() { _showControls.value = !_showControls.value }
    fun hideControls() { _showControls.value = false }
    fun toggleTtsPanel() { _showTtsPanel.value = !_showTtsPanel.value }
    fun hideTtsPanel() { _showTtsPanel.value = false }
    fun toggleSettingsPanel() { _showSettingsPanel.value = !_showSettingsPanel.value }
    fun hideSettingsPanel() { _showSettingsPanel.value = false }
    fun setAutoPageInterval(seconds: Int) { _autoPageInterval.value = seconds }
    fun stopAutoPage() { _autoPageInterval.value = 0 }
    fun onTextSelected(text: String) { _selectedText.value = text }
    fun clearSelectedText() { _selectedText.value = "" }
    fun onImageClick(src: String) { _viewingImageSrc.value = src }
    fun dismissImageViewer() { _viewingImageSrc.value = null }

    fun copyTextToClipboard(text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("MoRealm", text))
        Toast.makeText(context, "\u5df2\u590d\u5236", Toast.LENGTH_SHORT).show()
    }

    fun speakSelectedText() {
        val text = _selectedText.value
        tts.speakSelectedText(text)
        _selectedText.value = ""
    }

    fun readAloudFromPosition(chapterPosition: Int) {
        tts.readAloudFrom(
            displayedContent = chapter.chapterContent.value.cleanContentForTts(),
            bookTitle = chapter.book.value?.title ?: "",
            chapterTitle = chapter.chapters.value.getOrNull(chapter.currentChapterIndex.value)?.title ?: "",
            startChapterPosition = chapterPosition.coerceAtLeast(0),
            paragraphPositions = readAloudParagraphPositions,
            bookId = chapter.book.value?.id,
            chapterIndex = chapter.currentChapterIndex.value,
            onChapterFinished = { _readAloudPageTurn.tryEmit(1) },
        )
        _showTtsPanel.value = true
    }

    fun updateReadAloudParagraphPositions(positions: List<Int>) {
        readAloudParagraphPositions = positions.takeIf { it.isNotEmpty() }
    }

    fun updateVisibleReadAloudPosition(chapterIndex: Int, chapterPosition: Int) {
        if (chapterIndex == chapter.currentChapterIndex.value) {
            visibleReadAloudChapterPosition = chapterPosition.coerceAtLeast(0)
        }
    }

    // ── Lifecycle ──

    init {
        viewModelScope.launch(Dispatchers.IO) {
            chapter.initReplaceRules()
            chapter.loadBook()
        }
        settings.initialize()
        tts.initialize(
            getBookTitle = { chapter.book.value?.title ?: "" },
            getChapterTitle = { chapter.chapters.value.getOrNull(chapter.currentChapterIndex.value)?.title ?: "" },
        )
        // ── TTS event router ────────────────────────────────────────────────
        // Single subscriber to TtsEventBus.events; routes notification actions and
        // host signals into the correct ViewModel responses (chapter switch + resume).
        viewModelScope.launch {
            TtsEventBus.events.collect { event ->
                when (event) {
                    is TtsEventBus.Event.PlayPause -> {
                        // Notification toggled play/pause — flip current state.
                        if (tts.ttsPlaying.value) {
                            TtsEventBus.sendCommand(TtsEventBus.Command.Pause)
                        } else {
                            TtsEventBus.sendCommand(TtsEventBus.Command.Play)
                        }
                    }
                    is TtsEventBus.Event.PrevChapter -> {
                        if (tts.ttsPlaying.value) pendingTtsResumeOnNewChapter = true
                        _readAloudPageTurn.tryEmit(-1)
                    }
                    is TtsEventBus.Event.PrevChapterToLast -> {
                        // 段级跨章触发：用户在章首按"上一段"，期望切上一章 + 续读末段。
                        // 跟 PrevChapter 比多一个 startAtLastParagraph 旗标，由 chapterContent
                        // observer 在调 ttsPlay 时透传给 host。pendingTtsResumeOnNewChapter
                        // 保持 true 让续播逻辑接管。
                        if (tts.ttsPlaying.value) {
                            pendingTtsResumeOnNewChapter = true
                            pendingTtsStartAtLastParagraph = true
                        }
                        _readAloudPageTurn.tryEmit(-1)
                    }
                    is TtsEventBus.Event.NextChapter -> {
                        if (tts.ttsPlaying.value) pendingTtsResumeOnNewChapter = true
                        _readAloudPageTurn.tryEmit(1)
                    }
                    is TtsEventBus.Event.ChapterFinished -> {
                        // Host finished the current chapter — let the UI advance and the
                        // chapterContent observer below will hand the new chapter back to
                        // the host (Legado-equivalent seamless continuation).
                        pendingTtsResumeOnNewChapter = true
                        _readAloudPageTurn.tryEmit(1)
                    }
                    is TtsEventBus.Event.AddTimer -> tts.addTtsSleepTimer()
                    is TtsEventBus.Event.AudioFocusLoss,
                    is TtsEventBus.Event.AudioFocusGain -> {
                        // Service-side host already handled the actual pause/resume;
                        // ViewModel doesn't need to respond — UI observes via playbackState.
                    }
                    is TtsEventBus.Event.Error -> {
                        // Minimal handler: log so the failure isn't silent. Surfacing
                        // a user-visible toast / snackbar / "open TTS settings" CTA
                        // belongs to a follow-up — leaving a TODO breadcrumb so the
                        // sealed `when` stays exhaustive without hiding the gap.
                        AppLog.warn(
                            "Reader",
                            "TTS error: ${event.message} (canOpenSettings=${event.canOpenSettings})",
                        )
                    }
                }
            }
        }
        // When a chapter resolves to the empty-content placeholder, auto-show the
        // control bar so the toolbar (and "换源" entry) are immediately discoverable.
        // Without this prompt the user only sees the floating day/night button on a
        // blank screen and assumes the app is broken.
        viewModelScope.launch {
            chapter.chapterContent.collect { text ->
                if (isEmptyContentPlaceholder(text) && !_showControls.value) {
                    _showControls.value = true
                }
                // If TTS triggered the chapter switch, hand the new content to the host
                // so playback continues from paragraph 0.
                if (pendingTtsResumeOnNewChapter && text.isNotBlank() && !isEmptyContentPlaceholder(text)) {
                    pendingTtsResumeOnNewChapter = false
                    val startAtLast = pendingTtsStartAtLastParagraph
                    pendingTtsStartAtLastParagraph = false
                    tts.ttsPlay(
                        displayedContent = text.cleanContentForTts(),
                        bookTitle = chapter.book.value?.title ?: "",
                        chapterTitle = chapter.chapters.value
                            .getOrNull(chapter.currentChapterIndex.value)?.title ?: "",
                        coverUrl = chapter.book.value?.coverUrl,
                        startChapterPosition = 0,
                        paragraphPositions = readAloudParagraphPositions,
                        bookId = chapter.book.value?.id,
                        chapterIndex = chapter.currentChapterIndex.value,
                        startAtLastParagraph = startAtLast,
                        onChapterFinished = null,
                    )
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        tts.shutdown()
        val sessionMs = System.currentTimeMillis() - progress.readingStartTime
        @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
        kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
            com.morealm.app.domain.parser.EpubParser.releaseCache()
            com.morealm.app.domain.parser.PdfParser.releaseCache()
            com.morealm.app.domain.parser.MobiParser.releaseCache()
            com.morealm.app.domain.parser.UmdParser.releaseCache()
            com.morealm.app.domain.parser.LocalBookParser.releaseTxtBuffer()
            progress.saveProgress()
            if (sessionMs > 5000) progress.saveReadingStats(sessionMs)
            // 进度落库后再推 widget — 顺序很重要：widget 取的是
            // BookRepository.getLastReadBook()，必须等 saveProgress 写完才能
            // 让桌面读到最新章节/进度。WidgetUpdater 内置 try/catch + SDK 守卫，
            // 失败仅记日志，不会影响阅读器本身的清理流程。
            WidgetUpdater.refresh(context)
        }
    }
}
