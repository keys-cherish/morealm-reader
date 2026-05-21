package com.morealm.app.domain.parser.epub.streaming

import com.morealm.app.domain.parser.epub.ChapterBlock
import com.morealm.app.domain.parser.epub.StructuredChapterContent
import com.morealm.app.domain.parser.epub.StyledSpan
import com.morealm.epub.css.Color
import com.morealm.epub.css.CssStyle
import com.morealm.epub.css.Length
import com.morealm.epub.ir.TagId
import com.morealm.epub.xhtml.Attrs
import com.morealm.epub.xhtml.NodeVisitor

/**
 * Terminal NodeVisitor that materializes the streamed XHTML into
 * [StructuredChapterContent] blocks. Sits at the bottom of the
 * StreamingChapterReader visitor stack.
 *
 * P2.2: 在 plain block segmentation 之上叠加 **inline style cascade**：
 * 每个 element 节点保留一个 styling frame（color / sizeScale / weight / italic），
 * onText 时根据当前栈顶 frame 产出 [StyledSpan]。block flush 时根据是否
 * 含非默认 styling 决定 emit [ChapterBlock.RichText] 还是 [ChapterBlock.Paragraph]。
 *
 * 范围：仅处理 `style="..."` inline 属性 + 语义 tag (`<b>`/`<strong>` →
 * weight 700；`<i>`/`<em>` → italic)。**不处理外部 stylesheet** (`<link
 * rel=stylesheet>` 引入的 CSS) —— 那需要 tree-based [com.morealm.epub.css.StyleResolver]，
 * 跟 streaming visitor 模型不兼容，留 P3 视情况升级。
 *
 * Reusable across multiple `Chapter.streamTo(...)` calls when assembling a
 * single logical chapter that spans multiple spine items: feed all spine
 * items into the same builder, then call [build] once at the end.
 */
class ChapterBlockBuilder : NodeVisitor {

    private val blocks = ArrayList<ChapterBlock>()
    private val paraBuf = ArrayList<StyledSpan>()
    private var pendingHeadingLevel: Int = 0

    /** 跨 onText 调用保留的空白折叠状态。初始 true 让段首空白被折叠掉。 */
    private var lastCharWasSpace: Boolean = true

    /** Styling frame 栈 —— onOpen push 一帧（push 时计算 effective style），onClose pop。 */
    private val frameStack = ArrayDeque<Frame>()

    private data class Frame(
        val tag: TagId,
        val color: Int?,
        val sizeScale: Float,
        val weight: Int?,
        val italic: Boolean,
    )

    private val rootFrame: Frame = Frame(TagId.UNKNOWN, color = null, sizeScale = 1f, weight = null, italic = false)

    override fun onOpen(tag: TagId, attrs: Attrs, selfClosing: Boolean) {
        // 1. 块语义切分
        when {
            tag.isHeading() -> {
                flushParagraph()
                pendingHeadingLevel = tag.headingLevel()
            }
            tag == TagId.IMG -> {
                flushParagraph()
                val src = attrs.src()
                if (src.isNotEmpty()) blocks.add(ChapterBlock.Image(src))
            }
            tag == TagId.BR -> flushParagraph()
            tag.isContainer() -> flushParagraph()
            // inline tags fall through
        }

        // 2. 计算并 push styling frame（selfClosing 不入栈避免污染后续节点）
        if (!selfClosing) {
            val parent = frameStack.lastOrNull() ?: rootFrame
            frameStack.addLast(computeFrame(parent, tag, attrs))
        }
    }

    override fun onClose(tag: TagId) {
        // 1. pop styling frame（栈顶 tag 匹配才 pop，防止配对错乱）
        if (frameStack.isNotEmpty() && frameStack.last().tag == tag) {
            frameStack.removeLast()
        }

        // 2. 块语义结束
        when {
            tag.isHeading() -> {
                val txt = paraBuf.joinToString("") { it.text }.trim()
                paraBuf.clear()
                lastCharWasSpace = true
                if (txt.isNotEmpty()) {
                    blocks.add(ChapterBlock.Heading(pendingHeadingLevel.coerceIn(1, 6), txt))
                }
                pendingHeadingLevel = 0
            }
            tag == TagId.P || tag.isContainer() -> flushParagraph()
        }
    }

