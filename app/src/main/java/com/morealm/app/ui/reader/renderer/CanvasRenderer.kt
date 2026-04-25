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
import android.os.BatteryManager
import android.text.TextPaint
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
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
import com.morealm.app.domain.entity.ReaderStyle
import com.morealm.app.domain.render.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    fontSize: Float = 18f,
    lineHeight: Float = 1.8f,
    typeface: Typeface = Typeface.SERIF,
    paddingHorizontal: Int = 24,
    paddingVertical: Int = 24,
    bgImageUri: String = "",
    startFromLastPage: Boolean = false,
    initialProgress: Int = 0,
    pageAnimType: PageAnimType = PageAnimType.SLIDE,
    onTapCenter: () -> Unit = {},
    onProgress: (Int) -> Unit = {},
    onNextChapter: () -> Unit = {},
    onPrevChapter: () -> Unit = {},
    onScrollNearBottom: () -> Unit = {},
    onScrollReachedBottom: () -> Unit = {},
    onCopyText: (String) -> Unit = {},
    onSpeakFromHere: (String) -> Unit = {},
    onTranslateText: (String) -> Unit = {},
    onLookupWord: (String) -> Unit = {},
    onImageClick: (String) -> Unit = {},
    onToggleTts: () -> Unit = {},
    onAddBookmark: () -> Unit = {},
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
    headerLeft: String = "none",
    headerCenter: String = "none",
    headerRight: String = "none",
    footerLeft: String = "chapter",
    footerCenter: String = "none",
    footerRight: String = "progress",
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
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
    // Ensure content padding is at least as large as the cutout insets
    val effectivePadLeft = maxOf(padHPx, cutoutLeft)
    val effectivePadRight = maxOf(padHPx, cutoutRight)
    val effectivePadTop = maxOf(padVPx, cutoutTop)
    val effectivePadBottom = maxOf(padVPx, cutoutBottom)
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
    val accentArgb = accentColor.toArgb()
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
    val titlePaint = remember(fontSizePx, typeface, accentArgb, styleLetterSpacing) {
        TextPaint().apply {
            color = accentArgb
            textSize = fontSizePx * 1.2f
            isAntiAlias = true
            isFakeBoldText = true
            this.typeface = Typeface.create(typeface, Typeface.BOLD)
            letterSpacing = styleLetterSpacing
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
            ),
            contentPaint = effectiveContentPaint,
            titlePaint = effectiveTitlePaint,
        )
    }

    // Layout pages — use async streaming layout for faster first-page display
    var textChapter by remember { mutableStateOf<TextChapter?>(null) }
    var pageCount by remember { mutableIntStateOf(1) }
    val scope = rememberCoroutineScope()
    val prelayoutCache = remember { mutableStateMapOf<String, TextChapter>() }
    fun chapterCacheKey(index: Int, title: String, body: String): String = "$index|$title|${body.hashCode()}|$screenWidthPx|$screenHeightPx|$fontSizePx|$lineHeight|$effectivePadLeft|$effectivePadRight|$effectivePadTop|$effectivePadBottom|${readerStyle?.hashCode()}"

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

    LaunchedEffect(content, chapterTitle, screenWidthPx, screenHeightPx, fontSizePx, effectivePadLeft, effectivePadRight, effectivePadTop, effectivePadBottom, lineHeight, readerStyle) {
        if (content.isBlank()) {
            val chapter = TextChapter(chapterIndex, chapterTitle, 0).apply {
                val emptyPage = TextPage(title = chapterTitle)
                addPage(emptyPage)
                isCompleted = true
            }
            textChapter = chapter
            pageCount = 1
        } else {
            val cachedChapter = prelayoutCache[chapterCacheKey(chapterIndex, chapterTitle, content)]
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
    var lastRenderablePages by remember { mutableStateOf<List<TextPage>>(emptyList()) }
    if (!chapter?.pages.isNullOrEmpty()) {
        lastRenderablePages = chapter?.pages ?: emptyList()
    }
    val pages = chapter?.pages?.takeIf { it.isNotEmpty() } ?: lastRenderablePages
    // pageCount is driven by async layout via mutableIntStateOf above

    // Track whether we've already restored the saved position for this content
    var progressRestored by remember { mutableStateOf(false) }
    // Reset restore flag when content changes
    LaunchedEffect(content, chapterTitle) {
        progressRestored = false
    }

    // Selection state
    val selectionState = remember { SelectionState() }
    var toolbarOffset by remember { mutableStateOf(Offset.Zero) }
    // Share quote dialog state
    var shareQuoteText by remember { mutableStateOf<String?>(null) }

    // Pager state — always start at 0, then jump after layout completes
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { pageCount })

    // Restore saved progress after layout is complete
    LaunchedEffect(pageCount, chapter?.isCompleted, progressRestored) {
        if (progressRestored) return@LaunchedEffect
        if (chapter?.isCompleted != true) return@LaunchedEffect
        if (pageCount <= 1) {
            progressRestored = true
            return@LaunchedEffect
        }
        val targetPage = when {
            startFromLastPage -> pageCount - 1
            initialProgress > 0 -> ((initialProgress / 100f) * (pageCount - 1)).toInt().coerceIn(0, pageCount - 1)
            else -> 0
        }
        if (targetPage != pagerState.currentPage) {
            pagerState.scrollToPage(targetPage)
        }
        progressRestored = true
    }

    // Report progress
    LaunchedEffect(pagerState.currentPage, pageCount, chapter?.isCompleted, progressRestored) {
        if (chapter?.isCompleted != true || !progressRestored) return@LaunchedEffect
        val pct = if (pageCount > 1) (pagerState.currentPage * 100) / (pageCount - 1) else 100
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

    // SimulationParams for bezier page curl
    val simulationParams = remember(pages, titlePaint, contentPaint, bgArgb, bgBitmap, bgMeanColor) {
        if (pageAnimType == PageAnimType.SIMULATION && pages.isNotEmpty()) {
            SimulationParams(
                pages = pages,
                titlePaint = titlePaint,
                contentPaint = contentPaint,
                bgColor = bgArgb,
                bgBitmap = bgBitmap,
                bgMeanColor = bgMeanColor,
                onPageChanged = { onProgress(if (pageCount > 1) (it * 100) / (pageCount - 1) else 100) },
                onNextChapter = onNextChapter,
                onPrevChapter = onPrevChapter,
                onTapCenter = onTapCenter,
                onLongPress = { offset ->
                    val page = pages.getOrNull(pagerState.currentPage) ?: return@SimulationParams
                    val col = hitTestColumn(page, offset.x, offset.y)
                    if (col is ImageColumn) { onImageClick(col.src); return@SimulationParams }
                    val pos = hitTestPage(page, offset.x, offset.y)
                    if (pos != null) {
                        val wordRange = findWordRange(page, pos)
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
                    Modifier.pointerInput(selectionState.isActive) {
                        detectTapGestures(
                            onTap = { offset ->
                                if (selectionState.isActive) {
                                    selectionState.clear()
                                    return@detectTapGestures
                                }
                                // Check if tap landed on an ImageColumn (ported from Legado ContentTextView.click)
                                val page = pages.getOrNull(pagerState.currentPage)
                                if (page != null) {
                                    val col = hitTestColumn(page, offset.x, offset.y)
                                    if (col is ImageColumn) {
                                        onImageClick(col.src)
                                        return@detectTapGestures
                                    }
                                }
                                // 9-zone tap (ported from Legado ReadView.onSingleTapUp + setRect9x)
                                val w = size.width; val h = size.height
                                val col = when {
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
                                val action = when (row to col) {
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
                                // Execute action
                                when (action) {
                                    "prev" -> scope.launch {
                                        if (pagerState.currentPage > 0) {
                                            pagerState.animateScrollToPage(pagerState.currentPage - 1)
                                        } else onPrevChapter()
                                    }
                                    "next" -> scope.launch {
                                        if (pagerState.currentPage < pageCount - 1) {
                                            pagerState.animateScrollToPage(pagerState.currentPage + 1)
                                        } else onNextChapter()
                                    }
                                    "prev_chapter" -> onPrevChapter()
                                    "next_chapter" -> onNextChapter()
                                    "tts" -> onToggleTts()
                                    "bookmark" -> onAddBookmark()
                                    "menu" -> onTapCenter()
                                    // "none" → do nothing
                                }
                            },
                            onLongPress = { offset ->
                                val page = pages.getOrNull(pagerState.currentPage) ?: return@detectTapGestures
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
                pages = pages,
                titlePaint = titlePaint,
                contentPaint = contentPaint,
                bgColor = bgArgb,
                bgBitmap = bgBitmap,
                selectionStart = selectionState.startPos,
                selectionEnd = selectionState.endPos,
                onScrollProgress = { if (chapter?.isCompleted == true) onProgress(it) },
                onNearBottom = onScrollNearBottom,
                onReachedBottom = onScrollReachedBottom,
                onTapCenter = onTapCenter,
                resetKey = chapterIndex,
                startFromLastPage = startFromLastPage,
                initialProgress = initialProgress,
                layoutCompleted = chapter?.isCompleted == true,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            AnimatedPageReader(
                pagerState = pagerState,
                animType = pageAnimType,
                modifier = Modifier.fillMaxSize(),
                simulationParams = simulationParams,
            ) { pageIndex ->
                PageContentBox(
                page = pages.getOrElse(pageIndex) { TextPage() },
                    pageIndex = pageIndex,
                    currentPage = pagerState.currentPage,
                    titlePaint = titlePaint,
                    contentPaint = contentPaint,
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
            )
        }

        // Selection toolbar
        ReaderSelectionToolbar(
            selectionState = selectionState,
            toolbarOffset = toolbarOffset,
            page = pages.getOrNull(pagerState.currentPage),
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
 * "progress", "page_progress", "book_name", "time_battery", "time_battery_pct"
 */
@Composable
private fun ReaderInfoBar(
    slotLeft: String,
    slotCenter: String,
    slotRight: String,
    chapterTitle: String,
    pageIndex: Int,
    pageCount: Int,
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
private fun InfoSlotContent(
    slot: String,
    chapterTitle: String,
    pageIndex: Int,
    pageCount: Int,
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
            val pct = if (chaptersSize > 0) {
                ((chapterIndex + (pageIndex + 1f) / pageCount.coerceAtLeast(1)) / chaptersSize * 100)
            } else if (pageCount > 1) {
                (pageIndex.toFloat() / (pageCount - 1) * 100)
            } else 100f
            Text("%.1f%%".format(pct), style = tipStyle, modifier = modifier)
        }
        "page_progress" -> {
            val pct = if (chaptersSize > 0) {
                ((chapterIndex + (pageIndex + 1f) / pageCount.coerceAtLeast(1)) / chaptersSize * 100)
            } else 0f
            Text("${pageIndex + 1}/$pageCount  ${"%.1f%%".format(pct)}", style = tipStyle, modifier = modifier)
        }
        "book_name" -> Text("MoRealm", style = tipStyle, modifier = modifier)
        "time_battery" -> Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
            Text(currentTime, style = tipStyle)
            Spacer(Modifier.width(6.dp))
            BatteryIcon(batteryLevel, tipColor)
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
) {
    Box(modifier = Modifier.fillMaxSize().background(backgroundColor)) {
        PageCanvas(
            page = page,
            titlePaint = titlePaint,
            contentPaint = contentPaint,
            bgBitmap = bgBitmap,
            selectionStart = if (pageIndex == currentPage) selectionState.startPos else null,
            selectionEnd = if (pageIndex == currentPage) selectionState.endPos else null,
            selectionColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.30f),
            aloudColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.20f),
            searchResultColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
            bookmarkColor = MaterialTheme.colorScheme.error,
            modifier = Modifier.fillMaxSize(),
        )
        // ── Header info bar (ported from Legado PageView) ──
        ReaderInfoBar(
            slotLeft = headerLeft,
            slotCenter = headerCenter,
            slotRight = headerRight,
            chapterTitle = chapterTitle,
            pageIndex = pageIndex,
            pageCount = pageCount,
            chapterIndex = chapterIndex,
            chaptersSize = chaptersSize,
            batteryLevel = batteryLevel,
            currentTime = currentTime,
            textColor = textColor,
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .padding(horizontal = paddingHorizontal.dp, vertical = 4.dp),
        )
        // ── Footer info bar (ported from Legado PageView) ──
        ReaderInfoBar(
            slotLeft = footerLeft,
            slotCenter = footerCenter,
            slotRight = footerRight,
            chapterTitle = chapterTitle,
            pageIndex = pageIndex,
            pageCount = pageCount,
            chapterIndex = chapterIndex,
            chaptersSize = chaptersSize,
            batteryLevel = batteryLevel,
            currentTime = currentTime,
            textColor = textColor,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(horizontal = paddingHorizontal.dp, vertical = 4.dp),
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
    onCopyText: (String) -> Unit,
    onSpeakFromHere: (String) -> Unit,
    onTranslateText: (String) -> Unit,
    onLookupWord: (String) -> Unit,
    onShareQuote: (String) -> Unit,
) {
    if (!selectionState.isActive) return

    fun selectedText(): String {
        if (page == null || selectionState.startPos == null || selectionState.endPos == null) return ""
        return getSelectedText(page, selectionState.startPos!!, selectionState.endPos!!)
    }

    SelectionToolbar(
        offset = toolbarOffset,
        onCopy = { onCopyText(selectedText()); selectionState.clear() },
        onSpeak = { onSpeakFromHere(selectedText()); selectionState.clear() },
        onTranslate = { onTranslateText(selectedText()); selectionState.clear() },
        onShare = { onShareQuote(selectedText()); selectionState.clear() },
        onLookup = { onLookupWord(selectedText()); selectionState.clear() },
        onDismiss = { selectionState.clear() },
    )
}
