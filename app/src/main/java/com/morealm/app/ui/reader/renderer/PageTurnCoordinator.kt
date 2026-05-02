package com.morealm.app.ui.reader.renderer

import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.morealm.app.core.log.AppLog
import com.morealm.app.domain.render.TextPage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Coordinates page turn state across all non-scroll animation modes (SLIDE, COVER, SIMULATION, NONE).
 *
 * Extracted from CanvasRenderer to:
 * 1. Reduce CanvasRenderer from ~1840 to ~800 lines
 * 2. Centralize all page-turn state mutations (easier to log & debug)
 * 3. Enable mode isolation via remember(chapterIndex, pageAnimType)
 *
 * Ported from Legado's PageDelegate pattern:
 * - fillPage = Legado ReadView.fillPage
 * - turnPageByTap = Legado PageDelegate.keyTurnPage
 * - turnPageByDrag = Legado PageDelegate onAnimStart after drag
 *
 * All state changes are logged with [PageTurn] prefix for diagnostics.
 */
@Stable
internal class PageTurnCoordinator(
    private val scope: CoroutineScope,
    private val pageAnimType: PageAnimType,
    private val onNextChapter: () -> Unit,
    private val onPrevChapter: () -> Unit,
    private val onProgress: (Int) -> Unit,
    private val onVisiblePageChanged: (Int, String, String, Int) -> Unit,
) {
    // ── Mutable state (all reset when coordinator is recreated on mode/chapter change) ──
    private var _lastSettledDisplayPage by mutableIntStateOf(0)

    /**
     * 实际 setter 日志钩 — 任何人写 lastSettledDisplayPage 都会被 BookmarkDebug 记录。
     * 用于定位"书签跳转设了 5，下一秒被别的路径覆盖回 1"这种幽灵覆盖。
     */
    var lastSettledDisplayPage: Int
        get() = _lastSettledDisplayPage
        set(value) {
            if (_lastSettledDisplayPage != value) {
                AppLog.info(
                    "BookmarkDebug",
                    "coord.lastSettledDisplayPage ${_lastSettledDisplayPage}→$value" +
                        " caller=${Throwable().stackTrace.getOrNull(1)?.let { "${it.fileName}:${it.lineNumber}" }}",
                )
            }
            _lastSettledDisplayPage = value
        }
    var pendingSettledDirection by mutableStateOf<ReaderPageDirection?>(null)
    var pendingTurnStartDisplayPage by mutableIntStateOf(0)
    var ignoredSettledDisplayPage by mutableStateOf<Int?>(null)
    var lastReaderContent by mutableStateOf<ReaderPageContent?>(null)
    val pageDelegateState = ReaderPageDelegateState()

    // ── Dependencies (updated via updateDeps on each recomposition) ──
    var pageFactory: ReaderPageFactory? = null
        private set
    var pagerState: PagerState? = null
        private set
    var chapterIndex: Int = 0
        private set
    var pageCount: Int = 1
        private set
    var renderPageCount: Int = 1
        private set

    fun updateDeps(
        pageFactory: ReaderPageFactory,
        pagerState: PagerState,
        chapterIndex: Int,
        pageCount: Int,
        renderPageCount: Int,
    ) {
        this.pageFactory = pageFactory
        this.pagerState = pagerState
        this.chapterIndex = chapterIndex
        this.pageCount = pageCount
        this.renderPageCount = renderPageCount
    }

    // ══════════════════════════════════════════════════════════════
    // Page turn operations — extracted from CanvasRenderer local functions
    // ══════════════════════════════════════════════════════════════

    fun createPageState(displayIndex: Int): ReaderPageState {
        val factory = pageFactory ?: return ReaderPageState(
            ReaderPageFactory(SnapshotReaderDataSource(0, null, null, null, false)),
            displayIndex,
        ) { _ -> }
        return ReaderPageState(
            pageFactory = factory,
            currentDisplayIndex = displayIndex,
            onBoundaryChapter = { direction ->
                when (direction) {
                    ReaderPageDirection.PREV -> onPrevChapter()
                    ReaderPageDirection.NEXT -> onNextChapter()
                    ReaderPageDirection.NONE -> Unit
                }
            },
        )
    }

    fun reportProgress(content: ReaderPageContent?) {
        val page = content?.currentPage ?: return
        if (page.chapterIndex == chapterIndex && pageCount > 0) {
            onProgress(if (pageCount > 1) (page.index * 100) / (pageCount - 1) else 100)
        }
        onVisiblePageChanged(page.chapterIndex, page.title, page.readProgress, page.chapterPosition)
    }

    fun getPageAt(displayIndex: Int): TextPage {
        val factory = pageFactory ?: return TextPage()
        val fallback = factory.pages.getOrElse(displayIndex) { TextPage() }
        val content = lastReaderContent ?: return fallback
        return content.pageForDisplay(displayIndex, fallback)
    }

    fun getRelativePage(displayIndex: Int, relativePos: Int): TextPage {
        val content = lastReaderContent ?: createPageState(displayIndex).upContent()
        return content.relativePage(relativePos)
    }

    fun commitPageTurn(displayIndex: Int, direction: ReaderPageDirection, readerPageIndexSetter: (Int) -> Unit): Int? {
        val factory = pageFactory ?: return null
        val content = createPageState(displayIndex).fillPage(direction)
        if (content == null) {
            pageDelegateState.stopScroll()
            return null
        }
        if (content.boundaryDirection != null) {
            // 跨章闪烁防御第三层：把"目标章节的目标 displayIndex"回写到
            // lastSettledDisplayPage。
            //   - PREV：跳到上一章的末页（factory.prevChapterLastDisplayIndex）
            //   - NEXT：跳到下一章的首页（恒为 0）
            // 这个写入的 coordinator 实例即将被 chapterIndex 变化触发的
            // remember 重建销毁，从该实例本身看回写"白做了"——但配合层 2
            // (CanvasRenderer 的 coordinator 同步初始化) 后，构造块会读 prelayout
            // cache / readerPageIndex 算出同一个值。两层独立计算同一个目标，互
            // 为 cross-check：只要任一层正确，displayPage 就是对的。
            val targetDisplayIndex = when (content.boundaryDirection) {
                ReaderPageDirection.PREV -> factory.prevChapterLastDisplayIndex() ?: 0
                ReaderPageDirection.NEXT -> 0
                ReaderPageDirection.NONE -> null
            }
            AppLog.debug(
                "PageTurnFlicker",
                "[1] commitPageTurn BOUNDARY direction=$direction inputDisplayIdx=$displayIndex" +
                    " contentCurrentIdx=${content.currentDisplayIndex}" +
                    " boundaryDir=${content.boundaryDirection}" +
                    " lastSettledBefore=$lastSettledDisplayPage" +
                    " writeBackTo=$targetDisplayIndex",
            )
            targetDisplayIndex?.let { lastSettledDisplayPage = it }
            pageDelegateState.stopScroll()
            return null
        }
        lastReaderContent = content
        lastSettledDisplayPage = content.currentDisplayIndex
        factory.currentLocalIndex(content.currentDisplayIndex)?.let { localIndex ->
            readerPageIndexSetter(localIndex)
        }
        reportProgress(content)
        pageDelegateState.stopScroll()
        return content.currentDisplayIndex
    }

    fun commitScrollChapterBoundary(direction: ReaderPageDirection, displayIndex: Int, readerPageIndexSetter: (Int) -> Unit): Boolean {
        val factory = pageFactory ?: return false
        val startDisplayPage = displayIndex.coerceIn(0, renderPageCount - 1)
        val canCommitBoundary = when (direction) {
            ReaderPageDirection.PREV -> factory.isPrevChapterTurn(startDisplayPage)
            ReaderPageDirection.NEXT -> factory.isNextChapterTurn(startDisplayPage)
            ReaderPageDirection.NONE -> false
        }
        if (!canCommitBoundary) return false
        commitPageTurn(startDisplayPage, direction, readerPageIndexSetter)
        return true
    }

    fun turnPageByTap(direction: ReaderPageDirection, readerPageIndexSetter: (Int) -> Unit) {
        val factory = pageFactory ?: return
        val state = pagerState ?: return
        // Reset delegate state on each turn attempt (Legado onDown pattern)
        if (!pageDelegateState.keyTurnPage(direction)) return
        // Sync with pagerState for non-simulation modes
        if (pageAnimType != PageAnimType.SIMULATION) {
            lastSettledDisplayPage = state.currentPage
        }
        val startDisplayPage = lastSettledDisplayPage.coerceIn(0, renderPageCount - 1)
        if (pageAnimType == PageAnimType.NONE) {
            val committed = commitPageTurn(startDisplayPage, direction, readerPageIndexSetter)
            if (committed != null) {
                scope.launch { state.scrollToPage(committed.coerceIn(0, renderPageCount - 1)) }
            }
            return
        }
        val target = when (direction) {
            ReaderPageDirection.PREV -> factory.moveToPrev(startDisplayPage)
            ReaderPageDirection.NEXT -> factory.moveToNext(startDisplayPage)
            ReaderPageDirection.NONE -> null
        }
        if (target != null) {
            pendingSettledDirection = direction
            pendingTurnStartDisplayPage = startDisplayPage
            scope.launch { state.animateScrollToPage(target) }
        } else if (
            (direction == ReaderPageDirection.PREV && factory.hasPrev(startDisplayPage)) ||
            (direction == ReaderPageDirection.NEXT && factory.hasNext(startDisplayPage))
        ) {
            commitPageTurn(startDisplayPage, direction, readerPageIndexSetter)
        } else {
            pageDelegateState.stopScroll()
        }
    }

    fun turnPageByDrag(direction: ReaderPageDirection, readerPageIndexSetter: (Int) -> Unit) {
        val factory = pageFactory ?: return
        val state = pagerState ?: return
        if (!pageDelegateState.startAnim(direction)) return
        if (pageAnimType != PageAnimType.SIMULATION) {
            lastSettledDisplayPage = state.currentPage
        }
        val startDisplayPage = lastSettledDisplayPage.coerceIn(0, renderPageCount - 1)
        if (pageAnimType == PageAnimType.NONE) {
            val committed = commitPageTurn(startDisplayPage, direction, readerPageIndexSetter)
            if (committed != null) {
                scope.launch { state.scrollToPage(committed.coerceIn(0, renderPageCount - 1)) }
            }
            return
        }
        val target = when (direction) {
            ReaderPageDirection.PREV -> factory.moveToPrev(startDisplayPage)
            ReaderPageDirection.NEXT -> factory.moveToNext(startDisplayPage)
            ReaderPageDirection.NONE -> null
        }
        if (target != null) {
            pendingSettledDirection = direction
            pendingTurnStartDisplayPage = startDisplayPage
            scope.launch { state.animateScrollToPage(target) }
        } else if (
            (direction == ReaderPageDirection.PREV && factory.hasPrev(startDisplayPage)) ||
            (direction == ReaderPageDirection.NEXT && factory.hasNext(startDisplayPage))
        ) {
            commitPageTurn(startDisplayPage, direction, readerPageIndexSetter)
        } else {
            pageDelegateState.stopScroll()
        }
    }

    /** Handle pager settling after animation (for SLIDE/COVER modes, NOT simulation/scroll). */
    fun handlePagerSettled(settledPage: Int, readerPageIndexSetter: (Int) -> Unit) {
        if (pageAnimType == PageAnimType.SIMULATION || pageAnimType == PageAnimType.SCROLL) return
        val state = pagerState ?: return
        val ignoredPage = ignoredSettledDisplayPage
        if (ignoredPage == settledPage) {
            ignoredSettledDisplayPage = null
            pendingSettledDirection = null
            lastSettledDisplayPage = settledPage
            return
        }
        if (settledPage == lastSettledDisplayPage) {
            pendingSettledDirection = null
            pageDelegateState.stopScroll()
            return
        }
        val direction = pendingSettledDirection
        val turnStartDisplayPage = pendingTurnStartDisplayPage.coerceIn(0, renderPageCount - 1)
        pendingSettledDirection = null
        if (direction == null) {
            // ─── HorizontalPager 切换模式时的 phantom-settle 防御 ─────────
            // 当 coordinator 刚刚 rebuild 时 [ignoredSettledDisplayPage] 被设为
            // 期望的 initial page（在 CanvasRenderer remember 块里 = readerPageIndex
            // 解析出的进度页索引）。能走到这里说明 settledPage 既不匹配
            // ignoredPage 也不匹配 lastSettled——这种情形几乎只发生在「上一个
            // 模式 (SCROLL) 期间 pagerState.currentPage 被同步到了 0，新挂载
            // 的 HorizontalPager (SLIDE/COVER/NONE) 立刻把残留的 0 当作 settled
            // 上报」。如果这里照旧 lastSettledDisplayPage = settledPage，会把
            // 进度直接回滚到 0、下一帧渲染章节首页大字标题——实测 21:16:53
            // 那一段就是这条路径。
            //
            // 修复：消费掉「等待初始 settle」的槽位但不写回，让 LaunchedEffect
            // 后续的 pagerState.scrollToPage(initial) 把状态拉到正确位置。
            if (ignoredSettledDisplayPage != null) {
                ignoredSettledDisplayPage = null
                return
            }
            lastSettledDisplayPage = settledPage
            lastReaderContent = createPageState(settledPage).upContent()
            reportProgress(lastReaderContent)
            pageDelegateState.stopScroll()
            return
        }
        val committed = commitPageTurn(turnStartDisplayPage, direction, readerPageIndexSetter)
        if (committed == null) {
            lastSettledDisplayPage = settledPage
        } else if (committed != settledPage) {
            ignoredSettledDisplayPage = committed
            scope.launch { state.scrollToPage(committed.coerceIn(0, renderPageCount - 1)) }
        }
    }
}
