package com.morealm.app.core.calendar

import java.time.LocalDate

/**
 * 农历（阴历）转换工具 — 基于 1900-2099 年历表 + 位运算解码。
 *
 * # 数据结构：状态压缩
 * 农历年的全部信息压缩在一个 [Int] 里（每年）：
 *
 * ```
 *   bit 16 │ bits 15..4 │ bits 3..0
 *   leap30 │  m1..m12   │ leap_m
 *   1 bit  │  12 bits   │  4 bits
 * ```
 *
 *  - **bits 0..3** `leap_m` ：闰月月份（1..12），0 表示该年无闰月
 *  - **bits 4..15** `m1..m12` ：每个 bit 对应一个月，1 = 大月 30 天 / 0 = 小月 29 天，
 *    位顺序低位 → 高位 = 1 月 → 12 月（注意：标准 lunarInfo 数据按 高 → 低 编码月份，
 *    具体见 [getMonthDays]）
 *  - **bit 16** `leap30` ：闰月是大月（30）还是小月（29）；leap_m=0 时无意义
 *
 * 1900-2099 共 200 年的表占用 200 × 4B = 800 字节，比存离散字段省得多。
 *
 * # 转换基准
 * 1900-01-31（公历）= 1900 年正月初一（农历）。所有公历日期先算到这个基准的天数偏移
 * `delta`，再在 [LUNAR_INFO] 上**逐年减、逐月减**还原成 (年, 月, 日, isLeap)。
 *
 * 反向（农历→公历）同理：累加天数到基准上 [LocalDate.plusDays]。
 *
 * # 精度
 * 表里 200 条数据来自紫金山天文台权威农历表，覆盖 1900-01-31 到 2099-12-31 的全部
 * 公历↔农历转换。不在该范围的输入返回 null（书架场景实际数据都在这区间内）。
 */
object LunarCalendar {

    /** 1900-01-31 = 农历 1900 年正月初一。所有日期偏移基准。 */
    private val BASE_DATE: LocalDate = LocalDate.of(1900, 1, 31)
    private const val BASE_LUNAR_YEAR = 1900
    private const val MAX_LUNAR_YEAR = 2099

