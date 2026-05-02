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
    /**
     * 恢复命令 token：每次 loadChapter 赋一个新的 System.nanoTime()；
     * 同章书签跳转时 resetKey (chapterIndex) 不变，但 restoreToken 变 →
     * 重新触发 pendingRestore。
     */
    restoreToken: Long = 0L,
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
    //
    // ── 跨章闪烁修复（A）── currentPageIndex 改为 remember(resetKey, restoreToken) {
    // 同步算 initialPage }，避免出生 = 0 然后 LaunchedEffect 异步设到 95 之间
    // 那一帧渲染章首页的视觉闪烁。
    //
    // 不依赖 pages 列表稳定 —— pages 在 prelayout 期间是流式增长的，但 initialPageIndex
    // 是 caller (CanvasRenderer) 提前算好的稳定值，可以直接用。后续真有需要修正
    // (比如 chapterPosition 解析出更精确的 index) 还有 LaunchedEffect 那条兜底。
    var currentPageIndex by remember(resetKey, restoreToken) {
        mutableIntStateOf(
            when {
                initialPageIndex >= 0 -> initialPageIndex
                startFromLastPage -> Int.MAX_VALUE  // 暂用 MAX，等 LaunchedEffect 在 pageCount 已知时夹回
                else -> 0
            }
        )
    }
    var lastNearBottomRequestPageCount by remember { mutableIntStateOf(0) }
    var lastNearBottomPageCount by remember { mutableIntStateOf(0) }
    // pendingRestore 以 restoreToken 为 key —— 同章书签跳转时 resetKey
    // (chapterIndex) 不变但 restoreToken 变 → 重置 pendingRestore 重新跑恢复。
    var pendingRestore by remember(resetKey, restoreToken) { mutableStateOf(true) }
    var hasUserScrolled by remember(resetKey, restoreToken) { mutableStateOf(false) }
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

    LaunchedEffect(resetKey, restoreToken, layoutCompleted) {
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
        AppLog.info(
            "BookmarkDebug",
            "ScrollRenderer RESTORE currentPageIndex=$currentPageIndex" +
                " pageOffset=$pageOffset initChapPos=$initialChapterPosition" +
                " initPageIdx=$initialPageIndex pageCount=$pageCount restoreToken=$restoreToken",
        )
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
        // 跨章过渡风暴防御：pendingBoundaryTurn 已提出 → 期间任何新请求直接吞掉，
        // 不再打日志、不再往 ViewModel 发（之前日志里同一秒内几十条 rejected 的根因
        // 就是这里丢了 "已在 pending" 的 guard 后没提前返回）。
        // 注意：之前这里有一行 AppLog.debug 与上面的注释直接矛盾，导致每帧 SKIPPED
        // 都打一条 log，单次手势刷出 100+ 行，已删除。
        if (pendingBoundaryTurn != null) {
            return true
        }
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
                // Diagnostic: include pendingBoundaryTurn + hasUserScrolled so we can
                // see when a NEXT commit fires while a PREV submit is still pending —
                // i.e. the two state machines are out of sync. See log.txt symptom
                // where 100+ "pending=PREV" SKIPPED lines preceded a commit NEXT.
                AppLog.debug(
                    "Scroll",
                    "Scroll cross-chapter commit NEXT | page=$currentPageIndex/$pageCount | scrollInto=$scrollInto" +
                        " | pendingBoundaryTurn=$pendingBoundaryTurn | hasUserScrolled=$hasUserScrolled",
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
                    "Scroll cross-chapter commit PREV | page=$currentPageIndex/$pageCount | offsetInPrev=$offsetInPrev" +
                        " | pendingBoundaryTurn=$pendingBoundaryTurn | hasUserScrolled=$hasUserScrolled",
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

    /**
     * Diagnostic-only variant of cursorOffsetFor that returns the same Offset?
     * but ALSO surfaces which short-circuit branch was taken. Pure data, no
     * logging — caller decides when/how to log so we don't spam every recompose.
     * Used by the CursorHandleTrace LaunchedEffect inside the Box body.
     */
    fun cursorReasonFor(textPos: TextPos?, startHandle: Boolean): String {
        val pos = textPos ?: return "null-input"
        val page = relativePage(pos.relativePagePos)
            ?: return "no-page-at-relPos${pos.relativePagePos}(pages=${pages.size},curIdx=$currentPageIndex)"
        val line = page.lines.getOrNull(pos.lineIndex)
            ?: return "no-line-at-${pos.lineIndex}(lines=${page.lines.size})"
        if (line.columns.isEmpty()) return "empty-columns"
        return "ok"
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
            // 滚动模式 tap 策略 —— 只区分两类：
            //  1) 点中可交互 column（图片 / 链接）→ 走 column click
            //  2) 其余区域（含原本的顶/底"翻一页"区）→ 切换 reader 菜单
            //
            // 历史上这里有顶/底 1/3 → 翻一页的 9-zone 行为，但用户在滚动模式下：
            //  · 没有"固定页"概念，calcPrevPageOffset/calcNextPageOffset 实际是
            //    按视高估算的偏移，跨段落剪掉一行就闪一下，体验割裂；
            //  · 长内容滚动天然就用拖动 / 自动滚动 / 音量键，再加顶底点翻页和
            //    fling 抢手势，触发率高且容易误触；
            //  · 横向翻页 / 仿真 / 覆盖那几种"每页内容固定"的模式仍走原 9-zone
            //    （CanvasRenderer.kt 内 SCROLL/SIMULATION 之外那条分支），不受影响。
            // 所以滚动模式直接砍掉顶/底翻页，统一回退到"点哪都切菜单"。
            .pointerInput(pageCount) {
                detectTapGestures(
                    onTap = { offset ->
                        val touched = touchRelativePage(offset.x, offset.y)
                        // column click 优先于菜单切换 —— 链接 / 图片要能正常打开。
                        if (handleColumnClick(touched?.third)) return@detectTapGestures
                        onTapCenter()
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

                // 1) Draw background OUTSIDE the offscreen layer (not affected by DstOut)
                drawIntoCanvas { composeCanvas ->
                    val canvas = composeCanvas.nativeCanvas
                    canvas.drawColor(bgColor)
                    if (bgBitmap != null && !bgBitmap.isRecycled) {
                        drawBgBitmap(canvas, bgBitmap, viewWidth.toFloat(), viewHeight.toFloat())
                    }
                }

                // 2) Draw text content in offscreen layer so DstOut only erases text
                drawContext.canvas.saveLayer(
                    androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height),
                    androidx.compose.ui.graphics.Paint()
                )

                drawIntoCanvas { composeCanvas ->
                    val canvas = composeCanvas.nativeCanvas

                    // Save and clip to viewport
                    canvas.save()
                    canvas.clipRect(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())

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

                // 3) Restore the offscreen layer (text with faded edges composited onto background)
                drawContext.canvas.restore()
            }
    ) {
        val startHandleOffset = cursorOffsetFor(selectionStart, startHandle = true)
        val endHandleOffset = cursorOffsetFor(selectionEnd, startHandle = false)
        // Diagnostic: trace why selection cursor handles aren't appearing in SCROLL mode.
        // The cursor only renders when cursorOffsetFor() returns non-null. Coupled with
        // cursorReasonFor() we get the exact short-circuit step (no-page / no-line /
        // empty-columns / ok). One log line per state change (LaunchedEffect on digest).
        val startReason = cursorReasonFor(selectionStart, startHandle = true)
        val endReason = cursorReasonFor(selectionEnd, startHandle = false)
        val cursorTraceDigest = "selStart=$selectionStart selEnd=$selectionEnd" +
            " startReason=$startReason endReason=$endReason" +
            " startOff=$startHandleOffset endOff=$endHandleOffset" +
            " curIdx=$currentPageIndex pagesSize=${pages.size}" +
            " viewH=$viewHeight pageOffset=$pageOffset"
        LaunchedEffect(cursorTraceDigest) {
            if (selectionStart != null || selectionEnd != null) {
                AppLog.debug("CursorHandleTrace", "Scroll cursor | $cursorTraceDigest")
            }
        }
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
