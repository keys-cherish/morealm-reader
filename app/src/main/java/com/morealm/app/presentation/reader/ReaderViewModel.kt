package com.morealm.app.presentation.reader

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.morealm.app.domain.entity.Book
import com.morealm.app.domain.entity.BookSource
import com.morealm.app.domain.entity.Bookmark
import com.morealm.app.domain.entity.BookChapter
import com.morealm.app.domain.preference.AppPreferences
import com.morealm.app.domain.repository.BookRepository
import com.morealm.app.domain.repository.BookmarkRepository
import com.morealm.app.domain.repository.ReadStatsRepository
import com.morealm.app.domain.repository.ReplaceRuleRepository
import com.morealm.app.domain.repository.SourceRepository
import com.morealm.app.core.log.AppLog
import com.morealm.app.core.text.stripHtml
import com.morealm.app.service.TtsEventBus
import com.morealm.app.widget.WidgetUpdater
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
     * 手势 PREV 跨章语义：true = 跳上一章末页（连续阅读体感）；false = 跳章头。
     *
     * 按 MoRealm 阅读器导航语义铁则（MEMORY.md 「阅读器导航语义」段）：
     *  - 按钮 PREV / NEXT（顶栏 / TtsPanel / 目录跳转）→ 章头（startFromLastPage=false）
     *  - 手势 PREV（仿真翻页向右滑、滑动 / 覆盖手势、竖排手势）→ 上一章末页（startFromLastPage=true）
     *  - 手势 NEXT → 下一章章头（同按钮，仍 false）
     *
     * `commitChapterShiftPrev(toLastPage = true)` 设此字段为 true；按钮路径默认 false。
     * ReaderScreen 把它透传给 CanvasRenderer.startFromLastPage 参数；CanvasRenderer
     * 内 line 988 / 1110 / 1309 据此把 initialPage 算成 pageCount - 1。
     */
    val startFromLastPage: Boolean = false,
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
    /**
     * 用于阅读器 TTS 面板的"语音"下拉自加载（http_* 引擎走 dao 取源名作单元素 voice）。
     * 见 [ReaderTtsController]：reader 不再依赖 host 那条 voices 路径，独立维护。
     */
    private val httpTtsDao: com.morealm.app.domain.db.HttpTtsDao,
    /** 用户高亮词 DAO —— 透传给 [ReaderSettingsController]，喂给排版引擎做高亮词上色。 */
    private val highlightWordDao: com.morealm.app.domain.db.HighlightWordDao,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val bookId: String = savedStateHandle["bookId"] ?: ""

    // ── Delegates (existing) ──
    val tts = ReaderTtsController(context, prefs, viewModelScope, httpTtsDao)
    val settings = ReaderSettingsController(prefs, viewModelScope, context, styleRepo, fontRepo, highlightWordDao)

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
        firstLineIndentChars = { settings.firstLineIndent.value },
        pageTurnMode = { settings.pageTurnMode.value },
        resetTtsParagraphIndex = { tts.resetParagraphIndex() },
        fontRepo = fontRepo,
        onChapterLoaded = { viewModelScope.launch(Dispatchers.IO) { progress.saveProgress() } },
        setSuppressNextProgressSave = { progress.suppressNextProgressSave = it },
        onInitialChapterLoaded = { progress.initialLoadComplete = true },
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
        // 启动进度快照收集器：去掉直接 launch 的 saveProgress 路径，改由
        // combine + distinctUntilChanged + debounce(300) 统一收口，避免翻页时
        // 同状态被反复写 + scroll% 反弹被多次落地（详见 ReaderProgressController.start KDoc）。
        progress.start()

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

        // 登录脚本反向刷新通道：登录完成后脚本调 `java.refreshBookToc / refreshContent` 时，
        // 让当前阅读器立刻重拉目录 / 正文。两条事件都靠当前打开的 book 来决定"哪本书要刷"，
        // 所以即使多个书的源同时登录，也只有当前书响应。
        viewModelScope.launch {
            com.morealm.app.domain.source.SourceLoginRefreshBus.bookToc.collect {
                chapter.refreshTocFromSource()
            }
        }
        viewModelScope.launch {
            com.morealm.app.domain.source.SourceLoginRefreshBus.content.collect {
                chapter.reloadCurrentChapter()
            }
        }
        // 亮度持久化 collect 放在 _readerBrightness 字段声明附近的另一个 init 块里
        // （见本文件下方）—— 不能在这个 init 块启动，因为 Kotlin 按声明顺序初始化，
        // _readerBrightness 字段在本 init 块之后才声明，viewModelScope.launch 用
        // Main.immediate 立即同步执行 collect lambda，访问到尚未初始化的 _readerBrightness
        // 会抛 NPE（崩溃栈：ReaderViewModel.<init>:234, 2026-05-14 22:46:56）。
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

    /**
     * 阅读器内触发"去登录"的事件流。ReaderChapterController 检测到章节/目录加载
     * 失败且疑似登录问题时 emit 出对应源；ReaderScreen collect 后用 Snackbar 把
     * 入口送到用户眼前，避免用户要返回到书源管理页去找登录。
     */
    val loginPrompt: SharedFlow<BookSource> =
        chapter.loginPrompt

    // ── UI-only state (stays in ViewModel) ──
    private val _isControlsVisible = MutableStateFlow(false)
    val isControlsVisible: StateFlow<Boolean> = _isControlsVisible.asStateFlow()
    /**
     * 控制栏是否由「空内容占位自动弹出」触发（见 [chapterContent] collect），用于区别
     * 用户手动开。内容恢复后据此自动收起，避免 showControls 残留 true → 返回键被
     * 「先关控制栏」吃掉一次（用户报「正文页返回偶发要按两次」）。用户手动 toggle/hide
     * 过即清零，不再自动收起，尊重用户操作。
     */
    private var controlsAutoShownForEmpty = false

    private val _isTtsPanelVisible = MutableStateFlow(false)
    val isTtsPanelVisible: StateFlow<Boolean> = _isTtsPanelVisible.asStateFlow()

    private val _isSettingsPanelVisible = MutableStateFlow(false)
    val isSettingsPanelVisible: StateFlow<Boolean> = _isSettingsPanelVisible.asStateFlow()

    /** EffectiveReplacesDialog (#5) 显示状态。toggle by Reader 顶栏按钮。 */
    private val _isEffectiveReplacesDialogVisible = MutableStateFlow(false)
    val isEffectiveReplacesDialogVisible: StateFlow<Boolean> = _isEffectiveReplacesDialogVisible.asStateFlow()

    fun showEffectiveReplacesDialog() { _isEffectiveReplacesDialogVisible.value = true }
    fun hideEffectiveReplacesDialog() { _isEffectiveReplacesDialogVisible.value = false }

    /**
     * EffectiveReplacesDialog 内禁用某条规则 — 写库 + 刷新缓存（不立即重渲染，等 dialog 关闭统一来）。
     */
    fun disableReplaceRule(ruleId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            replaceRuleRepo.setEnabled(ruleId, false)
            chapter.refreshReplaceRules()
        }
    }

    /** 关闭繁简转换 — 等价于 Legado 占位条目的 ✕ 操作。
     *
     *  ⚠ 走 [setChineseConvertMode](0) 而不是直接 prefs.setChineseConvertMode(0)。
     *  历史 bug：之前直接写 prefs，cache 不清、chapter 不 reload，dialog 关闭时
     *  refreshAfterReplaceRulesChanged 跑 loadChapter 又因为 chineseConvertMode()
     *  lambda 读 stateIn.value 还是 stale 的旧 mode，于是「关闭繁简」点完仍显示
     *  繁体（或反过来）——用户报的「大概率失灵或相反」其中一个根因。
     */
    fun disableChineseConvert() {
        setChineseConvertMode(0)
    }

    /**
     * 用户在 EffectiveReplacesDialog 内做了任何修改（禁用 / 编辑 / 改繁简） → dismiss 时调一次，
     * 重拉规则缓存并请求重渲染当前章。Legado 等价 viewModel.replaceRuleChanged()。
     *
     * 与 [setChineseConvertMode] 共享 [chineseConvertMutex]：dialog 内可能刚刚连点了
     * 几次 onSetChineseConvertMode（每次走 mutex + reload），dismiss 立即触发的这次
     * refresh 必须排在那些 reload 之后；否则两个 loadChapter job 可能交叉，
     * 最终 _chapterContent 是用某次中间 mode 转的内容。
     *
     * 即便没有改过繁简（只编辑了替换规则），这里也走 mutex 是无害的——mutex 没人持
     * 时立刻拿到锁，开销可忽略。
     */
    fun refreshAfterReplaceRulesChanged() {
        viewModelScope.launch(Dispatchers.IO) {
            chapter.refreshReplaceRules()
            chineseConvertMutex.withLock {
                // 清预加载——它们是用旧 rules / 旧 mode 转的；不清的话翻下一页
                // 直接消费旧 PreloadedReaderChapter。
                chapter.clearPreloadedChapters()
                val idx = chapter.currentChapterIndex.value
                withContext(Dispatchers.Main) {
                    chapter.loadChapter(idx)
                }
            }
        }
    }

    // 亮度初值 -1f = 跟随系统；下面的 init 块从 AppPreferences 注入持久化值
    // （DataStore Flow 首次 emit 在 collect 启动后异步到达，可能短暂闪一下跟随系统，
    // 但等异步首值过来后立刻补到用户上次的设定值）。
    private val _readerBrightness = MutableStateFlow(-1f)
    val readerBrightness: StateFlow<Float> = _readerBrightness.asStateFlow()

    // 必须在 _readerBrightness 声明之后启动 collect —— Kotlin 按声明顺序初始化，
    // 上面那个大 init 块（class 顶部）执行时本字段还是 null，那时启动 collect 会因
    // Main.immediate 同步触发 lambda 而访问到 null 字段 → NPE 闪退（2026-05-14 复现）。
    // 放在字段后的独立 init 块保证字段已就位。
    init {
        viewModelScope.launch {
            prefs.readerBrightness.collect { _readerBrightness.value = it }
        }
    }

    init {
        // 首行缩进改变 → TXT 正文预埋的段首空格数变了 → 必须重新取章重排（EPUB 走
        // config 由 activeStyle 回流自动重排，但 reload 对它也无害）。drop(1) 跳过
        // stateIn 的初始 emit（避免进屏即无谓 reload）；distinctUntilChanged 防抖同值。
        // 注意：调用 reloadForBakedSettingChange()（方法体运行时才访问 chineseConvertMutex，
        // 该字段声明在本 init 块之后；直接在 collect body 里摸 mutex 会踩「字段未就位」NPE，
        // 见 readerBrightness init 块注释）。
        viewModelScope.launch {
            settings.firstLineIndent
                .drop(1)
                .distinctUntilChanged()
                .collect { reloadForBakedSettingChange() }
        }
    }

    /**
     * 重排当前章 —— 用于「烘进正文的设置」变更（首行缩进等）。clearPreloaded + loadChapter，
     * 与 [setChineseConvertMode] 共享 [chineseConvertMutex] 串行化，避免和简繁切换的
     * loadChapter 并行 race。方法体在**运行时**解析 mutex，规避 init 块声明顺序陷阱。
     */
    private suspend fun reloadForBakedSettingChange() {
        chineseConvertMutex.withLock {
            chapter.clearPreloadedChapters()
            val idx = chapter.currentChapterIndex.value
            withContext(Dispatchers.Main) { chapter.loadChapter(idx) }
        }
    }

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
        val positions = readAloudParagraphPositions
        AppLog.info(
            "TTS",
            "VM.ttsPlayPause: isPlaying=${tts.ttsPlaying.value}, " +
                "book='${chapter.book.value?.title ?: ""}', chapter='$title', " +
                "contentLen=${content.length}, positions=${positions?.size ?: -1}, " +
                "startPos=${visibleReadAloudChapterPosition}",
        )
        tts.ttsPlayPause(
            displayedContent = content.cleanContentForTts(),
            bookTitle = chapter.book.value?.title ?: "",
            chapterTitle = title,
            coverUrl = chapter.book.value?.coverUrl,
            startChapterPosition = visibleReadAloudChapterPosition
                .takeIf { tts.ttsPlaying.value.not() && it >= 0 },
            paragraphPositions = positions,
            bookId = chapter.book.value?.id,
            chapterIndex = chapter.currentChapterIndex.value,
            onChapterFinished = { _readAloudPageTurn.tryEmit(1) },
        )
    }

    fun ttsStop() { tts.ttsStop(); _isTtsPanelVisible.value = false }

    fun setChineseConvertMode(mode: Int) {
        // [DIAGNOSTIC 2026-05-10] 临时排查繁简反复切换后仍是繁体的问题，复现后删除。
        AppLog.info(
            "ChineseDebug",
            "setMode ENTRY target=$mode currentSettings=${settings.chineseConvertMode.value} " +
                "anchor=$chineseConvertAnchor",
        )
        if (mode == settings.chineseConvertMode.value) {
            AppLog.info("ChineseDebug", "setMode OUTER-GUARD-RETURN target=$mode (already at this mode)")
            return
        }
        // ── Anchor 锁：连点期间共用同一个起始位置 ──────────────────────────
        // 用户连点繁→简→繁 时每次都会 loadChapter(restoreChapterPosition=visiblePage.chapterPosition)。
        // 但 LazyScroll 模式下 restoreProgress JUMP 后 visible 段的 chapterPosition
        // 会退到 page 起点（譬如 2842 → 2587）——不是用户实际看到的位置。下次切换
        // 时 anchor 已经是退过一次的值，反复累计 → 用户每点一次繁简，章内位置就
        // 退一点（日志 22:28:40~52 段一连串 loadChapter 的 pos 从 2842 一路掉到
        // 106，就是这个累计回退）。
        //
        // 修复：第一次进入时拍快照，连点期间共用；anchorClearJob 延迟 2 秒后清，
        // 用户停止操作 2 秒才允许下一次重新拍快照。新点击会 cancel 上次的 clear job
        // 重新延后 2 秒——只要用户连点不停，anchor 就保留为最初的精确位置。
        anchorClearJob?.cancel()
        if (chineseConvertAnchor == null) {
            chineseConvertAnchor = progress.scrollProgress.value to progress.visiblePage.value.chapterPosition
        }
        viewModelScope.launch {
            // 串行化：用户连点繁→简→繁 时 viewModelScope 会启动多个并行
            // coroutine，每个跑一遍 setChineseConvertMode + first { it == mode } +
            // clearPreloaded + loadChapter；并行执行会让多个 chapterLoadJob 互相
            // cancel 但中间还可能 publish 旧 mode 的 _chapterContent / _renderedChapter
            // 一帧（race），UI 短暂显示旧 mode 内容（"切了又变回去"）。
            // Mutex 保证多次点击按到达顺序排队执行，最后一次胜出。
            chineseConvertMutex.withLock {
                AppLog.info(
                    "ChineseDebug",
                    "setMode LOCK-ACQUIRED target=$mode currentSettings=${settings.chineseConvertMode.value}",
                )
                if (mode == settings.chineseConvertMode.value) {
                    AppLog.info("ChineseDebug", "setMode INNER-GUARD-RETURN target=$mode")
                    return@withLock
                }
                val (anchorProg, anchorPos) = chineseConvertAnchor
                    ?: (progress.scrollProgress.value to progress.visiblePage.value.chapterPosition)
                settings.setChineseConvertMode(mode)
                // 等 stateIn 反映新值再 reload，避免 chineseConvertMode() lambda
                // 在 IO 协程里读到旧值（DataStore 写入 → Flow emit → stateIn StateFlow.value
                // 更新存在跨线程延迟，旧路径直接 loadChapter 会 race 拿到旧 mode）。
                settings.chineseConvertMode.first { it == mode }
                AppLog.info(
                    "ChineseDebug",
                    "setMode SETTINGS-SYNCED target=$mode confirmedSettings=${settings.chineseConvertMode.value}" +
                        " → loadChapter(idx=${chapter.currentChapterIndex.value}, prog=$anchorProg, pos=$anchorPos)",
                )
                // 清旧 mode 转过的预加载（StateFlow + @Volatile 双轨），
                // 否则同步翻页直接消费旧 PreloadedReaderChapter，呈现
                // "切完繁简翻下一页又变回去" 的反效果。
                chapter.clearPreloadedChapters()
                chapter.loadChapter(
                    chapter.currentChapterIndex.value,
                    restoreProgress = anchorProg,
                    restoreChapterPosition = anchorPos,
                )
            }
        }
        // 启动 / 重置「2 秒静默后清 anchor」的 timer。同 setChineseConvertMode
        // 的连续调用都会先 cancel 上次的 clear job 再启动新的，相当于 debounce。
        anchorClearJob = viewModelScope.launch {
            kotlinx.coroutines.delay(ANCHOR_RETENTION_MS)
            chineseConvertAnchor = null
        }
    }

    /** 串行化 [setChineseConvertMode]——见函数内注释。 */
    private val chineseConvertMutex = Mutex()
    /** 连点期间共享的 (scrollProgress%, chapterPosition) 锚点；停滞 2 秒后清。 */
    private var chineseConvertAnchor: Pair<Int, Int>? = null
    /** 延迟清 [chineseConvertAnchor] 的 job——每次新点击 cancel 重启。 */
    private var anchorClearJob: kotlinx.coroutines.Job? = null
    private companion object {
        /**
         * Anchor 静默保留时长：用户停止繁简切换 2 秒后再清 anchor，下次新点击
         * 才允许重新从 progress 拍快照。2 秒覆盖一般用户「确认效果再点下一次」
         * 的节奏，又不会让 anchor 长时间陈旧（用户真停下来 → 下次切换从最新位置开始）。
         */
        const val ANCHOR_RETENTION_MS = 2_000L
    }

    fun setReaderBrightness(value: Float) {
        // 内存先落，让 slider 拖动无延迟；DataStore 写入异步跟上
        // （update 函数内部已经线程安全，DataStore 自带 coalesce 写盘）。
        _readerBrightness.value = value
        viewModelScope.launch { prefs.setReaderBrightness(value) }
    }

    // ── Forwarded functions (thin delegations) ──

    fun loadChapter(index: Int, restoreProgress: Int = 0, restoreChapterPosition: Int = 0) =
        chapter.loadChapter(index, restoreProgress, restoreChapterPosition)

    /**
     * 「拖动 Slider 所见所得」轻量 seek 入口。同章 in-place（不重 load 内容），
     * 跨章 fallback [loadChapter]。详见 [ReaderChapterController.seekProgressInPlace]。
     */
    fun seekProgressInPlace(index: Int, progress: Int) =
        chapter.seekProgressInPlace(index, progress)

    fun updateScrollProgress(pct: Int) = progress.updateScrollProgress(pct)
    fun onVisiblePageChanged(index: Int, title: String, readProgress: String, chapterPosition: Int = 0) =
        progress.onVisiblePageChanged(index, title, readProgress, chapterPosition)

    /**
     * V2 滚动/分页 Host 只需要上报排版无关的字符锚点；章节标题与展示进度由 VM
     * 从当前状态补齐，避免两个 UI Host 各自复制一套 VisibleReaderPage 组装逻辑。
     */
    fun onVisibleChapterPositionChanged(index: Int, chapterPosition: Int) {
        val target = chapter.chapters.value.getOrNull(index) ?: return
        progress.onVisiblePageChanged(
            index = index,
            title = target.title,
            readProgress = progress.visiblePage.value.readProgress,
            chapterPosition = chapterPosition,
        )
        updateVisibleReadAloudPosition(index, chapterPosition)
    }

    fun onVisibleChapterChanged(index: Int) = progress.onVisibleChapterChanged(index)
    fun saveProgressNow() = progress.saveProgressNow()
    suspend fun saveProgressNowAndWait() = progress.saveProgressNowAndWait()

    fun nextChapter() = navigation.nextChapter()

    /**
     * 跨章 PREV（仿 Legado ReadBook.moveToPrevChapter）。
     * @param toLast true（默认）= 跳上一章末页（手势）；false = 跳章头（按钮）。
     */
    fun prevChapter(toLast: Boolean = true) = navigation.prevChapter(toLast)

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

    /**
     * 同 [commitChapterShiftNext] 但走 PREV 路径（仿 Legado ReadBook.moveToPrevChapter）。
     * @param toLast true（默认）= 跳上一章末页（手势）；false = 跳章头（按钮）。
     */
    fun commitChapterShiftPrev(toLast: Boolean = true): Boolean {
        val ok = chapter.commitChapterShiftPrev(toLast)
        if (!ok) {
            AppLog.debug("ReadBook", "commitChapterShiftPrev fallback to async prevChapter(toLast=$toLast)")
            navigation.prevChapter(toLast)
        }
        return ok
    }
    fun clearNavigateDirection() { navigation._navigateDirection.value = 0 }
    fun openNextLinkedBook() = navigation.openNextLinkedBook()
    fun dismissNextBookPrompt() = navigation.dismissNextBookPrompt()
    fun setNavigateToBookCallback(callback: (String) -> Unit) = navigation.setNavigateToBookCallback(callback)
    fun onScrollNearBottom() = chapter.onScrollNearBottom()
    fun onScrollReachedBottom() = navigation.onScrollReachedBottom()

    /**
     * 全文搜索。同时把 [AppPreferences.innerSearchMode] 与当前阅读位置传给
     * SearchController 做方向过滤；mode = "all" 时与旧行为完全等价。
     */
    fun searchFullText(query: String) = viewModelScope.launch {
        val mode = prefs.innerSearchMode.first()
        val cur = chapter.currentChapterIndex.value
        val pos = progress.visiblePage.value.chapterPosition.coerceAtLeast(0)
        search.searchFullText(query, mode, cur, pos)
    }
    /**
     * 章内搜索：用已加载的当前章节内容做关键词扫描，列出**所有**命中位置。比
     * [searchFullText] 瞬时（无 IO，不下载其它章节），命中无 50 条上限。
     * 复用 `_searchResults`，UI 端共用 [FullTextSearchPanel] 渲染——区别由 Tab 决定。
     *
     * 同样应用 [AppPreferences.innerSearchMode] 方向过滤：前向只列当前页前的命中，
     * 后向只列之后的（同章内按 queryIndexInChapter 与 chapterPosition 比较）。
     */
    fun searchInChapter(query: String) = viewModelScope.launch {
        val mode = prefs.innerSearchMode.first()
        val plain = chapter.chapterContent.value.stripHtml()
        val idx = chapter.currentChapterIndex.value
        val pos = progress.visiblePage.value.chapterPosition.coerceAtLeast(0)
        val title = chapter.chapters.value.getOrNull(idx)?.title ?: ""
        search.searchCurrentChapter(query, plain, idx, title, mode, pos)
    }
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
    fun toggleControls() { _isControlsVisible.value = !_isControlsVisible.value; controlsAutoShownForEmpty = false }
    fun hideControls() { _isControlsVisible.value = false; controlsAutoShownForEmpty = false }
    fun toggleTtsPanel() { _isTtsPanelVisible.value = !_isTtsPanelVisible.value }
    fun hideTtsPanel() { _isTtsPanelVisible.value = false }
    fun toggleSettingsPanel() { _isSettingsPanelVisible.value = !_isSettingsPanelVisible.value }
    fun hideSettingsPanel() { _isSettingsPanelVisible.value = false }
    fun setAutoPageInterval(seconds: Int) { _autoPageInterval.value = seconds }
    fun stopAutoPage() { _autoPageInterval.value = 0 }
    fun onTextSelected(text: String) { _selectedText.value = text }
    fun clearSelectedText() { _selectedText.value = "" }
    fun onImageClick(src: String) { _viewingImageSrc.value = src }
    fun dismissImageViewer() { _viewingImageSrc.value = null }

    fun copyTextToClipboard(text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("MoRealm", text))
        // \u590d\u5236\u53cd\u9988\u7531 UI \u5c42\u7edf\u4e00\u8d70\u5c45\u4e2d\u6d6e\u5c42\uff08CenterToastHost\uff09\uff1bVM \u53ea\u7ba1\u526a\u8d34\u677f\uff0c\u5173\u6ce8\u70b9\u5206\u79bb\u3002
    }

    fun speakSelectedText() {
        val text = _selectedText.value
        tts.speakSelectedText(text)
        _selectedText.value = ""
    }

    fun readAloudFromPosition(chapterPosition: Int) {
        val positions = readAloudParagraphPositions
        tts.readAloudFrom(
            displayedContent = chapter.chapterContent.value.cleanContentForTts(),
            bookTitle = chapter.book.value?.title ?: "",
            chapterTitle = chapter.chapters.value.getOrNull(chapter.currentChapterIndex.value)?.title ?: "",
            startChapterPosition = chapterPosition.coerceAtLeast(0),
            paragraphPositions = positions,
            bookId = chapter.book.value?.id,
            chapterIndex = chapter.currentChapterIndex.value,
            onChapterFinished = { _readAloudPageTurn.tryEmit(1) },
        )
        _isTtsPanelVisible.value = true
    }

    fun updateReadAloudParagraphPositions(positions: List<Int>) {
        readAloudParagraphPositions = positions.takeIf { it.isNotEmpty() }
    }

    /** 获取当前章节的段落总数（用于 TTS 面板显示）。 */
    fun getCurrentChapterParagraphCount(): Int = readAloudParagraphPositions?.size ?: 0

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
                        if (event.loadedByHost) {
                            // host 已经在 service 端 sendCommand(LoadAndPlay) 了——
                            // UI 仅 page-turn 同步，不要再 sendCommand 触发二次加载。
                            // pendingTtsResumeOnNewChapter 保持 false，chapterContent
                            // observer 收到新内容时只走 reader 自身的渲染路径。
                            _readAloudPageTurn.tryEmit(-1)
                        } else {
                            // host 没拿到 bookId 上下文（oneShot / Listen Tab 起播 /
                            // 旧路径），退回 ViewModel 驱动：page-turn + observer 推
                            // LoadAndPlay。
                            if (tts.ttsPlaying.value) pendingTtsResumeOnNewChapter = true
                            _readAloudPageTurn.tryEmit(-1)
                        }
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
                        if (event.loadedByHost) {
                            _readAloudPageTurn.tryEmit(1)
                        } else {
                            if (tts.ttsPlaying.value) pendingTtsResumeOnNewChapter = true
                            _readAloudPageTurn.tryEmit(1)
                        }
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
                val emptyPlaceholder = isEmptyContentPlaceholder(text)
                if (emptyPlaceholder && !_isControlsVisible.value) {
                    _isControlsVisible.value = true
                    controlsAutoShownForEmpty = true
                } else if (!emptyPlaceholder && controlsAutoShownForEmpty) {
                    // 空占位自动弹出的控制栏，内容恢复后自动收起（用户没手动动过时）。
                    // 否则残留 showControls=true 会让返回键先关控制栏、再退出 → 按两次。
                    _isControlsVisible.value = false
                    controlsAutoShownForEmpty = false
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
        progress.stop()
        tts.shutdown()
        com.morealm.app.domain.font.EpubFontRegistry.clearActive()
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
