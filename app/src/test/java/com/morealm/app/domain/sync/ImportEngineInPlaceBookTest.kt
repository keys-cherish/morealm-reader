package com.morealm.app.domain.sync

import android.net.Uri
import com.morealm.app.domain.entity.BookFormat
import com.morealm.app.domain.storage.FastFileScanner
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 导入「原位引用」契约测试（brief 契约用例 1 的可单测半边）：
 *
 * - localPath == 原文件 uri 原样字符串（file:// 与 SAF content:// 都不改写、不复制）
 * - fileSize/fileMtime == 扫描带出的指纹（章节 DB 缓存的失效校验依据）
 *
 * 「全程无文件复制」的另一半由代码层保证：ImportEngine / ShelfImportController
 * 已不再引用 LocalBookStorage.saveAsLocal（编译期可查），导入 = 纯 DB insert。
 *
 * 需要 Robolectric：android.net.Uri 在纯 JVM 下是 stub。
 */
@RunWith(RobolectricTestRunner::class)
class ImportEngineInPlaceBookTest {

    @Test
    fun `file scheme - localPath is original uri, fingerprint stamped`() {
        val uri = Uri.fromFile(java.io.File("/storage/emulated/0/Books/示例长篇.txt"))
        val item = FastFileScanner.ScanItem(
            uri = uri,
            name = "示例长篇.txt",
            filePath = "/storage/emulated/0/Books/示例长篇.txt",
            size = 1_234_567L,
            lastModified = 1_700_000_000_000L,
        )

        val book = ImportEngine.buildInPlaceBook(
            item, BookFormat.TXT, folderId = "folder-1", addedAtStamp = 42L,
        )

        assertEquals(uri.toString(), book.localPath)
        assertEquals(1_234_567L, book.fileSize)
        assertEquals(1_700_000_000_000L, book.fileMtime)
        assertEquals(BookFormat.TXT, book.format)
        assertEquals("folder-1", book.folderId)
        assertEquals(42L, book.addedAt)
        assertEquals("示例长篇", book.title)
    }

    @Test
    fun `SAF content scheme - document uri preserved verbatim`() {
        // tree 派生 document uri（含转义字符）必须原样落库——任何 normalize/decode
        // 都会让 openInputStream / findByLocalPath dedup 失配
        val raw = "content://com.android.externalstorage.documents/tree/primary%3ABooks" +
            "/document/primary%3ABooks%2F%E6%B5%8B%E8%AF%95%E4%B9%A6.epub"
        val item = FastFileScanner.ScanItem(
            uri = Uri.parse(raw),
            name = "测试之书.epub",
            filePath = null,
            size = 88_888L,
            lastModified = 0L, // provider 不给 mtime 的情形
        )

        val book = ImportEngine.buildInPlaceBook(
            item, BookFormat.EPUB, folderId = null, addedAtStamp = 1L,
        )

        assertEquals(raw, book.localPath)
        assertEquals(88_888L, book.fileSize)
        assertEquals(0L, book.fileMtime)
        assertEquals("测试之书", book.title)
    }

    @Test
    fun `filename with author bracket parsed into title-author`() {
        val item = FastFileScanner.ScanItem(
            uri = Uri.parse("file:///Books/%5Bx%5D.txt"),
            name = "【测试作者】测试书名.txt",
            filePath = null,
        )
        val book = ImportEngine.buildInPlaceBook(item, BookFormat.TXT, null, 0L)
        assertEquals("测试书名", book.title)
        assertEquals("测试作者", book.author)
        // size/mtime 缺省 0：老路径 / 拿不到指纹时首开解析后由 reader 回填
        assertEquals(0L, book.fileSize)
        assertEquals(0L, book.fileMtime)
    }
}