    /**
     * 农历信息表 1900-2099。每条 17-bit `Int`：
     *   `bit 16` = 闰月日数（1=30 / 0=29），`bit 15..4` = 12 个月的大小月（高位为 1 月，
     *   1=30 / 0=29），`bit 3..0` = 闰月月份（0 无闰）。
     *
     * 数据为业界通用 lunarInfo 表（《GB/T 33661-2017 农历的编算和颁行》参考实现）。
     */
    private val LUNAR_INFO = intArrayOf(
        0x04bd8, 0x04ae0, 0x0a570, 0x054d5, 0x0d260, 0x0d950, 0x16554, 0x056a0, 0x09ad0, 0x055d2, // 1900-1909
        0x04ae0, 0x0a5b6, 0x0a4d0, 0x0d250, 0x1d255, 0x0b540, 0x0d6a0, 0x0ada2, 0x095b0, 0x14977, // 1910-1919
        0x04970, 0x0a4b0, 0x0b4b5, 0x06a50, 0x06d40, 0x1ab54, 0x02b60, 0x09570, 0x052f2, 0x04970, // 1920-1929
        0x06566, 0x0d4a0, 0x0ea50, 0x06e95, 0x05ad0, 0x02b60, 0x186e3, 0x092e0, 0x1c8d7, 0x0c950, // 1930-1939
        0x0d4a0, 0x1d8a6, 0x0b550, 0x056a0, 0x1a5b4, 0x025d0, 0x092d0, 0x0d2b2, 0x0a950, 0x0b557, // 1940-1949
        0x06ca0, 0x0b550, 0x15355, 0x04da0, 0x0a5b0, 0x14573, 0x052b0, 0x0a9a8, 0x0e950, 0x06aa0, // 1950-1959
        0x0aea6, 0x0ab50, 0x04b60, 0x0aae4, 0x0a570, 0x05260, 0x0f263, 0x0d950, 0x05b57, 0x056a0, // 1960-1969
        0x096d0, 0x04dd5, 0x04ad0, 0x0a4d0, 0x0d4d4, 0x0d250, 0x0d558, 0x0b540, 0x0b6a0, 0x195a6, // 1970-1979
        0x095b0, 0x049b0, 0x0a974, 0x0a4b0, 0x0b27a, 0x06a50, 0x06d40, 0x0af46, 0x0ab60, 0x09570, // 1980-1989
        0x04af5, 0x04970, 0x064b0, 0x074a3, 0x0ea50, 0x06b58, 0x055c0, 0x0ab60, 0x096d5, 0x092e0, // 1990-1999
        0x0c960, 0x0d954, 0x0d4a0, 0x0da50, 0x07552, 0x056a0, 0x0abb7, 0x025d0, 0x092d0, 0x0cab5, // 2000-2009
        0x0a950, 0x0b4a0, 0x0baa4, 0x0ad50, 0x055d9, 0x04ba0, 0x0a5b0, 0x15176, 0x052b0, 0x0a930, // 2010-2019
        0x07954, 0x06aa0, 0x0ad50, 0x05b52, 0x04b60, 0x0a6e6, 0x0a4e0, 0x0d260, 0x0ea65, 0x0d530, // 2020-2029
        0x05aa0, 0x076a3, 0x096d0, 0x04afb, 0x04ad0, 0x0a4d0, 0x1d0b6, 0x0d250, 0x0d520, 0x0dd45, // 2030-2039
        0x0b5a0, 0x056d0, 0x055b2, 0x049b0, 0x0a577, 0x0a4b0, 0x0aa50, 0x1b255, 0x06d20, 0x0ada0, // 2040-2049
        0x14b63, 0x09370, 0x049f8, 0x04970, 0x064b0, 0x168a6, 0x0ea50, 0x06b20, 0x1a6c4, 0x0aae0, // 2050-2059
        0x0a2e0, 0x0d2e3, 0x0c960, 0x0d557, 0x0d4a0, 0x0da50, 0x05d55, 0x056a0, 0x0a6d0, 0x055d4, // 2060-2069
        0x052d0, 0x0a9b8, 0x0a950, 0x0b4a0, 0x0b6a6, 0x0ad50, 0x055a0, 0x0aba4, 0x0a5b0, 0x052b0, // 2070-2079
        0x0b273, 0x06930, 0x07337, 0x06aa0, 0x0ad50, 0x14b55, 0x04b60, 0x0a570, 0x054e4, 0x0d160, // 2080-2089
        0x0e968, 0x0d520, 0x0daa0, 0x16aa6, 0x056d0, 0x04ae0, 0x0a9d4, 0x0a2d0, 0x0d150, 0x0f252, // 2090-2099
    )

    /** 农历 / 公历互转结果。 */
    data class LunarDate(
        val year: Int,
        val month: Int,
        val day: Int,
        /** 该日落在闰月里时为 true（年里"闰 5 月"vs"5 月"靠这个区分）。 */
        val isLeapMonth: Boolean,
    )

    /** 获取该年闰月（1..12），0 表示无闰月。 [LUNAR_INFO] 低 4 位。 */
    fun getLeapMonth(lunarYear: Int): Int {
        if (lunarYear !in BASE_LUNAR_YEAR..MAX_LUNAR_YEAR) return 0
        return LUNAR_INFO[lunarYear - BASE_LUNAR_YEAR] and 0xF
    }

    /** 该年闰月日数（29 / 30），无闰月时返回 0。 [LUNAR_INFO] 第 16 bit。 */
    fun getLeapMonthDays(lunarYear: Int): Int {
        if (getLeapMonth(lunarYear) == 0) return 0
        return if ((LUNAR_INFO[lunarYear - BASE_LUNAR_YEAR] and 0x10000) != 0) 30 else 29
    }

