package com.morealm.app.domain.render.layout

/**
 * 高亮绘制规格 —— [ScrollHighlightProjector] 把 DB [com.morealm.app.domain.entity.Highlight]
 * 投影到 [ScrollChapterLayout] 得到的可绘制结果。
 *
 * 一个 Highlight（含 startChapterPos..endChapterPos）可能跨越多行 / 多页，
 * [rects] 列表枚举所有受影响的矩形（同页同行 1 矩形；跨行 N 矩形）。
 *
 * 渲染层（[com.morealm.app.ui.reader.renderer.scroll.ChapterPaneCanvas]）三层绘制：
 *   - **kind=KIND_BACKGROUND**：在文字下方按 rect 画背景色（先画，后画文字盖上）
 *   - **kind=KIND_TEXT_COLOR**：文字绘制时 paint.color 替换为 argb（按 cp 命中识别）
 *   - **kind=KIND_UNDERLINE**：在 rect.bottom 下方画线（按 underlineStyle 切换实/虚/点/波浪）
 */
data class ScrollHighlightDrawSpec(
    /** 对应 DB [com.morealm.app.domain.entity.Highlight.id]。 */
    val highlightId: String,
    /** 0=KIND_BACKGROUND, 1=KIND_TEXT_COLOR, 2=KIND_UNDERLINE。见 Highlight.KIND_*。 */
    val kind: Int,
    /** 高亮色 ARGB。kind=BG 用作背景填充色；kind=TEXT_COLOR 用作字体前景色；kind=UNDERLINE 用作线色。 */
    val argb: Int,
    /** 下划线线型（仅 kind=UNDERLINE 有意义）：0=实线 / 1=虚线 / 2=点线 / 3=波浪。 */
    val underlineStyle: Int,
    /** 高亮覆盖的章内 cp 范围（含起含止）—— kind=TEXT_COLOR 时按此识别哪些 column 需替换 paint.color。 */
    val cpRangeFirst: Int,
    val cpRangeLast: Int,
    /** 高亮跨越的所有矩形（按 page → line 顺序）。 */
    val rects: List<ScrollHighlightRect>,
)

/**
 * 高亮覆盖的单个矩形 —— 同页同行内 highlight 起止 column 之间的连续区域。
 *
 * y 坐标含**章内累计 page offset**：top/bottom 是相对**章顶**而非相对 page 顶的 y，
 * 渲染层直接 drawRect(left, top, right, bottom) 即可，无需再算 pageOffsetY。
 */
data class ScrollHighlightRect(
    /** 矩形所属 page 的 pageIndex（调试用）。 */
    val pageIndex: Int,
    /** 矩形顶 y（相对章顶，已含 pageOffsetY）。 */
    val top: Float,
    /** 矩形底 y（相对章顶）。 */
    val bottom: Float,
    /** 矩形左 x（line.columns 中被覆盖首列的 start；空 line/图片段时 = 0）。 */
    val left: Float,
    /** 矩形右 x（line.columns 中被覆盖末列的 end；空 line/图片段时 = viewWidth）。 */
    val right: Float,
)
