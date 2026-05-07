package com.morealm.app.domain.holiday

import com.morealm.app.core.calendar.LunarCalendar
import com.morealm.app.core.calendar.RelativeWeekday
import com.morealm.app.core.calendar.SolarTerms
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * 节日定义。三大类合并到一个 sealed 体系，[HolidayCatalog.holidaysOn] 用类型分派
 * 算每个 [Holiday] 在某年是否落在指定日期上。
 */
sealed class Holiday {
    abstract val id: String
    abstract val name: String
    /** 弹窗 / 卡片里显示的彩蛋祝福。短一点的一句话，不要凑字。 */
    abstract val message: String

    /** 公历固定日期（如元旦、国庆）。 */
    data class Solar(
        override val id: String,
        override val name: String,
        override val message: String,
        val month: Int,
        val day: Int,
    ) : Holiday()

    /** 农历固定日期（如春节、中秋）。leap 节日罕见，本表里全部按非闰月匹配。 */
    data class Lunar(
        override val id: String,
        override val name: String,
        override val message: String,
        val lunarMonth: Int,
        val lunarDay: Int,
    ) : Holiday()

    /** 节气节日（清明、冬至）— 节气当天即为节日。 */
    data class Term(
        override val id: String,
        override val name: String,
        override val message: String,
        val term: SolarTerms.Term,
    ) : Holiday()

    /** 「M 月第 N 个星期 X」型（母亲节、父亲节、感恩节）。 */
    data class WeekdayInMonth(
        override val id: String,
        override val name: String,
        override val message: String,
        val month: Int,
        val weekday: DayOfWeek,
        val occurrence: Int,
    ) : Holiday()
}

/**
 * 节日清单 — 集中所有彩蛋节日。 [holidaysOn] 用 [Holiday] 子类型分派计算。
 *
 * 设计原则：
 *  - 名字短、寓意正向：MoRealm 是阅读 app，节日彩蛋以"陪伴 / 温暖"基调为主，不刻意
 *    煽情也不空洞祝福
 *  - 一个公历日期可能匹配多个节日（如 4-1 愚人节 + 同年清明节落在 4-4），返回 List
 *    让上层决定显示哪个
 *  - 寿星公式、农历位表、相对偏移算法的结果统一缓存到 [LocalDate] 后比对，全部 O(1)
 *    或 O(节气数)，调用 [holidaysOn] 不会拖启动
 */
object HolidayCatalog {

