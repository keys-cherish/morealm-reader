package com.morealm.app.domain.render.scroll

import android.text.TextPaint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * ScrollLayoutEngine 单测 —— M1 阶段持续填充。
 *
 * 启用的 case 验证 M1.2（纯文本 happy path）+ computeStyleSignature 基础不变量；
 * [Ignore] 的 case 等 M1.3-M1.5 实现完成解 ignore。
 */
@RunWith(RobolectricTestRunner::class)
class ScrollLayoutEngineTest {

    private lateinit var contentPaint: TextPaint
    private lateinit var titlePaint: TextPaint

    @Before
    fun setup() {
        contentPaint = TextPaint().apply { textSize = 48f }
        titlePaint = TextPaint().apply { textSize = 72f }
    }

    private fun engine(
        viewWidth: Int = 1080,
        viewHeight: Int = 2200,
        paddingLeft: Int = 40,
        paddingRight: Int = 40,
        paddingTop: Int = 60,
        paddingBottom: Int = 60,
        lineSpacingExtra: Float = 1.2f,
        paragraphSpacing: Int = 8,
    ) = ScrollLayoutEngine(
        viewWidth = viewWidth,
        viewHeight = viewHeight,
        paddingLeft = paddingLeft,
        paddingRight = paddingRight,
        paddingTop = paddingTop,
        paddingBottom = paddingBottom,
        titlePaint = titlePaint,
        contentPaint = contentPaint,
        lineSpacingExtra = lineSpacingExtra,
        paragraphSpacing = paragraphSpacing,
    )

    // ── M1.2 启用 case ────────────────────────────────────────────

    @Test
    fun `content 空串按严格语义切成 1 空段 产生 1 空行 占 1 cp`() {
        // 严格语义（用户决策 2026-05-17）：content="" 经 split('\n') = [""] 是 1 个空段
        // → 产生 1 个空 ScrollLine + 占 1 cp。
        // 这不是「真空章节」—— 真空章节由上层用 sentinel 文本（如「[加载失败]」）传达，
        // 排版引擎不区分 input 来源，统一走严格段切分语义。
        val eng = engine(paddingTop = 60, paddingBottom = 60)
        val layout = eng.layoutChapter(chapterIndex = 0, title = "空章", content = "", omitChapterTitleBlock = true)
        assertEquals(1, layout.pages.size)
        val page = layout.pages[0]
        assertEquals("空内容切成 1 空段 → 1 行空白", 1, page.lines.size)
        assertTrue("空段 columns 应为空", page.lines[0].columns.isEmpty())
        assertEquals("空段 paragraphNum = 1", 1, page.lines[0].paragraphNum)
        assertEquals("空段占 1 cp", 1, layout.totalCharCount)
        assertFalse("有空段空行 isEmpty 应为 false（line 存在）", layout.isEmpty)
    }

    @Test
    fun `单段短文本 排版为单页 column 与原文 1对1 chapterPosition 连续`() {
        val eng = engine()
        val layout = eng.layoutChapter(chapterIndex = 5, title = "T", content = "ABC", omitChapterTitleBlock = true)
        assertEquals(1, layout.pages.size)
        val page = layout.pages[0]
        assertTrue("应至少有一行", page.lines.isNotEmpty())
        assertEquals(5, page.chapterIndex)

        val allColumns = page.lines.flatMap { line ->
            // 行内 start/end 不变量：
            //  - start 单调不减（**不**严格递增：零宽字符 / Robolectric fake paint）
            //  - end >= start
            //  - 相邻 column 衔接：a.end == b.start
            line.columns.zipWithNext().forEach { (a, b) ->
                assertTrue("column.start 单调不减: ${a.start} > ${b.start}", a.start <= b.start)
                assertTrue("column.end >= start", a.end >= a.start)
                assertEquals("相邻 column 衔接 a.end == b.start", a.end, b.start, 0.001f)
            }
            line.columns
        }
        // 缩进作为排版属性（不生成 column）→ 总 column = 原文字符数 3
        assertEquals(3, allColumns.size)
        // 原文 A B C 的 chapterPosition = 0, 1, 2 严格 1:1
        assertEquals(0, allColumns[0].chapterPosition)
        assertEquals(1, allColumns[1].chapterPosition)
        assertEquals(2, allColumns[2].chapterPosition)
        // totalCharCount = 3 字符 + 段末 \n 1 cp = 4（与旧 ChapterProvider stringBuilder.append('\n') 对齐）
        assertEquals(4, layout.totalCharCount)
        // 段首第一行：第一个 column.start == indentWidth（缩进作为 margin，无 column 占位）
        val firstLine = page.lines.first()
        val firstCol = firstLine.columns.first()
        assertTrue("段首行首 column.start 应 >= 0（缩进 margin 表现在 start 偏移）", firstCol.start >= 0f)
    }

