package com.morealm.app.domain.reader.runtime

import com.morealm.epub.render.ScrollPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderWindowStoreTest {
    private fun artifact(unitId: ReadingUnitId) = LayoutArtifact(
        key = LayoutKey("book", unitId, contentVersion = 1, styleSignature = 1),
        title = unitId.value,
        pages = listOf(ScrollPage(0, emptyList(), 100f, 1)),
        anchorIndex = AnchorIndex(listOf(0)),
    )

    @Test
    fun `repeated ensure reuses request while loading`() {
        val store = ReaderWindowStore(ReadingUnitId("1"))
        store.setNeighbors(previous = ReadingUnitId("0"), next = ReadingUnitId("2"))

        val first = store.ensure(ReadingUnitId("2")) as EnsureResult.Started
        val second = store.ensure(ReadingUnitId("2")) as EnsureResult.Loading

        assertEquals(first.requestId, second.requestId)
    }

    @Test
    fun `stale completion cannot replace newer request`() {
        val store = ReaderWindowStore(ReadingUnitId("1"))
        store.setNeighbors(previous = null, next = ReadingUnitId("2"))
        val requestId = (store.ensure(ReadingUnitId("2")) as EnsureResult.Started).requestId

        assertFalse(store.complete(ReadingUnitId("2"), requestId + 1, artifact(ReadingUnitId("2"))))
        assertTrue(store.complete(ReadingUnitId("2"), requestId, artifact(ReadingUnitId("2"))))
        assertTrue(store.snapshot.nextEntry is WindowEntry.Ready)
    }

    @Test
    fun `committing next atomically shifts current to previous`() {
        val first = ReadingUnitId("1")
        val second = ReadingUnitId("2")
        val store = ReaderWindowStore(first)
        store.setCurrent(WindowEntry.Ready(artifact(first)))
        store.setNeighbors(previous = null, next = second)
        val requestId = (store.ensure(second) as EnsureResult.Started).requestId
        store.complete(second, requestId, artifact(second))

        assertTrue(store.commitAdjacent(second))
        assertEquals(second, store.snapshot.current)
        assertEquals(first, store.snapshot.previous)
        assertTrue(store.snapshot.currentEntry is WindowEntry.Ready)
    }
}
