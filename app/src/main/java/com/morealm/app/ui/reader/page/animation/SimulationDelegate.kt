package com.morealm.app.ui.reader.page.animation

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import com.morealm.app.core.log.AppLog
import com.morealm.app.domain.entity.Highlight
import com.morealm.app.domain.render.ImageColumn
import com.morealm.app.domain.render.TextPage
import com.morealm.app.domain.render.TextPos
import com.morealm.app.ui.reader.renderer.CursorHandle
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
import com.morealm.app.ui.reader.renderer.hitTestPageRough

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
    /**
     * 当前章节的用户高亮（kind=0）渲染数据。CanvasRenderer 已派生为 [HighlightSpan]，
     * 这里直接转给 [SimulationParams.chapterHighlights] 让 SimulationPager 在 bitmap
     * 渲染时按页过滤后画底色矩形。
     */
    highlightSpans: List<com.morealm.app.ui.reader.renderer.HighlightSpan>,
    /**
     * 当前章节的字体强调色 spans（kind=1）渲染数据。
     */
    textColorSpans: List<com.morealm.app.ui.reader.renderer.HighlightSpan>,
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
        // highlights / textColor 数据变化也要让 params 重建，否则用户保存高亮后
        // bitmap 不会按新数据重出 → 看不到刚划的高亮。
        highlightSpans,
        textColorSpans,
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
                chapterHighlights = highlightSpans,
                chapterTextColorSpans = textColorSpans,
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
                // 选区激活时让 SimulationReadView 的触摸门控生效——popup 弹着的时候
                // 翻页手势整个静默。lambda 引用 selectionState 实时取值，不会被
                // remember 锁旧。
                isSelectionActive = { selectionState.isActive },
                // 门控期间用户点空白：等价 SLIDE/COVER 的 detectTapGestures 兜底——
                // 清选区 + 清掉已存高亮 action menu，让 popup 关掉。两个状态都清是
                // 因为 toolbar / action menu 互斥但都靠这条路径关；多清一次无副作用。
                onDismissPopup = {
                    if (selectionState.isActive) selectionState.clear()
                    setHighlightActionTarget(null)
                },
            )
        } else null
    }
}

/**
 * SIMULATION 模式专用的 selection / cursor overlay。
 *
 * ── 为什么需要独立 overlay ──
 *
 * SLIDE / COVER 等翻页模式走 [com.morealm.app.ui.reader.renderer.PageContentBox]
 * 的 Compose 路径渲染整页（PageCanvas + cursor），选区背景在 PageContentDrawer 里
 * 作为 canvas 绘制路径完成，cursor 在 PageContentBox 末尾叠加。但 SIMULATION 把整页
 * 烘成 Bitmap 喂给 [com.morealm.app.ui.reader.renderer.SimulationReadView]（一个
 * AndroidView 跑贝塞尔曲面动画），bitmap 缓存命中率是性能基石——选区拖把手时每帧
 * 重 render bitmap 不可接受。
 *
 * ── 方案 ──
 *
 * 把 selection 背景矩形 + 双 [CursorHandle] 画在 SimulationReadView **之上**的
 * Compose 层（一个 fillMaxSize 的 [Box] 内）。bitmap 保持静态文字 + 已存高亮，
 * 实时选区只动 Compose 层 → drag cursor 时仅触发 Compose 重绘，bitmap 零重 render。
 *
 * ── 与 PageContentBox 的差异 ──
 *
 * - PageContentBox 自己画整页文字，本 overlay 只画 selection rect + cursor
 * - 用 [hitTestPageRough] 而不是严格 hit-test：拖到行首左侧、行末右侧时 clamp
 *   到首列/末列，避免选区被拖丢
 * - 不读 selectionState.startPos/endPos 的 relativePagePos —— SIMULATION 的 page
 *   切换由 SimulationReadView 内部贝塞尔动画处理，本 overlay 只负责"当前 settled
 *   page"上的选区；翻页 settled 后 [SimulationParams.onPageChanged] 已经 clear
 *   selectionState，overlay 自然消失
 *
 * @param selectionState 当前选区状态。startPos/endPos 任一为 null 时不画 cursor。
 * @param currentPage 当前 settled page（[PageTurnCoordinator.lastSettledDisplayPage]
 *        对应的 [TextPage]）。null = 不画。caller 通过 lambda 提供以避免每次重组
 *        都重读 coordinator。
 * @param selectionColor 选区背景色（取主题 [com.morealm.app.ui.reader.renderer.LocalReaderRenderTheme.selectionColor]）。
 * @param onSelectionStartMove 拖动起点 cursor 时回调，参数是新的 [TextPos]。
 * @param onSelectionEndMove 拖动终点 cursor 时回调。
 */