    /**
     * 该年第 [lunarMonth] 个普通月（不含闰月）的天数（29 / 30）。
     *
     * lunarInfo 第 4..15 位编码 12 个月的大小月。bit 第 (15 − month + 1) 位对应该月：
     *   - bit 15 = 1 月
     *   - bit 14 = 2 月
     *   - …
     *   - bit  4 = 12 月
     *
     * 所以测试位运算：`(info shr (16 - month)) and 1`。
     */
    fun getMonthDays(lunarYear: Int, lunarMonth: Int): Int {
        if (lunarYear !in BASE_LUNAR_YEAR..MAX_LUNAR_YEAR) return 0
        if (lunarMonth !in 1..12) return 0
        val info = LUNAR_INFO[lunarYear - BASE_LUNAR_YEAR]
        return if ((info shr (16 - lunarMonth)) and 1 != 0) 30 else 29
    }

    /** 该年总天数（含闰月）。354 / 355（无闰）or 383 / 384（有闰）。 */
    fun getYearDays(lunarYear: Int): Int {
        var sum = 0
        for (m in 1..12) sum += getMonthDays(lunarYear, m)
        return sum + getLeapMonthDays(lunarYear)
    }

    /** 公历 → 农历。超出 1900-2099 范围返回 null。 */
    fun fromSolar(date: LocalDate): LunarDate? {
        if (date.isBefore(BASE_DATE)) return null
        var delta = java.time.temporal.ChronoUnit.DAYS.between(BASE_DATE, date).toInt()
        // 第一步：逐年减
        var year = BASE_LUNAR_YEAR
        while (year <= MAX_LUNAR_YEAR) {
            val yd = getYearDays(year)
            if (delta < yd) break
            delta -= yd
            year++
        }
        if (year > MAX_LUNAR_YEAR) return null

        // 第二步：在年内逐月减。遇到闰月特殊处理。
        val leapMonth = getLeapMonth(year)
        var month = 1
        var isLeap = false
        while (month <= 12) {
            val md = getMonthDays(year, month)
            if (delta < md) break
            delta -= md
            // 闰月紧跟在 leapMonth 之后插入（如闰 5 月在 5 月正常月之后）
            if (month == leapMonth) {
                val lmd = getLeapMonthDays(year)
                if (delta < lmd) {
                    isLeap = true
                    break
                }
                delta -= lmd
            }
            month++
        }
        return LunarDate(year, month, delta + 1, isLeap)
    }

    /** 农历 → 公历。参数无效（年超界 / 月不存在 / 日越界）返回 null。 */
    fun toSolar(lunarYear: Int, lunarMonth: Int, lunarDay: Int, isLeapMonth: Boolean): LocalDate? {
        if (lunarYear !in BASE_LUNAR_YEAR..MAX_LUNAR_YEAR) return null
        if (lunarMonth !in 1..12 || lunarDay < 1) return null
        if (isLeapMonth && getLeapMonth(lunarYear) != lunarMonth) return null

        var totalDays = 0
        for (y in BASE_LUNAR_YEAR until lunarYear) totalDays += getYearDays(y)
        val leapMonth = getLeapMonth(lunarYear)
        for (m in 1 until lunarMonth) {
            totalDays += getMonthDays(lunarYear, m)
            if (m == leapMonth) totalDays += getLeapMonthDays(lunarYear)
        }
        // 跳过当月正常部分（如查"闰 5 月"，要先跨过 5 月再计入闰月里的日子）
        if (isLeapMonth) {
            totalDays += getMonthDays(lunarYear, lunarMonth)
            if (lunarDay > getLeapMonthDays(lunarYear)) return null
        } else {
            if (lunarDay > getMonthDays(lunarYear, lunarMonth)) return null
        }
        totalDays += lunarDay - 1
        return BASE_DATE.plusDays(totalDays.toLong())
    }
}
