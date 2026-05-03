package com.morealm.app.ui.reader.page.animation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import com.morealm.app.core.log.AppLog
import com.morealm.app.domain.entity.Highlight
import com.morealm.app.domain.render.ImageColumn
import com.morealm.app.domain.render.TextPage
import com.morealm.app.ui.reader.renderer.LocalReaderRenderTheme
import com.morealm.app.ui.reader.renderer.PageInfoOverlaySpec
import com.morealm.app.ui.reader.renderer.PageTurnCoordinator
import com.morealm.app.ui.reader.renderer.ReaderPageDirection
import com.morealm.app.ui.reader.renderer.ReaderPageFactory
import com.morealm.app.ui.reader.renderer.SelectionState
import com.morealm.app.ui.reader.renderer.chapterPositionAt
import com.morealm.app.ui.reader.renderer.findWordRange
import com.morealm.app.ui.reader.renderer.hitTestColumn
import com.morealm.app.ui.reader.renderer.hitTestPage

/**
 * 仿真翻页（贝塞尔曲面书页）参数构建——从 CanvasRenderer 抽出独立模块。
 *
 * # 抽出动机
 *
 * `CanvasRenderer.kt` 在 Phase 2 之前是 2456 行的巨型 Composable，里面塞了所有翻页
 * 模式（SCROLL / SLIDE / SIMULATION / COVER / FADE）+ 排版触发 + 选区 + 高亮 +
 * 进度恢复 + 自动翻页 + 9-zone tap action 等等。所有翻页模式共享同一个 LaunchedEffect
 * 池、同一个 remember 状态机、同一个 layoutChapterAsync 触发链——**改任意一个翻页
 * 模式都可能连锁破坏其他模式**（实测：改 SCROLL 阈值 → 触发 LaunchedEffect 重启 →
 * 影响 SIMULATION 的 coordinator initialPage 计算）。
 *
 * 本文件抽出 SIMULATION 的 [SimulationParams] 构建块——即 CanvasRenderer 旧 L1126-1212
 * 的 `simulationParams = remember { ... }` 段——让仿真翻页的入参组装独立化，
 * CanvasRenderer 只调用 [rememberSimulationParams] 拿到一个 nullable
 * `SimulationParams`，不再关心仿真细节。
 *
 * # 与其他文件的关系
 *
 * - `SimulationReadView.kt` (599 行)  — Android View 端的贝塞尔曲面 + 阴影 + 翻页动画
 * - `SimulationDrawHelper.kt` (519 行) — 仿真曲面计算 / 像素采样辅助
 * - `PageAnimationPagers.kt` 的 `AnimatedSimulationPager` — Compose AndroidView wrapper
 *   把上面的 `SimulationParams` 转给 SimulationReadView
 * - **本文件** — Compose 侧组装 `SimulationParams` 的 remember 块
 *
 * 等价于 Legado MD3 的 `delegate/SimulationPageDelegate.kt`（612 行）的 Compose 版本。
 *
 * # 调用方式
 *
 * ```kotlin
 * val simulationParams = rememberSimulationParams(
 *   pageAnimType = pageAnimType,
 *   pages = currentChapterPages,
 *   ...
 * )
 * AnimatedPageReader(simulationParams = simulationParams, ...)
 * ```
 *
 * 当 `pageAnimType != SIMULATION` 或 `pages.isEmpty()` 时返回 null，调用方据此决定
 * 渲染路径。
 */
