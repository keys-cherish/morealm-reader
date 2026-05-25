package com.morealm.app.ui.reader.renderer.scroll

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Constraints
import com.morealm.app.domain.render.layout.ScrollHighlightDrawSpec
import com.morealm.app.domain.render.layout.ScrollPageFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 滚动 Canvas 阅读器 UI 入口 —— **page-level 3-pane**（Phase 4 重构）。
 *
 * ── 与重构前 chapter-level 版本的核心差异 ──
 *
 * |             | chapter-level (重构前)              | page-level (本版本)              |
 * | ---         | ---                                 | ---                              |
 * | 滚动单元    | chapter (~22000px)                  | **page (~1800px)**               |
 * | 三块面板    | prev/cur/next 章                    | curPage/nextPage/nextPlusPage    |
 * | measure 上限| chapter.totalHeight cap 130k 防崩 | page.height < 18-bit 自然 OK    |
 * | swap 频率   | 每次跨章必触发                       | 仅 page 跨章 boundary 才触发     |
 * | 跨章        | applyScrollDelta 直接处理            | 由 factory.moveToNext/Prev 触发  |
 * | 视口剔除    | ChapterPaneCanvas 内按 page 剔除    | 不需要（每个 pane = 单 page）     |
 *
 * ── 手势模型（保留 D 方案的自实现 drag + fling 路径）──
 *
 * detectVerticalDragGestures + AnimationState.animateDecay + rememberSplineBasedDecay。
 * 调用 [applyPageScrollDelta] 替代旧 [applyScrollDelta]：
 * - delta 改写 state.pageOffset 而非 state.pixelOffset
 * - 单次跨 1 page + clamp，避免 fling 单帧瞬跳多 page
 * - 跨章在 factory.moveToNext/Prev 内通过 chapterShiftCallback 触发 state.swapToNext/Prev
 *   → drag 期 "allowSwap 守门 / dragSwapsConsumed / DIAG 探针" 全部不需要
 *
 * @param state 持久状态（章节 layout + pageOffset + currentChapterIndex）
 * @param pageFactory page 序列管理器（已注入 state 作为 DataSource）
 * @param curPageHighlightSpecs 当前 page 已投影的高亮 spec（rects 是 page-relative）
 * @param nextPageHighlightSpecs 下一 page 已投影的高亮 spec
 * @param nextPlusPageHighlightSpecs 下下 page 已投影的高亮 spec
 * @param curPageBookmarkCps 当前 page 内的书签 cp 列表
 * @param onChapterShift factory swap 后回调（delta=±1，Host 异步加载新远端章 + persist 进度）
 */
