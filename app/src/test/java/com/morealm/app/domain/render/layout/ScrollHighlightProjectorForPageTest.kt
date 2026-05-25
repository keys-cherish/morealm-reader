package com.morealm.app.domain.render.layout

import com.morealm.app.domain.entity.Highlight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ScrollHighlightProjector.projectForPage] 单测 —— page-level 投影。
 *
 * 与 [ScrollHighlightProjectorTest] 对偶但简化：单 page 输入 + page-relative rects 输出。
 */
class ScrollHighlightProjectorForPageTest {

    private fun mockLine(
        firstCp: Int,
        columnCount: Int,
        lineTop: Float,
        lineHeight: Float = 60f,
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
            paragraphNum = 1,
            isTitle = false,
            text = columns.joinToString("") { it.charData },
            firstChapterPos = firstCp,
            lastChapterPos = if (columnCount > 0) firstCp + columnCount - 1 else firstCp,
        )
    }

    private fun mockPage(
        chapterIndex: Int = 5,
        pageIndex: Int = 0,
        lines: List<ScrollLine>,
    ): ScrollPage = ScrollPage(
        pageIndex = pageIndex,
        lines = lines,
        height = lines.lastOrNull()?.lineBottom ?: 0f,
        chapterIndex = chapterIndex,
    )

    private fun mockHighlight(
        startCp: Int,
        endCp: Int,
        kind: Int = Highlight.KIND_BACKGROUND,
        argb: Int = 0xFFFFFF00.toInt(),
    ): Highlight = Highlight(
        id = "h-$startCp-$endCp",
        bookId = "book",
        chapterIndex = 5,
        startChapterPos = startCp,
        endChapterPos = endCp,
        content = "",
        kind = kind,
        colorArgb = argb,
        underlineStyle = Highlight.UNDERLINE_STYLE_SOLID,
        createdAt = 0L,
    )

    @Test
    fun `empty highlights - empty result`() {
        val page = mockPage(lines = listOf(mockLine(0, 5, 0f)))
        val specs = ScrollHighlightProjector.projectForPage(page, 1080, emptyList())
        assertTrue(specs.isEmpty())
    }

    @Test
    fun `empty page - empty result`() {
        val page = mockPage(lines = emptyList())
        val h = mockHighlight(0, 10)
        val specs = ScrollHighlightProjector.projectForPage(page, 1080, listOf(h))
        assertTrue(specs.isEmpty())
    }

    @Test
    fun `highlight entirely before page - filtered out`() {
        val page = mockPage(lines = listOf(mockLine(firstCp = 100, columnCount = 5, lineTop = 0f)))
        val h = mockHighlight(startCp = 0, endCp = 50)
        val specs = ScrollHighlightProjector.projectForPage(page, 1080, listOf(h))
        assertTrue(specs.isEmpty())
    }

    @Test
    fun `highlight entirely after page - filtered out`() {
        val page = mockPage(lines = listOf(mockLine(firstCp = 0, columnCount = 5, lineTop = 0f)))
        val h = mockHighlight(startCp = 100, endCp = 200)
        val specs = ScrollHighlightProjector.projectForPage(page, 1080, listOf(h))
        assertTrue(specs.isEmpty())
    }

    @Test
    fun `single line single highlight - page-relative rect`() {
        // page 内行 cp 0..4, lineTop=100, lineHeight=60
        val page = mockPage(lines = listOf(mockLine(firstCp = 0, columnCount = 5, lineTop = 100f, lineHeight = 60f)))
        val h = mockHighlight(startCp = 1, endCp = 3)
        val specs = ScrollHighlightProjector.projectForPage(page, 1080, listOf(h))
        assertEquals(1, specs.size)
        val rects = specs[0].rects
        assertEquals(1, rects.size)
        val r = rects[0]
        // cp=1 column.start=30, cp=3 column.end=120
        assertEquals(30f, r.left, 0.01f)
        assertEquals(120f, r.right, 0.01f)
        // top = line.lineTop = 100（page-relative，不加 pageOffset）
        assertEquals("rect top 是 page-relative", 100f, r.top, 0.01f)
        assertEquals(160f, r.bottom, 0.01f)
    }

    @Test
    fun `highlight across multiple lines - multiple rects`() {
        // page: 2 lines，line1 cp 0..4 / line2 cp 5..9
        val page = mockPage(lines = listOf(
            mockLine(firstCp = 0, columnCount = 5, lineTop = 0f),
            mockLine(firstCp = 5, columnCount = 5, lineTop = 60f),
        ))
        val h = mockHighlight(startCp = 2, endCp = 7)
        val specs = ScrollHighlightProjector.projectForPage(page, 1080, listOf(h))
        assertEquals(1, specs.size)
        val rects = specs[0].rects
        assertEquals(2, rects.size)
        // 第一行 cp 2..4
        assertEquals(60f, rects[0].left, 0.01f)
        assertEquals(150f, rects[0].right, 0.01f)
        // 第二行 cp 5..7
        assertEquals(0f, rects[1].left, 0.01f)
        assertEquals(90f, rects[1].right, 0.01f)
    }

    @Test
    fun `empty line (paragraph break) hit - rect uses visibleWidth`() {
        // 空段（columns 空）命中：rect = 全宽
        val emptyLine = ScrollLine(
            columns = emptyList(),
            lineTop = 0f,
            lineBottom = 30f,
            paragraphNum = 1,
            isTitle = false,
            text = "",
            firstChapterPos = 5,
            lastChapterPos = 5,
        )
        val page = mockPage(lines = listOf(emptyLine))
        val h = mockHighlight(startCp = 5, endCp = 5)
        val specs = ScrollHighlightProjector.projectForPage(page, 1080, listOf(h))
        assertEquals(1, specs.size)
        val r = specs[0].rects[0]
        assertEquals(0f, r.left, 0.01f)
        assertEquals(1080f, r.right, 0.01f)
    }

    @Test
    fun `multiple highlights of different kinds - each becomes own spec`() {
        val page = mockPage(lines = listOf(mockLine(0, 10, 0f)))
        val highlights = listOf(
            mockHighlight(0, 2, Highlight.KIND_BACKGROUND, 0xFF00FF00.toInt()),
            mockHighlight(3, 5, Highlight.KIND_TEXT_COLOR, 0xFFFF0000.toInt()),
            mockHighlight(6, 8, Highlight.KIND_UNDERLINE, 0xFF0000FF.toInt()),
        )
        val specs = ScrollHighlightProjector.projectForPage(page, 1080, highlights)
        assertEquals(3, specs.size)
        assertEquals(Highlight.KIND_BACKGROUND, specs[0].kind)
        assertEquals(Highlight.KIND_TEXT_COLOR, specs[1].kind)
        assertEquals(Highlight.KIND_UNDERLINE, specs[2].kind)
    }
}
