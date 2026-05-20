package com.morealm.app.domain.parser.epub.streaming

import com.morealm.app.domain.parser.epub.ChapterBlock
import com.morealm.epub.xhtml.XhtmlReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImgRewriteVisitorTest {

    private fun parseWith(lookup: (String) -> String?, xhtml: String): List<ChapterBlock> {
        val builder = ChapterBlockBuilder()
        val visitor = ImgRewriteVisitor(builder, lookup)
        XhtmlReader.parse(wrap(xhtml), visitor)
        return builder.build().blocks
    }

    private fun wrap(body: String): String =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
            "<html xmlns=\"http://www.w3.org/1999/xhtml\"><body>$body</body></html>"

    @Test
    fun `lookup hit emits Image with rewritten src`() {
        val blocks = parseWith(
            { src -> if (src == "x.jpg") "file:///cache/x.jpg" else null },
            "<img src=\"x.jpg\"/>",
        )
        assertEquals(listOf(ChapterBlock.Image("file:///cache/x.jpg")), blocks)
    }

    @Test
    fun `lookup miss drops image entirely`() {
        val blocks = parseWith({ null }, "<img src=\"missing.jpg\"/>")
        assertTrue(blocks.isEmpty())
    }

    @Test
    fun `empty src skips lookup and drops image`() {
        var calls = 0
        val blocks = parseWith({ calls++; null }, "<img src=\"\"/>")
        assertTrue(blocks.isEmpty())
        assertEquals(0, calls)
    }

    @Test
    fun `surrounding paragraphs are preserved across drop`() {
        val blocks = parseWith(
            { null },
            "<p>before</p><img src=\"missing.jpg\"/><p>after</p>",
        )
        assertEquals(
            listOf(
                ChapterBlock.Paragraph("before"),
                ChapterBlock.Paragraph("after"),
            ),
            blocks,
        )
    }

    @Test
    fun `surrounding paragraphs are preserved across hit`() {
        val blocks = parseWith(
            { "file:///$it" },
            "<p>before</p><img src=\"y.jpg\"/><p>after</p>",
        )
        assertEquals(
            listOf(
                ChapterBlock.Paragraph("before"),
                ChapterBlock.Image("file:///y.jpg"),
                ChapterBlock.Paragraph("after"),
            ),
            blocks,
        )
    }

    @Test
    fun `multiple images each go through lookup independently`() {
        val blocks = parseWith(
            { src -> if (src.endsWith(".jpg")) "file:///$src" else null },
            "<img src=\"a.jpg\"/><img src=\"b.png\"/><img src=\"c.jpg\"/>",
        )
        assertEquals(
            listOf(
                ChapterBlock.Image("file:///a.jpg"),
                ChapterBlock.Image("file:///c.jpg"),
            ),
            blocks,
        )
    }

    @Test
    fun `lookup receives original src before rewrite`() {
        val seen = ArrayList<String>()
        parseWith(
            { src -> seen.add(src); "file:///cache/$src" },
            "<img src=\"a.jpg\"/><img src=\"sub/b.png\"/>",
        )
        assertEquals(listOf("a.jpg", "sub/b.png"), seen)
    }
}
