package com.morealm.app.domain.render.layout

import com.morealm.epub.render.*

import com.morealm.epub.compat.BlockStyle
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * A1 + A2 类型骨架的最小验证 —— Atom.cpCount 契约 + ScrollAtomBridge 转换语义。
 *
 * Phase 3 真行为（hit-test / selection / Renderer）在 A3+ 才上来，本测试只覆盖
 * "数据类型本身的不变量"。
 */
class AtomTest {

    @Test
    fun `TextRun cpCount equals text length`() {
        val run = TextRun(text = "为美好的", colorArgb = null, width = 100f, height = 30f, baseline = 24f)
        assertEquals(4, run.cpCount)
    }

    @Test
    fun `TextRun preserves surrogate-pair char counting via length`() {
        // 表情 emoji 在 String.length 占 2 char unit；Atom 直接借用 String.length
        // 跟 ScrollColumn.chapterPosition 字符级递增规则对齐（每 code unit 1 cp）
        val emoji = "😃"  // 😃 = 2 char units
        val run = TextRun(text = emoji, colorArgb = null, width = 30f, height = 30f, baseline = 24f)
        assertEquals(2, run.cpCount)
    }

    @Test
    fun `InlineImage cpCount is always 1`() {
        val img = InlineImage(src = "file:///fake.png", width = 200f, height = 200f)
        assertEquals(1, img.cpCount)
    }

    @Test
    fun `InlineImage baseline equals height`() {
        val img = InlineImage(src = "file:///x.jpg", width = 50f, height = 80f)
        assertEquals(80f, img.baseline, 0f)
    }

    @Test
    fun `ScrollAtomBridge toAtoms on image line yields single InlineImage`() {
        val line = ScrollLine(
            columns = emptyList(),
            lineTop = 0f,
            lineBottom = 200f,
            paragraphNum = 1,
            isTitle = false,
            isImage = true,
            imageSrc = "file:///cover.png",
            text = "",
            firstChapterPos = 5,
            lastChapterPos = 5,
        )
        val atoms = ScrollAtomBridge.toAtoms(line)
        assertEquals(1, atoms.size)
        val img = atoms.first()
        assertTrue("expected InlineImage, was ${img::class.simpleName}", img is InlineImage)
        assertEquals(200f, img.height, 0f)
    }

    @Test
    fun `ScrollAtomBridge toAtoms on text line yields single TextRun`() {
        val cols = listOf(
            ScrollColumn(charData = "为", start = 0f, end = 30f, chapterPosition = 0),
            ScrollColumn(charData = "美", start = 30f, end = 60f, chapterPosition = 1),
            ScrollColumn(charData = "好", start = 60f, end = 90f, chapterPosition = 2),
        )
        val line = ScrollLine(
            columns = cols,
            lineTop = 0f,
            lineBottom = 40f,
            paragraphNum = 1,
            isTitle = false,
            text = "为美好",
            firstChapterPos = 0,
            lastChapterPos = 2,
        )
        val atoms = ScrollAtomBridge.toAtoms(line)
        assertEquals(1, atoms.size)
        val run = atoms.first()
        assertTrue("expected TextRun, was ${run::class.simpleName}", run is TextRun)
        run as TextRun
        assertEquals("为美好", run.text)
        assertEquals(3, run.cpCount)
        assertEquals(90f, run.width, 0f)
    }

    @Test
    fun `ScrollAtomBridge toAtoms on empty paragraph yields empty list`() {
        val line = ScrollLine(
            columns = emptyList(),
            lineTop = 0f,
            lineBottom = 20f,
            paragraphNum = 1,
            isTitle = false,
            text = "",
            firstChapterPos = 10,
            lastChapterPos = 10,
        )
        val atoms = ScrollAtomBridge.toAtoms(line)
        assertTrue("expected empty atoms for empty paragraph line", atoms.isEmpty())
    }

    @Test
    fun `ScrollAtomBridge toAtoms forwards line blockStyle textColor`() {
        val cols = listOf(
            ScrollColumn(charData = "美", start = 0f, end = 30f, chapterPosition = 0),
        )
        val line = ScrollLine(
            columns = cols,
            lineTop = 0f,
            lineBottom = 40f,
            paragraphNum = 1,
            isTitle = false,
            text = "美",
            firstChapterPos = 0,
            lastChapterPos = 0,
            blockStyle = BlockStyle(textColor = 0xFFFF0161.toInt()),
        )
        val atoms = ScrollAtomBridge.toAtoms(line)
        val run = atoms.first() as TextRun
        assertEquals(0xFFFF0161.toInt(), run.colorArgb)
    }

    @Test
    fun `ScrollAtomBridge cpCountOf matches sum-of-atoms invariant`() {
        val cols = (0 until 5).map {
            ScrollColumn(charData = "x", start = it * 20f, end = (it + 1) * 20f, chapterPosition = it)
        }
        val line = ScrollLine(
            columns = cols,
            lineTop = 0f, lineBottom = 30f,
            paragraphNum = 1, isTitle = false,
            text = "xxxxx",
            firstChapterPos = 0, lastChapterPos = 4,
        )
        assertEquals(ScrollAtomBridge.toAtoms(line).sumOf { it.cpCount }, ScrollAtomBridge.cpCountOf(line))
    }
}
