package com.morealm.app.ui.reader.renderer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.net.Uri
import android.os.BatteryManager
import android.text.TextPaint
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.asPaddingValues
import com.morealm.app.core.log.AppLog
import com.morealm.app.domain.entity.ReaderStyle
import com.morealm.app.domain.render.*
import com.morealm.app.presentation.reader.ReaderSearchController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private const val VERTICAL_SWIPE_PAGE_THRESHOLD_RATIO = 0.08f
private const val PAGE_DELEGATE_DRAG_AXIS_RATIO = 1.2f

/**
 * Full-featured Canvas-based text reader.
 *
 * Rendering pipeline:
 *   content → ChapterProvider (layout engine with justification + ZhLayout)
 *           → TextChapter (pre-computed pages with positioned columns)
 *           → PageCanvas (native Canvas drawing per page)
 *           → AnimatedPageReader / ScrollRenderer (page animation)
 *
 * Features:
 * - Full text justification with Chinese typography rules
 * - Text selection with cursor handles and floating toolbar
 * - TTS read-aloud line highlighting
 * - Search result highlighting
 * - Page animations: Slide, Cover, Simulation, None, Scroll
 * - Bookmark indicator
 * - Long-press context menu
 */
@Composable
fun CanvasRenderer(
    content: String,
    chapterTitle: String,
    chapterIndex: Int = 0,
    nextChapterTitle: String = "",
    nextChapterContent: String = "",
    prevChapterTitle: String = "",
    prevChapterContent: String = "",
    backgroundColor: Color,
    textColor: Color,
    accentColor: Color,
    isNight: Boolean = false,
    fontSize: Float = 18f,
    lineHeight: Float = 1.8f,
    typeface: Typeface = Typeface.SERIF,
    paddingHorizontal: Int = 24,
    paddingVertical: Int = 24,
    bgImageUri: String = "",
    startFromLastPage: Boolean = false,
    initialProgress: Int = 0,
    initialChapterPosition: Int = 0,
    onProgressRestored: () -> Unit = {},
    pageAnimType: PageAnimType = PageAnimType.SLIDE,
    onTapCenter: () -> Unit = {},
    onProgress: (Int) -> Unit = {},
    onVisiblePageChanged: (chapterIndex: Int, title: String, readProgress: String, chapterPosition: Int) -> Unit = { _, _, _, _ -> },
    onNextChapter: () -> Unit = {},
    onPrevChapter: () -> Unit = {},
    onScrollNearBottom: () -> Unit = {},
    onScrollReachedBottom: () -> Unit = {},
    onCopyText: (String) -> Unit = {},
    onSpeakFromHere: (Int) -> Unit = {},
    onTranslateText: (String) -> Unit = {},
    onLookupWord: (String) -> Unit = {},
    onImageClick: (String) -> Unit = {},
    onToggleTts: () -> Unit = {},
    onAddBookmark: () -> Unit = {},
    onReadAloudParagraphPositions: (List<Int>) -> Unit = {},
    onVisibleReadAloudPosition: (chapterIndex: Int, chapterPosition: Int) -> Unit = { _, _ -> },
    pendingSearchSelection: ReaderSearchController.SearchSelection? = null,
    onSearchSelectionConsumed: () -> Unit = {},
    bookTitle: String = "",
    bookAuthor: String = "",
    // 9-zone tap actions (ported from Legado ReadView.click)
    // Values: "prev", "next", "menu", "prev_chapter", "next_chapter", "tts", "bookmark", "none"
    tapActionTopLeft: String = "prev",
    tapActionTopRight: String = "next",
    tapActionBottomLeft: String = "prev",
    tapActionBottomRight: String = "next",
    readerStyle: ReaderStyle? = null,
    chaptersSize: Int = 0,
    showChapterName: Boolean = true,
    showTimeBattery: Boolean = true,
    headerLeft: String = "chapter",
    headerCenter: String = "none",
    headerRight: String = "none",
    footerLeft: String = "battery_time",
    footerCenter: String = "none",
    footerRight: String = "page_progress",
    pageTurnCommand: ReaderPageDirection? = null,
    onPageTurnCommandConsumed: () -> Unit = {},
    autoPageSeconds: Int = 0,
    readAloudChapterPosition: Int = -1,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

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

    val density = LocalDensity.current
    val config = LocalConfiguration.current
    val layoutDirection = LocalLayoutDirection.current
    val screenWidthPx = with(density) { config.screenWidthDp.dp.toPx().toInt() }
    val screenHeightPx = with(density) { config.screenHeightDp.dp.toPx().toInt() }

    // Display cutout insets (ported from Legado PageView.upPaddingDisplayCutouts)
    val cutoutPadding = WindowInsets.displayCutout.asPaddingValues()
    val cutoutLeft = with(density) { cutoutPadding.calculateLeftPadding(layoutDirection).toPx().toInt() }
    val cutoutRight = with(density) { cutoutPadding.calculateRightPadding(layoutDirection).toPx().toInt() }
    val cutoutTop = with(density) { cutoutPadding.calculateTopPadding().toPx().toInt() }
    val cutoutBottom = with(density) { cutoutPadding.calculateBottomPadding().toPx().toInt() }

    val padHPx = with(density) { paddingHorizontal.dp.toPx().toInt() }
    val padVPx = with(density) { paddingVertical.dp.toPx().toInt() }
    val infoBarHeightPx = with(density) { 64.dp.toPx().toInt() }
    // Ensure content padding is at least as large as the cutout insets
    val effectivePadLeft = maxOf(padHPx, cutoutLeft)
    val effectivePadRight = maxOf(padHPx, cutoutRight)
    val effectivePadTop = maxOf(padVPx, cutoutTop) + infoBarHeightPx
    val effectivePadBottom = maxOf(padVPx, cutoutBottom) + infoBarHeightPx
    val fontSizePx = with(density) { fontSize.sp.toPx() }

    // ── Battery level (ported from Legado ReadBookActivity battery receiver) ──
    var batteryLevel by rememberBatteryLevel(context)

    // ── Time (update every 30s) ──
    var currentTime by remember { mutableStateOf(SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())) }
    LaunchedEffect(Unit) {
        while (true) {
            currentTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            kotlinx.coroutines.delay(30_000)
        }
    }

    // Create paints — apply letterSpacing and textBold from ReaderStyle
    val textArgb = textColor.toArgb()
    val chapterTitleColor = if (isNight) 0xFFE0E0E0.toInt() else 0xFF1A1A1A.toInt()
    val chapterAccentColor = if (isNight) 0xFFCFA875.toInt() else 0xFFBFA175.toInt()
    val styleLetterSpacing = readerStyle?.letterSpacing ?: 0f
    val styleTextBold = readerStyle?.textBold ?: 0

    val contentPaint = remember(fontSizePx, typeface, textArgb, lineHeight, styleLetterSpacing, styleTextBold) {
        TextPaint().apply {
            color = textArgb
            textSize = fontSizePx
            isAntiAlias = true
            this.typeface = when (styleTextBold) {
                1 -> Typeface.create(typeface, Typeface.BOLD)
                2 -> typeface // light — no fake bold
                else -> typeface
            }
            letterSpacing = styleLetterSpacing
        }
    }
    val titlePaint = remember(fontSizePx, typeface, chapterTitleColor, styleLetterSpacing) {
        TextPaint().apply {
            color = chapterTitleColor
            textSize = fontSizePx * 1.45f
            isAntiAlias = true
            isFakeBoldText = true
            this.typeface = typeface
            letterSpacing = styleLetterSpacing + 0.01f
        }
    }
    // Chapter number paint: smaller, accent-colored, with letter-spacing
    val chapterNumPaint = remember(fontSizePx, typeface, chapterAccentColor, styleLetterSpacing) {
        TextPaint().apply {
            color = chapterAccentColor
            textSize = fontSizePx * 0.85f
            isAntiAlias = true
            isFakeBoldText = true
            this.typeface = Typeface.create(typeface, Typeface.BOLD)
            letterSpacing = styleLetterSpacing + 0.04f
        }
    }
    val textMeasure = remember(contentPaint) { TextMeasure(contentPaint) }

    data class LayoutInputs(
        val provider: ChapterProvider,
        val contentPaint: TextPaint,
        val titlePaint: TextPaint,
    )

    val layoutInputs = remember(screenWidthPx, screenHeightPx, fontSizePx, effectivePadLeft, effectivePadRight, effectivePadTop, effectivePadBottom, lineHeight, readerStyle, contentPaint, titlePaint, textMeasure, density) {
        val style = readerStyle
        val cssOverrides = CssParser.parse(style?.customCss ?: "")
        val effectiveContentPaint = if (cssOverrides.fontSize != null) {
            TextPaint(contentPaint).apply { textSize = cssOverrides.fontSize * density.density }
        } else TextPaint(contentPaint)
        val effectiveTitlePaint = if (cssOverrides.fontSize != null) {
            TextPaint(titlePaint).apply { textSize = (cssOverrides.fontSize + 4) * density.density }
        } else TextPaint(titlePaint)
        if (cssOverrides.letterSpacing != null) {
            effectiveContentPaint.letterSpacing = cssOverrides.letterSpacing
        }
        val effectiveMeasure = if (cssOverrides.fontSize != null || cssOverrides.letterSpacing != null) {
            TextMeasure(effectiveContentPaint)
        } else textMeasure
        LayoutInputs(
            provider = ChapterProvider(
                viewWidth = screenWidthPx,
                viewHeight = screenHeightPx,
                paddingLeft = cssOverrides.paddingLeft?.let { (it * density.density).toInt() } ?: effectivePadLeft,
                paddingRight = cssOverrides.paddingRight?.let { (it * density.density).toInt() } ?: effectivePadRight,
                paddingTop = cssOverrides.paddingTop?.let { (it * density.density).toInt() } ?: effectivePadTop,
                paddingBottom = cssOverrides.paddingBottom?.let { (it * density.density).toInt() } ?: effectivePadBottom,
                titlePaint = effectiveTitlePaint,
                contentPaint = effectiveContentPaint,
                textMeasure = effectiveMeasure,
                lineSpacingExtra = cssOverrides.lineSpacingExtra ?: lineHeight,
                paragraphIndent = cssOverrides.paragraphIndent ?: style?.paragraphIndent ?: "\u3000\u3000",
                textFullJustify = cssOverrides.textAlign?.let { it == "justify" } ?: (style?.isJustify() ?: true),
                titleMode = style?.titleMode ?: 0,
                isMiddleTitle = style?.titleMode == 1,
                paragraphSpacing = cssOverrides.paragraphSpacing ?: style?.paragraphSpacing ?: 8,
                titleTopSpacing = style?.titleTopSpacing ?: 0,
                titleBottomSpacing = style?.titleBottomSpacing ?: 0,
                chapterNumPaint = chapterNumPaint,
            ),
            contentPaint = effectiveContentPaint,
            titlePaint = effectiveTitlePaint,
        )
    }

    fun chapterCacheKey(index: Int, title: String, body: String): String = "$index|$title|${body.hashCode()}|$screenWidthPx|$screenHeightPx|$fontSizePx|$lineHeight|$effectivePadLeft|$effectivePadRight|$effectivePadTop|$effectivePadBottom|${readerStyle?.hashCode()}"

    val currentChapterKey: String = remember(
        chapterIndex,
        chapterTitle,
        content,
        screenWidthPx,
        screenHeightPx,
        fontSizePx,
        effectivePadLeft,
        effectivePadRight,
        effectivePadTop,
        effectivePadBottom,
        lineHeight,
        readerStyle,
    ) {
        chapterCacheKey(chapterIndex, chapterTitle, content)
    }

    fun placeholderChapterFor(
        index: Int,
        title: String,
        message: String = title.ifBlank { "加载中..." },
    ): TextChapter {
        val chapter = TextChapter(index, title, chaptersSize)
        val page = TextPage(
            index = 0,
            title = title,
            chapterIndex = index,
            chapterSize = chaptersSize,
            paddingTop = effectivePadTop,
        ).apply {
            text = message.ifBlank { "加载中..." }
            textChapter = chapter
        }.format()
        chapter.addPage(page)
        return chapter
    }

    fun placeholderChapter(message: String = chapterTitle.ifBlank { "加载中..." }): TextChapter =
        placeholderChapterFor(chapterIndex, chapterTitle, message)

    // Layout pages — use async streaming layout for faster first-page display
    var textChapter by remember(currentChapterKey) { mutableStateOf<TextChapter?>(placeholderChapter()) }
    var pageCount by remember { mutableIntStateOf(1) }
    val scope = rememberCoroutineScope()
    val prelayoutCache = remember { mutableStateMapOf<String, TextChapter>() }

    LaunchedEffect(screenWidthPx, screenHeightPx, fontSizePx, effectivePadLeft, effectivePadRight, effectivePadTop, effectivePadBottom, lineHeight, readerStyle) {
        prelayoutCache.clear()
    }

    LaunchedEffect(nextChapterContent, nextChapterTitle, prevChapterContent, prevChapterTitle, layoutInputs) {
        val targets = listOf(
            Triple(chapterIndex + 1, nextChapterTitle, nextChapterContent),
            Triple(chapterIndex - 1, prevChapterTitle, prevChapterContent),
        ).filter { (_, title, body) -> title.isNotBlank() && body.isNotBlank() }
        targets.forEach { (index, title, body) ->
            val key = chapterCacheKey(index, title, body)
            if (!prelayoutCache.containsKey(key)) {
                val chapter = withContext(Dispatchers.Default) {
                    val chapter = layoutInputs.provider.layoutChapter(
                        title = title,
                        content = body,
                        chapterIndex = index,
                        chaptersSize = chaptersSize,
                    )
                    chapter
                }
                prelayoutCache[key] = chapter
            }
        }
    }

    LaunchedEffect(currentChapterKey, layoutInputs) {
        textChapter = placeholderChapter()
        pageCount = 1
        if (content.isBlank()) {
            val chapter = placeholderChapter(chapterTitle.ifBlank { "当前章节暂无正文" }).apply {
                isCompleted = true
            }
            textChapter = chapter
            pageCount = 1
        } else {
            val cachedChapter = prelayoutCache[currentChapterKey]
            if (cachedChapter != null) {
                textChapter = cachedChapter
                pageCount = cachedChapter.pageSize.coerceAtLeast(1)
                return@LaunchedEffect
            }
            var handle: com.morealm.app.domain.render.AsyncLayoutHandle? = null
            handle = layoutInputs.provider.layoutChapterAsync(
                title = chapterTitle,
                content = content,
                chapterIndex = chapterIndex,
                chaptersSize = chaptersSize,
                scope = this,
                onPageReady = { index, _ ->
                    if (index == 0) {
                        textChapter = handle?.textChapter
                    }
                    pageCount = handle?.textChapter?.pageSize?.coerceAtLeast(1) ?: 1
                },
                onCompleted = {
                    textChapter = handle?.textChapter
                    pageCount = handle?.textChapter?.pageSize?.coerceAtLeast(1) ?: 1
                },
            )
        }
    }

    val chapter = textChapter
    var lastRenderablePages by remember(currentChapterKey) { mutableStateOf<List<TextPage>>(emptyList()) }
    if (!chapter?.pages.isNullOrEmpty()) {
        lastRenderablePages = chapter?.pages ?: emptyList()
    }
    val currentChapterPages = chapter?.pages?.takeIf { it.isNotEmpty() }
        ?: lastRenderablePages.ifEmpty { placeholderChapter().pages }
    val prevTextChapter = if (prevChapterTitle.isNotBlank() && prevChapterContent.isNotBlank()) {
        prelayoutCache[chapterCacheKey(chapterIndex - 1, prevChapterTitle, prevChapterContent)]
    } else null
    val nextTextChapter = if (nextChapterTitle.isNotBlank() && nextChapterContent.isNotBlank()) {
        prelayoutCache[chapterCacheKey(chapterIndex + 1, nextChapterTitle, nextChapterContent)]
    } else null
    var readerPageIndex by remember(chapterIndex) { mutableIntStateOf(0) }
    LaunchedEffect(pageCount, chapter?.isCompleted) {
        if (chapter?.isCompleted == true && pageCount > 0 && readerPageIndex > pageCount - 1) {
            readerPageIndex = pageCount - 1
        }
    }
    val pageFactory = remember(
        chapter,
        prevTextChapter,
        nextTextChapter,
        currentChapterPages,
        pageCount,
        chapter?.isCompleted,
        prevTextChapter?.pageSize,
        prevTextChapter?.isCompleted,
        nextTextChapter?.pageSize,
        nextTextChapter?.isCompleted,
        pageAnimType,
        readerPageIndex,
    ) {
        ReaderPageFactory(
            dataSource = SnapshotReaderDataSource(
                pageIndex = readerPageIndex,
                currentChapter = chapter,
                prevChapter = prevTextChapter,
                nextChapter = nextTextChapter,
                isScroll = pageAnimType == PageAnimType.SCROLL,
                hasNextChapterValue = chapterIndex < chaptersSize - 1,
                hasPrevChapterValue = chapterIndex > 0,
                onUpContent = { relativePosition, resetPageOffset ->
                    AppLog.debug(
                        "PageTurn",
                        "upContent(relativePosition=$relativePosition, resetPageOffset=$resetPageOffset)",
                    )
                },
            ),
        )
    }

    val pages = pageFactory.pages
    val renderPageCount = pageFactory.pageCount

    LaunchedEffect(chapter, readAloudChapterPosition) {
        chapter?.pages?.forEach { it.removePageAloudSpan() }
        if (chapter != null && readAloudChapterPosition >= 0) {
            val aloudPageIndex = chapter.getPageIndexByCharIndex(readAloudChapterPosition)
            val page = chapter.getPage(aloudPageIndex)
            val pageStart = chapter.getReadLength(aloudPageIndex)
            page?.upPageAloudSpan(readAloudChapterPosition - pageStart)
        }
    }

    LaunchedEffect(chapter) {
        val positions = chapter?.getParagraphs(pageSplit = false)
            ?.map { it.chapterPosition }
            .orEmpty()
        onReadAloudParagraphPositions(positions)
    }

    // Track whether we've already restored the saved position for this content
    var progressRestored by remember { mutableStateOf(false) }
    // Reset restore flag when content changes
    LaunchedEffect(content, chapterTitle) {
        progressRestored = false
    }

    // Selection state
    val selectionState = remember { SelectionState() }
    var selectedTextPage by remember(chapterIndex) { mutableStateOf<TextPage?>(null) }
    var scrollRelativePages by remember(chapterIndex) { mutableStateOf<Map<Int, TextPage>>(emptyMap()) }
    var toolbarOffset by remember { mutableStateOf(Offset.Zero) }
    // Share quote dialog state
    var shareQuoteText by remember { mutableStateOf<String?>(null) }
    var scrollPageIndex by remember(chapterIndex) { mutableIntStateOf(0) }
    val autoPagerState = remember(chapterIndex, pageAnimType) { ReaderAutoPagerState() }
    var autoPageProgress by remember(chapterIndex, pageAnimType) { mutableIntStateOf(0) }
    var autoScrollDelta by remember(chapterIndex, pageAnimType) { mutableIntStateOf(0) }
    // Cross-chapter scroll state: survives chapter transitions for visual continuity.
    val scrollState = remember { ReaderScrollState() }

    // Pager state — always start at 0, then jump after layout completes
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { renderPageCount })

    // Page-turn coordinator — replaces local page-turn functions and state
    val coordinator = remember(chapterIndex, pageAnimType) {
        PageTurnCoordinator(scope, pageAnimType, onNextChapter, onPrevChapter, onProgress, onVisiblePageChanged)
    }
    // Update deps on each recomposition so coordinator always sees latest values
    coordinator.updateDeps(pageFactory, pagerState, chapterIndex, pageCount, renderPageCount)

    LaunchedEffect(currentChapterKey, pageAnimType) {
        if (pageAnimType != PageAnimType.SCROLL) {
            // Coordinator state is auto-reset via remember(chapterIndex, pageAnimType),
            // but we still need to reset pagerState position on chapter/anim key change.
            coordinator.ignoredSettledDisplayPage = 0
            coordinator.pendingSettledDirection = null
            coordinator.lastSettledDisplayPage = 0
            coordinator.lastReaderContent = null
            pagerState.scrollToPage(0)
        }
    }

    LaunchedEffect(chapter, pendingSearchSelection) {
        val selection = pendingSearchSelection ?: return@LaunchedEffect
        val textChapter = chapter ?: return@LaunchedEffect
        if (!textChapter.isCompleted || selection.chapterIndex != chapterIndex) return@LaunchedEffect
        val range = textChapter.searchSelectionRange(
            contentPosition = selection.queryIndexInChapter,
            queryLength = selection.queryLength,
        ) ?: return@LaunchedEffect
        selectedTextPage = textChapter.getPage(range.pageIndex)
        selectionState.setSelection(range.start, range.end)
        readerPageIndex = range.pageIndex.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
        if (pageAnimType != PageAnimType.SCROLL) {
            pagerState.scrollToPage(pageFactory.displayIndexForCurrentPage(range.pageIndex).coerceIn(0, renderPageCount - 1))
        }
        onSearchSelectionConsumed()
    }

    fun stopAutoPager() {
        autoPagerState.stop()
        autoPageProgress = 0
    }

    LaunchedEffect(autoPageSeconds, pageAnimType, screenHeightPx, progressRestored) {
        if (!progressRestored || autoPageSeconds <= 0) {
            stopAutoPager()
            return@LaunchedEffect
        }
        autoPagerState.start()
        while (autoPagerState.isRunning && autoPageSeconds > 0) {
            withFrameNanos { }
            val offset = autoPagerState.computeOffset(
                readSpeedSeconds = autoPageSeconds,
                height = screenHeightPx,
                isScroll = pageAnimType == PageAnimType.SCROLL,
            )
            if (offset <= 0) continue
            if (pageAnimType == PageAnimType.SCROLL) {
                autoScrollDelta += offset
            } else {
                autoPageProgress = autoPagerState.progress.coerceIn(0, screenHeightPx)
                if (autoPagerState.progress >= screenHeightPx) {
                    val startDisplayPage = coordinator.lastSettledDisplayPage.coerceIn(0, renderPageCount - 1)
                    val canTurn = pageFactory.hasNext(startDisplayPage)
                    if (!canTurn) {
                        stopAutoPager()
                    } else {
                        coordinator.commitPageTurn(startDisplayPage, ReaderPageDirection.NEXT) { readerPageIndex = it }?.let { committed ->
                            pagerState.scrollToPage(committed.coerceIn(0, renderPageCount - 1))
                        } ?: stopAutoPager()
                        autoPagerState.reset()
                        autoPageProgress = 0
                    }
                }
            }
        }
    }

    LaunchedEffect(pageTurnCommand, progressRestored, pageAnimType, renderPageCount) {
        val direction = pageTurnCommand ?: return@LaunchedEffect
        if (!progressRestored) return@LaunchedEffect
        if (pageAnimType == PageAnimType.SCROLL) return@LaunchedEffect
        onPageTurnCommandConsumed()
        coordinator.turnPageByTap(direction) { readerPageIndex = it }
    }

    // Restore saved progress after layout is complete
    LaunchedEffect(renderPageCount, pageCount, chapter?.isCompleted, progressRestored, pageAnimType) {
        if (progressRestored) return@LaunchedEffect
        if (chapter?.isCompleted != true) return@LaunchedEffect
        if (renderPageCount <= 1) {
            progressRestored = true
            return@LaunchedEffect
        }
        val currentTargetPage = when {
            startFromLastPage -> pageCount - 1
            initialChapterPosition > 0 -> chapter.getPageIndexByCharIndex(initialChapterPosition)
                .coerceIn(0, pageCount - 1)
            initialProgress > 0 -> ((initialProgress / 100f) * (pageCount - 1)).roundToInt().coerceIn(0, pageCount - 1)
            else -> 0
        }
        val targetPage = pageFactory.displayIndexForCurrentPage(currentTargetPage).coerceIn(0, renderPageCount - 1)
        readerPageIndex = currentTargetPage.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
        if (targetPage != pagerState.currentPage) {
            coordinator.ignoredSettledDisplayPage = targetPage
            pagerState.scrollToPage(targetPage)
        }
        coordinator.lastSettledDisplayPage = targetPage
        coordinator.lastReaderContent = coordinator.createPageState(targetPage).upContent()
        coordinator.reportProgress(coordinator.lastReaderContent)
        progressRestored = true
        onProgressRestored()
    }

    // Report progress
    LaunchedEffect(pagerState.currentPage, renderPageCount, pageCount, chapter?.isCompleted, progressRestored, pageAnimType) {
        if (chapter?.isCompleted != true || !progressRestored) return@LaunchedEffect
        val localPage = pageFactory.currentLocalIndex(pagerState.currentPage) ?: return@LaunchedEffect
        val pct = if (pageCount > 1) (localPage * 100) / (pageCount - 1) else 100
        onProgress(pct)
    }

    val bgArgb = backgroundColor.toArgb()

    // Load background image bitmap (decoded + cached by BgImageManager)
    val bgEntry = remember(bgImageUri, screenWidthPx, screenHeightPx) {
        if (bgImageUri.isNotEmpty() && screenWidthPx > 0 && screenHeightPx > 0) {
            BgImageManager.getBgBitmap(context, bgImageUri, screenWidthPx, screenHeightPx)
        } else null
    }
    val bgBitmap = bgEntry?.bitmap
    val bgMeanColor = bgEntry?.meanColor ?: bgArgb
    val pageInfoOverlaySpec = PageInfoOverlaySpec(
        chapterTitle = chapterTitle,
        pageCount = pageCount,
        chapterIndex = chapterIndex,
        chaptersSize = chaptersSize,
        batteryLevel = batteryLevel,
        currentTime = currentTime,
        textColorArgb = textColor.toArgb(),
        backgroundColorArgb = bgArgb,
        paddingHorizontalPx = with(density) { paddingHorizontal.dp.toPx() },
        barHeightPx = with(density) { 64.dp.toPx() },
        verticalPaddingPx = with(density) { 8.dp.toPx() },
        textSizePx = with(density) { 10.sp.toPx() },
        showChapterName = showChapterName,
        showTimeBattery = showTimeBattery,
        headerLeft = headerLeft,
        headerCenter = headerCenter,
        headerRight = headerRight,
        footerLeft = footerLeft,
        footerCenter = footerCenter,
        footerRight = footerRight,
        hasBgImage = bgBitmap != null,
    )

    // SimulationParams for bezier page curl
    val simulationParams = remember(
        pages,
        titlePaint,
        contentPaint,
        bgArgb,
        bgBitmap,
        bgMeanColor,
        pageInfoOverlaySpec,
        pageFactory,
        chapterIndex,
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
                pageForTurn = { displayIndex, relativePos ->
                    pageFactory.pageForTurn(displayIndex, relativePos)
                },
                currentDisplayIndex = {
                    coordinator.lastSettledDisplayPage.coerceIn(0, (renderPageCount - 1).coerceAtLeast(0))
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
                },
                onFillPage = { displayIndex, direction ->
                    coordinator.commitPageTurn(displayIndex, direction) { readerPageIndex = it }
                },
                onTapCenter = onTapCenter,
                onLongPress = { offset ->
                    val page = coordinator.getPageAt(coordinator.lastSettledDisplayPage)
                    val col = hitTestColumn(page, offset.x, offset.y)
                    if (col is ImageColumn) { onImageClick(col.src); return@SimulationParams }
                    val pos = hitTestPage(page, offset.x, offset.y)
                    if (pos != null) {
                        val wordRange = findWordRange(page, pos)
                        selectedTextPage = page
                        selectionState.setSelection(wordRange.first, wordRange.second)
                        toolbarOffset = offset
                    }
                },
            )
        } else null
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(backgroundColor)
            .then(
                if (pageAnimType != PageAnimType.SCROLL && pageAnimType != PageAnimType.SIMULATION) {
                    Modifier.pointerInput(selectionState.isActive, pageAnimType, renderPageCount) {
                        var totalDragX = 0f
                        var totalDragY = 0f
                        var dragAxis = 0 // 0 unknown, 1 horizontal, 2 vertical
                        var turnRequested = false
                        val slop = viewConfiguration.touchSlop
                        detectDragGestures(
                            onDragStart = {
                                totalDragX = 0f
                                totalDragY = 0f
                                dragAxis = 0
                                turnRequested = false
                                if (!selectionState.isActive) {
                                    coordinator.pageDelegateState.onDown()
                                }
                            },
                            onDrag = { change, dragAmount ->
                                if (selectionState.isActive) return@detectDragGestures
                                totalDragX += dragAmount.x
                                totalDragY += dragAmount.y
                                if (dragAxis == 0) {
                                    val absX = abs(totalDragX)
                                    val absY = abs(totalDragY)
                                    if (absX > slop || absY > slop) {
                                        dragAxis = when {
                                            pageAnimType != PageAnimType.SLIDE_VERTICAL &&
                                                absX > absY * PAGE_DELEGATE_DRAG_AXIS_RATIO -> 1
                                            absY > absX * PAGE_DELEGATE_DRAG_AXIS_RATIO -> 2
                                            else -> 0
                                        }
                                    }
                                }
                                if (dragAxis != 0) {
                                    change.consume()
                                }
                            },
                            onDragEnd = {
                                if (!selectionState.isActive) {
                                    val horizontalThreshold = (size.width * VERTICAL_SWIPE_PAGE_THRESHOLD_RATIO).coerceAtLeast(48f)
                                    val verticalThreshold = (size.height * VERTICAL_SWIPE_PAGE_THRESHOLD_RATIO).coerceAtLeast(48f)
                                    when {
                                        dragAxis == 1 && abs(totalDragX) > horizontalThreshold -> {
                                            turnRequested = true
                                            coordinator.turnPageByDrag(if (totalDragX > 0f) ReaderPageDirection.PREV else ReaderPageDirection.NEXT) { readerPageIndex = it }
                                        }
                                        dragAxis == 2 && abs(totalDragY) > verticalThreshold -> {
                                            turnRequested = true
                                            coordinator.turnPageByDrag(if (totalDragY < 0f) ReaderPageDirection.NEXT else ReaderPageDirection.PREV) { readerPageIndex = it }
                                        }
                                    }
                                }
                                totalDragX = 0f
                                totalDragY = 0f
                                dragAxis = 0
                                if (!turnRequested) {
                                    coordinator.pageDelegateState.stopScroll()
                                }
                            },
                            onDragCancel = {
                                totalDragX = 0f
                                totalDragY = 0f
                                dragAxis = 0
                                turnRequested = false
                                coordinator.pageDelegateState.abortAnim()
                            },
                        )
                    }
                } else Modifier
            )
            .then(
                if (pageAnimType != PageAnimType.SCROLL && pageAnimType != PageAnimType.SIMULATION) {
                    Modifier.pointerInput(selectionState.isActive) {
                        detectTapGestures(
                            onTap = { offset ->
                                if (selectionState.isActive) {
                                    selectionState.clear()
                                    return@detectTapGestures
                                }
                                // Reset delegate state on tap — matches Legado's onDown()
                                // which always runs before any page-turn request. Without
                                // this, a stuck isRunning from a prior animation blocks
                                // all future tap-based page turns.
                                coordinator.pageDelegateState.onDown()
                                // 9-zone tap (ported from Legado ReadView.onSingleTapUp + setRect9x)
                                val w = size.width; val h = size.height
                                val tapColumn = when {
                                    offset.x < w * 0.33f -> 0  // left
                                    offset.x < w * 0.66f -> 1  // center
                                    else -> 2                   // right
                                }
                                val row = when {
                                    offset.y < h * 0.33f -> 0  // top
                                    offset.y < h * 0.66f -> 1  // middle
                                    else -> 2                   // bottom
                                }
                                // Determine action for this zone
                                val action = when (row to tapColumn) {
                                    0 to 0 -> tapActionTopLeft      // TL
                                    0 to 1 -> "prev"                // TC: prev page
                                    0 to 2 -> tapActionTopRight     // TR
                                    1 to 0 -> "prev"                // ML: prev page
                                    1 to 1 -> "menu"                // MC: show menu
                                    1 to 2 -> "next"                // MR: next page
                                    2 to 0 -> tapActionBottomLeft   // BL
                                    2 to 1 -> "next"                // BC: next page
                                    2 to 2 -> tapActionBottomRight  // BR
                                    else -> "menu"
                                }
                                if (action == "menu") {
                                    onTapCenter()
                                    return@detectTapGestures
                                }
                                // Execute page-zone actions before image hit testing. Legado's
                                // PageDelegate treats a moved/page-turn gesture separately from
                                // ContentTextView.click(), so cover images must not swallow left/right
                                // page turns or fast horizontal swipes.
                                when (action) {
                                    "prev" -> {
                                        if (pageAnimType != PageAnimType.SCROLL) {
                                            coordinator.turnPageByTap(ReaderPageDirection.PREV) { readerPageIndex = it }
                                        }
                                        return@detectTapGestures
                                    }
                                    "next" -> {
                                        if (pageAnimType != PageAnimType.SCROLL) {
                                            coordinator.turnPageByTap(ReaderPageDirection.NEXT) { readerPageIndex = it }
                                        }
                                        return@detectTapGestures
                                    }
                                    "prev_chapter" -> {
                                        onPrevChapter()
                                        return@detectTapGestures
                                    }
                                    "next_chapter" -> {
                                        onNextChapter()
                                        return@detectTapGestures
                                    }
                                    "tts" -> {
                                        onToggleTts()
                                        return@detectTapGestures
                                    }
                                    "bookmark" -> {
                                        onAddBookmark()
                                        return@detectTapGestures
                                    }
                                    "none" -> Unit
                                }
                                val page = coordinator.getPageAt(pagerState.currentPage)
                                val hitColumn = hitTestColumn(page, offset.x, offset.y)
                                handleColumnClick(hitColumn)
                            },
                            onLongPress = { offset ->
                                val page = coordinator.getPageAt(pagerState.currentPage)
                                // If long-press on image, show image viewer (ported from Legado)
                                val col = hitTestColumn(page, offset.x, offset.y)
                                if (col is ImageColumn) {
                                    onImageClick(col.src)
                                    return@detectTapGestures
                                }
                                val pos = hitTestPage(page, offset.x, offset.y)
                                if (pos != null) {
                                    // Word-level selection (ported from Legado ReadView.onLongPress)
                                    val wordRange = findWordRange(page, pos)
                                    selectedTextPage = page
                                    selectionState.setSelection(wordRange.first, wordRange.second)
                                    toolbarOffset = offset
                                }
                            },
                        )
                    }
                } else Modifier
            )
    ) {
        if (pageAnimType == PageAnimType.SCROLL) {
            ScrollRenderer(
                pages = currentChapterPages,
                nextChapterPages = nextTextChapter?.pages.orEmpty(),
                prevChapterPages = prevTextChapter?.pages.orEmpty(),
                initialPageOffset = scrollState.consumeScrollOffset(),
                onChapterCommit = { direction, scrollIntoOffset ->
                    scrollState.commitChapterShift(direction, scrollIntoOffset)
                    when (direction) {
                        ReaderPageDirection.NEXT -> onNextChapter()
                        ReaderPageDirection.PREV -> onPrevChapter()
                        else -> {}
                    }
                },
                titlePaint = titlePaint,
                contentPaint = contentPaint,
                chapterNumPaint = chapterNumPaint,
                bgColor = bgArgb,
                bgBitmap = bgBitmap,
                selectionStart = selectionState.startPos,
                selectionEnd = selectionState.endPos,
                onScrollProgress = { if (chapter?.isCompleted == true) onProgress(it) },
                onScrollPageChanged = { displayIndex, page ->
                    scrollPageIndex = displayIndex
                    readerPageIndex = displayIndex.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
                    onVisiblePageChanged(page.chapterIndex, page.title, page.readProgress, page.chapterPosition)
                },
                onNearBottom = onScrollNearBottom,
                onReachedBottom = onScrollReachedBottom,
                onBoundaryPageTurn = { direction, displayIndex -> coordinator.commitScrollChapterBoundary(direction, displayIndex) { readerPageIndex = it } },
                onTapCenter = onTapCenter,
                onImageClick = onImageClick,
                onLongPressText = { page, textPos, offset ->
                    val wordRange = findWordRange(page, textPos)
                    selectedTextPage = page
                    scrollRelativePages = scrollRelativePages + (textPos.relativePagePos to page)
                    selectionState.setSelection(wordRange.first, wordRange.second)
                    toolbarOffset = offset
                },
                onReadAloudVisiblePosition = { page, line ->
                    onVisibleReadAloudPosition(page.chapterIndex, line.chapterPosition)
                },
                onSelectionStartMove = { page, textPos ->
                    selectedTextPage = page
                    scrollRelativePages = scrollRelativePages + (textPos.relativePagePos to page)
                    selectionState.selectStartMove(textPos)
                },
                onSelectionEndMove = { page, textPos ->
                    selectedTextPage = page
                    scrollRelativePages = scrollRelativePages + (textPos.relativePagePos to page)
                    selectionState.selectEndMove(textPos)
                },
                onRelativePagesChanged = { relativePages ->
                    scrollRelativePages = relativePages
                },
                resetKey = chapterIndex,
                startFromLastPage = startFromLastPage,
                initialProgress = initialProgress,
                initialChapterPosition = initialChapterPosition,
                initialPageIndex = readerPageIndex.coerceIn(0, (pageCount - 1).coerceAtLeast(0)),
                pageTurnCommand = pageTurnCommand,
                onPageTurnCommandConsumed = onPageTurnCommandConsumed,
                autoScrollDelta = autoScrollDelta,
                onAutoScrollDeltaConsumed = { autoScrollDelta = 0 },
                layoutCompleted = chapter?.isCompleted == true,
                modifier = Modifier.fillMaxSize(),
            )
            PageReaderInfoOverlay(
                pages = currentChapterPages,
                onCurrentPageChanged = {},
                pageIndex = scrollPageIndex.coerceIn(0, (pageCount - 1).coerceAtLeast(0)),
                pageCount = pageCount,
                backgroundColor = backgroundColor,
                chapterTitle = chapterTitle,
                chapterIndex = chapterIndex,
                chaptersSize = chaptersSize,
                batteryLevel = batteryLevel,
                currentTime = currentTime,
                textColor = textColor,
                paddingHorizontal = paddingHorizontal,
                showChapterName = showChapterName,
                showTimeBattery = showTimeBattery,
                headerLeft = headerLeft,
                headerCenter = headerCenter,
                headerRight = headerRight,
                footerLeft = footerLeft,
                footerCenter = footerCenter,
                footerRight = footerRight,
                hasBgImage = bgBitmap != null,
            )
        } else {
            AnimatedPageReader(
                pagerState = pagerState,
                animType = pageAnimType,
                modifier = Modifier.fillMaxSize(),
                simulationParams = simulationParams,
                simulationDisplayPage = coordinator.lastSettledDisplayPage.coerceIn(0, (renderPageCount - 1).coerceAtLeast(0)),
                onPageSettled = { settledPage ->
                    if (!progressRestored) {
                        coordinator.pendingSettledDirection = null
                        return@AnimatedPageReader
                    }
                    coordinator.handlePagerSettled(settledPage) { readerPageIndex = it }
                },
            ) { pageIndex ->
                    PageContentBox(
                page = coordinator.getPageAt(pageIndex),
                    pageIndex = pageIndex,
                    currentPage = if (pageAnimType == PageAnimType.SIMULATION) {
                        coordinator.lastSettledDisplayPage.coerceIn(0, renderPageCount - 1)
                    } else {
                        pagerState.currentPage
                    },
                    titlePaint = titlePaint,
                    contentPaint = contentPaint,
                    chapterNumPaint = chapterNumPaint,
                    bgBitmap = bgBitmap,
                    backgroundColor = backgroundColor,
                    selectionState = selectionState,
                    chapterTitle = chapterTitle,
                    pageCount = pageCount,
                    chapterIndex = chapterIndex,
                    chaptersSize = chaptersSize,
                    batteryLevel = batteryLevel,
                    currentTime = currentTime,
                    textColor = textColor,
                    paddingHorizontal = paddingHorizontal,
                    headerLeft = headerLeft,
                    headerCenter = headerCenter,
                    headerRight = headerRight,
                    footerLeft = footerLeft,
                    footerCenter = footerCenter,
                    footerRight = footerRight,
                    showChapterName = showChapterName,
                    showTimeBattery = showTimeBattery,
                    autoPageOverlayProgress = autoPageProgress,
                    autoPageNextPage = if (autoPageProgress > 0) coordinator.getRelativePage(pageIndex, 1) else null,
                    autoPageAccentColor = accentColor,
                    onCurrentPageChanged = {},
                    onSelectionStartMove = { textPos ->
                        selectedTextPage = coordinator.getPageAt(pageIndex)
                        selectionState.selectStartMove(textPos)
                    },
                    onSelectionEndMove = { textPos ->
                        selectedTextPage = coordinator.getPageAt(pageIndex)
                        selectionState.selectEndMove(textPos)
                    },
                )
            }
        }

        if (pages.isEmpty() && chapter?.isCompleted != true) {
            PageContentBox(
                page = TextPage(title = chapterTitle),
                pageIndex = 0,
                currentPage = 0,
                titlePaint = titlePaint,
                contentPaint = contentPaint,
                chapterNumPaint = chapterNumPaint,
                bgBitmap = bgBitmap,
                backgroundColor = backgroundColor,
                selectionState = selectionState,
                chapterTitle = chapterTitle,
                pageCount = 1,
                chapterIndex = chapterIndex,
                chaptersSize = chaptersSize,
                batteryLevel = batteryLevel,
                currentTime = currentTime,
                textColor = textColor,
                paddingHorizontal = paddingHorizontal,
                headerLeft = headerLeft,
                headerCenter = headerCenter,
                headerRight = headerRight,
                footerLeft = footerLeft,
                footerCenter = footerCenter,
                footerRight = footerRight,
                showChapterName = showChapterName,
                showTimeBattery = showTimeBattery,
                onCurrentPageChanged = { page -> onVisiblePageChanged(page.chapterIndex, page.title, page.readProgress, page.chapterPosition) },
                onSelectionStartMove = { textPos ->
                    selectedTextPage = TextPage(title = chapterTitle)
                    selectionState.selectStartMove(textPos)
                },
                onSelectionEndMove = { textPos ->
                    selectedTextPage = TextPage(title = chapterTitle)
                    selectionState.selectEndMove(textPos)
                },
            )
        }

        // Selection toolbar
        val currentDisplayForSelection = if (pageAnimType == PageAnimType.SIMULATION) {
            coordinator.lastSettledDisplayPage.coerceIn(0, renderPageCount - 1)
        } else {
            pagerState.currentPage
        }
        ReaderSelectionToolbar(
            selectionState = selectionState,
            toolbarOffset = toolbarOffset,
            page = selectedTextPage ?: coordinator.getPageAt(currentDisplayForSelection),
            relativePageProvider = { relativePos ->
                when (pageAnimType) {
                    PageAnimType.SCROLL -> scrollRelativePages[relativePos] ?: selectedTextPage?.takeIf {
                        relativePos == selectionState.startPos?.relativePagePos ||
                            relativePos == selectionState.endPos?.relativePagePos
                    }
                    else -> coordinator.getRelativePage(currentDisplayForSelection, relativePos)
                }
            },
            onCopyText = onCopyText,
            onSpeakFromHere = onSpeakFromHere,
            onTranslateText = onTranslateText,
            onLookupWord = onLookupWord,
            onShareQuote = { text -> shareQuoteText = text },
        )

        // Quote share dialog
        if (shareQuoteText != null) {
            com.morealm.app.ui.reader.QuoteShareDialog(
                quoteText = shareQuoteText!!,
                bookTitle = bookTitle,
                author = bookAuthor,
                accentColor = accentColor,
                backgroundColor = backgroundColor,
                onDismiss = { shareQuoteText = null },
            )
        }
    }
}