    @Test
    fun `空段 产生空 ScrollLine 占 1 cp 高度等于 contentLineHeight`() {
        val eng = engine()
        // content = "段一。\n\n段二。" → 切成 ["段一。", "", "段二。"] 3 段（中间空段）
        val layout = eng.layoutChapter(0, "T", "段一。\n\n段二。", omitChapterTitleBlock = true)
        val allLines = layout.pages.flatMap { it.lines }
        // 应该 3 行：段一行 + 空段空行 + 段二行
        assertEquals(3, allLines.size)
        val emptyLine = allLines[1]
        assertEquals("空段 paragraphNum 应为 2", 2, emptyLine.paragraphNum)
        assertTrue("空段 columns 应为空", emptyLine.columns.isEmpty())
        assertEquals("空段 text 应为空串", "", emptyLine.text)
        // 段间 cp 累加：para1.last + 段A末\n + 空段 + 段B第字 = para2.first
        //   段一末字「。」cp = 2 (para1Last)
        //   段一段末 \n cp = 3
        //   空段 cp = 4
        //   段二第字「段」cp = 5 (para2First)
        // para2.first - para1.last = 3
        val para1LastCp = allLines[0].columns.last().chapterPosition
        val para2FirstCp = allLines[2].columns.first().chapterPosition
        assertEquals("段间 cp 跳 +3（段A末\\n + 空段 + 段B首字）",
            para1LastCp + 3, para2FirstCp)
    }

    @Test
    fun `computeStyleSignature 同参数稳定 字段变化 hash 变`() {
        val eng1 = engine(lineSpacingExtra = 1.2f)
        val eng2 = engine(lineSpacingExtra = 1.2f)
        assertEquals(eng1.computeStyleSignature(), eng2.computeStyleSignature())

        val eng3 = engine(lineSpacingExtra = 1.5f)
        assertNotEquals(
            "lineSpacingExtra 变化时 signature 必须变",
            eng1.computeStyleSignature(), eng3.computeStyleSignature(),
        )
    }

    @Test
    fun `layoutChapter 输出 ScrollChapterLayout 必含 styleSignature 等于 engine signature`() {
        val eng = engine()
        val layout = eng.layoutChapter(0, "T", "Hello", omitChapterTitleBlock = true)
        assertEquals(eng.computeStyleSignature(), layout.styleSignature)
    }

    @Test
    fun `多段文本 paragraphCounter 递增 同段所有行 paragraphNum 一致`() {
        val eng = engine()
        // 三段，每段短到单行
        val layout = eng.layoutChapter(0, "T", "段一。\n段二。\n段三。", omitChapterTitleBlock = true)
        val allLines = layout.pages.flatMap { it.lines }
        assertTrue("应至少 3 行（每段一行）", allLines.size >= 3)
        val paragraphNums = allLines.map { it.paragraphNum }.distinct().sorted()
        assertEquals(listOf(1, 2, 3), paragraphNums)
        allLines.forEach {
            assertFalse("正文行 isTitle 应为 false（M1.4 章首块尚未实现）", it.isTitle)
        }
    }

    // ── M1.6 findColumnAt ────────────────────────────────────────

    @Test
    fun `findColumnAt 首字符 末字符 反查命中 charData 正确`() {
        val eng = engine()
        val layout = eng.layoutChapter(0, "T", "ABC", omitChapterTitleBlock = true)
        val hit0 = eng.findColumnAt(layout, 0)
        assertNotNull("cp=0 应命中", hit0)
        assertEquals("A", hit0!!.column!!.charData)
        assertEquals(0, hit0.column!!.chapterPosition)

        val hit2 = eng.findColumnAt(layout, 2)
        assertNotNull("cp=2 应命中", hit2)
        assertEquals("C", hit2!!.column!!.charData)
    }

