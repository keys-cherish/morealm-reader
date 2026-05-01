package com.morealm.app.domain.tts

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * EdgeTtsCache 单测。覆盖：
 * - 不同 (voice|rate|pitch|text) 组合产生不同 key
 * - put 后 get 拿到完全相同字节
 * - 总量超 maxBytes 时 LRU 淘汰到 targetBytes 之下
 * - lastModified 在 get 命中时被刷新（保证"重听段"不会被误删）
 */
@RunWith(RobolectricTestRunner::class)
class EdgeTtsCacheTest {

    private lateinit var cache: EdgeTtsCache
    private lateinit var cacheDir: File

    @Before
    fun setup() {
        // Robolectric 提供的 cacheDir 是临时目录，每个 test 独立
        val ctx = RuntimeEnvironment.getApplication()
        // max=1024, target=700：写 4×300=1200 后，需删 2 个回到 600 ≤ 700
        cache = EdgeTtsCache(ctx, maxBytes = 1024L, targetBytes = 700L)
        cacheDir = File(ctx.cacheDir, "edge_tts")
        // 清干净避免上一轮残留干扰
        cacheDir.listFiles()?.forEach { it.delete() }
    }

    @After
    fun teardown() {
        cacheDir.listFiles()?.forEach { it.delete() }
    }

    @Test
    fun `keyFor distinguishes by every input`() {
        val k1 = cache.keyFor("voice-A", "+0%", "+0Hz", "hello")
        val k2 = cache.keyFor("voice-B", "+0%", "+0Hz", "hello")
        val k3 = cache.keyFor("voice-A", "+50%", "+0Hz", "hello")
        val k4 = cache.keyFor("voice-A", "+0%", "+5Hz", "hello")
        val k5 = cache.keyFor("voice-A", "+0%", "+0Hz", "world")
        assertNotEquals(k1, k2)
        assertNotEquals(k1, k3)
        assertNotEquals(k1, k4)
        assertNotEquals(k1, k5)
        // 同输入 → 同 key（确定性）
        assertEquals(k1, cache.keyFor("voice-A", "+0%", "+0Hz", "hello"))
    }

    @Test
    fun `put then get returns same bytes`() {
        val k = cache.keyFor("v", "+0%", "+0Hz", "abc")
        val payload = byteArrayOf(1, 2, 3, 4, 5, 0x7F, -1)
        cache.put(k, payload)
        val file = cache.get(k)
        assertNotNull(file)
        assertArrayEquals(payload, file!!.readBytes())
    }

    @Test
    fun `get returns null when missing`() {
        val k = cache.keyFor("v", "+0%", "+0Hz", "ghost")
        assertNull(cache.get(k))
    }

    @Test
    fun `enforceLimit evicts oldest until under target`() {
        // 关键：put() 内部会自动调 enforceLimit()，所以前 3 个 put 不会触发（总量 900 < 1024）。
        // 第 4 个 put 触发：total=1200 > 1024 → 必须降到 ≤ 700。
        //
        // 但 put 写入瞬间各文件 mtime 是 OS 实时时间（差几毫秒），排序不稳定。
        // 解决：先全部 put 完，再统一手动 setLastModified 拉开间距，最后显式 enforceLimit()。
        // 但 put #4 已经触发过一次淘汰，可能会按 OS mtime 删了非预期的文件 —— 改用更大的
        // maxBytes 让 put 中不触发淘汰，最后再手动调 enforceLimit() 验证排序正确性。

        val bigCache = EdgeTtsCache(
            RuntimeEnvironment.getApplication(),
            maxBytes = 10_000L,        // 大容量：put 期间绝不触发淘汰
            targetBytes = 700L,
        )
        val payload = ByteArray(300) { it.toByte() }
        val keys = (1..5).map { bigCache.keyFor("v", "+0%", "+0Hz", "text-$it") }
        for ((i, k) in keys.withIndex()) {
            bigCache.put(k, payload)
        }
        // 写完后人为设置 mtime 让排序确定：i 越小 mtime 越早
        for ((i, k) in keys.withIndex()) {
            File(cacheDir, "$k.mp3").setLastModified(1_000_000_000L + i * 1000L)
        }

        // 现在手动收紧上限触发淘汰
        val tightCache = EdgeTtsCache(
            RuntimeEnvironment.getApplication(),
            maxBytes = 1024L,
            targetBytes = 700L,
        )
        tightCache.enforceLimit()

        assertTrue(
            "total=${tightCache.totalBytes()} should be <= maxBytes=1024",
            tightCache.totalBytes() <= 1024L,
        )
        // 1500B → 删到 ≤ 700B → 至少删 3 个 → 至多剩 2 个
        assertTrue(
            "total=${tightCache.totalBytes()} should be <= 700 after eviction",
            tightCache.totalBytes() <= 700L,
        )

        // 最旧的两个 (text-1, text-2) 必被删
        assertNull("text-1 (oldest) should be evicted", tightCache.get(keys[0]))
        assertNull("text-2 should be evicted", tightCache.get(keys[1]))
        // 最新的 text-5 必存活
        assertNotNull("text-5 (newest) should survive", tightCache.get(keys[4]))
    }

