package com.morealm.app.ui.reader.renderer

import android.graphics.Typeface
import android.text.TextPaint
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.morealm.app.domain.render.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    backgroundColor: Color,
    textColor: Color,
    accentColor: Color,
    fontSize: Float = 18f,
    lineHeight: Float = 1.8f,
    typeface: Typeface = Typeface.SERIF,
    paddingHorizontal: Int = 24,
    paddingVertical: Int = 24,
    startFromLastPage: Boolean = false,
    pageAnimType: PageAnimType = PageAnimType.SLIDE,
    onTapCenter: () -> Unit = {},
    onProgress: (Int) -> Unit = {},
    onNextChapter: () -> Unit = {},
    onPrevChapter: () -> Unit = {},
    onCopyText: (String) -> Unit = {},
    onSpeakFromHere: (String) -> Unit = {},
    onLookupWord: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val config = LocalConfiguration.current
    val screenWidthPx = with(density) { config.screenWidthDp.dp.toPx().toInt() }
    val screenHeightPx = with(density) { config.screenHeightDp.dp.toPx().toInt() }
    val padHPx = with(density) { paddingHorizontal.dp.toPx().toInt() }
    val padVPx = with(density) { paddingVertical.dp.toPx().toInt() }
    val fontSizePx = with(density) { fontSize.sp.toPx() }

    // Create paints
    val textArgb = textColor.toArgb()
    val accentArgb = accentColor.toArgb()

    val contentPaint = remember(fontSizePx, typeface, textArgb, lineHeight) {
        TextPaint().apply {
            color = textArgb
            textSize = fontSizePx
            isAntiAlias = true
            this.typeface = typeface
        }
    }
    val titlePaint = remember(fontSizePx, typeface, accentArgb) {
        TextPaint().apply {
            color = accentArgb
            textSize = fontSizePx * 1.2f
            isAntiAlias = true
            isFakeBoldText = true
            this.typeface = typeface
        }
    }
    val textMeasure = remember(contentPaint) { TextMeasure(contentPaint) }

    // Layout pages on background thread
    var textChapter by remember { mutableStateOf<TextChapter?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(content, chapterTitle, screenWidthPx, screenHeightPx, fontSizePx, padHPx, padVPx, lineHeight) {
        textChapter = withContext(Dispatchers.Default) {
            if (content.isBlank()) {
                TextChapter(chapterIndex, chapterTitle, 0).apply {
                    val emptyPage = TextPage(title = chapterTitle)
                    addPage(emptyPage)
                    isCompleted = true
                }
            } else {
                val provider = ChapterProvider(
                    viewWidth = screenWidthPx,
                    viewHeight = screenHeightPx,
                    paddingLeft = padHPx,
                    paddingRight = padHPx,
                    paddingTop = padVPx,
                    paddingBottom = padVPx,
                    titlePaint = titlePaint,
                    contentPaint = contentPaint,
                    textMeasure = textMeasure,
                    lineSpacingExtra = lineHeight,
                )
                provider.layoutChapter(chapterTitle, content, chapterIndex)
            }
        }
    }

    val chapter = textChapter
    val pages = chapter?.pages ?: emptyList()
    val pageCount = pages.size.coerceAtLeast(1)
    val initialPage = if (startFromLastPage) pageCount - 1 else 0

    // Selection state
    val selectionState = remember { SelectionState() }
    var toolbarOffset by remember { mutableStateOf(Offset.Zero) }

    // Pager state
    val pagerState = rememberPagerState(initialPage = initialPage, pageCount = { pageCount })

    // Report progress
    LaunchedEffect(pagerState.currentPage, pageCount) {
        val pct = if (pageCount > 1) (pagerState.currentPage * 100) / (pageCount - 1) else 100
        onProgress(pct)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(backgroundColor)
            .pointerInput(selectionState.isActive) {
                detectTapGestures(
                    onTap = { offset ->
                        if (selectionState.isActive) {
                            selectionState.clear()
                            return@detectTapGestures
                        }
                        val third = size.width / 3f
                        when {
                            offset.x < third -> scope.launch {
                                if (pagerState.currentPage > 0) {
                                    pagerState.animateScrollToPage(pagerState.currentPage - 1)
                                } else onPrevChapter()
                            }
                            offset.x > third * 2 -> scope.launch {
                                if (pagerState.currentPage < pageCount - 1) {
                                    pagerState.animateScrollToPage(pagerState.currentPage + 1)
                                } else onNextChapter()
                            }
                            else -> onTapCenter()
                        }
                    },
                    onLongPress = { offset ->
                        val page = pages.getOrNull(pagerState.currentPage) ?: return@detectTapGestures
                        val pos = hitTestPage(page, offset.x, offset.y)
                        if (pos != null) {
                            // Select the entire line on long press
                            val line = page.lines.getOrNull(pos.lineIndex) ?: return@detectTapGestures
                            val startPos = TextPos(0, pos.lineIndex, 0)
                            val endPos = TextPos(0, pos.lineIndex, line.columns.lastIndex.coerceAtLeast(0))
                            selectionState.setSelection(startPos, endPos)
                            toolbarOffset = offset
                        }
                    },
                )
            }
    ) {
        if (pageAnimType == PageAnimType.SCROLL) {
            ScrollRenderer(
                pages = pages,
                titlePaint = titlePaint,
                contentPaint = contentPaint,
                selectionStart = selectionState.startPos,
                selectionEnd = selectionState.endPos,
                onScrollProgress = { onProgress(it) },
                onNearBottom = { onNextChapter() },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            AnimatedPageReader(
                pagerState = pagerState,
                animType = pageAnimType,
                modifier = Modifier.fillMaxSize(),
            ) { pageIndex ->
                val page = pages.getOrElse(pageIndex) { TextPage() }
                Box(modifier = Modifier.fillMaxSize()) {
                    PageCanvas(
                        page = page,
                        titlePaint = titlePaint,
                        contentPaint = contentPaint,
                        selectionStart = if (pageIndex == pagerState.currentPage) selectionState.startPos else null,
                        selectionEnd = if (pageIndex == pagerState.currentPage) selectionState.endPos else null,
                        modifier = Modifier.fillMaxSize(),
                    )
                    // Page number footer
                    Text(
                        "${pageIndex + 1} / $pageCount",
                        style = TextStyle(color = textColor.copy(alpha = 0.3f), fontSize = 11.sp),
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 8.dp),
                    )
                }
            }
        }

        // Selection toolbar
        if (selectionState.isActive) {
            val page = pages.getOrNull(pagerState.currentPage)
            SelectionToolbar(
                offset = toolbarOffset,
                onCopy = {
                    if (page != null && selectionState.startPos != null && selectionState.endPos != null) {
                        val text = getSelectedText(page, selectionState.startPos!!, selectionState.endPos!!)
                        onCopyText(text)
                    }
                    selectionState.clear()
                },
                onSpeak = {
                    if (page != null && selectionState.startPos != null && selectionState.endPos != null) {
                        val text = getSelectedText(page, selectionState.startPos!!, selectionState.endPos!!)
                        onSpeakFromHere(text)
                    }
                    selectionState.clear()
                },
                onLookup = {
                    if (page != null && selectionState.startPos != null && selectionState.endPos != null) {
                        val text = getSelectedText(page, selectionState.startPos!!, selectionState.endPos!!)
                        onLookupWord(text)
                    }
                    selectionState.clear()
                },
                onDismiss = { selectionState.clear() },
            )
        }
    }
}
