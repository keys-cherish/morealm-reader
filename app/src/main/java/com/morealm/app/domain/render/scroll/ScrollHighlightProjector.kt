package com.morealm.app.domain.render.scroll

import com.morealm.app.domain.entity.Highlight

/**
 * Highlight DB → [ScrollHighlightDrawSpec] 投影 —— 把章内 highlight 投影到 layout 坐标。
 *
 * 输入：layout (整章排版) + List<Highlight>（该书的所有 highlight）。
 * 输出：List<ScrollHighlightDrawSpec>，渲染层据此直接 drawRect / 替换 paint.color / 画下划线。
 *
 * 算法（O(H × P × L × C)，H=highlight 数，P=页数，L=每页行数，C=每行 column 数）：
 *   1. 过滤 chapterIndex 不匹配的 highlight
 *   2. 遍历 layout.pages，累计 pageOffsetY
 *   3. 每 line 检查 cp 范围是否与 highlight [startCp, endCp] 相交
 *   4. 相交时：
 *      - 空 line (空段/图片段，columns 空)：rect = 全行宽（0..viewWidth）
 *      - 非空 line：rect = 命中 column 区间的 (first.start, last.end)
 *   5. 同 highlight 跨多 rect 收集到一个 spec
 *
 * 不做：
 *   - 性能优化（大量 highlight 场景的 R-tree 加速等）。M6 性能压测时按需扩。
 *   - color blending / alpha 合并。渲染层各自画各自的。
 */
object ScrollHighlightProjector {

    /**
     * 投影章内所有 highlight 到 layout 坐标。
     *
     * @param layout 整章已排版结果
     * @param highlights 该书的全部 [Highlight]；本函数会过滤 chapterIndex 不匹配项
     * @return 当章内可绘制的 spec 列表（按 highlight 顺序，未排序）
     */
    fun project(
        layout: ScrollChapterLayout,
        highlights: List<Highlight>,
    ): List<ScrollHighlightDrawSpec> {
        val targets = highlights.filter { it.chapterIndex == layout.chapterIndex }
        if (targets.isEmpty()) return emptyList()

        return targets.mapNotNull { h ->
            val rects = collectRects(
                layout = layout,
                startCp = h.startChapterPos,
                endCp = h.endChapterPos,
                viewWidth = layout.viewWidth.toFloat(),
            )
            if (rects.isEmpty()) return@mapNotNull null  // 高亮范围全在不可见 cp（如段末 \n）跳过
            ScrollHighlightDrawSpec(
                highlightId = h.id,
                kind = h.kind,
                argb = h.colorArgb,
                underlineStyle = h.underlineStyle,
                cpRangeFirst = h.startChapterPos,
                cpRangeLast = h.endChapterPos,
                rects = rects,
            )
        }
    }

    /**
     * **page-level 投影**（Phase 4 新增）—— 把单 page 内的高亮投影到 page 坐标系。
     *
     * 与 [project] 的差异：
     * - 输入是单 [ScrollPage]，不是整章 layout
     * - 输出 spec.rects 的 top/bottom 是 **page-relative**（line.lineTop / lineBottom 直接用）
     * - 调用方传入已按 chapterIndex 过滤的 highlights（避免本函数重复过滤）
     *
     * 用于 page-level Renderer 的 PagePaneCanvas 直接消费。
     *
     * @param page 单 page 排版结果
     * @param chapterViewWidth 章节视口宽（用于空段 / 图片段 rect 取全行宽）
     * @param chapterHighlights 该章节已过滤的 highlights（不再按 chapterIndex 过滤）
     * @return 该 page 内可绘制的 spec 列表；rects 坐标相对 page 顶
     */
    fun projectForPage(
        page: ScrollPage,
        chapterViewWidth: Int,
        chapterHighlights: List<Highlight>,
    ): List<ScrollHighlightDrawSpec> {
        if (chapterHighlights.isEmpty()) return emptyList()
        if (page.lines.isEmpty()) return emptyList()

        // 计算 page cp 范围 [first, last]，提前过滤完全不在 page 的 highlight
        val pageFirstCp = page.lines.first().firstChapterPos
        val pageLastCp = page.lines.last().lastChapterPos

        val viewWidthF = chapterViewWidth.toFloat()
        return chapterHighlights.mapNotNull { h ->
            // 完全不相交跳过
            if (h.endChapterPos < pageFirstCp || h.startChapterPos > pageLastCp) return@mapNotNull null

            val rects = mutableListOf<ScrollHighlightRect>()
            for (line in page.lines) {
                if (line.lastChapterPos < h.startChapterPos || line.firstChapterPos > h.endChapterPos) continue
                val rectLeft: Float
                val rectRight: Float
                if (line.columns.isEmpty()) {
                    rectLeft = 0f
                    rectRight = viewWidthF
                } else {
                    val matched = line.columns.filter { it.chapterPosition in h.startChapterPos..h.endChapterPos }
                    if (matched.isEmpty()) continue
                    rectLeft = matched.first().start
                    rectRight = matched.last().end
                }
                rects.add(
                    ScrollHighlightRect(
                        pageIndex = page.pageIndex,
                        // page-relative：直接用 line.lineTop / lineBottom
                        top = line.lineTop,
                        bottom = line.lineBottom,
                        left = rectLeft,
                        right = rectRight,
                    ),
                )
            }
            if (rects.isEmpty()) return@mapNotNull null
            ScrollHighlightDrawSpec(
                highlightId = h.id,
                kind = h.kind,
                argb = h.colorArgb,
                underlineStyle = h.underlineStyle,
                cpRangeFirst = h.startChapterPos,
                cpRangeLast = h.endChapterPos,
                rects = rects,
            )
        }
    }

    /**
     * 收集 [startCp, endCp]（含起含止）覆盖的所有矩形。
     *
     * 空 line / 图片段 line 命中时 rect = 全行宽（left=0, right=viewWidth）—— 视觉上整行高亮。
     * 非空 line 命中时 rect = (line 内命中 column 首.start, 末.end)。
     */
    private fun collectRects(
        layout: ScrollChapterLayout,
        startCp: Int,
        endCp: Int,
        viewWidth: Float,
    ): List<ScrollHighlightRect> {
        if (startCp > endCp) return emptyList()
        val out = mutableListOf<ScrollHighlightRect>()
        var pageOffsetY = 0f
        for (page in layout.pages) {
            val pageTop = pageOffsetY
            for (line in page.lines) {
                // 行 cp 范围 [line.firstChapterPos, line.lastChapterPos]
                // 与高亮 cp 范围 [startCp, endCp] 不相交则跳过
                if (line.lastChapterPos < startCp || line.firstChapterPos > endCp) continue

                val rectLeft: Float
                val rectRight: Float
                if (line.columns.isEmpty()) {
                    // 空段 / 图片段命中：rect = 全行宽
                    rectLeft = 0f
                    rectRight = viewWidth
                } else {
                    // 非空 line：找命中 cp 的首末 column
                    val matched = line.columns.filter { it.chapterPosition in startCp..endCp }
                    if (matched.isEmpty()) continue  // 行 cp 范围与高亮相交但 column 都不在（理论上不该发生）
                    rectLeft = matched.first().start
                    rectRight = matched.last().end
                }
                out.add(
                    ScrollHighlightRect(
                        pageIndex = page.pageIndex,
                        top = pageTop + line.lineTop,
                        bottom = pageTop + line.lineBottom,
                        left = rectLeft,
                        right = rectRight,
                    ),
                )
            }
            pageOffsetY += page.height
        }
        return out
    }
}
