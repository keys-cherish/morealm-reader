package com.morealm.app.domain.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for [ParagraphTextPos] + 配套坐标转换 ([chapterPositionToParagraphPos] /
 * [toChapterPosition] / [toScrollAnchor] / [findItemIndex]).
 *
 * Phase 4 引入的「章 + 段 + 段内字符偏移」坐标系，给选区/TTS/书签运行时使用。
 */
@RunWith(RobolectricTestRunner::class)
class ParagraphTextPosTest {

    // ── 复用 ScrollAnchorTest 的 helper 构造跨章段落窗口 ──

    private fun line(
        paragraphNum: Int,
        top: Float,
        bottom: Float,
        chapterPosition: Int,
        text: String = "x",
        isParagraphEnd: Boolean = false,
    ): TextLine = TextLine(text = text, isParagraphEnd = isParagraphEnd).apply {
        this.paragraphNum = paragraphNum
        this.lineTop = top
        this.lineBottom = bottom
        this.lineBase = bottom - 2f
        this.chapterPosition = chapterPosition
        addColumn(TextColumn(charData = text, start = 0f, end = 10f))
    }

    private fun page(paddingTop: Int, vararg lines: TextLine): TextPage =
        TextPage(paddingTop = paddingTop).apply {
            lines.forEach { addLine(it) }
            height = lines.lastOrNull()?.lineBottom ?: 0f
            isCompleted = true
        }

    private fun chapter(chapterIdx: Int, vararg pages: TextPage): TextChapter =
        TextChapter(chapterIndex = chapterIdx, title = "ch$chapterIdx", chaptersSize = 10).apply {
            pages.forEach { addPage(it) }
            isCompleted = true
        }

    /** 跨 3 章窗口，每章 2 段、每段 2 行；段内字符位置 0-5 / 10-15 / 20-25 / 30-35。 */
    private fun buildThreeChapterWindow(): List<ScrollParagraph> {
        val chapters = (0..2).map { chapterIdx ->
            chapter(
                chapterIdx,
                page(
                    paddingTop = if (chapterIdx == 0) 50 else 0,
                    line(paragraphNum = 1, top = 0f, bottom = 30f, chapterPosition = 0),
                    line(paragraphNum = 1, top = 30f, bottom = 60f, chapterPosition = 5, isParagraphEnd = true),
                    line(paragraphNum = 2, top = 60f, bottom = 90f, chapterPosition = 10),
                    line(paragraphNum = 2, top = 90f, bottom = 120f, chapterPosition = 15, isParagraphEnd = true),
                ),
            )
        }
        return chapters.flatMap { it.toScrollParagraphs() }
    }

    // ── 1. 构造器约束 ──

