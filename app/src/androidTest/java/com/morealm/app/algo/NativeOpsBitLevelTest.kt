package com.morealm.app.algo

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * NativeOps 位级一致性 instrumented test —— 跑在真机 / 模拟器，能加载
 * `libmorealm_algo.so`，对比 cpp 实现与 Kotlin reference 算法字节级输出。
 *
 * 跑法：`./gradlew :app:connectedDebugAndroidTest --tests "*NativeOpsBitLevelTest*"`
 *
 * Robolectric 不能 load Android ABI .so，所以同样语义的位级一致性测试在
 * `test/NativeOpsTest` 是 [org.junit.Assume.assumeTrue] skip 的。**真机这套是
 * 位级一致性的最终守门**。
 */
@RunWith(AndroidJUnit4::class)
class NativeOpsBitLevelTest {

    private val referenceToken = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
    private val winEpochOffsetSeconds = 11_644_473_600L
    private val roundWindowSeconds = 300L
    private val ticksPerSecond = 10_000_000L

    /** Kotlin oracle —— 与 cpp 应字节一致。 */
    private fun referenceGenerate(nowMillis: Long): String {
        val unixSeconds = nowMillis / 1000L
        val winFileTimeSeconds = unixSeconds + winEpochOffsetSeconds
        val rounded = winFileTimeSeconds - (winFileTimeSeconds % roundWindowSeconds)
        val ticks = rounded * ticksPerSecond
        val payload = "$ticks$referenceToken"
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(payload.toByteArray(Charsets.US_ASCII))
        val sb = StringBuilder(digest.size * 2)
        val hex = "0123456789ABCDEF"
        for (b in digest) {
            val v = b.toInt() and 0xFF
            sb.append(hex[v ushr 4])
            sb.append(hex[v and 0x0F])
        }
        return sb.toString()
    }

    @Test
    fun native_matches_reference_at_fixed_timestamp() {
        val now = 1_700_000_000_000L
        assertEquals(referenceGenerate(now), NativeOps.generateSecMsGec(now))
    }

    @Test
    fun native_matches_reference_at_multiple_timestamps() {
        val samples = longArrayOf(
            0L,
            1L,
            1_000L,
            1_500_000_000_000L,
            1_700_000_100_000L,
            1_700_000_400_000L,
            1_999_999_999_999L,
        )
        for (ts in samples) {
            assertEquals("mismatch at ts=$ts", referenceGenerate(ts), NativeOps.generateSecMsGec(ts))
        }
    }

    @Test
    fun output_is_64_char_uppercase_hex() {
        val token = NativeOps.generateSecMsGec(1_700_000_000_000L)
        assertEquals(64, token.length)
        assertTrue("uppercase hex only: $token", token.all {
            it in '0'..'9' || it in 'A'..'F'
        })
    }

    @Test
    fun same_5min_window_produces_stable_token() {
        val base = 1_700_000_100_000L
        val t1 = NativeOps.generateSecMsGec(base)
        assertEquals(t1, NativeOps.generateSecMsGec(base + 1_000L))
        assertEquals(t1, NativeOps.generateSecMsGec(base + 60_000L))
        assertEquals(t1, NativeOps.generateSecMsGec(base + 4 * 60_000L + 59_000L))
    }

    @Test
    fun crossing_5min_window_changes_token() {
        val base = 0L
        assertNotEquals(
            NativeOps.generateSecMsGec(base),
            NativeOps.generateSecMsGec(base + 5 * 60_000L),
        )
    }

    @Test
    fun trusted_identifier_is_32_char_uppercase_hex() {
        val id = NativeOps.trustedClientIdentifier()
        assertEquals(32, id.length)
        assertTrue("uppercase hex only: $id", id.all {
            it in '0'..'9' || it in 'A'..'F'
        })
        // 稳定性 —— 同一进程多次调用得相同值
        assertEquals(id, NativeOps.trustedClientIdentifier())
    }
}
