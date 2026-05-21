package com.morealm.app.domain.render.layout

/**
 * page-level 滚动模式的 page 序列管理器 —— V2 滚动 Canvas 重构 (page+全) 的核心。
 *
 * 仿 Legado [外部开源阅读器实现] 设计：
 * - 4 个 page 槽 [curPage] / [prevPage] / [nextPage] / [nextPlusPage]
 * - [moveToPrev] / [moveToNext] 内部自动处理跨章（章末→下章首 / 章首→上章末）
 * - [hasPrev] / [hasNext] / [hasNextPlus] 边界判定（首/末章 + 章内首/末 page）
 *
 * ── 与 V2 chapter-level 模型的差异 ──
 *
 * |             | V2 chapter-level (现状)              | page-level (本类)              |
 * | ---         | ---                                  | ---                            |
 * | 滚动单元    | chapter (~22000px)                   | **page (~1800px)**             |
 * | swap 频率   | 每次跨章必触发                       | 仅当 page 跨章 boundary 才触发 |
 * | 章号变化    | 每次 swap 章号必变                   | page 跨章才变（章内不变）      |
 * | InfoBar 闪 | 频繁                                 | **罕见**（对齐 Legado 体感）   |
 *
 * ── 跨章自动 swap 算法 ──
 *
 * [moveToNext] 章末：currentChapter ← nextChapter, prevChapter ← (旧 cur), nextChapter ← null,
 * pageIndex = 0, 触发 [chapterShiftCallback]+1 通知 Host 异步加载新 next。
 *
 * [moveToPrev] 章首：currentChapter ← prevChapter, nextChapter ← (旧 cur), prevChapter ← null,
 * pageIndex = newCur.lastIndex, 触发 [chapterShiftCallback]-1 通知 Host 异步加载新 prev。
 *
 * 注意：currentChapter/prevChapter/nextChapter 的真值在 [dataSource]（由 Host 维护的 Compose state）。
 * Factory 不自己存这 3 个引用，每次访问通过 dataSource get —— 这样 Host 异步 load 完
 * 新章节后填回 dataSource，下一次 hasNext()/nextPage 自然看到新值，无需额外同步。
 *
 * ── 与 Legado TextPageFactory 的差异 ──
 *
 * - chapter 引用 swap 这一步在 Legado 由 `ReadBook.moveToNextChapter` 全局单例做，
 *   本工程改由 [chapterShiftCallback] 通知 Host，Host 在 ViewModel 层 swap dataSource state
 * - Legado `upContent` UI 通知本工程不需要（Compose 重组自动驱动）
 *
 * @property dataSource Host 注入的 3 章 layout 数据源（Compose state-backed）。
 * @property chapterShiftCallback 跨章 swap 完成后回调 (delta=+1 next / -1 prev)。
 *   Host 应在此异步加载新远端章 + throttle 后持久化进度。**调用时 [pageIndex] 已重设。**
 */
