package com.morealm.app.ui.reader.renderer

import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.morealm.app.core.log.AppLog
import com.morealm.app.domain.render.TextPage
import com.morealm.app.ui.reader.page.animation.PageAnimType
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
 * Coordinates the reader page-state contract:
 * - fillPage prepares the visible page slots
 * - turnPageByTap handles tap navigation
 * - turnPageByDrag handles drag navigation
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
        val factoryChanged = this.pageFactory !== pageFactory
        val changed = factoryChanged ||
            this.chapterIndex != chapterIndex ||
            this.pageCount != pageCount ||
            this.renderPageCount != renderPageCount
        if (changed) {
            AppLog.debug(
                "ReaderTap",
                "updateDeps chIdx=${this.chapterIndex}→$chapterIndex" +
                    " pageCount=${this.pageCount}→$pageCount" +
                    " renderPC=${this.renderPageCount}→$renderPageCount" +
                    " factory=${if (this.pageFactory === pageFactory) "same" else "NEW"}" +
                    " newFactory.curChIdx=${pageFactory.snapshotCurrentChapterIndex()}" +
                    " newFactory.prevChIdx=${pageFactory.snapshotPrevChapterIndex()}" +
                    " lastSettled=$lastSettledDisplayPage",
            )
        }
        // pageFactory 是上游唯一真值（Single Source of Truth）；lastReaderContent
        // 是其派生 snapshot（持有当时的 pages list / TextPage 引用 / canvasRecorder）。
        // factory 引用变化 = 派生缓存语义失效，必须同步清空。
        //
        // 实际触发场景：同章 reflow（拖间距 / 字号 commit）—— ReflowEngine.Ready
        // 完成后 textChapter swap 到新对象，CanvasRenderer 的 pageFactory remember
        // key 含 chapter 引用 → 新 ReaderPageFactory 实例 → pages 是新 TextPage list
        // （每个 TextPage 持有 fresh canvasRecorder）。
        //
        // 不清的话：getPageAt 同章路径只校验 chapterIndex 相同就直接走
        // content.pageForDisplay，返回旧 TextPage → 旧 page.canvasRecorder
        // needRecord() 已 false → 回放旧缓存 → 当前页画面不刷新；只有翻页
        // commitPageTurn 才会用新 factory 重建 ReaderPageContent。用户感受为
        // 「调间距当前页无变化，翻页后才变」。
        //
        // 这不是补丁——这是把"上游真值变化 → 下游派生失效"的状态机不变量
        // 收敛到 coordinator 内部，调用方不再需要散户手动清 lastReaderContent。
        if (factoryChanged) {
            lastReaderContent = null
        }
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
        // ─── lastReaderContent stale guard ─────────────────────────────────
        // 跨章瞬间 restoreProgress JUMP 在 LaunchedEffect 里跑，它写
        // `lastReaderContent = createPageState(target).upContent()`。但
        // createPageState 用 coordinator.pageFactory，而 pageFactory 是 remember
        // 出来的——跨章那一帧 remember 重建有滞后（一开始 curChIdx 还是上一章），
        // 等 publishCurTextChapter 推完新章 chapter 才会重建到新 factory。如果
        // restoreProgress JUMP 落在「pageFactory 还是旧的」这窗口里，写出的
        // lastReaderContent 是旧章的 ReaderPageContent，currentPage.chapterIndex
        // 永远停在旧章。后续即使 updateDeps 把 pageFactory 切到新章，
        // lastReaderContent 不会被自动刷新——下次 commitPageTurn 才会重写。
        // 视觉表现：跨章 NEXT 后看到上一章 idx=0，跨章 PREV 后看到下一章末页。
        // 修法：渲染时校验 lastReaderContent.chapterIndex 跟当前 chapter 一致；
        // 不一致时走 fallback（factory.pages[N] 是最新 factory 的对应页）。
        if (content.currentPage.chapterIndex != chapterIndex) return fallback
        return content.pageForDisplay(displayIndex, fallback)
    }

    fun getRelativePage(displayIndex: Int, relativePos: Int): TextPage {
        val factory = pageFactory ?: return TextPage()
        val content = lastReaderContent
        // 同 getPageAt：lastReaderContent 跨章后可能 stale，强制重算。
        if (content == null || content.currentPage.chapterIndex != chapterIndex) {
            return createPageState(displayIndex).upContent().relativePage(relativePos)
        }
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
            val factoryCurChapterIdx = factory.snapshotCurrentChapterIndex()
            val factoryPrevChapterIdx = factory.snapshotPrevChapterIndex()
            val factoryPrevPageCount = factory.snapshotPrevChapterPageCount()
            AppLog.debug(
                "PageTurnFlicker",
                "[1] commitPageTurn BOUNDARY direction=$direction inputDisplayIdx=$displayIndex" +
                    " contentCurrentIdx=${content.currentDisplayIndex}" +
                    " boundaryDir=${content.boundaryDirection}" +
                    " lastSettledBefore=$lastSettledDisplayPage" +
                    " writeBackTo=$targetDisplayIndex" +
                    " coord.chapterIdx=$chapterIndex factory.curChIdx=$factoryCurChapterIdx" +
                    " factory.prevChIdx=$factoryPrevChapterIdx factory.prevPC=$factoryPrevPageCount",
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
        // Reset per-turn state before evaluating a new page-turn attempt.
        if (!pageDelegateState.keyTurnPage(direction)) return
        val lastSettledBeforeSync = lastSettledDisplayPage
        val pagerCurrentPage = state.currentPage
        // Sync with pagerState for non-simulation modes
        if (pageAnimType != PageAnimType.SIMULATION) {
            lastSettledDisplayPage = state.currentPage
        }
        val displayingPage = factory.pageAt(lastSettledDisplayPage)
        AppLog.info(
            "ReaderTap",
            "turnPageByTap ENTRY dir=$direction anim=$pageAnimType" +
                " lastSettledBefore=$lastSettledBeforeSync pagerCurrent=$pagerCurrentPage" +
                " lastSettledAfterSync=$lastSettledDisplayPage" +
                " pageCount=$pageCount renderPC=$renderPageCount" +
                " factory.curChIdx=${factory.snapshotCurrentChapterIndex()}" +
                " factory.prevChIdx=${factory.snapshotPrevChapterIndex()}" +
                " factory.currentLastDisplayIdx=${factory.pageCount - 1}" +
                " displayingPage[title='${displayingPage.title.take(20)}'" +
                " chIdx=${displayingPage.chapterIndex} idx=${displayingPage.index} pageSize=${displayingPage.pageSize}]" +
                " coord.chIdx=$chapterIndex",
        )
        // 修复 COVER/SLIDE 模式跨章乱跳：用 pageCount（真实章节页数）而非
        // renderPageCount（pageFactory 快照页数）做边界判定。
        // 根因：coordinator REBUILD 瞬间 renderPageCount 还停留在上一章节的值
        // （如章节 3 有 14 页 → 进入章节 4 时 renderPC=14 但 pageCount=12），
        // 导致 startDisplayPage.coerceIn(0, 13) 让用户在章节 4 第 13 页（不存在）
        // 就触发 boundary→CHAPTER ADVANCE，强制跳到下一章。
        // pageCount 来自 TextChapter.getPageSize()，是章节真实页数；renderPageCount
        // 来自 pageFactory.pages.size，是 snapshot 时刻的增量渲染进度，跨章时会滞后。
        val startDisplayPage = lastSettledDisplayPage.coerceIn(0, pageCount - 1)
        if (pageAnimType == PageAnimType.NONE) {
            val committed = commitPageTurn(startDisplayPage, direction, readerPageIndexSetter)
            if (committed != null) {
                scope.launch { state.scrollToPage(committed.coerceIn(0, renderPageCount - 1)) }
            }
            AppLog.info(
                "ReaderTap",
                "turnPageByTap NONE-anim: dir=$direction start=$startDisplayPage committed=$committed",
            )
            return
        }
        val target = when (direction) {
            ReaderPageDirection.PREV -> factory.moveToPrev(startDisplayPage)
            ReaderPageDirection.NEXT -> factory.moveToNext(startDisplayPage)
            ReaderPageDirection.NONE -> null
        }
        AppLog.info(
            "ReaderTap",
            "turnPageByTap DECIDE dir=$direction start=$startDisplayPage target=$target" +
                " isPrevChapterTurn=${factory.isPrevChapterTurn(startDisplayPage)}" +
                " isNextChapterTurn=${factory.isNextChapterTurn(startDisplayPage)}" +
                " hasPrev=${factory.hasPrev(startDisplayPage)}" +
                " hasNext=${factory.hasNext(startDisplayPage)}",
        )
        if (target != null) {
            pendingSettledDirection = direction
            pendingTurnStartDisplayPage = startDisplayPage
            scope.launch {
                runCatching { state.animateScrollToPage(target) }
                // ── SIMULATION 模式 isRunning 死锁修复 (音量键 / 重复 tap 失灵) ──
                // SIMULATION 用 SimulationReadView 自管贝塞尔渲染，PagerState 只
                // 充当"当前页索引"的状态机，没有上层的 isScrollInProgress
                // snapshotFlow 钩子去触发 [handlePagerSettled]。结果：
                //   1. startAnim(direction) 设 isRunning = true
                //   2. animateScrollToPage 完成
                //   3. handlePagerSettled 永不被调到 → stopScroll 永不执行
                //   4. isRunning 永远卡 true
                //   5. 用户后续每次音量键 / tap 走 keyTurnPage → startAnim 看到
                //      isRunning=true → return false → turnPageByTap 在 line 233
                //      静默 return（连 ReaderTap ENTRY log 都不打）
                //
                // 复现：日志 19:28:26 那次 tap NEXT 成功 → 之后 11+ 次音量 PREV
                // 全部只有 ReaderKey log 没有 ReaderTap log，与上述链条 100% 吻合。
                //
                // 修复：动画 await 完成后手动 stopScroll + 清 pendingSettledDirection。
                // SLIDE/COVER 不需要因为它们走 handlePagerSettled 自然复位。
                if (pageAnimType == PageAnimType.SIMULATION) {
                    pageDelegateState.stopScroll()
                    pendingSettledDirection = null
                }
            }
            AppLog.info(
                "ReaderTap",
                "turnPageByTap animate: dir=$direction start=$startDisplayPage target=$target renderPC=$renderPageCount pageCount=$pageCount",
            )
        } else if (
            (direction == ReaderPageDirection.PREV && factory.hasPrev(startDisplayPage)) ||
            (direction == ReaderPageDirection.NEXT && factory.hasNext(startDisplayPage))
        ) {
            // 关键诊断点：target=null 但 hasNext/hasPrev=true 时走 commitPageTurn，
            // 这条路径会跨章节（commitPageTurn 内部会调 nextChapter/prevChapter）。
            // 用户报"点屏跳下一章"如果出在这里，常见原因：当前章节只有 1 页（renderPC=1）
            // 或当前已停在章末且 moveToNext 返回 null。
            AppLog.info(
                "ReaderTap",
                "turnPageByTap boundary→commit (CHAPTER ADVANCE): dir=$direction start=$startDisplayPage renderPC=$renderPageCount pageCount=$pageCount",
            )
            commitPageTurn(startDisplayPage, direction, readerPageIndexSetter)
        } else {
            AppLog.info(
                "ReaderTap",
                "turnPageByTap stop: dir=$direction start=$startDisplayPage (no prev/next)",
            )
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
        // 同 turnPageByTap，用 pageCount 而非 renderPageCount 做边界判定。
        val startDisplayPage = lastSettledDisplayPage.coerceIn(0, pageCount - 1)
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
            scope.launch {
                runCatching { state.animateScrollToPage(target) }
                // 同 turnPageByTap 修复：SIMULATION 没有 handlePagerSettled 复位，
                // 必须在这里手动 stopScroll，否则 isRunning 卡 true → 后续 drag/
                // tap/音量键全失灵。
                if (pageAnimType == PageAnimType.SIMULATION) {
                    pageDelegateState.stopScroll()
                    pendingSettledDirection = null
                }
            }
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
        val dirSnapshot = pendingSettledDirection
        val factoryCurChIdx = pageFactory?.snapshotCurrentChapterIndex()
        // ignoredHit 仅放行「非用户翻页触发的 settle」——即 dirSnapshot==null 时
        // 才允许把 settled 当作系统同步信号吞掉。dirSnapshot != null 表示这次
        // settle 是 turnPageByTap/Drag animate 的回调，必须走下方的 commit 路径
        // 才能更新 lastReaderContent / 触发 reportProgress / 写正确的 _visiblePage。
        //
        // 历史 bug：ignored 在 restoreProgress JUMP / coordinator REBUILD 路径写入
        // 后，commitMatch 等正常路径都不消费它，残留多次翻页才会被某一次 settled
        // 命中（最常见 PREV 翻到 idx=0 命中 ignored=0）。一旦命中就跳过 commit，
        // _visiblePage.chapterPosition 留在前一页的值（如 idx=1 的 148），紧跟着
        // 的 saveProgress 写出 (chapter, position, scroll) 三者不自洽的快照。
        if (ignoredPage == settledPage && dirSnapshot == null) {
            AppLog.debug(
                "PageTurnFlicker",
                "[3o] onPageSettled BRANCH=ignoredHit settled=$settledPage lastSettled=$lastSettledDisplayPage" +
                    " ignored=$ignoredPage dir=$dirSnapshot coord.chIdx=$chapterIndex factory.chIdx=$factoryCurChIdx",
            )
            ignoredSettledDisplayPage = null
            pendingSettledDirection = null
            lastSettledDisplayPage = settledPage
            return
        }
        if (settledPage == lastSettledDisplayPage) {
            AppLog.debug(
                "PageTurnFlicker",
                "[3o] onPageSettled BRANCH=same settled=$settledPage lastSettled=$lastSettledDisplayPage" +
                    " ignored=$ignoredPage dir=$dirSnapshot coord.chIdx=$chapterIndex factory.chIdx=$factoryCurChIdx",
            )
            pendingSettledDirection = null
            pageDelegateState.stopScroll()
            return
        }
        val direction = pendingSettledDirection
        // 同 turnPageByTap，用 pageCount 而非 renderPageCount 做边界判定。
        val turnStartDisplayPage = pendingTurnStartDisplayPage.coerceIn(0, pageCount - 1)
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
                AppLog.debug(
                    "PageTurnFlicker",
                    "[3o] onPageSettled BRANCH=phantomConsume settled=$settledPage lastSettled=$lastSettledDisplayPage" +
                        " ignored=$ignoredPage coord.chIdx=$chapterIndex factory.chIdx=$factoryCurChIdx",
                )
                ignoredSettledDisplayPage = null
                return
            }
            AppLog.debug(
                "PageTurnFlicker",
                "[3o] onPageSettled BRANCH=phantomFallback settled=$settledPage lastSettled=$lastSettledDisplayPage" +
                    " coord.chIdx=$chapterIndex factory.chIdx=$factoryCurChIdx WILL_WRITE_lastSettled=$settledPage",
            )
            lastSettledDisplayPage = settledPage
            lastReaderContent = createPageState(settledPage).upContent()
            reportProgress(lastReaderContent)
            pageDelegateState.stopScroll()
            return
        }
        val committed = commitPageTurn(turnStartDisplayPage, direction, readerPageIndexSetter)
        // 用户翻页处理完成后，主动清掉残留的 ignored 槽位，避免 restoreProgress JUMP /
        // coordinator REBUILD 写入的 ignored 在多次同章翻页后被错配命中。commitSnap
        // 分支需要重写 ignored 用于自校对滚动，单独在那条分支里覆盖。
        if (committed == null) {
            AppLog.debug(
                "PageTurnFlicker",
                "[3o] onPageSettled BRANCH=commitNull settled=$settledPage lastSettled=$lastSettledDisplayPage" +
                    " dir=$direction turnStart=$turnStartDisplayPage coord.chIdx=$chapterIndex" +
                    " factory.chIdx=$factoryCurChIdx WILL_WRITE_lastSettled=$settledPage",
            )
            lastSettledDisplayPage = settledPage
            ignoredSettledDisplayPage = null
        } else if (committed != settledPage) {
            AppLog.debug(
                "PageTurnFlicker",
                "[3o] onPageSettled BRANCH=commitSnap settled=$settledPage lastSettled=$lastSettledDisplayPage" +
                    " dir=$direction committed=$committed SNAP→$committed",
            )
            ignoredSettledDisplayPage = committed
            scope.launch { state.scrollToPage(committed.coerceIn(0, renderPageCount - 1)) }
        } else {
            AppLog.debug(
                "PageTurnFlicker",
                "[3o] onPageSettled BRANCH=commitMatch settled=$settledPage lastSettled=$lastSettledDisplayPage" +
                    " dir=$direction committed=$committed",
            )
            ignoredSettledDisplayPage = null
        }
    }
}
