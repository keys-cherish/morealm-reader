package com.morealm.app.domain.reader.runtime

import com.morealm.epub.render.ScrollPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderSessionTest {
    private fun artifact(unitId: ReadingUnitId) = LayoutArtifact(
        key = LayoutKey("book", unitId, contentVersion = 1, styleSignature = 1),
        title = unitId.value,
        pages = listOf(ScrollPage(0, emptyList(), 100f, 1)),
        anchorIndex = AnchorIndex(listOf(0, 100)),
    )

    private fun session(): ReaderSession {
        val store = ReaderWindowStore(ReadingUnitId("1"))
        store.setCurrent(WindowEntry.Ready(artifact(ReadingUnitId("1"))))
        store.setNeighbors(previous = ReadingUnitId("0"), next = ReadingUnitId("2"))
        store.complete(
            ReadingUnitId("0"),
            (store.ensure(ReadingUnitId("0")) as EnsureResult.Started).requestId,
            artifact(ReadingUnitId("0")),
        )
        store.complete(
            ReadingUnitId("2"),
            (store.ensure(ReadingUnitId("2")) as EnsureResult.Started).requestId,
            artifact(ReadingUnitId("2")),
        )
        return ReaderSession(
            windowStore = store,
            firstAnchorFor = { ContentAnchor(it, 0) },
            lastAnchorFor = { ContentAnchor(it, 100) },
        )
    }

    @Test
    fun `button previous targets chapter start`() {
        val preparation = session().prepare(
            ReaderIntent.Move(NavigationSource.BUTTON, NavigationDirection.PREVIOUS)
        ) as NavigationPreparation.Ready

        assertEquals(0, preparation.plan.transaction.target.characterOffset)
    }

    @Test
    fun `gesture previous targets chapter end`() {
        val preparation = session().prepare(
            ReaderIntent.Move(NavigationSource.GESTURE, NavigationDirection.PREVIOUS)
        ) as NavigationPreparation.Ready

        assertEquals(100, preparation.plan.transaction.target.characterOffset)
    }

    @Test
    fun `only latest transaction can commit`() {
        val reader = session()
        val first = reader.prepare(
            ReaderIntent.Move(NavigationSource.BUTTON, NavigationDirection.NEXT)
        ) as NavigationPreparation.Ready
        val second = reader.prepare(
            ReaderIntent.Move(NavigationSource.BUTTON, NavigationDirection.PREVIOUS)
        ) as NavigationPreparation.Ready

        assertFalse(reader.commit(first.plan.transaction.id))
        assertTrue(reader.commit(second.plan.transaction.id))
        assertEquals("0", reader.window.current.value)
    }
}
