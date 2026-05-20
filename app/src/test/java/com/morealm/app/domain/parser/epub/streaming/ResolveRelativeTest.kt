package com.morealm.app.domain.parser.epub.streaming

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers [StreamingChapterReader.resolveRelative] — the chapter-relative
 * href resolver that lets the imgLookup callback receive an OPF-relative
 * src regardless of how the source XHTML authored the `<img src>`.
 *
 * Behavior must match the old EpubParser.parseBody path which used
 * `URI(chapterHref).resolve(src)` so existing books continue to find their
 * cached images on disk.
 */
class ResolveRelativeTest {

    private fun resolve(base: String, src: String): String =
        StreamingChapterReader.resolveRelative(base, src)

    @Test
    fun `empty src returns empty unchanged`() {
        assertEquals("", resolve("Text/ch1.xhtml", ""))
    }

    @Test
    fun `flat base with same-dir src yields src`() {
        assertEquals("cover.jpg", resolve("cover.xhtml", "cover.jpg"))
    }

    @Test
    fun `nested base with same-dir src concatenates dir`() {
        assertEquals("Text/cover.jpg", resolve("Text/ch1.xhtml", "cover.jpg"))
    }

    @Test
    fun `nested base with parent dir src climbs out`() {
        assertEquals("Images/cover.jpg", resolve("Text/ch1.xhtml", "../Images/cover.jpg"))
    }

    @Test
    fun `double parent dir src climbs further`() {
        assertEquals(
            "OEBPS/Images/cover.jpg",
            resolve("OEBPS/Text/sub/ch1.xhtml", "../../Images/cover.jpg"),
        )
    }

    @Test
    fun `url encoded src is decoded`() {
        assertEquals("Images/cover image.jpg", resolve("Text/ch1.xhtml", "../Images/cover%20image.jpg"))
    }

    @Test
    fun `external http URL is unchanged`() {
        assertEquals(
            "http://example.com/cover.jpg",
            resolve("Text/ch1.xhtml", "http://example.com/cover.jpg"),
        )
    }
}
