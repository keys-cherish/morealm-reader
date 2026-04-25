package com.morealm.app.ui.reader.renderer

import android.graphics.Bitmap
import android.text.TextPaint
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import com.morealm.app.domain.render.TextPage
import com.morealm.app.domain.render.TextPos
import com.morealm.app.domain.render.canvasrecorder.recordIfNeeded
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * Continuous vertical scroll renderer — ported from Legado's ScrollPageDelegate + ContentTextView.
 *
 * Architecture (matching Legado):
 * - A single Canvas draws the current page at `pageOffset`, plus the next page(s) below it
 * - `pageOffset` ranges from 0 (top of current page) to -pageHeight (bottom → triggers page advance)
 * - Finger drag directly adjusts `pageOffset`; fling uses exponential decay via Animatable
 * - When `pageOffset` crosses page boundaries, the "current page" index shifts and offset wraps
 *
 * This gives true pixel-level continuous scrolling, not page-snapping.
 */
@Composable
fun ScrollRenderer(
    pages: List<TextPage>,
    titlePaint: TextPaint,
    contentPaint: TextPaint,
    bgColor: Int = Color.White.toArgb(),
    bgBitmap: Bitmap? = null,
    selectionStart: TextPos? = null,
    selectionEnd: TextPos? = null,
    selectionColor: Color = DEFAULT_SELECTION_COLOR,
    aloudLineIndex: Int = -1,
    aloudColor: Color = DEFAULT_ALOUD_COLOR,
    searchResultColor: Color = DEFAULT_SEARCH_RESULT_COLOR,
    onScrollProgress: (Int) -> Unit = {},
    onNearBottom: () -> Unit = {},
    onReachedBottom: () -> Unit = {},
    onTapCenter: () -> Unit = {},
    resetKey: Int = 0,
    startFromLastPage: Boolean = false,
    initialProgress: Int = 0,
    layoutCompleted: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val pageCount = pages.size

    // Current page index (the page whose top edge is at or above the viewport top)
    var currentPageIndex by remember { mutableIntStateOf(0) }
    var lastNearBottomRequestPageCount by remember { mutableIntStateOf(0) }
    var lastNearBottomPageCount by remember { mutableIntStateOf(0) }
    var pendingRestore by remember(resetKey) { mutableStateOf(true) }
    var hasUserScrolled by remember(resetKey) { mutableStateOf(false) }

    // Pixel offset of the current page relative to viewport top.
    // 0 = page top aligned with viewport top
    // negative = page scrolled upward (content moves up)
    // Legado convention: pageOffset ∈ [0, -pageHeight]
    var pageOffset by remember { mutableFloatStateOf(0f) }

    // View dimensions
    var viewWidth by remember { mutableIntStateOf(0) }
    var viewHeight by remember { mutableIntStateOf(0) }

    // Fling animation
    val flingAnim = remember { Animatable(0f) }
    var isFling by remember { mutableStateOf(false) }

    LaunchedEffect(pages, currentPageIndex, viewWidth, viewHeight, titlePaint, contentPaint, searchResultColor) {
        if (viewWidth <= 0 || viewHeight <= 0 || pages.isEmpty()) return@LaunchedEffect
        val indices = listOf(currentPageIndex - 1, currentPageIndex, currentPageIndex + 1, currentPageIndex + 2)
            .filter { it in pages.indices }
            .distinct()
        withContext(Dispatchers.Default) {
            indices.forEach { index ->
                val page = pages[index]
                predecodePageImages(page)
                val pageHeight = page.height.toInt().coerceAtLeast(viewHeight)
                page.canvasRecorder.recordIfNeeded(viewWidth, pageHeight) { recCanvas ->
                    drawPageContent(
                        canvas = recCanvas,
                        page = page,
                        titlePaint = titlePaint,
                        contentPaint = contentPaint,
                        searchColorArgb = searchResultColor.toArgb(),
                        canvasWidth = viewWidth.toFloat(),
                    )
                }
            }
        }
    }

    LaunchedEffect(resetKey, layoutCompleted) {
        if (!pendingRestore || !layoutCompleted || pageCount <= 0) return@LaunchedEffect
        currentPageIndex = when {
            startFromLastPage -> pageCount - 1
            initialProgress > 0 -> ((initialProgress / 100f) * (pageCount - 1)).toInt().coerceIn(0, pageCount - 1)
            else -> 0
        }
        pageOffset = 0f
        lastNearBottomRequestPageCount = 0
        lastNearBottomPageCount = 0
        pendingRestore = false
    }

    LaunchedEffect(pageCount) {
        if (pageCount <= 0) return@LaunchedEffect
        if (currentPageIndex > pageCount - 1) {
            currentPageIndex = pageCount - 1
            pageOffset = 0f
        }
    }

    // Report progress only after restore/layout is stable. Do not key this effect by
    // pageCount: appending the next chapter changes the denominator and otherwise
    // causes the progress indicator to jump briefly.
    LaunchedEffect(currentPageIndex, pendingRestore, layoutCompleted) {
        if (!pendingRestore && layoutCompleted && pageCount > 0) {
            val progress = if (pageCount > 1) (currentPageIndex * 100) / (pageCount - 1) else 100
            onScrollProgress(progress.coerceIn(0, 100))
        }
    }

    // Pre-append near the bottom. This remains keyed by pageCount so very short
    // chapters such as cover/TOC can chain into following chapters naturally.
    LaunchedEffect(currentPageIndex, pageCount, pendingRestore, layoutCompleted) {
        if (!pendingRestore && currentPageIndex >= (pageCount - 2).coerceAtLeast(0) && pageCount > lastNearBottomRequestPageCount) {
            lastNearBottomRequestPageCount = pageCount
            onNearBottom()
        }
    }

    /**
     * Core scroll logic — ported from Legado ContentTextView.scroll().
     * Adjusts pageOffset by [delta] pixels and handles page boundary crossings.
     *
     * @param delta positive = scroll down (show previous content), negative = scroll up (show next content)
     */
    fun applyScroll(delta: Float) {
        if (pageCount == 0 || viewHeight <= 0) return
        if (abs(delta) > 0.5f) {
            hasUserScrolled = true
        }

        pageOffset += delta

        // Get current page height (use viewHeight as fallback)
        val curPageHeight = pages.getOrNull(currentPageIndex)?.let {
            val h = it.height.toInt()
            if (h > 0) h else viewHeight
        } ?: viewHeight

        // Boundary: scrolled past top of first page
        if (currentPageIndex == 0 && pageOffset > 0) {
            pageOffset = 0f
            return
        }

        // Boundary: scrolled past bottom of last page
        if (currentPageIndex >= pageCount - 1 && pageOffset < 0) {
            val minOffset = (viewHeight - curPageHeight).toFloat().coerceAtMost(0f)
            if (pageOffset < minOffset) {
                pageOffset = minOffset
                if (hasUserScrolled && pageCount > lastNearBottomPageCount) {
                    lastNearBottomPageCount = pageCount
                    onReachedBottom()
                }
            }
            return
        }

        // Crossed into next page (scrolled up past current page bottom)
        if (pageOffset < -curPageHeight) {
            if (currentPageIndex < pageCount - 1) {
                pageOffset += curPageHeight
                currentPageIndex++
            } else {
                pageOffset = -curPageHeight.toFloat()
            }
        }

        // Crossed into previous page (scrolled down past current page top)
        if (pageOffset > 0) {
            if (currentPageIndex > 0) {
                currentPageIndex--
                val prevPageHeight = pages.getOrNull(currentPageIndex)?.let {
                    val h = it.height.toInt()
                    if (h > 0) h else viewHeight
                } ?: viewHeight
                pageOffset -= prevPageHeight
            } else {
                pageOffset = 0f
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            // Tap gesture: center tap opens menu, top/bottom tap scrolls
            .pointerInput(pageCount) {
                detectTapGestures(
                    onTap = { offset ->
                        val thirdH = size.height / 3f
                        val thirdW = size.width / 3f
                        when {
                            // Center region — show controls
                            offset.x > thirdW && offset.x < thirdW * 2 &&
                                offset.y > thirdH && offset.y < thirdH * 2 -> {
                                onTapCenter()
                            }
                            // Top region — scroll up one "page" (keep one line visible)
                            offset.y < thirdH -> {
                                scope.launch {
                                    flingAnim.stop()
                                    applyScroll(viewHeight * 0.85f)
                                }
                            }
                            // Bottom region — scroll down one "page"
                            offset.y > thirdH * 2 -> {
                                scope.launch {
                                    flingAnim.stop()
                                    applyScroll(-viewHeight * 0.85f)
                                }
                            }
                            else -> onTapCenter()
                        }
                    },
                )
            }
            // Drag + fling gesture for continuous scrolling
            .pointerInput(pageCount) {
                val velocityTracker = VelocityTracker()

                detectVerticalDragGestures(
                    onDragStart = {
                        // Stop any ongoing fling
                        scope.launch { flingAnim.stop() }
                        isFling = false
                        velocityTracker.resetTracking()
                    },
                    onVerticalDrag = { change, dragAmount ->
                        velocityTracker.addPointerInputChange(change)
                        applyScroll(dragAmount)
                    },
                    onDragEnd = {
                        val velocity = velocityTracker.calculateVelocity().y
                        isFling = true
                        scope.launch {
                            flingAnim.snapTo(0f)
                            var prevValue = 0f
                            flingAnim.animateDecay(
                                initialVelocity = velocity,
                                animationSpec = exponentialDecay(frictionMultiplier = 1.5f),
                            ) {
                                val delta = value - prevValue
                                prevValue = value
                                applyScroll(delta)
                            }
                            isFling = false
                        }
                    },
                    onDragCancel = {
                        isFling = false
                    },
                )
            }
            // Draw: render current page + next pages with offset (like Legado ContentTextView.drawPage)
            .drawWithContent {
                if (viewWidth == 0) {
                    viewWidth = size.width.toInt()
                    viewHeight = size.height.toInt()
                }
                if (viewWidth <= 0 || viewHeight <= 0 || pageCount == 0) return@drawWithContent

                drawIntoCanvas { composeCanvas ->
                    val canvas = composeCanvas.nativeCanvas

                    // Save and clip to viewport
                    canvas.save()
                    canvas.clipRect(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())

                    // Draw background
                    canvas.drawColor(bgColor)
                    if (bgBitmap != null && !bgBitmap.isRecycled) {
                        drawBgBitmap(canvas, bgBitmap, viewWidth.toFloat(), viewHeight.toFloat())
                    }

                    // Draw pages starting from currentPageIndex at pageOffset
                    var yOffset = pageOffset
                    var pageIdx = currentPageIndex

                    while (yOffset < viewHeight && pageIdx < pageCount) {
                        val page = pages.getOrNull(pageIdx) ?: break
                        val pageH = page.height.let { if (it > 0f) it else viewHeight.toFloat() }

                        // Only draw if page is visible
                        if (yOffset + pageH > 0) {
                            canvas.save()
                            canvas.translate(0f, yOffset)

                            val hasOverlay = pageIdx == currentPageIndex &&
                                (selectionStart != null || selectionEnd != null || aloudLineIndex >= 0)
                            if (hasOverlay) {
                                drawPageContent(
                                    canvas = canvas,
                                    page = page,
                                    titlePaint = titlePaint,
                                    contentPaint = contentPaint,
                                    selectionStart = selectionStart,
                                    selectionEnd = selectionEnd,
                                    selColorArgb = selectionColor.toArgb(),
                                    aloudLineIndex = aloudLineIndex,
                                    aloudColorArgb = aloudColor.toArgb(),
                                    searchColorArgb = searchResultColor.toArgb(),
                                    canvasWidth = viewWidth.toFloat(),
                                )
                            } else {
                                drawRecordedPageContent(
                                    canvas = canvas,
                                    page = page,
                                    titlePaint = titlePaint,
                                    contentPaint = contentPaint,
                                    width = viewWidth,
                                    height = pageH.toInt().coerceAtLeast(viewHeight),
                                    searchColorArgb = searchResultColor.toArgb(),
                                    canvasWidth = viewWidth.toFloat(),
                                )
                            }

                            canvas.restore()
                        }

                        yOffset += pageH
                        pageIdx++
                    }

                    canvas.restore()
                }
            }
    )
}