    @Test(expected = IllegalArgumentException::class)
    fun `paragraphNum must be 1-based`() {
        ParagraphTextPos(chapterIndex = 0, paragraphNum = 0, charOffset = 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `charOffset cannot be negative`() {
        ParagraphTextPos(chapterIndex = 0, paragraphNum = 1, charOffset = -1)
    }

    // ── 2. chapterPositionToParagraphPos ──

    @Test
    fun `chapterPositionToParagraphPos - chapter start maps to first paragraph`() {
        val window = buildThreeChapterWindow()
        val pos = chapterPositionToParagraphPos(window, chapterIndex = 1, chapterPosition = 0)
        assertNotNull(pos)
        assertEquals(1, pos!!.chapterIndex)
        assertEquals(1, pos.paragraphNum)
        assertEquals(0, pos.charOffset)
    }

    @Test
    fun `chapterPositionToParagraphPos - mid paragraph offset preserved`() {
        val window = buildThreeChapterWindow()
        // ch1 段 1 段首=0；chapterPosition=3 在段 1 内 → offset=3
        val pos = chapterPositionToParagraphPos(window, chapterIndex = 1, chapterPosition = 3)
        assertNotNull(pos)
        assertEquals(1, pos!!.paragraphNum)
        assertEquals(3, pos.charOffset)
    }

    @Test
    fun `chapterPositionToParagraphPos - second paragraph mapping`() {
        val window = buildThreeChapterWindow()
        // ch1 段 2 段首=10；chapterPosition=12 → offset=2
        val pos = chapterPositionToParagraphPos(window, chapterIndex = 1, chapterPosition = 12)
        assertNotNull(pos)
        assertEquals(2, pos!!.paragraphNum)
        assertEquals(2, pos.charOffset)
    }

    @Test
    fun `chapterPositionToParagraphPos - returns null for missing chapter`() {
        val window = buildThreeChapterWindow()
        assertNull(chapterPositionToParagraphPos(window, chapterIndex = 99, chapterPosition = 0))
    }

    @Test
    fun `chapterPositionToParagraphPos - position past last paragraph clamps to last`() {
        val window = buildThreeChapterWindow()
        // ch1 最末段是 段 2 (firstChapterPosition=10)，pos=999 → 仍命中 段 2，offset=989
        val pos = chapterPositionToParagraphPos(window, chapterIndex = 1, chapterPosition = 999)
        assertNotNull(pos)
        assertEquals(2, pos!!.paragraphNum)
        assertEquals(989, pos.charOffset)
    }

    // ── 3. toChapterPosition 反向 ──

    @Test
    fun `toChapterPosition - reverses chapterPositionToParagraphPos`() {
        val window = buildThreeChapterWindow()
        val pos = ParagraphTextPos(chapterIndex = 2, paragraphNum = 1, charOffset = 4)
        val chapPos = pos.toChapterPosition(window)
        assertEquals(4, chapPos)  // ch2 段 1 firstChapterPosition=0 + offset=4

        val pos2 = ParagraphTextPos(chapterIndex = 0, paragraphNum = 2, charOffset = 3)
        assertEquals(13, pos2.toChapterPosition(window))  // 10 + 3
    }

    @Test
    fun `toChapterPosition - returns null when paragraph not in window`() {
        val window = buildThreeChapterWindow()
        val pos = ParagraphTextPos(chapterIndex = 99, paragraphNum = 1, charOffset = 0)
        assertNull(pos.toChapterPosition(window))
    }

    // ── 4. toScrollAnchor / findItemIndex 与现有 helper 一致 ──

    @Test
    fun `toScrollAnchor - keeps chapter and paragraph, drops charOffset`() {
        val pos = ParagraphTextPos(chapterIndex = 5, paragraphNum = 3, charOffset = 99)
        val anchor = pos.toScrollAnchor()
        assertEquals(5, anchor.chapterIndex)
        assertEquals(3, anchor.paragraphNum)
        assertEquals(0, anchor.lineIdxInParagraph)
    }

    @Test
    fun `findItemIndex - matches findAnchorIndex with same paragraph`() {
        val window = buildThreeChapterWindow()
        // window 顺序：ch0-1, ch0-2, ch1-1, ch1-2, ch2-1, ch2-2
        val pos = ParagraphTextPos(chapterIndex = 1, paragraphNum = 2, charOffset = 0)
        assertEquals(3, pos.findItemIndex(window))
        assertEquals(findAnchorIndex(window, pos.toScrollAnchor()), pos.findItemIndex(window))
    }

    @Test
    fun `findItemIndex - returns minus one when missing`() {
        val window = buildThreeChapterWindow()
        val pos = ParagraphTextPos(chapterIndex = 7, paragraphNum = 1, charOffset = 0)
        assertEquals(-1, pos.findItemIndex(window))
    }

    // ── 5. round-trip 不变性 ──

    @Test
    fun `round trip - chapterPosition stays stable`() {
        val window = buildThreeChapterWindow()
        for (chIdx in 0..2) {
            for (cp in listOf(0, 3, 5, 7, 10, 14, 20)) {
                val pos = chapterPositionToParagraphPos(window, chIdx, cp) ?: continue
                val back = pos.toChapterPosition(window) ?: continue
                assertEquals("round-trip ch$chIdx@$cp 应该回到原值", cp, back)
            }
        }
    }
}
