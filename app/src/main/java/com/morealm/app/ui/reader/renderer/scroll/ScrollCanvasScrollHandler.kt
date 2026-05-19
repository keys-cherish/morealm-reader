package com.morealm.app.ui.reader.renderer.scroll

/**
 * page-level 滚动 delta 应用 —— Phase 4+ 唯一路径（旧 chapter-level applyScrollDelta
 * 已删除，原 137 行的 D 方案 allowSwap 守门 + DIAG 探针 + chapter swap 同步逻辑
 * 全部由 [com.morealm.app.domain.render.scroll.ScrollPageFactory] 接管）。
 *
 * 抽出纯函数便于单测 —— 不依赖 Compose UI。由 [ScrollCanvasRenderer] 的
 * detectVerticalDragGestures + AnimationState.animateDecay 内逐帧调用。
 */

/**
 * **page-level 滚动 delta 应用**。
 *
 * 与 chapter-level 旧路径的关键差异：
 * - swap 粒度从 chapter 降到 **page**（约 1800px / 1 viewport）
 * - 跨章不由本函数直接处理；由 [com.morealm.app.domain.render.scroll.ScrollPageFactory]
 *   的 moveToNext / moveToPrev 内部通过 chapterShiftCallback 触发
 *   [ScrollCanvasReaderState.swapToNext] / [ScrollCanvasReaderState.swapToPrev]
 * - **单次调用最多跨 1 page**（用户决策 2026-05-19）：
 *   fling 单帧 delta 过大时跨 1 page 后 clamp 到边界，下一帧 fling delta 继续累积再 swap。
 *   保证视觉上"page-by-page"翻动自然，避免单帧瞬跳过多 page 的突兀感。
 *
 * ── pageOffset 范围 ──
 *
 * `[0f, curPage.height]`（正值约定）：
 * - `pageOffset += -delta`（dragAmount > 0 向下滑视口看上方 → pageOffset 减小）
 * - 越界 ≥ curPage.height → 触发 moveToNext + pageOffset -= 旧 pageH
 * - 越界 < 0 → 触发 moveToPrev + pageOffset += 新 pageH（来自 prev page）
 *
 * ── 单次跨 1 page 的 clamp 策略 ──
 *
 * 1. 向下越界后若 moveToNext 成功，新 pageOffset 仍 ≥ 新 curPage.height（即"想跨 2 page"），
 *    强制 clamp 到新 pageH（page 末贴视口顶，准备下帧再跨）
 * 2. 向上越界后若 moveToPrev 成功，新 pageOffset 仍 < 0（即"想跨 2 page 向上"），
 *    强制 clamp 到 0
 * 3. moveToNext/Prev 失败（末章末页 / 首章首页 / 加载中）→ clamp 到 pageH / 0
 *
 * @param state Compose state-backed 阅读器状态（pageOffset 高频写）
 * @param factory page 序列管理器（已注入 dataSource = state）
 * @param delta Compose `Modifier.scrollable` 或 detectVerticalDragGestures 原始 dragAmount
 * @return 已消费的 delta（始终返回入参 delta，完全消费）
 */
internal fun applyPageScrollDelta(
    state: ScrollCanvasReaderState,
    factory: com.morealm.app.domain.render.scroll.ScrollPageFactory,
    delta: Float,
): Float {
    val curPageH = factory.curPage.height
    if (curPageH <= 0f) {
        // 无效 page（EMPTY_PAGE / dataSource 加载中）：consume 但不动 offset
        return delta
    }

    state.pageOffset -= delta

    when {
        // 向下越界（pageOffset ≥ curPageH）：单次跨 1 page
        state.pageOffset >= curPageH -> {
            if (factory.moveToNext()) {
                // 跨 page 成功（章内 OR 跨章）；factory 内部 swap 后 curPage 已变
                state.pageOffset -= curPageH
                // 如果仍越界（fling 单帧想跨 2+ page）→ clamp 到新 page 末，等下帧再跨
                val newPageH = factory.curPage.height
                if (newPageH > 0f && state.pageOffset >= newPageH) {
                    state.pageOffset = newPageH
                }
            } else {
                // 末章末页 / next 加载中 → clamp 到 pageH（"拖不过去"但不卡死）
                state.pageOffset = curPageH
            }
        }

        // 向上越界（pageOffset < 0）：单次跨 1 page prev
        state.pageOffset < 0f -> {
            if (factory.moveToPrev()) {
                val newPageH = factory.curPage.height
                state.pageOffset += newPageH
                // 同上：仍越界 → clamp 到 0
                if (state.pageOffset < 0f) {
                    state.pageOffset = 0f
                }
            } else {
                // 首章首页 / prev 加载中 → clamp 到 0
                state.pageOffset = 0f
            }
        }

        // 章内 / page 内：pageOffset 自然在 [0, curPageH] 内变化，无 swap
        else -> Unit
    }

    return delta
}
