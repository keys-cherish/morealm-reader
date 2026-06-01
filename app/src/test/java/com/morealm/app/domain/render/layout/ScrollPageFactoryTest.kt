package com.morealm.app.domain.render.layout

import com.morealm.epub.render.*

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ScrollPageFactory] 单测 —— 不依赖 Android framework，纯逻辑覆盖。
 *
 * 覆盖维度：基础（4）+ 跨章（3）+ 边界（4）+ 4 槽语义（3）+ 跳转（3）= 17 场景。
 */
class ScrollPageFactoryTest {

    /**
     * mock dataSource —— 直接持 3 章引用 + 全书边界 flag，外部测试可读写。
     * hasPrevChapter / hasNextChapter 默认从 prev/next != null 派生，可手动 override 模拟"全书边界 vs 加载中"两态。
     */
    private class MockDataSource(
        override var currentChapter: ScrollChapterLayout? = null,
        override var prevChapter: ScrollChapterLayout? = null,
        override var nextChapter: ScrollChapterLayout? = null,
        private var hasPrevChapterOverride: Boolean? = null,
        private var hasNextChapterOverride: Boolean? = null,
    ) : ScrollChapterDataSource {
        override fun hasPrevChapter(): Boolean = hasPrevChapterOverride ?: (prevChapter != null)
        override fun hasNextChapter(): Boolean = hasNextChapterOverride ?: (nextChapter != null)

        fun setHasPrevChapter(value: Boolean) { hasPrevChapterOverride = value }
        fun setHasNextChapter(value: Boolean) { hasNextChapterOverride = value }
    }

    /** 构造单 page 的 mock chapter（lines 空，仅用于引用 / pageIndex 测试）。 */
    private fun mockChapter(chapterIndex: Int, pageCount: Int): ScrollChapterLayout {
        val pages = (0 until pageCount).map {
            ScrollPage(pageIndex = it, lines = emptyList(), height = 1800f, chapterIndex = chapterIndex)
        }
        return ScrollChapterLayout(
            chapterIndex = chapterIndex,
            title = "章$chapterIndex",
            pages = pages,
            totalHeight = pageCount * 1800f,
            viewWidth = 1080,
            styleSignature = "mock",
            totalCharCount = pageCount * 100,
        )
    }

    // ─── 基础 4 个 ───────────────────────────────────────────

    @Test
    fun `empty dataSource - has and move all false`() {
        val ds = MockDataSource()
        val factory = ScrollPageFactory(ds)

        assertFalse(factory.hasPrev())
        assertFalse(factory.hasNext())
        assertFalse(factory.hasNextPlus())
        assertFalse(factory.moveToNext())
        assertFalse(factory.moveToPrev())
        assertEquals(0, factory.pageIndex)
    }

    @Test
    fun `single chapter 5 pages - moveToNext 4 times then false`() {
        val ds = MockDataSource(currentChapter = mockChapter(0, 5))
        ds.setHasPrevChapter(false)
        ds.setHasNextChapter(false)
        val factory = ScrollPageFactory(ds)

        for (i in 1..4) {
            assertTrue("第 $i 次 moveToNext 应成功", factory.moveToNext())
            assertEquals(i, factory.pageIndex)
        }
        assertFalse("第 5 次 moveToNext 应失败（末章末页）", factory.moveToNext())
        assertEquals(4, factory.pageIndex)
    }

    @Test
    fun `single chapter at page 0 - moveToPrev returns false`() {
        val ds = MockDataSource(currentChapter = mockChapter(0, 3))
        ds.setHasPrevChapter(false)
        val factory = ScrollPageFactory(ds)

        assertFalse(factory.moveToPrev())
        assertEquals(0, factory.pageIndex)
    }

    @Test
    fun `chapter shift callback fires only on cross-chapter swap`() {
        val shifts = mutableListOf<Int>()
        val ds = MockDataSource(
            currentChapter = mockChapter(1, 3),
            prevChapter = mockChapter(0, 3),
            nextChapter = mockChapter(2, 3),
        )
        val factory = ScrollPageFactory(ds) { delta -> shifts.add(delta) }

        // 章内 2 次 next：无 shift
        factory.moveToNext(); factory.moveToNext()
        assertTrue("章内移动不应触发 shift", shifts.isEmpty())

        // 第 3 次 next：跨章
        factory.moveToNext()
        assertEquals(listOf(+1), shifts)
    }