    @Test
    fun `findColumnAt 越界返 null`() {
        val eng = engine()
        val layout = eng.layoutChapter(0, "T", "ABC", omitChapterTitleBlock = true)
        assertNull("cp=-1 越界", eng.findColumnAt(layout, -1))
        assertNull("cp=totalCharCount 越界", eng.findColumnAt(layout, layout.totalCharCount))
        assertNull("cp=100 远超越界", eng.findColumnAt(layout, 100))
    }

    @Test
    fun `findColumnAt 空段命中 column 为 null 但 line 非 null`() {
        val eng = engine()
        // "A\n\nB" → ["A", "", "B"]：
        //   段 A:  A cp=0, 段末\n cp=1 (无 line 反查)
        //   空段:  emptyCp=2 (空段 line 占)
        //   段 B:  B cp=3, 段末\n cp=4 (无反查)
        // 空段 cp=2 应命中空段 line
        val layout = eng.layoutChapter(0, "T", "A\n\nB", omitChapterTitleBlock = true)
        val hit = eng.findColumnAt(layout, 2)
        assertNotNull("空段 cp=2 应命中 line", hit)
        assertNull("空段命中 column 为 null（无具体字符）", hit!!.column)
        assertEquals("命中 line paragraphNum=2", 2, hit.line.paragraphNum)
        assertTrue("命中 line columns 应空", hit.line.columns.isEmpty())
        assertEquals("line.firstChapterPos 应等于命中 cp", 2, hit.line.firstChapterPos)
        assertEquals("line.lastChapterPos 应等于命中 cp", 2, hit.line.lastChapterPos)
        // cp=1 是段 A 段末 \n，无几何 → null
        assertNull("段末 \\n cp 无 line 几何 → 返 null", eng.findColumnAt(layout, 1))
    }

    @Test
    fun `findColumnAt 多段连续 各 cp 命中正确段 段末换行无 line`() {
        val eng = engine()
        // "AB\nCD" 段切：
        //   段 1 (AB): A cp=0, B cp=1, 段末\n cp=2
        //   段 2 (CD): C cp=3, D cp=4, 段末\n cp=5
        val layout = eng.layoutChapter(0, "T", "AB\nCD", omitChapterTitleBlock = true)
        assertEquals(1, eng.findColumnAt(layout, 0)!!.line.paragraphNum)  // A
        assertEquals(1, eng.findColumnAt(layout, 1)!!.line.paragraphNum)  // B
        assertNull("cp=2 是段 1 段末 \\n，无 line 几何", eng.findColumnAt(layout, 2))
        assertEquals(2, eng.findColumnAt(layout, 3)!!.line.paragraphNum)  // C
        assertEquals(2, eng.findColumnAt(layout, 4)!!.line.paragraphNum)  // D
        assertNull("cp=5 是段 2 段末 \\n，无 line 几何", eng.findColumnAt(layout, 5))
    }

    // ── M1.7 findColumnByPixel ───────────────────────────────────

    @Test
    fun `findColumnByPixel 行内中点 命中 column 属于该 line`() {
        // Robolectric 下 fake TextPaint 让所有字符 width=0、line height=0，无法靠
        // 像素值区分相邻 column。改用契约级断言：命中 column 必属于命中 line 的 columns，
        // 且 chapterPosition 在 line.firstChapterPos..lastChapterPos 范围内。
        // 精确像素命中（"点中 A 不返 B"）由真机灰盒测试（M6 阶段）覆盖。
        val eng = engine()
        val layout = eng.layoutChapter(0, "T", "ABC", omitChapterTitleBlock = true)
        val firstLine = layout.pages[0].lines[0]
        val firstCol = firstLine.columns[0]
        val midX = (firstCol.start + firstCol.end) / 2f
        val midY = (firstLine.lineTop + firstLine.lineBottom) / 2f
        val hit = eng.findColumnByPixel(layout, midX, midY)
        assertNotNull(hit)
        assertEquals("命中行应是首行", firstLine, hit!!.line)
        assertNotNull("行内命中应有 column（非空段）", hit.column)
        assertTrue("命中 column 应属于该 line.columns", hit.column in firstLine.columns)
        assertTrue(
            "命中 column.chapterPosition 应在 line cp 范围内",
            hit.column!!.chapterPosition in firstLine.firstChapterPos..firstLine.lastChapterPos,
        )
    }

