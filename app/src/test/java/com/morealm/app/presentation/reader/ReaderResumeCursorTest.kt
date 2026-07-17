package com.morealm.app.presentation.reader

import com.morealm.app.domain.entity.Book
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
            chapterCount = 10,
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
            chapterCount = 10,
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
            chapterCount = 10,
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
            chapterCount = 5,
        )

        assertEquals(4, cursor.chapterIndex)
        assertEquals(0, cursor.chapterPosition)
        assertEquals(0, cursor.chapterProgress)
    }
}
