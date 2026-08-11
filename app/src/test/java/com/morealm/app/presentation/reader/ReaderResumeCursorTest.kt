package com.morealm.app.presentation.reader

import com.morealm.app.domain.entity.Book
import com.morealm.app.domain.entity.BookChapter
import com.morealm.app.domain.entity.ReadProgress
import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderResumeCursorTest {
    private fun book(
        chapter: Int = 2,
        position: Int = 200,
        totalProgress: Float = 0.25f,
        updatedAt: Long = 100L,
    ) = Book(
        id = "book",
        title = "测试书",
        lastReadChapter = chapter,
        lastReadPosition = position,
        readProgress = totalProgress,
        lastReadAt = updatedAt,
    )

    private fun chapters(count: Int): List<BookChapter> = List(count) { i ->
        BookChapter(id = "book_$i", bookId = "book", index = i, title = "第${i}章", url = "ch/$i.xhtml")
    }

    @Test
    fun `专用进度较新时整份使用专用进度`() {
        val cursor = resolveReaderResumeCursor(
            book = book(chapter = 6, position = 666, updatedAt = 100L),
            progress = ReadProgress(
                bookId = "book",
                chapterIndex = 3,
                chapterPosition = 333,
                totalProgress = 0.35f,
                updatedAt = 200L,
            ),
            chapters = chapters(10),
        )

        assertEquals(3, cursor.chapterIndex)
        assertEquals(333, cursor.chapterPosition)
        assertEquals(50, cursor.chapterProgress)
        assertEquals(ReaderResumeCursor.Source.READ_PROGRESS, cursor.source)
    }

    @Test
    fun `Book 较新时不与旧专用进度混合`() {
        val cursor = resolveReaderResumeCursor(
            book = book(chapter = 7, position = 777, totalProgress = 0.75f, updatedAt = 300L),
            progress = ReadProgress(
                bookId = "book",
                chapterIndex = 2,
                chapterPosition = 222,
                totalProgress = 0.25f,
                updatedAt = 200L,
            ),
            chapters = chapters(10),
        )

        assertEquals(7, cursor.chapterIndex)
        assertEquals(777, cursor.chapterPosition)
        assertEquals(50, cursor.chapterProgress)
        assertEquals(ReaderResumeCursor.Source.BOOK, cursor.source)
    }

    @Test
    fun `较新进度的合法零位置不会回退到另一章旧位置`() {
        val cursor = resolveReaderResumeCursor(
            book = book(chapter = 2, position = 999, updatedAt = 100L),
            progress = ReadProgress(
                bookId = "book",
                chapterIndex = 4,
                chapterPosition = 0,
                totalProgress = 0.4f,
                updatedAt = 200L,
            ),
            chapters = chapters(10),
        )

        assertEquals(4, cursor.chapterIndex)
        assertEquals(0, cursor.chapterPosition)
    }

    @Test
    fun `章节索引越界时清除不可移植的字符位置`() {
        val cursor = resolveReaderResumeCursor(
            book = book(updatedAt = 100L),
            progress = ReadProgress(
                bookId = "book",
                chapterIndex = 20,
                chapterPosition = 888,
                totalProgress = 0.9f,
                updatedAt = 200L,
            ),
            chapters = chapters(5),
        )

        assertEquals(4, cursor.chapterIndex)
        assertEquals(0, cursor.chapterPosition)
        assertEquals(0, cursor.chapterProgress)
    }

    // ── 锚点 v2 ──

    @Test
    fun `chapterId 失配时按 id 重映射章号并保留 cp 与快照`() {
        // 目录刷新后原第 3 章挪到了第 5 章；index 存的 3 已经指向别的章
        val cursor = resolveReaderResumeCursor(
            book = book(updatedAt = 100L),
            progress = ReadProgress(
                bookId = "book",
                chapterIndex = 3,
                chapterPosition = 333,
                totalProgress = 0.35f,
                chapterId = "ch/5.xhtml",
                anchorSnippet = "云与云之间的缝隙里漏下来光",
                updatedAt = 200L,
            ),
            chapters = chapters(10),
        )

        assertEquals(5, cursor.chapterIndex)
        assertEquals(333, cursor.chapterPosition)
        // totalProgress 是按旧章号编码的，重映射后失义 → 清零（cp/快照才是真锚）
        assertEquals(0, cursor.chapterProgress)
        assertEquals("云与云之间的缝隙里漏下来光", cursor.anchorSnippet)
    }

    @Test
    fun `chapterId 在目录里找不到时保持原 index 行为不变`() {
        val cursor = resolveReaderResumeCursor(
            book = book(updatedAt = 100L),
            progress = ReadProgress(
                bookId = "book",
                chapterIndex = 3,
                chapterPosition = 333,
                totalProgress = 0.35f,
                chapterId = "gone.xhtml",
                updatedAt = 200L,
            ),
            chapters = chapters(10),
        )

        assertEquals(3, cursor.chapterIndex)
        assertEquals(333, cursor.chapterPosition)
    }

    @Test
    fun `Book 源较新时不使用进度表的快照`() {
        val cursor = resolveReaderResumeCursor(
            book = book(chapter = 7, position = 777, updatedAt = 300L),
            progress = ReadProgress(
                bookId = "book",
                chapterIndex = 2,
                chapterPosition = 222,
                anchorSnippet = "旧快照",
                updatedAt = 200L,
            ),
            chapters = chapters(10),
        )

        assertEquals("", cursor.anchorSnippet)
    }

    // ── 换源 ──
    //
    // 换源后 ChangeSourceController 会同步覆写 read_progress（新 index / 新章 url /
    // 清 anchorSnippet / 顶 updatedAt），使恢复游标选中它而非旧 Book 镜像 ——
    // 否则 ChapterMatcher 算出的目标章会被旧进度覆盖（详见 ChangeSourceController
    // Step 5.1 注释）。以下三个用例验证「覆写后的进度表」是恢复游标的最终依据。

    @Test
    fun `换源覆写后的进度优先于旧 Book 镜像`() {
        val cursor = resolveReaderResumeCursor(
            // book.lastReadChapter = newIndex（ChapterMatcher 的结果），但 updatedAt 较旧
            book = book(chapter = 7, position = 0, totalProgress = 0.7f, updatedAt = 100L),
            // 换源覆写后的进度表：chapterIndex = newIndex、chapterId = 新源章 url
            progress = ReadProgress(
                bookId = "book",
                chapterIndex = 7,
                chapterPosition = 0,
                totalProgress = 0.7f,
                chapterId = "ch/7.xhtml",
                anchorSnippet = "",
                updatedAt = 200L,
            ),
            chapters = chapters(10),
        )

        assertEquals(7, cursor.chapterIndex)
        assertEquals(0, cursor.chapterPosition)
        assertEquals(ReaderResumeCursor.Source.READ_PROGRESS, cursor.source)
    }

    @Test
    fun `换源后进度与 Book 镜像同源且 chapterId 对得上时保持新章`() {
        val cursor = resolveReaderResumeCursor(
            // 换源同时写了 Book（lastReadChapter=newIndex）与 read_progress（同 index + 新 url）
            book = book(chapter = 7, position = 0, totalProgress = 0.7f, updatedAt = 200L),
            progress = ReadProgress(
                bookId = "book",
                chapterIndex = 7,
                chapterPosition = 0,
                totalProgress = 0.7f,
                chapterId = "ch/7.xhtml",
                anchorSnippet = "",
                updatedAt = 200L,
            ),
            chapters = chapters(10),
        )

        assertEquals(7, cursor.chapterIndex)
        assertEquals(0, cursor.chapterPosition)
    }

    @Test
    fun `换源后进度 chapterId 指向新源章时按 id 重映射生效`() {
        // 极端：chapterIndex 与 chapterId 不同步（目录结构差异），id 自校验应按 url 重映射
        val cursor = resolveReaderResumeCursor(
            book = book(chapter = 7, position = 0, totalProgress = 0.7f, updatedAt = 100L),
            progress = ReadProgress(
                bookId = "book",
                chapterIndex = 7,
                chapterPosition = 0,
                totalProgress = 0.7f,
                chapterId = "ch/5.xhtml", // 新源目录里目标章实际是第 5 章
                anchorSnippet = "",
                updatedAt = 200L,
            ),
            chapters = chapters(10),
        )

        assertEquals(5, cursor.chapterIndex)
    }
}
