package com.morealm.app.domain.render.scroll

/**
 * 单行排版结果 —— [ScrollPage] 的子结构。
 *
 * 一行 = N 个 [ScrollColumn]（横排时按 x 升序，整体 y 范围 [lineTop, lineBottom]）。
 *
 * 字段语义严格区分行内绘制坐标（lineTop/lineBottom）与逻辑归属（paragraphNum / isTitle）。
 * 高亮反查时按 chapterPosition 找到 column → 拿 line 的 lineTop/lineBottom 作为 rect 的 y 边界。
 */
data class ScrollLine(
    /** 行内字符列表，按 [ScrollColumn.start] 升序。空行（空段 / 图片段）为空 list。 */
    val columns: List<ScrollColumn>,
    /** 行顶 y 像素（相对所在 [ScrollPage]）。 */
    val lineTop: Float,
    /** 行底 y 像素。`height = lineBottom - lineTop`。含 lineSpacingExtra 影响。 */
    val lineBottom: Float,
    /**
     * 段编号（1-based，对齐 [com.morealm.app.domain.render.ScrollParagraph.paragraphNum]
     * 语义保持一致）。同一段内多行共享相同 paragraphNum；章首块独占段号 1。
     */
    val paragraphNum: Int,
    /** true=章节标题行（含章首块的章序号 / 主标题）；false=正文行。绘制 paint 选择依据。 */
    val isTitle: Boolean,
    /**
     * true=章序号小字行（"第N章" 用 [ScrollLayoutEngine.chapterNumPaint]）；
     * false=主标题 / 正文（用 titlePaint / contentPaint）。
     *
     * 仅当 [isTitle] = true 时此字段才有意义。渲染层 paint 选择规则：
     *   - isTitle && isChapterNum → chapterNumPaint（橙色小字）
     *   - isTitle && !isChapterNum → titlePaint（主色大字）
     *   - !isTitle → contentPaint（正文）
     */
    val isChapterNum: Boolean = false,
    /**
     * true=章首块最后一行 —— 渲染层据此画装饰横条（accent bar），视觉区分章首块与正文。
     * 仅在章首块末行（title 末行；无 title 时是 chapter-num 末行）为 true。
     */
    val isTitleEnd: Boolean = false,
    /**
     * true=图片占位行 —— 渲染层据此从 [imageSrc] 异步加载 bitmap，按 lineTop / lineBottom
     * 给定的像素范围绘制。column 列表为空。
     *
     * cp 占用规则（与旧 [com.morealm.app.domain.render.ChapterProvider.setTypeImage] 严格对齐）：
     * 每个图片占 1 cp（对应旧引擎 stringBuilder.append(" ") 占位字符），
     * firstChapterPos = lastChapterPos = 该图占的那 1 cp。
     */
    val isImage: Boolean = false,
    /**
     * 图片资源标识 —— 协议形式（如 `file:///...`、`mobi-img://...`、`http(s)://...`），
     * 由上层 contentProcessor 嵌入 `<img src="...">` 占位标记内，本引擎仅原样保存供渲染层
     * 异步加载使用。null = 非图片行。
     */
    val imageSrc: String? = null,
    /**
     * 行内整文本（含全角空格 / 标点，由 [ScrollLayoutEngine] 决定是否含末尾对齐填充）。
     * 跟 `columns.map { it.charData }.joinToString("")` 在大多数行下等价，但章首块 /
     * 图片段例外 —— 文本以 `text` 为权威，columns 为坐标权威。
     */
    val text: String,
    /**
     * 行覆盖的 chapterPosition 范围（含起含止）—— 反查工具
     * [ScrollLayoutEngine.findColumnAt] / [ScrollLayoutEngine.findColumnByPixel] 用。
     *
     * 设计动机：空段 / 图片段 line 的 [columns] 为空，但**该行仍占据 chapterPosition**
     * （空段占 1 cp、图片段占 1 cp）。仅靠 columns 无法反推 line 的 cp 归属——必须
     * 在 emit 阶段由 [ScrollLayoutEngine] 显式记录范围。
     *
     * 三种 line 类型的 firstChapterPos / lastChapterPos 约定：
     * - **非空文本行**：firstChapterPos = columns.first().chapterPosition,
     *   lastChapterPos = columns.last().chapterPosition
     * - **空段 line**：firstChapterPos = lastChapterPos = 该空段占的那 1 cp
     * - **图片段 line**：firstChapterPos = lastChapterPos = 该图片占的那 1 cp
     */
    val firstChapterPos: Int,
    /** 见 [firstChapterPos]。 */
    val lastChapterPos: Int,
) {
    val height: Float get() = lineBottom - lineTop

    /** 该行是否承载某 chapterPosition（含起含止）。反查时常用。 */
    fun containsChapterPos(cp: Int): Boolean = cp in firstChapterPos..lastChapterPos
}
