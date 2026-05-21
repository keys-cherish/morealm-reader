package com.morealm.app.domain.parser.epub

/**
 * EPUB 章节正文的**结构化中间表示** —— 走「自解析 HTML」新路径用，不再把 `<p>` / `<h1>` /
 * `<h2>` 等结构标签压扁成 `\n` 后丢失语义。
 *
 * ── 设计基调（Step 1，仅 EPUB） ──
 *
 *   1. **粒度**：块级（Paragraph / Heading / Image），不到字符行内样式
 *      （`<em>` / `<strong>` 等行内强调留给 Step 2 后续；Step 1 把它们文本累积进当前段）
 *   2. **不引入 paint / px / 行宽** —— 那些是排版层 (`ChapterProvider`) 的责任。本中间
 *      表示只携带「语义」，排版层据 [Heading.level] 决定字号 / 粗体 / 上下间距
 *   3. **不存原始 HTML** —— 排版层不应该再去解析 HTML。中间表示就是真值
 *   4. **图片块独立** —— EPUB 章节里 `<p><img/></p>` 这种「图片占整段」是常态；
 *      段中嵌入图片 `<p>text <img/> more</p>` 会被拆成 Paragraph("text") + Image + Paragraph("more")，
 *      Step 1 不做段内 inline 图片
 *
 * ── 与老 [com.morealm.app.domain.parser.EpubParser.readChapter] 路径的关系 ──
 *
 *   老路径输出 `String`，把 HTML 标签全部正则剥光 + `\n` 替换块标签；新路径平行存在，
 *   不动老路径。EPUB 阅读流程切换到新路径在 Step 3。
 */
sealed interface ChapterBlock {

    /**
     * 普通段落 —— `<p>` / `<div>` 文本内容 / 块容器里的纯文本。
     *
     * @property text 已 trim、去除连续空白后的段文本。空串段不会被生成（结构化器会过滤掉）
     */
    data class Paragraph(val text: String) : ChapterBlock

    /**
     * 多级标题 —— `<h1>` .. `<h6>`。
     *
     * 排版层（Step 3）根据 [level] 决定字号缩放（h1 最大 → h6 最小，常用阶 1.6/1.4/1.2/1.1/1.05/1.0）
     * 和粗体处理。
     *
     * 同章节里出现的「内嵌 h1」**不**与 `BookChapter.title` 合并 —— 排版层可以选择丢弃
     * 章首第一个 Heading（避免与外部目录标题重复），但那是排版层策略，不是中间表示职责。
     *
     * @property level 1..6，超界数值会被结构化器 coerce 到合法范围
     * @property text 标题文本，已 trim
     */
    data class Heading(val level: Int, val text: String) : ChapterBlock {
        init {
            require(level in 1..6) { "heading level must be 1..6, was $level" }
        }
    }

    /**
     * 图片块 —— `<img src="...">` 已被 [com.morealm.app.domain.parser.EpubParser.processContent]
     * 改写为 `file://...` 绝对路径（指向 epub_chapters_v3 缓存目录里抽出的图片）。
     *
     * @property src 改写后的 `file://...` 绝对路径，或老 src（如果改写失败）；空串不会生成块
     */
    data class Image(val src: String) : ChapterBlock
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
     * 调用方（[com.morealm.app.domain.parser.LocalBookParser]）可据此显示「（本章暂无内容）」占位。
     */
    fun isEmpty(): Boolean = blocks.isEmpty() || blocks.all {
        when (it) {
            is ChapterBlock.Paragraph -> it.text.isBlank()
            is ChapterBlock.Heading -> it.text.isBlank()
            is ChapterBlock.Image -> it.src.isBlank()
        }
    }

    /** 章节累计字符数（标题 + 段落文本，不含图片）—— 给进度估算用。 */
    val totalChars: Int by lazy {
        blocks.sumOf {
            when (it) {
                is ChapterBlock.Paragraph -> it.text.length
                is ChapterBlock.Heading -> it.text.length
                is ChapterBlock.Image -> 0
            }
        }
    }

    /**
     * 把结构化块列表展平成与老 [com.morealm.app.domain.parser.EpubParser.formatKeepImg]
     * 输出兼容的纯文本 + `<img src>` 字符串 —— L1.5 桥接路径用：让 streaming 解析的
     * 结果能直接喂给当前 reader 字符串排版层（30+ 渲染文件不动）。
     *
     * 输出契约：
     *  - 每个块一行，行间用 `\n` 分隔（与 formatKeepImg 单 \n 段距对齐）
     *  - Heading / Paragraph 直接吐文本（Heading 不加任何前缀 —— 老 reader 字符串排版
     *    本来就无法区分；L2 之后渲染层接 ChapterBlock 才能用 Heading.level 改字号）
     *  - Image 输出 `<img src="...">`，老 formatImageRegex 能识别 + 单独渲染图块
     *  - 全局 trim 去掉首尾空白
     */
    fun flattenToString(): String {
        if (blocks.isEmpty()) return ""
        val sb = StringBuilder()
        for (block in blocks) {
            val line = when (block) {
                is ChapterBlock.Heading -> block.text
                is ChapterBlock.Paragraph -> block.text
                is ChapterBlock.Image -> "<img src=\"${block.src}\">"
            }
            if (line.isEmpty()) continue
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(line)
        }
        return sb.toString()
    }
}
