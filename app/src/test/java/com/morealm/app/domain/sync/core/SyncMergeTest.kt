package com.morealm.app.domain.sync.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncMergeTest {

    private fun rec(id: String, at: Long, dev: String = "A", deleted: Boolean = false, payload: String = "") =
        SyncRecord(syncId = id, updatedAt = at, deviceId = dev, deleted = deleted, payload = payload)

    @Test
    fun `newer record wins regardless of side`() {
        val local = listOf(rec("p1", 100, payload = "old"))
        val remote = listOf(rec("p1", 200, dev = "B", payload = "new"))
        val r = mergeRecords(local, remote)
        assertEquals("new", r.merged.single().payload)
        assertEquals(listOf("new"), r.toApplyLocal.map { it.payload })
        assertTrue(r.toUpload.isEmpty())
    }

    @Test
    fun `local newer means upload not apply`() {
        val r = mergeRecords(
            listOf(rec("p1", 300, payload = "mine")),
            listOf(rec("p1", 200, dev = "B", payload = "theirs")),
        )
        assertEquals("mine", r.merged.single().payload)
        assertEquals(1, r.toUpload.size)
        assertTrue(r.toApplyLocal.isEmpty())
    }

    @Test
    fun `disjoint ids union both directions`() {
        val r = mergeRecords(listOf(rec("a", 1)), listOf(rec("b", 2, dev = "B")))
        assertEquals(setOf("a", "b"), r.merged.map { it.syncId }.toSet())
        assertEquals(listOf("b"), r.toApplyLocal.map { it.syncId })
        assertEquals(listOf("a"), r.toUpload.map { it.syncId })
    }

    @Test
    fun `tombstone with newer timestamp propagates deletion`() {
        val r = mergeRecords(
            listOf(rec("h1", 100, payload = "alive")),
            listOf(rec("h1", 150, dev = "B", deleted = true)),
        )
        assertTrue(r.merged.single().deleted)
        assertTrue(r.toApplyLocal.single().deleted)
    }

    @Test
    fun `equal timestamps resolve deterministically by deviceId on both ends`() {
        val a = rec("x", 100, dev = "A", payload = "fromA")
        val b = rec("x", 100, dev = "B", payload = "fromB")
        val fromSide1 = mergeRecords(listOf(a), listOf(b)).merged.single()
        val fromSide2 = mergeRecords(listOf(b), listOf(a)).merged.single()
        assertEquals(fromSide1, fromSide2)
        assertEquals("fromB", fromSide1.payload)
    }

    @Test
    fun `identical records produce no traffic`() {
        val same = rec("p1", 100, payload = "x")
        val r = mergeRecords(listOf(same), listOf(same))
        assertTrue(r.toApplyLocal.isEmpty())
        assertTrue(r.toUpload.isEmpty())
    }
}
