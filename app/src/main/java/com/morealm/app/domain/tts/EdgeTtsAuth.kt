package com.morealm.app.domain.tts

import com.morealm.app.core.log.AppLog
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Edge TTS 鉴权辅助：生成微软 2024 年起强制要求的 `Sec-MS-GEC` 时间戳 token。
 *
 * # 为什么需要这个
 * 老版本 EdgeTtsEngine 只发了 TrustedClientToken，没带 Sec-MS-GEC，
 * 服务端会偶发 401/403 + 中文长句被切断。把这个头补齐后稳定性就回来了。
 *
 * # 算法（公开记录于 rany2/edge-tts issue #290，与微软 Edge 浏览器内部行为一致）
 * 1. 取当前 UTC 秒（Unix 时间戳）
 * 2. 加上 Windows 文件时间纪元偏移 (1601-01-01 → 1970-01-01) = 11644473600 秒
 * 3. 向下取整到 5 分钟（300 秒）的边界 —— 同一窗口内多次调用产生相同 token
 *    （便于服务端缓存校验，也允许客户端时钟微小漂移）
 * 4. 转成 100ns ticks（Windows file time 单位） = × 10^7
 * 5. ASCII 拼接 ticks 整数 + TrustedClientToken
 * 6. SHA-256 → 大写 hex
 *
 * # 时钟偏移自校正
 * 设备时钟可能因用户手调或时区错乱偏离服务端 UTC 数分钟。token 取整到 5 分钟
 * 的窗口给了一定容忍度，但极端偏差会导致全部请求被拒。[adjustClockSkew] 在收到
 * 401/403 + 服务端 `Date` 头时调用，把客户端与服务端的差值记下来用于下次生成。
 *
 * # 仅整数算术
 * 5 分钟边界（秒为 300 的倍数）× 10^7 始终是干净的整数，不会有浮点舍入误差，
 * 全部用 [Long] 即可。
 *
 * # 日志
 * 用 tag `EdgeTtsAuth`（已注册到 [com.morealm.app.core.log.LogTagCatalog]）。
 * 关键事件：偏移调整成功 / 解析失败 / 偏移过大被忽略。token 生成本身不打日志
 * （每次连 WSS 都会调，过于频繁）。
 */
object EdgeTtsAuth {

    /** Edge 浏览器内置常量 —— 不是用户密钥，所有 Edge 浏览器都用同一个值。 */
    private const val TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"

    /** Windows file time 起算点（1601-01-01 UTC）相对 Unix epoch（1970-01-01 UTC）的偏移秒数。 */
    private const val WIN_EPOCH_OFFSET_SECONDS = 11_644_473_600L

    /** 5 分钟取整窗口（秒）。 */
    private const val ROUND_WINDOW_SECONDS = 300L

    /** 1 秒 = 10^7 个 100ns ticks。 */
    private const val TICKS_PER_SECOND = 10_000_000L

    /** 当前对齐的 Chromium 完整版本号 —— 与 [userAgent] / [secMsGecVersion] 保持同步。 */
    private const val CHROMIUM_FULL_VERSION = "143.0.3650.75"

    /** Chromium 主版本号，用于 UA 字符串。 */
    private const val CHROMIUM_MAJOR_VERSION = "143"

    /** `Sec-MS-GEC-Version` 头/查询参数固定值。 */
    const val SEC_MS_GEC_VERSION = "1-$CHROMIUM_FULL_VERSION"

    /** WSS 握手用的 UA —— 必须像最新版 Edge 浏览器，否则会被按版本拒连。 */
    val userAgent: String =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/$CHROMIUM_MAJOR_VERSION.0.0.0 Safari/537.36 " +
            "Edg/$CHROMIUM_MAJOR_VERSION.0.0.0"

    /** WSS 握手用的 Origin —— 模拟 Edge 浏览器 read aloud 扩展。 */
    const val ORIGIN = "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold"

    /**
     * 累计的客户端时钟偏移（秒）。`+` 表示客户端比服务端慢，需要把客户端时间往前调。
     * [adjustClockSkew] 写入，[currentUnixSeconds] 读取。`@Volatile` 确保多线程可见。
     */
    @Volatile
    private var clockSkewSeconds: Long = 0L

