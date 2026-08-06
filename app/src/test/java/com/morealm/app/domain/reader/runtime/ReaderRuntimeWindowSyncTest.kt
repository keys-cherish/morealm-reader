package com.morealm.app.domain.reader.runtime

import com.morealm.epub.render.ScrollChapterLayout
import com.morealm.epub.render.ScrollPage
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderRuntimeWindowSyncTest {
    private fun layout(index: Int) = ScrollChapterLayout(
        chapterIndex = index,
        title = "Chapter $index",
        pages = listOf(ScrollPage(0, emptyList(), 100f, index)),
        totalHeight = 100f,
        viewWidth = 100,
        styleSignature = "style",
        totalCharCount = 100,
    )

    @Test
    fun `publishes legacy current and adjacent layouts as ready window`() {
        val store = ReaderWindowStore(ReadingUnitId.fromChapterIndex(1))
        ReaderRuntimeWindowSync("book", 2, store).publish(
            current = layout(1),
            previous = layout(0),
            next = layout(2),
        )

        assertTrue(store.snapshot.currentEntry is WindowEntry.Ready)
        assertTrue(store.snapshot.previousEntry is WindowEntry.Ready)
        assertTrue(store.snapshot.nextEntry is WindowEntry.Ready)
    }
}
