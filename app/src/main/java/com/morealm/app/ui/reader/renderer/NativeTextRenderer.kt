package com.morealm.app.ui.reader.renderer

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * Native Compose text renderer for TXT files.
 * Uses HorizontalPager for page-turn with native gestures and animations.
 * Text is measured and split into pages that fit the screen.
 *
 * Advantages over WebView for plain text:
 * - Zero WebView overhead (~50MB memory saved)
 * - Instant page turns (native Compose animation)
 * - No HTML parsing/rendering latency
 * - Proper Compose lifecycle integration
 */
@Composable
fun NativeTextReader(
    content: String,
    chapterTitle: String,
    backgroundColor: Color,
    textColor: Color,
    accentColor: Color,
    fontSize: Float = 18f,
    lineHeight: Float = 2.0f,
    fontFamily: FontFamily = FontFamily.Serif,
    paddingHorizontal: Int = 24,
    paddingVertical: Int = 24,
    paragraphIndent: String = "　　",
    onTapCenter: () -> Unit = {},
    onProgress: (Int) -> Unit = {},
    onChapterEnd: () -> Unit = {},
    onChapterStart: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val config = LocalConfiguration.current
    val screenHeightDp = config.screenHeightDp
    val screenWidthDp = config.screenWidthDp

    val textStyle = TextStyle(
        color = textColor,
        fontSize = fontSize.sp,
        lineHeight = (fontSize * lineHeight).sp,
        fontFamily = fontFamily,
        textIndent = TextIndent(firstLine = (fontSize * 2).sp),
        textAlign = TextAlign.Justify,
    )

    // Split content into paragraphs
    val paragraphs = remember(content, paragraphIndent) {
        content.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .map { if (paragraphIndent.isNotBlank()) "$paragraphIndent$it" else it }
    }

    // Calculate available height for text (screen - padding - header/footer)
    val availableHeightDp = screenHeightDp - paddingVertical * 2 - 40 // 40dp for header/footer
    val availableWidthDp = screenWidthDp - paddingHorizontal * 2

    // Split paragraphs into pages based on estimated line count
    val pages = remember(paragraphs, fontSize, lineHeight, availableHeightDp, availableWidthDp) {
        paginateText(paragraphs, fontSize, lineHeight, availableHeightDp.toFloat(), availableWidthDp.toFloat())
    }

    val pageCount = pages.size.coerceAtLeast(1)
    val pagerState = rememberPagerState(pageCount = { pageCount })

    // Report progress
    LaunchedEffect(pagerState.currentPage, pageCount) {
        val pct = if (pageCount > 1) (pagerState.currentPage * 100) / (pageCount - 1) else 100
        onProgress(pct)
    }

    // Detect chapter boundary
    LaunchedEffect(pagerState.currentPage) {
        if (pagerState.currentPage == 0 && pageCount > 1) onChapterStart()
        if (pagerState.currentPage == pageCount - 1) onChapterEnd()
    }

    val scope = rememberCoroutineScope()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(backgroundColor)
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val third = size.width / 3f
                    when {
                        offset.x < third -> {
                            scope.launch {
                                if (pagerState.currentPage > 0) pagerState.animateScrollToPage(pagerState.currentPage - 1)
                                else onChapterStart()
                            }
                        }
                        offset.x > third * 2 -> {
                            scope.launch {
                                if (pagerState.currentPage < pageCount - 1) pagerState.animateScrollToPage(pagerState.currentPage + 1)
                                else onChapterEnd()
                            }
                        }
                        else -> onTapCenter()
                    }
                }
            }
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
        ) { pageIndex ->
            val pageText = pages.getOrElse(pageIndex) { "" }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = paddingHorizontal.dp, vertical = paddingVertical.dp)
            ) {
                // Chapter title on first page
                if (pageIndex == 0 && chapterTitle.isNotBlank()) {
                    Column {
                        Text(
                            chapterTitle,
                            style = TextStyle(
                                color = accentColor,
                                fontSize = (fontSize + 4).sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = fontFamily,
                            ),
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(pageText, style = textStyle)
                    }
                } else {
                    Text(pageText, style = textStyle)
                }

                // Page number footer
                Text(
                    "${pageIndex + 1} / $pageCount",
                    style = TextStyle(
                        color = textColor.copy(alpha = 0.3f),
                        fontSize = 11.sp,
                    ),
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp),
                )
            }
        }
    }
}

/**
 * Simple text pagination: estimates how many lines fit per page,
 * then splits paragraphs across pages.
 */
private fun paginateText(
    paragraphs: List<String>,
    fontSizeSp: Float,
    lineHeightMultiplier: Float,
    availableHeightDp: Float,
    availableWidthDp: Float,
): List<String> {
    if (paragraphs.isEmpty()) return listOf("")

    val lineHeightDp = fontSizeSp * lineHeightMultiplier * 1.1f // approximate sp→dp with density factor
    val charsPerLine = (availableWidthDp / (fontSizeSp * 0.55f)).toInt().coerceAtLeast(10)
    val linesPerPage = (availableHeightDp / lineHeightDp).toInt().coerceAtLeast(5)

    val pages = mutableListOf<String>()
    val currentPage = StringBuilder()
    var linesUsed = 0

    for (para in paragraphs) {
        // Estimate lines this paragraph needs
        val paraLines = (para.length / charsPerLine) + 1

        if (linesUsed + paraLines > linesPerPage && currentPage.isNotEmpty()) {
            // Start new page
            pages.add(currentPage.toString().trimEnd())
            currentPage.clear()
            linesUsed = 0
        }

        if (currentPage.isNotEmpty()) currentPage.append("\n\n")
        currentPage.append(para)
        linesUsed += paraLines + 1 // +1 for paragraph spacing
    }

    if (currentPage.isNotEmpty()) {
        pages.add(currentPage.toString().trimEnd())
    }

    return pages.ifEmpty { listOf("") }
}