// ══════════════════════════════════════════════════════════════
// ReaderInfoBar — configurable header/footer with 6 slots
// Ported from Legado PageView + ReadTipConfig + BatteryView
// ══════════════════════════════════════════════════════════════

/**
 * Slot content types (matching Legado ReadTipConfig):
 * "none", "chapter", "time", "battery", "battery_pct", "page",
 * "progress", "page_progress", "book_name", "time_battery", "battery_time", "time_battery_pct"
 */
@Composable
private fun ReaderInfoBar(
    slotLeft: String,
    slotCenter: String,
    slotRight: String,
    chapterTitle: String,
    pageIndex: Int,
    pageCount: Int,
    currentPage: TextPage? = null,
    chapterIndex: Int,
    chaptersSize: Int,
    batteryLevel: Int,
    currentTime: String,
    textColor: Color,
    modifier: Modifier = Modifier,
) {
    // If all slots are "none", don't render anything
    if (slotLeft == "none" && slotCenter == "none" && slotRight == "none") return

    val tipColor = textColor.copy(alpha = 0.4f)
    val tipStyle = TextStyle(color = tipColor, fontSize = 10.sp)

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Left slot
        if (slotLeft != "none") {
            InfoSlotContent(
                slot = slotLeft,
                chapterTitle = chapterTitle,
                pageIndex = pageIndex,
                pageCount = pageCount,
                currentPage = currentPage,
                chapterIndex = chapterIndex,
                chaptersSize = chaptersSize,
                batteryLevel = batteryLevel,
                currentTime = currentTime,
                tipColor = tipColor,
                tipStyle = tipStyle,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        if (slotLeft != "none" && (slotCenter != "none" || slotRight != "none")) {
            Spacer(Modifier.weight(1f))
        }
        // Center slot
        if (slotCenter != "none") {
            InfoSlotContent(
                slot = slotCenter,
                chapterTitle = chapterTitle,
                pageIndex = pageIndex,
                pageCount = pageCount,
                currentPage = currentPage,
                chapterIndex = chapterIndex,
                chaptersSize = chaptersSize,
                batteryLevel = batteryLevel,
                currentTime = currentTime,
                tipColor = tipColor,
                tipStyle = tipStyle,
            )
        }
        if (slotRight != "none" && (slotLeft != "none" || slotCenter != "none")) {
            Spacer(Modifier.weight(1f))
        }
        // Right slot
        if (slotRight != "none") {
            InfoSlotContent(
                slot = slotRight,
                chapterTitle = chapterTitle,
                pageIndex = pageIndex,
                pageCount = pageCount,
                currentPage = currentPage,
                chapterIndex = chapterIndex,
                chaptersSize = chaptersSize,
                batteryLevel = batteryLevel,
                currentTime = currentTime,
                tipColor = tipColor,
                tipStyle = tipStyle,
            )
        }
    }
}

@Composable
private fun PageReaderInfoOverlay(
    pages: List<TextPage>,
    onCurrentPageChanged: (TextPage) -> Unit = {},
    pageIndex: Int,
    pageCount: Int,
    chapterTitle: String,
    chapterIndex: Int,
    chaptersSize: Int,
    batteryLevel: Int,
    currentTime: String,
    textColor: Color,
    backgroundColor: Color,
    paddingHorizontal: Int,
    showChapterName: Boolean,
    showTimeBattery: Boolean,
    headerLeft: String,
    headerCenter: String,
    headerRight: String,
    footerLeft: String,
    footerCenter: String,
    footerRight: String,
    hasBgImage: Boolean = false,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        val safePageIndex = pageIndex.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
        val currentPage = pages.getOrNull(safePageIndex) ?: pages.firstOrNull()
        val actualPageIndex = currentPage?.index ?: safePageIndex
        val actualPageCount = currentPage?.pageSize?.takeIf { it > 0 } ?: pageCount
        val actualChapterTitle = currentPage?.title?.takeIf { it.isNotBlank() } ?: chapterTitle
        val actualChapterIndex = currentPage?.chapterIndex ?: chapterIndex
        LaunchedEffect(currentPage) {
            currentPage?.let(onCurrentPageChanged)
        }
        ReaderInfoBar(
            slotLeft = if (showTimeBattery) headerLeft else "none",
            slotCenter = if (showChapterName) headerCenter else "none",
            slotRight = if (showTimeBattery) headerRight else "none",
            chapterTitle = actualChapterTitle,
            pageIndex = actualPageIndex,
            pageCount = actualPageCount,
            currentPage = currentPage,
            chapterIndex = actualChapterIndex,
            chaptersSize = chaptersSize,
            batteryLevel = batteryLevel,
            currentTime = currentTime,
            textColor = textColor,
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .height(64.dp)
                .then(
                    if (hasBgImage) Modifier
                    else Modifier.background(
                        Brush.verticalGradient(
                            0f to backgroundColor,
                            0.72f to backgroundColor,
                            1f to backgroundColor.copy(alpha = 0f),
                        )
                    )
                )
                .padding(horizontal = paddingHorizontal.dp, vertical = 8.dp),
        )
        ReaderInfoBar(
            slotLeft = if (showChapterName) footerLeft else "none",
            slotCenter = footerCenter,
            slotRight = footerRight,
            chapterTitle = actualChapterTitle,
            pageIndex = actualPageIndex,
            pageCount = actualPageCount,
            currentPage = currentPage,
            chapterIndex = actualChapterIndex,
            chaptersSize = chaptersSize,
            batteryLevel = batteryLevel,
            currentTime = currentTime,
            textColor = textColor,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .height(64.dp)
                .then(
                    if (hasBgImage) Modifier
                    else Modifier.background(
                        Brush.verticalGradient(
                            0f to backgroundColor.copy(alpha = 0f),
                            0.28f to backgroundColor,
                            1f to backgroundColor,
                        )
                    )
                )
                .padding(horizontal = paddingHorizontal.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun InfoSlotContent(
    slot: String,
    chapterTitle: String,
    pageIndex: Int,
    pageCount: Int,
    currentPage: TextPage? = null,
    chapterIndex: Int,
    chaptersSize: Int,
    batteryLevel: Int,
    currentTime: String,
    tipColor: Color,
    tipStyle: TextStyle,
    modifier: Modifier = Modifier,
) {
    when (slot) {
        "chapter" -> Text(
            chapterTitle,
            style = tipStyle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = modifier,
        )
        "time" -> Text(currentTime, style = tipStyle, modifier = modifier)
        "battery" -> BatteryIcon(batteryLevel, tipColor, modifier)
        "battery_pct" -> Text("$batteryLevel%", style = tipStyle, modifier = modifier)
        "page" -> Text("${pageIndex + 1}/$pageCount", style = tipStyle, modifier = modifier)
        "progress" -> {
            val readProgress = currentPage?.readProgress?.takeIf { it.isNotBlank() }
            if (readProgress != null) {
                Text(readProgress, style = tipStyle, modifier = modifier)
                return
            }
            val pct = if (pageCount > 1) {
                (pageIndex.toFloat() / (pageCount - 1) * 100)
            } else 100f
            Text("%.1f%%".format(pct), style = tipStyle, modifier = modifier)
        }
        "page_progress" -> {
            val readProgress = currentPage?.readProgress?.takeIf { it.isNotBlank() } ?: "0.0%"
            val actualPageSize = currentPage?.pageSize?.takeIf { it > 0 } ?: pageCount
            Text("${pageIndex + 1}/$actualPageSize  $readProgress", style = tipStyle, modifier = modifier)
        }
        "book_name" -> Text("MoRealm", style = tipStyle, modifier = modifier)
        "time_battery" -> Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
            Text(currentTime, style = tipStyle)
            Spacer(Modifier.width(6.dp))
            BatteryIcon(batteryLevel, tipColor)
        }
        "battery_time" -> Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
            BatteryIcon(batteryLevel, tipColor)
            Spacer(Modifier.width(6.dp))
            Text(currentTime, style = tipStyle)
        }
        "time_battery_pct" -> Text(
            "$currentTime  $batteryLevel%",
            style = tipStyle,
            modifier = modifier,
        )
        "chapter_progress" -> {
            if (chaptersSize > 0) {
                Text("${chapterIndex + 1}/$chaptersSize", style = tipStyle, modifier = modifier)
            }
        }
    }
}

/**
 * Battery icon drawn with Compose Canvas (ported from Legado BatteryView).
 * Draws a small battery outline with fill level.
 */
@Composable
private fun BatteryIcon(
    level: Int,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val w = 22.dp
    val h = 11.dp
    androidx.compose.foundation.Canvas(
        modifier = modifier.size(w, h),
    ) {
        val strokeW = 1.dp.toPx()
        val capW = 2.dp.toPx()
        val bodyW = size.width - capW - strokeW
        val bodyH = size.height
        val inset = strokeW / 2

        // Battery body outline
        drawRect(
            color = color,
            topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
            size = androidx.compose.ui.geometry.Size(bodyW - strokeW, bodyH - strokeW),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeW),
        )
        // Battery cap (positive terminal)
        val capH = bodyH * 0.4f
        val capY = (bodyH - capH) / 2
        drawRect(
            color = color,
            topLeft = androidx.compose.ui.geometry.Offset(bodyW, capY),
            size = androidx.compose.ui.geometry.Size(capW, capH),
        )
        // Fill level
        val fillInset = strokeW + 1.dp.toPx()
        val fillW = (bodyW - fillInset * 2) * (level.coerceIn(0, 100) / 100f)
        if (fillW > 0) {
            drawRect(
                color = color,
                topLeft = androidx.compose.ui.geometry.Offset(fillInset, fillInset),
                size = androidx.compose.ui.geometry.Size(fillW, bodyH - fillInset * 2),
            )
        }
    }
}

// ══════════════════════════════════════════════════════════════
// Extracted composables from CanvasRenderer
// ══════════════════════════════════════════════════════════════

/**
 * Observes battery level via a sticky broadcast receiver.
 * Returns a [MutableState] that updates whenever the system reports a new level.
 */
@Composable
private fun rememberBatteryLevel(context: Context): MutableState<Int> {
    val state = remember { mutableIntStateOf(100) }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                if (level >= 0) state.intValue = (level * 100) / scale
            }
        }
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val sticky = context.registerReceiver(receiver, filter)
        // Read initial value from sticky intent
        if (sticky != null) {
            val level = sticky.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = sticky.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            if (level >= 0) state.intValue = (level * 100) / scale
        }
        onDispose { context.unregisterReceiver(receiver) }
    }
    return state
}