    override fun onText(text: CharSequence) {
        if (text.isEmpty()) return
        val frame = frameStack.lastOrNull() ?: rootFrame
        // 折叠空白：连续 whitespace（ASCII + 全角空格 + NBSP）→ 单 ASCII 空格
        val sb = StringBuilder(text.length)
        for (i in 0 until text.length) {
            val c = text[i]
            val isSpace = c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == ' ' || c == '　'
            if (isSpace) {
                if (!lastCharWasSpace) {
                    sb.append(' ')
                    lastCharWasSpace = true
                }
            } else {
                sb.append(c)
                lastCharWasSpace = false
            }
        }
        if (sb.isEmpty()) return

        val added = sb.toString()
        // 累积到当前 span：跟前一个 span 同 styling 就拼接，否则 push 新 span
        val last = paraBuf.lastOrNull()
        if (last != null && sameStyleAsFrame(last, frame)) {
            paraBuf[paraBuf.size - 1] = last.copy(text = last.text + added)
        } else {
            paraBuf.add(
                StyledSpan(
                    text = added,
                    color = frame.color,
                    sizeScale = frame.sizeScale,
                    weight = frame.weight,
                    italic = frame.italic,
                ),
            )
        }
    }

    override fun onCdata(text: CharSequence) {
        onText(text)
    }

    fun build(): StructuredChapterContent {
        flushParagraph()
        // 兜底：FragmentSliceVisitor STOP / 异常截断可能没发出最外层 body close ——
        // 触发一次 BODY close 让残留 flush。多 emit 一次 close 不会重复出 block。
        onClose(TagId.BODY)
        return StructuredChapterContent(blocks.toList())
    }

    // ── 私有逻辑 ─────────────────────────────────────────────────────

    private fun flushParagraph() {
        if (paraBuf.isEmpty()) return
        // pendingHeadingLevel != 0：当前在 heading 内，文本由 onClose(H*) emit
        if (pendingHeadingLevel != 0) {
            // heading 内不该有跨段 paraBuf；保险起见清空
            paraBuf.clear()
            lastCharWasSpace = true
            return
        }

        val trimmed = trimSpansEdge(paraBuf)
        paraBuf.clear()
        lastCharWasSpace = true
        if (trimmed.isEmpty()) return

        val allPlain = trimmed.all { it.isPlain }
        if (allPlain) {
            val joined = stripCjkBoundarySpaces(trimmed.joinToString("") { it.text })
            if (joined.isNotEmpty()) blocks.add(ChapterBlock.Paragraph(joined))
        } else {
            val coalesced = coalesceSpans(trimmed)
            if (coalesced.isNotEmpty()) blocks.add(ChapterBlock.RichText(coalesced))
        }
    }

    /**
     * 删 CJK 字符之间的孤立 ASCII 空格 —— `<span>为</span><span>美</span>` 经
     * HTML 默认 whitespace 折叠会产 "为 美"，CJK 阅读者不期望这个空格。
     * Latin 文本边界（`Hello World`）不动。
     */
    private fun stripCjkBoundarySpaces(s: String): String {
        if (' ' !in s) return s
        val n = s.length
        val out = StringBuilder(n)
        var i = 0
        while (i < n) {
            val c = s[i]
            if (c == ' ' && i > 0 && i + 1 < n && isCjk(s[i - 1]) && isCjk(s[i + 1])) {
                i++
                continue
            }
            out.append(c)
            i++
        }
        return out.toString()
    }

    private fun isCjk(c: Char): Boolean {
        val code = c.code
        return (code in 0x3040..0x30FF) ||
            (code in 0x3400..0x4DBF) ||
            (code in 0x4E00..0x9FFF) ||
            (code in 0xF900..0xFAFF) ||
            (code in 0xFF00..0xFFEF)
    }

    /** 把 spans 列表首末 trim 掉边界空白，返回新列表（不改原）。 */
    private fun trimSpansEdge(spans: List<StyledSpan>): List<StyledSpan> {
        if (spans.isEmpty()) return spans
        val result = ArrayList(spans)
        // trim 首
        while (result.isNotEmpty()) {
            val first = result[0]
            val trimmedText = first.text.trimStart()
            when {
                trimmedText.isEmpty() -> result.removeAt(0)
                trimmedText.length != first.text.length -> {
                    result[0] = first.copy(text = trimmedText); break
                }
                else -> break
            }
        }
        // trim 末
        while (result.isNotEmpty()) {
            val last = result.last()
            val trimmedText = last.text.trimEnd()
            when {
                trimmedText.isEmpty() -> result.removeAt(result.size - 1)
                trimmedText.length != last.text.length -> {
                    result[result.size - 1] = last.copy(text = trimmedText); break
                }
                else -> break
            }
        }
        return result
    }

    /** 合并连续同 styling 的 spans，减少下游 SpanStyle 切换开销。 */
    private fun coalesceSpans(spans: List<StyledSpan>): List<StyledSpan> {
        if (spans.size < 2) return spans
        val out = ArrayList<StyledSpan>(spans.size)
        for (s in spans) {
            val last = out.lastOrNull()
            if (last != null && sameStyle(last, s)) {
                out[out.size - 1] = last.copy(text = last.text + s.text)
            } else {
                out.add(s)
            }
        }
        return out
    }

