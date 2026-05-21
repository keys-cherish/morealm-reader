package com.morealm.app.domain.render.layout

/**
 * 单字符级排版结果 —— 滚动 Canvas 引擎（ScrollLayoutEngine）的最小坐标单元。
 *
 * 为什么独立存在：高亮 / 选区 / 字体强调色 / 下划线全部按字符 offset 持久化在 DB
 * （`Highlight.startChapterPos` / `endChapterPos`，章内字符 offset），渲染时必须能从
 * offset 反查到「屏幕上的像素 rect」。`ScrollColumn.chapterPosition` 即反查桥梁。
 *
 * 与现有 [com.morealm.app.domain.render.ScrollParagraph] 的区别：旧路径用 LazyColumn
 * 段级 modifier 画高亮，单字符坐标隐藏在 Compose Text 内部，不暴露；新路径在 Canvas
 * drawText 前必须自持每字符的 (x, y, width) 才能精准画背景 / 下划线 / handle hit-test。
 *
 * 字段全 val，构造后不变；批量构造由 [ScrollLayoutEngine.layoutChapter] 一次完成。
 */
data class ScrollColumn(
    /**
     * 字符本身（String 而非 Char）—— 表达 surrogate pair / 表情等多 code unit 字符。
     *
     * 单 Char (UTF-16 code unit) 无法承载 U+10000 以上字符（表情符号、罕用汉字扩展）。
     * 与 [com.morealm.app.domain.render.TextMeasure.measureTextSplit] 返回的 String 列表
     * 直接对齐，1:1 拷贝无歧义。占位列（图片 / 装饰）此值约定为空串。
     */
    val charData: String,
    /** 字符左边界像素 x 坐标（相对所在 [ScrollLine] 起点，paddingLeft 之内）。 */
    val start: Float,
    /** 字符右边界像素 x 坐标。`width = end - start`。 */
    val end: Float,
    /**
     * 章内字符 offset（0-based，跨段累计）—— DB 高亮坐标键。
     *
     * 与 `Highlight.startChapterPos / endChapterPos` 严格 1:1 对齐，确保旧高亮数据
     * 在新引擎下反查无歧义。这是"实际逻辑语义"的核心约定，由 [ScrollLayoutEngine]
     * 统一管理 chapterPosition 递增规则：
     *
     * 1. **原文每个字符（含 CJK / 半角 / emoji 代理对）**：占 1 cp。
     * 2. **段末 `\n`（空段）**：占 1 cp，产生空 ScrollLine（columns 空）。空段
     *    在 [ScrollLine] 层面对应"一行空白"，用户选中即选中此 1 cp。
     * 3. **段首缩进**：作为排版属性（[ScrollLayoutEngine.paragraphIndent] 仅决定
     *    段首第一行 lineCursor 起点 = indentWidth），**不**生成 column、**不**占 cp。
     *    hit-test 命中缩进区域（x < line.columns.first().start）由
     *    [ScrollLayoutEngine.findColumnByPixel] 吸附到段首第一个真实字符。
     * 4. **图片占位**：占 1 cp，对应 ScrollLine 含 0 columns + height = 图片像素高
     *    （M1.5 实施时严格遵循）。
     * 5. **章首样式块**：章序号 + 主标题分别按字符占 cp，标题 cp 跟原文 chapter title
     *    严格 1:1（M1.4 实施时严格遵循）。
     */
    val chapterPosition: Int,
    /**
     * **P3-5b Step 2c char-level color**：本字符的 CSS color 覆盖（来自 `<span class="w-co1">字</span>`
     * 这种字符级颜色）。null = 用 line/paint 默认色。
     *
     * 渲染优先级：用户高亮 textColorByCp > col.colorArgb > line.blockStyle.textColor > paint 默认
     */
    val colorArgb: Int? = null,
) {
    val width: Float get() = end - start
}
