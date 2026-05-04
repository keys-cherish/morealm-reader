package com.morealm.app.ui.reader.renderer

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D

/**
 * 合成 reveal HighlightSpan 时使用的固定 id。
 *
 * 作用：上层（如点击后弹删除/分享菜单）会按 id 找回原始 [com.morealm.app.domain.entity.Highlight]
 * 来定位 DB 行；reveal 是临时纯 UI 反馈，没有对应 DB 行，用一个明显的特殊 id 标记，
 * 让点击 hit-test 路径能立刻识别出来跳过 DB 查询。
 */
const val REVEAL_HIGHLIGHT_ID = "__reveal_highlight__"

/**
 * 跳转后短暂出现的「目标整段呼吸高亮」状态。
 *
 * 触发场景：书签 / TOC / 续读 / 跨章成功恢复后，让用户一眼看到自己跳到了哪一段。
 * 不持久化，纯 UI 反馈层，1 秒左右褪色清空。
 *
 * - [chapterIndex]：高亮属于哪一章（跨章跳转后用来判断是否还在当前章节渲染范围内）。
 * - [startChapterPos] / [endChapterPos]：目标段在章内的字符区间，[start, end)；
 *   合成 [HighlightSpan] 时直接复用这两个字段。
 * - [baseColorArgb]：色值的 RGB 基色，alpha 通道由 [alpha] 实时调制；通常取主题 primary。
 *   注意：构造时把传入色 ARGB 的 alpha 通道清零，避免和 [alpha] 双重应用导致始终偏淡。
 * - [alpha]：[Animatable] 驱动的 0..1 透明度乘子。每帧由 reader 主体重组读取触发更新。
 *
 * 设计选择：Reveal 与已存高亮（[com.morealm.app.domain.entity.Highlight]）共用渲染管线。
 * 在分页模式下我们把 Reveal 包装成一个临时 [HighlightSpan] 注入到 chapterHighlights，
 * 走和已存高亮一模一样的"per-line bg rect"路径——免维护两套绘制代码。滚动模式
 * （[LazyScrollRenderer]）目前没接入 highlights 管线，留作后续；分页模式覆盖了
 * 99% 的跳转使用场景。
 */
data class RevealHighlight(
    val chapterIndex: Int,
    val startChapterPos: Int,
    val endChapterPos: Int,
    val baseColorArgb: Int,
    val alpha: Animatable<Float, AnimationVector1D>,
) {
    /**
     * 当前 alpha 调制后的最终 ARGB。alpha=1 完整显示，0 完全透明（实际上等于不画）。
     * 上限做 0.32f 缩放：reveal 是"轻提示"，太重会和文字抢戏。
     */
    fun currentArgb(): Int {
        val a = (alpha.value * 0.32f).coerceIn(0f, 1f)
        val alphaByte = (a * 255).toInt().coerceIn(0, 255)
        return (alphaByte shl 24) or (baseColorArgb and 0x00FFFFFF)
    }
}
