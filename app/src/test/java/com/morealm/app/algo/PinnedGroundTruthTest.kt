package com.morealm.app.algo

import com.morealm.app.domain.tts.EdgeTtsAuth
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pinned ground-truth 测试 —— 固定时间戳 → 固定输出。
 *
 * 这些 expected 值由独立工具（Python `hashlib.sha256`）算出，与 Kotlin /
 * cpp 实现完全解耦，能 catch 算法整段被改写或常量漂移。
 *
 * 真机 instrumented test [NativeOpsBitLevelTest] 用同样 oracle 跟 native 对比。
 */
class PinnedGroundTruthTest {

    @After
    fun teardown() {
        EdgeTtsAuth.resetClockSkew()
    }

    @Test
    fun kotlin_implementation_matches_python_ground_truth() {
        // 由 Python hashlib.sha256 算出，作为算法 spec 的不可变锚点。
        val cases = listOf(
            0L to "7ECB79D14E3AA576D2D79E6D487A1388156D91E614B1BE11C64226A29BC8DD8C",
            1_700_000_000_000L to "42301B335578FEFDAE2637DED1ABD614505D432559EC08032B82048483726AFF",
            1_700_000_100_000L to "AE4CF72E466874182A75878E20EADA83D29A1C12CAD9C3E0E014CCE0BFA55880",
            1_700_000_400_000L to "CA2F7F35ACEB51738EEE9E9FEF6A5E9E6165A29E7FDE395479BB9F57DABB7086",
        )
        for ((ts, expected) in cases) {
            assertEquals("ts=$ts", expected, EdgeTtsAuth.generateSecMsGec(ts))
        }
    }
}
