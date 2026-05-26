package com.morealm.app.algo

import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * NativeOps native 实现位级一致性测试。
 *
 * 关键不变量：
 * 1. native 输出与 Kotlin 参考实现在任意时间戳下**完全字节一致**
 * 2. 同一 5 分钟窗口内多次调用产生相同 token
 * 3. 跨窗口调用产生不同 token
 * 4. 输出 64 字符大写 hex
 *
 * Robolectric 用 JVM `java.lang.UnsatisfiedLinkError` 拒绝加载 `.so`
 * （Robolectric 不跑 native lib），所以这里检测到无 native 时直接 skip——
 * 真机 instrumented test 才跑得起 native。
 */
@RunWith(RobolectricTestRunner::class)
class NativeOpsTest {

    /** 与 cpp 内 kTokenEnc 解码后值一致的参考值（Kotlin 端 oracle）。 */
    private val referenceToken = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
    private val winEpochOffsetSeconds = 11_644_473_600L
    private val roundWindowSeconds = 300L
    private val ticksPerSecond = 10_000_000L

    /**
     * Robolectric 跑在 host JVM，无法加载 Android ABI .so。这里探测一次,
     * 后续每个 case 用 [assumeTrue] 把 native 不可用的环境直接跳过, 让 JUnit 报告
     * skipped 而不是 fail。真机 instrumented test 必能 load → 在那里走完整位级一致性。
     */
    private var nativeAvailable = false

    @Before
    fun probeNative() {
        nativeAvailable = try {
            NativeOps.generateSecMsGec(0L)
            true
        } catch (_: Throwable) {
            false
        }
    }

    /** Pure Kotlin reference (与项目内 EdgeTtsAuth.generateSecMsGec 同算法)。 */
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
    fun `native matches Kotlin reference at fixed timestamp`() {
        assumeTrue("native lib not available in this JVM (Robolectric)", nativeAvailable)
        val now = 1_700_000_000_000L
        val native = NativeOps.generateSecMsGec(now)
        val reference = referenceGenerate(now)
        assertEquals("native must match Kotlin reference byte-for-byte", reference, native)
    }

    @Test
    fun `native matches reference across multiple timestamps`() {
        assumeTrue("native lib not available in this JVM (Robolectric)", nativeAvailable)
        val samples = listOf(
            0L,
            1L,
            1_000L,
            1_500_000_000_000L,
            1_700_000_100_000L,
            1_700_000_400_000L,
            1_999_999_999_999L,
        )
        for (ts in samples) {
            val native = NativeOps.generateSecMsGec(ts)
            assertEquals("mismatch at ts=$ts", referenceGenerate(ts), native)
        }
    }

    @Test
    fun `output is 64 char uppercase hex`() {
        assumeTrue("native lib not available in this JVM (Robolectric)", nativeAvailable)
        val token = NativeOps.generateSecMsGec(1_700_000_000_000L)
        assertEquals(64, token.length)
        assertTrue("uppercase hex only: $token", token.all {
            it in '0'..'9' || it in 'A'..'F'
        })
    }

    @Test
    fun `same 5 minute window produces stable token`() {
        assumeTrue("native lib not available in this JVM (Robolectric)", nativeAvailable)
        val base = 1_700_000_100_000L  // 1700000100s, divisible by 300
        val t1 = NativeOps.generateSecMsGec(base)
        val t2 = NativeOps.generateSecMsGec(base + 1_000L)
        val t3 = NativeOps.generateSecMsGec(base + 60_000L)
        val t4 = NativeOps.generateSecMsGec(base + 4 * 60_000L + 59_000L)
        assertEquals(t1, t2)
        assertEquals(t1, t3)
        assertEquals(t1, t4)
    }

    @Test
    fun `crossing 5 minute window changes token`() {
        assumeTrue("native lib not available in this JVM (Robolectric)", nativeAvailable)
        val base = 0L
        val t1 = NativeOps.generateSecMsGec(base)
        val t2 = NativeOps.generateSecMsGec(base + 5 * 60_000L)
        assertNotEquals(t1, t2)
    }

    /**
     * Reference oracle 自检 —— 不依赖 native，验证 Kotlin reference 算法本身没坏。
     * Robolectric 与真机都跑。Step 4 后用做 EdgeTtsAuth 内部位级对照基线。
     */
    @Test
    fun `reference oracle is stable and well-formed`() {
        val now = 1_700_000_000_000L
        val r1 = referenceGenerate(now)
        val r2 = referenceGenerate(now + 1_000L)
        val r3 = referenceGenerate(now + 5 * 60_000L)  // 跨窗口
        assertEquals(64, r1.length)
        assertEquals("same window stable", r1, r2)
        assertNotEquals("cross 5min boundary changes token", r1, r3)
        assertTrue("uppercase hex only: $r1", r1.all {
            it in '0'..'9' || it in 'A'..'F'
        })
    }
}
