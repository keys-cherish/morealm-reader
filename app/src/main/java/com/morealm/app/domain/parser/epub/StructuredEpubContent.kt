package com.morealm.app.domain.parser.epub

/**
 * EPUB 章节正文的**结构化中间表示** —— 走自研流式解析路径，不再把 `<p>` / `<h1>` /
 * `<h2>` 等结构标签压扁成 `\n` 后丢失语义。
 *
 * ── 设计基调 ──
 *
 *   1. **块级粒度**：[ChapterBlock.Paragraph] / [ChapterBlock.Heading] /
 *      [ChapterBlock.RichText] / [ChapterBlock.Image] —— 块级表达段落 / 标题 / 富文本段 /
 *      图片；字符级 styling 由 [RichText] 内含的 [StyledSpan] 携带
 *   2. **不引入 paint / px / 行宽** —— 那些是排版层 (`ChapterProvider`) 的责任。本中间
 *      表示只携带「语义」+「CSS resolved styling」，排版层据 [Heading.level] / [StyledSpan.color] /
 *      [StyledSpan.sizeScale] 等决定字号 / 颜色 / 上下间距
 *   3. **不存原始 HTML** —— 排版层不应该再去解析 HTML。中间表示就是真值
 *   4. **图片块独立** —— EPUB 章节里 `<p><img/></p>` 这种「图片占整段」是常态；
 *      P2 暂不支持段内 inline 图片（留 P3 box model）
 *
 * ── 与 CSS cascade 的关系 ──
 *
 *   字符级 styling（`<span style="color:red">` / `<em>`）由 epub-core
 *   [com.morealm.epub.css.StyleResolver] 算出每个节点的 [ResolvedStyles]，
 *   再由 [com.morealm.app.domain.parser.epub.streaming.ChapterBlockBuilder]
 *   转换成 [StyledSpan]（P2.2 工作）。
 */
sealed interface ChapterBlock {

    /**
     * 普通段落 —— `<p>` / `<div>` 文本内容 / 块容器里的纯文本，**无字符级样式**。
     *
     * P2.2 BlockVisitor 接 CssParser 前：所有段落都 emit Paragraph。
     * P2.2 之后：仅当段落内部所有字符 styling 一致（color/sizeScale/weight 都
     * 是父继承值）时 emit Paragraph，否则 emit [RichText]。
     *
     * @property text 已 trim、去除连续空白后的段文本。空串段不会被生成
     */
    data class Paragraph(val text: String) : ChapterBlock

    /**
     * 富文本段落 —— 携带字符级 styling（color / sizeScale / weight / italic）。
     *
     * 用于「为」黑「美」粉「好」橙这种字符级单字彩色 cover/title 排版 —— 解析后 emit:
     * ```
     * RichText(spans = listOf(
     *     StyledSpan("为", color = ARGB_BLACK),
     *     StyledSpan("美", color = ARGB_PINK),
     *     StyledSpan("好", color = ARGB_ORANGE),
     *     StyledSpan("的", color = ARGB_BLACK),
     * ))
     * ```
     *
     * @property spans 段内的 styled 字符片段序列；按 DOM 序排列。
     *   全部 [StyledSpan.isPlain] 时建议降级为 [Paragraph] 减少下游开销
     */
    data class RichText(val spans: List<StyledSpan>) : ChapterBlock

    /**
     * 多级标题 —— `<h1>` .. `<h6>`。
     *
     * 排版层根据 [level] 决定字号缩放（h1 最大 → h6 最小，常用阶 1.6/1.4/1.2/1.1/1.05/1.0）
     * 和粗体处理。
     *
     * P2.1 暂保留 text: String —— P2.2 BlockVisitor 接 CssParser 后再决定是否升级
     * 为 spans（标题字符级彩色场景罕见，目前不优先）。
     *
     * @property level 1..6
     * @property text 标题文本，已 trim
     */
    data class Heading(val level: Int, val text: String) : ChapterBlock {
        init {
            require(level in 1..6) { "heading level must be 1..6, was $level" }
        }
    }

    /**
     * 图片块 —— `<img src="...">` 已被 ImgRewriteVisitor 改写为 `file://...` 绝对路径。
     *
     * @property src 改写后的 `file://...` 绝对路径，或老 src（如果改写失败）；空串不会生成块
     */
    data class Image(val src: String) : ChapterBlock
}