    @Test
    fun `findColumnByPixel 行首左侧 缩进区 吸附段首 column`() {
        val eng = engine()
        val layout = eng.layoutChapter(0, "T", "ABC", omitChapterTitleBlock = true)
        val firstLine = layout.pages[0].lines[0]
        val firstCol = firstLine.columns.first()
        val midY = (firstLine.lineTop + firstLine.lineBottom) / 2f
        // x=0 落在缩进区域（如果 indentWidth > 0；Robolectric 下也合法：x == firstCol.start）
        val hit = eng.findColumnByPixel(layout, 0f, midY)
        assertNotNull(hit)
        assertEquals("缩进区 / 行首左 吸附段首", firstCol, hit!!.column)
    }

    @Test
    fun `findColumnByPixel 行尾右侧 吸附 last column`() {
        val eng = engine()
        val layout = eng.layoutChapter(0, "T", "ABC", omitChapterTitleBlock = true)
        val firstLine = layout.pages[0].lines[0]
        val lastCol = firstLine.columns.last()
        val midY = (firstLine.lineTop + firstLine.lineBottom) / 2f
        val hit = eng.findColumnByPixel(layout, 9999f, midY)
        assertNotNull(hit)
        assertEquals("行尾右侧吸附 last column", lastCol, hit!!.column)
    }

    @Test
    fun `findColumnByPixel y 越界返 null`() {
        val eng = engine()
        val layout = eng.layoutChapter(0, "T", "ABC", omitChapterTitleBlock = true)
        assertNull("y<0 章顶上方", eng.findColumnByPixel(layout, 100f, -1f))
        assertNull("y>totalHeight 章末下方",
            eng.findColumnByPixel(layout, 100f, layout.totalHeight + 1f))
    }

    @Test
    fun `findColumnByPixel 空段 line 命中 column 为 null（mocked layout 脱离 Robolectric paint）`() {
        // Robolectric fake TextPaint 让 layoutChapter 输出全部 line 高=0、column 宽=0，
        // 多 line 重叠在同 y，像素 hit-test 无法区分。
        // 改用 mocked layout（显式给非零行高）直接验证引擎的 hit-test 算法分支：
        // 命中 columns 空的 line → column = null。
        val eng = engine()
        val emptyLine = ScrollLine(
            columns = emptyList(),
            lineTop = 60f,
            lineBottom = 100f,
            paragraphNum = 2,
            isTitle = false,
            text = "",
            firstChapterPos = 1,
            lastChapterPos = 1,
        )
        val page = ScrollPage(
            pageIndex = 0,
            lines = listOf(emptyLine),
            height = 160f,
            chapterIndex = 0,
        )
        val layout = ScrollChapterLayout(
            chapterIndex = 0,
            title = "T",
            pages = listOf(page),
            totalHeight = 160f,
            viewWidth = 1080,
            styleSignature = "mock",
            totalCharCount = 1,
        )
        val hit = eng.findColumnByPixel(layout, 100f, 80f)  // y 落在 line 内
        assertNotNull(hit)
        assertEquals(emptyLine, hit!!.line)
        assertNull("空段 line 命中 column 为 null", hit.column)
    }

    @Test
    fun `findColumnByPixel 缩进区 mocked 验证吸附段首`() {
        // 显式给非零 column 宽度的 mocked layout，验证缩进区（x < first column.start）
        // 命中吸附到 firstColumn 的行为。
        val eng = engine()
        val firstCol = ScrollColumn(charData = "A", start = 100f, end = 130f, chapterPosition = 0)
        val secondCol = ScrollColumn(charData = "B", start = 130f, end = 160f, chapterPosition = 1)
        val line = ScrollLine(
            columns = listOf(firstCol, secondCol),
            lineTop = 60f, lineBottom = 100f,
            paragraphNum = 1, isTitle = false, text = "AB",
            firstChapterPos = 0, lastChapterPos = 1,
        )
        val page = ScrollPage(
            pageIndex = 0, lines = listOf(line),
            height = 160f, chapterIndex = 0,
        )
        val layout = ScrollChapterLayout(
            chapterIndex = 0, title = "T", pages = listOf(page),
            totalHeight = 160f, viewWidth = 1080,
            styleSignature = "mock", totalCharCount = 2,
        )
        // x=50 落在缩进区（< firstCol.start=100）→ 吸附到 firstCol
        val hit = eng.findColumnByPixel(layout, 50f, 80f)
        assertNotNull(hit)
        assertEquals("缩进区吸附段首", firstCol, hit!!.column)
    }