    // ─── 跨章 3 个 ───────────────────────────────────────────

    @Test
    fun `cross chapter prev - shift -1 and pageIndex set to prev chapter last`() {
        val shifts = mutableListOf<Int>()
        val ds = MockDataSource(
            currentChapter = mockChapter(1, 3),
            prevChapter = mockChapter(0, 3),  // prev 章有 3 页
        )
        val factory = ScrollPageFactory(ds) { delta -> shifts.add(delta) }

        // 章首调 moveToPrev → 跨章 + pageIndex 设为 prev.pages.lastIndex = 2
        assertEquals(0, factory.pageIndex)
        assertTrue(factory.moveToPrev())
        assertEquals(listOf(-1), shifts)
        assertEquals(2, factory.pageIndex)  // 进入 prev 章末页
    }

    @Test
    fun `cross chapter next - shift +1 and pageIndex reset to 0`() {
        val shifts = mutableListOf<Int>()
        val ds = MockDataSource(
            currentChapter = mockChapter(1, 3),
            nextChapter = mockChapter(2, 3),
        )
        val factory = ScrollPageFactory(ds) { delta -> shifts.add(delta) }

        // 推到章末
        factory.moveToNext(); factory.moveToNext()
        assertEquals(2, factory.pageIndex)
        shifts.clear()

        // 第 3 次 → 跨章 + pageIndex = 0
        assertTrue(factory.moveToNext())
        assertEquals(listOf(+1), shifts)
        assertEquals(0, factory.pageIndex)
    }

    @Test
    fun `continuous moveToNext across 2 chapters then end - returns false at last`() {
        // 6 次 moveToNext：章 1 (3 页) → 章 2 (3 页) → 末页
        // 模拟 Host 在 shift 后同步 swap dataSource（测试中手动同步）
        val shifts = mutableListOf<Int>()
        val ds = MockDataSource(
            currentChapter = mockChapter(1, 3),
            nextChapter = mockChapter(2, 3),
        )
        ds.setHasNextChapter(true)  // 一开始章 1 之后有章 2
        val factory = ScrollPageFactory(ds) { delta ->
            shifts.add(delta)
            // Host 模拟：cur ← next, next ← null（之后没章了）
            if (delta == +1) {
                ds.prevChapter = ds.currentChapter
                ds.currentChapter = ds.nextChapter
                ds.nextChapter = null
                ds.setHasNextChapter(false)  // 章 2 是末章
            }
        }

        // 章 1: next, next, next (跨章) → 现在在章 2 page 0
        assertTrue(factory.moveToNext())  // page 1
        assertTrue(factory.moveToNext())  // page 2
        assertTrue(factory.moveToNext())  // 跨章到章 2 page 0
        assertEquals(2, ds.currentChapter!!.chapterIndex)
        assertEquals(0, factory.pageIndex)

        // 章 2: next, next → 章 2 page 2（末页）
        assertTrue(factory.moveToNext())
        assertTrue(factory.moveToNext())
        assertEquals(2, factory.pageIndex)

        // 末页再 next：false
        assertFalse(factory.moveToNext())
        assertEquals(listOf(+1), shifts)  // 仅 1 次跨章
    }

    // ─── 边界 4 个 ───────────────────────────────────────────

    @Test
    fun `last chapter last page - moveToNext false no shift`() {
        val shifts = mutableListOf<Int>()
        val ds = MockDataSource(currentChapter = mockChapter(99, 3))
        ds.setHasNextChapter(false)
        val factory = ScrollPageFactory(ds) { delta -> shifts.add(delta) }

        factory.moveToNext(); factory.moveToNext()
        assertEquals(2, factory.pageIndex)
        assertFalse(factory.moveToNext())
        assertTrue(shifts.isEmpty())
    }

    @Test
    fun `first chapter first page - moveToPrev false no shift`() {
        val shifts = mutableListOf<Int>()
        val ds = MockDataSource(currentChapter = mockChapter(0, 3))
        ds.setHasPrevChapter(false)
        val factory = ScrollPageFactory(ds) { delta -> shifts.add(delta) }

        assertFalse(factory.moveToPrev())
        assertTrue(shifts.isEmpty())
    }

