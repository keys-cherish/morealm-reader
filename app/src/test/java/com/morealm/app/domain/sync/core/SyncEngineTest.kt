package com.morealm.app.domain.sync.core

import com.morealm.app.domain.entity.ReadProgress
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncEngineTest {

    /** 内存 transport：模拟远端存储，两台“设备”共享。 */
    private class FakeTransport : SyncTransport {
        val store = HashMap<String, SyncEnvelope>()
        var uploads = 0
        override suspend fun download(collection: String): SyncEnvelope? = store[collection]
        override suspend fun upload(collection: String, envelope: SyncEnvelope) {
            uploads++
            store[collection] = envelope
        }
    }

    private fun rec(id: String, at: Long, dev: String, payload: String = "") =
        SyncRecord(syncId = id, updatedAt = at, deviceId = dev, payload = payload)

    @Test
    fun `two devices converge to same set`() = runBlocking {
        val transport = FakeTransport()
        val engine = SyncEngine(transport)

        // 设备 A 先同步：本地 2 条 → 全部上云
        engine.sync("c", listOf(rec("p1", 100, "A", "a1"), rec("p2", 100, "A", "a2"))) {}
        // 设备 B 同步：p1 更新、p3 新增 → 拉到 p2，推上 p1/p3
        val appliedOnB = ArrayList<SyncRecord>()
        engine.sync("c", listOf(rec("p1", 200, "B", "b1"), rec("p3", 50, "B", "b3"))) {
            appliedOnB.addAll(it)
        }

        assertEquals(listOf("p2"), appliedOnB.map { it.syncId })
        val cloud = transport.store["c"]!!.records.associateBy { it.syncId }
        assertEquals(setOf("p1", "p2", "p3"), cloud.keys)
        assertEquals("b1", cloud["p1"]!!.payload)
    }

    @Test
    fun `no upload when nothing changed`() = runBlocking {
        val transport = FakeTransport()
        val engine = SyncEngine(transport)
        val local = listOf(rec("p1", 100, "A"))
        engine.sync("c", local) {}
        val before = transport.uploads
        engine.sync("c", local) {}
        assertEquals(before, transport.uploads)
        return@runBlocking
    }

    @Test
    fun `progress codec roundtrip and forward-compat null`() {
        val p = ReadProgress(bookId = "b1", chapterIndex = 3, chapterPosition = 42, updatedAt = 123L)
        val record = ProgressSyncCodec.encode(p, deviceId = "dev-A")
        assertEquals("progress:b1", record.syncId)
        assertEquals(123L, record.updatedAt)
        assertEquals(p, ProgressSyncCodec.decode(record))
        assertNull(ProgressSyncCodec.decode(record.copy(payload = "not json")))
        assertNotNull(ProgressSyncCodec.decode(record.copy(payload = """{"bookId":"b1","futureField":1}""")))
        assertTrue(ProgressSyncCodec.COLLECTION.isNotBlank())
    }
}
