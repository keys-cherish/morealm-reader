package com.morealm.app.domain.parser.epub.streaming

import com.morealm.epub.compat.ChapterBlock
import com.morealm.epub.compat.ChapterBlockBuilder
import com.morealm.epub.compat.ChapterReader
import com.morealm.epub.compat.FragmentSliceVisitor
import com.morealm.epub.compat.ImgRewriteVisitor
import com.morealm.epub.compat.RubyRewriteVisitor
import com.morealm.epub.compat.SvgImageRewriteVisitor
import com.morealm.epub.compat.TableMergeVisitor
import com.morealm.epub.xhtml.XhtmlReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end coverage of the visitor pipeline the StreamingChapterReader
 * assembles per spine item — same chain order, same decorators, just driven
 * by a literal XHTML string instead of a [com.morealm.epub.EpubBook] +
 * [com.morealm.app.domain.entity.BookChapter] pair.
 *
 * The cross-spine assembly and cover sentinel paths in
 * [StreamingChapterReader.read] are covered by D.5b's device validation
 * since they require a real [com.morealm.epub.EpubBook] instance.
 */
class StreamingChainIntegrationTest {

    private fun parseChain(
        xhtml: String,
        imgLookup: (String) -> String? = { "file:///$it" },
        startFragment: String? = null,
        endFragment: String? = null,
    ): List<ChapterBlock> {
        val builder = ChapterBlockBuilder()
        val imgRewrite = ImgRewriteVisitor(builder, imgLookup)
        val tableMerge = TableMergeVisitor(imgRewrite)
        val rubyRewrite = RubyRewriteVisitor(tableMerge)
        val svgRewrite = SvgImageRewriteVisitor(rubyRewrite)
        val fragmentSlice = FragmentSliceVisitor(svgRewrite, startFragment, endFragment)
        XhtmlReader.parse(wrap(xhtml), fragmentSlice)
        return builder.build().blocks
    }

    private fun wrap(body: String): String =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
            "<html xmlns=\"http://www.w3.org/1999/xhtml\"><body>$body</body></html>"

    @Test
    fun `svg wrapped image is rewritten to img and resolved`() {
        val blocks = parseChain(
            "<svg viewBox=\"0 0 1200 1800\" xmlns:xlink=\"http://www.w3.org/1999/xlink\">" +
                "<image width=\"1200\" height=\"1800\" xlink:href=\"cover.jpg\"/>" +
                "</svg>",
        )
        assertEquals(listOf(ChapterBlock.Image("file:///cover.jpg")), blocks)
    }

    @Test
    fun `ruby annotations are inlined into surrounding paragraph`() {
        val blocks = parseChain("<p>before<ruby>八奈見<rt>やなみ</rt></ruby>です。</p>")
        assertEquals(
            listOf(ChapterBlock.Paragraph("before八奈見(やなみ)です。")),
            blocks,
        )
    }

    @Test
    fun `sibling tables merge into one paragraph`() {
        val blocks = parseChain(
            "<table><tr><td>为美好的</td></tr></table>" +
                "<table><tr><td>世界献上</td></tr></table>" +
                "<table><tr><td>祝福</td></tr></table>",
        )
        assertEquals(1, blocks.size)
        val text = (blocks[0] as ChapterBlock.Paragraph).text
        assertTrue("got $text", text.contains("为美好的"))
        assertTrue("got $text", text.contains("祝福"))
    }

    @Test
    fun `script and style content suppressed`() {
        val blocks = parseChain(
            "<p>first</p><script>alert(1)</script><p>second</p><style>.x{}</style>",
        )
        assertEquals(
            listOf(
                ChapterBlock.Paragraph("first"),
                ChapterBlock.Paragraph("second"),
            ),
            blocks,
        )
    }

    @Test
    fun `image inside fragment range is rewritten`() {
        val blocks = parseChain(
            "<p>before</p><div id=\"s\"><p>head</p><img src=\"in.jpg\"/></div><p>after</p>",
            startFragment = "s",
        )
        assertEquals(
            listOf(
                ChapterBlock.Paragraph("head"),
                ChapterBlock.Image("file:///in.jpg"),
                ChapterBlock.Paragraph("after"),
            ),
            blocks,
        )
    }

    @Test
    fun `image dropped on lookup miss does not break surrounding paragraph`() {
        val blocks = parseChain(
            "<p>line one</p><img src=\"x.jpg\"/><p>line two</p>",
            imgLookup = { null },
        )
        assertEquals(
            listOf(
                ChapterBlock.Paragraph("line one"),
                ChapterBlock.Paragraph("line two"),
            ),
            blocks,
        )
    }

    @Test
    fun `heading and paragraph in nested div preserved across the chain`() {
        val blocks = parseChain("<div><h2>Sec</h2><p>body</p></div>")
        assertEquals(
            listOf(
                ChapterBlock.Heading(2, "Sec"),
                ChapterBlock.Paragraph("body"),
            ),
            blocks,
        )
    }
}
