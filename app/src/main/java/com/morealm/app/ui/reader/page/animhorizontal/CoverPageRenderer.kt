package com.morealm.app.ui.reader.page.animhorizontal

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Constraints
import com.morealm.app.domain.render.scroll.ScrollHighlightDrawSpec
import com.morealm.app.domain.render.scroll.ScrollPageFactory
import com.morealm.app.ui.reader.renderer.scroll.PagePaneCanvas
import com.morealm.app.ui.reader.renderer.scroll.ScrollCanvasReaderState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 覆盖翻页（COVER）独立 Renderer —— V2 page-level 横向 cover 版本。
 *
 * 按 Legado PageDelegate 模型：Renderer 只 own drag + 动画绘制 + fling，
 * tap/长按/选区由 [PageLevelReaderHost] 共享层处理（Renderer 不接 pointerInput tap）。
 *
 * ## 与 SLIDE 的差别
 *
 * |              | SLIDE (横向同步)        | COVER (覆盖, 本类)                |
 * | ---          | ---                     | ---                               |
 * | cur placement| -offset                 | 0 (cur 不动)                      |
 * | next/prev    | -offset + W / -offset - W | W - offset / -W - offset (覆盖入场) |
 * | 视觉         | 两页同步滑动           | next/prev 像纸张滑上来盖住 cur   |
 * | 阴影         | 无                      | next 左 / prev 右边缘 0.45 alpha 黑色阴影 |
 *
 * pageOffset 解释：0 = cur 完全显示；+viewportW = next 完全覆盖；-viewportW = prev 完全覆盖。
 */
