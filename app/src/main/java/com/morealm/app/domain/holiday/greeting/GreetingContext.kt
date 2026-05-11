package com.morealm.app.domain.holiday.greeting

import com.morealm.app.domain.holiday.Holiday
import com.morealm.app.domain.repository.BookRepository
import com.morealm.app.domain.repository.ReadStatsRepository
import java.time.LocalDate
import java.time.LocalDateTime
import kotlinx.coroutines.flow.firstOrNull

/**
 * 节日彩蛋生成上下文 — DMRG 引擎的全部输入数据。
 *
 * 设计：
 *  - **immutable + valueType** — 可作为 cache key、可跨线程安全传递。
 *  - **容错** — 所有可空字段允许缺失，[GreetingEngine] 走 NONE/UNKNOWN 桶兜底。
 *  - **小** — 10 个字段，序列化压缩后 < 200B，适合放 DataStore 当缓存索引。
 */
internal data class GreetingContext(
    val date: LocalDate,
    val holidayId: String,
    val holidayName: String,
    val holidayFallbackMessage: String,
    /** 0..23 — 用于 [DimensionPools.StateBucket] 时段段位推导。 */
    val hourOfDay: Int,
    val bookTitle: String?,
    val bookCategory: String?,
    val bookKind: String?,
    /** 今日累计阅读分钟（向下取整）。 */
    val todayMinutes: Int,
    /** 距上次阅读的天数；-1 表示历史无阅读记录或时间戳无效。 */
    val daysSinceLastRead: Int,
) {
    companion object {
        /**
         * 从 Repository 异步组装上下文 — 必须在 IO 协程里调用。
         *
         * 任意子查询失败用合理 fallback（null / 0 / -1），保证引擎不会因 DB
         * 异常拿不到数据；最坏情况下生成一句无书的通用彩蛋。
         */
        suspend fun build(
            holiday: Holiday,
            today: LocalDate,
            bookRepo: BookRepository,
            statsRepo: ReadStatsRepository,
        ): GreetingContext {
            val now = LocalDateTime.now()
            val book = runCatching { bookRepo.getLastReadBook().firstOrNull() }.getOrNull()
            val todayMs = runCatching {
                statsRepo.getByDate(today.toString())?.readDurationMs ?: 0L
            }.getOrNull() ?: 0L
            val daysSince = book?.takeIf { it.lastReadAt > 0L }?.let {
                ((System.currentTimeMillis() - it.lastReadAt) / 86_400_000L)
                    .toInt()
                    .coerceAtLeast(0)
            } ?: -1
            return GreetingContext(
                date = today,
                holidayId = holiday.id,
                holidayName = holiday.name,
                holidayFallbackMessage = holiday.message,
                hourOfDay = now.hour,
                bookTitle = book?.title,
                bookCategory = book?.category,
                bookKind = book?.kind,
                todayMinutes = (todayMs / 60_000L).toInt(),
                daysSinceLastRead = daysSince,
            )
        }
    }
}
