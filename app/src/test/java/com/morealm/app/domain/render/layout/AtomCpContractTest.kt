package com.morealm.app.domain.render.layout

import com.morealm.epub.compat.BlockStyle
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * **CP 契约强制断言** —— 把 [Atom] KDoc 列出的 4 条 invariant 固化为 CI 红灯条件。
 *
 * 跟 [AtomTest] 的分工：
 *  - [AtomTest]：单 atom 的字段属性（width/height/baseline 之类）
 *  - 本测试：**跨 atom + 跨 line 的 cp 总和不变性** —— 让 A3-A6 推进时只要破坏旧
 *    cp 计数契约就立刻 CI 红，防止旧 Highlight / Bookmark / ReadProgress 数据错位
 *
 * 设计原则：**不依赖 Paint / Density / Robolectric**。用 stub ScrollLine 直接构造，
 * 把 [ScrollLayoutEngine.emitImage] 跟 column 字符级 emit 抽象成「ScrollLine 的
 * firstChapterPos / lastChapterPos / columns 字段值」—— 这样 contract test 只看
 * 数据形状不跑排版引擎，CI < 100ms。
 *
 * 关于真排版引擎的等价性：[ScrollLayoutEngineTest] 已经覆盖了 layoutChapter 的
 * cp 递增正确性；这里的 contract 测试默认 ScrollLine.firstChapterPos /
 * lastChapterPos 是 ScrollLayoutEngine 写对的。
 */
class AtomCpContractTest {

    // ── Invariant 2: TextRun.cpCount = text.length ──────────────────────────

    @Test
    fun `TextRun cpCount equals UTF-16 char count for ASCII`() {
        assertEquals(3, run("abc").cpCount)
    }

    @Test
    fun `TextRun cpCount equals UTF-16 char count for CJK`() {
        assertEquals(2, run("中文").cpCount)
    }

    @Test
    fun `TextRun cpCount preserves surrogate pair as 2 char units`() {
        // 😀 = U+1F600 = surrogate pair = 2 UTF-16 char units = 2 cp
        // 必须跟 String.length / ScrollColumn 字符级 emit 一致（每 char unit 1 cp）
        assertEquals(2, run("😀").cpCount)
    }

    // ── Invariant 3: InlineImage.cpCount = 1 ────────────────────────────────

    @Test
    fun `InlineImage cpCount is always 1 regardless of dimensions`() {
        assertEquals(1, InlineImage("cover.jpg", 1000f, 1000f).cpCount)
        assertEquals(1, InlineImage("chibi.png", 50f, 80f).cpCount)
        assertEquals(1, InlineImage("file:///x.gif", 0f, 0f).cpCount)
    }

    // ── Invariant 1: 跨章节 atoms.sumOf{cpCount} 等于章节 cp delta ──────────

    @Test
    fun `mixed atoms sum equals chapter cp delta — text + image + text`() {
        // 模拟一段章节布局：3 字符文本 → 图片 → 2 字符文本
        // 旧引擎 chapterPositionCounter 递增：0,1,2 → 3 → 4,5
        // chapterPosDelta = 6（结束 cp） - 0 (起始 cp) = 6
        val lines = listOf(
            textLine("abc", startCp = 0, endCp = 2),
            imageLine(cp = 3),
            textLine("de", startCp = 4, endCp = 5),
        )
        val atoms = lines.flatMap { ScrollAtomBridge.toAtoms(it) }
        val legacyDelta = lines.last().lastChapterPos - lines.first().firstChapterPos + 1
        assertEquals(
            "atoms.sumOf{cpCount} 必须等于章节 cp delta（含图片 1 cp）",
            legacyDelta,
            atoms.sumOf { it.cpCount },
        )
        // 显式：3 text + 1 image + 2 text = 6
        assertEquals(6, atoms.sumOf { it.cpCount })
    }

    @Test
    fun `mixed atoms sum preserves CJK + emoji + image`() {
        // 中文（2 cp）→ emoji（surrogate 2 cp）→ image（1 cp）→ 标点（1 cp）
        // 总 = 6
        val lines = listOf(
            textLine("中文", startCp = 0, endCp = 1),
            textLine("😀", startCp = 2, endCp = 3),  // 1 emoji = 2 cp
            imageLine(cp = 4),
            textLine("。", startCp = 5, endCp = 5),
        )
        val atoms = lines.flatMap { ScrollAtomBridge.toAtoms(it) }
        assertEquals(6, atoms.sumOf { it.cpCount })
    }

    @Test
    fun `empty-paragraph line contributes 0 atoms but still occupies 1 cp in layout`() {
        // 空段 line: columns 空 + firstChapterPos=lastChapterPos（占 1 cp）
        // 当前 ScrollAtomBridge.toAtoms 返空 list —— 空段 cp 由 firstChapterPos 字段
        // 直接持有，不通过 atoms 表达。A3 改写时必须保留此分工（atoms 表 visible token，
        // cp 表 ranged position；两者不重叠）。
        val emptyLine = textLine("", startCp = 10, endCp = 10)
        val atoms = ScrollAtomBridge.toAtoms(emptyLine)
        assertTrue("空段 line 应产 0 atoms", atoms.isEmpty())
        // 但 line 仍占 1 cp（A3 改写时不能丢这个 cp）
        assertEquals(1, emptyLine.lastChapterPos - emptyLine.firstChapterPos + 1)
    }

    // ── Invariant 4 (DB schema stability) ──────────────────────────────────

    @Test
    fun `Highlight entity DB column names are stable across A6 cleanup`() {
        // 编译时锁定 DB schema 字段名 —— 用反射查这些字段必须存在，A6 改名时不能动
        val fields = com.morealm.app.domain.entity.Highlight::class.java.declaredFields
            .map { it.name }.toSet()
        listOf("startChapterPos", "endChapterPos").forEach { name ->
            assertTrue(
                "Highlight entity 必须保留 DB 字段 '$name'（用户高亮 / 书签数据迁移依赖）",
                name in fields,
            )
        }
    }

    // ── helpers (无 Paint / Density 依赖) ────────────────────────────────

    private fun run(text: String): TextRun =
        TextRun(text = text, colorArgb = null, width = 0f, height = 0f, baseline = 0f)

    private fun textLine(text: String, startCp: Int, endCp: Int): ScrollLine {
        val cols = (startCp..endCp).mapIndexed { i, cp ->
            val ch = if (i < text.length) text[i].toString() else ""
            ScrollColumn(charData = ch, start = i * 10f, end = (i + 1) * 10f, chapterPosition = cp)
        }.let {
            // 空段保留空 columns（与 ScrollLayoutEngine emit 约定一致）
            if (text.isEmpty()) emptyList() else it
        }
        return ScrollLine(
            columns = cols,
            lineTop = 0f, lineBottom = 30f,
            paragraphNum = 1, isTitle = false,
            text = text,
            firstChapterPos = startCp, lastChapterPos = endCp,
        )
    }

    private fun imageLine(cp: Int): ScrollLine = ScrollLine(
        columns = emptyList(),
        lineTop = 0f, lineBottom = 200f,
        paragraphNum = 1, isTitle = false,
        isImage = true, imageSrc = "file:///fake.png",
        text = " ",
        firstChapterPos = cp, lastChapterPos = cp,
    )

    // TODO(atom-A4): 加 `ZhLayout 不切 U+FFFC` 断言 —— A4 引入 InlineImage 真排版后,
    // 跑 ZhLayout.format("啊￼，", narrowWidth) 验证 ORC 不被 libunibreak CB 拆开。
    // 当前 A1+A2 阶段无 inline image 排版能力，跳过此断言。
}
