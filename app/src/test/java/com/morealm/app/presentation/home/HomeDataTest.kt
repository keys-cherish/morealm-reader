package com.morealm.app.presentation.home

import com.morealm.app.domain.entity.Book
import com.morealm.app.domain.repository.DailyQuoteRepository
import com.morealm.app.ui.home.quickActionsThemeRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeDataTest {

    @Test
    fun `同一天始终选择同一条离线句子`() {
        val first = DailyQuoteRepository.fallbackForDay("2026-07-20")
        val second = DailyQuoteRepository.fallbackForDay("2026-07-20")

        assertEquals(first, second)
        assertTrue(first.text.isNotBlank())
        assertTrue(first.source.isNotBlank())
    }

    @Test
    fun `相邻日期会轮换离线句子`() {
        val today = DailyQuoteRepository.fallbackForDay("2026-07-20")
        val tomorrow = DailyQuoteRepository.fallbackForDay("2026-07-21")

        assertNotEquals(today, tomorrow)
    }

    @Test
    fun `一言作者和作品名会完整保留`() {
        assertEquals(
            "李商隐 · 锦瑟",
            DailyQuoteRepository.formatSource(" 李商隐 ", "锦瑟"),
        )
        assertEquals("锦瑟", DailyQuoteRepository.formatSource(null, "锦瑟"))
        assertEquals("佚名", DailyQuoteRepository.formatSource(" ", null))
    }

    @Test
    fun `常用功能精灵图按主题选择正确行`() {
        assertEquals(0, quickActionsThemeRow(isDarkTheme = false, isEinkTheme = false))
        assertEquals(1, quickActionsThemeRow(isDarkTheme = true, isEinkTheme = false))
        assertEquals(2, quickActionsThemeRow(isDarkTheme = false, isEinkTheme = true))
        assertEquals(2, quickActionsThemeRow(isDarkTheme = true, isEinkTheme = true))
    }

    @Test
    fun `阅读历史过滤未读书并按访问时间排序`() {
        val books = listOf(
            book(id = "old", lastReadAt = 100L, addedAt = 5L),
            book(id = "unread", lastReadAt = 0L, addedAt = 999L),
            book(id = "new", lastReadAt = 300L, addedAt = 1L),
            book(id = "middle", lastReadAt = 200L, addedAt = 2L),
            book(id = "same-newer-added", lastReadAt = 200L, addedAt = 8L),
        )

        val sorted = sortReadingHistoryByLru(books)

        assertEquals(
            listOf("new", "same-newer-added", "middle", "old"),
            sorted.map { it.id },
        )
        assertEquals(listOf("new", "same-newer-added", "middle"), sorted.take(3).map { it.id })
    }

    private fun book(id: String, lastReadAt: Long, addedAt: Long): Book =
        Book(
            id = id,
            title = id,
            lastReadAt = lastReadAt,
            addedAt = addedAt,
        )
}
