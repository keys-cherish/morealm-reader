package com.morealm.app.domain.sync

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * `bfsCollectFilesWith` 单测 —— 覆盖 4 个场景：
 *
 *  a. emit 节奏：累积 bufferSize 触发；最后剩余补一次
 *  b. Semaphore 限并发 ≤ BFS_CONCURRENCY=5（用 listFn 内 atomic counter + delay 验证）
 *  c. 嵌套 3 层目录树平铺 emit（任意层文件都会进 buffer，跨层不丢）
 *  d. flow cancel 时 BFS 队列释放（消费者只取一批就 break，不无限 hang）
 *
 * 直接对 top-level `bfsCollectFilesWith` 测试，跳过 Hilt / 真 WebDavClient。
 */
class RemoteBookManagerBfsTest {

    /** 与 RemoteBookManager.bookExtensions 严格一致。 */
    private val bookExtensions = setOf(
        "epub", "txt", "umd", "mobi", "azw3", "pdf", "cbz",
        "zip", "rar", "7z",
    )

    // ── 场景 a：emit 节奏 ─────────────────────────────────────

    @Test
    fun `emit triggers when buffer fills to bufferSize`() = runBlocking {
        // 单层目录，10 本书；bufferSize=4 → 应 emit 3 批：4 + 4 + 2
        val tree = mapOf(
            "/root" to (0 until 10).map { file("book_$it.epub", modTime = it.toLong()) },
        )
        val flow = bfsCollectFilesWith(
            rootPath = "/root",
            bufferSize = 4,
            bookExtensions = bookExtensions,
            listFn = { tree[it] ?: emptyList() },
        )
        val chunks = flow.toList()
        assertEquals(3, chunks.size)
        assertEquals(4, chunks[0].size)
        assertEquals(4, chunks[1].size)
        assertEquals(2, chunks[2].size)
        assertEquals(10, chunks.flatten().size)
    }

    @Test
    fun `emit flushes residual buffer at end even below bufferSize`() = runBlocking {
        val tree = mapOf("/r" to (0 until 3).map { file("b_$it.epub", modTime = it.toLong()) })
        val flow = bfsCollectFilesWith("/r", bufferSize = 200, bookExtensions = bookExtensions) {
            tree[it] ?: emptyList()
        }
        val chunks = flow.toList()
        assertEquals(1, chunks.size)
        assertEquals(3, chunks[0].size)
    }

    @Test
    fun `empty tree emits nothing`() = runBlocking {
        val flow = bfsCollectFilesWith("/empty", bufferSize = 200, bookExtensions = bookExtensions) {
            emptyList()
        }
        val chunks = flow.toList()
        assertTrue(chunks.isEmpty())
    }

    // ── 场景 b：Semaphore 限并发 ────────────────────────────

    @Test
    fun `concurrent listFn invocations bounded by BFS_CONCURRENCY`() = runBlocking {
        // 第一层根目录返回 20 个子目录（远超 BFS_CONCURRENCY=5），强制下一层并发触发
        val rootChildren = (0 until 20).map { dir("d$it", modTime = it.toLong()) }
        val inflight = AtomicInteger(0)
        val peak = AtomicInteger(0)
        val tree = HashMap<String, List<WebDavClient.DavFile>>()
        tree["/root"] = rootChildren
        for (i in 0 until 20) {
            // 每个子目录里只放 1 本书
            tree["/root/d$i"] = listOf(file("book.epub", modTime = i.toLong()))
        }

        val flow = bfsCollectFilesWith(
            rootPath = "/root",
            bufferSize = 200,
            bookExtensions = bookExtensions,
            listFn = { path ->
                val cur = inflight.incrementAndGet()
                // 更新峰值
                var prev = peak.get()
                while (cur > prev && !peak.compareAndSet(prev, cur)) {
                    prev = peak.get()
                }
                try {
                    delay(20)  // 让并发窗口可被观测
                    tree[path] ?: emptyList()
                } finally {
                    inflight.decrementAndGet()
                }
            },
        )
        val chunks = flow.toList()
        // 验证收集到 20 本书
        assertEquals(20, chunks.flatten().size)
        // 验证并发 peak ≤ BFS_CONCURRENCY
        assertTrue(
            "peak concurrency ${peak.get()} should be <= $BFS_CONCURRENCY",
            peak.get() <= BFS_CONCURRENCY,
        )
    }

    // ── 场景 c：嵌套目录树平铺 ───────────────────────────

