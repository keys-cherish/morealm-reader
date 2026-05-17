package com.morealm.app.domain.render.scroll

import com.morealm.app.domain.entity.Highlight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ScrollHighlightProjector 单测 —— 直接构造 mock ScrollChapterLayout，
 * 不依赖 ScrollLayoutEngine（避开 Robolectric / TextPaint）。
 */
class ScrollHighlightProjectorTest {

    /** 构造 mock 单行 line（含若干 columns，charData 用 a/b/c...）。 */
    private fun mockLine(
        firstCp: Int,
        columnCount: Int,
        lineTop: Float,
        lineHeight: Float = 60f,
        paragraphNum: Int = 1,
        isTitle: Boolean = false,
    ): ScrollLine {
        val columns = (0 until columnCount).map { i ->
            ScrollColumn(
                charData = ('a' + i % 26).toString(),
                start = i * 30f,
                end = (i + 1) * 30f,
                chapterPosition = firstCp + i,
            )
        }
        return ScrollLine(
            columns = columns,
            lineTop = lineTop,
            lineBottom = lineTop + lineHeight,
            paragraphNum = paragraphNum,
            isTitle = isTitle,
            text = columns.joinToString("") { it.charData },
            firstChapterPos = firstCp,
            lastChapterPos = if (columnCount > 0) firstCp + columnCount - 1 else firstCp,
        )
    }

    private fun mockEmptyLine(cp: Int, lineTop: Float, lineHeight: Float = 60f, paragraphNum: Int = 1): ScrollLine =
        ScrollLine(
            columns = emptyList(),
            lineTop = lineTop,
            lineBottom = lineTop + lineHeight,
            paragraphNum = paragraphNum,
            isTitle = false,
            text = "",
            firstChapterPos = cp,
            lastChapterPos = cp,
        )

    private fun mockLayout(
        chapterIndex: Int = 5,
        pages: List<ScrollPage>,
        viewWidth: Int = 1080,
    ): ScrollChapterLayout {
        val totalHeight = pages.fold(0f) { acc, p -> acc + p.height }
        val totalCp = pages.lastOrNull()?.lines?.lastOrNull()?.lastChapterPos?.plus(1) ?: 0
        return ScrollChapterLayout(
            chapterIndex = chapterIndex,
            title = "T",
            pages = pages,
            totalHeight = totalHeight,
            viewWidth = viewWidth,
            styleSignature = "mock",
            totalCharCount = totalCp,
        )
    }

    private fun mockHighlight(
        chapterIndex: Int = 5,
        startCp: Int,
        endCp: Int,
        kind: Int = Highlight.KIND_BACKGROUND,
        argb: Int = 0xFFFFFF00.toInt(),
        underlineStyle: Int = Highlight.UNDERLINE_STYLE_SOLID,
    ): Highlight = Highlight(
        id = "h-$startCp-$endCp",
        bookId = "book",
        chapterIndex = chapterIndex,
        startChapterPos = startCp,
        endChapterPos = endCp,
        content = "",
        colorArgb = argb,
        kind = kind,
        underlineStyle = underlineStyle,
    )

    // ── 基本场景 ──────────────────────────────────────────

    @Test
    fun `chapterIndex 不匹配返空`() {
        val layout = mockLayout(
            chapterIndex = 5,
            pages = listOf(ScrollPage(0, listOf(mockLine(0, 5, 60f)), 200f, 5)),
        )
        val highlights = listOf(mockHighlight(chapterIndex = 99, startCp = 0, endCp = 4))
        assertTrue("跨章 highlight 应过滤", ScrollHighlightProjector.project(layout, highlights).isEmpty())
    }

    @Test
    fun `单行高亮 命中单 rect 左右等于首末 column 边界`() {
        val line = mockLine(firstCp = 0, columnCount = 5, lineTop = 60f)
        val page = ScrollPage(0, listOf(line), 200f, 5)
        val layout = mockLayout(pages = listOf(page))
        // 高亮 cp 1..3（命中 column[1] / [2] / [3]）
        val specs = ScrollHighlightProjector.project(layout, listOf(mockHighlight(startCp = 1, endCp = 3)))
        assertEquals(1, specs.size)
        val spec = specs[0]
        assertEquals("一行内 1 rect", 1, spec.rects.size)
        val rect = spec.rects[0]
        // column[1].start = 30, column[3].end = 120
        assertEquals(30f, rect.left, 0.01f)
        assertEquals(120f, rect.right, 0.01f)
        // top = pageOffsetY(0) + lineTop(60) = 60
        assertEquals(60f, rect.top, 0.01f)
        assertEquals(120f, rect.bottom, 0.01f)
        assertEquals(0, rect.pageIndex)
    }

