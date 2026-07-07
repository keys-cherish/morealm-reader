package com.morealm.app.domain.parser

import com.morealm.app.domain.entity.BookChapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.charset.Charset
import java.util.regex.Pattern

/**
 * TXT 字节级 TOC 扫描（[LocalBookParser.parseTocBytewise]）的等价性单测。
 *
 * oracle = 旧字符级实现 [LocalBookParser.parseTocCharwise]：同一文件两条路径产出的
 * 章节列表 title/startPosition/endPosition 必须完全一致（brief 契约用例 3，UTF-8 与
 * GBK 各一个样例）。另测 bytewise 独有的健壮性路径：跨块 pending 行、超长行 overflow、
 * 无换行结尾文件的精确 endPosition（charwise 有 +1 固有误差，文档已注明）。
 */
class LocalBookParserBytewiseTest {

    private val tocPattern: Pattern =
        Pattern.compile("^\\s*第[零一二三四五六七八九十百千万\\d]+[章节回卷集部篇].*")

    private val utf8: Charset = Charsets.UTF_8
    private val gbk: Charset = Charset.forName("GBK")

    /** 标准样例：前言 + 3 个章节标题（含全角空格缩进标题），换行结尾。 */
    private val sampleContent = buildString {
        append("这是一本测试书的前言部分。\n")
        append("介绍性文字第二行。\n")
        append("第一章 初见\n")
        repeat(5) { append("正文内容甲，普通段落文字。\n") }
        append("第二章 转折\n")
        repeat(5) { append("正文内容乙，普通段落文字。\n") }
        append("　　第三章 结局\n")
        repeat(5) { append("正文内容丙，普通段落文字。\n") }
    }

    private fun parseBytewise(data: ByteArray, cs: Charset): List<BookChapter> =
        LocalBookParser.parseTocBytewise({ ByteArrayInputStream(data) }, cs, tocPattern)

    private fun parseCharwise(data: ByteArray, cs: Charset): List<BookChapter> =
        LocalBookParser.parseTocCharwise({ ByteArrayInputStream(data) }, cs, tocPattern)

    private fun assertChaptersEqual(expected: List<BookChapter>, actual: List<BookChapter>) {
        assertEquals("章节数不一致", expected.size, actual.size)
        expected.zip(actual).forEachIndexed { i, (e, a) ->
            assertEquals("chapter[$i].title", e.title, a.title)
            assertEquals("chapter[$i].startPosition", e.startPosition, a.startPosition)
            assertEquals("chapter[$i].endPosition", e.endPosition, a.endPosition)
        }
    }

    /**
     * 新旧实现对拍：除**末章 endPosition** 外逐字段全等。
     *
     * 末章终点是 oracle 的已知固有误差（恒 +1：\n 结尾 → split 幻影空尾元素；
     * 非 \n 结尾 → 末行补 \n，见 parseTocCharwise KDoc）——bytewise 修正为精确
     * 字节数。这里显式断言差异，防止未来把 bytewise "改回去对齐 oracle"。
     */
    private fun assertOracleParity(data: ByteArray, cs: Charset): List<BookChapter> {
        val byteChapters = parseBytewise(data, cs)
        val charChapters = parseCharwise(data, cs)
        assertEquals("章节数不一致", charChapters.size, byteChapters.size)
        charChapters.zip(byteChapters).forEachIndexed { i, (e, a) ->
            assertEquals("chapter[$i].title", e.title, a.title)
            assertEquals("chapter[$i].startPosition", e.startPosition, a.startPosition)
            if (i < charChapters.size - 1) {
                assertEquals("chapter[$i].endPosition", e.endPosition, a.endPosition)
            }
        }
        assertEquals("bytewise 末章终点应为精确文件字节数",
            data.size.toLong(), byteChapters.last().endPosition)
        assertEquals("oracle 末章终点固有 +1（此断言挂了说明旧实现被改动，请同步审视对拍前提）",
            data.size.toLong() + 1, charChapters.last().endPosition)
        return byteChapters
    }

    // ── 契约用例 3：新旧实现对拍 ─────────────────────────

    @Test
    fun `bytewise equals charwise oracle - UTF-8`() {
        val data = sampleContent.toByteArray(utf8)
        val byteChapters = assertOracleParity(data, utf8)

        // 结构自证：前言 + 三章，全书字节覆盖无缝无洞
        assertEquals(4, byteChapters.size)
        assertEquals("前言", byteChapters[0].title)
        assertEquals("第一章 初见", byteChapters[1].title)
        assertEquals("第二章 转折", byteChapters[2].title)
        assertEquals("第三章 结局", byteChapters[3].title)
        assertEquals(0L, byteChapters.first().startPosition)
        assertEquals(data.size.toLong(), byteChapters.last().endPosition)
        byteChapters.zipWithNext().forEach { (a, b) ->
            assertEquals(a.endPosition, b.startPosition)
        }
    }