class ScrollPageFactory(
    private val dataSource: ScrollChapterDataSource,
    private val chapterShiftCallback: (delta: Int) -> Unit = {},
) {

    /**
     * 当前 page 在 [ScrollChapterDataSource.currentChapter] 内的索引（0-based）。
     * **必须是 mutableIntStateOf**：Renderer measure scope 通过 [curPage] 等 getter
     * read 本字段。普通 var 让 Compose 无法订阅 → 章内 page 切换 pageIndex++ 不触发
     * measure → 画面停在旧 page → 滚到下 page 时延迟切换 = 用户感受到的"滚动闪烁"。
     */
    private val _pageIndex = androidx.compose.runtime.mutableIntStateOf(0)
    var pageIndex: Int
        get() = _pageIndex.intValue
        private set(value) { _pageIndex.intValue = value }

    // ── 4 page 槽（仿 Legado）──
    // 章末 page 时 nextPage 自然取 nextChapter.pages[0]（跨章拼接），nextPlusPage 取 [1]。
    // currentChapter == null（加载中）时所有槽返回 emptyPage 占位，避免上层 NPE。

    val curPage: ScrollPage
        get() {
            val ch = dataSource.currentChapter ?: return EMPTY_PAGE
            return ch.pages.getOrElse(pageIndex) { EMPTY_PAGE }
        }

    val nextPage: ScrollPage
        get() {
            val ch = dataSource.currentChapter ?: return EMPTY_PAGE
            // 章内还有 page
            if (pageIndex < ch.pages.lastIndex) {
                return ch.pages[pageIndex + 1]
            }
            // 章末 → 跨章取 nextChapter.pages[0]
            val next = dataSource.nextChapter ?: return EMPTY_PAGE
            return next.pages.firstOrNull() ?: EMPTY_PAGE
        }

    val nextPlusPage: ScrollPage
        get() {
            val ch = dataSource.currentChapter ?: return EMPTY_PAGE
            // 章内还有 ≥2 个 page
            if (pageIndex < ch.pages.lastIndex - 1) {
                return ch.pages[pageIndex + 2]
            }
            // 章倒数第 2 page → next chapter 的 page[0]
            if (pageIndex == ch.pages.lastIndex - 1) {
                val next = dataSource.nextChapter ?: return EMPTY_PAGE
                return next.pages.firstOrNull() ?: EMPTY_PAGE
            }
            // 章末 page → next chapter 的 page[1]
            val next = dataSource.nextChapter ?: return EMPTY_PAGE
            return next.pages.getOrElse(1) { EMPTY_PAGE }
        }

    val prevPage: ScrollPage
        get() {
            val ch = dataSource.currentChapter ?: return EMPTY_PAGE
            // 章内还有上一 page —— 加 getOrElse 防 layout 重排后 pageIndex 越界
            // （cache 版本 bump 后同章新 layout 行数不同 + 老 progress restore 出来的
            // pageIndex 可能超过新 pages.size，触发 IOOB）。失败兜底 EMPTY_PAGE
            // 让 reader 不 crash，下一次手势会触发 progress 自我修正。
            if (pageIndex > 0) {
                return ch.pages.getOrElse(pageIndex - 1) { EMPTY_PAGE }
            }
            // 章首 → 跨章取 prevChapter.pages.last
            val prev = dataSource.prevChapter ?: return EMPTY_PAGE
            return prev.pages.lastOrNull() ?: EMPTY_PAGE
        }

    // ── 边界判定 ──

    /**
     * 是否还能往上滚（向前一 page）。
     * 章内 pageIndex > 0 OR （章首但全书还有上一章 AND prevChapter 已加载就绪）。
     *
     * 注意：[ScrollChapterDataSource.hasPrevChapter] 与 [ScrollChapterDataSource.prevChapter]
     * 解耦：前者只判全书边界，后者判加载完成度。两者都 true 才允许跨章。
     */
    fun hasPrev(): Boolean {
        if (pageIndex > 0) return true
        return dataSource.hasPrevChapter() && dataSource.prevChapter != null
    }

    /**
     * 是否还能往下滚（向后一 page）。
     * 章内 pageIndex < lastIndex OR （章末但全书还有下一章 AND nextChapter 已加载就绪）。
     */
    fun hasNext(): Boolean {
        val ch = dataSource.currentChapter ?: return false
        if (pageIndex < ch.pages.lastIndex) return true
        return dataSource.hasNextChapter() && dataSource.nextChapter != null
    }

    /**
     * 是否还有 next 之后的 page（用于 3-pane 拼接判断 nextPlusPage 有效性）。
     * 章内 pageIndex < lastIndex - 1 OR 跨章后 nextChapter.pages.size > 1 也算。
     */
    fun hasNextPlus(): Boolean {
        val ch = dataSource.currentChapter ?: return false
        if (pageIndex < ch.pages.lastIndex - 1) return true  // 章内还有 ≥2 个
        if (pageIndex == ch.pages.lastIndex - 1) {
            // 章倒数第 2，next+ = next chapter 首 page
            return dataSource.hasNextChapter() && dataSource.nextChapter != null
        }
        // 章末 page，next+ = next chapter 第 2 page
        val next = dataSource.nextChapter ?: return false
        return next.pages.size > 1
    }

    // ── 移动方法 ──

    /**
     * 向后移动一 page。
     * @return true = 移动成功（章内 OR 跨章），false = 已到末章末页 / next 未加载
     */
    fun moveToNext(): Boolean {
        val ch = dataSource.currentChapter ?: return false
        // 章内：直接 pageIndex++
        if (pageIndex < ch.pages.lastIndex) {
            pageIndex++
            return true
        }
        // 章末：尝试跨章
        if (!dataSource.hasNextChapter()) return false  // 末章
        if (dataSource.nextChapter == null) return false  // next 加载中
        // 跨章 swap 由 Host 在 chapterShiftCallback 回调内完成（更新 dataSource state）。
        // Factory 这里先重设 pageIndex = 0，然后回调通知 Host。Host 同步 swap 后下次
        // currentChapter 访问就是新章了。
        // 注意：pageIndex 重设必须在 callback 之前，否则 Host 内 derivedStateOf 可能读到旧 pageIndex。
        pageIndex = 0
        chapterShiftCallback(+1)
        return true
    }

    /**
     * 向前移动一 page。
     * @return true = 移动成功（章内 OR 跨章），false = 已到首章首页 / prev 未加载
     */
    fun moveToPrev(): Boolean {
        if (dataSource.currentChapter == null) return false
        // 章内：直接 pageIndex--
        if (pageIndex > 0) {
            pageIndex--
            return true
        }
        // 章首：尝试跨章
        if (!dataSource.hasPrevChapter()) return false  // 首章
        val prev = dataSource.prevChapter ?: return false  // prev 加载中
        // pageIndex 重设为新章末页索引（先记住，再 callback；callback 内 Host 把 prev swap 到 cur）
        pageIndex = prev.pages.lastIndex.coerceAtLeast(0)
        chapterShiftCallback(-1)
        return true
    }

    /** 跳到当前章首 page。chapter prefetch / 外部跳章配套。 */
    fun moveToFirstPageOfChapter() {
        pageIndex = 0
    }

    /** 跳到当前章末 page。手势 PREV 跨章定位用（章末页 = 进入下一章的视觉连续点）。 */
    fun moveToLastPageOfChapter() {
        val ch = dataSource.currentChapter ?: run { pageIndex = 0; return }
        pageIndex = ch.pages.lastIndex.coerceAtLeast(0)
    }

    /**
     * 跳到当前章指定 page。restoreProgress / 书签 cp 定位用。
     * pageIdx 越界自动 coerce。
     */
    fun moveToPage(pageIdx: Int) {
        val ch = dataSource.currentChapter ?: run { pageIndex = 0; return }
        pageIndex = pageIdx.coerceIn(0, ch.pages.lastIndex.coerceAtLeast(0))
    }

    companion object {
        /** 占位 page（dataSource 加载中 / 越界时返回）。chapterIndex = -1 标识无效。 */
        val EMPTY_PAGE: ScrollPage = ScrollPage(
            pageIndex = 0,
            lines = emptyList(),
            height = 0f,
            chapterIndex = -1,
        )
    }
}