@Composable
fun CoverPageRenderer(
    state: ScrollCanvasReaderState,
    pageFactory: ScrollPageFactory,
    backgroundColor: Color,
    contentPaint: android.text.TextPaint,
    titlePaint: android.text.TextPaint,
    chapterNumPaint: android.text.TextPaint,
    revealHighlight: com.morealm.app.ui.reader.renderer.RevealHighlight? = null,
    searchHighlightChapterIndex: Int = -1,
    searchHighlightCpRange: IntRange = IntRange.EMPTY,
    searchHighlightArgb: Int = 0x55FFFF00.toInt(),
    selectionChapterIndex: Int = -1,
    selectionCpRange: IntRange = IntRange.EMPTY,
    selectionArgb: Int = 0x4D5B6CFE.toInt(),
    curPageHighlightSpecs: List<ScrollHighlightDrawSpec> = emptyList(),
    nextPageHighlightSpecs: List<ScrollHighlightDrawSpec> = emptyList(),
    prevPageHighlightSpecs: List<ScrollHighlightDrawSpec> = emptyList(),
    curPageBookmarkCps: List<Int> = emptyList(),
    nextPageBookmarkCps: List<Int> = emptyList(),
    prevPageBookmarkCps: List<Int> = emptyList(),
    /** Host 注入：zone tap → 走本 Renderer 的 animateAndCommit 覆盖动画；null = Host fallback 瞬切 */
    turnCtrl: PageTurnAnimController? = null,
    modifier: Modifier = Modifier,
    onChapterShift: (delta: Int) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val velocityTracker = remember { VelocityTracker() }
    var flingJob by remember { mutableStateOf<Job?>(null) }
    val onChapterShiftUpdated by rememberUpdatedState(onChapterShift)
    var viewportWidthPx by remember { mutableIntStateOf(0) }

    suspend fun animateAndCommit(targetEdge: Float, viewportW: Float) {
        if (viewportW <= 0f) return
        val startOffset = state.pageOffset
        if (startOffset == targetEdge) return
        val distance = kotlin.math.abs(targetEdge - startOffset)
        val durationMs = (distance / viewportW * 280f).toInt().coerceIn(120, 320)
        animate(
            initialValue = startOffset,
            targetValue = targetEdge,
            animationSpec = tween(durationMillis = durationMs),
        ) { value, _ -> state.pageOffset = value }
        val beforeChIdx = state.currentChapterIndex
        when {
            targetEdge > 0 && pageFactory.moveToNext() -> {
                state.pageOffset = 0f
                if (state.currentChapterIndex != beforeChIdx) {
                    onChapterShiftUpdated(state.currentChapterIndex - beforeChIdx)
                }
            }
            targetEdge < 0 && pageFactory.moveToPrev() -> {
                state.pageOffset = 0f
                if (state.currentChapterIndex != beforeChIdx) {
                    onChapterShiftUpdated(state.currentChapterIndex - beforeChIdx)
                }
            }
            else -> state.pageOffset = 0f
        }
    }

    // Host zone tap 注入：走本 Renderer 的 animateAndCommit 让 zone tap 也有覆盖动画。
    DisposableEffect(turnCtrl) {
        turnCtrl?.animateToNext = {
            val viewportW = viewportWidthPx.toFloat()
            if (viewportW > 0f) animateAndCommit(viewportW, viewportW)
        }
        turnCtrl?.animateToPrev = {
            val viewportW = viewportWidthPx.toFloat()
            if (viewportW > 0f) animateAndCommit(-viewportW, viewportW)
        }
        onDispose {
            turnCtrl?.animateToNext = null
            turnCtrl?.animateToPrev = null
        }
    }

    var diagDragMoves by remember { mutableIntStateOf(0) }
    Layout(
        modifier = modifier
            .onSizeChanged { viewportWidthPx = it.width }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = {
                        com.morealm.app.core.log.AppLog.info("CoverRenderer", "DIAG onDragStart viewportW=$viewportWidthPx pageOffset=${state.pageOffset}")
                        diagDragMoves = 0
                        flingJob?.cancel()
                        flingJob = null
                        velocityTracker.resetTracking()
                    },
                    onHorizontalDrag = { change, dragAmount ->
                        if (diagDragMoves < 3) {
                            com.morealm.app.core.log.AppLog.info("CoverRenderer", "DIAG onHorizontalDrag #$diagDragMoves dx=$dragAmount off=${state.pageOffset}")
                        }
                        diagDragMoves++
                        velocityTracker.addPosition(change.uptimeMillis, change.position)
                        val viewportW = viewportWidthPx.toFloat()
                        if (viewportW > 0f) {
                            val canNext = pageFactory.hasNext()
                            val canPrev = pageFactory.hasPrev()
                            val newOffset = (state.pageOffset - dragAmount).coerceIn(
                                if (canPrev) -viewportW else 0f,
                                if (canNext) viewportW else 0f,
                            )
                            state.pageOffset = newOffset
                        }
                        change.consume()
                    },
                    onDragEnd = {
                        val flingVelocity = velocityTracker.calculateVelocity().x
                        com.morealm.app.core.log.AppLog.info("CoverRenderer", "DIAG onDragEnd moves=$diagDragMoves off=${state.pageOffset} vel=$flingVelocity")
                        flingJob?.cancel()
                        flingJob = scope.launch {
                            try {
                                val viewportW = viewportWidthPx.toFloat()
                                if (viewportW <= 0f) return@launch
                                val curOffset = state.pageOffset
                                val velocityCommitThreshold = 800f
                                val targetOffset = when {
                                    curOffset > 0 -> {
                                        val commit = curOffset > viewportW / 2 || flingVelocity < -velocityCommitThreshold
                                        val cancel = flingVelocity > velocityCommitThreshold
                                        if (commit && !cancel) viewportW else 0f
                                    }
                                    curOffset < 0 -> {
                                        val commit = curOffset < -viewportW / 2 || flingVelocity > velocityCommitThreshold
                                        val cancel = flingVelocity < -velocityCommitThreshold
                                        if (commit && !cancel) -viewportW else 0f
                                    }
                                    else -> 0f
                                }
                                animateAndCommit(targetOffset, viewportW)
                            } catch (_: CancellationException) {
                                // animate 被新 drag 打断
                            }
                        }
                    },
                    onDragCancel = { velocityTracker.resetTracking() },
                )
            },
        content = {
            val curPage = pageFactory.curPage
            val nextPage = pageFactory.nextPage
            val prevPage = pageFactory.prevPage
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

            // 索引 0：curPage（始终在 0,0，不动）
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
                modifier = Modifier.fillMaxSize().background(backgroundColor),
            )
            // 索引 1：nextPage（从右滑入覆盖 cur），带左边阴影
            PagePaneCanvas(
                page = nextPage,
                chapterViewWidth = chapterViewWidth,
                chapterPaddingLeft = chapterPaddingLeft,
                contentPaint = contentPaint,
                titlePaint = titlePaint,
                chapterNumPaint = chapterNumPaint,
                highlightSpecs = nextPageHighlightSpecs,
                bookmarkCps = nextPageBookmarkCps,
                modifier = Modifier
                    .fillMaxSize()
                    .background(backgroundColor)
                    .drawWithContent {
                        drawContent()
                        if (state.pageOffset > 0f) {
                            val shadowWidth = 24f
                            drawRect(
                                brush = Brush.horizontalGradient(
                                    0f to Color.Black.copy(alpha = 0.45f),
                                    1f to Color.Transparent,
                                    startX = 0f,
                                    endX = shadowWidth,
                                ),
                                topLeft = androidx.compose.ui.geometry.Offset(0f, 0f),
                                size = androidx.compose.ui.geometry.Size(shadowWidth, size.height),
                            )
                        }
                    },
            )
            // 索引 2：prevPage（从左滑入覆盖 cur），带右边阴影
            PagePaneCanvas(
                page = prevPage,
                chapterViewWidth = chapterViewWidth,
                chapterPaddingLeft = chapterPaddingLeft,
                contentPaint = contentPaint,
                titlePaint = titlePaint,
                chapterNumPaint = chapterNumPaint,
                highlightSpecs = prevPageHighlightSpecs,
                bookmarkCps = prevPageBookmarkCps,
                modifier = Modifier
                    .fillMaxSize()
                    .background(backgroundColor)
                    .drawWithContent {
                        drawContent()
                        if (state.pageOffset < 0f) {
                            val shadowWidth = 24f
                            drawRect(
                                brush = Brush.horizontalGradient(
                                    0f to Color.Transparent,
                                    1f to Color.Black.copy(alpha = 0.45f),
                                    startX = size.width - shadowWidth,
                                    endX = size.width,
                                ),
                                topLeft = androidx.compose.ui.geometry.Offset(size.width - shadowWidth, 0f),
                                size = androidx.compose.ui.geometry.Size(shadowWidth, size.height),
                            )
                        }
                    },
            )
        },
    ) { measurables, constraints ->
        check(measurables.size == 3) { "CoverPageRenderer expects 3 measurables (cur/next/prev)" }
        val viewWidth = constraints.maxWidth
        val viewHeight = constraints.maxHeight
        val pageConstraints = Constraints.fixed(width = viewWidth, height = viewHeight.coerceAtLeast(1))
        val curPlaceable = measurables[0].measure(pageConstraints)
        val nextPlaceable = measurables[1].measure(pageConstraints)
        val prevPlaceable = measurables[2].measure(pageConstraints)
        layout(viewWidth, viewHeight) {
            val offset = state.pageOffset.toInt()
            // COVER：cur 永远在 (0,0) 不动；next 从右覆盖；prev 从左覆盖
            curPlaceable.placeRelative(0, 0)
            nextPlaceable.placeRelative(viewWidth - offset, 0)
            prevPlaceable.placeRelative(-viewWidth - offset, 0)
        }
    }
}
