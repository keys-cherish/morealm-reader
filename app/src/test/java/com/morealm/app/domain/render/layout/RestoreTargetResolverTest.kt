package com.morealm.app.domain.render.layout

import org.junit.Assert.assertEquals
import org.junit.Test

class RestoreTargetResolverTest {

    // ── 降级链 ──

    @Test
    fun `L1 anchor hit wins`() {
        val r = resolveRestoreTarget(
            chapterPosition = 36,
            progressPercent = 100,
            resolveByAnchor = { 2 },
            resolveByProgress = { error("不该走 L2") },
            chapterStart = { error("不该走 L3") },
        )
        assertEquals(2, r.target)
        assertEquals(RestoreSource.ANCHOR, r.source)
    }

    @Test
    fun `L1 miss falls to L2 progress instead of chapter start`() {
        // 此前的行为是 `findColumnAt(cp) ?: 0` —— cp 失配直接回章首，
        // 同一条记录里的 progress 从没被用过。降级链修的正是这个。
        val r = resolveRestoreTarget(
            chapterPosition = 9999,
            progressPercent = 66,
            resolveByAnchor = { null },
            resolveByProgress = { p -> pagedProgressToPageIndex(p, 3) },
            chapterStart = { 0 },
        )
        assertEquals(1, r.target)
        assertEquals(RestoreSource.PROGRESS, r.source)
    }

    @Test
    fun `L1 miss with no progress falls to L3`() {
        val r = resolveRestoreTarget(
            chapterPosition = 9999,
            progressPercent = 0,
            resolveByAnchor = { null },
            resolveByProgress = { error("prog=0 不该走 L2") },
            chapterStart = { 0 },
        )
        assertEquals(0, r.target)
        assertEquals(RestoreSource.CHAPTER_START, r.source)
    }

    @Test
    fun `cp zero with progress goes straight to L2`() {
        // 整页插图章首 cp==0 的场景：cp 无信息量，progress 才是可用的锚。
        var anchorCalled = false
        val r = resolveRestoreTarget(
            chapterPosition = 0,
            progressPercent = 50,
            resolveByAnchor = { anchorCalled = true; null },
            resolveByProgress = { p -> pagedProgressToPageIndex(p, 2) },
            chapterStart = { 0 },
        )
        assertEquals(false, anchorCalled)
        assertEquals(0, r.target) // 50% of 2 pages → 第 1 页（index 0）
        assertEquals(RestoreSource.PROGRESS, r.source)
    }

    @Test
    fun `all empty falls to chapter start`() {
        val r = resolveRestoreTarget(
            chapterPosition = 0,
            progressPercent = 0,
            resolveByAnchor = { null },
            resolveByProgress = { error("") },
            chapterStart = { 0 },
        )
        assertEquals(RestoreSource.CHAPTER_START, r.source)
    }

    // ── EPUB 翻页 progress 逆运算与上报公式配对 ──

    @Test
    fun `paged progress inverse pairs with report formula`() {
        // 上报：p = ((i + 1) / total * 100).toInt()；逆运算必须还原 i。
        // total ≤ 50 时严格恒等；更大的 total 受 1% 分辨率限制允许差 1 页
        // （见 pagedProgressToPageIndex KDoc）。
        for (total in intArrayOf(1, 2, 3, 5, 13, 50)) {
            for (i in 0 until total) {
                val reported = ((i + 1).toFloat() / total * 100f).toInt().coerceIn(0, 100)
                assertEquals(
                    "total=$total i=$i reported=$reported",
                    i,
                    pagedProgressToPageIndex(reported, total),
                )
            }
        }
        for (total in intArrayOf(67, 130, 400)) {
            // 1% 分辨率的量化步长 = total/100 页：400 页的章 1% 就是 4 页，
            // 前几页的 progress 甚至被 toInt 截成 0%。这是存储精度极限，
            // 不是逆运算缺陷 —— 锚点存储升级（DB 迁移）前 L2 只能到这个精度。
            val quantum = (total + 99) / 100
            for (i in 0 until total) {
                val reported = ((i + 1).toFloat() / total * 100f).toInt().coerceIn(0, 100)
                val back = pagedProgressToPageIndex(reported, total)
                val diff = kotlin.math.abs(back - i)
                if (diff > quantum) {
                    throw AssertionError("total=$total i=$i reported=$reported back=$back 差 $diff 页（量化步长 $quantum）")
                }
            }
        }
    }

    @Test
    fun `paged progress inverse regression cases`() {
        // 实测过的坏值：3 页章存 100%（章末），旧逆运算 p/100*total 落到越界再 clamp，
        // 语义上是 i+1。修正后：
        assertEquals(2, pagedProgressToPageIndex(100, 3))
        assertEquals(0, pagedProgressToPageIndex(50, 2)) // 卷首插图 2 页章停第 1 页
        assertEquals(1, pagedProgressToPageIndex(66, 3))
        assertEquals(0, pagedProgressToPageIndex(7, 13))
        // 防御边界
        assertEquals(0, pagedProgressToPageIndex(0, 3))
        assertEquals(0, pagedProgressToPageIndex(100, 1))
        assertEquals(0, pagedProgressToPageIndex(50, 0)) // pageCount=0 clamp 防御
    }
}
