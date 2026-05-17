package com.morealm.app.ui.reader.renderer.scroll

/**
 * 选区状态 —— 用户长按选词 / handle drag 期间持有的选区端点。
 *
 * 用 chapter position（cp）而非 ScrollColumn 引用持端点：
 * 1. **章节 swap 安全**：cur swap 到 next 章时旧 column 引用失效；持 cp 在新章节排版结果里
 *    重新反查（[com.morealm.app.domain.render.scroll.ScrollLayoutEngine.findColumnAt]）即可。
 * 2. **DB 持久化对齐**：选区"高亮"动作直接把 startCp/endCp 落库到 Highlight，与
 *    [com.morealm.app.domain.entity.Highlight.startChapterPos] 1:1 对应。
 *
 * [startCp] 与 [endCp] 顺序无关 —— [cpRange] 自动 min/max 归一化。
 * 这对应「向上 / 向下拖 handle」两种方向都正确。
 *
 * @param chapterIndex 选区所在章 idx（M4.2 跨章选区暂不支持，超章 handle drag 走自动滚动 + cap 在章内）
 * @param startCp 选区起 cp（按长按位置或 drag 起 handle 位置）
 * @param endCp 选区末 cp（按 drag 末 handle 位置）
 * @param isActive 选区是否激活（长按触发后 = true；点击空白处取消 = false）
 */
data class ScrollSelectionState(
    val chapterIndex: Int,
    val startCp: Int,
    val endCp: Int,
    val isActive: Boolean = true,
    /**
     * 用户**长按 tap 点**在 reader Box 本地坐标系下的位置 —— SelectionToolbar Popup
     * 用这个值定位（与 V1 LazyScrollSection anchorInBox 等价）。
     *
     * 与 [endCp] 推导的端点位置不同：用户拖 handle 扩选区时 endCp 跟着变，但 anchor
     * 始终是最初长按那一点，让 popup 和箭头始终指向"用户的意图位置"而不是飘到选区末。
     */
    val anchorInBox: androidx.compose.ui.geometry.Offset =
        androidx.compose.ui.geometry.Offset.Zero,
) {
    /** 归一化的 cp 范围（含起含止）。startCp 可能大于 endCp（反向 drag），用 min/max 归一。 */
    val cpRange: IntRange
        get() = minOf(startCp, endCp)..maxOf(startCp, endCp)

    /** 单字符选区：start == end。 */
    val isSingleChar: Boolean
        get() = startCp == endCp

    companion object {
        /** 空选区（无激活，常用于初始化 / 取消选区）。 */
        val Empty: ScrollSelectionState = ScrollSelectionState(
            chapterIndex = -1,
            startCp = 0,
            endCp = 0,
            isActive = false,
        )
    }
}
