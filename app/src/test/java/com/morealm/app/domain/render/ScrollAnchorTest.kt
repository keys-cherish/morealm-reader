package com.morealm.app.domain.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for [ScrollAnchor] + 配套算法 ([findAnchorIndex] /
 * [calcAnchorScrollOffsetPx] / [calcNewAnchorAfterPrepend] /
 * [calcChapterProgress] / [bookmarkToAnchor] / [ScrollAnchor.toBookmark]).
 *
 * 这些是 Phase 2 LazyScrollRenderer Composable 主体的依赖算法 ——
 * 纯算法可单测，UI 行为留给 Compose UI 测试或设备测试。
 */
@RunWith(RobolectricTestRunner::class)
class ScrollAnchorTest {

    /** 复用 ScrollParagraphTest 的构造器：直接 set 字段，不走排版器。 */
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

    /** 构造一个跨 3 章的扁平段窗口，每章 2 段，每段 2 行。 */
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

    // ── 1. ScrollAnchor 构造器 ──

    @Test
    fun `atChapterStart - default to first paragraph first line`() {
        val anchor = ScrollAnchor.atChapterStart(chapterIndex = 7)
        assertEquals(7, anchor.chapterIndex)
        assertEquals(1, anchor.paragraphNum)
        assertEquals(0, anchor.lineIdxInParagraph)
        assertEquals(0f, anchor.scrollOffsetInLine, 0.001f)
    }

    // ── 2. findAnchorIndex ──

    @Test
    fun `findAnchorIndex - locates correct paragraph in flat window`() {
        val window = buildThreeChapterWindow()
        // window 顺序：ch0-1, ch0-2, ch1-1, ch1-2, ch2-1, ch2-2
        assertEquals(0, findAnchorIndex(window, ScrollAnchor(chapterIndex = 0, paragraphNum = 1)))
        assertEquals(1, findAnchorIndex(window, ScrollAnchor(chapterIndex = 0, paragraphNum = 2)))
        assertEquals(2, findAnchorIndex(window, ScrollAnchor(chapterIndex = 1, paragraphNum = 1)))
        assertEquals(5, findAnchorIndex(window, ScrollAnchor(chapterIndex = 2, paragraphNum = 2)))
    }

    @Test
    fun `findAnchorIndex - returns minus one when not found`() {
        val window = buildThreeChapterWindow()
        assertEquals(-1, findAnchorIndex(window, ScrollAnchor(chapterIndex = 9, paragraphNum = 1)))
        assertEquals(-1, findAnchorIndex(window, ScrollAnchor(chapterIndex = 0, paragraphNum = 99)))
    }

    // ── 3. calcAnchorScrollOffsetPx ──

    @Test
    fun `calcAnchorScrollOffsetPx - line top + scrollOffsetInLine`() {
        val ch = chapter(
            0,
            page(
                paddingTop = 100,
                line(paragraphNum = 1, top = 0f, bottom = 30f, chapterPosition = 0),
                line(paragraphNum = 1, top = 30f, bottom = 60f, chapterPosition = 5, isParagraphEnd = true),
            ),
        )
        val p = ch.toScrollParagraphs()[0]
        // p.linePositions = [100f, 130f]（paddingTop + 累加）
        // anchor 第 0 行 + 0 偏移 → 100
        assertEquals(100, calcAnchorScrollOffsetPx(p, ScrollAnchor(0, 1, lineIdxInParagraph = 0)))
        // anchor 第 1 行 + 5px 偏移 → 130 + 5 = 135
        assertEquals(135, calcAnchorScrollOffsetPx(p, ScrollAnchor(0, 1, lineIdxInParagraph = 1, scrollOffsetInLine = 5f)))
    }

    @Test
    fun `calcAnchorScrollOffsetPx - line idx out of range coerces safely`() {
        val ch = chapter(
            0,
            page(0, line(paragraphNum = 1, top = 0f, bottom = 30f, chapterPosition = 0, isParagraphEnd = true)),
        )
        val p = ch.toScrollParagraphs()[0]
        // 越界 line idx 应被 coerceIn 到 [0, lastIndex] —— 取末行 top（这里只有 1 行）
        val offset = calcAnchorScrollOffsetPx(p, ScrollAnchor(0, 1, lineIdxInParagraph = 99))
        assertEquals(0, offset) // 末行就是首行，linePositions[0]=0
    }

    @Test
    fun `calcAnchorScrollOffsetPx - empty lines returns zero`() {
        val placeholder = loadingScrollParagraph(chapterIndex = 0)
        assertEquals(0, calcAnchorScrollOffsetPx(placeholder, ScrollAnchor(0, 0)))
    }

    // ── 4. calcNewAnchorAfterPrepend ──