    /**
     * 把客户端时钟与服务端 `Date` 头的差值累加进 [clockSkewSeconds]。
     *
     * 例：服务端是 12:00:00，客户端是 11:55:00 → diff = +300 → 下次生成 token 时
     * 客户端的"当前时间"会被加上 300 秒，对齐到服务端窗口。
     *
     * @param serverDate 来自 `Response.headers["Date"]`，RFC 1123 格式
     *        （例 `Wed, 12 Nov 2024 12:00:00 GMT`）。null / 解析失败时不调整。
     */
    fun adjustClockSkew(serverDate: String?) {
        if (serverDate.isNullOrBlank()) return
        val serverEpoch = parseRfc1123(serverDate) ?: run {
            AppLog.debug(TAG, "adjustClockSkew: parse failed for serverDate='$serverDate'")
            return
        }
        val clientEpoch = currentUnixSeconds()
        val diff = serverEpoch - clientEpoch
        // 超过 1 天的"偏移"基本是解析或网络异常，忽略以免把 token 推到非法范围。
        if (kotlin.math.abs(diff) > 86_400L) {
            AppLog.warn(
                TAG,
                "adjustClockSkew: ignoring extreme diff=${diff}s (server='$serverDate', client=$clientEpoch)",
            )
            return
        }
        clockSkewSeconds += diff
        AppLog.info(
            TAG,
            "adjustClockSkew: diff=${diff}s applied → totalSkew=${clockSkewSeconds}s " +
                "(server='$serverDate', client=$clientEpoch)",
        )
    }

    /** 把当前累计偏移清零（测试用 / 重置入口）。 */
    fun resetClockSkew() {
        clockSkewSeconds = 0L
    }

    /**
     * 生成 `Sec-MS-GEC` token。
     *
     * @param nowMillis 注入用的当前时间（默认 [System.currentTimeMillis]）。
     *        测试可以传固定值得到确定 hash。
     */
    fun generateSecMsGec(nowMillis: Long = System.currentTimeMillis()): String {
        // 步骤 1-2：Unix 秒 + 时钟偏移修正 + Windows 纪元偏移
        val unixSeconds = nowMillis / 1000L + clockSkewSeconds
        val winFileTimeSeconds = unixSeconds + WIN_EPOCH_OFFSET_SECONDS

        // 步骤 3：向下取整到 5 分钟边界
        val rounded = winFileTimeSeconds - (winFileTimeSeconds % ROUND_WINDOW_SECONDS)

        // 步骤 4：转 100ns ticks。300s × 10^7 仍在 Long 范围内（约 1.7e10 vs Long.MAX 9.2e18）
        val ticks = rounded * TICKS_PER_SECOND

        // 步骤 5：ASCII 拼接 → SHA-256 → 大写 hex
        val payload = "$ticks$TRUSTED_CLIENT_TOKEN"
        return sha256UpperHex(payload)
    }

    /** 暴露给 [EdgeTtsEngine] 的当前时间戳（含偏移）—— 单元测试同样可以注入。 */
    fun currentUnixSeconds(nowMillis: Long = System.currentTimeMillis()): Long =
        nowMillis / 1000L + clockSkewSeconds

    /** 暴露给 [EdgeTtsEngine] 的 TrustedClientToken，避免它再持一份重复常量。 */
    val trustedClientToken: String get() = TRUSTED_CLIENT_TOKEN

    // ── 内部工具 ──────────────────────────────────────────────────────────

    private fun sha256UpperHex(input: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(input.toByteArray(Charsets.US_ASCII))
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4])
            sb.append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    private val HEX = charArrayOf(
        '0', '1', '2', '3', '4', '5', '6', '7',
        '8', '9', 'A', 'B', 'C', 'D', 'E', 'F',
    )

    /**
     * 解析 RFC 1123 / RFC 2616 日期串为 Unix 秒。
     * `Wed, 12 Nov 2024 12:00:00 GMT` → 1731412800
     */
    private fun parseRfc1123(date: String): Long? {
        return try {
            val sdf = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
            sdf.timeZone = TimeZone.getTimeZone("UTC")
            val parsed: Date = sdf.parse(date) ?: return null
            parsed.time / 1000L
        } catch (_: Exception) {
            null
        }
    }

    private const val TAG = "EdgeTtsAuth"
}
