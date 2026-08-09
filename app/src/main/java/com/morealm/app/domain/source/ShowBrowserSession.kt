package com.morealm.app.domain.source

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * `loginExt.showBrowser(...)` / `java.showBrowser(...)` 交互式 WebView 的请求—应答通道。
 *
 * 为什么需要它：登录脚本里 `showBrowser` 是**同步调用**（脚本要等页面加载完、用户操作完才
 * 能继续）。我们通过 `ShowBrowserSession` 启 Activity，用这个 Channel 把结果回传给等待中
 * 的脚本协程——脚本侧用 [awaitResult] 挂起等待，Activity 侧用 [emit] 提交结果。
 *
 * [mutex] 保证并发请求串行化：第二次调用等第一次 emit 后才能进 channel，避免同一 channel
 * 被多个等待者哄抢导致结果错配。参照实现 `BottomWebViewDialog` 是单例 dialog，同样是同一时
 * 刻只能开一个——行为对齐。
 *
 * 结果约定：
 *  - 非 null String：WebView 加载完成后的页面 HTML（登录脚本通常正则匹配里面的 token）
 *  - null：用户关闭 / 超时 / Activity 被杀，脚本应当回退到"登录失败"分支
 */
object ShowBrowserSession {
    private val mutex = Mutex()
    private val channel = Channel<String?>(Channel.RENDEZVOUS)

    /**
     * 脚本侧挂起直到 Activity 调 [emit]。并发调用会在 [mutex] 上排队串行化。Activity
     * 启动的 Intent 与 [awaitResult] 之间没有显式绑定——同一时刻只有一个请求持有 mutex，
     * emit 结果必然配对。
     */
    suspend fun awaitResult(): String? = mutex.withLock {
        channel.receive()
    }

    /** Activity 侧用户完成 / 取消时调。若当前无等待者，trySend 直接 drop。 */
    fun emit(html: String?) {
        channel.trySend(html)
    }
}
