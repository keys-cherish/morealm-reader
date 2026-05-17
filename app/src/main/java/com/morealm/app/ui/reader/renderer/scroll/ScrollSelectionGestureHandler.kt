package com.morealm.app.ui.reader.renderer.scroll

import com.morealm.app.domain.render.scroll.ScrollChapterLayout
import com.morealm.app.domain.render.scroll.findColumnByPixel

/**
 * 选区手势纯函数 —— 长按 → 选词起；drag handle → 移动端点。
 *
 * 抽出纯函数便于单测；UI 层（[ScrollSelectionOverlay]）的 pointerInput
 * 回调内调用这些函数 + 调 setState 触发重组。
 */

/** 选区 handle 端点标识。 */
enum class ScrollHandleSide {
    /** 选区起点 handle（视觉上：左侧 / 较小 cp 端）。 */
    START,
    /** 选区末点 handle（视觉上：右侧 / 较大 cp 端）。 */
    END,
}

/**
 * 长按选词：把屏幕坐标 (x, yInChapter) 转成单字符选区。
 *
 * 命中字符 column → 选区为该字符；
 * 命中空段 / 图片段 line → 选区为该 line.firstChapterPos（视觉是整行选中）；
 * 未命中 → 返 [ScrollSelectionState.Empty]（不激活，取消现有选区）。
 *
 * 简化：长按只选单字符（startCp == endCp）。用户后续 drag handle 扩展选区。
 * 旧 ChapterProvider 路径有"长按选词边界"逻辑（按 CJK / ASCII 分词），M6 后再补。
 *
 * @param chapterIndex 当前章 idx（必须匹配 layout）
 */
fun handleLongPress(
    layout: ScrollChapterLayout,
    chapterIndex: Int,
    x: Float,
    yInChapter: Float,
    /**
     * 用户长按 tap 点在 reader Box 本地坐标系下的位置 —— 给 SelectionToolbar 当
     * anchor，让 popup / 箭头始终指向用户最初按下的位置（而非选区末端）。
     * 默认 Offset.Zero 保持单测兼容；UI 路径必须传真实坐标（view-local）。
     */
    anchorInBox: androidx.compose.ui.geometry.Offset = androidx.compose.ui.geometry.Offset.Zero,
): ScrollSelectionState {
    if (chapterIndex != layout.chapterIndex) {
        com.morealm.app.core.log.AppLog.warn(
            "ScrollSelection",
            "handleLongPress mismatched chapter: chapterIdx=$chapterIndex layout.idx=${layout.chapterIndex}",
        )
        return ScrollSelectionState.Empty
    }
    val hit = layout.findColumnByPixel(x, yInChapter)
    if (hit == null) {
        com.morealm.app.core.log.AppLog.warn(
            "ScrollSelection",
            "handleLongPress findColumnByPixel MISS x=$x yInChapter=$yInChapter chapterIdx=$chapterIndex",
        )
        return ScrollSelectionState.Empty
    }
    val cp = hit.column?.chapterPosition ?: hit.line.firstChapterPos
    com.morealm.app.core.log.AppLog.info(
        "ScrollSelection",
        "handleLongPress HIT input x=$x yInChapter=$yInChapter anchorInBox=$anchorInBox " +
            "→ cp=$cp hit.column=${hit.column?.let { "start=${it.start} end=${it.end} char='${it.charData}'" }} " +
            "hit.line.lineTop=${hit.line.lineTop} hit.line.lineBottom=${hit.line.lineBottom}",
    )
    return ScrollSelectionState(
        chapterIndex = chapterIndex,
        startCp = cp,
        endCp = cp,
        isActive = true,
        anchorInBox = anchorInBox,
    )
}

/**
 * Drag handle：移动选区某端点到屏幕坐标 (x, yInChapter) 命中的 cp。
 *
 * 选区 inactive 或坐标越界时不动 selection（返原值）。
 *
 * @param side START → 更新 selection.startCp；END → 更新 selection.endCp
 */
fun handleHandleDrag(
    selection: ScrollSelectionState,
    layout: ScrollChapterLayout,
    side: ScrollHandleSide,
    x: Float,
    yInChapter: Float,
): ScrollSelectionState {
    if (!selection.isActive) return selection
    if (selection.chapterIndex != layout.chapterIndex) return selection
    val hit = layout.findColumnByPixel(x, yInChapter) ?: return selection
    val cp = hit.column?.chapterPosition ?: hit.line.firstChapterPos
    return when (side) {
        ScrollHandleSide.START -> selection.copy(startCp = cp)
        ScrollHandleSide.END -> selection.copy(endCp = cp)
    }
}

/** 取消选区（点击空白处）。 */
fun handleCancelSelection(): ScrollSelectionState = ScrollSelectionState.Empty