    @Test
    fun `跨行高亮 同页两行 命中 2 rect`() {
        val line1 = mockLine(firstCp = 0, columnCount = 5, lineTop = 60f, paragraphNum = 1)
        val line2 = mockLine(firstCp = 5, columnCount = 5, lineTop = 120f, paragraphNum = 1)
        val page = ScrollPage(0, listOf(line1, line2), 200f, 5)
        val layout = mockLayout(pages = listOf(page))
        // 高亮 cp 3..7 跨两行
        val specs = ScrollHighlightProjector.project(layout, listOf(mockHighlight(startCp = 3, endCp = 7)))
        val spec = specs.single()
        assertEquals("跨两行 2 rect", 2, spec.rects.size)
        // line1 命中 column[3], [4] → left=90, right=150
        assertEquals(90f, spec.rects[0].left, 0.01f)
        assertEquals(150f, spec.rects[0].right, 0.01f)
        // line2 命中 column[0], [1], [2] → left=0, right=90
        assertEquals(0f, spec.rects[1].left, 0.01f)
        assertEquals(90f, spec.rects[1].right, 0.01f)
    }

    @Test
    fun `跨页高亮 命中 2 页各 1 rect 章内累计 y 含 pageOffsetY`() {
        val line1 = mockLine(firstCp = 0, columnCount = 5, lineTop = 60f)
        val line2 = mockLine(firstCp = 5, columnCount = 5, lineTop = 60f)
        val page1 = ScrollPage(0, listOf(line1), 200f, 5)
        val page2 = ScrollPage(1, listOf(line2), 200f, 5)
        val layout = mockLayout(pages = listOf(page1, page2))
        // 高亮 cp 3..7 跨页
        val specs = ScrollHighlightProjector.project(layout, listOf(mockHighlight(startCp = 3, endCp = 7)))
        val spec = specs.single()
        assertEquals("跨两页 2 rect", 2, spec.rects.size)
        // page1 line top: pageOffset 0 + lineTop 60 = 60
        assertEquals(60f, spec.rects[0].top, 0.01f)
        // page2 line top: pageOffset 200 + lineTop 60 = 260
        assertEquals(260f, spec.rects[1].top, 0.01f)
        assertEquals(0, spec.rects[0].pageIndex)
        assertEquals(1, spec.rects[1].pageIndex)
    }

    // ── 特殊段类型 ──────────────────────────────────────────

    @Test
    fun `空段 line 命中 rect 全行宽 0 到 viewWidth`() {
        val emptyLine = mockEmptyLine(cp = 0, lineTop = 60f, paragraphNum = 1)
        val page = ScrollPage(0, listOf(emptyLine), 200f, 5)
        val layout = mockLayout(pages = listOf(page), viewWidth = 1080)
        val specs = ScrollHighlightProjector.project(layout, listOf(mockHighlight(startCp = 0, endCp = 0)))
        val spec = specs.single()
        val rect = spec.rects.single()
        assertEquals("空段 rect 左 = 0", 0f, rect.left, 0.01f)
        assertEquals("空段 rect 右 = viewWidth", 1080f, rect.right, 0.01f)
    }

    @Test
    fun `图片段 line 命中 rect 全行宽`() {
        val imgLine = ScrollLine(
            columns = emptyList(),
            lineTop = 60f, lineBottom = 660f,
            paragraphNum = 1, isTitle = false, text = " ",
            firstChapterPos = 0, lastChapterPos = 0,
            isImage = true, imageSrc = "img1",
        )
        val page = ScrollPage(0, listOf(imgLine), 720f, 5)
        val layout = mockLayout(pages = listOf(page), viewWidth = 1080)
        val specs = ScrollHighlightProjector.project(layout, listOf(mockHighlight(startCp = 0, endCp = 0)))
        val rect = specs.single().rects.single()
        assertEquals(0f, rect.left, 0.01f)
        assertEquals(1080f, rect.right, 0.01f)
    }