    private fun sameStyle(a: StyledSpan, b: StyledSpan): Boolean =
        a.color == b.color && a.sizeScale == b.sizeScale &&
            a.weight == b.weight && a.italic == b.italic

    /** span 跟 frame 的 styling 完全一致 —— 仅文字可拼接到同一 span。 */
    private fun sameStyleAsFrame(span: StyledSpan, frame: Frame): Boolean =
        span.color == frame.color && span.sizeScale == frame.sizeScale &&
            span.weight == frame.weight && span.italic == frame.italic

    private fun computeFrame(parent: Frame, tag: TagId, attrs: Attrs): Frame {
        var color = parent.color
        var sizeScale = parent.sizeScale
        var weight = parent.weight
        var italic = parent.italic

        // 1. 标签隐式样式
        when (tag) {
            TagId.B, TagId.STRONG -> weight = 700
            TagId.I, TagId.EM -> italic = true
            else -> Unit
        }

        // 2. inline style="..." 解析（最高优先级）
        val styleStr = attrs.style()
        if (styleStr.isNotEmpty()) {
            val css = CssStyle.parseInline(styleStr)
            css["color"]?.let { v -> Color.parse(v)?.let { color = it } }
            css["font-size"]?.let { v ->
                parseFontSizeScale(v, parent.sizeScale)?.let { sizeScale = it }
            }
            css["font-weight"]?.let { v -> parseFontWeight(v)?.let { weight = it } }
            css["font-style"]?.let { v ->
                when (v.trim().lowercase()) {
                    "italic", "oblique" -> italic = true
                    "normal" -> italic = false
                    else -> Unit
                }
            }
        }

        return Frame(tag, color, sizeScale, weight, italic)
    }

    /**
     * 把 CSS `font-size` 值（如 "1.5em" / "16px" / "150%" / "small"）解析成相对
     * 父字号的倍率。返回 null 表示无法解析（忽略此声明）。
     */
    private fun parseFontSizeScale(value: String, parentScale: Float): Float? {
        val trimmed = value.trim().lowercase()
        when (trimmed) {
            "xx-small" -> return parentScale * 0.6f
            "x-small" -> return parentScale * 0.75f
            "small" -> return parentScale * 0.85f
            "medium" -> return parentScale
            "large" -> return parentScale * 1.2f
            "x-large" -> return parentScale * 1.5f
            "xx-large" -> return parentScale * 2.0f
            "smaller" -> return parentScale * 0.83f
            "larger" -> return parentScale * 1.2f
        }
        val len = Length.parse(value.trim()) ?: return null
        return when (len.unit) {
            Length.Unit.Em, Length.Unit.Rem -> parentScale * len.value
            Length.Unit.Percent -> parentScale * len.value / 100f
            Length.Unit.Px -> parentScale * len.value / 16f
            Length.Unit.Pt -> parentScale * len.value * 4f / 3f / 16f
            else -> null
        }
    }

    /** 解析 CSS `font-weight`：normal/bold/100..900。 */
    private fun parseFontWeight(value: String): Int? {
        val v = value.trim().lowercase()
        return when (v) {
            "normal" -> 400
            "bold" -> 700
            "bolder" -> 900
            "lighter" -> 100
            else -> v.toIntOrNull()?.coerceIn(100, 900)
        }
    }

    // ── 块标签判定（独立实现避免依赖 epub-compat internal） ──

    private fun TagId.isHeading(): Boolean =
        this == TagId.H1 || this == TagId.H2 || this == TagId.H3 ||
            this == TagId.H4 || this == TagId.H5 || this == TagId.H6

    private fun TagId.headingLevel(): Int = when (this) {
        TagId.H1 -> 1; TagId.H2 -> 2; TagId.H3 -> 3
        TagId.H4 -> 4; TagId.H5 -> 5; TagId.H6 -> 6
        else -> 0
    }

    private fun TagId.isContainer(): Boolean = when (this) {
        TagId.BODY, TagId.DIV, TagId.SECTION, TagId.ARTICLE, TagId.MAIN,
        TagId.BLOCKQUOTE, TagId.FIGURE, TagId.FIGCAPTION,
        TagId.HEADER, TagId.FOOTER, TagId.NAV, TagId.ASIDE,
        TagId.UL, TagId.OL, TagId.LI,
        TagId.TABLE, TagId.THEAD, TagId.TBODY, TagId.TFOOT,
        TagId.TR, TagId.TD, TagId.TH -> true
        else -> false
    }
}