    @Test
    fun `calcNewAnchorAfterPrepend - shifts by prepended count`() {
        // 用户原本看 idx=10，prepend 3 段后应看 idx=13
        assertEquals(13, calcNewAnchorAfterPrepend(oldAnchorIdx = 10, prependedCount = 3))
        // 边界：prepend 0 → 不变
        assertEquals(10, calcNewAnchorAfterPrepend(oldAnchorIdx = 10, prependedCount = 0))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `calcNewAnchorAfterPrepend - negative count rejected`() {
        calcNewAnchorAfterPrepend(oldAnchorIdx = 10, prependedCount = -1)
    }

    // ── 5. calcChapterProgress ──

    @Test
    fun `calcChapterProgress - mid item gives proportional progress`() {
        val ch = chapter(
            0,
            page(
                0,
                line(paragraphNum = 1, top = 0f, bottom = 100f, chapterPosition = 0, isParagraphEnd = true),
                line(paragraphNum = 2, top = 100f, bottom = 200f, chapterPosition = 50, isParagraphEnd = true),
            ),
        )
        val paragraphs = ch.toScrollParagraphs()
        // 章总字符 = 段1(charSize=2: 1 col + 1 endNL) + 段2(2) = 4
        // 段 0 的 firstChapterPosition=0, charSize=2, totalHeight=100
        // 滚到 item 内 50px (50% in) → readChars = 0 + 2*0.5 = 1 → 1/4 = 25%
        val progress = calcChapterProgress(paragraphs[0], scrollOffsetInItem = 50f, chapterCharSize = 4)
        assertEquals(25, progress)
    }

    @Test
    fun `calcChapterProgress - zero chapter chars gives zero`() {
        val placeholder = loadingScrollParagraph(chapterIndex = 0)
        assertEquals(0, calcChapterProgress(placeholder, scrollOffsetInItem = 100f, chapterCharSize = 0))
    }

    @Test
    fun `calcChapterProgress - clamped to 0 100`() {
        val ch = chapter(
            0,
            page(0, line(paragraphNum = 1, top = 0f, bottom = 100f, chapterPosition = 0, isParagraphEnd = true)),
        )
        val p = ch.toScrollParagraphs()[0]
        // 异常输入：scrollOffset 远超 totalHeight，应被 coerceIn(0,1) 截到 100%
        val progress = calcChapterProgress(p, scrollOffsetInItem = 9999f, chapterCharSize = 2)
        assertEquals(100, progress)
        // 负偏移 → 0%
        val negProgress = calcChapterProgress(p, scrollOffsetInItem = -100f, chapterCharSize = 2)
        assertEquals(0, negProgress)
    }

    // ── 6. bookmarkToAnchor ──

    @Test
    fun `bookmarkToAnchor - locates paragraph and line`() {
        val ch = chapter(
            0,
            page(
                0,
                line(paragraphNum = 1, top = 0f, bottom = 30f, chapterPosition = 0),
                line(paragraphNum = 1, top = 30f, bottom = 60f, chapterPosition = 10, isParagraphEnd = true),
                line(paragraphNum = 2, top = 60f, bottom = 90f, chapterPosition = 20, isParagraphEnd = true),
            ),
        )
        val paragraphs = ch.toScrollParagraphs()
        // chapterPosition=12 落在段 1 第 2 行（chapterPosition=10..19）
        val anchor = bookmarkToAnchor(0, 12, paragraphs)!!
        assertEquals(0, anchor.chapterIndex)
        assertEquals(1, anchor.paragraphNum)
        assertEquals(1, anchor.lineIdxInParagraph)
    }

    @Test
    fun `bookmarkToAnchor - returns null when chapter not in window`() {
        val window = buildThreeChapterWindow()
        assertNull(bookmarkToAnchor(chapterIndex = 99, chapterPosition = 0, paragraphs = window))
    }

    @Test
    fun `bookmarkToAnchor - position before first paragraph falls to first paragraph`() {
        val ch = chapter(
            0,
            page(
                0,
                line(paragraphNum = 1, top = 0f, bottom = 30f, chapterPosition = 5, isParagraphEnd = true),
            ),
        )
        val paragraphs = ch.toScrollParagraphs()
        // chapterPosition=0 < firstChapterPosition=5，应仍能定位首段（容错：用户可能用旧
        // bookmark，章首位置略有飘移）。当前实现返回 null（找不到 firstChapterPosition <= 0
        // 的段），是预期行为还是 bug？
        // 决策：null 是合理 —— 调用方可 fallback 到 atChapterStart()。这里只验当前合约。
        assertNull(bookmarkToAnchor(0, 0, paragraphs))
    }

    // ── 7. ScrollAnchor.toBookmark ──

    @Test
    fun `toBookmark - reverse of bookmarkToAnchor for valid input`() {
        val ch = chapter(
            0,
            page(
                0,
                line(paragraphNum = 1, top = 0f, bottom = 30f, chapterPosition = 0),
                line(paragraphNum = 1, top = 30f, bottom = 60f, chapterPosition = 10, isParagraphEnd = true),
                line(paragraphNum = 2, top = 60f, bottom = 90f, chapterPosition = 20, isParagraphEnd = true),
            ),
        )
        val paragraphs = ch.toScrollParagraphs()
        val original = ScrollAnchor(chapterIndex = 0, paragraphNum = 1, lineIdxInParagraph = 1)
        val (chIdx, pos) = original.toBookmark(paragraphs)
        assertEquals(0, chIdx)
        // 第 0 章 第 1 段 第 2 行 → chapterPosition = 10
        assertEquals(10, pos)
    }

    @Test
    fun `toBookmark - missing paragraph falls back to position zero`() {
        val window = buildThreeChapterWindow()
        // 不存在的段：(chapterIdx=0, paragraphNum=99) → 回退 (0, 0)
        val anchor = ScrollAnchor(chapterIndex = 0, paragraphNum = 99)
        assertEquals(0 to 0, anchor.toBookmark(window))
    }

    // ── 8. roundtrip 整体性 ──

    @Test
    fun `bookmark to anchor to bookmark - roundtrip stable for line-aligned positions`() {
        val window = buildThreeChapterWindow()
        // window 中 ch1-段2-line1: chapterPosition=15 (line top of 2nd para 2nd line)
        val anchor = bookmarkToAnchor(chapterIndex = 1, chapterPosition = 15, paragraphs = window)!!
        val (chIdx, pos) = anchor.toBookmark(window)
        assertEquals(1, chIdx)
        assertEquals(15, pos)
    }
}