    @Test
    fun `bytewise equals charwise oracle - GBK`() {
        val data = sampleContent.toByteArray(gbk)
        val byteChapters = assertOracleParity(data, gbk)
        assertEquals(4, byteChapters.size)
        assertEquals(data.size.toLong(), byteChapters.last().endPosition)
    }

    @Test
    fun `title at byte 0 merged into preface - parity`() {
        // 历史行为：第 0 字节的标题行不切章（lineStart > chapterStart 不成立），
        // 首个 emit 的章节标题为「前言」。两实现必须一致。
        val content = buildString {
            append("第一章 开局\n")
            repeat(3) { append("正文一。\n") }
            append("第二章 次章\n")
            repeat(3) { append("正文二。\n") }
        }
        val data = content.toByteArray(utf8)
        val byteChapters = assertOracleParity(data, utf8)
        assertEquals("前言", byteChapters[0].title)
        assertEquals("第二章 次章", byteChapters[1].title)
    }

    // ── bytewise 独有健壮性 ─────────────────────────────

    /** 模拟慢速/分片 IO：每次 read 最多返回 [maxChunk] 字节，逼出跨块 pending 路径。 */
    private class ChunkedInputStream(
        private val data: ByteArray,
        private val maxChunk: Int,
    ) : InputStream() {
        private var pos = 0
        override fun read(): Int =
            if (pos < data.size) data[pos++].toInt() and 0xFF else -1

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (pos >= data.size) return -1
            val n = minOf(len, maxChunk, data.size - pos)
            System.arraycopy(data, pos, b, off, n)
            pos += n
            return n
        }
    }

    @Test
    fun `chunked reads produce identical result - lines split across blocks`() {
        // 7 字节一块：每个多字节汉字、每个标题行都被撕成多块，全走 pending 缓冲
        val data = sampleContent.toByteArray(utf8)
        val whole = parseBytewise(data, utf8)
        val chunked = LocalBookParser.parseTocBytewise(
            { ChunkedInputStream(data, 7) }, utf8, tocPattern,
        )
        assertChaptersEqual(whole, chunked)
    }

    @Test
    fun `overlong line skipped via overflow without corrupting offsets`() {
        // 超过 MAX_TITLE_BYTES(512) 的行不可能是标题：跨块累积时丢内容只记 overflow，
        // 后续章节偏移必须不受影响（与 oracle 对拍验证）
        val content = buildString {
            append("前言文字。\n")
            append("第一章 正常\n")
            append("x".repeat(2000)).append("\n") // 病态超长行
            append("第二章 收尾\n")
            repeat(3) { append("正文。\n") }
        }
        val data = content.toByteArray(utf8)
        // 对拍 + 超长行所在章节字节区间完整
        val byteChapters = assertOracleParity(data, utf8)
        // 333 字节分块下同样成立（overflow 跨多块累积）
        val chunked = LocalBookParser.parseTocBytewise(
            { ChunkedInputStream(data, 333) }, utf8, tocPattern,
        )
        assertChaptersEqual(byteChapters, chunked)
    }

    @Test
    fun `file without trailing newline - bytewise endPosition is exact`() {
        // charwise 固有误差：末行按 (line+"\n") 估算，无换行结尾时末章 endPosition 多 1 字节
        // （readTxtChapter 有 coerce 兜底）。bytewise 修正为精确字节数——这里显式断言
        // 两者的已知差异，防止未来有人把 bytewise "改回去对齐 oracle"。
        val content = "前言。\n第一章 唯一\n正文最后一行没有换行"
        val data = content.toByteArray(utf8)
        val byteChapters = parseBytewise(data, utf8)
        val charChapters = parseCharwise(data, utf8)

        assertEquals(data.size.toLong(), byteChapters.last().endPosition)
        assertEquals(data.size.toLong() + 1, charChapters.last().endPosition)
        // 除末章 endPosition 外全部一致
        assertChaptersEqual(
            charChapters.dropLast(1),
            byteChapters.dropLast(1),
        )
        assertEquals(charChapters.last().title, byteChapters.last().title)
        assertEquals(charChapters.last().startPosition, byteChapters.last().startPosition)
    }

    @Test
    fun `isNewlineByteSafe charset classification`() {
        assertTrue(LocalBookParser.isNewlineByteSafe(Charsets.UTF_8))
        assertTrue(LocalBookParser.isNewlineByteSafe(gbk))
        assertTrue(LocalBookParser.isNewlineByteSafe(Charset.forName("GB18030")))
        assertTrue(LocalBookParser.isNewlineByteSafe(Charset.forName("Big5")))
        assertFalse(LocalBookParser.isNewlineByteSafe(Charsets.UTF_16LE))
        assertFalse(LocalBookParser.isNewlineByteSafe(Charsets.UTF_16BE))
        assertFalse(LocalBookParser.isNewlineByteSafe(Charsets.UTF_16))
        assertFalse(LocalBookParser.isNewlineByteSafe(Charset.forName("UTF-32LE")))
    }
}
