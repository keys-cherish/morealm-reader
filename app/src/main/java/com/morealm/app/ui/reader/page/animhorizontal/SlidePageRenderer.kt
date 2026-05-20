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
 * 平移翻页（SLIDE）独立 Renderer —— V2 page-level 横向 Renderer。
 *
 * 按 Legado PageDelegate 模型：Renderer 只 own **drag + animation 翻页**，
 * tap/长按/选区由 [PageLevelReaderHost] 共享层处理（Renderer 不接 pointerInput tap）。
 *
 * - 手势：detectHorizontalDragGestures + settle-to-edge fling（无 animateDecay 卡半空 bug）
 * - placement：3 个 PagePaneCanvas 横向排列（cur / next / nextPlus）
 *   cur 在 -offset，next 在 -offset + W，nextPlus 在 -offset + 2W
 *
 * pageOffset 解释：state.pageOffset 在 SLIDE 模式表 X 方向 px（[0, viewportW]）。
 */
@Composable
fun SlidePageRenderer(
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
    nextPlusPageHighlightSpecs: List<ScrollHighlightDrawSpec> = emptyList(),
    curPageBookmarkCps: List<Int> = emptyList(),
    nextPageBookmarkCps: List<Int> = emptyList(),
    nextPlusPageBookmarkCps: List<Int> = emptyList(),
    /** Host 注入：zone tap → 走 animateAndCommit 平移动画；null = Host fallback 瞬切 */
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

    // Host zone tap 注入：走本 Renderer 的 animateAndCommit 让 zone tap 也有平移动画。
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
                        com.morealm.app.core.log.AppLog.info("SlideRenderer", "DIAG onDragStart viewportW=$viewportWidthPx pageOffset=${state.pageOffset}")
                        diagDragMoves = 0
                        flingJob?.cancel()
                        flingJob = null
                        velocityTracker.resetTracking()
                    },
                    onHorizontalDrag = { change, dragAmount ->
                        if (diagDragMoves < 3) {
                            com.morealm.app.core.log.AppLog.info("SlideRenderer", "DIAG onHorizontalDrag #$diagDragMoves dx=$dragAmount off=${state.pageOffset}")
                        }
                        diagDragMoves++
                        velocityTracker.addPosition(change.uptimeMillis, change.position)
                        val viewportW = viewportWidthPx.toFloat()
                        if (viewportW > 0f) {
                            // drag 期间只跟手指 + clamp 到 ±viewportW（不预 commit 翻页）。
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
                        com.morealm.app.core.log.AppLog.info("SlideRenderer", "DIAG onDragEnd moves=$diagDragMoves off=${state.pageOffset} vel=$flingVelocity")
                        flingJob?.cancel()
                        flingJob = scope.launch {
                            try {
                                val viewportW = viewportWidthPx.toFloat()
                                if (viewportW <= 0f) return@launch
                                // settle-to-edge：offset 过半 OR velocity > 800px/s commit；否则回弹
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
            val nextPlusPage = pageFactory.nextPlusPage
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
                modifier = Modifier.fillMaxSize().background(backgroundColor),
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
                modifier = Modifier.fillMaxSize().background(backgroundColor),
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
                modifier = Modifier.fillMaxSize().background(backgroundColor),
            )
        },
    ) { measurables, constraints ->
        check(measurables.size == 3) { "SlidePageRenderer expects 3 measurables (cur/next/nextPlus)" }
        val viewWidth = constraints.maxWidth
        val viewHeight = constraints.maxHeight
        val pageConstraints = Constraints.fixed(width = viewWidth, height = viewHeight.coerceAtLeast(1))
        val curPlaceable = measurables[0].measure(pageConstraints)
        val nextPlaceable = measurables[1].measure(pageConstraints)
        val nextPlusPlaceable = measurables[2].measure(pageConstraints)
        layout(viewWidth, viewHeight) {
            val offset = state.pageOffset.toInt()
            // SLIDE：cur / next / nextPlus 同步横向滑动
            curPlaceable.placeRelative(-offset, 0)
            nextPlaceable.placeRelative(-offset + viewWidth, 0)
            nextPlusPlaceable.placeRelative(-offset + 2 * viewWidth, 0)
        }
    }
}
