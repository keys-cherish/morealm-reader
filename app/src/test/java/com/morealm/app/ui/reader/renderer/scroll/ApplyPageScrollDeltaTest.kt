package com.morealm.app.ui.reader.renderer.scroll

import com.morealm.app.domain.render.layout.ScrollChapterDataSource
import com.morealm.app.domain.render.layout.ScrollChapterLayout
import com.morealm.app.domain.render.layout.ScrollPage
import com.morealm.app.domain.render.layout.ScrollPageFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [applyPageScrollDelta] 单测 —— page-level 滚动 delta 应用算法。
 *
 * 关键约束（用户决策 2026-05-19）：
 * - 单次调用最多跨 1 page
 * - 范围 [0, curPage.height] 正值
 * - 跨章由 factory 内部 chapterShiftCallback 触发 state.swapToNext/Prev
 *
 * 覆盖：章内（3）+ 跨 page 章内（2）+ 跨 page 跨章（2）+ 单次跨 1 限制（2）+
 *       边界 clamp（4）+ factory swap 后边界（3）= 16 场景
 */
class ApplyPageScrollDeltaTest {

    /** mock dataSource，与 ScrollPageFactoryTest 同款 */
    private class MockDataSource(
        override var currentChapter: ScrollChapterLayout? = null,
        override var prevChapter: ScrollChapterLayout? = null,
        override var nextChapter: ScrollChapterLayout? = null,
        private var hasPrev: Boolean? = null,
        private var hasNext: Boolean? = null,
    ) : ScrollChapterDataSource {
        override fun hasPrevChapter(): Boolean = hasPrev ?: (prevChapter != null)
        override fun hasNextChapter(): Boolean = hasNext ?: (nextChapter != null)
        fun setHasPrev(v: Boolean) { hasPrev = v }
        fun setHasNext(v: Boolean) { hasNext = v }
    }

    /** 构造章节（每页等高，默认 1800px / 5 页）。 */
    private fun mockChapter(idx: Int, pageCount: Int = 5, pageH: Float = 1800f): ScrollChapterLayout {
        val pages = (0 until pageCount).map {
            ScrollPage(pageIndex = it, lines = emptyList(), height = pageH, chapterIndex = idx)
        }
        return ScrollChapterLayout(
            chapterIndex = idx,
            title = "章$idx",
            pages = pages,
            totalHeight = pageCount * pageH,
            viewWidth = 1080,
            styleSignature = "mock",
            totalCharCount = pageCount * 100,
        )
    }

    /** 构造 state + factory 一体（chapterShiftCallback 同步调 state.swap）。 */
    private fun setup(
        currentIdx: Int = 1,
        hasPrev: Boolean = true,
        hasNext: Boolean = true,
        cur: ScrollChapterLayout? = mockChapter(1),
        prev: ScrollChapterLayout? = mockChapter(0),
        next: ScrollChapterLayout? = mockChapter(2),
        chapterCount: Int = 10,
    ): Triple<ScrollCanvasReaderState, ScrollPageFactory, MutableList<Int>> {
        val state = ScrollCanvasReaderState(initialChapterIndex = currentIdx)
        state.chapterCount = chapterCount
        state.currentChapter = cur
        state.prevChapter = prev
        state.nextChapter = next

        val shifts = mutableListOf<Int>()
        val factory = ScrollPageFactory(state) { delta ->
            shifts.add(delta)
            if (delta == +1) state.swapToNext() else state.swapToPrev()
        }
        return Triple(state, factory, shifts)
    }

    // ─── 章内 page 中段（无 swap）────────────────────────────────

    @Test
    fun `intra-page scroll - small positive delta moves offset down`() {
        val (state, factory, shifts) = setup()
        // delta=+100 → pageOffset -= 100 → pageOffset = -100；但 [0, 1800] clamp 不到这里
        // 不对，delta > 0 (手指向上推) → pageOffset -= delta → pageOffset 减小，进入越界
        // 重审：初始 pageOffset = 0，delta=+100 → pageOffset = -100 → 触发 moveToPrev
        // 第一个测试应该用初始 pageOffset > 0，或 delta < 0
        state.pageOffset = 500f
        applyPageScrollDelta(state, factory, delta = +200f)
        assertEquals(300f, state.pageOffset, 0.01f)
        assertTrue("章内无 shift", shifts.isEmpty())
    }