    /** 全量节日清单。新增节日只在这里加一行就够。 */
    private val ALL: List<Holiday> = listOf(
        // ── 公历节日 ──
        Holiday.Solar("new_year", "元旦", "新一年的第一页，翻得轻一点。", 1, 1),
        Holiday.Solar("valentine", "情人节", "爱书也是一种深情。", 2, 14),
        Holiday.Solar("women_day", "妇女节", "她翻开书，世界为她让路。", 3, 8),
        Holiday.Solar("planting_day", "植树节", "字也是种下的，慢慢就长成森林了。", 3, 12),
        Holiday.Solar("april_fools", "愚人节", "故事里的认真，比现实更值得相信。", 4, 1),
        Holiday.Solar("labor_day", "劳动节", "翻页也是劳动，尤其在追更的时候。", 5, 1),
        Holiday.Solar("youth_day", "青年节", "读到的每一页都成为以后的你。", 5, 4),
        Holiday.Solar("children_day", "儿童节", "大人也可以读童话，不用解释。", 6, 1),
        Holiday.Solar("party_day", "建党节", "百年篇章，仍在续写。", 7, 1),
        Holiday.Solar("army_day", "建军节", "向所有守护安静阅读时光的人致敬。", 8, 1),
        Holiday.Solar("teacher_day", "教师节", "感谢每一本曾经是老师的书。", 9, 10),
        Holiday.Solar("national_day", "国庆节", "祖国给了我们安心读书的几十年。", 10, 1),
        Holiday.Solar("singles_day", "光棍节", "你不是一个人，你有 200 多本未读。", 11, 11),
        Holiday.Solar("christmas", "圣诞节", "今晚的故事里，有人会想起你。", 12, 25),
        Holiday.Solar("reading_day", "世界读书日", "今天读什么都算是过节。", 4, 23),

        // ── 农历节日 ──
        Holiday.Lunar("spring_festival", "春节", "新年好。第一卷新书已经为你翻好。", 1, 1),
        Holiday.Lunar("lantern_festival", "元宵节", "月圆灯亮，宜读温柔的故事。", 1, 15),
        Holiday.Lunar("dragon_boat", "端午节", "粽叶香里，故事更有滋味。", 5, 5),
        Holiday.Lunar("qixi", "七夕节", "天上的故事写了千年，还在继续。", 7, 7),
        Holiday.Lunar("mid_autumn", "中秋节", "月在头顶，书在手边。", 8, 15),
        Holiday.Lunar("chongyang", "重阳节", "登高望远，翻页同辽阔。", 9, 9),
        Holiday.Lunar("lunar_new_year_eve", "除夕", "陪你跨年的不止鞭炮，还有翻页的轻响。", 12, 30), // 注：腊月可能 29 天，匹配时若该年腊月 29 也算

        // ── 节气节日 ──
        Holiday.Term("qing_ming", "清明节", "翻一页，记住该记住的人。", SolarTerms.Term.QING_MING),
        Holiday.Term("dong_zhi", "冬至", "夜最长的这一天，最适合读书。", SolarTerms.Term.DONG_ZHI),
        Holiday.Term("li_chun", "立春", "春天来了，该读点新故事了。", SolarTerms.Term.LI_CHUN),
        Holiday.Term("li_qiu", "立秋", "凉风一起，最适合长篇。", SolarTerms.Term.LI_QIU),

        // ── 月内第 N 星期型 ──
        Holiday.WeekdayInMonth("mothers_day", "母亲节", "今天的故事，献给最早的读者。", 5, DayOfWeek.SUNDAY, 2),
        Holiday.WeekdayInMonth("fathers_day", "父亲节", "他可能不读书，但他读你。", 6, DayOfWeek.SUNDAY, 3),
        Holiday.WeekdayInMonth("thanksgiving", "感恩节", "感谢每一本陪过你的书。", 11, DayOfWeek.THURSDAY, 4),
    )

    /**
     * 当天匹配的所有节日。多匹配优先级保留输入顺序（ALL 列表里排前的优先 — 大节日
     * 一般在前）。无匹配返回空列表。
     */
    fun holidaysOn(date: LocalDate): List<Holiday> = ALL.filter { it.matches(date) }

    /**
     * 即将到来的下一个节日（含今天）。用于"距离 X 天还有 N 天"提示。
     * 范围：今天 → 一年后。仍找不到（理论不会）返回 null。
     */
    fun nextUpcoming(from: LocalDate = LocalDate.now()): Pair<Holiday, LocalDate>? {
        var cur = from
        val end = from.plusYears(1)
        while (!cur.isAfter(end)) {
            val matches = holidaysOn(cur)
            if (matches.isNotEmpty()) return matches.first() to cur
            cur = cur.plusDays(1)
        }
        return null
    }

    /** 单个节日类型与某日比对的核心逻辑。 */
    private fun Holiday.matches(date: LocalDate): Boolean = when (this) {
        is Holiday.Solar -> date.monthValue == month && date.dayOfMonth == day
        is Holiday.Lunar -> {
            val lunar = LunarCalendar.fromSolar(date)
            lunar != null && !lunar.isLeapMonth &&
                lunar.month == lunarMonth &&
                // 除夕特例：腊月最后一天（29 或 30），传入 lunarDay=30 时也匹配 29 大月
                (lunar.day == lunarDay ||
                    (lunarMonth == 12 && lunarDay == 30 &&
                        lunar.day == LunarCalendar.getMonthDays(lunar.year, 12)))
        }
        is Holiday.Term -> SolarTerms.termOn(date) == term
        is Holiday.WeekdayInMonth ->
            date.monthValue == month &&
                date.dayOfWeek == weekday &&
                RelativeWeekday.whichOccurrence(date) == occurrence
    }
}
