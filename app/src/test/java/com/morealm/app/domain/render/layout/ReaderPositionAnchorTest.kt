package com.morealm.app.domain.render.layout

import com.morealm.epub.render.ScrollChapterLayout
import com.morealm.epub.render.ScrollLine
import com.morealm.epub.render.ScrollPage
import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderPositionAnchorTest {
    private fun line(top: Float, bottom: Float, cp: Int) = ScrollLine(
        columns = emptyList(),
        lineTop = top,
        lineBottom = bottom,
        paragraphNum = 0,
        isTitle = false,
        text = "",
        firstChapterPos = cp,
        lastChapterPos = cp,
    )

    private fun layout(pages: List<ScrollPage>) = ScrollChapterLayout(
        chapterIndex = 1,
        title = "章节",
        pages = pages,
        totalHeight = pages.sumOf { it.height.toDouble() }.toFloat(),
        viewWidth = 1080,
        styleSignature = "test",
        totalCharCount = 1000,
    )

    @Test
    fun `横向分页保存当前页第一行`() {
        val value = visibleChapterPosition(
            layout = layout(
                listOf(
                    ScrollPage(0, listOf(line(0f, 50f, 10)), 200f, 1),
                    ScrollPage(1, listOf(line(0f, 50f, 200)), 200f, 1),
                )
            ),
            pageIndex = 1,
            pageOffset = 0f,
        )

        assertEquals(200, value)
    }

    @Test
    fun `滚动锚点按页内偏移选择可见行`() {
        val value = visibleChapterPosition(
            layout = layout(
                listOf(
                    ScrollPage(
                        0,
                        listOf(line(0f, 50f, 10), line(50f, 100f, 20), line(100f, 150f, 30)),
                        200f,
                        1,
                    )
                )
            ),
            pageIndex = 0,
            pageOffset = 40f,
            viewportAnchorY = 70f,
        )

        assertEquals(30, value)
    }

    @Test
    fun `滚动锚点越过当前页时落到下一页`() {
        val value = visibleChapterPosition(
            layout = layout(
                listOf(
                    ScrollPage(0, listOf(line(0f, 50f, 10)), 100f, 1),
                    ScrollPage(1, listOf(line(0f, 60f, 300)), 100f, 1),
                )
            ),
            pageIndex = 0,
            pageOffset = 80f,
            viewportAnchorY = 40f,
        )

        assertEquals(300, value)
    }
}
