package com.morealm.app.domain.render.layout

/**
 * 单页排版结果 —— [ScrollChapterLayout] 的子结构。
 *
 * 设计动机（为什么不是「整章一个超长 Canvas」）：
 *   ChapterPaneCanvas 渲染时按 page 做视口剔除（只画 viewport ∩ page 非空的页），
 *   避免一次 drawScope 内提交几万行字符的 drawText 调用。
 *
 *   另外章节内增量 reflow（commit 字号 / 行距）可以按 page 局部失效重排，不必整章重测。
 *   (M5 阶段做)
 *
 * 一页 = N 行，行间已按 lineTop 升序；页高度 = lines.lastOrNull()?.lineBottom ?: 0。
 */
data class ScrollPage(
    /** 页序号（0-based，章内连续）。pixelOffset 跨页定位用。 */
    val pageIndex: Int,
    /** 行列表，按 [ScrollLine.lineTop] 升序。 */
    val lines: List<ScrollLine>,
    /** 页总高度像素（含 paddingTop/Bottom）；放置时直接累加为 [ScrollChapterLayout.totalHeight]。 */
    val height: Float,
    /** 所属章 idx —— 跨章拼装时识别归属，调试 / 进度上报用。 */
    val chapterIndex: Int,
)
