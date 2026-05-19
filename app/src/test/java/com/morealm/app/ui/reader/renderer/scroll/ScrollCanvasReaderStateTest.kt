package com.morealm.app.ui.reader.renderer.scroll

import com.morealm.app.domain.render.scroll.ScrollChapterLayout
import com.morealm.app.domain.render.scroll.ScrollPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ScrollCanvasReaderState] 单测 —— page+全 重构后的 DataSource + swap + 单向真值语义。
 *
 * mutableState delegate 在非 Composition 上下文可读写（不依赖 Compose runtime）。
 */
class ScrollCanvasReaderStateTest {

    private fun mockChapter(idx: Int): ScrollChapterLayout = ScrollChapterLayout(
        chapterIndex = idx,
        title = "章$idx",
        pages = listOf(ScrollPage(0, emptyList(), 1800f, idx)),
        totalHeight = 1800f,
        viewWidth = 1080,
        styleSignature = "mock",
        totalCharCount = 100,
    )

    @Test
    fun `initial state - defaults`() {
        val state = ScrollCanvasReaderState()
        assertEquals(0, state.currentChapterIndex)
        assertEquals(0f, state.pageOffset, 0.0f)
        assertNull(state.currentChapter)
        assertNull(state.prevChapter)
        assertNull(state.nextChapter)
        assertEquals(0, state.chapterCount)
    }

    @Test
    fun `initial state - custom values`() {
        val state = ScrollCanvasReaderState(initialChapterIndex = 5, initialPageOffset = -120f)
        assertEquals(5, state.currentChapterIndex)
        assertEquals(-120f, state.pageOffset, 0.0f)
    }

    // ─── hasPrev / hasNext 边界 ────────────────────────────────

    @Test
    fun `hasPrevChapter false at index 0`() {
        val state = ScrollCanvasReaderState(initialChapterIndex = 0)
        state.chapterCount = 10
        assertFalse(state.hasPrevChapter())
    }

    @Test
    fun `hasPrevChapter true at index gt 0`() {
        val state = ScrollCanvasReaderState(initialChapterIndex = 3)
        state.chapterCount = 10
        assertTrue(state.hasPrevChapter())
    }

    @Test
    fun `hasNextChapter false at last index`() {
        val state = ScrollCanvasReaderState(initialChapterIndex = 9)
        state.chapterCount = 10
        assertFalse(state.hasNextChapter())
    }

    @Test
    fun `hasNextChapter false when chapterCount 0`() {
        val state = ScrollCanvasReaderState(initialChapterIndex = 0)
        state.chapterCount = 0
        assertFalse(state.hasNextChapter())
    }

    // ─── swap 语义 ────────────────────────────────────────────

    @Test
    fun `swapToNext shifts references and increments index`() {
        val ch1 = mockChapter(1)
        val ch2 = mockChapter(2)
        val state = ScrollCanvasReaderState(initialChapterIndex = 1)
        state.currentChapter = ch1
        state.nextChapter = ch2

        state.swapToNext()

        assertEquals(2, state.currentChapterIndex)
        assertSame(ch1, state.prevChapter)
        assertSame(ch2, state.currentChapter)
        assertNull("nextChapter 应被清空，等 Host 异步加载新远端章", state.nextChapter)
    }

    @Test
    fun `swapToPrev shifts references and decrements index`() {
        val ch0 = mockChapter(0)
        val ch1 = mockChapter(1)
        val state = ScrollCanvasReaderState(initialChapterIndex = 1)
        state.prevChapter = ch0
        state.currentChapter = ch1

        state.swapToPrev()

        assertEquals(0, state.currentChapterIndex)
        assertSame(ch1, state.nextChapter)
        assertSame(ch0, state.currentChapter)
        assertNull("prevChapter 应被清空，等 Host 异步加载新远端章", state.prevChapter)
    }

    @Test
    fun `swapToNext at last with null nextChapter still decrements - caller must guard`() {
        // 注意：swapToNext 不防御 next=null 边界，调用方 (ScrollPageFactory) 必须先 hasNext() 守门
        // 本测试验证 swap 操作本身的副作用语义（哪怕被误调），不验证业务合法性
        val state = ScrollCanvasReaderState(initialChapterIndex = 9)
        state.currentChapter = mockChapter(9)
        state.nextChapter = null

        state.swapToNext()

        assertEquals("swap 仍执行（caller 责任守门）", 10, state.currentChapterIndex)
        assertNull("currentChapter 被 null 覆盖", state.currentChapter)
    }

    // ─── 单向真值 ────────────────────────────────────────────

    @Test
    fun `setExternalChapterIndex resets all state`() {
        val state = ScrollCanvasReaderState(initialChapterIndex = 3)
        state.currentChapter = mockChapter(3)
        state.prevChapter = mockChapter(2)
        state.nextChapter = mockChapter(4)
        state.pageOffset = -800f

        state.setExternalChapterIndex(15)

        assertEquals(15, state.currentChapterIndex)
        assertEquals("pageOffset 归零", 0f, state.pageOffset, 0.0f)
        assertNull(state.currentChapter)
        assertNull(state.prevChapter)
        assertNull(state.nextChapter)
    }

    @Test
    fun `currentChapterIndex private set - only swap and setExternal can mutate`() {
        // Phase 5 起 setter 收紧 private：外部无法直接赋值，必须走 swap/setExternal
        val state = ScrollCanvasReaderState()
        // 以下行如果取消注释应编译失败（private set 约束）：
        // state.currentChapterIndex = 99  // ← Cannot assign: 'currentChapterIndex' is invisible
        assertEquals(0, state.currentChapterIndex)
        // 改通过 setExternalChapterIndex 修改
        state.setExternalChapterIndex(99)
        assertEquals(99, state.currentChapterIndex)
    }

    // ─── DataSource 接口契约（与 ScrollPageFactory 交互） ──────────────

    @Test
    fun `state acts as ScrollChapterDataSource - factory can consume it`() {
        val ds: com.morealm.app.domain.render.scroll.ScrollChapterDataSource = ScrollCanvasReaderState()
        // 编译通过即证明实现接口；运行时调用接口方法验证基础语义
        assertNull(ds.currentChapter)
        assertFalse(ds.hasPrevChapter())
        assertFalse(ds.hasNextChapter())
    }

    @Test
    fun `combined swapToNext flow with factory-like usage`() {
        // 模拟 Factory 的 chapterShiftCallback 调用流程：
        // 1. Factory 内 hasNext() 守门 + pageIndex=0
        // 2. callback 调 state.swapToNext()
        // 3. Host 异步 load 新 next 章 → 填回 state.nextChapter
        val state = ScrollCanvasReaderState(initialChapterIndex = 1)
        state.chapterCount = 5
        state.currentChapter = mockChapter(1)
        state.nextChapter = mockChapter(2)

        // 模拟 Factory 调用 swap
        state.swapToNext()

        // Host 异步加载 ch3
        state.nextChapter = mockChapter(3)

        // 现在 state 应是：cur=ch2, prev=ch1, next=ch3, idx=2
        assertEquals(2, state.currentChapterIndex)
        assertEquals(1, state.prevChapter?.chapterIndex)
        assertEquals(2, state.currentChapter?.chapterIndex)
        assertEquals(3, state.nextChapter?.chapterIndex)
    }
}
