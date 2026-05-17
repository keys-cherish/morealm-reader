package com.morealm.app.ui.reader.renderer.scroll

import com.morealm.app.domain.render.scroll.ScrollChapterLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * applyScrollDelta 纯函数单测 —— 验证滚动 + 章节 swap + 边界 clamp 行为。
 *
 * 不依赖 Compose UI，直接构造 mock ScrollChapterLayout + ScrollCanvasReaderState 测试。
 */
class ScrollCanvasScrollHandlerTest {

    /** mock 一个简化 layout，仅用于测试 swap 逻辑（pages/content 不重要）。 */
    private fun layout(chapterIndex: Int, totalHeight: Float): ScrollChapterLayout =
        ScrollChapterLayout(
            chapterIndex = chapterIndex,
            title = "ch$chapterIndex",
            pages = emptyList(),
            totalHeight = totalHeight,
            viewWidth = 1080,
            styleSignature = "mock",
            totalCharCount = 1,
        )

    private fun mockState(
        cur: ScrollChapterLayout = layout(5, 1000f),
        prev: ScrollChapterLayout? = layout(4, 800f),
        next: ScrollChapterLayout? = layout(6, 1200f),
        pixelOffset: Float = 0f,
    ): ScrollCanvasReaderState = ScrollCanvasReaderState(
        initialChapterIndex = cur.chapterIndex,
        initialPixelOffset = pixelOffset,
    ).apply {
        this.currentChapter = cur
        this.prevChapter = prev
        this.nextChapter = next
    }

    @Test
    fun `正常向下滚动 pixelOffset 累加`() {
        val state = mockState(pixelOffset = 100f)
        val consumed = applyScrollDelta(state, delta = 50f, onChapterShift = {})
        assertEquals(50f, consumed, 0.01f)
        // delta=50 正（手指上推 = 内容向上滚 = pixelOffset 增加） → 100 + 50 = ?
        // applyScrollDelta 内 newOffset = pixelOffset - delta = 100 - 50 = 50
        // 但语义注释「pixelOffset 增加对应 viewport 下移」与 delta 关系是
        // `pixelOffset -= delta`，所以 delta>0 → pixelOffset 减少 → viewport 上移
        // 实际语义：delta 正 = 手指下拉 = 内容下滚 = viewport 上移 = pixelOffset 减少
        // 验证：100 - 50 = 50
        assertEquals(50f, state.pixelOffset, 0.01f)
    }

    @Test
    fun `向下滚跨过 cur 末 next 就绪 触发 swap to next`() {
        var shiftDelta = 0
        val state = mockState(pixelOffset = 950f)  // 接近 cur 末（curH=1000）
        // delta = -100 → newOffset = 950 - (-100) = 1050 > curH=1000 → swap to next
        applyScrollDelta(state, delta = -100f, onChapterShift = { shiftDelta = it })
        assertEquals("swap 触发 onChapterShift(+1)", +1, shiftDelta)
        assertEquals("currentChapterIndex 切到 6", 6, state.currentChapterIndex)
        assertEquals("currentChapter 应是原 next", 6, state.currentChapter?.chapterIndex)
        assertEquals("prevChapter 应是原 cur", 5, state.prevChapter?.chapterIndex)
        assertNull("nextChapter 清空等 VM 加载", state.nextChapter)
        // pixelOffset = newOffset(1050) - curH(1000) = 50
        assertEquals(50f, state.pixelOffset, 0.01f)
    }

