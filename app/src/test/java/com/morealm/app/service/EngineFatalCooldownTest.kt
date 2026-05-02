package com.morealm.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [EngineFatalCooldown] 单元测试 —— 纯逻辑、纯 JVM，不依赖 Android。
 *
 * 关键不变量：
 *  1. **初始态**：刚构造时不在冷却。
 *  2. **markFatal 触发冷却**：调用后立刻 `isInCooldown == true`。
 *  3. **冷却期内 isInCooldown 持续返回 true**：模拟时钟前进但未到期，仍冷却。
 *  4. **到期自动解除**：模拟时钟越过 cooldown 后下一次 isInCooldown 返回 false
 *     并把 fatalAt 清零（避免泄漏的"半冷却"状态）。
 *  5. **clear 立即解除**：手动 clear 后即使时钟没动也不在冷却。
 *  6. **remainingSeconds 边界**：刚 mark 时约等于 cooldown/1000；不在冷却时返回 0。
 */
class EngineFatalCooldownTest {

    /** 可控时钟：测试中手动推进，用于无 sleep 验证 cooldown 状态机。 */
    private class FakeClock(var nowMs: Long = 1_000_000L) {
        fun advance(deltaMs: Long) { nowMs += deltaMs }
    }

    private fun cooldown(durationMs: Long = 30_000L, clock: FakeClock = FakeClock()) =
        Pair(EngineFatalCooldown(durationMs) { clock.nowMs }, clock)

    @Test
    fun `initial state is not in cooldown`() {
        val (gate, _) = cooldown()
        assertFalse(gate.isInCooldown())
        assertEquals(0L, gate.remainingSeconds())
    }

    @Test
    fun `markFatal enters cooldown immediately`() {
        val (gate, _) = cooldown()
        gate.markFatal()
        assertTrue("expected in cooldown right after markFatal", gate.isInCooldown())
    }

    @Test
    fun `still in cooldown before window elapses`() {
        val (gate, clock) = cooldown(durationMs = 30_000L)
        gate.markFatal()
        clock.advance(15_000L) // 半个窗口
        assertTrue(gate.isInCooldown())
        clock.advance(14_999L) // 总共 29_999ms，差 1ms 到期
        assertTrue(gate.isInCooldown())
    }

    @Test
    fun `auto-clears on window expiry`() {
        val (gate, clock) = cooldown(durationMs = 30_000L)
        gate.markFatal()
        clock.advance(30_000L) // 正好到期
        assertFalse("expected cooldown to auto-clear at expiry", gate.isInCooldown())
        // 再次询问应仍是 false（fatalAt 已被清零，不会"重新进入冷却"）
        assertFalse(gate.isInCooldown())
        assertEquals(0L, gate.remainingSeconds())
    }

    @Test
    fun `manual clear lifts cooldown without time advance`() {
        val (gate, _) = cooldown()
        gate.markFatal()
        assertTrue(gate.isInCooldown())
        gate.clear()
        assertFalse(gate.isInCooldown())
    }

    @Test
    fun `clear is no-op when not in cooldown`() {
        val (gate, _) = cooldown()
        gate.clear() // 应该不抛
        assertFalse(gate.isInCooldown())
    }

    @Test
    fun `remainingSeconds reflects time left`() {
        val (gate, clock) = cooldown(durationMs = 30_000L)
        gate.markFatal()
        // 刚 mark：还剩 ~30s（向下取整）
        assertEquals(30L, gate.remainingSeconds())
        clock.advance(10_500L) // 用了 10.5s
        // 还剩 19.5s → 整数除法 19
        assertEquals(19L, gate.remainingSeconds())
        clock.advance(20_000L) // 总共 30.5s 已过
        assertFalse(gate.isInCooldown())
        assertEquals(0L, gate.remainingSeconds())
    }

    @Test
    fun `markFatal twice resets the timer`() {
        val (gate, clock) = cooldown(durationMs = 30_000L)
        gate.markFatal()
        clock.advance(20_000L)
        // 第二次 markFatal：相当于把窗口重新拉满
        gate.markFatal()
        clock.advance(15_000L) // 距第二次 markFatal 仅 15s，仍在冷却
        assertTrue(
            "second markFatal should restart the window",
            gate.isInCooldown(),
        )
    }

    @Test
    fun `multiple markFatal in same instant is idempotent`() {
        val (gate, _) = cooldown()
        gate.markFatal()
        gate.markFatal()
        gate.markFatal()
        assertTrue(gate.isInCooldown())
    }
}
