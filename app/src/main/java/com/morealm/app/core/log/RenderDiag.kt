package com.morealm.app.core.log

/**
 * 渲染/排版热路径诊断日志总开关。
 *
 * 逐行（EpubW5H/D1a）、逐帧（PageTurnFlicker）级诊断日志的字符串构建 + logd 写入
 * 在章节排版 / 翻页绘制热路径上有实打实的开销：进程死亡恢复实测中 logcat 被刷到
 * chatty 限流（每秒丢几百行），既拖慢排版又把真正有用的诊断信息全部挤丢。
 *
 * 默认关闭；排查排版/翻页问题时把 [verbose] 置 true 重新放行（重新编译或调试期改值）。
 *
 * 两级拦截：
 *  - [AppLog] dispatch 对 [isHotPathTag] 命中的 tag 直接丢弃（sink/logd 全跳过）；
 *  - epub-lib 引擎侧逐行位点由 `LayoutLog.verbose` 在调用点跳过字符串构建
 *    （见 AppLogLayoutLog —— 它把本开关透传给引擎）。
 *
 * 低频高价值 tag（PageLevelCore / SwapDiag / ReadAnchor / JUMP / TitleLayout /
 * PlateDiag 等）不在此列，照常放行。
 */
object RenderDiag {
    @JvmStatic
    @Volatile
    var verbose: Boolean = false

    /** dispatch 期按前缀匹配的热路径 tag（逐行 / 逐帧 / 逐盒级别）。 */
    @JvmStatic
    fun isHotPathTag(tag: String): Boolean =
        tag.startsWith("EpubW5H") ||
            tag.startsWith("D1a/") ||
            tag.startsWith("BoxScope") ||
            tag.startsWith("BoxGroup/") ||
            tag == "PageTurnFlicker" ||
            tag == "P3-5b/CharColor"
}
