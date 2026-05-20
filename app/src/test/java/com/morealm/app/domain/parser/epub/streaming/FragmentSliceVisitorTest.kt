package com.morealm.app.domain.parser.epub.streaming

import com.morealm.app.domain.parser.epub.ChapterBlock
import com.morealm.epub.xhtml.XhtmlReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FragmentSliceVisitorTest {

    private fun parseWith(
        start: String?,
        end: String?,
        xhtml: String,
    ): List<ChapterBlock> {
        val builder = ChapterBlockBuilder()
        val slice = FragmentSliceVisitor(builder, start, end)
        XhtmlReader.parse(wrap(xhtml), slice)
        return builder.build().blocks
    }

    private fun wrap(body: String): String =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
            "<html xmlns=\"http://www.w3.org/1999/xhtml\"><body>$body</body></html>"

    @Test
    fun `both fragments null forwards entire body`() {
        val blocks = parseWith(null, null, "<p>A</p><p>B</p>")
        assertEquals(
            listOf(ChapterBlock.Paragraph("A"), ChapterBlock.Paragraph("B")),
            blocks,
        )
    }

    @Test
    fun `both fragments empty forwards entire body`() {
        val blocks = parseWith("", "", "<p>A</p><p>B</p>")
        assertEquals(
            listOf(ChapterBlock.Paragraph("A"), ChapterBlock.Paragraph("B")),
            blocks,
        )
    }

    @Test
    fun `start anchor included from match onward`() {
        val blocks = parseWith(
            "s",
            null,
            "<p>before</p><p id=\"s\">at</p><p>after</p>",
        )
        assertEquals(
            listOf(ChapterBlock.Paragraph("at"), ChapterBlock.Paragraph("after")),
            blocks,
        )
    }

    @Test
    fun `end anchor excluded so range is half open`() {
        val blocks = parseWith(
            null,
            "e",
            "<p>A</p><p id=\"e\">stop</p><p>after</p>",
        )
        assertEquals(listOf(ChapterBlock.Paragraph("A")), blocks)
    }

    @Test
    fun `start plus end yield half open window`() {
        val blocks = parseWith(
            "s",
            "e",
            "<p>before</p><p id=\"s\">first</p><p>middle</p><p id=\"e\">stop</p><p>after</p>",
        )
        assertEquals(
            listOf(
                ChapterBlock.Paragraph("first"),
                ChapterBlock.Paragraph("middle"),
            ),
            blocks,
        )
    }

    @Test
    fun `missing start anchor yields empty output`() {
        val blocks = parseWith("nope", null, "<p>A</p><p>B</p>")
        assertTrue(blocks.isEmpty())
    }

    @Test
    fun `missing end anchor forwards through document end`() {
        val blocks = parseWith(
            "s",
            "nope",
            "<p>A</p><p id=\"s\">B</p><p>C</p>",
        )
        assertEquals(
            listOf(ChapterBlock.Paragraph("B"), ChapterBlock.Paragraph("C")),
            blocks,
        )
    }

    @Test
    fun `end anchor deep in nested element stops cleanly`() {
        val blocks = parseWith(
            null,
            "e",
            "<p>A</p><div><p>nested before</p><p id=\"e\">stop</p></div><p>after</p>",
        )
        assertEquals(
            listOf(
                ChapterBlock.Paragraph("A"),
                ChapterBlock.Paragraph("nested before"),
            ),
            blocks,
        )
    }

    @Test
    fun `start anchor heading is itself emitted`() {
        val blocks = parseWith(
            "s",
            null,
            "<p>before</p><h2 id=\"s\">section</h2><p>body</p>",
        )
        assertEquals(
            listOf(ChapterBlock.Heading(2, "section"), ChapterBlock.Paragraph("body")),
            blocks,
        )
    }

    @Test
    fun `start anchor element with children is included entirely`() {
        val blocks = parseWith(
            "s",
            null,
            "<p>before</p><div id=\"s\"><p>one</p><p>two</p></div><p>after</p>",
        )
        assertEquals(
            listOf(
                ChapterBlock.Paragraph("one"),
                ChapterBlock.Paragraph("two"),
                ChapterBlock.Paragraph("after"),
            ),
            blocks,
        )
    }
}
