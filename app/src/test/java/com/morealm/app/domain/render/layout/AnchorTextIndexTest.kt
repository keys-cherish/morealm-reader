package com.morealm.app.domain.render.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnchorTextIndexTest {

    /** cp 连续场景：cp == 字符下标。 */
    private fun contiguous(text: String) =
        AnchorTextIndex(text, IntArray(text.length) { it })

    /** cp 带洞场景（图片/marker 占位跳号）：cp = 字符下标 * 3 + 10。 */
    private fun gapped(text: String) =
        AnchorTextIndex(text, IntArray(text.length) { it * 3 + 10 })

    private val sample = "少女举起法杖，云与云之间的缝隙里漏下来光，照在石桥的栏杆上。远处传来钟声。"

    // ── verifyAt ──

    @Test
    fun `verifyAt 命中同位置同文本`() {
        val idx = contiguous(sample)
        assertTrue(idx.verifyAt(7, "云与云之间的缝隙里漏下来光"))
    }

    @Test
    fun `verifyAt 只比前缀 尾部截断不影响`() {
        val idx = contiguous(sample)
        // 快照带了 32 cp 跨度，但恢复端只需前缀一致
        assertTrue(idx.verifyAt(0, sample.take(30)))
    }

    @Test
    fun `verifyAt 文本失配返回 false`() {
        val idx = contiguous(sample)
        assertFalse(idx.verifyAt(0, "完全不同的一段文字内容"))
    }

    @Test
    fun `verifyAt cp 带洞时按 cp 定位而非字符下标`() {
        val idx = gapped(sample)
        // 字符下标 7 的 cp = 7*3+10 = 31
        assertTrue(idx.verifyAt(31, "云与云之间的缝隙里漏下来光"))
        assertFalse(idx.verifyAt(7, "云与云之间的缝隙里漏下来光")) // cp=7 不存在
    }

    @Test
    fun `verifyAt 空快照或越界 cp 返回 false`() {
        val idx = contiguous(sample)
        assertFalse(idx.verifyAt(0, ""))
        assertFalse(idx.verifyAt(9999, "少女举起法杖"))
    }

    // ── findNearestCp ──

    @Test
    fun `findNearestCp 唯一命中返回其 cp 区间`() {
        val idx = gapped(sample)
        val hit = idx.findNearestCp("缝隙里漏下来光", nearCp = 0)!!
        // "缝" 在字符下标 13 → cp = 49；末字符"光"下标 19 → cp = 67，end 排他 = 68
        assertEquals(49, hit.startCp)
        assertEquals(68, hit.endCpExclusive)
    }

    @Test
    fun `findNearestCp 多处命中取离 nearCp 最近`() {
        val text = "远处钟声响起。很长的间隔文字在中间隔开两次。远处钟声响起。"
        val idx = contiguous(text)
        val early = idx.findNearestCp("远处钟声响起。", nearCp = 0)!!
        val late = idx.findNearestCp("远处钟声响起。", nearCp = text.length)!!
        assertEquals(0, early.startCp)
        assertEquals(22, late.startCp)
    }

    @Test
    fun `findNearestCp 全串未命中时用前缀核心降级`() {
        val idx = contiguous(sample)
        // 前 12 字仍在章里，但尾部被改（模拟替换规则改了后文）
        val hit = idx.findNearestCp("云与云之间的缝隙里漏下来暗", nearCp = 0)
        assertEquals(7, hit!!.startCp)
    }

    @Test
    fun `findNearestCp 快照过短或不存在返回 null`() {
        val idx = contiguous(sample)
        assertNull(idx.findNearestCp("云与", nearCp = 0))
        assertNull(idx.findNearestCp("此段文字根本不在章里出现过", nearCp = 0))
    }

    // ── 与恢复链协作 ──

    @Test
    fun `恢复链 L0 快照校验通过时 cp 为 0 也直用`() {
        val idx = contiguous(sample)
        val r = resolveRestoreTarget(
            chapterPosition = 0,
            progressPercent = 66,
            snippet = sample.take(20),
            verifyAnchor = { cp -> idx.verifyAt(cp, sample.take(20)) },
            resolveBySnippet = { error("校验已通过不该搜") },
            resolveByAnchor = { cp -> cp },
            resolveByProgress = { error("不该走 progress") },
            chapterStart = { -1 },
        )
        assertEquals(0, r.target)
        assertEquals(RestoreSource.ANCHOR_VERIFIED, r.source)
    }

    @Test
    fun `恢复链 L1 校验失败走快照重定位`() {
        // 模拟 wire 版本变化：cp 整体位移 +5（旧 cp 12 处文字对不上了）
        val idx = contiguous("前面插了五个字$sample")
        val snippet = "云与云之间的缝隙里漏下来光"
        val r = resolveRestoreTarget(
            chapterPosition = 7,
            progressPercent = 66,
            snippet = snippet,
            verifyAnchor = { cp -> idx.verifyAt(cp, snippet) },
            resolveBySnippet = { idx.findNearestCp(snippet, 7)?.startCp },
            resolveByAnchor = { cp -> cp },
            resolveByProgress = { error("快照能救回不该走 progress") },
            chapterStart = { -1 },
        )
        assertEquals(14, r.target)
        assertEquals(RestoreSource.SNIPPET, r.source)
    }

    @Test
    fun `恢复链 快照彻底找不到时落回 progress`() {
        val idx = contiguous(sample)
        val snippet = "这本书根本没有这一段文字了"
        val r = resolveRestoreTarget(
            chapterPosition = 7,
            progressPercent = 66,
            snippet = snippet,
            verifyAnchor = { cp -> idx.verifyAt(cp, snippet) },
            resolveBySnippet = { idx.findNearestCp(snippet, 7)?.startCp },
            // 章文本已整个换掉：裸 cp 也视为不可信 —— 本用例里 cp 仍可命中排版，
            // 但快照失配说明内容变了，L2 裸 cp 命中的是错误文字。现实现仍会先试
            // 裸 cp（保守保留旧行为），这里让它 miss 模拟 cp 越界。
            resolveByAnchor = { null },
            resolveByProgress = { p -> p },
            chapterStart = { -1 },
        )
        assertEquals(66, r.target)
        assertEquals(RestoreSource.PROGRESS, r.source)
    }
}