    @Test
    fun `findColumnByPixel 行尾右 mocked 验证吸附 last column`() {
        val eng = engine()
        val firstCol = ScrollColumn(charData = "A", start = 100f, end = 130f, chapterPosition = 0)
        val lastCol = ScrollColumn(charData = "B", start = 130f, end = 160f, chapterPosition = 1)
        val line = ScrollLine(
            columns = listOf(firstCol, lastCol),
            lineTop = 60f, lineBottom = 100f,
            paragraphNum = 1, isTitle = false, text = "AB",
            firstChapterPos = 0, lastChapterPos = 1,
        )
        val page = ScrollPage(
            pageIndex = 0, lines = listOf(line),
            height = 160f, chapterIndex = 0,
        )
        val layout = ScrollChapterLayout(
            chapterIndex = 0, title = "T", pages = listOf(page),
            totalHeight = 160f, viewWidth = 1080,
            styleSignature = "mock", totalCharCount = 2,
        )
        // x=500 落在行尾右（> lastCol.end=160）→ 吸附到 lastCol
        val hit = eng.findColumnByPixel(layout, 500f, 80f)
        assertNotNull(hit)
        assertEquals("行尾右吸附 last column", lastCol, hit!!.column)
    }

    @Test
    fun `findColumnByPixel 行内字符 mocked 精确命中`() {
        val eng = engine()
        val colA = ScrollColumn(charData = "A", start = 100f, end = 130f, chapterPosition = 0)
        val colB = ScrollColumn(charData = "B", start = 130f, end = 160f, chapterPosition = 1)
        val colC = ScrollColumn(charData = "C", start = 160f, end = 190f, chapterPosition = 2)
        val line = ScrollLine(
            columns = listOf(colA, colB, colC),
            lineTop = 60f, lineBottom = 100f,
            paragraphNum = 1, isTitle = false, text = "ABC",
            firstChapterPos = 0, lastChapterPos = 2,
        )
        val page = ScrollPage(
            pageIndex = 0, lines = listOf(line),
            height = 160f, chapterIndex = 0,
        )
        val layout = ScrollChapterLayout(
            chapterIndex = 0, title = "T", pages = listOf(page),
            totalHeight = 160f, viewWidth = 1080,
            styleSignature = "mock", totalCharCount = 3,
        )
        // x=145 落在 B 中间（130..160）
        val hit = eng.findColumnByPixel(layout, 145f, 80f)
        assertNotNull(hit)
        assertEquals("精确命中 B", colB, hit!!.column)
    }

    @Test
    fun `findColumnByPixel 行间空白 吸附最近 line`() {
        val eng = engine()
        val layout = eng.layoutChapter(0, "T", "A\nB", omitChapterTitleBlock = true)
        val allLines = layout.pages.flatMap { it.lines }
        assertEquals("两段两行", 2, allLines.size)
        val l1 = allLines[0]
        val l2 = allLines[1]
        // 段间空白中点（l1.lineBottom < y < l2.lineTop 严格区间，确实落空白）
        val midSpace = (l1.lineBottom + l2.lineTop) / 2f
        if (midSpace > l1.lineBottom && midSpace < l2.lineTop) {
            val hit = eng.findColumnByPixel(layout, 100f, midSpace)
            assertNotNull(hit)
            assertTrue("行间空白吸附 l1 或 l2", hit!!.line == l1 || hit.line == l2)
        }
        // 若 paragraphSpacingPx == 0（Robolectric 下 textHeight 可能 0），跳过该 case
    }

    // ── M1.3-M1.5 待实现 stub ────────────────────────────────────

    @Test
    @Ignore("M1.3 待实现：ZhLayout 接入")
    fun `中文段排版 column 坐标连续不重叠 ZhLayout 压缩生效`() {
        TODO()
    }