    // ── kind / 字段保留 ───────────────────────────────────

    @Test
    fun `kind TEXT_COLOR 字段保留 cpRange 用于 paint 替换`() {
        val line = mockLine(firstCp = 0, columnCount = 5, lineTop = 60f)
        val page = ScrollPage(0, listOf(line), 200f, 5)
        val layout = mockLayout(pages = listOf(page))
        val highlights = listOf(
            mockHighlight(startCp = 1, endCp = 3, kind = Highlight.KIND_TEXT_COLOR, argb = 0xFFFF0000.toInt()),
        )
        val spec = ScrollHighlightProjector.project(layout, highlights).single()
        assertEquals(Highlight.KIND_TEXT_COLOR, spec.kind)
        assertEquals(0xFFFF0000.toInt(), spec.argb)
        assertEquals(1, spec.cpRangeFirst)
        assertEquals(3, spec.cpRangeLast)
    }

    @Test
    fun `kind UNDERLINE 各 underlineStyle 字段保留`() {
        val line = mockLine(firstCp = 0, columnCount = 5, lineTop = 60f)
        val page = ScrollPage(0, listOf(line), 200f, 5)
        val layout = mockLayout(pages = listOf(page))
        listOf(
            Highlight.UNDERLINE_STYLE_SOLID,
            Highlight.UNDERLINE_STYLE_DASHED,
            Highlight.UNDERLINE_STYLE_DOTTED,
            Highlight.UNDERLINE_STYLE_WAVY,
        ).forEach { style ->
            val h = mockHighlight(startCp = 0, endCp = 4, kind = Highlight.KIND_UNDERLINE, underlineStyle = style)
            val spec = ScrollHighlightProjector.project(layout, listOf(h)).single()
            assertEquals("underlineStyle=$style 应保留", style, spec.underlineStyle)
            assertEquals(Highlight.KIND_UNDERLINE, spec.kind)
        }
    }

    @Test
    fun `多个 highlight 各自独立 spec 顺序按输入`() {
        val line = mockLine(firstCp = 0, columnCount = 10, lineTop = 60f)
        val page = ScrollPage(0, listOf(line), 200f, 5)
        val layout = mockLayout(pages = listOf(page))
        val highlights = listOf(
            mockHighlight(startCp = 0, endCp = 2, argb = 0xFFFF0000.toInt()),
            mockHighlight(startCp = 5, endCp = 7, argb = 0xFF00FF00.toInt()),
        )
        val specs = ScrollHighlightProjector.project(layout, highlights)
        assertEquals(2, specs.size)
        assertEquals(0xFFFF0000.toInt(), specs[0].argb)
        assertEquals(0xFF00FF00.toInt(), specs[1].argb)
    }

    @Test
    fun `高亮 cp 范围完全在不可见 cp 上 spec 列表为空`() {
        // 单行 cp 0..4，高亮 cp 10..15 完全不在行内 → 不应产生 spec
        val line = mockLine(firstCp = 0, columnCount = 5, lineTop = 60f)
        val page = ScrollPage(0, listOf(line), 200f, 5)
        val layout = mockLayout(pages = listOf(page))
        val specs = ScrollHighlightProjector.project(layout, listOf(mockHighlight(startCp = 10, endCp = 15)))
        assertTrue("cp 不在行内 → 无 spec", specs.isEmpty())
    }

    @Test
    fun `单字符高亮 cp 等于自身 命中 1 column`() {
        val line = mockLine(firstCp = 0, columnCount = 5, lineTop = 60f)
        val page = ScrollPage(0, listOf(line), 200f, 5)
        val layout = mockLayout(pages = listOf(page))
        // 仅高亮 cp=2 单字符
        val spec = ScrollHighlightProjector.project(layout, listOf(mockHighlight(startCp = 2, endCp = 2))).single()
        val rect = spec.rects.single()
        // column[2].start = 60, column[2].end = 90
        assertEquals(60f, rect.left, 0.01f)
        assertEquals(90f, rect.right, 0.01f)
    }
}