    @Test
    fun `intra-page scroll - small negative delta moves offset down further`() {
        val (state, factory, shifts) = setup()
        state.pageOffset = 500f
        applyPageScrollDelta(state, factory, delta = -200f)
        assertEquals(700f, state.pageOffset, 0.01f)
        assertTrue(shifts.isEmpty())
    }

    @Test
    fun `zero delta - no change`() {
        val (state, factory, shifts) = setup()
        state.pageOffset = 800f
        applyPageScrollDelta(state, factory, delta = 0f)
        assertEquals(800f, state.pageOffset, 0.01f)
        assertTrue(shifts.isEmpty())
    }

    // ─── 跨 page 章内（无 chapterShift）─────────────────────────────────

    @Test
    fun `cross page within chapter - moveToNext triggered no shift`() {
        // 章 1 有 5 页；当前 pageIndex=0，pageOffset=1700（接近 page 末）
        // delta=-200（手指向下推，看后面）→ pageOffset=1900 ≥ 1800 → moveToNext 章内 page 1
        // pageOffset = 1900 - 1800 = 100
        val (state, factory, shifts) = setup()
        state.pageOffset = 1700f
        applyPageScrollDelta(state, factory, delta = -200f)
        assertEquals(1, factory.pageIndex)
        assertEquals(100f, state.pageOffset, 0.01f)
        assertTrue("章内跨 page 无 chapterShift", shifts.isEmpty())
        assertEquals("不跨章 currentChapterIndex 不变", 1, state.currentChapterIndex)
    }

    @Test
    fun `cross page backwards within chapter - moveToPrev no shift`() {
        val (state, factory, shifts) = setup()
        factory.moveToNext()  // 章内到 page 1
        state.pageOffset = 100f
        applyPageScrollDelta(state, factory, delta = +200f)
        // pageOffset -= 200 → -100 → moveToPrev → 章内 page 0 + pageOffset += 1800 = 1700
        assertEquals(0, factory.pageIndex)
        assertEquals(1700f, state.pageOffset, 0.01f)
        assertTrue(shifts.isEmpty())
    }

    // ─── 跨 page 跨章（factory 触发 chapterShift + state.swap）──────────

    @Test
    fun `cross chapter forward - factory triggers shift and state swaps`() {
        // 章 1 末页（pageIndex=4），pageOffset=1700；delta=-200 → 跨章到章 2 page 0
        val (state, factory, shifts) = setup()
        factory.moveToLastPageOfChapter()  // pageIndex=4
        state.pageOffset = 1700f

        applyPageScrollDelta(state, factory, delta = -200f)

        assertEquals(listOf(+1), shifts)
        assertEquals("currentChapterIndex 由 swap 自增", 2, state.currentChapterIndex)
        assertEquals("跨章后 factory pageIndex reset 0", 0, factory.pageIndex)
        assertEquals("pageOffset = 1900 - 1800 = 100", 100f, state.pageOffset, 0.01f)
        assertEquals("旧 cur 变 prev", 1, state.prevChapter?.chapterIndex)
        assertEquals("旧 next 变 cur", 2, state.currentChapter?.chapterIndex)
    }

    @Test
    fun `cross chapter backward - factory triggers shift -1 and state swaps`() {
        // 章 1 page 0，pageOffset=100；delta=+200 → 跨章到章 0 末页
        val (state, factory, shifts) = setup()
        state.pageOffset = 100f

        applyPageScrollDelta(state, factory, delta = +200f)

        assertEquals(listOf(-1), shifts)
        assertEquals(0, state.currentChapterIndex)
        assertEquals("跨章后 pageIndex 设为新章末页 4", 4, factory.pageIndex)
        // pageOffset = -100 + newPageH (1800) = 1700
        assertEquals(1700f, state.pageOffset, 0.01f)
        assertEquals(0, state.currentChapter?.chapterIndex)
        assertEquals(1, state.nextChapter?.chapterIndex)
    }

