package com.morealm.app.domain.render.scroll

/**
 * [ScrollChapterLayout] 反查工具 —— extension 函数让 UI 层不耦合 [ScrollLayoutEngine]
 * 实例即可调用反查。原 engine 内 findColumnAt / findColumnByPixel delegate 到这里
 * 保持 API 向后兼容。
 *
 * 设计动机：M4 选区 / handle drag 需在 UI 层（ScrollSelectionOverlay）调反查，
 * 但 ScrollLayoutEngine 是 domain 层有状态的实例（含 paint / 排版参数），UI 持其
 * 引用会反向耦合渲染层到排版引擎，破坏分层。Extension 函数把"layout 上的纯查询"
 * 提到独立单元，无依赖。
 */

/**
 * 反查：给定章内字符 offset，找到承载该字符的 [ScrollHitResult]。
 *
 * 算法：线性扫 page→line（O(N×M)），命中 line 后在 columns 内找精确 column。
 * 性能：page 数 < 50，line 数 < 30/page，平均 < 0.1ms。
 *
 * @return 命中 [ScrollHitResult]：
 *   - 文本行命中：column 非 null
 *   - 空段 / 图片段 line 命中：column = null（整行 rect 由调用方处理）
 *   越界（cp < 0 或 cp >= totalCharCount）返 null
 */
fun ScrollChapterLayout.findColumnAt(chapterPosition: Int): ScrollHitResult? {
    if (chapterPosition < 0 || chapterPosition >= totalCharCount) return null
    for (page in pages) {
        for (line in page.lines) {
            if (line.containsChapterPos(chapterPosition)) {
                val column = line.columns.firstOrNull { it.chapterPosition == chapterPosition }
                return ScrollHitResult(page, line, column)
            }
        }
    }
    return null
}

/**
 * 反查反向：给定屏幕坐标（相对章顶 0..[ScrollChapterLayout.totalHeight]），找到承载
 * 该坐标的 [ScrollHitResult]。
 *
 * 吸附规则（用户决策 2026-05-17，"不让用户觉得点在了空气上"）：
 *
 * **纵向（y）**：
 * - y 落在 line.lineTop..lineBottom → 命中该 line
 * - y 在 line 间空白 → 吸附到最近 line（midY 距离最小）
 * - y 在 page padding 区 → 吸附到该 page 首/末 line
 * - y 越界（< 0 或 > totalHeight） → null
 *
 * **横向（x）**：
 * - 命中 line 空段 / 图片段 → column = null
 * - x < line.columns.first().start → 吸附 first column
 * - x >= line.columns.last().end → 吸附 last column
 * - x 落在某 column.start..end → 命中该 column
 */
/**
 * 提取 cp 范围内的连续文本（按 column 顺序拼接 charData）。
 *
 * 用于：选区菜单复制 / 翻译 / 高亮存库 content 字段等。
 * 复杂度 O(N + selection_size)：page 级 + line 级用 firstChapterPos/lastChapterPos 剪枝，
 * line.firstChapterPos > cpRange.last 时整章遍历早退。
 *
 * @param cpRange 选区 chapter position 范围（含起含止）
 * @return 拼接文本；cpRange 内全是空段 / 段末 \n / 图片占位则返空串
 */
fun ScrollChapterLayout.extractText(cpRange: IntRange): String {
    val sb = StringBuilder()
    outer@ for (page in pages) {
        for (line in page.lines) {
            if (line.lastChapterPos < cpRange.first) continue
            if (line.firstChapterPos > cpRange.last) break@outer
            for (col in line.columns) {
                if (col.chapterPosition < cpRange.first) continue
                if (col.chapterPosition > cpRange.last) break@outer
                sb.append(col.charData)
            }
        }
    }
    return sb.toString()
}

fun ScrollChapterLayout.findColumnByPixel(x: Float, yWithinChapter: Float): ScrollHitResult? {
    if (yWithinChapter < 0f || yWithinChapter > totalHeight) return null

    var pageOffsetY = 0f
    var matchedPage: ScrollPage? = null
    var yInPage = 0f
    for (page in pages) {
        val pageTop = pageOffsetY
        val pageBottom = pageOffsetY + page.height
        if (yWithinChapter in pageTop..pageBottom) {
            matchedPage = page
            yInPage = yWithinChapter - pageTop
            break
        }
        pageOffsetY = pageBottom
    }
    val page = matchedPage ?: return null
    if (page.lines.isEmpty()) return null

    val matchedLine = page.lines.firstOrNull { yInPage in it.lineTop..it.lineBottom }
    val line = matchedLine ?: page.lines.minBy { l ->
        val midY = (l.lineTop + l.lineBottom) / 2f
        kotlin.math.abs(yInPage - midY)
    }

    val column: ScrollColumn? = when {
        line.columns.isEmpty() -> null
        x < line.columns.first().start -> line.columns.first()
        // 严格大于：边界 x == last.end 时优先走 in-column 匹配（用 0 宽 column 退化场景
        // 之前用 >= 会把所有 col.start=col.end=0 的 column 吸到末位 → 测试失败）
        x > line.columns.last().end -> line.columns.last()
        else -> line.columns.firstOrNull { x in it.start..it.end }
            ?: line.columns.last()
    }

    return ScrollHitResult(page, line, column)
}
