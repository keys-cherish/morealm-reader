package com.morealm.app.domain.render.layout

/**
 * [ScrollLine] → List<[Atom]> 转换工具（A2 起 forward only）。
 *
 * **Phase 3 过渡期定位**：旧排版引擎 [ScrollLayoutEngine] 产出 ScrollLine（字符级
 * `columns` + line-level `isImage/imageSrc`），新原子化排版会直接产出 List<Atom>。
 * 这层 bridge 让两条路径并存到 A6 完成才下线 ScrollColumn。
 *
 * **当前限制（A2 阶段不解决）**：现行 ScrollLine 只能承载"整行文本"或"整行图片"
 * 二选一，**不支持 inline text + image mix**。本 bridge 因此只产出**单 atom line**：
 *  - 图片行（[ScrollLine.isImage] = true）→ 1 个 [InlineImage]
 *  - 文本行 → 1 个 [TextRun]（含整行文字）
 *  - 空段行（无 columns 且非图片）→ empty list
 *
 * 真正的 inline mix 要等 A3 起重写 [ScrollLayoutEngine] 让 emit 出来的 line 直接
 * 携带 atoms 字段。本 bridge 是过渡期的**只读视图**：让 Atom-aware 的下游组件能在
 * ScrollLine 还在的时候就开工。
 *
 * **反向（List<Atom> → ScrollLine）暂不实现**：每个 atom 转回 ScrollColumn 需要
 * 逐字符 measureText 还原 start/end，依赖 [com.morealm.app.domain.render.TextMeasure]
 * 上下文，跟数据类型放一起会循环依赖。等 A3 真要 emit 双轨结果时再加。
 */
object ScrollAtomBridge {

    /**
     * 把一条 [ScrollLine] 视图化为 [Atom] 列表。零拷贝意图：单元素 list 直接 `listOf`
     * 包，不复制字符串内容（[TextRun.text] 直接拿 line columns 拼出的 string，
     * 长度为该 line 字符数）。
     *
     * @return 1 个 atom 的 list（文本行/图片行）或空 list（空段行）
     */
    fun toAtoms(line: ScrollLine): List<Atom> {
        if (line.isImage) {
            val src = line.imageSrc ?: return emptyList()
            // 图片行的 ScrollColumn 列表通常为空；用 line 自身的 height 作图片高，
            // width 取 columns 最右端（若 emit 时记录了图片宽度作占位列）或回退 0。
            val width = line.columns.maxOfOrNull { it.end } ?: 0f
            return listOf(InlineImage(src = src, width = width, height = line.height))
        }
        if (line.columns.isEmpty()) return emptyList()  // 空段 line
        val text = line.columns.joinToString("") { it.charData }
        val width = line.columns.last().end - line.columns.first().start
        // baseline 用行高的近似值（80%）—— A3 起 ScrollLayoutEngine 改写时会从
        // FontMetrics.ascent 算出真值传上来。
        val baseline = line.height * 0.8f
        return listOf(
            TextRun(
                text = text,
                colorArgb = line.blockStyle.textColor,
                width = width,
                height = line.height,
                baseline = baseline,
            ),
        )
    }

    /**
     * 一条 line 的总 cpCount —— 等价于 `toAtoms(line).sumOf { it.cpCount }` 但不分配
     * list。给频繁调用的 cp 索引场景（hit-test、selection 边界判定）用。
     */
    fun cpCountOf(line: ScrollLine): Int = when {
        line.isImage -> 1
        line.columns.isEmpty() -> 0  // 空段 line（但 line.firstChapterPos/lastChapterPos 仍占 1 cp，由 caller 处理）
        else -> line.columns.size
    }
}
