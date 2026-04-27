package com.morealm.app.ui.reader.renderer

import android.graphics.Bitmap
import android.text.TextPaint
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.VectorConverter
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.input.pointer.pointerInput
import com.morealm.app.core.log.AppLog
import com.morealm.app.domain.render.TextPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/** Cover animation constants */
private const val COVER_SHADOW_WIDTH = 30f          // 阴影渐变宽度 (px)
private const val COVER_MAX_SHADOW_ALPHA = 0.4f     // 滑入页左侧阴影最大透明度

/** Simulation (page curl) constants */
private const val DRAG_DIRECTION_THRESHOLD = 10f    // 判定拖拽方向的最小位移 (px)
private const val PAGE_FLIP_THRESHOLD = 0.35f       // 翻页完成阈值（屏幕宽度的比例）
private const val SIMULATION_ANIMATION_SPEED = 300  // Legado ReadView.defaultAnimationSpeed
private const val TOUCH_EDGE_GUARD = 0.1f           // 触摸点边界保护值，防止除零

private data class SimulationBitmapWindow(
    val pageIndex: Int,
    val width: Int,
    val height: Int,
    val prev: Bitmap?,
    val current: Bitmap,
    val next: Bitmap?,
) {
    fun matches(index: Int, viewWidth: Int, viewHeight: Int): Boolean =
        pageIndex == index && width == viewWidth && height == viewHeight
}

private fun recycleBitmapIfDetached(bitmap: Bitmap?, vararg keep: Bitmap?) {
    if (bitmap == null || bitmap.isRecycled || keep.any { it === bitmap }) return
    bitmap.recycle()
}

private fun SimulationBitmapWindow.recycleExcept(vararg keep: Bitmap?) {
    recycleBitmapIfDetached(prev, *keep)
    recycleBitmapIfDetached(current, *keep)
    recycleBitmapIfDetached(next, *keep)
}

private fun simulationAnimationDuration(
    start: Offset,
    target: Offset,
    viewWidth: Int,
    viewHeight: Int,
): Int {
    val dx = abs(target.x - start.x)
    val dy = abs(target.y - start.y)
    val distance = if (dx > 0f) dx else dy
    val extent = if (dx > 0f) viewWidth else viewHeight
    if (extent <= 0) return 1
    return ((SIMULATION_ANIMATION_SPEED * distance) / extent).toInt().coerceAtLeast(1)
}

/**
 * Page animation types supported by the reader.
 */
enum class PageAnimType {
    NONE,           // Instant page change
    SLIDE,          // Both pages slide together horizontally
    SLIDE_VERTICAL, // Both pages slide together vertically (上下翻页)
    COVER,          // Incoming page slides over, outgoing stays
    SIMULATION,     // Page curl effect with bezier curves
    SCROLL,         // Vertical continuous scroll
}

fun String.toPageAnimType(): PageAnimType = when (this.lowercase()) {
    "none" -> PageAnimType.NONE
    "slide" -> PageAnimType.SLIDE
    "slide_vertical", "vertical_slide", "上下翻页" -> PageAnimType.SLIDE_VERTICAL
    "cover" -> PageAnimType.COVER
    "simulation" -> PageAnimType.SIMULATION
    "scroll", "vertical" -> PageAnimType.SCROLL
    else -> PageAnimType.SLIDE
}

/**
 * 仿真翻页所需的额外参数。
 * 由 CanvasRenderer 构建并传入 AnimatedPageReader。
 */
class SimulationParams(
    val pages: List<TextPage>,
    val titlePaint: TextPaint,
    val contentPaint: TextPaint,
    val chapterNumPaint: TextPaint? = null,
    val bgColor: Int,
    val bgBitmap: Bitmap? = null,
    val bgMeanColor: Int = bgColor,
    val pageInfoOverlay: PageInfoOverlaySpec? = null,
    val pageForTurn: (displayIndex: Int, relativePos: Int) -> TextPage? = { displayIndex, relativePos ->
        pages.getOrNull(displayIndex + relativePos)
    },
    val currentDisplayIndex: () -> Int,
    val canTurn: (Int, ReaderPageDirection) -> Boolean,
    val onPageChanged: (Int) -> Unit,
    val onFillPage: (Int, ReaderPageDirection) -> Int?,
    val onTapCenter: () -> Unit = {},
    val onLongPress: ((Offset) -> Unit)? = null,
)

/**
 * Paged reader with configurable page-turn animation.
 */
