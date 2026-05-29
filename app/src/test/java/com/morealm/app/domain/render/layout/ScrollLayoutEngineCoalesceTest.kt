package com.morealm.app.domain.render.layout

import android.text.TextPaint
import com.morealm.epub.compat.StructuredChapterContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * **Step 9.2 Phase B / Phase 3** —— ScrollLayoutEngine.emitOneLine 的 bg-only coalesce 单测。
 *
 * 验证含 SPAN_BG marker 的章节经 epub-layout parseInlineMarkers(bgRuns) → emitOneLine 后：
 * 同 boxId 连续字符 coalesce 成单 [TextRun]（带 inlineBg* 字段）；非 bg 字符仍每字符 1 atom
 * （现有 color/size/image 路径零行为变化）。SPAN_BG marker 用 epub-compat 公开协议常量构造，
 * 与 emit 端字节对齐。
 */
@RunWith(RobolectricTestRunner::class)
class ScrollLayoutEngineCoalesceTest {

    private lateinit var contentPaint: TextPaint
    private lateinit var titlePaint: TextPaint

    @Before
    fun setup() {
        contentPaint = TextPaint().apply { textSize = 48f }
        titlePaint = TextPaint().apply { textSize = 72f }
    }

    private fun engine() = ScrollLayoutEngine(
        viewWidth = 1080,
        viewHeight = 2200,
        paddingLeft = 40,
        paddingRight = 40,
        paddingTop = 60,
        paddingBottom = 60,
        titlePaint = titlePaint,
        contentPaint = contentPaint,
    )

    /** 构造 emit 格式 SPAN_BG marker；[pad] = scaled int（px×100），与 emitSpanBgHeader 一致。 */
    private fun spanBg(argbHex: String, text: String, boxId: Int, pad: Int = 0): String {
        val d = StructuredChapterContent.SPAN_BG_DELIM
        return StructuredChapterContent.SPAN_BG_START + argbHex + d + "$pad" + d + "$pad" + d +
            "$pad" + d + "$pad" + d + "$boxId" + StructuredChapterContent.SPAN_BG_END + text +
            StructuredChapterContent.SPAN_BG_RESET
    }

    private fun ScrollChapterLayout.allAtoms(): List<Atom> =
        pages.flatMap { it.lines }.flatMap { it.atoms ?: emptyList() }

    @Test
    fun `同 box 多字符 coalesce 成单 TextRun 带 bg 字段`() {
        val eng = engine()
        val content = spanBg("ffc27c88", "内容", boxId = 1)
        val layout = eng.layoutChapter(0, "T", content, omitChapterTitleBlock = true)
        val bgRuns = layout.allAtoms().filterIsInstance<TextRun>().filter { it.inlineBgBoxId != null }
        assertEquals("内容 同 box → 1 coalesced TextRun", 1, bgRuns.size)
        assertEquals("内容", bgRuns[0].text)
        assertEquals(1, bgRuns[0].inlineBgBoxId)
        assertEquals(0xffc27c88.toInt(), bgRuns[0].inlineBgArgb)
    }

    @Test
    fun `两 box 中间空格 → 2 独立 bg TextRun（不同 boxId 不 merge）`() {
        val eng = engine()
        val content = spanBg("ffff0000", "内", boxId = 1) + " " + spanBg("ff0000ff", "容", boxId = 2)
        val layout = eng.layoutChapter(0, "T", content, omitChapterTitleBlock = true)
        val bgRuns = layout.allAtoms().filterIsInstance<TextRun>().filter { it.inlineBgBoxId != null }
        assertEquals("两 box → 2 独立 TextRun", 2, bgRuns.size)
        assertEquals(setOf(1, 2), bgRuns.mapNotNull { it.inlineBgBoxId }.toSet())
    }

    @Test
    fun `纯文本无 SPAN_BG 不产生带 bg 的 atom（零开销 fast path）`() {
        val eng = engine()
        val layout = eng.layoutChapter(0, "T", "纯文本无标记", omitChapterTitleBlock = true)
        assertTrue(
            "无 SPAN_BG → 无带 bg 的 atom",
            layout.allAtoms().filterIsInstance<TextRun>().none { it.inlineBgBoxId != null },
        )
    }

    @Test
    fun `bg padding 从 marker 还原到 TextRun（scaled 300 = 3px）`() {
        val eng = engine()
        val content = spanBg("ffff0000", "字", boxId = 1, pad = 300)
        val layout = eng.layoutChapter(0, "T", content, omitChapterTitleBlock = true)
        val run = layout.allAtoms().filterIsInstance<TextRun>().first { it.inlineBgBoxId != null }
        assertEquals(3f, run.inlineBgPaddingLeftPx)
        assertEquals(3f, run.inlineBgPaddingTopPx)
        assertEquals(3f, run.inlineBgPaddingRightPx)
        assertEquals(3f, run.inlineBgPaddingBottomPx)
    }
}