    @Test
    fun `nested 3-level tree flattens all leaf files into chunks`() = runBlocking {
        // 树形（每层 2 个子目录，第 3 层每个目录 2 本书 = 8 本叶子书）+ 第 1/2 层各 1 本散书
        val tree = HashMap<String, List<WebDavClient.DavFile>>()
        tree["/r"] = listOf(
            dir("a", modTime = 1L),
            dir("b", modTime = 2L),
            file("root_book.epub", modTime = 3L),
        )
        tree["/r/a"] = listOf(
            dir("a1", modTime = 11L),
            dir("a2", modTime = 12L),
            file("a_mid_book.epub", modTime = 13L),
        )
        tree["/r/b"] = listOf(
            dir("b1", modTime = 21L),
            dir("b2", modTime = 22L),
        )
        tree["/r/a/a1"] = listOf(
            file("aa1_book1.epub", modTime = 101L),
            file("aa1_book2.txt", modTime = 102L),
        )
        tree["/r/a/a2"] = listOf(file("aa2_book.epub", modTime = 110L))
        tree["/r/b/b1"] = listOf(file("bb1_book.mobi", modTime = 121L))
        tree["/r/b/b2"] = listOf(
            file("bb2_book1.epub", modTime = 131L),
            file("bb2_book2.pdf", modTime = 132L),
        )
        // 期望共 8 本书：root_book + a_mid_book + aa1_book1 + aa1_book2 + aa2_book +
        //                bb1_book + bb2_book1 + bb2_book2

        val flow = bfsCollectFilesWith("/r", bufferSize = 200, bookExtensions = bookExtensions) {
            tree[it] ?: emptyList()
        }
        val allFiles = flow.toList().flatten()
        assertEquals(8, allFiles.size)
        val names = allFiles.map { it.name }.toSet()
        assertEquals(
            setOf(
                "root_book.epub",
                "a_mid_book.epub",
                "aa1_book1.epub", "aa1_book2.txt",
                "aa2_book.epub",
                "bb1_book.mobi",
                "bb2_book1.epub", "bb2_book2.pdf",
            ),
            names,
        )
        // 验证 remotePath 反映完整层级
        val deep = allFiles.first { it.name == "aa1_book1.epub" }
        assertEquals("/r/a/a1/aa1_book1.epub", deep.remotePath)
    }

    // ── 场景 d：cancel 时释放 ────────────────────────────

    @Test
    fun `cancelling consumer terminates BFS without hanging`() = runBlocking {
        // 构造极大树：每层 20 个子目录 × 多层，若不被 cancel 终止会跑很久
        val tree = HashMap<String, List<WebDavClient.DavFile>>()
        // 根目录返回 20 个子目录，每个子目录里放足够的书填一批 (bufferSize=5)
        tree["/r"] = (0 until 20).map { dir("d$it", modTime = it.toLong()) }
        for (i in 0 until 20) {
            tree["/r/d$i"] = (0 until 10).map { file("b${i}_$it.epub", modTime = it.toLong()) }
            // 二级子目录再放 20 个，让 BFS 队列持续生长
            tree["/r/d$i"] = (tree["/r/d$i"] ?: emptyList()) +
                (0 until 20).map { dir("dd$it", modTime = it.toLong()) }
            for (j in 0 until 20) {
                tree["/r/d$i/dd$j"] = (0 until 10).map { file("z.epub", modTime = it.toLong()) }
            }
        }
        val flow = bfsCollectFilesWith("/r", bufferSize = 5, bookExtensions = bookExtensions) {
            delay(1)  // 模拟轻微 IO
            tree[it] ?: emptyList()
        }
        // 只取一批就终止 collect —— Flow.first 会取消上游
        val firstChunk = flow.first()
        // 没卡死 + 取到至少 1 个 chunk = pass
        assertTrue(firstChunk.isNotEmpty())
        assertTrue(firstChunk.size <= 5)
    }

    // ── 测试辅助 ───────────────────────────────────────────

    private fun dir(name: String, modTime: Long) = WebDavClient.DavFile(
        name = name,
        href = "/$name",
        isDirectory = true,
        size = 0L,
        lastModified = "",
        lastModifiedEpoch = modTime,
    )

    private fun file(name: String, modTime: Long) = WebDavClient.DavFile(
        name = name,
        href = "/$name",
        isDirectory = false,
        size = 1024L,
        lastModified = "",
        lastModifiedEpoch = modTime,
    )
}
