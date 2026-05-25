package com.morealm.app.domain.render.layout

/**
 * 图片像素 dims 解析接口 —— 让 [ScrollLayoutEngine] 不直接依赖 ImageCache / BitmapFactory
 * （它们需要 Android Context / 文件系统访问），保持 domain 层纯净。
 *
 * 生产代码（UI 层 / DI 模块）注入 [com.morealm.app.domain.render.ImageCache] 桥接实现；
 * 单测注入 [NoOp]（走 fallback dims） 或 mock 实现验证特定场景。
 *
 * 与旧 [com.morealm.app.domain.render.ChapterProvider.setTypeImage] 内嵌的
 * `mobi-img://` / file 协议解码逻辑等价分离 —— 同样的 dims 计算结果交给本接口实现层。
 */
fun interface ScrollImageDimensionsResolver {
    /**
     * 解码图片得到原始（intrinsic）像素 dims。
     *
     * @param src 图片资源标识：`file:///...` / `mobi-img://...` / `http(s)://...`
     * @param targetWidth 渲染目标宽（visibleWidth）—— 实现可据此选择采样率降低内存
     * @return (intrinsicWidth, intrinsicHeight)；无法解码 / 不存在 / 异步未就绪返 null
     *         null 时 [ScrollLayoutEngine] 走 4:3 fallback dims（visibleWidth × 0.75）
     */
    fun resolve(src: String, targetWidth: Int): Pair<Int, Int>?

    companion object {
        /**
         * NoOp 实现：始终返 null → 触发 [ScrollLayoutEngine] 的 4:3 fallback。
         * 单测默认 / 图片未加载时使用。
         */
        val NoOp: ScrollImageDimensionsResolver = ScrollImageDimensionsResolver { _, _ -> null }
    }
}