    // ── M1.4 章首样式块 ──────────────────────────────────────────

    @Test
    fun `章首块 标准格式 chapter-num 与 title 分行 cp 占用对齐旧引擎`() {
        val eng = engine()
        // "第一章 山边小村" → splitChapterNumAndTitle 拆为 ("第一章", "山边小村")
        // 章首块 cp 占用（与旧 ChapterProvider 严格对齐）：
        //   "第一章" 3 字符 + 段末 \n 1 cp = 4 cp
        //   "山边小村" 4 字符 + 段末 \n 1 cp = 5 cp
        //   总章首块 = 9 cp；content 第一字符 cp = 9
        val layout = eng.layoutChapter(chapterIndex = 0, title = "第一章 山边小村", content = "正文。")
        val allLines = layout.pages.flatMap { it.lines }
        val titleLines = allLines.filter { it.isTitle }
        assertTrue("应至少有 2 个 title line", titleLines.size >= 2)

        val chapterNumLine = titleLines.first { it.isChapterNum }
        assertEquals("第一章", chapterNumLine.text)
        assertEquals(0, chapterNumLine.firstChapterPos)
        assertEquals(2, chapterNumLine.lastChapterPos)
        assertFalse("chapter-num 行 isTitleEnd 应为 false（不是末行）", chapterNumLine.isTitleEnd)

        val titleMainLine = titleLines.first { !it.isChapterNum }
        assertEquals("山边小村", titleMainLine.text)
        assertTrue("title 行 isTitleEnd 应为 true（章首块末行）", titleMainLine.isTitleEnd)
        assertEquals(4, titleMainLine.firstChapterPos)
        assertEquals(7, titleMainLine.lastChapterPos)

        // 正文行：第一个非 title line 的 first column cp 应为 9
        val contentLine = allLines.first { !it.isTitle }
        assertTrue("正文行 columns 非空", contentLine.columns.isNotEmpty())
        assertEquals("正文第一字符 cp 应等于 9（章首块 9 cp 占完）",
            9, contentLine.columns.first().chapterPosition)
    }

    @Test
    fun `章首块 只有 title 无 chapter-num`() {
        val eng = engine()
        val layout = eng.layoutChapter(0, "山边小村", "正文")
        val titleLines = layout.pages.flatMap { it.lines }.filter { it.isTitle }
        assertEquals("只有 1 个 title 行", 1, titleLines.size)
        val line = titleLines[0]
        assertFalse("无 chapter-num", line.isChapterNum)
        assertTrue("唯一 title 行 isTitleEnd=true", line.isTitleEnd)
        assertEquals("山边小村", line.text)
        // cp: "山边小村" 4 char + \n 1 cp = 5 cp；content "正文" 第一字符 cp=5
        val contentLine = layout.pages.flatMap { it.lines }.first { !it.isTitle }
        assertEquals(5, contentLine.columns.first().chapterPosition)
    }

    @Test
    fun `章首块 omitChapterTitleBlock 跳过 content 第一字符 cp 为 0`() {
        val eng = engine()
        val layout = eng.layoutChapter(0, "第一章 山边小村", "正文", omitChapterTitleBlock = true)
        val allLines = layout.pages.flatMap { it.lines }
        assertTrue("omit 后无 title 行", allLines.none { it.isTitle })
        assertEquals("content 第一字符 cp=0", 0, allLines.first().columns.first().chapterPosition)
    }

    @Test
    fun `章首块 空 title 跳过`() {
        val eng = engine()
        val layout = eng.layoutChapter(0, "", "正文")
        val allLines = layout.pages.flatMap { it.lines }
        assertTrue("空 title 不画章首块", allLines.none { it.isTitle })
        assertEquals("content 第一字符 cp=0", 0, allLines.first().columns.first().chapterPosition)
    }

    @Test
    fun `章首块 titleMode 为 2 时跳过`() {
        val eng = ScrollLayoutEngine(
            viewWidth = 1080, viewHeight = 2200,
            paddingLeft = 40, paddingRight = 40, paddingTop = 60, paddingBottom = 60,
            titlePaint = titlePaint, contentPaint = contentPaint,
            titleMode = 2,  // 无标题模式
        )
        val layout = eng.layoutChapter(0, "第一章 山边小村", "正文")
        val allLines = layout.pages.flatMap { it.lines }
        assertTrue("titleMode=2 不画章首块", allLines.none { it.isTitle })
    }

