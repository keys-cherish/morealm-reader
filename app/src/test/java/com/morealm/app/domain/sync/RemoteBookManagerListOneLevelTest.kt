package com.morealm.app.domain.sync

import com.morealm.app.domain.entity.BookFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * `RemoteBookManager.listOneLevel` 单测 —— 覆盖 4 个场景：
 *
 *  a. 混合 folder + file 时 folder 全留，file 仅书形扩展名留
 *  b. 排序：Folder 优先 + 时间倒序
 *  c. 空输入返回空 list（client 返回空目录）
 *  d. listFn 抛异常时返回空 list（不抛给调用方，UI banner 由 ViewModel 决定）
 *
 * 直接对 `listOneLevelWith(path, bookExtensions, listFn)` top-level helper 测试，
 * 跳过 RemoteBookManager Hilt 依赖（需要 BackupRepository / AppDatabase 全套），
 * 也跳过真 WebDavClient 网络层。生产路径 RemoteBookManager.listOneLevel
 * 直接调本 helper（只比测试多一层 `createClient` 包装），覆盖率等价。
 */
class RemoteBookManagerListOneLevelTest {

    /** 与 RemoteBookManager.bookExtensions 严格一致（应该 sync 才安全）。 */
    private val bookExtensions = setOf(
        "epub", "txt", "umd", "mobi", "azw3", "pdf", "cbz",
        "zip", "rar", "7z",
    )

    // ── 场景 a：filter 行为 ─────────────────────────────────────

    @Test
    fun `mixed folder and files keeps folders and book-extension files only`() = runBlocking {
        val daved = listOf(
            dir("folder_a", 100L),
            file("book_a.epub", size = 1024L, modTime = 200L),
            file("note.docx", size = 512L, modTime = 300L),   // 非书形 → 过滤
            file(".DS_Store", size = 8L, modTime = 400L),     // 隐藏 → 过滤
            file("book_b.txt", size = 256L, modTime = 500L),
        )
        val result = listOneLevelWith("/books", bookExtensions) { daved }

        // 期望 4 项中保留 3 项：folder_a, book_a.epub, book_b.txt
        val names = result.map { it.name }
        assertEquals(setOf("folder_a", "book_a.epub", "book_b.txt"), names.toSet())
        assertEquals(3, result.size)
        // 文件夹用 Folder，文件用 File
        assertTrue(result.first { it.name == "folder_a" } is RemoteEntry.Folder)
        val epub = result.first { it.name == "book_a.epub" } as RemoteEntry.File
        assertEquals(BookFormat.EPUB, epub.format)
        val txt = result.first { it.name == "book_b.txt" } as RemoteEntry.File
        assertEquals(BookFormat.TXT, txt.format)
    }

    // ── 场景 b：排序 ────────────────────────────────────────────

    @Test
    fun `result sorted by folder first then last-modified desc`() = runBlocking {
        val daved = listOf(
            file("old_book.epub", size = 1L, modTime = 100L),   // file 旧
            dir("new_folder", 1000L),                           // folder 新
            file("new_book.epub", size = 1L, modTime = 500L),   // file 新
            dir("old_folder", 200L),                            // folder 旧
        )
        val result = listOneLevelWith("/books", bookExtensions) { daved }
        // 期望排序：new_folder (folder, 1000) → old_folder (folder, 200) → new_book (500) → old_book (100)
        assertEquals(
            listOf("new_folder", "old_folder", "new_book.epub", "old_book.epub"),
            result.map { it.name },
        )
    }

    // ── 场景 c：空输入 ──────────────────────────────────────────

    @Test
    fun `empty input returns empty list`() = runBlocking {
        val result = listOneLevelWith("/empty", bookExtensions) { emptyList() }
        assertTrue(result.isEmpty())
    }

    // ── 场景 d：listFn 抛异常 ──────────────────────────────────

    @Test
    fun `listFn throwing IOException returns empty list not throwing`() = runBlocking {
        val result = listOneLevelWith("/bad", bookExtensions) { throw IOException("network down") }
        assertTrue(result.isEmpty())
    }

    @Test
    fun `listFn throwing generic Exception is also swallowed`() = runBlocking {
        val result = listOneLevelWith("/bad", bookExtensions) { throw RuntimeException("boom") }
        assertTrue(result.isEmpty())
    }

    @Test
    fun `entries carry remote path joined from dirPath`() = runBlocking {
        val daved = listOf(file("书 A.epub", size = 1L, modTime = 1L))
        val result = listOneLevelWith("/MoRealm/books/示例 A", bookExtensions) { daved }
        assertEquals(1, result.size)
        assertEquals("/MoRealm/books/示例 A/书 A.epub", result[0].remotePath)
    }

    // ── 测试辅助 ───────────────────────────────────────────────

    private fun dir(name: String, modTime: Long) = WebDavClient.DavFile(
        name = name,
        href = "/books/$name",
        isDirectory = true,
        size = 0L,
        lastModified = "",
        lastModifiedEpoch = modTime,
    )

    private fun file(name: String, size: Long, modTime: Long) = WebDavClient.DavFile(
        name = name,
        href = "/books/$name",
        isDirectory = false,
        size = size,
        lastModified = "",
        lastModifiedEpoch = modTime,
    )
}

private fun <T> runBlocking(block: suspend () -> T): T =
    kotlinx.coroutines.runBlocking { block() }
