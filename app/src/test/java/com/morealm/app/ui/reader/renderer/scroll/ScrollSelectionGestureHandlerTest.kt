package com.morealm.app.ui.reader.renderer.scroll

import com.morealm.epub.render.ScrollChapterLayout
import com.morealm.epub.render.ScrollColumn
import com.morealm.epub.render.ScrollLine
import com.morealm.epub.render.ScrollPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrollSelectionGestureHandlerTest {

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

    /** 按给定文本逐字建 column 的行（每字 30px 宽），用于分词边界断言。 */
    private fun mockLineOf(text: String, firstCp: Int = 0, lineTop: Float = 60f): ScrollLine {
        val columns = text.mapIndexed { i, ch ->
            ScrollColumn(
                charData = ch.toString(),
                start = i * 30f,
                end = (i + 1) * 30f,
                chapterPosition = firstCp + i,
            )
        }
        return ScrollLine(
            columns = columns,
            lineTop = lineTop,
            lineBottom = lineTop + 60f,
            paragraphNum = 1,
            isTitle = false,
            text = text,
            firstChapterPos = firstCp,
            lastChapterPos = firstCp + text.length - 1,
        )
    }

    private fun layoutOf(text: String, chapterIndex: Int = 5): ScrollChapterLayout {
        val page = ScrollPage(0, listOf(mockLineOf(text)), 200f, chapterIndex)
        return ScrollChapterLayout(
            chapterIndex = chapterIndex,
            title = "T", pages = listOf(page), totalHeight = 200f,
            viewWidth = 1080, styleSignature = "mock", totalCharCount = text.length + 1,
        )
    }

    private fun mockLayout(chapterIndex: Int = 5): ScrollChapterLayout {
        val line = mockLine(firstCp = 0, columnCount = 10, lineTop = 60f)
        val page = ScrollPage(0, listOf(line), 200f, chapterIndex)
        return ScrollChapterLayout(
            chapterIndex = chapterIndex,
            title = "T", pages = listOf(page), totalHeight = 200f,
            viewWidth = 1080, styleSignature = "mock", totalCharCount = 11,
        )
    }

    // ── handleLongPress ─────────────────────────────────────

    @Test
    fun `长按命中字符 选区扩到整词边界`() {
        // "hi you"：点在 'y'(idx 3) 上应整词选中 "you"(3..5)。
        // 旧行为是只选中单个 'y' —— 用户长按一个词只高亮一个字，必须再拖游标才凑齐。
        val layout = layoutOf("hi you")
        val sel = handleLongPress(layout, chapterIndex = 5, x = 3 * 30f + 15f, yInChapter = 80f)
        assertTrue(sel.isActive)
        assertEquals(3, sel.startCp)
        assertEquals(5, sel.endCp)
        assertEquals(5, sel.chapterIndex)
        assertFalse(sel.isSingleChar)
    }

    @Test
    fun `长按命中空白 不扩词 退回单字符`() {
        // idx 2 是空格。BreakIterator 会把连续空白单独切成一段，扩过去等于选中
        // 一片看不见的东西，所以这种情况明确退回单字符。
        val layout = layoutOf("hi you")
        val sel = handleLongPress(layout, chapterIndex = 5, x = 2 * 30f + 15f, yInChapter = 80f)
        assertTrue(sel.isActive)
        assertEquals(2, sel.startCp)
        assertEquals(2, sel.endCp)
        assertTrue(sel.isSingleChar)
    }

    @Test
    fun `长按命中空段 line 选区 cp 等于 line firstChapterPos`() {
        // 空段 line cp=5
        val emptyLine = ScrollLine(
            columns = emptyList(),
            lineTop = 60f, lineBottom = 120f,
            paragraphNum = 1, isTitle = false, text = "",
            firstChapterPos = 5, lastChapterPos = 5,
        )
        val page = ScrollPage(0, listOf(emptyLine), 200f, 5)
        val layout = ScrollChapterLayout(
            chapterIndex = 5, title = "T", pages = listOf(page),
            totalHeight = 200f, viewWidth = 1080,
            styleSignature = "mock", totalCharCount = 6,
        )
        val sel = handleLongPress(layout, chapterIndex = 5, x = 100f, yInChapter = 80f)
        assertTrue(sel.isActive)
        assertEquals(5, sel.startCp)
        assertEquals(5, sel.endCp)
    }

    @Test
    fun `长按 chapterIndex 与 layout 不匹配返 Empty`() {
        val layout = mockLayout(chapterIndex = 5)
        val sel = handleLongPress(layout, chapterIndex = 99, x = 100f, yInChapter = 80f)
        assertFalse(sel.isActive)
    }

    @Test
    fun `长按 y 越界 返 Empty`() {
        val layout = mockLayout()
        val sel = handleLongPress(layout, chapterIndex = 5, x = 100f, yInChapter = -10f)
        assertFalse(sel.isActive)
    }

    // ── handleHandleDrag ─────────────────────────────────────

    @Test
    fun `drag START handle 更新 startCp 保留 endCp`() {
        val layout = mockLayout()
        val initial = ScrollSelectionState(chapterIndex = 5, startCp = 3, endCp = 6)
        // drag START 到 column[1] (x=40, y=80)
        val updated = handleHandleDrag(initial, layout, ScrollHandleSide.START, x = 40f, yInChapter = 80f)
        assertEquals(1, updated.startCp)
        assertEquals(6, updated.endCp)  // endCp 不变
        assertTrue(updated.isActive)
    }

    @Test
    fun `drag END handle 更新 endCp 保留 startCp`() {
        val layout = mockLayout()
        val initial = ScrollSelectionState(chapterIndex = 5, startCp = 3, endCp = 6)
        val updated = handleHandleDrag(initial, layout, ScrollHandleSide.END, x = 250f, yInChapter = 80f)
        assertEquals(3, updated.startCp)
        assertEquals(8, updated.endCp)  // column[8] x in [240, 270]
    }

    @Test
    fun `drag handle inactive selection 不动`() {
        val layout = mockLayout()
        val initial = ScrollSelectionState(chapterIndex = 5, startCp = 3, endCp = 6, isActive = false)
        val updated = handleHandleDrag(initial, layout, ScrollHandleSide.START, x = 0f, yInChapter = 80f)
        assertEquals(initial, updated)  // 完全不变
    }

    @Test
    fun `drag handle chapterIndex 不匹配 不动`() {
        val layout = mockLayout(chapterIndex = 5)
        val initial = ScrollSelectionState(chapterIndex = 99, startCp = 3, endCp = 6)
        val updated = handleHandleDrag(initial, layout, ScrollHandleSide.START, x = 40f, yInChapter = 80f)
        assertEquals(initial, updated)
    }

    @Test
    fun `drag handle y 越界 保持原 selection 端点`() {
        val layout = mockLayout()
        val initial = ScrollSelectionState(chapterIndex = 5, startCp = 3, endCp = 6)
        val updated = handleHandleDrag(initial, layout, ScrollHandleSide.START, x = 40f, yInChapter = -100f)
        assertEquals(initial, updated)
    }

    @Test
    fun `drag handle 反向 startCp 大于 endCp 仍合法 cpRange 自动归一`() {
        val layout = mockLayout()
        val initial = ScrollSelectionState(chapterIndex = 5, startCp = 3, endCp = 6)
        // drag START 到 cp=8（超过 endCp=6）
        val updated = handleHandleDrag(initial, layout, ScrollHandleSide.START, x = 250f, yInChapter = 80f)
        assertEquals(8, updated.startCp)
        assertEquals(6, updated.endCp)
        // cpRange 归一后 6..8
        assertEquals(6..8, updated.cpRange)
    }

    @Test
    fun `handleCancelSelection 返 Empty`() {
        val sel = handleCancelSelection()
        assertFalse(sel.isActive)
        assertEquals(ScrollSelectionState.Empty, sel)
    }
}
