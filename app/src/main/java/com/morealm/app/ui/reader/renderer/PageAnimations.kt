package com.morealm.app.ui.reader.renderer

import android.graphics.Bitmap
import android.text.TextPaint
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
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
import com.morealm.app.domain.render.TextPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/** Cover animation constants */
private const val COVER_SHADOW_WIDTH = 30f          // 阴影渐变宽度 (px)
private const val COVER_MAX_SHADOW_ALPHA = 0.4f     // 滑入页左侧阴影最大透明度
private const val COVER_MAX_DIM_ALPHA = 0.3f        // 被覆盖页最大变暗透明度

/** Simulation (page curl) constants */
private const val DRAG_DIRECTION_THRESHOLD = 10f    // 判定拖拽方向的最小位移 (px)
private const val PAGE_FLIP_THRESHOLD = 0.35f       // 翻页完成阈值（屏幕宽度的比例）
private val EmphasizedEasing = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)
private const val SNAP_BACK_OFFSET = 10f            // 回弹动画的屏幕外偏移 (px)
private const val TOUCH_EDGE_GUARD = 0.1f           // 触摸点边界保护值，防止除零

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
    val bgColor: Int,
    val bgBitmap: Bitmap? = null,
    val bgMeanColor: Int = bgColor,
    val onPageChanged: (Int) -> Unit,
    val onNextChapter: () -> Unit,
    val onPrevChapter: () -> Unit,
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
    pageContent: @Composable (Int) -> Unit,
) {
    when (animType) {
        PageAnimType.SLIDE -> SlidePager(pagerState, modifier, pageContent)
        PageAnimType.SLIDE_VERTICAL -> VerticalSlidePager(pagerState, modifier, pageContent)
        PageAnimType.COVER -> CoverPager(pagerState, modifier, pageContent)
        PageAnimType.SIMULATION -> {
            if (simulationParams != null) {
                SimulationPager(
                    pagerState = pagerState,
                    params = simulationParams,
                    modifier = modifier,
                )
            } else {
                // Fallback if no params provided
                SlidePager(pagerState, modifier, pageContent)
            }
        }
        PageAnimType.SCROLL -> ScrollPager(pagerState, modifier, pageContent)
        PageAnimType.NONE -> {
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
    pageContent: @Composable (Int) -> Unit,
) {
    HorizontalPager(
        state = pagerState,
        modifier = modifier.fillMaxSize(),
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
    pageContent: @Composable (Int) -> Unit,
) {
    VerticalPager(
        state = pagerState,
        modifier = modifier.fillMaxSize(),
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
    pageContent: @Composable (Int) -> Unit,
) {
    HorizontalPager(
        state = pagerState,
        modifier = modifier.fillMaxSize(),
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
                    } else if (offset > 0) {
                        // Dim the page being covered
                        val dimAlpha = (offset * COVER_MAX_DIM_ALPHA).coerceIn(0f, COVER_MAX_DIM_ALPHA)
                        drawRect(
                            color = Color.Black.copy(alpha = dimAlpha),
                            size = size,
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

private enum class DragState { IDLE, DRAGGING_NEXT, DRAGGING_PREV }

@Composable
private fun SimulationPager(
    pagerState: PagerState,
    params: SimulationParams,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val pages = params.pages
    val pageCount = pages.size.coerceAtLeast(1)

    // 当前显示页索引（由 pagerState 驱动）
    var currentPage by remember { mutableIntStateOf(pagerState.currentPage) }

    // 同步 pagerState 变化
    LaunchedEffect(pagerState.currentPage) {
        currentPage = pagerState.currentPage
    }

    // 手势状态
    var dragState by remember { mutableStateOf(DragState.IDLE) }
    var startY by remember { mutableFloatStateOf(0f) }

    // 动画触摸点
    val animOffset = remember { Animatable(Offset.Zero, Offset.VectorConverter) }

    // SimulationDrawHelper 实例
    val drawHelper = remember { SimulationDrawHelper() }

    // Bitmap 缓存：当前页和目标页
    var curBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var nextBitmap by remember { mutableStateOf<Bitmap?>(null) }
    // 静态显示用的当前页 bitmap
    var staticBitmap by remember { mutableStateOf<Bitmap?>(null) }

    // 尺寸
    var viewWidth by remember { mutableIntStateOf(0) }
    var viewHeight by remember { mutableIntStateOf(0) }

    // 当页面或尺寸变化时，在后台线程渲染静态 bitmap（移植自 Legado renderThread）
    // Note: we do NOT pass staticBitmap as reuseBitmap because drawWithContent
    // on the render thread may still be reading it. Create a new bitmap and let
    // the old one be GC'd (or recycled on next recomposition).
    LaunchedEffect(currentPage, viewWidth, viewHeight, pages) {
        if (viewWidth > 0 && viewHeight > 0 && currentPage in pages.indices) {
            val newBitmap = withContext(Dispatchers.Default) {
                renderPageToBitmap(
                    viewWidth, viewHeight, params.bgColor,
                    pages[currentPage], params.titlePaint, params.contentPaint,
                    reuseBitmap = null, bgBitmap = params.bgBitmap,
                )
            }
            val oldBitmap = staticBitmap
            staticBitmap = newBitmap
            oldBitmap?.recycle()
        }
    }

    fun renderBitmapsForDrag(isNext: Boolean) {
        if (viewWidth <= 0 || viewHeight <= 0) return
        val curPage = pages.getOrNull(currentPage) ?: return
        val targetIdx = if (isNext) currentPage + 1 else currentPage - 1
        val targetPage = pages.getOrNull(targetIdx) ?: return

        // Safe: renderBitmapsForDrag is only called at drag start (IDLE → DRAGGING),
        // so drawWithContent is not yet reading these bitmaps.
        curBitmap = renderPageToBitmap(
            viewWidth, viewHeight, params.bgColor,
            curPage, params.titlePaint, params.contentPaint, curBitmap, params.bgBitmap,
        )
        nextBitmap = renderPageToBitmap(
            viewWidth, viewHeight, params.bgColor,
            targetPage, params.titlePaint, params.contentPaint, nextBitmap, params.bgBitmap,
        )
    }

    // ── Programmatic page turn (tap-to-flip) ──
    // Mimics Legado HorizontalPageDelegate.nextPageByAnim / prevPageByAnim:
    // render bitmaps, set drag state, animate touch point from edge to completion.
    fun animatePageTurn(isNext: Boolean) {
        if (viewWidth <= 0 || viewHeight <= 0) return
        if (isNext && currentPage >= pageCount - 1) { params.onNextChapter(); return }
        if (!isNext && currentPage <= 0) { params.onPrevChapter(); return }

        renderBitmapsForDrag(isNext)
        dragState = if (isNext) DragState.DRAGGING_NEXT else DragState.DRAGGING_PREV

        // Start point: right edge for next, left edge for prev (like Legado)
        val startX = if (isNext) viewWidth.toFloat() * 0.9f else viewWidth.toFloat() * 0.1f
        val startYPos = viewHeight.toFloat() * 0.5f
        drawHelper.setDirectionAware(startX, startYPos, isNext)

        val targetX = if (isNext) -viewWidth.toFloat() else viewWidth.toFloat() * 2f
        scope.launch {
            animOffset.snapTo(Offset(startX, startYPos))
            animOffset.animateTo(
                Offset(targetX, startYPos),
                animationSpec = tween(450, easing = EmphasizedEasing),
            )
            val newPage = if (isNext) currentPage + 1 else currentPage - 1
            currentPage = newPage.coerceIn(0, pageCount - 1)
            pagerState.scrollToPage(currentPage)
            params.onPageChanged(currentPage)
            dragState = DragState.IDLE
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            // Tap gesture: 3-column zones (left=prev, center=menu, right=next)
            .pointerInput(currentPage, pageCount) {
                viewWidth = size.width
                viewHeight = size.height
                detectTapGestures(
                    onTap = { offset ->
                        val third = size.width / 3f
                        when {
                            offset.x < third -> animatePageTurn(false)
                            offset.x > third * 2 -> animatePageTurn(true)
                            else -> params.onTapCenter()
                        }
                    },
                    onLongPress = { offset -> params.onLongPress?.invoke(offset) },
                )
            }
            // Drag gesture: bezier page curl
            .pointerInput(currentPage, pageCount) {
                viewWidth = size.width
                viewHeight = size.height
                drawHelper.setViewSize(size.width, size.height)

                detectDragGestures(
                    onDragStart = { offset ->
                        startY = offset.y
                        dragState = DragState.IDLE
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        val totalDragX = change.position.x - (change.position.x - dragAmount.x)

                        if (dragState == DragState.IDLE) {
                            // 判断方向
                            if (dragAmount.x < -DRAG_DIRECTION_THRESHOLD && currentPage < pageCount - 1) {
                                dragState = DragState.DRAGGING_NEXT
                                renderBitmapsForDrag(true)
                            } else if (dragAmount.x > DRAG_DIRECTION_THRESHOLD && currentPage > 0) {
                                dragState = DragState.DRAGGING_PREV
                                renderBitmapsForDrag(false)
                            }
                        }

                        if (dragState != DragState.IDLE) {
                            val isNext = dragState == DragState.DRAGGING_NEXT
                            val touchX = change.position.x.coerceIn(TOUCH_EDGE_GUARD, viewWidth.toFloat() - TOUCH_EDGE_GUARD)
                            val touchY = change.position.y.coerceIn(TOUCH_EDGE_GUARD, viewHeight.toFloat() - TOUCH_EDGE_GUARD)
                            drawHelper.setDirectionAware(touchX, touchY, isNext)
                            scope.launch {
                                animOffset.snapTo(Offset(touchX, touchY))
                            }
                        }
                    },
                    onDragEnd = {
                        if (dragState == DragState.IDLE) return@detectDragGestures
                        val isNext = dragState == DragState.DRAGGING_NEXT
                        val curX = animOffset.value.x
                        val threshold = viewWidth * PAGE_FLIP_THRESHOLD

                        // 判断是否完成翻页
                        val shouldComplete = if (isNext) {
                            curX < viewWidth - threshold
                        } else {
                            curX > threshold
                        }

                        val targetX = if (shouldComplete) {
                            if (isNext) -viewWidth.toFloat() else viewWidth.toFloat() * 2f
                        } else {
                            if (isNext) viewWidth.toFloat() + SNAP_BACK_OFFSET else -SNAP_BACK_OFFSET
                        }
                        val targetY = animOffset.value.y

                        scope.launch {
                            animOffset.animateTo(
                                Offset(targetX, targetY),
                                animationSpec = tween(500, easing = EmphasizedEasing),
                            )
                            if (shouldComplete) {
                                val newPage = if (isNext) currentPage + 1 else currentPage - 1
                                currentPage = newPage.coerceIn(0, pageCount - 1)
                                pagerState.scrollToPage(currentPage)
                                params.onPageChanged(currentPage)
                            }
                            dragState = DragState.IDLE
                        }
                    },
                    onDragCancel = {
                        if (dragState != DragState.IDLE) {
                            val isNext = dragState == DragState.DRAGGING_NEXT
                            val targetX = if (isNext) viewWidth.toFloat() + 10f else -10f
                            scope.launch {
                                animOffset.animateTo(
                                    Offset(targetX, animOffset.value.y),
                                    animationSpec = tween(500, easing = EmphasizedEasing),
                                )
                                dragState = DragState.IDLE
                            }
                        }
                    },
                )
            }
            .drawWithContent {
                if (viewWidth == 0) {
                    viewWidth = size.width.toInt()
                    viewHeight = size.height.toInt()
                    drawHelper.setViewSize(viewWidth, viewHeight)
                }

                if (dragState != DragState.IDLE) {
                    // 正在拖拽或动画中 — 用 SimulationDrawHelper 绘制贝塞尔翻页
                    val isNext = dragState == DragState.DRAGGING_NEXT
                    val touchX = animOffset.value.x.coerceIn(TOUCH_EDGE_GUARD, viewWidth.toFloat() - TOUCH_EDGE_GUARD)
                    val touchY = animOffset.value.y.coerceIn(TOUCH_EDGE_GUARD, viewHeight.toFloat() - TOUCH_EDGE_GUARD)
                    drawHelper.setDirectionAware(touchX, touchY, isNext)
                    drawHelper.bgMeanColor = params.bgMeanColor

                    drawIntoCanvas { composeCanvas ->
                        val nativeCanvas = composeCanvas.nativeCanvas
                        drawHelper.onDraw(nativeCanvas, curBitmap, nextBitmap)
                    }
                } else {
                    // 静态显示当前页
                    val bmp = staticBitmap
                    if (bmp != null && !bmp.isRecycled) {
                        drawIntoCanvas { composeCanvas ->
                            composeCanvas.nativeCanvas.drawBitmap(bmp, 0f, 0f, null)
                        }
                    } else {
                        // bitmap 还没准备好，先画背景
                        drawRect(Color(params.bgColor))
                    }
                }
            }
    )

    // 清理 bitmap
    DisposableEffect(Unit) {
        onDispose {
            curBitmap?.recycle()
            nextBitmap?.recycle()
            staticBitmap?.recycle()
        }
    }
}

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