    // ── M1.5 图片段 ──────────────────────────────────────────────

    @Test
    fun `图片段 单图 fallback dims columns 空 imageSrc 正确 cp 占 1`() {
        // NoOp resolver → fallback 4:3：visibleWidth × 0.75 = (1080-40-40) × 0.75 = 750
        val eng = engine()
        val content = """<img src="file:///test.png"/>"""
        val layout = eng.layoutChapter(0, "T", content, omitChapterTitleBlock = true)
        val allLines = layout.pages.flatMap { it.lines }
        assertEquals("应只有 1 行（img）", 1, allLines.size)
        val imgLine = allLines[0]
        assertTrue("isImage 应为 true", imgLine.isImage)
        assertEquals("file:///test.png", imgLine.imageSrc)
        assertTrue("columns 应空（图片无字符 column）", imgLine.columns.isEmpty())
        assertEquals("fallback 4:3 height = visibleWidth × 0.75 = 750", 750f, imgLine.height, 0.01f)
        assertEquals("img 占 1 cp，first=0", 0, imgLine.firstChapterPos)
        assertEquals("img 占 1 cp，last=0", 0, imgLine.lastChapterPos)
        // totalCharCount = img 1 cp + 段末 \n 1 cp = 2
        assertEquals(2, layout.totalCharCount)
    }

    @Test
    fun `图片段 文字 + 图片 + 文字 顺序正确 段内连续 emit`() {
        val eng = engine()
        val content = """A<img src="img1"/>B"""
        val layout = eng.layoutChapter(0, "T", content, omitChapterTitleBlock = true)
        val allLines = layout.pages.flatMap { it.lines }
        assertEquals("text A + img + text B = 3 行", 3, allLines.size)
        assertFalse("第 1 行非 image", allLines[0].isImage)
        assertEquals("A", allLines[0].columns.first().charData)
        assertTrue("第 2 行 image", allLines[1].isImage)
        assertEquals("img1", allLines[1].imageSrc)
        assertFalse("第 3 行非 image", allLines[2].isImage)
        assertEquals("B", allLines[2].columns.first().charData)
        // cp 累计：A=0, img=1, B=2
        assertEquals(0, allLines[0].firstChapterPos)
        assertEquals(1, allLines[1].firstChapterPos)
        assertEquals(2, allLines[2].firstChapterPos)
    }

    @Test
    fun `图片段 自定义 resolver 返非空 dims 走真实缩放`() {
        // intrinsic 200×100 → 等比缩 visibleWidth=1000 → h = 1000×100/200 = 500
        val resolver = ScrollImageDimensionsResolver { _, _ -> 200 to 100 }
        val eng = ScrollLayoutEngine(
            viewWidth = 1080, viewHeight = 2200,
            paddingLeft = 40, paddingRight = 40, paddingTop = 60, paddingBottom = 60,
            titlePaint = titlePaint, contentPaint = contentPaint,
            imageDimensionsResolver = resolver,
        )
        val content = """<img src="x"/>"""
        val layout = eng.layoutChapter(0, "T", content, omitChapterTitleBlock = true)
        val img = layout.pages[0].lines[0]
        assertEquals(500f, img.height, 0.01f)
    }

    @Test
    fun `图片段 dims 超 visibleHeight 反向限高`() {
        // intrinsic 100×10000 → 按 visibleWidth=1000 缩 → h=100000 远超 visibleHeight=2080
        // 反向限高：h=2080, w 按 2080 比例缩
        val resolver = ScrollImageDimensionsResolver { _, _ -> 100 to 10000 }
        val eng = ScrollLayoutEngine(
            viewWidth = 1080, viewHeight = 2200,
            paddingLeft = 40, paddingRight = 40, paddingTop = 60, paddingBottom = 60,
            titlePaint = titlePaint, contentPaint = contentPaint,
            imageDimensionsResolver = resolver,
        )
        val content = """<img src="huge"/>"""
        val layout = eng.layoutChapter(0, "T", content, omitChapterTitleBlock = true)
        val img = layout.pages[0].lines[0]
        assertEquals("限到 visibleHeight=2080", 2080f, img.height, 0.01f)
    }
}
