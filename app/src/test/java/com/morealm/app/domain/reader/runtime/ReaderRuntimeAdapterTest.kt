package com.morealm.app.domain.reader.runtime

import com.morealm.epub.render.ScrollChapterLayout
import com.morealm.epub.render.ScrollLine
import com.morealm.epub.render.ScrollPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderRuntimeAdapterTest {
    @Test
    fun `legacy layout becomes immutable runtime artifact`() {
        val layout = ScrollChapterLayout(
            chapterIndex = 4,
            title = "Chapter 4",
            pages = listOf(
                ScrollPage(
                    pageIndex = 0,
                    lines = listOf(
                        ScrollLine(
                            columns = emptyList(),
                            lineTop = 0f,
                            lineBottom = 10f,
                            paragraphNum = 0,
                            isTitle = false,
                            text = "text",
                            firstChapterPos = 30,
                            lastChapterPos = 34,
                        )
                    ),
                    height = 100f,
                    chapterIndex = 4,
                )
            ),
            totalHeight = 100f,
            viewWidth = 100,
            styleSignature = "style",
            totalCharCount = 100,
        )

        val artifact = ReaderRuntimeAdapter.artifactFrom("book", layout, contentVersion = 7)

        assertEquals("4", artifact.key.unitId.value)
        assertEquals(7, artifact.key.contentVersion)
        assertEquals(listOf(30), artifact.anchorIndex.characterOffsets)
        assertTrue(artifact.pages !== layout.pages)
    }
}
