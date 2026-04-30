package com.morealm.app.ui.reader.renderer

import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
    var lastSettledDisplayPage by mutableIntStateOf(0)
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
