package com.morealm.app.presentation.profile

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class ReadingStatsFormattingTest {
    @Test fun `reading streak is not truncated at thirty days`() {
        val today = LocalDate.of(2026, 9, 8)
        val dates = (0L until 45).map { today.minusDays(it).toString() }.toSet()
        assertEquals(45, readingStreak(dates, today))
    }
    @Test fun `yesterday streak remains visible before first reading today`() {
        val today = LocalDate.of(2026, 9, 8)
        assertEquals(2, readingStreak(setOf("2026-09-07", "2026-09-06"), today))
        assertEquals(0, readingStreak(setOf("2026-09-06"), today))
    }
    @Test fun `positive subminute reading is not displayed as zero`() {
        assertEquals("不足 1 分钟", formatReadingDuration(45_000))
        assertEquals("1 小时 5 分钟", formatReadingDuration(65 * 60_000L))
        assertEquals("0 分钟", formatReadingDuration(-1))
    }
}