    @Test
    fun `nextChapter null but hasNextChapter true - moveToNext false`() {
        // 全书有下一章但还在加载中（next 字段是 null）
        val shifts = mutableListOf<Int>()
        val ds = MockDataSource(currentChapter = mockChapter(1, 3))
        ds.setHasNextChapter(true)
        ds.nextChapter = null  // 加载中
        val factory = ScrollPageFactory(ds) { delta -> shifts.add(delta) }

        factory.moveToNext(); factory.moveToNext()
        assertEquals(2, factory.pageIndex)
        assertFalse("next 未加载就绪应返回 false 而非误跨章", factory.moveToNext())
        assertTrue(shifts.isEmpty())
        // hasNext 也应反映 prev 未就绪
        assertFalse(factory.hasNext())
    }

    @Test
    fun `prevChapter null but hasPrevChapter true - moveToPrev false`() {
        val shifts = mutableListOf<Int>()
        val ds = MockDataSource(currentChapter = mockChapter(1, 3))
        ds.setHasPrevChapter(true)
        ds.prevChapter = null  // 加载中
        val factory = ScrollPageFactory(ds) { delta -> shifts.add(delta) }

        assertFalse(factory.moveToPrev())
        assertTrue(shifts.isEmpty())
        assertFalse(factory.hasPrev())
    }

    // ─── 4 槽语义 3 个 ─────────────────────────────────────

    @Test
    fun `mid chapter - 4 slots all from same chapter`() {
        val ch1 = mockChapter(1, 5)
        val ds = MockDataSource(
            currentChapter = ch1,
            prevChapter = mockChapter(0, 3),
            nextChapter = mockChapter(2, 3),
        )
        val factory = ScrollPageFactory(ds)
        factory.moveToNext(); factory.moveToNext()  // 章 1 page 2

        assertSame(ch1.pages[2], factory.curPage)
        assertSame(ch1.pages[1], factory.prevPage)
        assertSame(ch1.pages[3], factory.nextPage)
        assertSame(ch1.pages[4], factory.nextPlusPage)
    }

    @Test
    fun `second to last page - nextPlus crosses to next chapter first page`() {
        val ch1 = mockChapter(1, 3)
        val ch2 = mockChapter(2, 3)
        val ds = MockDataSource(currentChapter = ch1, nextChapter = ch2)
        val factory = ScrollPageFactory(ds)
        factory.moveToNext()  // page 1 = 倒数第 2

        assertSame(ch1.pages[1], factory.curPage)
        assertSame(ch1.pages[2], factory.nextPage)
        assertSame("章倒数第 2 page → nextPlus 跨章取 next.pages[0]", ch2.pages[0], factory.nextPlusPage)
    }

    @Test
    fun `last page - nextPage from next chapter and nextPlus from next chapter index 1`() {
        val ch1 = mockChapter(1, 3)
        val ch2 = mockChapter(2, 3)
        val ds = MockDataSource(currentChapter = ch1, nextChapter = ch2)
        val factory = ScrollPageFactory(ds)
        factory.moveToNext(); factory.moveToNext()  // 章 1 末页

        assertSame(ch1.pages[2], factory.curPage)
        assertSame("末页 → nextPage 跨章取 next.pages[0]", ch2.pages[0], factory.nextPage)
        assertSame("末页 → nextPlus 跨章取 next.pages[1]", ch2.pages[1], factory.nextPlusPage)
    }

    // ─── 跳转 3 个 ───────────────────────────────────────────

    @Test
    fun `moveToFirstPageOfChapter resets pageIndex to 0`() {
        val ds = MockDataSource(currentChapter = mockChapter(1, 5))
        val factory = ScrollPageFactory(ds)
        factory.moveToNext(); factory.moveToNext(); factory.moveToNext()
        assertEquals(3, factory.pageIndex)

        factory.moveToFirstPageOfChapter()
        assertEquals(0, factory.pageIndex)
    }

    @Test
    fun `moveToLastPageOfChapter sets pageIndex to lastIndex`() {
        val ds = MockDataSource(currentChapter = mockChapter(1, 5))
        val factory = ScrollPageFactory(ds)

        factory.moveToLastPageOfChapter()
        assertEquals(4, factory.pageIndex)
    }