    // ─── 单次只跨 1 page 限制（fling 单帧大 delta）─────────────────

    @Test
    fun `single frame huge delta - only crosses 1 page then clamps`() {
        // 章 1 page 0，pageOffset=0；delta=-5000（fling 单帧极大）
        // 预期：pageOffset = 5000 ≥ 1800 → moveToNext → pageOffset = 3200
        // 仍 ≥ 新 pageH 1800 → clamp 到 1800（page 末，等下帧再跨）
        val (state, factory, shifts) = setup()

        applyPageScrollDelta(state, factory, delta = -5000f)

        assertEquals("单次只跨 1 page", 1, factory.pageIndex)
        assertEquals("剩余 delta 被 clamp 到新 page 末", 1800f, state.pageOffset, 0.01f)
        assertTrue("章内跨 1 page 无 chapterShift", shifts.isEmpty())
    }

    @Test
    fun `single frame huge negative delta - cross 1 page backward then clamps`() {
        val (state, factory, shifts) = setup()
        factory.moveToNext()  // page 1
        state.pageOffset = 100f

        applyPageScrollDelta(state, factory, delta = +5000f)

        assertEquals("单次只跨 1 page backward", 0, factory.pageIndex)
        assertEquals("剩余正 delta 被 clamp 到 0", 0f, state.pageOffset, 0.01f)
        assertTrue(shifts.isEmpty())
    }

    // ─── 边界 clamp ─────────────────────────────────────────────

    @Test
    fun `last chapter last page - down delta clamps to pageH`() {
        // 章 9 是末章（chapterCount=10），page 末，delta=-500 → next 没了 → clamp 到 pageH
        val (state, factory, shifts) = setup(
            currentIdx = 9,
            cur = mockChapter(9),
            prev = mockChapter(8),
            next = null,
            chapterCount = 10,
        )
        factory.moveToLastPageOfChapter()
        state.pageOffset = 1700f

        applyPageScrollDelta(state, factory, delta = -500f)

        assertEquals("末章末页向下 clamp 到 pageH", 1800f, state.pageOffset, 0.01f)
        assertEquals(9, state.currentChapterIndex)
        assertTrue(shifts.isEmpty())
    }

    @Test
    fun `first chapter first page - up delta clamps to 0`() {
        val (state, factory, shifts) = setup(
            currentIdx = 0,
            cur = mockChapter(0),
            prev = null,
            next = mockChapter(1),
            chapterCount = 10,
        )
        state.pageOffset = 100f

        applyPageScrollDelta(state, factory, delta = +500f)

        assertEquals("首章首页向上 clamp 到 0", 0f, state.pageOffset, 0.01f)
        assertEquals(0, state.currentChapterIndex)
        assertTrue(shifts.isEmpty())
    }

    @Test
    fun `next chapter loading (null) at chapter end - soft clamps with buffer`() {
        // 章 1 末页 + next=null + hasNextChapter true（章 2 加载中）
        // **Soft fix B**：hasNext=true 且 next loading 时允许 pageOffset 越界
        // BUFFER_NEXT_PX (200f)，让 user 感知等待而非卡死。next ready 后由 ReaderHost
        // LaunchedEffect 自动 commit moveToNext。
        val (state, factory, shifts) = setup(
            currentIdx = 1,
            cur = mockChapter(1),
            prev = mockChapter(0),
            next = null,
        )
        (state as ScrollChapterDataSource).let {
            // 让 hasNextChapter() 返回 true（基于 currentChapterIndex 1 < 9）
            // state 实现使用 currentChapterIndex < chapterCount - 1
        }
        factory.moveToLastPageOfChapter()
        state.pageOffset = 1700f

        applyPageScrollDelta(state, factory, delta = -500f)

        // pageOffset 1700 - (-500) = 2200, 越界 (2200 > pageH 1800)，next loading 进 soft
        // buffer 允许越界 200，coerce 到 max = pageH + 200 = 2000
        assertEquals("next loading → soft clamp 到 pageH + BUFFER_NEXT_PX", 2000f, state.pageOffset, 0.01f)
        assertTrue("无 chapterShift（加载中不强行跨，由 ReaderHost auto-snap 兜底）", shifts.isEmpty())
    }

