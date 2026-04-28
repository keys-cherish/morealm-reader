package com.morealm.app.ui.reader.renderer

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.text.TextPaint
import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import com.morealm.app.core.log.AppLog
import com.morealm.app.domain.render.BaseColumn
import com.morealm.app.domain.render.ButtonColumn
import com.morealm.app.domain.render.ImageColumn
import com.morealm.app.domain.render.ReviewColumn
import com.morealm.app.domain.render.TextHtmlColumn
import com.morealm.app.domain.render.TextLine
import com.morealm.app.domain.render.TextPage
import com.morealm.app.domain.render.TextPos
import com.morealm.app.domain.render.canvasrecorder.recordIfNeeded
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

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
    nextChapterPages: List<TextPage> = emptyList(),
    prevChapterPages: List<TextPage> = emptyList(),
    initialPageOffset: Float = 0f,
    onChapterCommit: (direction: ReaderPageDirection, scrollIntoOffset: Float) -> Unit = { _, _ -> },
    titlePaint: TextPaint,
    contentPaint: TextPaint,
    chapterNumPaint: TextPaint? = null,
    bgColor: Int = Color.White.toArgb(),
    bgBitmap: Bitmap? = null,
    selectionStart: TextPos? = null,
    selectionEnd: TextPos? = null,
    selectionColor: Color = DEFAULT_SELECTION_COLOR,
    aloudLineIndex: Int = -1,
    aloudColor: Color = DEFAULT_ALOUD_COLOR,
    searchResultColor: Color = DEFAULT_SEARCH_RESULT_COLOR,
    onScrollProgress: (Int) -> Unit = {},
    onScrollPageChanged: (pageIndex: Int, page: TextPage) -> Unit = { _, _ -> },
    onNearBottom: () -> Unit = {},
    onReachedBottom: () -> Unit = {},
    onBoundaryPageTurn: (direction: ReaderPageDirection, pageIndex: Int) -> Boolean = { _, _ -> false },
    onTapCenter: () -> Unit = {},
    onImageClick: (String) -> Unit = {},
    onLongPressText: (page: TextPage, textPos: TextPos, offset: Offset) -> Unit = { _, _, _ -> },
    onReadAloudVisiblePosition: (page: TextPage, line: TextLine) -> Unit = { _, _ -> },
    onSelectionStartMove: (page: TextPage, textPos: TextPos) -> Unit = { _, _ -> },
    onSelectionEndMove: (page: TextPage, textPos: TextPos) -> Unit = { _, _ -> },
    onRelativePagesChanged: (Map<Int, TextPage>) -> Unit = {},
    resetKey: Int = 0,
    startFromLastPage: Boolean = false,
    initialProgress: Int = 0,
    initialChapterPosition: Int = 0,
    initialPageIndex: Int = -1,
    pageTurnCommand: ReaderPageDirection? = null,
    onPageTurnCommandConsumed: () -> Unit = {},
    autoScrollDelta: Int = 0,
    onAutoScrollDeltaConsumed: () -> Unit = {},
    layoutCompleted: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val pageCount = pages.size

    fun handleColumnClick(column: BaseColumn?): Boolean {
        return when (column) {
            is ImageColumn -> {
                onImageClick(column.src)
                true
            }
            is TextHtmlColumn -> {
                val link = column.linkUrl?.takeIf { it.isNotBlank() } ?: return false
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)))
                }.onFailure {
                    Toast.makeText(context, "无法打开链接", Toast.LENGTH_SHORT).show()
                }
                true
            }
            is ButtonColumn, is ReviewColumn -> {
                Toast.makeText(context, "暂不支持该内容操作", Toast.LENGTH_SHORT).show()
                true
            }
            else -> false
        }
    }

    // Current page index (the page whose top edge is at or above the viewport top)
    var currentPageIndex by remember { mutableIntStateOf(0) }
    var lastNearBottomRequestPageCount by remember { mutableIntStateOf(0) }
    var lastNearBottomPageCount by remember { mutableIntStateOf(0) }
    var pendingRestore by remember(resetKey) { mutableStateOf(true) }
    var hasUserScrolled by remember(resetKey) { mutableStateOf(false) }
    var pendingBoundaryTurn by remember(resetKey) { mutableStateOf<ReaderPageDirection?>(null) }

    // Pixel offset of the current page relative to viewport top.
    // 0 = page top aligned with viewport top
    // negative = page scrolled upward (content moves up)
    // Legado convention: pageOffset ∈ [0, -pageHeight]
    var pageOffset by remember { mutableFloatStateOf(0f) }

    data class VisibleLine(
        val pageIndex: Int,
        val page: TextPage,
        val lineTop: Float,
        val lineBottom: Float,
    )

    data class VisibleReadAloudLine(
        val pageIndex: Int,
        val page: TextPage,
        val line: TextLine,
    )

    // View dimensions
    var viewWidth by remember { mutableIntStateOf(0) }
    var viewHeight by remember { mutableIntStateOf(0) }

    // Fling animation
    val flingAnim = remember { Animatable(0f) }
    var isFling by remember { mutableStateOf(false) }
    val scrollDelegateState = remember(resetKey) { ReaderPageDelegateState() }

    fun pageIndexForChapterPosition(chapterPosition: Int): Int {
        if (chapterPosition <= 0) return -1
        var lastSameChapter = -1
        for (index in pages.indices) {
            val page = pages[index]
            if (page.chapterIndex != resetKey) continue
            lastSameChapter = index
            val pageEnd = page.chapterPosition + page.charSize
            if (chapterPosition < pageEnd) return index
        }
        return lastSameChapter
    }

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
                        chapterNumPaint = chapterNumPaint,
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
            initialChapterPosition > 0 -> pageIndexForChapterPosition(initialChapterPosition)
                .takeIf { it >= 0 }
                ?: initialPageIndex.coerceIn(0, pageCount - 1)
            initialPageIndex >= 0 -> initialPageIndex.coerceIn(0, pageCount - 1)
            startFromLastPage -> pageCount - 1
            initialProgress > 0 -> ((initialProgress / 100f) * (pageCount - 1)).roundToInt().coerceIn(0, pageCount - 1)
            else -> 0
        }
        pageOffset = if (initialPageOffset != 0f) initialPageOffset else 0f
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
    fun pageHeight(index: Int): Float {
        val height = pages.getOrNull(index)?.height ?: 0f
        return if (height > 0f) height else viewHeight.toFloat()
    }

    fun relativeOffsetFor(index: Int): Float {
        var offset = pageOffset
        var pageIndex = currentPageIndex
        while (pageIndex < index) {
            offset += pageHeight(pageIndex)
            pageIndex++
        }
        while (pageIndex > index) {
            pageIndex--
            offset -= pageHeight(pageIndex)
        }
        return offset
    }

    fun isLineVisible(lineTop: Float, lineBottom: Float, visibleTop: Float, visibleBottom: Float): Boolean {
        val height = lineBottom - lineTop
        if (height <= 0f) return false
        return when {
            lineTop >= visibleTop && lineBottom <= visibleBottom -> true
            lineTop <= visibleTop && lineBottom >= visibleBottom -> true
            lineTop < visibleTop && lineBottom > visibleTop && lineBottom < visibleBottom ->
                (lineBottom - visibleTop) / height > 0.6f
            lineTop > visibleTop && lineTop < visibleBottom && lineBottom > visibleBottom ->
                (visibleBottom - lineTop) / height > 0.6f
            else -> false
        }
    }

    fun getVisibleLines(): List<VisibleLine> {
        if (pageCount == 0 || viewHeight <= 0) return emptyList()
        val result = arrayListOf<VisibleLine>()
        var pageIndex = currentPageIndex.coerceIn(0, pageCount - 1)
        var relativeOffset = relativeOffsetFor(pageIndex)
        while (pageIndex < pageCount && relativeOffset < viewHeight) {
            val page = pages[pageIndex]
            val visibleTop = page.paddingTop.toFloat()
            val visibleBottom = viewHeight.toFloat()
            for (line in page.lines) {
                val top = line.lineTop + page.paddingTop + relativeOffset
                val bottom = line.lineBottom + page.paddingTop + relativeOffset
                if (isLineVisible(top, bottom, visibleTop, visibleBottom)) {
                    result.add(VisibleLine(pageIndex, page, top, bottom))
                }
            }
            relativeOffset += pageHeight(pageIndex)
            pageIndex++
        }
        return result
    }

    fun getCurVisiblePageIndex(): Int {
        return getVisibleLines().firstOrNull()?.pageIndex ?: currentPageIndex.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
    }

    fun relativePage(relativePos: Int): TextPage? {
        return pages.getOrNull(currentPageIndex + relativePos)
    }

    fun relativeOffset(relativePos: Int): Float {
        return relativeOffsetFor(currentPageIndex + relativePos)
    }

    LaunchedEffect(pages, currentPageIndex) {
        onRelativePagesChanged((0..2).mapNotNull { relativePos ->
            relativePage(relativePos)?.let { page -> relativePos to page }
        }.toMap())
    }

    fun getReadAloudPos(): VisibleReadAloudLine? {
        for (relativePos in 0..2) {
            val pageIndex = currentPageIndex + relativePos
            val page = pages.getOrNull(pageIndex) ?: continue
            val relativeOffset = relativeOffset(relativePos)
            if (relativePos > 0 && relativeOffset >= viewHeight) break
            for (line in page.lines) {
                val top = line.lineTop + page.paddingTop + relativeOffset
                val bottom = line.lineBottom + page.paddingTop + relativeOffset
                if (line.isReadAloud && isLineVisible(top, bottom, page.paddingTop.toFloat(), viewHeight.toFloat())) {
                    return VisibleReadAloudLine(pageIndex, page, line)
                }
            }
        }
        return null
    }

    fun submitBoundaryTurn(direction: ReaderPageDirection, reason: String): Boolean {
        if (pendingBoundaryTurn != null) return true
        AppLog.debug(
            "Scroll",
            "Scroll boundary turn request direction=$direction reason=$reason " +
                "page=$currentPageIndex/$pageCount offset=$pageOffset layoutCompleted=$layoutCompleted",
        )
        val accepted = onBoundaryPageTurn(direction, currentPageIndex.coerceIn(0, (pageCount - 1).coerceAtLeast(0)))
        if (accepted) {
            pendingBoundaryTurn = direction
            scrollDelegateState.abortAnim()
            AppLog.debug("PageTurn", "Scroll boundary turn accepted direction=$direction")
        } else {
            AppLog.debug("PageTurn", "Scroll boundary turn rejected direction=$direction reason=$reason")
        }
        return accepted
    }

    fun clampAtFirstPage(): Boolean {
        if (currentPageIndex == 0 && pageOffset > 0) {
            // When prev chapter pages are available, allow scrolling up into them
            if (prevChapterPages.isNotEmpty()) return false

            if (hasUserScrolled && submitBoundaryTurn(ReaderPageDirection.PREV, "first-page-top")) {
                pageOffset = 0f
                return true
            }
            pageOffset = 0f
            scrollDelegateState.abortAnim()
            return true
        }
        return false
    }

    fun clampAtLastPage(): Boolean {
        if (currentPageIndex < pageCount - 1 || pageOffset >= 0) return false
        val curPageHeight = pageHeight(currentPageIndex)
        val minOffset = (viewHeight - curPageHeight).toFloat().coerceAtMost(0f)

        // When next chapter pages are available, allow scrolling past current chapter
        // boundary — the draw loop will render nextChapterPages seamlessly below.
        if (nextChapterPages.isNotEmpty() && pageOffset < minOffset) return false

        if (pageOffset < minOffset) {
            if (hasUserScrolled && submitBoundaryTurn(ReaderPageDirection.NEXT, "last-page-bottom")) {
                pageOffset = minOffset
                return true
            }
            pageOffset = minOffset
            scrollDelegateState.abortAnim()
            if (hasUserScrolled) {
                lastNearBottomPageCount = pageCount
                AppLog.debug(
                    "Scroll",
                    "Scroll reached true bottom page=$currentPageIndex/$pageCount " +
                        "offset=$pageOffset minOffset=$minOffset height=$curPageHeight viewHeight=$viewHeight",
                )
                onReachedBottom()
            }
        }
        return true
    }

    fun moveToPrevPageByScroll(): Boolean {
        if (currentPageIndex <= 0) {
            if (hasUserScrolled && submitBoundaryTurn(ReaderPageDirection.PREV, "prev-cross-boundary")) {
                pageOffset = 0f
                return false
            }
            pageOffset = 0f
            scrollDelegateState.abortAnim()
            return false
        }
        currentPageIndex--
        pageOffset -= pageHeight(currentPageIndex)
        return true
    }

    fun moveToNextPageByScroll(): Boolean {
        if (currentPageIndex >= pageCount - 1) {
            // When next chapter pages are available, don't stop — let the draw loop
            // render cross-chapter content. The chapter commit happens in a separate
            // LaunchedEffect when the viewport majority shows next chapter content.
            if (nextChapterPages.isNotEmpty()) return false

            val currentHeight = pageHeight(currentPageIndex)
            if (hasUserScrolled && submitBoundaryTurn(ReaderPageDirection.NEXT, "next-cross-boundary")) {
                pageOffset = (viewHeight - currentHeight).toFloat().coerceAtMost(0f)
                return false
            }
            pageOffset = -currentHeight
            scrollDelegateState.abortAnim()
            return false
        }
        val currentHeight = pageHeight(currentPageIndex)
        currentPageIndex++
        pageOffset += currentHeight
        return true
    }

    LaunchedEffect(currentPageIndex, pageOffset, pendingRestore, layoutCompleted) {
        if (!pendingRestore && layoutCompleted && pageCount > 0) {
            val visiblePageIndex = getCurVisiblePageIndex()
            val curPageHeight = pageHeight(visiblePageIndex)
            val visiblePageOffset = relativeOffsetFor(visiblePageIndex)
            val visiblePageFraction = if (curPageHeight > 0) (-visiblePageOffset / curPageHeight).coerceIn(0f, 1f) else 0f
            val currentPage = pages.getOrNull(visiblePageIndex)
            val chapterPageSize = currentPage?.pageSize?.takeIf { it > 1 }
            val chapterPageIndex = currentPage?.index ?: visiblePageIndex
            val progress = if (chapterPageSize != null) {
                ((chapterPageIndex + visiblePageFraction) * 100f / (chapterPageSize - 1)).roundToInt()
            } else if (pageCount > 1) {
                ((visiblePageIndex + visiblePageFraction) * 100f / (pageCount - 1)).roundToInt()
            } else 100
            onScrollProgress(progress.coerceIn(0, 100))
        }
    }

    LaunchedEffect(currentPageIndex, pageOffset, pendingRestore, layoutCompleted) {
        if (!pendingRestore && layoutCompleted && pageCount > 0) {
            val visiblePageIndex = getCurVisiblePageIndex()
            pages.getOrNull(visiblePageIndex)?.let { page ->
                onScrollPageChanged(visiblePageIndex, page)
            }
        }
    }

    LaunchedEffect(currentPageIndex, pageOffset, pendingRestore, layoutCompleted, pages) {
        if (!pendingRestore && layoutCompleted && pageCount > 0) {
            getReadAloudPos()?.let { onReadAloudVisiblePosition(it.page, it.line) }
        }
    }

    // Pre-append near the bottom. This remains keyed by pageCount so very short
    // chapters such as cover/TOC can chain into following chapters naturally.
    LaunchedEffect(currentPageIndex, pageCount, pendingRestore, layoutCompleted) {
        if (!pendingRestore && currentPageIndex >= (pageCount - 2).coerceAtLeast(0) && pageCount > lastNearBottomRequestPageCount) {
            lastNearBottomRequestPageCount = pageCount
            AppLog.debug(
                "Scroll",
                "Scroll near bottom page=$currentPageIndex/$pageCount layoutCompleted=$layoutCompleted",
            )
            onNearBottom()
        }
    }

    // Cross-chapter commit detection: when the viewport majority shows adjacent
    // chapter content, commit the chapter shift so the ViewModel advances.
    LaunchedEffect(currentPageIndex, pageOffset, pendingRestore, layoutCompleted, nextChapterPages.size, prevChapterPages.size) {
        if (pendingRestore || !layoutCompleted || pageCount <= 0 || viewHeight <= 0) return@LaunchedEffect
        // Forward commit: at last page, scrolled past 70% of it into next chapter
        if (currentPageIndex >= pageCount - 1 && nextChapterPages.isNotEmpty()) {
            val lastPageBottom = pageOffset + pageHeight(currentPageIndex)
            if (lastPageBottom < viewHeight * 0.3f) {
                val scrollInto = -(pageOffset + pageHeight(currentPageIndex))
                AppLog.debug(
                    "Scroll",
                    "Scroll cross-chapter commit NEXT | page=$currentPageIndex/$pageCount | scrollInto=$scrollInto",
                )
                onChapterCommit(ReaderPageDirection.NEXT, scrollInto.coerceAtLeast(0f))
            }
        }
        // Backward commit: at first page, scrolled up past 70% into prev chapter
        if (currentPageIndex == 0 && prevChapterPages.isNotEmpty() && pageOffset > 0) {
            val totalPrevHeight = prevChapterPages.sumOf { it.height.toDouble() }.toFloat()
            val scrollIntoPrev = pageOffset
            if (scrollIntoPrev > viewHeight * 0.7f && scrollIntoPrev < totalPrevHeight) {
                val offsetInPrev = totalPrevHeight - scrollIntoPrev
                AppLog.debug(
                    "Scroll",
                    "Scroll cross-chapter commit PREV | page=$currentPageIndex/$pageCount | offsetInPrev=$offsetInPrev",
                )
                onChapterCommit(ReaderPageDirection.PREV, -offsetInPrev)
            }
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

        if (clampAtFirstPage()) return
        if (clampAtLastPage()) return

        // Crossed into next page (scrolled up past current page bottom). Use a loop so
        // fast flings have the same final state as Legado's repeated scroll() calls.
        while (pageOffset < -pageHeight(currentPageIndex)) {
            if (!moveToNextPageByScroll()) break
        }
        clampAtLastPage()

        // Crossed into previous page (scrolled down past current page top). Also loop
        // for high velocity reverse flings.
        while (pageOffset > 0) {
            if (!moveToPrevPageByScroll()) break
        }
        clampAtFirstPage()

    }

    fun calcNextPageOffset(): Float {
        val lastLineTop = getVisibleLines().lastOrNull()?.lineTop ?: return -viewHeight.toFloat()
        val visiblePage = pages.getOrNull(getCurVisiblePageIndex()) ?: return -viewHeight.toFloat()
        val offset = (lastLineTop - visiblePage.paddingTop).coerceAtLeast(0f)
        return -offset
    }

    fun calcPrevPageOffset(): Float {
        val firstVisibleLine = getVisibleLines().firstOrNull() ?: return viewHeight.toFloat()
        val visiblePage = firstVisibleLine.page
        val offset = viewHeight - (firstVisibleLine.lineBottom - visiblePage.paddingTop)
        return offset
    }

    fun touchRelativePage(x: Float, y: Float): Triple<TextPage, TextPos, BaseColumn?>? {
        for (relativePos in 0..2) {
            val page = relativePage(relativePos) ?: continue
            val relativeOffset = relativeOffset(relativePos)
            if (relativePos > 0 && relativeOffset >= viewHeight) return null
            for ((lineIndex, line) in page.lines.withIndex()) {
                val localY = y - page.paddingTop - relativeOffset
                if (line.isTouchY(localY)) {
                    val exactColumnIndex = line.columnAtX(x)
                    val columnIndex = if (exactColumnIndex >= 0) {
                        exactColumnIndex
                    } else {
                        line.columns.indices.minByOrNull { index ->
                            val column = line.columns[index]
                            abs((column.start + column.end) / 2f - x)
                        } ?: return null
                    }
                    val column = line.columns.getOrNull(columnIndex)
                    return Triple(page, TextPos(relativePos, lineIndex, columnIndex), column)
                }
            }
        }
        return null
    }

    fun touchRelativePageRough(x: Float, y: Float): Pair<TextPage, TextPos>? {
        for (relativePos in 0..2) {
            val page = relativePage(relativePos) ?: continue
            val relativeOffset = relativeOffset(relativePos)
            if (relativePos > 0 && relativeOffset >= viewHeight) return null
            val textPos = hitTestPageRough(
                page = page,
                x = x,
                y = y - relativeOffset,
                relativePagePos = relativePos,
            ) ?: continue
            return page to textPos
        }
        return null
    }

    fun selectionForRelativePage(relativePos: Int): Pair<TextPos?, TextPos?> {
        val start = selectionStart?.takeIf { it.relativePagePos == relativePos }
            ?.let { TextPos(0, it.lineIndex, it.columnIndex) }
        val end = selectionEnd?.takeIf { it.relativePagePos == relativePos }
            ?.let { TextPos(0, it.lineIndex, it.columnIndex) }
        return start to end
    }

    fun cursorOffsetFor(textPos: TextPos?, startHandle: Boolean): Offset? {
        val pos = textPos ?: return null
        val page = relativePage(pos.relativePagePos) ?: return null
        val line = page.lines.getOrNull(pos.lineIndex) ?: return null
        if (line.columns.isEmpty()) return null
        val columnIndex = pos.columnIndex.coerceIn(0, line.columns.lastIndex)
        val column = line.columns[columnIndex]
        val x = when {
            startHandle && pos.columnIndex < line.columns.size -> column.start
            startHandle -> column.end
            pos.columnIndex >= 0 -> column.end
            else -> column.start
        }
        val y = line.lineBottom + page.paddingTop + relativeOffset(pos.relativePagePos)
        return Offset(x, y)
    }

    LaunchedEffect(pageTurnCommand, pendingRestore, layoutCompleted, viewHeight) {
        val direction = pageTurnCommand ?: return@LaunchedEffect
        if (pendingRestore || !layoutCompleted || viewHeight <= 0) return@LaunchedEffect
        onPageTurnCommandConsumed()
        flingAnim.stop()
        if (!scrollDelegateState.keyTurnPage(direction)) return@LaunchedEffect
        when (direction) {
            ReaderPageDirection.NEXT -> applyScroll(calcNextPageOffset())
            ReaderPageDirection.PREV -> applyScroll(calcPrevPageOffset())
            ReaderPageDirection.NONE -> Unit
        }
        scrollDelegateState.stopScroll()
    }

    LaunchedEffect(autoScrollDelta, pendingRestore, layoutCompleted, viewHeight) {
        if (pendingRestore || !layoutCompleted || viewHeight <= 0 || autoScrollDelta == 0) return@LaunchedEffect
        applyScroll(-autoScrollDelta.toFloat())
        onAutoScrollDeltaConsumed()
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
                        if (offset.x > thirdW && offset.x < thirdW * 2 &&
                            offset.y > thirdH && offset.y < thirdH * 2
                        ) {
                            onTapCenter()
                            return@detectTapGestures
                        }
                        when {
                            // Top region — scroll up one "page" (keep one line visible)
                            offset.y < thirdH -> {
                                scope.launch {
                                    flingAnim.stop()
                                    scrollDelegateState.abortAnim()
                                    applyScroll(calcPrevPageOffset())
                                    scrollDelegateState.stopScroll()
                                }
                            }
                            // Bottom region — scroll down one "page"
                            offset.y > thirdH * 2 -> {
                                scope.launch {
                                    flingAnim.stop()
                                    scrollDelegateState.abortAnim()
                                    applyScroll(calcNextPageOffset())
                                    scrollDelegateState.stopScroll()
                                }
                            }
                            else -> {
                                val touched = touchRelativePage(offset.x, offset.y)
                                if (!handleColumnClick(touched?.third)) {
                                    onTapCenter()
                                }
                            }
                        }
                    },
                    onLongPress = { offset ->
                        val touched = touchRelativePage(offset.x, offset.y) ?: return@detectTapGestures
                        val column = touched.third
                        if (column is ImageColumn) {
                            onImageClick(column.src)
                        } else {
                            onLongPressText(touched.first, touched.second, offset)
                        }
                    },
                )
            }
            // Drag + fling gesture for continuous scrolling
            .pointerInput(pageCount) {
                val velocityTracker = VelocityTracker()
                var dragAxis = 0 // 0 unknown, 1 vertical scroll, -1 horizontal ignore
                var totalDragX = 0f
                var totalDragY = 0f
                val slop = viewConfiguration.touchSlop

                detectDragGestures(
                    onDragStart = {
                        // Stop any ongoing fling
                        scope.launch { flingAnim.stop() }
                        scrollDelegateState.onDown()
                        isFling = false
                        dragAxis = 0
                        totalDragX = 0f
                        totalDragY = 0f
                        velocityTracker.resetTracking()
                    },
                    onDrag = { change, dragAmount ->
                        totalDragX += dragAmount.x
                        totalDragY += dragAmount.y
                        if (dragAxis == 0) {
                            val absX = abs(totalDragX)
                            val absY = abs(totalDragY)
                            if (absX > slop || absY > slop) {
                                dragAxis = if (absY > absX * 1.2f) 1 else -1
                            }
                        }
                        if (dragAxis == 1) {
                            change.consume()
                            velocityTracker.addPointerInputChange(change)
                            scrollDelegateState.markMoved()
                            applyScroll(dragAmount.y)
                        } else if (dragAxis == -1) {
                            change.consume()
                        }
                    },
                    onDragEnd = {
                        if (dragAxis != 1) {
                            scrollDelegateState.stopScroll()
                            return@detectDragGestures
                        }
                        val velocity = velocityTracker.calculateVelocity().y
                        isFling = true
                        scrollDelegateState.startAnim(if (velocity < 0f) ReaderPageDirection.NEXT else ReaderPageDirection.PREV)
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
                            scrollDelegateState.stopScroll()
                        }
                    },
                    onDragCancel = {
                        isFling = false
                        scrollDelegateState.abortAnim()
                    },
                )
            }
            // Offscreen compositing required for BlendMode.DstOut fade edges
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            // Draw: render current page + next pages with offset (like Legado ContentTextView.drawPage)
            .drawWithContent {
                val newWidth = size.width.toInt()
                val newHeight = size.height.toInt()
                if (newWidth != viewWidth || newHeight != viewHeight) {
                    viewWidth = newWidth
                    viewHeight = newHeight
                    pageOffset = pageOffset.coerceIn(-pageHeight(currentPageIndex), 0f)
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

                            val relativePos = pageIdx - currentPageIndex
                            val (relativeSelectionStart, relativeSelectionEnd) = selectionForRelativePage(relativePos)
                            val hasOverlay = relativeSelectionStart != null ||
                                relativeSelectionEnd != null ||
                                (relativePos == 0 && aloudLineIndex >= 0) ||
                                page.lines.any { it.isReadAloud }
                            if (hasOverlay) {
                                drawPageContent(
                                    canvas = canvas,
                                    page = page,
                                    titlePaint = titlePaint,
                                    contentPaint = contentPaint,
                                    chapterNumPaint = chapterNumPaint,
                                    selectionStart = relativeSelectionStart,
                                    selectionEnd = relativeSelectionEnd,
                                    selColorArgb = selectionColor.toArgb(),
                                    aloudLineIndex = if (relativePos == 0) aloudLineIndex else -1,
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
                                    chapterNumPaint = chapterNumPaint,
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

                    // Cross-chapter rendering: draw next chapter pages below current chapter
                    if (nextChapterPages.isNotEmpty() && yOffset < viewHeight) {
                        for (nextPage in nextChapterPages) {
                            if (yOffset >= viewHeight) break
                            val nextPageH = nextPage.height.let { if (it > 0f) it else viewHeight.toFloat() }
                            if (yOffset + nextPageH > 0) {
                                canvas.save()
                                canvas.translate(0f, yOffset)
                                drawPageContent(
                                    canvas = canvas,
                                    page = nextPage,
                                    titlePaint = titlePaint,
                                    contentPaint = contentPaint,
                                    chapterNumPaint = chapterNumPaint,
                                    searchColorArgb = searchResultColor.toArgb(),
                                    canvasWidth = viewWidth.toFloat(),
                                )
                                canvas.restore()
                            }
                            yOffset += nextPageH
                        }
                    }

                    // Cross-chapter rendering: draw prev chapter pages above current chapter
                    if (prevChapterPages.isNotEmpty() && pageOffset > 0) {
                        var prevYOffset = pageOffset
                        for (prevPage in prevChapterPages.asReversed()) {
                            val prevPageH = prevPage.height.let { if (it > 0f) it else viewHeight.toFloat() }
                            prevYOffset -= prevPageH
                            if (prevYOffset + prevPageH <= 0) break
                            if (prevYOffset < viewHeight) {
                                canvas.save()
                                canvas.translate(0f, prevYOffset)
                                drawPageContent(
                                    canvas = canvas,
                                    page = prevPage,
                                    titlePaint = titlePaint,
                                    contentPaint = contentPaint,
                                    chapterNumPaint = chapterNumPaint,
                                    searchColorArgb = searchResultColor.toArgb(),
                                    canvasWidth = viewWidth.toFloat(),
                                )
                                canvas.restore()
                            }
                        }
                    }

                    canvas.restore()
                }

                // Alpha fade edges: text fades out at top/bottom, background shows through
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
    ) {
        val startHandleOffset = cursorOffsetFor(selectionStart, startHandle = true)
        val endHandleOffset = cursorOffsetFor(selectionEnd, startHandle = false)
        if (startHandleOffset != null) {
            CursorHandle(position = startHandleOffset, onDrag = { offset ->
                touchRelativePageRough(offset.x, offset.y)?.let { touched ->
                    onSelectionStartMove(touched.first, touched.second)
                }
            })
        }
        if (endHandleOffset != null) {
            CursorHandle(position = endHandleOffset, onDrag = { offset ->
                touchRelativePageRough(offset.x, offset.y)?.let { touched ->
                    onSelectionEndMove(touched.first, touched.second)
                }
            })
        }
    }
}