    @Test
    fun `向上滚跨过 cur 顶 prev 就绪 触发 swap to prev`() {
        var shiftDelta = 0
        val state = mockState(pixelOffset = 50f)
        // delta = 100 → newOffset = 50 - 100 = -50 < 0 → swap to prev (prevH=800)
        applyScrollDelta(state, delta = 100f, onChapterShift = { shiftDelta = it })
        assertEquals("swap 触发 onChapterShift(-1)", -1, shiftDelta)
        assertEquals("currentChapterIndex 切到 4", 4, state.currentChapterIndex)
        assertEquals("currentChapter 应是原 prev", 4, state.currentChapter?.chapterIndex)
        assertEquals("nextChapter 应是原 cur", 5, state.nextChapter?.chapterIndex)
        assertNull("prevChapter 清空等 VM 加载", state.prevChapter)
        // pixelOffset = newOffset(-50) + prevH(800) = 750
        assertEquals(750f, state.pixelOffset, 0.01f)
    }

    @Test
    fun `末章 next 为 null 时 pixelOffset clamp 到 curH 不 swap`() {
        var shiftDelta = 0
        val state = mockState(pixelOffset = 950f, next = null)
        applyScrollDelta(state, delta = -200f, onChapterShift = { shiftDelta = it })
        assertEquals("末章不 swap", 0, shiftDelta)
        assertEquals("末章 currentChapterIndex 不变", 5, state.currentChapterIndex)
        assertEquals("pixelOffset clamp 到 curH=1000", 1000f, state.pixelOffset, 0.01f)
    }

    @Test
    fun `首章 prev 为 null 时 pixelOffset clamp 到 0 不 swap`() {
        var shiftDelta = 0
        val state = mockState(pixelOffset = 50f, prev = null)
        applyScrollDelta(state, delta = 200f, onChapterShift = { shiftDelta = it })
        assertEquals("首章不 swap", 0, shiftDelta)
        assertEquals("首章 currentChapterIndex 不变", 5, state.currentChapterIndex)
        assertEquals("pixelOffset clamp 到 0", 0f, state.pixelOffset, 0.01f)
    }

    @Test
    fun `currentChapter 为 null 时 直接返 delta 不做任何状态变更`() {
        val state = ScrollCanvasReaderState(initialChapterIndex = 0, initialPixelOffset = 100f)
        // 不 set currentChapter → null
        val consumed = applyScrollDelta(state, delta = 50f, onChapterShift = {})
        assertEquals(50f, consumed, 0.01f)
        assertEquals("无 cur 时 pixelOffset 不变", 100f, state.pixelOffset, 0.01f)
    }

    @Test
    fun `跨章 swap 时 prevChapter 引用从未知不出错 仅断言 swap 正确性`() {
        // prev=null + next 就绪 跨末章 swap to next
        var shiftDelta = 0
        val state = mockState(pixelOffset = 950f, prev = null)
        applyScrollDelta(state, delta = -100f, onChapterShift = { shiftDelta = it })
        assertEquals(+1, shiftDelta)
        assertEquals(6, state.currentChapterIndex)
        assertEquals("swap 后新 prev 是原 cur(5)", 5, state.prevChapter?.chapterIndex)
        assertNull(state.nextChapter)
    }

    @Test
    fun `swap to next 后 chapterCount 等不变状态保持`() {
        val state = mockState(pixelOffset = 950f).apply { chapterCount = 100 }
        applyScrollDelta(state, delta = -100f, onChapterShift = {})
        assertEquals("chapterCount 不被 swap 影响", 100, state.chapterCount)
    }

    @Test
    fun `applyScrollDelta 始终返全部 delta 表示完全消费`() {
        val state = mockState()
        val delta = 12345f
        val consumed = applyScrollDelta(state, delta = delta, onChapterShift = {})
        assertEquals(delta, consumed, 0.01f)
    }

    @Test
    fun `swap to prev 后新 next 是原 cur`() {
        val state = mockState(pixelOffset = 50f)
        applyScrollDelta(state, delta = 100f, onChapterShift = {})
        assertEquals("swap 后新 next 是原 cur(5)", 5, state.nextChapter?.chapterIndex)
        assertNull("swap 后 prev 清空", state.prevChapter)
        assertNotNull("currentChapter 非 null", state.currentChapter)
    }
}