@Composable
fun AnimatedPageReader(
    pagerState: PagerState,
    animType: PageAnimType,
    modifier: Modifier = Modifier,
    simulationParams: SimulationParams? = null,
    simulationDisplayPage: Int = 0,
    onPageSettled: (Int) -> Unit = {},
    pageContent: @Composable (Int) -> Unit,
) {
    when (animType) {
        PageAnimType.SLIDE -> SlidePager(pagerState, modifier, onPageSettled, pageContent)
        PageAnimType.SLIDE_VERTICAL -> VerticalSlidePager(pagerState, modifier, onPageSettled, pageContent)
        PageAnimType.COVER -> CoverPager(pagerState, modifier, onPageSettled, pageContent)
        PageAnimType.SIMULATION -> {
            if (simulationParams != null) {
                SimulationPager(
                    pagerState = pagerState,
                    params = simulationParams,
                    currentDisplayPage = simulationDisplayPage,
                    modifier = modifier,
                    pageContent = pageContent,
                )
            } else {
                // Fallback if no params provided
                SlidePager(pagerState, modifier, onPageSettled, pageContent)
            }
        }
        PageAnimType.SCROLL -> ScrollPager(pagerState, modifier, pageContent)
        PageAnimType.NONE -> {
            LaunchedEffect(pagerState.currentPage) {
                onPageSettled(pagerState.currentPage)
            }
            HorizontalPager(
                state = pagerState,
                modifier = modifier.fillMaxSize(),
                userScrollEnabled = false,
            ) { pageIndex ->
                pageContent(pageIndex)
            }
        }
    }
}

// ── Slide animation ──
// Both current and next/prev pages slide together, matching Legado's SlidePageDelegate.

@Composable
private fun SlidePager(
    pagerState: PagerState,
    modifier: Modifier = Modifier,
    onPageSettled: (Int) -> Unit = {},
    pageContent: @Composable (Int) -> Unit,
) {
    LaunchedEffect(pagerState.currentPage, pagerState.isScrollInProgress) {
        if (!pagerState.isScrollInProgress) onPageSettled(pagerState.currentPage)
    }
    HorizontalPager(
        state = pagerState,
        modifier = modifier.fillMaxSize(),
        userScrollEnabled = false,
    ) { pageIndex ->
        // Default HorizontalPager already does slide — both pages move together.
        // This matches Legado's SlidePageDelegate behavior.
        pageContent(pageIndex)
    }
}

// ── Vertical slide animation (上下翻页) ──
// Both current and next/prev pages slide together vertically.

@Composable
private fun VerticalSlidePager(
    pagerState: PagerState,
    modifier: Modifier = Modifier,
    onPageSettled: (Int) -> Unit = {},
    pageContent: @Composable (Int) -> Unit,
) {
    LaunchedEffect(pagerState.currentPage, pagerState.isScrollInProgress) {
        if (!pagerState.isScrollInProgress) onPageSettled(pagerState.currentPage)
    }
    VerticalPager(
        state = pagerState,
        modifier = modifier.fillMaxSize(),
        userScrollEnabled = false,
    ) { pageIndex ->
        pageContent(pageIndex)
    }
}

// ── Cover animation ──
// Incoming page slides over the outgoing page with a shadow gradient.
// Matches Legado's CoverPageDelegate.

@Composable
private fun CoverPager(
    pagerState: PagerState,
    modifier: Modifier = Modifier,
    onPageSettled: (Int) -> Unit = {},
    pageContent: @Composable (Int) -> Unit,
) {
    LaunchedEffect(pagerState.currentPage, pagerState.isScrollInProgress) {
        if (!pagerState.isScrollInProgress) onPageSettled(pagerState.currentPage)
    }
    HorizontalPager(
        state = pagerState,
        modifier = modifier.fillMaxSize(),
        userScrollEnabled = false,
    ) { pageIndex ->
        val pageOffset = (pagerState.currentPage - pageIndex) +
            pagerState.currentPageOffsetFraction

        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val offset = pageOffset.coerceIn(-1f, 1f)
                    when {
                        // Incoming page (swiping left → next page slides in from right)
                        // offset < 0: this is the NEXT page. Default pager position is off-screen right.
                        // We want it to slide in from the right edge, so no extra translation needed —
                        // HorizontalPager already handles this.
                        offset < 0 -> { /* default pager behavior is correct */ }

                        // Outgoing page (being covered): should stay pinned in place.
                        // HorizontalPager moves it left by default. Counteract by adding back the offset.
                        offset > 0 -> {
                            // Pager shifts this page left by (offset * width). Undo that.
                            translationX = size.width * offset
                        }
                    }
                }
                .drawWithContent {
                    drawContent()
                    val offset = pageOffset.coerceIn(-1f, 1f)
                    if (offset < 0) {
                        // Shadow on left edge of the sliding-in page
                        val shadowAlpha = (abs(offset) * COVER_MAX_SHADOW_ALPHA).coerceIn(0f, COVER_MAX_SHADOW_ALPHA)
                        drawRect(
                            brush = Brush.horizontalGradient(
                                colors = listOf(
                                    Color.Black.copy(alpha = shadowAlpha),
                                    Color.Transparent,
                                ),
                                startX = 0f,
                                endX = COVER_SHADOW_WIDTH,
                            ),
                            size = androidx.compose.ui.geometry.Size(COVER_SHADOW_WIDTH, size.height),
                        )
                    }
                }
        ) {
            pageContent(pageIndex)
        }
    }
}