@Composable
fun ScrollCanvasRenderer(
    state: ScrollCanvasReaderState,
    pageFactory: ScrollPageFactory,
    contentPaint: android.text.TextPaint,
    titlePaint: android.text.TextPaint,
    chapterNumPaint: android.text.TextPaint,
    /** 跳转后呼吸高亮（命中 curPage chapterIndex 时透传给 cur PagePane）。 */
    revealHighlight: com.morealm.app.ui.reader.renderer.RevealHighlight? = null,
    /** 搜索高亮命中章 idx；与 curPage.chapterIndex 不一致时不画 */
    searchHighlightChapterIndex: Int = -1,
    searchHighlightCpRange: IntRange = IntRange.EMPTY,
    searchHighlightArgb: Int = 0x55FFFF00.toInt(),
    /** 选区命中章 idx；与 curPage.chapterIndex 不一致时不画选区背景 */
    selectionChapterIndex: Int = -1,
    selectionCpRange: IntRange = IntRange.EMPTY,
    selectionArgb: Int = 0x4D5B6CFE.toInt(),
    /** 3 个 page 各自已投影的高亮 spec（Host 内按 page 投影）。 */
    curPageHighlightSpecs: List<ScrollHighlightDrawSpec> = emptyList(),
    nextPageHighlightSpecs: List<ScrollHighlightDrawSpec> = emptyList(),
    nextPlusPageHighlightSpecs: List<ScrollHighlightDrawSpec> = emptyList(),
    /** 3 个 page 各自的书签 cp 列表（Host 内按 page 过滤）。 */
    curPageBookmarkCps: List<Int> = emptyList(),
    nextPageBookmarkCps: List<Int> = emptyList(),
    nextPlusPageBookmarkCps: List<Int> = emptyList(),
    modifier: Modifier = Modifier,
    onChapterShift: (delta: Int) -> Unit = {},
    onTapCenter: () -> Unit = {},
    /**
     * tap (xInView, yInView) 回调 —— Host 内做 tap-on-highlight 命中判定。
     * 返回 true 表示已消费（如弹了 highlight 菜单），false 表示放过 → 调 onTapCenter。
     */
    onTap: (androidx.compose.ui.geometry.Offset) -> Boolean = { false },
    /** 长按 (xInView, yInView) 触发回调 —— Host 内做选区命中。 */
    onLongPress: (androidx.compose.ui.geometry.Offset) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val flingDecay = rememberSplineBasedDecay<Float>()
    val velocityTracker = remember { VelocityTracker() }
    var flingJob by remember { mutableStateOf<Job?>(null) }

    val onChapterShiftUpdated by rememberUpdatedState(onChapterShift)
    val onTapCenterUpdated by rememberUpdatedState(onTapCenter)
    val onTapUpdated by rememberUpdatedState(onTap)
    val onLongPressUpdated by rememberUpdatedState(onLongPress)

    var viewportHeightPx by remember { mutableIntStateOf(0) }

    Layout(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { viewportHeightPx = it.height }
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                val fadeHeight = size.height * 0.05f
                drawRect(
                    brush = Brush.verticalGradient(
                        0f to Color.Black,
                        1f to Color.Transparent,
                        startY = 0f,
                        endY = fadeHeight,
                    ),
                    blendMode = BlendMode.DstOut,
                )
                drawRect(
                    brush = Brush.verticalGradient(
                        0f to Color.Transparent,
                        1f to Color.Black,
                        startY = size.height - fadeHeight,
                        endY = size.height,
                    ),
                    blendMode = BlendMode.DstOut,
                )
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { offset ->
                        val consumed = onTapUpdated(offset)
                        if (!consumed) onTapCenterUpdated()
                    },
                    onLongPress = { offset -> onLongPressUpdated(offset) },
                )
            }
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = {
                        flingJob?.cancel()
                        flingJob = null
                        velocityTracker.resetTracking()
                    },
                    onVerticalDrag = { change, dragAmount ->
                        velocityTracker.addPosition(change.uptimeMillis, change.position)
                        // page-level scroll delta；跨章由 factory 内部 chapterShiftCallback 触发
                        // state.swapToNext/Prev，外部 onChapterShift 回调通知 Host 异步加载新远端章。
                        val beforeChIdx = state.currentChapterIndex
                        applyPageScrollDelta(state, pageFactory, dragAmount)
                        if (state.currentChapterIndex != beforeChIdx) {
                            onChapterShiftUpdated(state.currentChapterIndex - beforeChIdx)
                        }
                        change.consume()
                    },
                    onDragEnd = {
                        val flingVelocity = velocityTracker.calculateVelocity().y
                        flingJob?.cancel()
                        flingJob = scope.launch {
                            try {
                                var lastValue = 0f
                                AnimationState(
                                    initialValue = 0f,
                                    initialVelocity = flingVelocity,
                                ).animateDecay(flingDecay) {
                                    val frameDelta = value - lastValue
                                    lastValue = value
                                    val beforeChIdx = state.currentChapterIndex
                                    applyPageScrollDelta(state, pageFactory, frameDelta)
                                    if (state.currentChapterIndex != beforeChIdx) {
                                        onChapterShiftUpdated(state.currentChapterIndex - beforeChIdx)
                                    }
                                }
                            } catch (_: CancellationException) {
                                // fling 被新 drag 打断，正常
                            }
                        }
                    },
                    onDragCancel = {
                        velocityTracker.resetTracking()
                    },
                )
            },
        content = {
            // 3 个 PagePaneCanvas，对应 factory.curPage / nextPage / nextPlusPage
            // page 高度 = page.height（典型 1800px，远 < 18-bit Constraints 上限）
            val curPage = pageFactory.curPage
            val nextPage = pageFactory.nextPage
            val nextPlusPage = pageFactory.nextPlusPage

            // 找 page 所属的 chapter layout 取 viewWidth / paddingLeft（page 自身不含这些）
            // curPage 一定来自 state.currentChapter；nextPage 可能来自 cur 或 next chapter
            val chapterViewWidth = state.currentChapter?.viewWidth
                ?: state.nextChapter?.viewWidth ?: 1080
            val chapterPaddingLeft = state.currentChapter?.paddingLeft
                ?: state.nextChapter?.paddingLeft ?: 0

            val revealForCur = revealHighlight?.takeIf { it.chapterIndex == curPage.chapterIndex }
            val searchRangeForCur = if (searchHighlightChapterIndex == curPage.chapterIndex) {
                searchHighlightCpRange
            } else IntRange.EMPTY
            val selectionRangeForCur = if (selectionChapterIndex == curPage.chapterIndex) {
                selectionCpRange
            } else IntRange.EMPTY

            PagePaneCanvas(
                page = curPage,
                chapterViewWidth = chapterViewWidth,
                chapterPaddingLeft = chapterPaddingLeft,
                contentPaint = contentPaint,
                titlePaint = titlePaint,
                chapterNumPaint = chapterNumPaint,
                highlightSpecs = curPageHighlightSpecs,
                bookmarkCps = curPageBookmarkCps,
                revealHighlight = revealForCur,
                searchHighlightCpRange = searchRangeForCur,
                searchHighlightArgb = searchHighlightArgb,
                selectionCpRange = selectionRangeForCur,
                selectionArgb = selectionArgb,
            )
            PagePaneCanvas(
                page = nextPage,
                chapterViewWidth = chapterViewWidth,
                chapterPaddingLeft = chapterPaddingLeft,
                contentPaint = contentPaint,
                titlePaint = titlePaint,
                chapterNumPaint = chapterNumPaint,
                highlightSpecs = nextPageHighlightSpecs,
                bookmarkCps = nextPageBookmarkCps,
            )
            PagePaneCanvas(
                page = nextPlusPage,
                chapterViewWidth = chapterViewWidth,
                chapterPaddingLeft = chapterPaddingLeft,
                contentPaint = contentPaint,
                titlePaint = titlePaint,
                chapterNumPaint = chapterNumPaint,
                highlightSpecs = nextPlusPageHighlightSpecs,
                bookmarkCps = nextPlusPageBookmarkCps,
            )
        },
    ) { measurables, constraints ->
        check(measurables.size == 3) { "ScrollCanvasRenderer expects exactly 3 measurables (cur/next/nextPlus)" }
        val viewWidth = constraints.maxWidth
        val viewHeight = constraints.maxHeight

        val curPage = pageFactory.curPage
        val nextPage = pageFactory.nextPage
        val nextPlusPage = pageFactory.nextPlusPage

        val curH = curPage.height.toInt().coerceAtLeast(0)
        val nextH = nextPage.height.toInt().coerceAtLeast(0)
        val nextPlusH = nextPlusPage.height.toInt().coerceAtLeast(0)

        val curPlaceable = measurables[0].measure(
            Constraints.fixed(width = viewWidth, height = curH.coerceAtLeast(1)),
        )
        val nextPlaceable = measurables[1].measure(
            Constraints.fixed(width = viewWidth, height = nextH.coerceAtLeast(1)),
        )
        val nextPlusPlaceable = measurables[2].measure(
            Constraints.fixed(width = viewWidth, height = nextPlusH.coerceAtLeast(1)),
        )

        layout(viewWidth, viewHeight) {
            // placement-only state read：pageOffset 高频变化只触发 Placement
            val offset = state.pageOffset.toInt()
            // curPage 顶相对视口顶在 y = -offset 处
            curPlaceable.placeRelative(0, -offset)
            // nextPage 紧跟 curPage 下方
            nextPlaceable.placeRelative(0, -offset + curH)
            // nextPlusPage 跟在 nextPage 下方
            nextPlusPlaceable.placeRelative(0, -offset + curH + nextH)
        }
    }
}
