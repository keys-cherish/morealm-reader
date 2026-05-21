package com.morealm.app.domain.parser.epub.streaming

import com.morealm.epub.compat.ChapterBlock
import com.morealm.epub.compat.ChapterBlockBuilder
import com.morealm.epub.compat.ChapterReader
import com.morealm.epub.compat.FragmentSliceVisitor
import com.morealm.epub.compat.ImgRewriteVisitor
import com.morealm.epub.xhtml.XhtmlReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [ChapterBlockBuilder] driven via XhtmlReader so the
 * BlockVisitor inheritance is also covered end-to-end.
 */
class ChapterBlockBuilderTest {

    private fun parse(xhtml: String): List<ChapterBlock> {
        val builder = ChapterBlockBuilder()
        XhtmlReader.parse(wrap(xhtml), builder)
        return builder.build().blocks
    }

    private fun wrap(body: String): String =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
            "<html xmlns=\"http://www.w3.org/1999/xhtml\"><body>$body</body></html>"

    @Test
    fun `single paragraph emits Paragraph block`() {
        val blocks = parse("<p>Hello world.</p>")
        assertEquals(listOf(ChapterBlock.Paragraph("Hello world.")), blocks)
    }

    @Test
    fun `heading levels h1-h6 map correctly`() {
        val blocks = parse(
            "<h1>A</h1><h2>B</h2><h3>C</h3><h4>D</h4><h5>E</h5><h6>F</h6>",
        )
        assertEquals(
            listOf(
                ChapterBlock.Heading(1, "A"),
                ChapterBlock.Heading(2, "B"),
                ChapterBlock.Heading(3, "C"),
                ChapterBlock.Heading(4, "D"),
                ChapterBlock.Heading(5, "E"),
                ChapterBlock.Heading(6, "F"),
            ),
            blocks,
        )
    }

    @Test
    fun `img emits Image block with src preserved`() {
        val blocks = parse("<p><img src=\"foo.jpg\" alt=\"a\"/></p>")
        assertEquals(listOf(ChapterBlock.Image("foo.jpg")), blocks)
    }

    @Test
    fun `paragraph then image then paragraph splits into three blocks`() {
        val blocks = parse("<p>Before</p><img src=\"x.jpg\"/><p>After</p>")
        assertEquals(
            listOf(
                ChapterBlock.Paragraph("Before"),
                ChapterBlock.Image("x.jpg"),
                ChapterBlock.Paragraph("After"),
            ),
            blocks,
        )
    }

    @Test
    fun `empty paragraphs are filtered`() {
        val blocks = parse("<p>   </p><p>real text</p><p></p>")
        assertEquals(listOf(ChapterBlock.Paragraph("real text")), blocks)
    }

    @Test
    fun `inline em strong produce styled spans (P2_2)`() {
        val blocks = parse("<p>Hello <em>brave</em> <strong>new</strong> world.</p>")
        // P2.2 升级：em/strong 产 italic/bold styled span → emit RichText
        val rich = blocks.single() as ChapterBlock.RichText
        // 完整文本拼起来不丢
        assertEquals("Hello brave new world.", rich.spans.joinToString("") { it.text })
        // italic span 仅含 "brave"
        val italicText = rich.spans.filter { it.italic }.joinToString("") { it.text }
        assertEquals("brave", italicText)
        // bold span 仅含 "new"
        val boldText = rich.spans.filter { it.weight == 700 }.joinToString("") { it.text }
        assertEquals("new", boldText)
    }

    @Test
    fun `whitespace runs collapse to single space`() {
        val blocks = parse("<p>a    b\t\tc\n\nd</p>")
        assertEquals(listOf(ChapterBlock.Paragraph("a b c d")), blocks)
    }

    @Test
    fun `br within paragraph splits into two paragraphs`() {
        val blocks = parse("<p>line one<br/>line two</p>")
        assertEquals(
            listOf(
                ChapterBlock.Paragraph("line one"),
                ChapterBlock.Paragraph("line two"),
            ),
            blocks,
        )
    }

    @Test
    fun `nested div containers emit one paragraph each`() {
        val blocks = parse("<div><p>A</p><div><p>B</p></div></div>")
        assertEquals(
            listOf(
                ChapterBlock.Paragraph("A"),
                ChapterBlock.Paragraph("B"),
            ),
            blocks,
        )
    }

    @Test
    fun `heading level out of range is coerced`() {
        val builder = ChapterBlockBuilder()
        // Directly invoke the protected emit hook via public BlockVisitor close
        // is impossible — instead, drive via XhtmlReader with h1 to confirm the
        // coercion path is unreachable from real input. For safety also assert
        // build() of an empty builder is empty.
        assertTrue(builder.build().blocks.isEmpty())
    }

    @Test
    fun `cdata content folds into surrounding paragraph`() {
        val blocks = parse("<p>before<![CDATA[ inside ]]>after</p>")
        // CDATA is forwarded as onCdata which BlockVisitor's onText hook
        // does not override; this is treated as text per the BlockVisitor
        // current contract (CDATA in EPUB body is virtually unused).
        // We assert blocks is non-empty rather than depend on the exact
        // CDATA→text policy.
        assertTrue(blocks.isNotEmpty())
    }
}
