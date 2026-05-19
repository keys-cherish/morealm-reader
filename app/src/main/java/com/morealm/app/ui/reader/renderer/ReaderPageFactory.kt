package com.morealm.app.ui.reader.renderer

import com.morealm.app.domain.render.TextChapter
import com.morealm.app.domain.render.TextPage

/**
 * Compose/MVVM counterpart of Legado TextPageFactory.
 *
 * The public [pages] list intentionally contains only the committed current
 * chapter. Previous/next chapter pages are preview surfaces for delegates, not
 * real display pages. This mirrors Legado's ReadView model and keeps preview
 * pages from mutating saved progress or scroll state.
 */
internal class ReaderPageFactory(
    private val dataSource: ReaderDataSource,
) {
    // ── 阶段 B2（2026-05-19）所有字段改 getter 透传 dataSource ──
    //
    // 旧设计：构造时 snapshot dataSource 全部字段成 `private val`，factory 实例
    // 一旦创建就是冻结快照。跨章后必须 remember(chapter, prev, next, ...) 重建
    // factory → 重建 CanvasRecorder → DISPOSE/MOUNT 黑窗 + 重 measure = 跨章顿一下。
    //
    // 新设计：dataSource 用 MutableReaderDataSource（mutableState backing），
    // factory 实例永驻，通过 getter 读 dataSource 最新值。Compose snapshot system
    // 在 measure / placement / derivedStateOf 等读取路径自动跟踪 dependency，
    // 跨章 setAll() 时 observer 看到 atomic commit 不会撕裂。
    //
    // 性能：getter 调用比 val 读多一次方法 + dataSource 字段 mutableState read。
    // 每帧 ~20 次访问（measure/placement/draw 各几次），mutableState.value 在 snapshot
    // system 下是 lock-free read，性能可接受。如未来需要可加内部 cachedSnapshot
    // 复用相同 chapter ref 的 pages list（snapshotPages() 可能复制开销）。
    private val currentChapter: TextChapter? get() = dataSource.currentChapter
    private val prevChapter: TextChapter? get() = dataSource.prevChapter
    private val nextChapter: TextChapter? get() = dataSource.nextChapter
    private val pageIndex: Int get() = dataSource.pageIndex
    private val currentPages: List<TextPage>
        get() = currentChapter?.snapshotPages().orEmpty()
    private val displayPages: List<TextPage>
        get() = currentPages.ifEmpty {
            listOf(formattedTitlePage(currentChapter?.title.orEmpty(), currentChapter))
        }
    private val prevPages: List<TextPage> get() = prevChapter?.snapshotPages().orEmpty()
    private val nextPages: List<TextPage> get() = nextChapter?.snapshotPages().orEmpty()
    private val currentChapterCompleted: Boolean get() = currentChapter?.isCompleted == true
    private val prevChapterCompleted: Boolean get() = prevChapter?.isCompleted == true

    val currentPageIndex: Int
        get() = pageIndex.coerceIn(0, (displayPages.size - 1).coerceAtLeast(0))

    val pages: List<TextPage> get() = displayPages

    val pageCount: Int get() = pages.size.coerceAtLeast(1)
    val currentChapterPageCount: Int get() = displayPages.size.coerceAtLeast(1)
    private val currentLastDisplayIndex: Int get() = displayPages.lastIndex.coerceAtLeast(0)

    val hasPrev: Boolean get() = hasPrev(currentPageIndex)
    val hasNext: Boolean get() = hasNext(currentPageIndex)
    val hasNextPlus: Boolean get() = hasNextPlus(currentPageIndex)

    val curPage: TextPage
        get() = currentPageForLocalIndex(currentPageIndex)

    val nextPage: TextPage
        get() = nextPageForLocalIndex(currentPageIndex)

    val prevPage: TextPage
        get() = prevPageForLocalIndex(currentPageIndex)

    val nextPlusPage: TextPage
        get() = nextPlusPageForLocalIndex(currentPageIndex)

    fun moveToFirst(): Int = 0

    fun moveToLast(): Int = currentLastDisplayIndex

    fun hasPrev(displayIndex: Int): Boolean {
        return displayIndex > 0 || dataSource.hasPrevChapter()
    }

    fun hasNext(displayIndex: Int): Boolean {
        return displayIndex < currentLastDisplayIndex ||
            (currentChapterCompleted && dataSource.hasNextChapter())
    }

    fun hasNextPlus(displayIndex: Int): Boolean {
        return displayIndex < currentLastDisplayIndex - 1 ||
            (currentChapterCompleted && dataSource.hasNextChapter())
    }

    fun moveToPrev(displayIndex: Int): Int? {
        if (!hasPrev(displayIndex)) return null
        return if (displayIndex > 0) displayIndex - 1 else null
    }

    fun moveToNext(displayIndex: Int): Int? {
        if (!hasNext(displayIndex)) return null
        return if (displayIndex < currentLastDisplayIndex) displayIndex + 1 else null
    }

    fun isPrevChapterTurn(displayIndex: Int): Boolean {
        return displayIndex <= 0 && dataSource.hasPrevChapter()
    }

    fun isNextChapterTurn(displayIndex: Int): Boolean {
        return displayIndex >= currentLastDisplayIndex &&
            currentChapterCompleted &&
            dataSource.hasNextChapter()
    }

    /**
     * Last `displayIndex` of the *previous* chapter, or `null` if there is no
     * previous chapter / it has no pages snapshotted yet.
     *
     * Used by [PageTurnCoordinator.commitPageTurn] on the PREV boundary path
     * (cross-chapter flicker fix layer 3). When the user turns past the first
     * page of the current chapter, `commitPageTurn` writes this value back to
     * `lastSettledDisplayPage` so that — should the new coordinator be able to
     * read it before re-init clobbers it — the simulation view starts at the
     * incoming chapter's last page rather than its first page.
     *
     * Layer 2 (synchronous coordinator init in CanvasRenderer) is the one that
     * actually persists across coordinator rebuild; layer 3 is the in-memory
     * paper trail that lets layer 2 verify "yes, the user really did want the
     * last page of this chapter, not the first."
     */
    fun prevChapterLastDisplayIndex(): Int? {
        if (prevPages.isEmpty()) return null
        return prevPages.lastIndex
    }

    fun snapshotCurrentChapterIndex(): Int? = currentChapter?.chapterIndex

    fun snapshotPrevChapterIndex(): Int? = prevChapter?.chapterIndex

    fun snapshotPrevChapterPageCount(): Int = prevPages.size

    fun pageAt(displayIndex: Int): TextPage {
        return pages.getOrNull(displayIndex.coerceIn(0, pageCount - 1)) ?: curPage
    }

    fun prevPageForDisplay(displayIndex: Int): TextPage {
        val localIndex = currentLocalIndex(displayIndex)
        return when {
            localIndex != null -> prevPageForLocalIndex(localIndex)
            else -> prevPage
        }
    }

    fun nextPageForDisplay(displayIndex: Int): TextPage {
        val localIndex = currentLocalIndex(displayIndex)
        return when {
            localIndex != null -> nextPageForLocalIndex(localIndex)
            else -> nextPage
        }
    }

    fun nextPlusPageForDisplay(displayIndex: Int): TextPage {
        val localIndex = currentLocalIndex(displayIndex)
        return when {
            localIndex != null -> nextPlusPageForLocalIndex(localIndex)
            else -> nextPlusPage
        }
    }

    fun upContent(relativePosition: Int = 0, resetPageOffset: Boolean = true) {
        dataSource.upContent(relativePosition, resetPageOffset)
    }

    fun displayIndexForCurrentPage(localPageIndex: Int = currentPageIndex): Int {
        return localPageIndex.coerceIn(0, pageCount - 1)
    }

    fun currentLocalIndex(displayIndex: Int): Int? {
        return displayIndex.takeIf { it in displayPages.indices }
    }

    fun isCurrentChapterDisplay(displayIndex: Int): Boolean = currentLocalIndex(displayIndex) != null

    // ───────────────────────────────────────────────────────────────────────
    // Cross-chapter unified pageCount (SLIDE / COVER only, 2026-05-18)
    //
    // 仅 SLIDE / COVER 翻页动画启用：让 HorizontalPager.pageCount 等于
    // prevPages.size + pages.size + nextPages.size，HorizontalPager 翻页直接
    // 穿过章节边界 → 跨章动画与章内动画一致流畅。SIMULATION / SCROLL / NONE /
    // SLIDE_VERTICAL 不进此路径，仍按 [pageCount]（单章）渲染。
    //
    // 关键概念：
    //   - **unifiedIndex** = 全局联合页号 [0, unifiedPageCount)
    //   - 区间划分：
    //       [0, prevPages.size)                     → prev 章 pages
    //       [prevPages.size, prevPages.size+pages.size)        → cur 章 pages
    //       [prevPages.size+pages.size, unifiedPageCount)      → next 章 pages
    //   - chapter shift 触发条件：onPageSettled 时 settledUnified 不在 cur 区间
    // ───────────────────────────────────────────────────────────────────────

    /** Cross-chapter unified pageCount. prev/next 未加载时 size=0，等价于 [pageCount]。 */
    val unifiedPageCount: Int
        get() = (prevPages.size + pages.size + nextPages.size).coerceAtLeast(1)

    /** prev 章 page 数。LaunchedEffect / restoreProgress 用来算 unified offset。 */
    val unifiedPrevChapterSize: Int get() = prevPages.size

    /** next 章 page 数。 */
    val unifiedNextChapterSize: Int get() = nextPages.size

    /** cur 章 page 数（= [pageCount]，别名让 unified 路径读起来对称）。 */
    val unifiedCurChapterSize: Int get() = pages.size

    /** cur 章在 unified 联合页号里的起始 index（= prevPages.size）。 */
    val unifiedCurStartIndex: Int get() = prevPages.size

    /** cur 章在 unified 联合页号里的末尾 index（含，可作 `<=` 比较）。 */
    val unifiedCurEndIndex: Int get() = prevPages.size + (pages.size - 1).coerceAtLeast(0)

    /** Map unified index → 对应 [TextPage]。越界时返回 cur 末页防 crash。 */
    fun unifiedPageAt(unifiedIndex: Int): TextPage {
        val prevSize = prevPages.size
        val curSize = pages.size
        return when {
            unifiedIndex < 0 -> prevPages.firstOrNull() ?: pages.first()
            unifiedIndex < prevSize -> prevPages[unifiedIndex]
            unifiedIndex < prevSize + curSize -> pages[unifiedIndex - prevSize]
            unifiedIndex < prevSize + curSize + nextPages.size -> nextPages[unifiedIndex - prevSize - curSize]
            else -> pages.lastOrNull() ?: TextPage()
        }
    }

    /**
     * 把 cur 章本地 displayIndex 转 unified pageIndex（= prevSize + localIndex）。
     * 用于 chapter 切换后 scrollToPage 算 target unified index、restoreProgress JUMP 算 target。
     */
    fun unifiedFromCurLocal(localIndex: Int): Int = prevPages.size + localIndex.coerceAtLeast(0)

// companion object moved to bottom of class, merged with KEEP_SWIPE_TIP — see below.

    fun pageForTurn(displayIndex: Int, relativePos: Int): TextPage? {
        return when (relativePos) {
            -1 -> when {
                displayIndex > 0 -> pages.getOrNull(displayIndex - 1)
                prevChapterCompleted -> prevPages.lastOrNull()?.removePageAloudSpan()
                else -> null
            }
            0 -> pageAt(displayIndex)
            1 -> when {
                displayIndex < currentLastDisplayIndex -> pages.getOrNull(displayIndex + 1)
                currentChapterCompleted -> nextPages.firstOrNull()?.removePageAloudSpan()
                else -> null
            }
            2 -> when {
                displayIndex < currentLastDisplayIndex - 1 -> pages.getOrNull(displayIndex + 2)
                displayIndex < currentLastDisplayIndex && currentChapterCompleted ->
                    nextPages.firstOrNull()?.removePageAloudSpan()
                currentChapterCompleted -> nextPages.getOrNull(1)?.removePageAloudSpan()
                else -> null
            }
            else -> null
        }
    }

    private fun currentPageForLocalIndex(localIndex: Int): TextPage {
        return displayPages.getOrNull(localIndex)
            ?: formattedTitlePage(currentChapter?.title.orEmpty(), currentChapter)
    }

    private fun nextPageForLocalIndex(localIndex: Int): TextPage {
        currentChapter?.let { chapter ->
            if (localIndex < currentPages.size - 1) {
                // 同章邻页保留 isReadAloud：当前 TTS 朗读段所在页可能就是这个邻页，
                // 这里清掉会让用户翻到隔壁页再翻回来时高亮永久消失（因为
                // [CanvasRenderer] 的 LaunchedEffect(chapter, readAloudChapterPosition)
                // key 都没变，不会重跑 upPageAloudSpan 来恢复）。
                // 跨章路径下面那个 nextPages.firstOrNull()?.removePageAloudSpan()
                // 才需要清——切到 next 章的页本来就不该带当前章的 aloud span。
                return currentPages.getOrNull(localIndex + 1)
                    ?: formattedTitlePage(chapter.title, chapter)
            }
            if (!currentChapterCompleted) return formattedTitlePage(chapter.title, chapter)
        }
        nextChapter?.let { chapter ->
            return nextPages.firstOrNull()?.removePageAloudSpan()
                ?: formattedTitlePage(chapter.title, chapter)
        }
        return TextPage().format()
    }

    private fun prevPageForLocalIndex(localIndex: Int): TextPage {
        currentChapter?.let { chapter ->
            if (localIndex > 0) {
                // 同上：同章上一页禁清 aloud span，避免「翻去隔壁页再翻回来高亮消失」。
                // 复现路径：朗读段 A 在 page X，TTS 标好 isReadAloud；用户翻到 X+1
                // → next 路径不影响 X；翻回 X → prev 路径走到这里清掉了 X 的 aloud span。
                return currentPages.getOrNull(localIndex - 1)
                    ?: formattedTitlePage(chapter.title, chapter)
            }
            if (!currentChapterCompleted) return formattedTitlePage(chapter.title, chapter)
        }
        prevChapter?.let { chapter ->
            return prevPages.lastOrNull()?.removePageAloudSpan()
                ?: formattedTitlePage(chapter.title, chapter)
        }
        return TextPage().format()
    }

    private fun nextPlusPageForLocalIndex(localIndex: Int): TextPage {
        currentChapter?.let { chapter ->
            if (localIndex < currentPages.size - 2) {
                // 同章 +2 同样禁清 aloud span，保持与 [pageForTurn] line 168 路径
                // 一致（那条路径取同章邻页时不带 remove）。
                return currentPages.getOrNull(localIndex + 2)
                    ?: formattedTitlePage(chapter.title, chapter)
            }
            if (!currentChapterCompleted) return formattedTitlePage(chapter.title, chapter)
            nextChapter?.let { next ->
                if (localIndex < currentPages.size - 1) {
                    return nextPages.firstOrNull()?.removePageAloudSpan()
                        ?: formattedTitlePage(next.title, next)
                }
                return nextPages.getOrNull(1)?.removePageAloudSpan()
                    ?: TextPage().apply { text = KEEP_SWIPE_TIP }.format()
            }
        }
        return TextPage().format()
    }

    private fun formattedTitlePage(title: String, chapter: TextChapter?): TextPage {
        return TextPage(title = title).apply { textChapter = chapter }.format()
    }

    // companion 内既有 private 常量也有 public 测试 helper —— 整体非 private，
    // 私有内容用 @JvmStatic 或 internal 标记。这里 KEEP_SWIPE_TIP 仅本类用，留 internal 即可。
    companion object {
        internal const val KEEP_SWIPE_TIP = "继续滑动以加载下一章..."

        /**
         * Pure helper for unit tests: 给定 unified pageIndex + 三章 size，
         * 返回 (chapterRelative, localIndex) — chapterRelative -1=prev / 0=cur / +1=next。
         * 越界返回 cur 端点。
         */
        fun localFromUnifiedIndex(
            unifiedIndex: Int,
            prevCount: Int,
            curCount: Int,
            nextCount: Int,
        ): Pair<Int, Int> {
            val safeCur = curCount.coerceAtLeast(1)
            return when {
                unifiedIndex < 0 -> 0 to 0
                unifiedIndex < prevCount -> -1 to unifiedIndex
                unifiedIndex < prevCount + curCount -> 0 to (unifiedIndex - prevCount)
                unifiedIndex < prevCount + curCount + nextCount -> 1 to (unifiedIndex - prevCount - curCount)
                else -> 0 to (safeCur - 1)
            }
        }

        /**
         * Pure helper for unit tests + Coordinator: chapter shift 后旧 unified currentPage
         * 应该重映射到新 cur 章的哪个 unified index。
         *   - direction NEXT：新 cur 是旧 next → 新 unified target = newPrevCount + 0（新 cur 首页）
         *   - direction PREV：新 cur 是旧 prev → 新 unified target = newPrevCount + (newCurCount - 1)（新 cur 末页）
         *   - 其他 / NONE：返回原 unified（不重映射）
         */
        fun remapUnifiedAfterChapterShift(
            oldUnified: Int,
            isNextShift: Boolean,
            isPrevShift: Boolean,
            newPrevCount: Int,
            newCurCount: Int,
        ): Int {
            return when {
                isNextShift -> newPrevCount.coerceAtLeast(0)
                isPrevShift -> newPrevCount + (newCurCount - 1).coerceAtLeast(0)
                else -> oldUnified
            }
        }
    }
}
