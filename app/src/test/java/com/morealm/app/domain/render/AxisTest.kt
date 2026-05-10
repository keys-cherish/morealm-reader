package com.morealm.app.domain.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Axis 抽象正确性单测——保证两个映射器把逻辑坐标→屏幕坐标的方向语义稳定，
 * 以及 [LogicalRect] 的不变量校验生效。Phase 3 在这之上铺新算法时，能把
 * 「修改 mapper 是否破坏方向语义」的回归直接挡在单测层。
 */
class AxisTest {

    private val W = 1080f
    private val H = 2400f

    // ── HorizontalAxisMapper ────────────────────────────────────────────

    @Test
    fun `horizontal mapper maps inline to X and block to Y`() {
        val mapper = HorizontalAxisMapper(W, H, paddingLeft = 24f, paddingTop = 48f)
        val (x, y) = mapper.toScreenPoint(inline = 100f, block = 200f)
        assertEquals(124f, x)
        assertEquals(248f, y)
    }

    @Test
    fun `horizontal mapper toScreenRect preserves order`() {
        val mapper = HorizontalAxisMapper(W, H, paddingLeft = 0f, paddingTop = 0f)
        val rect = mapper.toScreenRect(LogicalRect(10f, 50f, 20f, 100f))
        assertEquals(10f, rect.left)
        assertEquals(50f, rect.right)
        assertEquals(20f, rect.top)
        assertEquals(100f, rect.bottom)
        assertTrue("left ≤ right", rect.left <= rect.right)
        assertTrue("top ≤ bottom", rect.top <= rect.bottom)
    }

    // ── VerticalRlAxisMapper ───────────────────────────────────────────
    //
    // 核心：block=0 对应最右侧 X = viewportWidth - paddingRight；block 增大 X 减小。

    @Test
    fun `vertical_rl mapper maps inline to Y and block to inverted X`() {
        val mapper = VerticalRlAxisMapper(W, H, paddingRight = 24f, paddingTop = 48f)
        val (x, y) = mapper.toScreenPoint(inline = 100f, block = 200f)
        // X 应该 = W - paddingRight - block = 1080 - 24 - 200 = 856
        assertEquals(856f, x)
        // Y 应该 = paddingTop + inline = 48 + 100 = 148
        assertEquals(148f, y)
    }

    @Test
    fun `vertical_rl block=0 lands on rightmost edge`() {
        val mapper = VerticalRlAxisMapper(W, H, paddingRight = 0f, paddingTop = 0f)
        val (x, _) = mapper.toScreenPoint(inline = 0f, block = 0f)
        assertEquals(W, x)
    }

    @Test
    fun `vertical_rl toScreenRect normalizes left right ordering`() {
        // blockStart=10, blockEnd=50 ⇒ X 物理上从「右近」向「左远」延伸，
        // 但返回的 ScreenRect 必须 left ≤ right
        val mapper = VerticalRlAxisMapper(W, H, paddingRight = 0f, paddingTop = 0f)
        val rect = mapper.toScreenRect(LogicalRect(0f, 100f, 10f, 50f))
        assertTrue("left ≤ right after vertical_rl mapping", rect.left <= rect.right)
        // left  = min(W - 10, W - 50) = W - 50
        // right = max(W - 10, W - 50) = W - 10
        assertEquals(W - 50f, rect.left)
        assertEquals(W - 10f, rect.right)
        assertEquals(0f, rect.top)
        assertEquals(100f, rect.bottom)
    }

    // ── Factory + LogicalRect 不变量 ────────────────────────────────────

    @Test
    fun `factory returns matching implementation for direction`() {
        val h = AxisMapper.forDirection(ReadingDirection.HORIZONTAL, W, H)
        val v = AxisMapper.forDirection(ReadingDirection.VERTICAL_RL, W, H)
        assertTrue(h is HorizontalAxisMapper)
        assertTrue(v is VerticalRlAxisMapper)
        assertEquals(ReadingDirection.HORIZONTAL, h.direction)
        assertEquals(ReadingDirection.VERTICAL_RL, v.direction)
    }

    @Test
    fun `LogicalRect rejects negative inline range`() {
        assertThrows(IllegalArgumentException::class.java) {
            LogicalRect(inlineStart = 50f, inlineEnd = 10f, blockStart = 0f, blockEnd = 100f)
        }
    }

    @Test
    fun `LogicalRect rejects negative block range`() {
        assertThrows(IllegalArgumentException::class.java) {
            LogicalRect(inlineStart = 0f, inlineEnd = 100f, blockStart = 50f, blockEnd = 10f)
        }
    }

    @Test
    fun `LogicalRect zero-size rectangle is allowed`() {
        // 边界条件：start == end，0 面积矩形是合法的（不存在的字符 / 空段尾占位）。
        val r = LogicalRect(10f, 10f, 20f, 20f)
        assertEquals(0f, r.inlineSize)
        assertEquals(0f, r.blockSize)
    }
}