    @Test
    fun `get touches lastModified so frequent items survive`() {
        // 思路：3 个文件 mtime 拉开 → touch 最旧 → put 第 4 个文件触发淘汰
        // → 验证被 touch 的"原最旧"幸存而非被删。
        //
        // 用大 maxBytes 写入避免 put 期间提前淘汰，最后用紧 maxBytes 单独触发。

        val bigCache = EdgeTtsCache(
            RuntimeEnvironment.getApplication(),
            maxBytes = 10_000L,
            targetBytes = 700L,
        )
        val payload = ByteArray(300) { it.toByte() }
        val keys = (1..3).map { bigCache.keyFor("v", "+0%", "+0Hz", "frequent-$it") }
        for ((i, k) in keys.withIndex()) {
            bigCache.put(k, payload)
            File(cacheDir, "$k.mp3").setLastModified(1_000_000L + i * 10_000L)
        }
        // mtime: file1=1_000_000, file2=1_010_000, file3=1_020_000

        // 反复访问 frequent-1（最老）让它的 mtime 跳到现在，远超 file2/file3
        bigCache.get(keys[0])
        // 现在 mtime 排序应是: frequent-2 (1_010_000) < frequent-3 (1_020_000) < frequent-1 (现在)

        // 写第 4 个文件并显式拉到"更新"，让它和 file1 都成为新条目
        val k4 = bigCache.keyFor("v", "+0%", "+0Hz", "frequent-4")
        bigCache.put(k4, payload)
        // file4 刚 put → mtime 是当前时间
        // 此时四个文件按 mtime 升序：file2 < file3 < (file1, file4 in some order both ~now)
        // 总量 1200B → 用紧上限触发淘汰到 ≤ 700B → 必删 2 个

        val tightCache = EdgeTtsCache(
            RuntimeEnvironment.getApplication(),
            maxBytes = 1024L,
            targetBytes = 700L,
        )
        tightCache.enforceLimit()

        // 关键断言：被 touch 的 frequent-1 不应被淘汰（它的 mtime 已经被刷到 now）
        assertNotNull(
            "touched item should survive eviction",
            tightCache.get(keys[0]),
        )
        // 同时最旧的 frequent-2 应被删
        assertNull(
            "untouched oldest (frequent-2) should be evicted",
            tightCache.get(keys[1]),
        )
    }

    @Test
    fun `clear removes all files`() {
        cache.put(cache.keyFor("v", "+0%", "+0Hz", "x"), ByteArray(10))
        cache.put(cache.keyFor("v", "+0%", "+0Hz", "y"), ByteArray(10))
        assertTrue(cache.totalBytes() > 0)
        cache.clear()
        assertEquals(0L, cache.totalBytes())
    }

    @Test
    fun `put with empty bytes is no-op`() {
        val k = cache.keyFor("v", "+0%", "+0Hz", "empty")
        cache.put(k, ByteArray(0))
        assertNull(cache.get(k))
    }
}
