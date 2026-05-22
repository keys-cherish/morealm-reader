package com.morealm.app.ui.reader.renderer.scroll

import com.morealm.app.domain.entity.Highlight
import com.morealm.app.domain.render.layout.ScrollChapterLayout
import com.morealm.app.domain.render.layout.ScrollColumn
import com.morealm.app.domain.render.layout.ScrollLine
import com.morealm.app.domain.render.layout.ScrollPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrollSelectionHighlightAdapterTest {

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
            lastChapterPos = firstCp + columnCount - 1,
        )
    }

    private fun mockLayout(chapterIndex: Int = 5): ScrollChapterLayout {
        val line = mockLine(firstCp = 0, columnCount = 10, lineTop = 60f)
        val page = ScrollPage(0, listOf(line), 200f, chapterIndex)
        return ScrollChapterLayout(
            chapterIndex = chapterIndex,
            title = "T",
            pages = listOf(page),
            totalHeight = 200f,
            viewWidth = 1080,
            styleSignature = "mock",
            totalCharCount = 11,
        )
    }

    @Test
    fun `inactive 选区返 null`() {
        val layout = mockLayout()
        val selection = ScrollSelectionState(chapterIndex = 5, startCp = 0, endCp = 3, isActive = false)
        assertNull(ScrollSelectionHighlightAdapter.toHighlightSpec(selection, layout))
    }

    @Test
    fun `Empty 选区返 null`() {
        val layout = mockLayout()
        assertNull(ScrollSelectionHighlightAdapter.toHighlightSpec(ScrollSelectionState.Empty, layout))
    }

    @Test
    fun `chapterIndex 不匹配返 null`() {
        val layout = mockLayout(chapterIndex = 5)
        val selection = ScrollSelectionState(chapterIndex = 99, startCp = 0, endCp = 3)
        assertNull(ScrollSelectionHighlightAdapter.toHighlightSpec(selection, layout))
    }

    @Test
    fun `单字符选区 命中 1 rect`() {
        val layout = mockLayout()
        val selection = ScrollSelectionState(chapterIndex = 5, startCp = 2, endCp = 2)
        val spec = ScrollSelectionHighlightAdapter.toHighlightSpec(selection, layout)
        assertNotNull(spec)
        assertEquals(1, spec!!.rects.size)
        // column[2].start = 60, end = 90
        assertEquals(60f, spec.rects[0].left, 0.01f)
        assertEquals(90f, spec.rects[0].right, 0.01f)
    }

    @Test
    fun `多字符选区 命中 rect 左右等于首末 column 边界`() {
        val layout = mockLayout()
        val selection = ScrollSelectionState(chapterIndex = 5, startCp = 2, endCp = 5)
        val spec = ScrollSelectionHighlightAdapter.toHighlightSpec(selection, layout)!!
        val rect = spec.rects.single()
        // column[2].start = 60, column[5].end = 180
        assertEquals(60f, rect.left, 0.01f)
        assertEquals(180f, rect.right, 0.01f)
    }

    @Test
    fun `反向选区 start 大于 end 自动 swap`() {
        val layout = mockLayout()
        val selection = ScrollSelectionState(chapterIndex = 5, startCp = 5, endCp = 2)
        val spec = ScrollSelectionHighlightAdapter.toHighlightSpec(selection, layout)!!
        val rect = spec.rects.single()
        // 归一化后 cpRange = 2..5，与正向选区结果一致
        assertEquals(60f, rect.left, 0.01f)
        assertEquals(180f, rect.right, 0.01f)
    }

    @Test
    fun `默认选区色 ARGB 半透明蓝色`() {
        val layout = mockLayout()
        val selection = ScrollSelectionState(chapterIndex = 5, startCp = 0, endCp = 0)
        val spec = ScrollSelectionHighlightAdapter.toHighlightSpec(selection, layout)!!
        assertEquals(0x556CCCFF.toInt(), spec.argb)
        assertEquals(Highlight.KIND_BACKGROUND, spec.kind)
    }

    @Test
    fun `自定义选区色 ARGB 透传到 spec`() {
        val layout = mockLayout()
        val customArgb = 0x80FF00FF.toInt()
        val selection = ScrollSelectionState(chapterIndex = 5, startCp = 0, endCp = 2)
        val spec = ScrollSelectionHighlightAdapter.toHighlightSpec(selection, layout, argb = customArgb)!!
        assertEquals(customArgb, spec.argb)
    }

    @Test
    fun `cpRange 不命中任何 line 返 null`() {
        val layout = mockLayout()  // cp 0..9
        val selection = ScrollSelectionState(chapterIndex = 5, startCp = 100, endCp = 200)
        assertNull(ScrollSelectionHighlightAdapter.toHighlightSpec(selection, layout))
    }

    @Test
    fun `ScrollSelectionState cpRange 归一化 等价 minOf 与 maxOf`() {
        val s = ScrollSelectionState(chapterIndex = 0, startCp = 7, endCp = 3)
        assertEquals(3..7, s.cpRange)
    }

    @Test
    fun `ScrollSelectionState isSingleChar 当 start 等于 end`() {
        assertTrue(ScrollSelectionState(0, 5, 5).isSingleChar)
        assertTrue(!ScrollSelectionState(0, 5, 6).isSingleChar)
    }
}
