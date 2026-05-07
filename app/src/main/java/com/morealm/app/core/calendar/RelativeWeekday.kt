package com.morealm.app.core.calendar

import java.time.DayOfWeek
import java.time.LocalDate

/**
 * 「M 月第 N 个星期 X」类节日的日期计算。
 *
 * 典型用例：
 *  - 母亲节 = 5 月第 2 个星期日
 *  - 父亲节 = 6 月第 3 个星期日
 *  - 感恩节 = 11 月第 4 个星期四（美式）
 *  - 中国植树节 12 日 / 不属于此类，走固定公历
 *
 * # 相对偏移算法
 *
 * 给定 `(year, month, weekday, n)`：
 *
 * 1. 取该月 1 号的 [DayOfWeek]
 * 2. 算出 1 号距离 `weekday` 的天数偏移：
 *    `offset = (weekday.value - firstDow.value + 7) mod 7`
 *    （value 1=Mon..7=Sun；mod 7 让 offset ∈ [0, 6]）
 * 3. 第一次出现的日期 = 1 + offset
 * 4. 第 n 次 = 1 + offset + (n − 1) × 7
 *
 * 整个计算无任何浮点，纯 Int 加减取模，O(1)。
 *
 * # 边界检查
 *  - n=5 偶尔越月（如 2 月只有 28-29 天，5 个星期 X 可能不存在）；越界返回 null
 *  - month 1..12，weekday 任意，n 1..5
 */
object RelativeWeekday {

    /**
     * 计算某月第 [n] 个 [weekday]（如 5 月第 2 个星期日）的公历日期。
     *
     * @param year 公历年份
     * @param month 月份 1..12
     * @param weekday 目标星期
     * @param n 第几次出现，1..5
     * @return 该日的 [LocalDate]，越界返回 null
     */
    fun nthDayOfMonth(year: Int, month: Int, weekday: DayOfWeek, n: Int): LocalDate? {
        if (month !in 1..12 || n !in 1..5) return null
        val firstOfMonth = LocalDate.of(year, month, 1)
        val firstDow = firstOfMonth.dayOfWeek
        // mod 7：把 weekday 落到 1 号同周或下一周
        val offset = (weekday.value - firstDow.value + 7) % 7
        val day = 1 + offset + (n - 1) * 7
        // 越月校验：YearMonth.lengthOfMonth() 太重，直接 try-catch LocalDate.of
        return runCatching { LocalDate.of(year, month, day) }.getOrNull()
    }

    /**
     * 反查：给定日期是否是「month 第 N 个 weekday」。常用于节日匹配。
     *
     * 算法：用 [LocalDate.dayOfMonth] 反推第几次出现。
     *  - dayOfMonth = 1 + offset + (n − 1) × 7
     *  - 解出 n = (dayOfMonth − 1 − offset) / 7 + 1
     */
    fun whichOccurrence(date: LocalDate): Int {
        return (date.dayOfMonth - 1) / 7 + 1
    }
}