/**
 * A single page's content: the Canvas-drawn text plus header/footer info bars.
 */
@Composable
private fun PageContentBox(
    page: TextPage,
    pageIndex: Int,
    currentPage: Int,
    titlePaint: TextPaint,
    contentPaint: TextPaint,
    chapterNumPaint: TextPaint? = null,
    bgBitmap: Bitmap?,
    backgroundColor: Color,
    selectionState: SelectionState,
    chapterTitle: String,
    pageCount: Int,
    chapterIndex: Int,
    chaptersSize: Int,
    batteryLevel: Int,
    currentTime: String,
    textColor: Color,
    paddingHorizontal: Int,
    headerLeft: String,
    headerCenter: String,
    headerRight: String,
    footerLeft: String,
    footerCenter: String,
    footerRight: String,
    showChapterName: Boolean,
    showTimeBattery: Boolean,
    autoPageOverlayProgress: Int = 0,
    autoPageNextPage: TextPage? = null,
    autoPageAccentColor: Color = Color.Transparent,
    onCurrentPageChanged: (TextPage) -> Unit = {},
    onSelectionStartMove: (TextPos) -> Unit = {},
    onSelectionEndMove: (TextPos) -> Unit = {},
) {
    fun cursorOffsetFor(textPos: TextPos?, startHandle: Boolean): Offset? {
        val pos = textPos?.takeIf { it.relativePagePos == 0 } ?: return null
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
        return Offset(x, line.lineBottom + page.paddingTop)
    }

    val isCurrentDisplayPage = pageIndex == currentPage
    Box(modifier = Modifier.fillMaxSize().background(backgroundColor)) {
        PageCanvas(
            page = page,
            titlePaint = titlePaint,
            contentPaint = contentPaint,
            chapterNumPaint = chapterNumPaint,
            bgBitmap = bgBitmap,
            selectionStart = if (isCurrentDisplayPage) {
                selectionState.startPos?.takeIf { it.relativePagePos == 0 }
            } else null,
            selectionEnd = if (isCurrentDisplayPage) {
                selectionState.endPos?.takeIf { it.relativePagePos == 0 }
            } else null,
            selectionColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.30f),
            aloudColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.20f),
            searchResultColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
            bookmarkColor = MaterialTheme.colorScheme.error,
            modifier = Modifier.fillMaxSize(),
        )
        if (isCurrentDisplayPage) {
            val startOffset = cursorOffsetFor(selectionState.startPos, startHandle = true)
            val endOffset = cursorOffsetFor(selectionState.endPos, startHandle = false)
            val handlesTooClose = startOffset != null && endOffset != null &&
                abs(startOffset.x - endOffset.x) < 28f &&
                abs(startOffset.y - endOffset.y) < 28f
            startOffset?.let { offset ->
                val adjustedOffset = if (handlesTooClose) offset.copy(y = offset.y - 18f) else offset
                CursorHandle(position = adjustedOffset, onDrag = { dragOffset ->
                    hitTestPageRough(page, dragOffset.x, dragOffset.y)?.let(onSelectionStartMove)
                })
            }
            endOffset?.let { offset ->
                val adjustedOffset = if (handlesTooClose) offset.copy(y = offset.y + 18f) else offset
                CursorHandle(position = adjustedOffset, onDrag = { dragOffset ->
                    hitTestPageRough(page, dragOffset.x, dragOffset.y)?.let(onSelectionEndMove)
                })
            }
        }
        if (autoPageOverlayProgress > 0 && autoPageNextPage != null && isCurrentDisplayPage) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(with(LocalDensity.current) { autoPageOverlayProgress.toDp() })
                    .align(Alignment.TopStart),
            ) {
                PageCanvas(
                    page = autoPageNextPage,
                    titlePaint = titlePaint,
                    contentPaint = contentPaint,
                    chapterNumPaint = chapterNumPaint,
                    bgBitmap = bgBitmap,
                    selectionStart = null,
                    selectionEnd = null,
                    selectionColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.30f),
                    aloudColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.20f),
                    searchResultColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
                    bookmarkColor = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .align(Alignment.TopStart)
                    .offset(y = with(LocalDensity.current) { autoPageOverlayProgress.toDp() })
                .background(autoPageAccentColor),
            )
        }
        PageReaderInfoOverlay(
            pages = listOf(page),
            onCurrentPageChanged = if (isCurrentDisplayPage && page.chapterIndex == chapterIndex) onCurrentPageChanged else { _ -> },
            pageIndex = 0,
            pageCount = page.pageSize.takeIf { it > 0 } ?: pageCount,
            backgroundColor = backgroundColor,
            chapterTitle = chapterTitle,
            chapterIndex = chapterIndex,
            chaptersSize = chaptersSize,
            batteryLevel = batteryLevel,
            currentTime = currentTime,
            textColor = textColor,
            paddingHorizontal = paddingHorizontal,
            showChapterName = showChapterName,
            showTimeBattery = showTimeBattery,
            headerLeft = headerLeft,
            headerCenter = headerCenter,
            headerRight = headerRight,
            footerLeft = footerLeft,
            footerCenter = footerCenter,
            footerRight = footerRight,
            hasBgImage = bgBitmap != null,
        )
    }
}

