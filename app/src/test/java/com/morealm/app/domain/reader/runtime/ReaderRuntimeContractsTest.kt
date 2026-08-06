package com.morealm.app.domain.reader.runtime

import com.morealm.epub.render.ScrollPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ReaderRuntimeContractsTest {
    @Test
    fun `chapter index adapter creates stable unit ids`() {
        assertEquals("12", ReadingUnitId.fromChapterIndex(12).value)
    }

    @Test
    fun `anchor index selects nearest stable offset`() {
        val index = AnchorIndex(listOf(0, 100, 240))

        assertEquals(240, index.nearestOffset(210))
    }

    @Test
    fun `layout artifact identity is unit scoped`() {
        val unitId = ReadingUnitId("chapter-1")
        val artifact = LayoutArtifact(
            key = LayoutKey("book", unitId, contentVersion = 1, styleSignature = 2),
            title = "Chapter 1",
            pages = listOf(ScrollPage(0, emptyList(), 100f, 1)),
            anchorIndex = AnchorIndex(listOf(0)),
        )

        assertEquals(true, artifact.contains(ContentAnchor(unitId, 50)))
        assertEquals(false, artifact.contains(ContentAnchor(ReadingUnitId("chapter-2"), 0)))
    }

    @Test
    fun `invalid contracts fail at construction`() {
        assertThrows(IllegalArgumentException::class.java) {
            ContentAnchor(ReadingUnitId("chapter"), -1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            WindowEntry.Loading(0)
        }
    }
}
