package com.morealm.app.algo

/**
 * Native 算法入口 —— Kotlin 侧 thin facade，所有方法体在 `libmorealm_algo.so`。
 *
 * # 命名
 * 方法名故意中性（`generateSecMsGec` 是协议名而非业务语义），cpp 内部函数名
 * 不与此对应，由 `JNI_OnLoad + RegisterNatives` 在加载时绑定。
 *
 * # 线程安全
 * 实现为纯函数（无可变状态），允许任意线程并发调用。如需缓存策略，由调用方在
 * Kotlin 侧实现。
 *
 * # 加载策略
 * `init` 内 `System.loadLibrary`；[com.morealm.app.MoRealmApp.onCreate] 触发一次
 * 类初始化预 load，避免首次 TTS 调用走 IO。低版本 / 异常 ABI 设备 load 失败时
 * `UnsatisfiedLinkError` 由调用方捕获。
 */
internal object NativeOps {

    init {
        System.loadLibrary("morealm_algo")
    }

    /**
     * 计算 Sec-MS-GEC token —— Edge TTS WSS / voices 接口必填的时间戳鉴权头。
     *
     * @param nowMillis 当前 Unix epoch 毫秒（通常 `System.currentTimeMillis()`，测试可注入）
     * @return 64 字符大写 hex 串
     */
    external fun generateSecMsGec(nowMillis: Long): String

    /**
     * Trusted client identifier —— Edge TTS query string 必填的 32 字符 hex 串。
     * 字符串由 native 在调用瞬间从 obfuscated 表解码,返回后 Kotlin 端不应长期缓存。
     */
    external fun trustedClientIdentifier(): String
}