/**
 * Shows the floating selection toolbar (copy / speak / lookup) when text is selected.
 * Automatically dismisses selection on any action.
 */
@Composable
private fun ReaderSelectionToolbar(
    selectionState: SelectionState,
    toolbarOffset: Offset,
    page: TextPage?,
    relativePageProvider: (Int) -> TextPage? = { page },
    onCopyText: (String) -> Unit,
    onSpeakFromHere: (Int) -> Unit,
    onTranslateText: (String) -> Unit,
    onLookupWord: (String) -> Unit,
    onShareQuote: (String) -> Unit,
) {
    if (!selectionState.isActive) return

    fun selectedText(): String {
        val start = selectionState.startPos ?: return ""
        val end = selectionState.endPos ?: return ""
        val (actualStart, actualEnd) = if (start.compare(end) <= 0) start to end else end to start
        if (actualStart.relativePagePos == actualEnd.relativePagePos) {
            val textPage = relativePageProvider(actualStart.relativePagePos) ?: page ?: return ""
            return getSelectedText(textPage, actualStart, actualEnd)
        }
        val startPage = relativePageProvider(actualStart.relativePagePos) ?: return ""
        val endPage = relativePageProvider(actualEnd.relativePagePos) ?: return getSelectedText(
            startPage,
            actualStart,
            TextPos(actualStart.relativePagePos, startPage.lines.lastIndex, Int.MAX_VALUE),
        )
        val builder = StringBuilder()
        builder.append(
            getSelectedText(
                startPage,
                actualStart,
                TextPos(actualStart.relativePagePos, startPage.lines.lastIndex, Int.MAX_VALUE),
            ),
        )
        if (builder.isNotEmpty()) builder.append('\n')
        builder.append(getSelectedText(endPage, TextPos(actualEnd.relativePagePos, 0, -1), actualEnd))
        return builder.toString()
    }

    fun selectedStartChapterPosition(): Int {
        val textPage = page ?: return 0
        val start = selectionState.startPos ?: return textPage.chapterPosition
        val end = selectionState.endPos ?: start
        val actualStart = if (start.compare(end) <= 0) start else end
        val startPage = relativePageProvider(actualStart.relativePagePos) ?: textPage
        return startPage.chapterPosition + startPage.getPosByLineColumn(actualStart.lineIndex, actualStart.columnIndex)
    }

    SelectionToolbar(
        offset = toolbarOffset,
        onCopy = { onCopyText(selectedText()); selectionState.clear() },
        onSpeak = { onSpeakFromHere(selectedStartChapterPosition()); selectionState.clear() },
        onTranslate = { onTranslateText(selectedText()); selectionState.clear() },
        onShare = { onShareQuote(selectedText()); selectionState.clear() },
        onLookup = { onLookupWord(selectedText()); selectionState.clear() },
        onDismiss = { selectionState.clear() },
    )
}