@Composable
fun SimulationSelectionOverlay(
    selectionState: SelectionState,
    currentPage: TextPage?,
    selectionColor: Color,
    onSelectionStartMove: (TextPos) -> Unit,
    onSelectionEndMove: (TextPos) -> Unit,
    modifier: Modifier = Modifier,
) {
    val page = currentPage ?: return
    val startPos = selectionState.startPos?.takeIf { it.relativePagePos == 0 }
    val endPos = selectionState.endPos?.takeIf { it.relativePagePos == 0 }
    if (startPos == null || endPos == null) return

    Box(modifier = modifier.fillMaxSize()) {
        // ── 选区背景：每行一个矩形 ──
        //
        // 跟 [com.morealm.app.ui.reader.renderer.PageContentDrawer.drawPageContent]
        // 的 selection-highlight 段等价，但用 Compose Canvas 画。
        // canvas 坐标系 = SimulationReadView 像素坐标系（fillMaxSize 让 Box 与 view
        // 等大；Canvas 默认 1:1）。
        Canvas(modifier = Modifier.fillMaxSize()) {
            val startLine = startPos.lineIndex
            val endLine = endPos.lineIndex
            val paddingTop = page.paddingTop
            for (lineIndex in page.lines.indices) {
                if (lineIndex !in startLine..endLine) continue
                val line = page.lines[lineIndex]
                if (line.columns.isEmpty()) continue
                val colStart = if (lineIndex == startLine) startPos.columnIndex else 0
                val colEnd = if (lineIndex == endLine) endPos.columnIndex else line.columns.lastIndex
                if (colStart > colEnd) continue
                val cs = colStart.coerceIn(0, line.columns.lastIndex)
                val ce = colEnd.coerceIn(0, line.columns.lastIndex)
                val left = line.columns[cs].start
                val right = line.columns[ce].end
                val top = line.lineTop + paddingTop
                val bottom = line.lineBottom + paddingTop
                drawRect(
                    color = selectionColor,
                    topLeft = Offset(left, top),
                    size = Size((right - left), (bottom - top)),
                )
            }
        }

        // ── 双 cursor handle ──
        //
        // 用 [TextPos.lineIndex/columnIndex] 算出 baseline 末尾下方的圆点位置。
        // 起点在 column.start（左缘）、终点在 column.end（右缘）—— 与 PageContentBox
        // L2443 cursorOffsetFor 等价。
        val startOffset = cursorOffsetForSimulation(page, startPos, startHandle = true)
        val endOffset = cursorOffsetForSimulation(page, endPos, startHandle = false)
        startOffset?.let { off ->
            CursorHandle(position = off, onDrag = { dragOffset ->
                hitTestPageRough(page, dragOffset.x, dragOffset.y)?.let(onSelectionStartMove)
            })
        }
        endOffset?.let { off ->
            CursorHandle(position = off, onDrag = { dragOffset ->
                hitTestPageRough(page, dragOffset.x, dragOffset.y)?.let(onSelectionEndMove)
            })
        }
    }
}

/**
 * 算 cursor 在 [page] 内的屏幕坐标 (x, y)。
 *
 * 与 [com.morealm.app.ui.reader.renderer.PageContentBox] L2443 cursorOffsetFor 等价
 * —— 复制一份是为了让 [SimulationSelectionOverlay] 不必把 PageContentBox 当 helper
 * 依赖（PageContentBox 是 private fun，且自身有别的页级渲染职责）。
 *
 * @param startHandle true = 起点 cursor（取 column.start 左缘）；false = 终点 cursor
 *        （取 column.end 右缘）。
 * @return null 如果 textPos 越界或行内无 column。
 */
private fun cursorOffsetForSimulation(
    page: TextPage,
    textPos: TextPos,
    startHandle: Boolean,
): Offset? {
    val line = page.lines.getOrNull(textPos.lineIndex) ?: return null
    if (line.columns.isEmpty()) return null
    val columnIndex = textPos.columnIndex.coerceIn(0, line.columns.lastIndex)
    val column = line.columns[columnIndex]
    val x = if (startHandle) column.start else column.end
    return Offset(x, line.lineBottom + page.paddingTop)
}