// ── Simulation (page curl) animation ──
// 真正的贝塞尔曲线仿真翻页，移植自 Legado SimulationPageDelegate。
// 不使用 HorizontalPager，而是自己管理手势 + Animatable 驱动 + Bitmap 离屏渲染。

// ── Simulation (page curl) animation ──
// Uses AndroidView wrapping a native SimulationReadView to avoid
// Compose pointerInput closure staleness issues.
// See docs/page-turn-bug-analysis.md for why this approach was chosen.

@Composable
private fun SimulationPager(
    pagerState: PagerState,
    params: SimulationParams,
    currentDisplayPage: Int,
    modifier: Modifier = Modifier,
    @Suppress("UNUSED_PARAMETER") pageContent: @Composable (Int) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val pages = params.pages
    val pageCount = pages.size.coerceAtLeast(1)
    val displayPage = currentDisplayPage.coerceIn(0, pageCount - 1)

    // AndroidView wrapping the native SimulationReadView
    androidx.compose.ui.viewinterop.AndroidView(
        factory = { context ->
            SimulationReadView(context)
        },
        update = { view ->
            // Update callbacks every recomposition — View reads fields, always fresh
            view.setBackgroundColor(params.bgColor)
            view.canTurnNext = { params.canTurn(displayPage, ReaderPageDirection.NEXT) }
            view.canTurnPrev = { params.canTurn(displayPage, ReaderPageDirection.PREV) }
            view.bgMeanColor = params.bgMeanColor
            // Bitmap provider uses the View's own dimensions (not canvasRecorder which may be 0)
            view.bitmapProvider = { relativePos, w, h ->
                val page = params.pageForTurn(displayPage, relativePos)
                if (page != null && w > 0 && h > 0) {
                    renderPageToBitmap(
                        w, h, params.bgColor, page,
                        params.titlePaint, params.contentPaint,
                        chapterNumPaint = params.chapterNumPaint,
                        reuseBitmap = null, bgBitmap = params.bgBitmap,
                        pageInfoOverlay = params.pageInfoOverlay,
                    )
                } else null
            }
            view.onTapCenter = { params.onTapCenter() }
            view.onLongPress = { x, y -> params.onLongPress?.invoke(Offset(x, y)) }
            view.onTapPrev = {
                // No prev page available — do nothing or show tip
            }
            view.onTapNext = {
                // No next page available — do nothing or show tip
            }
            view.onPageTurnCompleted = { isNext ->
                val direction = if (isNext) ReaderPageDirection.NEXT else ReaderPageDirection.PREV
                val committedPage = params.onFillPage(displayPage, direction)
                if (committedPage != null) {
                    val safePage = committedPage.coerceIn(0, pageCount - 1)
                    scope.launch { pagerState.scrollToPage(safePage) }
                    params.onPageChanged(safePage)
                }
            }

            // Update idle bitmap when displayPage changes (use View's own dimensions)
            val w = view.width
            val h = view.height
            if (w > 0 && h > 0) {
                val page = params.pageForTurn(displayPage, 0)
                val idleBmp = if (page != null) renderPageToBitmap(
                    w, h, params.bgColor, page,
                    params.titlePaint, params.contentPaint,
                    chapterNumPaint = params.chapterNumPaint,
                    reuseBitmap = null, bgBitmap = params.bgBitmap,
                    pageInfoOverlay = params.pageInfoOverlay,
                ) else null
                view.setIdleBitmap(idleBmp)
            }
        },
        modifier = modifier.fillMaxSize(),
    )

}

// Old Compose-based SimulationPager removed — replaced by AndroidView + SimulationReadView.
// See docs/page-turn-bug-analysis.md for rationale.

// ── Vertical scroll animation ──
// Continuous vertical scrolling through pages using LazyColumn.
// Inspired by Legado's ScrollPageDelegate — pages are laid out vertically,
// each taking full screen height, with native fling and smooth transitions.

@Composable
private fun ScrollPager(
    pagerState: PagerState,
    modifier: Modifier = Modifier,
    pageContent: @Composable (Int) -> Unit,
) {
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = pagerState.currentPage)
    val scope = rememberCoroutineScope()

    // 将 LazyColumn 的可见页同步回 pagerState，使外部逻辑（进度、章节切换等）正常工作
    val firstVisibleIndex by remember {
        derivedStateOf { listState.firstVisibleItemIndex }
    }
    LaunchedEffect(firstVisibleIndex) {
        if (firstVisibleIndex != pagerState.currentPage) {
            pagerState.scrollToPage(firstVisibleIndex)
        }
    }

    // 当外部通过 pagerState 跳页时（如目录跳转），同步到 LazyColumn
    LaunchedEffect(pagerState.currentPage) {
        if (pagerState.currentPage != listState.firstVisibleItemIndex) {
            listState.scrollToItem(pagerState.currentPage)
        }
    }

    // 用 BoxWithConstraints 获取屏幕高度，确保每页占满全屏
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val pageHeight = maxHeight

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
        ) {
            items(pagerState.pageCount) { pageIndex ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(pageHeight)
                ) {
                    pageContent(pageIndex)
                }
            }
        }
    }
}