    @Test
    fun `moveToPage with out-of-range index is coerced`() {
        val ds = MockDataSource(currentChapter = mockChapter(1, 5))
        val factory = ScrollPageFactory(ds)

        factory.moveToPage(99)
        assertEquals("越界 → lastIndex", 4, factory.pageIndex)

        factory.moveToPage(-3)
        assertEquals("负值 → 0", 0, factory.pageIndex)

        factory.moveToPage(2)
        assertEquals("正常值", 2, factory.pageIndex)
    }

    // ─── 额外：hasPrev / hasNext 边界 ─────────────────────────────────

    @Test
    fun `hasPrev and hasNext correctly reflect across-chapter readiness`() {
        // 章 1 page 0：hasPrev 取决于 prev 加载完没；hasNext 章内自然有
        val ds = MockDataSource(currentChapter = mockChapter(1, 3))
        ds.setHasPrevChapter(true)
        ds.setHasNextChapter(true)
        val factory = ScrollPageFactory(ds)

        // prev / next 都未加载
        assertFalse("prev 未加载 → hasPrev false", factory.hasPrev())
        assertTrue("章内 next 自然存在 → hasNext true", factory.hasNext())

        // prev 加载完成
        ds.prevChapter = mockChapter(0, 3)
        assertTrue("prev 加载完 → hasPrev true", factory.hasPrev())
    }

    @Test
    fun `curPage returns EMPTY when dataSource currentChapter is null`() {
        val ds = MockDataSource(currentChapter = null)
        val factory = ScrollPageFactory(ds)

        assertNotNull(factory.curPage)
        assertEquals(-1, factory.curPage.chapterIndex)  // EMPTY 标识
        assertTrue(factory.curPage.lines.isEmpty())
    }

    // ─── Race Condition 场景 A：nextChapter 从 null 变 non-null（异步加载完成）─────────

    @Test
    fun `race A - nextChapter null then loaded - moveToNext recovers correctly`() {
        // 场景：用户翻到章末，next 还在加载（null）→ moveToNext 返回 false
        // 几百毫秒后 next 加载完成 → 同一 factory 实例再 moveToNext 应成功跨章
        // 验证点：factory 无内部状态缓存「上次 false」，每次调用都基于 dataSource 当前真值判定
        val shifts = mutableListOf<Int>()
        val ds = MockDataSource(currentChapter = mockChapter(1, 3))
        ds.setHasNextChapter(true)
        ds.nextChapter = null  // 加载中
        val factory = ScrollPageFactory(ds) { delta -> shifts.add(delta) }

        // 推到章末
        factory.moveToNext(); factory.moveToNext()
        assertEquals(2, factory.pageIndex)

        // 章末 + next null → false
        assertFalse("加载中应 false", factory.moveToNext())
        assertEquals("pageIndex 不变保持在章末", 2, factory.pageIndex)
        assertTrue("无 chapterShift 触发", shifts.isEmpty())

        // 异步加载到达：Host 模拟填回 nextChapter
        ds.nextChapter = mockChapter(2, 3)

        // 再次 moveToNext 应跨章成功
        assertTrue("数据到达后跨章成功", factory.moveToNext())
        assertEquals(listOf(+1), shifts)
        assertEquals("跨章后 pageIndex reset 为 0", 0, factory.pageIndex)
    }

    @Test
    fun `race A - prevChapter null then loaded - moveToPrev recovers correctly`() {
        // 对称场景：章首 + prev null → false；prev 加载到达后再 moveToPrev 成功
        val shifts = mutableListOf<Int>()
        val ds = MockDataSource(currentChapter = mockChapter(1, 3))
        ds.setHasPrevChapter(true)
        ds.prevChapter = null
        val factory = ScrollPageFactory(ds) { delta -> shifts.add(delta) }

        // 章首 + prev null → false
        assertFalse(factory.moveToPrev())
        assertTrue(shifts.isEmpty())

        // 异步加载到达
        ds.prevChapter = mockChapter(0, 5)

        assertTrue(factory.moveToPrev())
        assertEquals(listOf(-1), shifts)
        assertEquals("跨章后 pageIndex 设为 prev 末页索引 4", 4, factory.pageIndex)
    }

