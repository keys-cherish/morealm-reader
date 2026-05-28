package com.morealm.app.domain.repository

import com.morealm.app.domain.db.BookGroupDao
import com.morealm.app.domain.entity.BookGroup
import kotlinx.coroutines.flow.Flow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

/**
 * `BookGroupRepository.ensureGroupTree` 单测 —— 覆盖 5 个场景：
 *
 *  a. 单段 ["A"] → 1 个 group id="webdav:A" parentId=null
 *  b. 多段 ["A","B","C"] → 3 个 group 链 parentId 正确
 *  c. 重复调用幂等不产生重复 row（in-memory dao 验证 row count）
 *  d. 不同 sourcePrefix 不串扰
 *  e. 空 segments 抛 IllegalArgumentException
 *
 * 用 in-memory [FakeBookGroupDao] 跑，不起 Room；测试聚焦于 stable-id 生成 +
 * REPLACE 幂等 + parentId 链。
 */
class BookGroupRepositoryEnsureTreeTest {

    // ── 场景 a：单段 ────────────────────────────────────────

    @Test
    fun `single segment creates one root-level group with null parent`() = runBlocking {
        val dao = FakeBookGroupDao()
        val repo = BookGroupRepository(dao)
        val leafId = repo.ensureGroupTree(listOf("A"))

        assertEquals("webdav:A", leafId)
        assertEquals(1, dao.snapshot().size)
        val a = dao.snapshot().getValue("webdav:A")
        assertEquals("A", a.name)
        assertNull(a.parentId)
    }

    // ── 场景 b：多段 ────────────────────────────────────────

    @Test
    fun `multi segment chain produces correct parentId chain`() = runBlocking {
        val dao = FakeBookGroupDao()
        val repo = BookGroupRepository(dao)
        val leafId = repo.ensureGroupTree(listOf("A", "B", "C"))

        assertEquals("webdav:A/B/C", leafId)
        assertEquals(3, dao.snapshot().size)

        val a = dao.snapshot().getValue("webdav:A")
        assertNull(a.parentId)
        assertEquals("A", a.name)

        val ab = dao.snapshot().getValue("webdav:A/B")
        assertEquals("webdav:A", ab.parentId)
        assertEquals("B", ab.name)

        val abc = dao.snapshot().getValue("webdav:A/B/C")
        assertEquals("webdav:A/B", abc.parentId)
        assertEquals("C", abc.name)
    }

    // ── 场景 c：幂等 ────────────────────────────────────────

    @Test
    fun `repeated calls do not produce duplicate rows`() = runBlocking {
        val dao = FakeBookGroupDao()
        val repo = BookGroupRepository(dao)

        repo.ensureGroupTree(listOf("A", "B", "C"))
        repo.ensureGroupTree(listOf("A", "B", "C"))
        repo.ensureGroupTree(listOf("A", "B"))      // 子集再走一遍
        repo.ensureGroupTree(listOf("A"))           // 更短子集再走一遍

        // 仍只 3 row（A / A/B / A/B/C），不重复
        assertEquals(3, dao.snapshot().size)
        assertNotNull(dao.snapshot()["webdav:A"])
        assertNotNull(dao.snapshot()["webdav:A/B"])
        assertNotNull(dao.snapshot()["webdav:A/B/C"])
    }

    // ── 场景 d：不同 sourcePrefix 不串扰 ─────────────────────

    @Test
    fun `different sourcePrefix produces isolated namespaces`() = runBlocking {
        val dao = FakeBookGroupDao()
        val repo = BookGroupRepository(dao)

        val webdavId = repo.ensureGroupTree(listOf("A"), sourcePrefix = "webdav")
        val ftpId = repo.ensureGroupTree(listOf("A"), sourcePrefix = "ftp")

        assertEquals("webdav:A", webdavId)
        assertEquals("ftp:A", ftpId)
        // 各自一行，不互相覆盖
        assertEquals(2, dao.snapshot().size)
        assertEquals("A", dao.snapshot().getValue("webdav:A").name)
        assertEquals("A", dao.snapshot().getValue("ftp:A").name)
    }

    // ── 场景 e：空 segments ────────────────────────────────

    @Test
    fun `empty segments throws IllegalArgumentException`() = runBlocking {
        val dao = FakeBookGroupDao()
        val repo = BookGroupRepository(dao)
        try {
            repo.ensureGroupTree(emptyList())
            fail("expected IllegalArgumentException for empty segments")
        } catch (_: IllegalArgumentException) {
            // expected
        }
        // 不应产生任何 row
        assertEquals(0, dao.snapshot().size)
    }
}

// ── In-memory fake DAO ─────────────────────────────────────────

/**
 * 内存版 [BookGroupDao]，仅实现本测试用到的方法（其他方法直接抛 fail）。
 * 用 LinkedHashMap 保留 insert 顺序，方便 snapshot 断言。
 */
private class FakeBookGroupDao : BookGroupDao {
    private val rows = LinkedHashMap<String, BookGroup>()

    fun snapshot(): Map<String, BookGroup> = rows.toMap()

    override suspend fun insert(group: BookGroup) {
        // REPLACE 语义：同 id 直接覆盖
        rows[group.id] = group
    }

    override suspend fun getById(id: String): BookGroup? = rows[id]

    override fun getGroups(parentId: String?): Flow<List<BookGroup>> = unused("getGroups")
    override fun getAllGroups(): Flow<List<BookGroup>> = unused("getAllGroups")
    override suspend fun delete(group: BookGroup) {
        unused<Unit>("delete")
    }
    override suspend fun deleteById(id: String) {
        unused<Unit>("deleteById")
    }
    override suspend fun getAllGroupsSync(): List<BookGroup> = unused("getAllGroupsSync")

    private fun <T> unused(name: String): T {
        throw UnsupportedOperationException("$name not implemented in FakeBookGroupDao")
    }
}

private fun <T> runBlocking(block: suspend () -> T): T =
    kotlinx.coroutines.runBlocking { block() }
