package com.morealm.app.domain.render.layout

/**
 * page-level 滚动模式的章节数据源 —— [ScrollPageFactory] 通过本接口取 prev/cur/next
 * 三章 [ScrollChapterLayout]，跨章 swap 时依据返回的引用做 page 序列拼接。
 *
 * 由 [com.morealm.app.ui.reader.renderer.scroll.ScrollCanvasReaderHost] 实现，
 * 内部持有 ViewModel 的章节加载 state（Compose mutableState）。
 *
 * 设计参考 Legado [外部开源阅读器实现]，差异：
 * - 去掉 `pageIndex` getter：本工程改由 [ScrollPageFactory.pageIndex] 维护，
 *   不依赖全局 ReadBook 单例
 * - 去掉 `isScroll`：本数据源专服务 scroll 模式，恒 true
 * - 去掉 `upContent(...)` UI 回调：Compose state 重组自动驱动，不需要 imperative 通知
 */
interface ScrollChapterDataSource {
    /** 当前章 layout。null = 加载中 / 未就绪。Factory 此时所有 move 返回 false。 */
    val currentChapter: ScrollChapterLayout?

    /** 上一章 layout。null = 首章 OR 加载中。 */
    val prevChapter: ScrollChapterLayout?

    /** 下一章 layout。null = 末章 OR 加载中。 */
    val nextChapter: ScrollChapterLayout?

    /** 全书是否还有上一章（chapterIndex > 0）。与 [prevChapter] 解耦：可能 true 但 prev 仍加载中。 */
    fun hasPrevChapter(): Boolean

    /** 全书是否还有下一章（chapterIndex < chapterCount - 1）。同上解耦语义。 */
    fun hasNextChapter(): Boolean
}