    // ─── Race Condition 场景 B：快速连续 moveToNext 跨 2 章（单页章 + 高频翻页）───────

    @Test
    fun `race B - 3 rapid moveToNext across 2 single-page chapters - state stays accurate`() {
        // 场景：章 0/1/2 各只 1 页。从章 0 page 0（即章末）连续 3 次 moveToNext。
        // 验证点：
        //   1. 每次 swap 都正确触发 chapterShift +1
        //   2. 最终停留 chapterIndex=2, pageIndex=0（章 2 是末章无法再前进）
        //   3. 中间过程 dataSource state 与 factory pageIndex 始终一致（无并发异常）
        val shifts = mutableListOf<Int>()
        val ds = MockDataSource(
            currentChapter = mockChapter(0, 1),
            nextChapter = mockChapter(1, 1),
        )
        ds.setHasNextChapter(true)
        val factory = ScrollPageFactory(ds) { delta ->
            shifts.add(delta)
            // Host 模拟同步 swap：cur ← next，next 异步加载（暂存 null 模拟 race）
            if (delta == +1) {
                ds.prevChapter = ds.currentChapter
                ds.currentChapter = ds.nextChapter
                ds.nextChapter = null  // 模拟异步加载未到达
            }
        }

        // 起点：章 0 page 0（已是末页）
        assertEquals(0, factory.pageIndex)
        assertEquals(0, ds.currentChapter!!.chapterIndex)

        // 第 1 次：跨章到章 1 page 0（next=ch1 已就绪）
        assertTrue("第 1 次跨章应成功", factory.moveToNext())
        assertEquals(1, ds.currentChapter!!.chapterIndex)
        assertEquals(0, factory.pageIndex)

        // 模拟章 2 异步加载到达
        ds.nextChapter = mockChapter(2, 1)
        ds.setHasNextChapter(true)

        // 第 2 次：跨章到章 2 page 0
        assertTrue("第 2 次跨章应成功", factory.moveToNext())
        assertEquals(2, ds.currentChapter!!.chapterIndex)
        assertEquals(0, factory.pageIndex)

        // 章 2 是末章，next 永不再加载
        ds.setHasNextChapter(false)

        // 第 3 次：末章末页 → false，无 shift
        assertFalse("第 3 次应 false（末章末页）", factory.moveToNext())
        assertEquals("仍停留章 2", 2, ds.currentChapter!!.chapterIndex)
        assertEquals("pageIndex 不变", 0, factory.pageIndex)

        // 最终验证：刚好 2 次 chapterShift +1（不多不少）
        assertEquals(listOf(+1, +1), shifts)
    }

    @Test
    fun `race B variant - 3 rapid moveToNext but next chapter never loads - stops at intermediate`() {
        // 变种：章 0/1 各 1 页。章 2 永不加载（网络断）。从章 0 连续 3 次 moveToNext。
        // 验证点：第 1 次成功跨到章 1；第 2/3 次因 next=null 都 false；不应有 phantom shift。
        val shifts = mutableListOf<Int>()
        val ds = MockDataSource(
            currentChapter = mockChapter(0, 1),
            nextChapter = mockChapter(1, 1),
        )
        ds.setHasNextChapter(true)
        val factory = ScrollPageFactory(ds) { delta ->
            shifts.add(delta)
            if (delta == +1) {
                ds.prevChapter = ds.currentChapter
                ds.currentChapter = ds.nextChapter
                ds.nextChapter = null  // 网络断 → 永不到达
            }
        }

        // 第 1 次成功
        assertTrue(factory.moveToNext())
        assertEquals(1, ds.currentChapter!!.chapterIndex)

        // 第 2 次：章 1 末页 + next=null + hasNextChapter true (still) → false
        // hasNextChapter 仍为 true 因为我们没 setHasNextChapter(false)，模拟"还有章但加载中"
        assertFalse("next 加载中应 false", factory.moveToNext())
        assertEquals("停在章 1", 1, ds.currentChapter!!.chapterIndex)

        // 第 3 次：同上
        assertFalse(factory.moveToNext())
        assertEquals(1, ds.currentChapter!!.chapterIndex)

        // 验证：仅 1 次 chapterShift（没有 phantom shift）
        assertEquals(listOf(+1), shifts)
    }
}
