package com.morealm.app.domain.render.layout

/**
 * 反查结果 —— [ScrollLayoutEngine.findColumnAt] / [ScrollLayoutEngine.findColumnByPixel]
 * 返回值。
 *
 * 用 data class 而不是 [Triple]：字段顺序记忆负担大，命名清晰更安全。
 *
 * [column] 可空：空段 / 图片段的 ScrollLine 命中时无具体字符 column，调用方需根据
 * `line` 自行画整行 rect（高亮）/ 整行 handle（选区）。
 */
data class ScrollHitResult(
    /** 命中所在页。 */
    val page: ScrollPage,
    /** 命中所在行。空段 / 图片段时该行 columns 为空。 */
    val line: ScrollLine,
    /**
     * 命中的字符 column；空段 / 图片段 line 时为 null。
     * 调用方典型用法：
     * ```
     * val rect = if (hit.column != null) {
     *     // 单字符精准 rect
     *     Rect(hit.column.start, hit.line.lineTop, hit.column.end, hit.line.lineBottom)
     * } else {
     *     // 整行 rect（空段 / 图片占位）
     *     Rect(0f, hit.line.lineTop, visibleWidth.toFloat(), hit.line.lineBottom)
     * }
     * ```
     */
    val column: ScrollColumn?,
)
