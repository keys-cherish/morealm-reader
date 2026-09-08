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

    @Test
    fun `moving the neighbor preserves the ready entry by unit id`() {
        val first = ReadingUnitId("1")
        val second = ReadingUnitId("2")
        val third = ReadingUnitId("3")
        val store = ReaderWindowStore(first)
        store.setNeighbors(previous = null, next = second)
        val requestId = (store.ensure(second) as EnsureResult.Started).requestId
        store.complete(second, requestId, artifact(second))

        store.setNeighbors(previous = second, next = third)

        assertTrue(store.snapshot.previousEntry is WindowEntry.Ready)
        assertEquals(second, (store.snapshot.previousEntry as WindowEntry.Ready).artifact.key.unitId)
    }

    @Test
    fun `ensure restarts a new request after failure`() {
        val store = ReaderWindowStore(ReadingUnitId("1"))
        store.setNeighbors(previous = null, next = ReadingUnitId("2"))
        val failedId = (store.ensure(ReadingUnitId("2")) as EnsureResult.Started).requestId
        assertTrue(store.fail(ReadingUnitId("2"), failedId, "boom"))

        val retry = store.ensure(ReadingUnitId("2")) as EnsureResult.Started

        assertTrue(retry.requestId != failedId)
        assertTrue(store.snapshot.nextEntry is WindowEntry.Loading)
    }

    @Test
    fun `moveTo keeps a retained ready entry and clears neighbors`() {
        val first = ReadingUnitId("1")
        val second = ReadingUnitId("2")
        val store = ReaderWindowStore(first)
        store.setCurrent(WindowEntry.Ready(artifact(first)))
        store.setNeighbors(previous = null, next = second)
        val requestId = (store.ensure(second) as EnsureResult.Started).requestId
        store.complete(second, requestId, artifact(second))

        store.moveTo(second)

        assertEquals(second, store.currentId)
        assertTrue(store.snapshot.currentEntry is WindowEntry.Ready)
        assertEquals(null, store.snapshot.previous)
        assertEquals(null, store.snapshot.next)
        assertTrue(store.snapshot.previousEntry is WindowEntry.Empty)
    }

    @Test
    fun `moveTo to an unknown unit starts from empty`() {
        val store = ReaderWindowStore(ReadingUnitId("1"))
        store.setCurrent(WindowEntry.Ready(artifact(ReadingUnitId("1"))))

        store.moveTo(ReadingUnitId("9"))

        assertEquals(ReadingUnitId("9"), store.currentId)
        assertTrue(store.snapshot.currentEntry is WindowEntry.Empty)
        assertTrue(store.ensure(ReadingUnitId("9")) is EnsureResult.Started)
    }

    @Test
    fun `invalidateAll empties every entry but keeps window ids`() {
        val first = ReadingUnitId("1")
        val second = ReadingUnitId("2")
        val store = ReaderWindowStore(first)
        store.setCurrent(WindowEntry.Ready(artifact(first)))
        store.setNeighbors(previous = null, next = second)
        val requestId = (store.ensure(second) as EnsureResult.Started).requestId
        store.complete(second, requestId, artifact(second))

        store.invalidateAll()

        assertEquals(first, store.currentId)
        assertEquals(second, store.snapshot.next)
        assertTrue(store.snapshot.currentEntry is WindowEntry.Empty)
        assertTrue(store.snapshot.nextEntry is WindowEntry.Empty)
        assertTrue(store.ensure(second) is EnsureResult.Started)
    }

    @Test
    fun `stale completion after invalidateAll is rejected`() {
        val store = ReaderWindowStore(ReadingUnitId("1"))
        store.setNeighbors(previous = null, next = ReadingUnitId("2"))
        val requestId = (store.ensure(ReadingUnitId("2")) as EnsureResult.Started).requestId

        store.invalidateAll()

        assertFalse(store.complete(ReadingUnitId("2"), requestId, artifact(ReadingUnitId("2"))))
        assertTrue(store.snapshot.nextEntry is WindowEntry.Empty)
    }
    @Test
    fun `repeated external jumps preserve reverse and forward page navigation`() {
        val units = (0..5).map { ReadingUnitId.fromChapterIndex(it) }
        val store = ReaderWindowStore(units[0])
        store.setCurrent(WindowEntry.Ready(artifact(units[0])))
        val session = ReaderSession(store, { ContentAnchor(it, 0) }, { ContentAnchor(it, 100) })

        for (index in 1..4) {
            store.setNeighbors(units.getOrNull(index - 2), units[index])
            val request = store.ensure(units[index]) as EnsureResult.Started
            store.complete(units[index], request.requestId, artifact(units[index]))
            store.moveTo(units[index], previous = units[index - 1], next = units[index + 1])

            val backward = session.prepare(
                ReaderIntent.Move(NavigationSource.GESTURE, NavigationDirection.PREVIOUS)
            ) as NavigationPreparation.Ready
            assertEquals(100, backward.plan.transaction.target.characterOffset)
            assertTrue(session.commit(backward.plan.transaction.id))
            val forward = session.prepare(
                ReaderIntent.Move(NavigationSource.GESTURE, NavigationDirection.NEXT)
            ) as NavigationPreparation.Ready
            assertEquals(0, forward.plan.transaction.target.characterOffset)
            assertTrue(session.commit(forward.plan.transaction.id))
        }
    }

    @Test
    fun `external jump retains in flight neighbor but rejects evicted completion`() {
        val units = (0..3).map { ReadingUnitId.fromChapterIndex(it) }
        val store = ReaderWindowStore(units[1])
        store.setNeighbors(units[0], units[2])
        val previous = store.ensure(units[0]) as EnsureResult.Started
        val next = store.ensure(units[2]) as EnsureResult.Started
        store.moveTo(units[3], previous = units[2])
        assertTrue(store.complete(units[2], next.requestId, artifact(units[2])))
        assertFalse(store.complete(units[0], previous.requestId, artifact(units[0])))
        assertTrue(store.snapshot.previousEntry is WindowEntry.Ready)
    }

}