@Composable
internal fun rememberSimulationParams(
    pageAnimType: PageAnimType,
    pages: List<TextPage>,
    bgMeanColor: Int,
    pageInfoOverlaySpec: PageInfoOverlaySpec?,
    pageFactory: ReaderPageFactory,
    chapterIndex: Int,
    pageCount: Int,
    renderPageCount: Int,
    coordinator: PageTurnCoordinator,
    selectionState: SelectionState,
    chapterHighlights: List<Highlight>,
    onProgress: (Int) -> Unit,
    onTapCenter: () -> Unit,
    onImageClick: (String) -> Unit,
    /** 设新 highlight action target（旧 var 等价：`highlightActionTarget = ...`） */
    setHighlightActionTarget: (Highlight?) -> Unit,
    /** 设 highlight action menu 显示位置 */
    setHighlightActionOffset: (Offset) -> Unit,
    /** 选中 TextPage（选区起点所在页）*/
    setSelectedTextPage: (TextPage?) -> Unit,
    /** 选区 toolbar 浮层位置 */
    setToolbarOffset: (Offset) -> Unit,
    /** readerPageIndex 写回（PageTurnCoordinator commit 后落到新 page index） */
    setReaderPageIndex: (Int) -> Unit,
): SimulationParams? {
    // ── paint / 背景 5 件套：通过 [LocalReaderRenderTheme] 取，不再走入参 ──
    //
    // 历史：caller (CanvasRenderer) 显式传入 titlePaint/contentPaint/chapterNumPaint/
    // bgArgb/bgBitmap。Phase 2 起 caller 已用 CompositionLocalProvider 注入主题，
    // 这里直接 .current 取 —— 既减少入参又减少 caller 跨多个翻页路径手传时漏一两个
    // 字段的低级 bug 概率。
    //
    // 注意：theme 字段变化会让本函数重组，进而让 remember 的 SimulationParams 重建。
    // 这正是期望行为：用户改字号 → titlePaint 换引用 → SimulationParams 重建 → 仿真
    // pager 拿到带新 paint 的 params → 下一帧 renderPageToBitmap 用新 paint 出图。
    val theme = LocalReaderRenderTheme.current
    val titlePaint = theme.titlePaint
    val contentPaint = theme.contentPaint
    val chapterNumPaint = theme.chapterNumPaint
    val bgArgb = theme.bgArgb
    val bgBitmap = theme.bgBitmap

    return remember(
        pages,
        titlePaint,
        contentPaint,
        bgArgb,
        bgBitmap,
        bgMeanColor,
        pageInfoOverlaySpec,
        pageFactory,
        chapterIndex,
    ) {
        if (pageAnimType == PageAnimType.SIMULATION && pages.isNotEmpty()) {
            SimulationParams(
                pages = pages,
                titlePaint = titlePaint,
                contentPaint = contentPaint,
                chapterNumPaint = chapterNumPaint,
                bgColor = bgArgb,
                bgBitmap = bgBitmap,
                bgMeanColor = bgMeanColor,
                pageInfoOverlay = pageInfoOverlaySpec,
                pageForTurn = { displayIndex, relativePos ->
                    pageFactory.pageForTurn(displayIndex, relativePos)
                },
                currentDisplayIndex = {
                    coordinator.lastSettledDisplayPage.coerceIn(
                        0,
                        (renderPageCount - 1).coerceAtLeast(0),
                    )
                },
                canTurn = { displayIndex, direction ->
                    when (direction) {
                        ReaderPageDirection.PREV -> pageFactory.hasPrev(displayIndex)
                        ReaderPageDirection.NEXT -> pageFactory.hasNext(displayIndex)
                        ReaderPageDirection.NONE -> false
                    }
                },
                onPageChanged = { displayIndex ->
                    val page = pages.getOrNull(displayIndex) ?: return@SimulationParams
                    if (page.chapterIndex == chapterIndex) {
                        onProgress(if (pageCount > 1) (page.index * 100) / (pageCount - 1) else 100)
                    }
                    // 仿真翻页：页面变化时清掉残留选区
                    if (selectionState.isActive) {
                        selectionState.clear()
                    }
                    setHighlightActionTarget(null)
                },
                onFillPage = { displayIndex, direction ->
                    coordinator.commitPageTurn(displayIndex, direction) { setReaderPageIndex(it) }
                },
                onTapCenter = onTapCenter,
                onSingleTap = if (chapterHighlights.isEmpty()) null else { offset ->
                    val page = coordinator.getPageAt(coordinator.lastSettledDisplayPage)
                    val pos = chapterPositionAt(page, offset.x, offset.y)
                    val hit = pos?.let { p ->
                        chapterHighlights.firstOrNull { p in it.startChapterPos until it.endChapterPos }
                    }
                    if (hit != null) {
                        setHighlightActionTarget(hit)
                        setHighlightActionOffset(offset)
                        true
                    } else false
                },
                onLongPress = { offset ->
                    val page = coordinator.getPageAt(coordinator.lastSettledDisplayPage)
                    val col = hitTestColumn(page, offset.x, offset.y)
                    if (col is ImageColumn) {
                        onImageClick(col.src)
                        return@SimulationParams
                    }
                    val pos = hitTestPage(page, offset.x, offset.y)
                    if (pos != null) {
                        val wordRange = findWordRange(page, pos)
                        setSelectedTextPage(page)
                        selectionState.setSelection(wordRange.first, wordRange.second)
                        setToolbarOffset(offset)
                        // Diagnostic: SIMULATION-only long-press path. Note this uses the
                        // SimulationView-supplied `page` directly (not pagerState.currentPage),
                        // so the page mismatch we'd otherwise see in NORMAL longPress is
                        // avoided here. Useful to compare with NORMAL/SCROLL traces.
                        AppLog.info(
                            "CursorHandleTrace",
                            "SIM longPress setSelection" +
                                " | tap=$pos -> word(${wordRange.first}..${wordRange.second})" +
                                " | page.lines.size=${page.lines.size} chPos=${page.chapterPosition}",
                        )
                    }
                },
            )
        } else null
    }
}
