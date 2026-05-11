package com.morealm.app.domain.holiday.greeting

import com.morealm.app.domain.holiday.greeting.DimensionPools.Genre
import com.morealm.app.domain.holiday.greeting.DimensionPools.StateBucket
import com.morealm.app.domain.holiday.greeting.DimensionPools.Stickiness

/**
 * DMRG（Deterministic Multi-stage Recursive Grammar）句子引擎。
 *
 * **核心思路**：
 *  - 不查表打分、不调 Random — 把 [GreetingContext] 哈希成一个稳定 seed，
 *    再用位移坍缩在四轴桶里取索引。
 *  - 复杂度：O(1) 数组索引 + 一次 7-Genre × K-keyword contains 扫描（K<10）。
 *  - 测得调用 < 50µs；调用方仍由 Presenter 放 IO 协程，纯粹为了不阻塞首屏布局。
 *
 * **种子设计**：
 *  - 同一天 + 同一本书 + 同一节日 → 同一句（用户多次打开看到一致）
 *  - 跨天 / 换书 / 节日不同 → 新句（保持新鲜感）
 *  - 阅读时长按 10 分钟分桶，避免每分钟一变
 *
 * **黄金比例常数** 0x9E3779B97F4A7C15 — Knuth 推荐的乘法哈希分布优良常数，
 * 用来在 seed 计算里把日序号铺开，避免邻近日期的 seed 高位贴近。
 */
internal object GreetingEngine {

    private const val GOLDEN_RATIO = -7046029254386353131L

    fun generate(ctx: GreetingContext): String {
        val seed = composeSeed(ctx)
        val skeleton = HolidayTemplates.SKELETONS[
            bucketIndex(seed, 0, HolidayTemplates.SKELETONS.size)
        ]
        val a = pickA(ctx, seed)
        val b = pickB(ctx, seed)
        val c = pickC(ctx, seed)
        val d = pickD(ctx, seed)
        return skeleton
            .replace("{A}", a)
            .replace("{B}", b)
            .replace("{C}", c)
            .replace("{D}", d)
    }

    private fun composeSeed(ctx: GreetingContext): Long {
        val day = ctx.date.toEpochDay()
        val holiday = ctx.holidayId.hashCode().toLong()
        val title = (ctx.bookTitle ?: "").hashCode().toLong()
        val durationBucket = (ctx.todayMinutes / 10).toLong()
        return (day * GOLDEN_RATIO) xor
            (holiday shl 16) xor
            (title shl 32) xor
            durationBucket
    }

    /** seed 位移后取模 — 高位与低位分布独立，让四轴各取不同位段。 */
    private fun bucketIndex(seed: Long, shift: Int, size: Int): Int {
        if (size <= 0) return 0
        return ((seed ushr shift) % size).toInt()
    }

    // ────────── A 轴：节日开场 ──────────

    private fun pickA(ctx: GreetingContext, seed: Long): String {
        val intros = HolidayTemplates.HOLIDAY_INTROS[ctx.holidayId].orEmpty()
        val pool = if (intros.isEmpty()) {
            listOf(ctx.holidayFallbackMessage)
        } else {
            intros + ctx.holidayFallbackMessage
        }
        return pool[bucketIndex(seed, 8, pool.size)]
    }

    // ────────── B 轴：阅读状态 / 时段 ──────────

    private fun pickB(ctx: GreetingContext, seed: Long): String {
        val bucket = deriveStateBucket(ctx)
        val pool = DimensionPools.STATE_VIBES[bucket] ?: DimensionPools.STATE_VIBES_FALLBACK
        return pool[bucketIndex(seed, 16, pool.size)]
    }

    private fun deriveStateBucket(ctx: GreetingContext): StateBucket {
        val tier = when {
            ctx.todayMinutes >= 30 -> Tier.DEEP
            ctx.todayMinutes >= 6 -> Tier.BRIEF
            else -> Tier.COLD
        }
        return when (ctx.hourOfDay) {
            in 0..4 -> StateBucket.DAWN
            in 5..11 -> when (tier) {
                Tier.DEEP -> StateBucket.MORNING_DEEP
                Tier.BRIEF -> StateBucket.MORNING_BRIEF
                Tier.COLD -> StateBucket.MORNING_COLD
            }
            in 12..13 -> StateBucket.NOON
            in 14..17 -> when (tier) {
                Tier.DEEP -> StateBucket.AFTERNOON_DEEP
                Tier.BRIEF -> StateBucket.AFTERNOON_BRIEF
                Tier.COLD -> StateBucket.AFTERNOON_COLD
            }
            in 18..19 -> StateBucket.DUSK
            in 20..23 -> when (tier) {
                Tier.DEEP -> StateBucket.NIGHT_DEEP
                Tier.BRIEF -> StateBucket.NIGHT_BRIEF
                Tier.COLD -> StateBucket.NIGHT_COLD
            }
            else -> StateBucket.NIGHT_COLD
        }
    }

    private enum class Tier { DEEP, BRIEF, COLD }

    // ────────── C 轴：书籍共鸣 ──────────

    private fun pickC(ctx: GreetingContext, seed: Long): String {
        if (ctx.bookTitle.isNullOrBlank()) {
            val none = DimensionPools.BOOK_RESONANCES_NONE
            return none[bucketIndex(seed, 24, none.size)]
        }
        val genre = deriveGenre(ctx)
        val pool = DimensionPools.BOOK_RESONANCES[genre]
            ?: DimensionPools.BOOK_RESONANCES.getValue(Genre.UNKNOWN)
        return pool[bucketIndex(seed, 24, pool.size)]
            .replace("{title}", ctx.bookTitle)
    }

    private fun deriveGenre(ctx: GreetingContext): Genre {
        val fields = listOfNotNull(ctx.bookTitle, ctx.bookCategory, ctx.bookKind)
        if (fields.isEmpty()) return Genre.NONE
        for ((genre, keywords) in DimensionPools.GENRE_KEYWORDS) {
            for (kw in keywords) {
                if (fields.any { it.contains(kw) }) return genre
            }
        }
        return Genre.UNKNOWN
    }

    // ────────── D 轴：情感闭环 ──────────

    private fun pickD(ctx: GreetingContext, seed: Long): String {
        val sticky = deriveStickiness(ctx)
        val pool = DimensionPools.EMOTION_CLOSURES.getValue(sticky)
        return pool[bucketIndex(seed, 40, pool.size)]
    }

    private fun deriveStickiness(ctx: GreetingContext): Stickiness = when {
        ctx.todayMinutes >= 60 -> Stickiness.STICKY
        ctx.daysSinceLastRead in 7..90 -> Stickiness.AWAKENING
        else -> Stickiness.PEACEFUL
    }
}