    @Test
    fun `prev chapter loading (null) at chapter start - clamps no shift`() {
        val (state, factory, shifts) = setup(
            currentIdx = 1,
            cur = mockChapter(1),
            prev = null,
            next = mockChapter(2),
        )
        state.pageOffset = 100f

        applyPageScrollDelta(state, factory, delta = +500f)

        assertEquals("prev 未加载 → clamp 到 0", 0f, state.pageOffset, 0.01f)
        assertTrue(shifts.isEmpty())
    }

    // ─── factory swap 后边界（关键边界 — 新章 page 高度可能与旧不同）─────────────

    @Test
    fun `factory swap to next with different page height - offset computed correctly`() {
        // 章 1 page=1800px，章 2 page=2400px（高 page）。跨章后新 page 高度变化
        val (state, factory, shifts) = setup(
            cur = mockChapter(1, pageH = 1800f),
            prev = mockChapter(0, pageH = 1800f),
            next = mockChapter(2, pageH = 2400f),
        )
        factory.moveToLastPageOfChapter()
        state.pageOffset = 1700f

        applyPageScrollDelta(state, factory, delta = -300f)
        // pageOffset = 1700 + 300 = 2000 ≥ 1800 → moveToNext → pageOffset = 200
        // 新 page (章 2 page 0) height 是 2400，pageOffset 200 < 2400 不再 clamp
        assertEquals(2, state.currentChapterIndex)
        assertEquals(200f, state.pageOffset, 0.01f)
        assertEquals(listOf(+1), shifts)
    }

    @Test
    fun `factory swap to prev with different page height - offset uses new page height`() {
        // 章 0 page=2000px，章 1 page=1800px。从章 1 page 0 向上 → 跨到章 0 末页
        val (state, factory, shifts) = setup(
            cur = mockChapter(1, pageH = 1800f),
            prev = mockChapter(0, pageH = 2000f),
            next = mockChapter(2, pageH = 1800f),
        )
        state.pageOffset = 100f

        applyPageScrollDelta(state, factory, delta = +300f)
        // pageOffset = 100 - 300 = -200 → moveToPrev → pageOffset += 新 pageH (2000) = 1800
        assertEquals(0, state.currentChapterIndex)
        assertEquals("使用新 page (章 0 末页) height 2000 计算偏移", 1800f, state.pageOffset, 0.01f)
        assertEquals(listOf(-1), shifts)
        assertEquals("跨章后 pageIndex 设为章 0 末页 index 4", 4, factory.pageIndex)
    }

    @Test
    fun `factory swap then immediate large delta - second cross requires new frame`() {
        // 章 1 末页 + 一帧 delta=-3700 (跨 2 page 量级)
        // 期望：跨 1 page (到章 2 page 0) + 剩余 clamp 到新 pageH（章 2 page 0 末）
        // 下一帧 delta 继续才会再次跨
        val (state, factory, shifts) = setup()
        factory.moveToLastPageOfChapter()
        state.pageOffset = 0f

        applyPageScrollDelta(state, factory, delta = -3700f)
        // pageOffset = 3700 ≥ 1800 → moveToNext 跨章 + pageOffset = 1900
        // 1900 ≥ 1800 → clamp 到 1800
        assertEquals(2, state.currentChapterIndex)
        assertEquals(0, factory.pageIndex)
        assertEquals("clamp 到新 page 末，下帧再跨", 1800f, state.pageOffset, 0.01f)
        assertEquals(listOf(+1), shifts)

        // 下一帧：delta=-100，继续推过 → 跨到章 2 page 1（章内）
        applyPageScrollDelta(state, factory, delta = -100f)
        // pageOffset = 1900 ≥ 1800 → moveToNext 章内 → pageOffset = 100
        assertEquals(2, state.currentChapterIndex)
        assertEquals(1, factory.pageIndex)
        assertEquals(100f, state.pageOffset, 0.01f)
        assertEquals("下帧不再跨章（章 2 内 page 1）", listOf(+1), shifts)
    }
}
