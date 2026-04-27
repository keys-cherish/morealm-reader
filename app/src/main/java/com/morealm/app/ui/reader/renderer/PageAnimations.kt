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

private enum class DragState { IDLE, DRAGGING_NEXT, DRAGGING_PREV }

@Composable
private fun SimulationPager(
    pagerState: PagerState,
    params: SimulationParams,
    currentDisplayPage: Int,
    modifier: Modifier = Modifier,
    pageContent: @Composable (Int) -> Unit,
) {
    val scope = rememberCoroutineScope()

    // rememberUpdatedState ensures pointerInput closures always read the
    // latest value, even when the coroutine was created in a prior composition.
    // This is the standard Compose fix for stale-closure bugs in gesture handlers.
    // (Legado avoids this entirely by using View fields instead of closures.)
    val currentParams by rememberUpdatedState(params)

    val pages = params.pages
    val pageCount = pages.size.coerceAtLeast(1)
    val displayPage = currentDisplayPage.coerceIn(0, pageCount - 1)
    val currentPage by rememberUpdatedState(displayPage)

    // 手势状态
    var dragState by remember { mutableStateOf(DragState.IDLE) }
    var turnStartDisplayIndex by remember { mutableIntStateOf(displayPage) }

    // 拖拽触摸点同步更新，松手后才交给 Animatable 执行动画。
    var touchOffset by remember { mutableStateOf(Offset.Zero) }
    var lastDragTouchOffset by remember { mutableStateOf(Offset.Zero) }
    var isAnimating by remember { mutableStateOf(false) }
    var isPointerDown by remember { mutableStateOf(false) }
    val animOffset = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    var turnJob by remember { mutableStateOf<Job?>(null) }
    var pendingDragBitmapDirection by remember { mutableStateOf<ReaderPageDirection?>(null) }

    // SimulationDrawHelper 实例
    val drawHelper = remember { SimulationDrawHelper() }

    // Bitmap 缓存：当前页和目标页
    var curBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var nextBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var bitmapWindow by remember { mutableStateOf<SimulationBitmapWindow?>(null) }

    // 尺寸
    var viewWidth by remember { mutableIntStateOf(0) }
    var viewHeight by remember { mutableIntStateOf(0) }

    fun renderPageBitmap(page: TextPage, width: Int, height: Int): Bitmap =
        renderPageToBitmap(
            width, height, currentParams.bgColor,
            page, currentParams.titlePaint, currentParams.contentPaint,
            chapterNumPaint = currentParams.chapterNumPaint,
            reuseBitmap = null, bgBitmap = currentParams.bgBitmap,
            pageInfoOverlay = currentParams.pageInfoOverlay,
        )

    // 当页面或尺寸变化时，在后台线程预渲染当前/相邻页。
    // Legado 在页面变化后提交 TextPageRender 任务，仿真翻页开始时只读取稳定的页面快照。
    LaunchedEffect(displayPage, viewWidth, viewHeight, pages, currentParams.pageInfoOverlay) {
        if (viewWidth > 0 && viewHeight > 0 && displayPage in pages.indices) {
            val width = viewWidth
            val height = viewHeight
            val pageIndex = displayPage
            val newWindow = withContext(Dispatchers.Default) {
                val currentPage = currentParams.pageForTurn(pageIndex, 0) ?: pages[pageIndex]
                SimulationBitmapWindow(
                    pageIndex = pageIndex,
                    width = width,
                    height = height,
                    prev = currentParams.pageForTurn(pageIndex, -1)?.let { renderPageBitmap(it, width, height) },
                    current = renderPageBitmap(currentPage, width, height),
                    next = currentParams.pageForTurn(pageIndex, 1)?.let { renderPageBitmap(it, width, height) },
                )
            }
            val oldWindow = bitmapWindow
            val oldCurBitmap = curBitmap
            val oldNextBitmap = nextBitmap
            val keepCurBitmap = oldCurBitmap.takeIf { dragState != DragState.IDLE }
            val keepNextBitmap = oldNextBitmap.takeIf { dragState != DragState.IDLE }
            bitmapWindow = newWindow
            if (dragState == DragState.IDLE) {
                curBitmap = null
                nextBitmap = null
            }
            oldWindow?.recycleExcept(newWindow.prev, newWindow.current, newWindow.next, keepCurBitmap, keepNextBitmap)
            recycleBitmapIfDetached(oldCurBitmap, newWindow.prev, newWindow.current, newWindow.next, keepCurBitmap, keepNextBitmap)
            recycleBitmapIfDetached(oldNextBitmap, newWindow.prev, newWindow.current, newWindow.next, keepCurBitmap, keepNextBitmap)
        }
    }

    fun prepareBitmapsForTurn(displayIndex: Int, isNext: Boolean): Boolean {
        if (viewWidth <= 0 || viewHeight <= 0) return false
        val window = bitmapWindow
        if (window?.matches(displayIndex, viewWidth, viewHeight) == true) {
            val targetBitmap = if (isNext) window.next else window.prev
            if (targetBitmap != null && !targetBitmap.isRecycled && !window.current.isRecycled) {
                curBitmap = window.current
                nextBitmap = targetBitmap
                return true
            }
        }
        AppLog.debug(
            "Reader",
            "Simulation bitmap window not ready display=$displayIndex isNext=$isNext " +
                "view=${viewWidth}x$viewHeight hasWindow=${window != null}",
        )
        return false
    }

    suspend fun renderBitmapsForTurn(displayIndex: Int, isNext: Boolean): Boolean {
        if (viewWidth <= 0 || viewHeight <= 0) return false
        val width = viewWidth
        val height = viewHeight
        val pageIndex = displayIndex
        val curPage = currentParams.pageForTurn(pageIndex, 0) ?: pages.getOrNull(pageIndex) ?: return false
        val targetPage = currentParams.pageForTurn(pageIndex, if (isNext) 1 else -1) ?: run {
            AppLog.debug("Reader", "Simulation render target missing display=$pageIndex isNext=$isNext")
            return false
        }

        val rendered = withContext(Dispatchers.Default) {
            renderPageBitmap(curPage, width, height) to renderPageBitmap(targetPage, width, height)
        }
        if (currentParams.currentDisplayIndex().coerceIn(0, pageCount - 1) != pageIndex || viewWidth != width || viewHeight != height) {
            recycleBitmapIfDetached(rendered.first)
            recycleBitmapIfDetached(rendered.second)
            AppLog.debug("Reader", "Simulation render discarded display=$pageIndex current=${currentParams.currentDisplayIndex()} view=${viewWidth}x$viewHeight expected=${width}x$height")
            return false
        }

        val oldCurBitmap = curBitmap
        val oldNextBitmap = nextBitmap
        curBitmap = rendered.first
        nextBitmap = rendered.second
        recycleBitmapIfDetached(oldCurBitmap, bitmapWindow?.prev, bitmapWindow?.current, bitmapWindow?.next)
        recycleBitmapIfDetached(oldNextBitmap, bitmapWindow?.prev, bitmapWindow?.current, bitmapWindow?.next)
        return true
    }

    fun beginDragTurn(displayIndex: Int, isNext: Boolean, touch: Offset): Boolean {
        val direction = if (isNext) ReaderPageDirection.NEXT else ReaderPageDirection.PREV
        if (!currentParams.canTurn(displayIndex, direction)) {
            AppLog.debug("Reader", "Simulation drag rejected: canTurn=false display=$displayIndex direction=$direction")
            return false
        }
        if (prepareBitmapsForTurn(displayIndex, isNext)) {
            turnStartDisplayIndex = displayIndex
            dragState = if (isNext) DragState.DRAGGING_NEXT else DragState.DRAGGING_PREV
            touchOffset = touch
            AppLog.debug("Reader", "Simulation drag started display=$displayIndex direction=$direction")
            return true
        }
        if (pendingDragBitmapDirection == null) {
            pendingDragBitmapDirection = direction
            val requestDisplay = displayIndex
            AppLog.debug("Reader", "Simulation drag waiting for bitmaps display=$requestDisplay direction=$direction")
            scope.launch {
                val ready = renderBitmapsForTurn(requestDisplay, isNext)
                if (ready && isPointerDown && dragState == DragState.IDLE && pendingDragBitmapDirection == direction &&
                    currentParams.currentDisplayIndex().coerceIn(0, pageCount - 1) == requestDisplay
                ) {
                    turnStartDisplayIndex = requestDisplay
                    dragState = if (isNext) DragState.DRAGGING_NEXT else DragState.DRAGGING_PREV
                    touchOffset = lastDragTouchOffset
                    AppLog.debug("Reader", "Simulation drag started after async bitmap render display=$requestDisplay direction=$direction")
                } else {
                    AppLog.debug("Reader", "Simulation async bitmap render did not start drag display=$requestDisplay direction=$direction ready=$ready state=$dragState")
                }
                if (pendingDragBitmapDirection == direction) {
                    pendingDragBitmapDirection = null
                }
            }
        }
        return false
    }

    fun clearTurnBitmaps() {
        val oldCurBitmap = curBitmap
        val oldNextBitmap = nextBitmap
        curBitmap = null
        nextBitmap = null
        recycleBitmapIfDetached(oldCurBitmap, bitmapWindow?.prev, bitmapWindow?.current, bitmapWindow?.next)
        recycleBitmapIfDetached(oldNextBitmap, bitmapWindow?.prev, bitmapWindow?.current, bitmapWindow?.next)
    }

    fun turnStartOffset(isNext: Boolean, tapY: Float): Offset {
        val guardedBottom = viewHeight.toFloat() - TOUCH_EDGE_GUARD
        return if (isNext) {
            val y = if (tapY > viewHeight / 2f) viewHeight.toFloat() * 0.9f else TOUCH_EDGE_GUARD
            Offset(viewWidth.toFloat() * 0.9f, y.coerceIn(TOUCH_EDGE_GUARD, guardedBottom))
        } else {
            Offset(TOUCH_EDGE_GUARD, guardedBottom)
        }
    }

    fun turnTargetOffset(isNext: Boolean, shouldComplete: Boolean, from: Offset): Offset {
        val targetX = when {
            isNext && shouldComplete -> -viewWidth.toFloat()
            isNext -> viewWidth.toFloat()
            shouldComplete -> viewWidth.toFloat()
            else -> -viewWidth.toFloat()
        }
        val targetY = if (from.y <= viewHeight / 2f) {
            TOUCH_EDGE_GUARD
        } else {
            viewHeight.toFloat() - TOUCH_EDGE_GUARD
        }
        return Offset(targetX, targetY)
    }

    // ── Programmatic page turn (tap-to-flip) ──
    // Mimics Legado HorizontalPageDelegate.nextPageByAnim / prevPageByAnim:
    // render bitmaps, set drag state, animate touch point from edge to completion.
    fun animatePageTurn(isNext: Boolean, tapY: Float) {
        if (viewWidth <= 0 || viewHeight <= 0) return
        // Abort any in-flight animation (matches Legado abortAnim() before keyTurnPage)
        turnJob?.cancel()
        if (dragState != DragState.IDLE) {
            isAnimating = false
            dragState = DragState.IDLE
            clearTurnBitmaps()
        }
        val startDisplayIndex = currentPage
        val direction = if (isNext) ReaderPageDirection.NEXT else ReaderPageDirection.PREV
        if (!currentParams.canTurn(startDisplayIndex, direction)) return

        turnJob?.cancel()
        turnJob = scope.launch {
            if (!prepareBitmapsForTurn(startDisplayIndex, isNext) && !renderBitmapsForTurn(startDisplayIndex, isNext)) {
                AppLog.debug("Reader", "Simulation tap fallback direct commit display=$startDisplayIndex direction=$direction")
                val committedPage = currentParams.onFillPage(startDisplayIndex, direction)
                if (committedPage != null) {
                    val safePage = committedPage.coerceIn(0, pageCount - 1)
                    pagerState.scrollToPage(safePage)
                    currentParams.onPageChanged(safePage)
                    AppLog.debug("Reader", "Simulation tap fallback committed=$safePage direction=$direction")
                } else {
                    AppLog.debug("Reader", "Simulation tap fallback rejected (chapter boundary) display=$startDisplayIndex direction=$direction")
                }
                return@launch
            }
            turnStartDisplayIndex = startDisplayIndex
            dragState = if (isNext) DragState.DRAGGING_NEXT else DragState.DRAGGING_PREV
            val start = turnStartOffset(isNext, tapY)
            val target = turnTargetOffset(isNext, shouldComplete = true, from = start)
            touchOffset = start
            isAnimating = true
            animOffset.snapTo(start)
            animOffset.animateTo(
                target,
                animationSpec = tween(
                    durationMillis = simulationAnimationDuration(start, target, viewWidth, viewHeight),
                    easing = LinearEasing,
                ),
            )
            val committedPage = currentParams.onFillPage(startDisplayIndex, direction)
            if (committedPage != null) {
                val safePage = committedPage.coerceIn(0, pageCount - 1)
                pagerState.scrollToPage(safePage)
                currentParams.onPageChanged(safePage)
                AppLog.debug("Reader", "Simulation tap animation committed=$safePage direction=$direction")
            } else {
                AppLog.debug("Reader", "Simulation tap animation fillPage rejected display=$startDisplayIndex direction=$direction")
            }
            touchOffset = target
            isAnimating = false
            dragState = DragState.IDLE
            clearTurnBitmaps()
        }
    }

    fun commitPendingDragIfNeeded(): Boolean {
        val direction = pendingDragBitmapDirection ?: return false
        val isNext = direction == ReaderPageDirection.NEXT
        val threshold = viewWidth * PAGE_FLIP_THRESHOLD
        val shouldComplete = if (isNext) {
            lastDragTouchOffset.x < viewWidth - threshold
        } else {
            lastDragTouchOffset.x > threshold
        }
        pendingDragBitmapDirection = null
        if (!shouldComplete) {
            AppLog.debug("Reader", "Simulation pending drag cancelled before bitmap ready direction=$direction")
            return true
        }
        val startDisplayIndex = turnStartDisplayIndex.coerceIn(0, pageCount - 1)
        val committedPage = currentParams.onFillPage(startDisplayIndex, direction)
        if (committedPage != null) {
            val safePage = committedPage.coerceIn(0, pageCount - 1)
            scope.launch { pagerState.scrollToPage(safePage) }
            currentParams.onPageChanged(safePage)
            AppLog.debug("Reader", "Simulation pending drag direct committed=$safePage direction=$direction")
        } else {
            AppLog.debug("Reader", "Simulation pending drag fillPage rejected display=$startDisplayIndex direction=$direction")
        }
        return true
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            // Tap gesture: 3-column zones (left=prev, center=menu, right=next)
            .pointerInput(Unit) {
                viewWidth = size.width
                viewHeight = size.height
                detectTapGestures(
                    onTap = { offset ->
                        val third = size.width / 3f
                        when {
                            offset.x < third -> animatePageTurn(false, offset.y)
                            offset.x > third * 2 -> animatePageTurn(true, offset.y)
                            else -> currentParams.onTapCenter()
                        }
                    },
                    onLongPress = { offset -> currentParams.onLongPress?.invoke(offset) },
                )
            }
            // Drag gesture: bezier page curl
            .pointerInput(Unit) {
                viewWidth = size.width
                viewHeight = size.height
                drawHelper.setViewSize(size.width, size.height)

                detectDragGestures(
                    onDragStart = { offset ->
                        // Matches Legado ACTION_DOWN → abortAnim() + onDown():
                        // Always abort any in-flight animation and fully reset state.
                        // Legado never gates onDown() on dragState — every touch starts clean.
                        turnJob?.cancel()
                        isAnimating = false
                        dragState = DragState.IDLE
                        isPointerDown = true
                        turnStartDisplayIndex = currentPage
                        pendingDragBitmapDirection = null
                        clearTurnBitmaps()
                        lastDragTouchOffset = Offset(
                            offset.x.coerceIn(TOUCH_EDGE_GUARD, viewWidth.toFloat() - TOUCH_EDGE_GUARD),
                            offset.y.coerceIn(TOUCH_EDGE_GUARD, viewHeight.toFloat() - TOUCH_EDGE_GUARD),
                        )
                        touchOffset = lastDragTouchOffset
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()

                        if (!isAnimating) {
                            lastDragTouchOffset = Offset(
                                change.position.x.coerceIn(TOUCH_EDGE_GUARD, viewWidth.toFloat() - TOUCH_EDGE_GUARD),
                                change.position.y.coerceIn(TOUCH_EDGE_GUARD, viewHeight.toFloat() - TOUCH_EDGE_GUARD),
                            )
                            if (dragState == DragState.IDLE) {
                                val dominantVertical = abs(dragAmount.y) > DRAG_DIRECTION_THRESHOLD &&
                                    abs(dragAmount.y) > abs(dragAmount.x)
                                val requestNext = dragAmount.x < -DRAG_DIRECTION_THRESHOLD ||
                                    (dominantVertical && dragAmount.y < 0f)
                                val requestPrev = dragAmount.x > DRAG_DIRECTION_THRESHOLD ||
                                    (dominantVertical && dragAmount.y > 0f)
                                if (requestNext) {
                                    beginDragTurn(turnStartDisplayIndex, true, lastDragTouchOffset)
                                } else if (requestPrev) {
                                    beginDragTurn(turnStartDisplayIndex, false, lastDragTouchOffset)
                                }
                            }

                            if (dragState != DragState.IDLE) {
                                touchOffset = lastDragTouchOffset
                            }
                        }
                    },
                    onDragEnd = {
                        isPointerDown = false
                        if (dragState != DragState.IDLE) {
                            val isNext = dragState == DragState.DRAGGING_NEXT
                            val start = touchOffset
                            val curX = start.x
                            val threshold = viewWidth * PAGE_FLIP_THRESHOLD

                            // 判断是否完成翻页
                            val shouldComplete = if (isNext) {
                                curX < viewWidth - threshold
                            } else {
                                curX > threshold
                            }

                            val target = turnTargetOffset(isNext, shouldComplete, start)

                            turnJob?.cancel()
                            turnJob = scope.launch {
                                isAnimating = true
                                animOffset.snapTo(start)
                                animOffset.animateTo(
                                    target,
                                    animationSpec = tween(
                                        durationMillis = simulationAnimationDuration(start, target, viewWidth, viewHeight),
                                        easing = LinearEasing,
                                    ),
                                )
                                if (shouldComplete) {
                                    val direction = if (isNext) ReaderPageDirection.NEXT else ReaderPageDirection.PREV
                                    val committedPage = currentParams.onFillPage(turnStartDisplayIndex, direction)
                                    if (committedPage != null) {
                                        val safePage = committedPage.coerceIn(0, pageCount - 1)
                                        pagerState.scrollToPage(safePage)
                                        currentParams.onPageChanged(safePage)
                                        AppLog.debug("Reader", "Simulation drag committed=$safePage direction=$direction")
                                    } else {
                                        AppLog.debug("Reader", "Simulation drag fillPage rejected display=$turnStartDisplayIndex direction=$direction")
                                    }
                                } else {
                                    AppLog.debug("Reader", "Simulation drag cancelled by threshold display=$turnStartDisplayIndex isNext=$isNext")
                                }
                                touchOffset = target
                                isAnimating = false
                                dragState = DragState.IDLE
                                clearTurnBitmaps()
                            }
                        } else {
                            commitPendingDragIfNeeded()
                        }
                    },
                    onDragCancel = {
                        isPointerDown = false
                        pendingDragBitmapDirection = null
                        if (dragState != DragState.IDLE) {
                            val isNext = dragState == DragState.DRAGGING_NEXT
                            val start = touchOffset
                            val target = turnTargetOffset(isNext, shouldComplete = false, from = start)
                            turnJob?.cancel()
                            turnJob = scope.launch {
                                isAnimating = true
                                animOffset.snapTo(start)
                                animOffset.animateTo(
                                    target,
                                    animationSpec = tween(
                                        durationMillis = simulationAnimationDuration(start, target, viewWidth, viewHeight),
                                        easing = LinearEasing,
                                    ),
                                )
                                touchOffset = target
                                isAnimating = false
                                dragState = DragState.IDLE
                                clearTurnBitmaps()
                            }
                        }
                    },
                )
            }
            .drawWithContent {
                val newWidth = size.width.toInt()
                val newHeight = size.height.toInt()
                if (viewWidth != newWidth || viewHeight != newHeight) {
                    viewWidth = newWidth
                    viewHeight = newHeight
                    drawHelper.setViewSize(viewWidth, viewHeight)
                }

                if (dragState != DragState.IDLE) {
                    // 正在拖拽或动画中 — 用 SimulationDrawHelper 绘制贝塞尔翻页
                    val isNext = dragState == DragState.DRAGGING_NEXT
                    val turnOffset = if (isAnimating) animOffset.value else touchOffset
                    val touchX = turnOffset.x
                    val touchY = turnOffset.y
                    drawHelper.setDirectionAware(touchX, touchY, isNext)
                    drawHelper.bgMeanColor = currentParams.bgMeanColor

                    drawIntoCanvas { composeCanvas ->
                        val nativeCanvas = composeCanvas.nativeCanvas
                        if (isNext) {
                            drawHelper.onDraw(nativeCanvas, curBitmap, nextBitmap)
                        } else {
                            drawHelper.onDraw(nativeCanvas, nextBitmap, curBitmap)
                        }
                    }
                } else {
                    drawContent()
                }
            }
    ) {
        pageContent(displayPage)
    }

    // 清理 bitmap
    DisposableEffect(Unit) {
        onDispose {
            turnJob?.cancel()
            bitmapWindow?.recycleExcept()
            recycleBitmapIfDetached(curBitmap)
            recycleBitmapIfDetached(nextBitmap)
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
