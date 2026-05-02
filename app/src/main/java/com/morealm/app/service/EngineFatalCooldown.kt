package com.morealm.app.service

/**
 * TTS 引擎"致命错误冷却"状态机。
 *
 * 用途：当 [TtsEngineHost] 检测到引擎已无法正常播放（典型场景：第 0 条 utterance
 * 入队就返回 ERROR、连续多次 reInit 仍失败、binder 调用持续超时），调
 * [markFatal] 进入冷却期。冷却期内任何播放入口（loadAndPlay / resume /
 * speakOneShot）都应该立即放弃，避免反复触发同样的 ANR 路径——历史 err.txt
 * 里 9 次连续相同堆栈的 ANR 就是因为没有这一层短路。
 *
 * 抽成独立类的理由：
 * 1. 状态机本身**完全没有 Android 依赖**——只看时间戳，可以写成纯 JVM 单元测试。
 * 2. host 类已经过千行，再塞一组 fatal 字段+方法会进一步推高复杂度；委托给这个
 *    helper 后，host 入口的短路检查只剩一行 `if (gate.shortCircuit(...)) return`。
 * 3. 单一职责：这个类只关心"是否在冷却"，不关心 publishState / sendEvent 等
 *    side-effect——副作用由调用方决定。
 *
 * 线程安全：所有内部字段是 `@Volatile`，方法本身无锁；多线程同时 markFatal/clear
 * 时最后写入者胜，对业务无影响（fatal 是单调状态——多记一次时间戳无害）。
 *
 * @param cooldownMs 冷却时长。一旦 [markFatal]，[isInCooldown] 在此时间内返回 true。
 * @param nowProvider 时钟源；测试时注入 fake 时钟以避免依赖墙钟。
 */
internal class EngineFatalCooldown(
    private val cooldownMs: Long,
    private val nowProvider: () -> Long = { System.currentTimeMillis() },
) {
    /** 上次 markFatal 的时间戳；0 表示当前没有 fatal 标记。 */
    @Volatile private var fatalAtMs: Long = 0L

    /**
     * 标记引擎进入 fatal 状态，开始 [cooldownMs] 冷却。
     * 调用方负责日志输出与上报，本类只维护时间戳。
     */
    fun markFatal() {
        fatalAtMs = nowProvider()
    }

    /**
     * 解除 fatal 标记。reInit 成功 / 用户主动切引擎 / 切包名时调用。
     * 不在冷却期时调用是 noop。
     */
    fun clear() {
        fatalAtMs = 0L
    }

    /**
     * @return true 表示当前在冷却期内；false 表示可以正常进入播放路径。
     *         冷却期到期时本方法会**自动清零** [fatalAtMs]，等价于自然解除。
     */
    fun isInCooldown(): Boolean {
        val at = fatalAtMs
        if (at == 0L) return false
        val elapsed = nowProvider() - at
        if (elapsed >= cooldownMs) {
            fatalAtMs = 0L
            return false
        }
        return true
    }

    /**
     * @return 距当前冷却结束的剩余秒数；不在冷却期返回 0。
     *         供错误文案显示用（"30 秒内重试将再次失败"）。
     */
    fun remainingSeconds(): Long {
        val at = fatalAtMs
        if (at == 0L) return 0L
        val remaining = cooldownMs - (nowProvider() - at)
        return (remaining / 1000L).coerceAtLeast(0L)
    }
}
