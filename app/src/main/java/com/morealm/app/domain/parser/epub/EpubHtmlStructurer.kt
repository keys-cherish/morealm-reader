package com.morealm.app.domain.parser.epub

import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/**
 * 把 EPUB 章节 XHTML 解析成 [StructuredChapterContent] —— 「自解析 HTML」新路径核心算法。
 *
 * ── 老 vs 新 ──
 *
 *   老 [com.morealm.app.domain.parser.EpubParser.formatHtml]：
 *     - 正则 `</?(?!img)...>` 删所有非 img 标签
 *     - 块标签 `<p>` / `<h1>` / `<div>` / `<blockquote>` 全部 `\n` 替换
 *     - 结果：纯文本 + `<img>` 串，**标题与正文无法区分**
 *
 *   新 [structuredFromBody]：
 *     - 用 jsoup AST 遍历，根据 tag 语义产出 [ChapterBlock]
 *     - 标题 (h1..h6)、段落 (p)、图片 (img) 各自成块
 *     - 内联强调标签 (em/strong/span) 退化为段内文本（Step 1 不做行内样式，留 Step 2）
 *
 * ── 块切分规则 ──
 *
 *   **块级标签**：h1-h6 / p / img / br / 容器 (div, section, article, blockquote, ...)
 *     - 命中块级 tag 时先 flush 当前累积 Paragraph，再生成对应 ChapterBlock
 *   **行内标签**：em / strong / span / a / 等
 *     - 不切块，子节点文本继续累积到当前段
 *   **文本节点**：累积到当前 Paragraph 文本 buffer
 *
 *   `<p>text <img/> more</p>` 拆成 3 块：Paragraph(text) + Image + Paragraph(more)。
 *   Step 1 不支持段内嵌入图片；Step 2 之后排版层支持后再合回。
 *
 * ── 为什么不抽专门的 visitor 类 ──
 *
 *   状态简单（一个段 buffer + 一个 blocks list），用闭包内 ArrayList + StringBuilder 比
 *   定义 NodeVisitor 子类直观，且无需 try-finally 清理。性能等价。
 */
object EpubHtmlStructurer {

    /**
     * 已知"容器型"块级 tag —— 遇到时递归处理子节点，flush 段 buffer，但**不**产出独立块。
     *
     * 列表标签 (ul/ol/li) Step 1 当作容器，列表项的文本会被并入一个 Paragraph
     * （或多个 Paragraph，按内部 p 切分）。Step 2 之后扩 ChapterBlock 加 ListItem 类型。
     */
    private val CONTAINER_TAGS = setOf(
        "body", "div", "section", "article", "main",
        "blockquote", "figure", "figcaption",
        "header", "footer", "nav", "aside",
        "ul", "ol", "li",
        "table", "thead", "tbody", "tfoot", "tr", "td", "th",
    )

    /**
     * 已知"段级"块标签 —— flush 现有段 buffer 后单独成段。
     *
     * 注意 `<p>` 处理走特殊路径（先递归子节点让 img 独立成块），见 [emitBlocks] 内部。
     */
    private val HEADING_TAGS = setOf("h1", "h2", "h3", "h4", "h5", "h6")

    /**
     * 入口：把 jsoup [body] 元素转为 [StructuredChapterContent]。
     *
     * 入参 body 应已经过 [com.morealm.app.domain.parser.EpubParser.parseBody] 预处理
     * （SVG 转 img、`<script>` / `<style>` 移除、ruby 注音内联化等），本函数只做语义切块。
     */
    fun structuredFromBody(body: Element): StructuredChapterContent {
        val blocks = ArrayList<ChapterBlock>()
        val paraBuf = StringBuilder()

        emitBlocks(body, blocks, paraBuf)
        flushParagraph(blocks, paraBuf)

        return StructuredChapterContent(blocks)
    }

    /**
     * 递归遍历 [el] 的子节点，按 tag 语义往 [blocks] 写块、往 [paraBuf] 累积段文本。
     */
    private fun emitBlocks(el: Element, blocks: ArrayList<ChapterBlock>, paraBuf: StringBuilder) {
        for (node in el.childNodes()) {
            when (node) {
                is TextNode -> paraBuf.append(node.text())
                is Element -> handleElement(node, blocks, paraBuf)
                else -> { /* Comment / CDataNode / DataNode 等忽略 */ }
            }
        }
    }

    private fun handleElement(el: Element, blocks: ArrayList<ChapterBlock>, paraBuf: StringBuilder) {
        val tag = el.tagName().lowercase()
        when {
            tag in HEADING_TAGS -> {
                flushParagraph(blocks, paraBuf)
                val level = tag[1].digitToInt().coerceIn(1, 6)
                val text = el.text().trim()
                if (text.isNotEmpty()) blocks.add(ChapterBlock.Heading(level, text))
            }
            tag == "p" -> {
                // <p> 可能含 img + text 混合 —— 递归处理子节点，让 img 独立成块、text 累积。
                // 子节点处理完后再 flush 当前段（emit Paragraph）。
                flushParagraph(blocks, paraBuf)
                emitBlocks(el, blocks, paraBuf)
                flushParagraph(blocks, paraBuf)
            }
            tag == "img" -> {
                flushParagraph(blocks, paraBuf)
                val src = el.attr("src")
                if (src.isNotEmpty()) blocks.add(ChapterBlock.Image(src))
            }
            tag == "br" -> {
                // <br> 视为段落分隔。<p>a<br/>b</p> 拆成两段。
                flushParagraph(blocks, paraBuf)
            }
            tag in CONTAINER_TAGS -> {
                // 容器：先 flush 当前段（容器边界视为段分隔），再递归子节点
                flushParagraph(blocks, paraBuf)
                emitBlocks(el, blocks, paraBuf)
                flushParagraph(blocks, paraBuf)
            }
            else -> {
                // 行内元素 (em / strong / span / a / sup / sub / 等)：不切段，子节点文本继续累积。
                // Step 2 之后扩 ChapterBlock 支持行内强调标记。
                emitBlocks(el, blocks, paraBuf)
            }
        }
    }

    /**
     * 把 [paraBuf] 累积的文本 trim + 折叠空白后作为 Paragraph 添加，然后清空 buffer。
     * 空白文本不产出块（避免空段污染列表）。
     */
    private fun flushParagraph(blocks: ArrayList<ChapterBlock>, paraBuf: StringBuilder) {
        if (paraBuf.isEmpty()) return
        val text = collapseWhitespace(paraBuf.toString()).trim()
        paraBuf.setLength(0)
        if (text.isNotEmpty()) blocks.add(ChapterBlock.Paragraph(text))
    }

    /**
     * 把连续空白（包括 `\n` / `\t` / 全角空格）折叠为单个空格。
     *
     * EPUB XHTML 排版常有为可读性插入的换行 / 缩进，jsoup 取文本时保留这些空白，
     * 排版层不需要这些「源码格式」噪声 —— 行宽 + 标点 + 字号才决定实际换行位置。
     */
    private fun collapseWhitespace(s: String): String {
        if (s.isEmpty()) return s
        val sb = StringBuilder(s.length)
        var prevSpace = false
        for (c in s) {
            val isSpace = c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == ' ' || c == '　'
            if (isSpace) {
                if (!prevSpace) sb.append(' ')
                prevSpace = true
            } else {
                sb.append(c)
                prevSpace = false
            }
        }
        return sb.toString()
    }
}