/**
 * 字符级 styling 片段。块内 styling 一致的连续字符聚合成一个 [StyledSpan]。
 *
 * 设计要点：
 *  - **所有字段 null/默认值 = 继承父元素 / 排版层默认**，不强制每个 span 重复声明
 *  - **颜色用 ARGB Int**，与 epub-core [com.morealm.epub.css.Color.parse] 输出对齐
 *  - **sizeScale 是相对值**（CSS `font-size:1.5em` → 1.5f），让排版层乘以基础字号
 *    （用户在 ReadingSettings 调过的字号 × span 的 sizeScale = 最终像素）
 *  - **不携带 background-color / border / padding 等 box model 属性** —— 那些是 P3
 *    [PositionedBlock] / box model 引入时再扩
 *
 * @property text 该 span 的字符内容（不可为空，空 span 不应被生成）
 * @property color 前景色 ARGB；null = 继承父元素 / 排版层默认色
 * @property sizeScale 字号倍率；1f = 与父元素同字号
 * @property weight CSS font-weight 数值（100..900，常见 400/700）；null = 继承
 * @property italic 是否斜体；false = 正体
 */
data class StyledSpan(
    val text: String,
    val color: Int? = null,
    val sizeScale: Float = 1f,
    val weight: Int? = null,
    val italic: Boolean = false,
) {
    /** 无任何 styling 覆写 —— 完全等价于父元素继承值。可降级为 [ChapterBlock.Paragraph] 文本。 */
    val isPlain: Boolean
        get() = color == null && sizeScale == 1f && weight == null && !italic
}

/**
 * 一章正文的结构化内容。
 *
 * 通过 [com.morealm.app.domain.parser.epub.streaming.StreamingChapterReader] 构造，
 * 由排版层（Step 3 起，[com.morealm.app.domain.render.ChapterProvider]）消费。
 */
data class StructuredChapterContent(val blocks: List<ChapterBlock>) {

    /**
     * 章节是否为「空」（无任何非空白文本 / 图片）。
     *
     * 当解析失败 / 拿到的 XHTML 完全只是装饰节点（纯 SVG 装饰图 / 空 div 等）时返回 true。
     * 调用方可据此显示「（本章暂无内容）」占位。
     */
    fun isEmpty(): Boolean = blocks.isEmpty() || blocks.all { it.isBlankBlock() }

    /** 章节累计字符数（标题 + 段落文本，不含图片）—— 给进度估算用。 */
    val totalChars: Int by lazy {
        blocks.sumOf {
            when (it) {
                is ChapterBlock.Paragraph -> it.text.length
                is ChapterBlock.RichText -> it.spans.sumOf { s -> s.text.length }
                is ChapterBlock.Heading -> it.text.length
                is ChapterBlock.Image -> 0
            }
        }
    }

    /**
     * 把结构化块列表展平成与老 [com.morealm.app.domain.parser.EpubParser] `formatKeepImg`
     * 输出兼容的纯文本 + `<img src>` 字符串 —— L1.5 桥接路径用：让 streaming 解析的
     * 结果能直接喂给当前 reader 字符串排版层（渲染层 30+ 文件不动）。
     *
     * 输出契约：
     *  - 每个块一行，行间用 `\n` 分隔（与 formatKeepImg 单 \n 段距对齐）
     *  - [ChapterBlock.RichText] 把所有 span text 串接（**字符级 styling 在此被丢弃**），
     *    要保留 styling 必须走 P2.3 渲染层 AnnotatedString 路径
     *  - Heading / Paragraph 直接吐文本
     *  - Image 输出 `<img src="...">`，老 formatImageRegex 能识别 + 单独渲染图块
     */
    fun flattenToString(): String {
        if (blocks.isEmpty()) return ""
        val sb = StringBuilder()
        for (block in blocks) {
            val line = when (block) {
                is ChapterBlock.Heading -> block.text
                is ChapterBlock.Paragraph -> block.text
                is ChapterBlock.RichText -> block.spans.joinToString("") { it.text }
                is ChapterBlock.Image -> "<img src=\"${block.src}\">"
            }
            if (line.isEmpty()) continue
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(line)
        }
        return sb.toString()
    }

    private fun ChapterBlock.isBlankBlock(): Boolean = when (this) {
        is ChapterBlock.Paragraph -> text.isBlank()
        is ChapterBlock.RichText -> spans.all { it.text.isBlank() }
        is ChapterBlock.Heading -> text.isBlank()
        is ChapterBlock.Image -> src.isBlank()
    }
}
