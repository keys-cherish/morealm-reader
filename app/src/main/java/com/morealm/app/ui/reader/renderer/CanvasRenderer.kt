package com.morealm.app.ui.reader.renderer

import android.graphics.BitmapFactory
import com.morealm.app.domain.render.*
import android.graphics.Typeface
import com.morealm.app.domain.render.*
import android.text.TextPaint
import com.morealm.app.domain.render.*
import androidx.compose.foundation.Canvas
import com.morealm.app.domain.render.*
import androidx.compose.foundation.background
import com.morealm.app.domain.render.*
import androidx.compose.foundation.gestures.detectTapGestures
import com.morealm.app.domain.render.*
import androidx.compose.foundation.layout.*
import com.morealm.app.domain.render.*
import androidx.compose.foundation.pager.HorizontalPager
import com.morealm.app.domain.render.*
import androidx.compose.foundation.pager.rememberPagerState
import com.morealm.app.domain.render.*
import androidx.compose.material3.Text
import com.morealm.app.domain.render.*
import androidx.compose.runtime.*
import com.morealm.app.domain.render.*
import androidx.compose.ui.Alignment
import com.morealm.app.domain.render.*
import androidx.compose.ui.Modifier
import com.morealm.app.domain.render.*
import androidx.compose.ui.graphics.Color
import com.morealm.app.domain.render.*
import androidx.compose.ui.graphics.asImageBitmap
import com.morealm.app.domain.render.*
import androidx.compose.ui.graphics.nativeCanvas
import com.morealm.app.domain.render.*
import androidx.compose.ui.graphics.toArgb
import com.morealm.app.domain.render.*
import androidx.compose.ui.input.pointer.pointerInput
import com.morealm.app.domain.render.*
import androidx.compose.ui.platform.LocalConfiguration
import com.morealm.app.domain.render.*
import androidx.compose.ui.platform.LocalDensity
import com.morealm.app.domain.render.*
import androidx.compose.ui.text.TextStyle
import com.morealm.app.domain.render.*
import androidx.compose.ui.unit.IntOffset
import com.morealm.app.domain.render.*
import androidx.compose.ui.unit.IntSize
import com.morealm.app.domain.render.*
import androidx.compose.ui.unit.dp
import com.morealm.app.domain.render.*
import androidx.compose.ui.unit.sp
import com.morealm.app.domain.render.*
import kotlinx.coroutines.launch
import com.morealm.app.domain.render.*
import java.io.File
import com.morealm.app.domain.render.*

/**
 * Canvas-based text reader with HorizontalPager page turning.
 *
 * Rendering pipeline:
 *   content → PageLayoutEngine (text measurement + line wrapping + pagination)
 *           → List<TextPage> (pre-computed page data)
 *           → HorizontalPager + Canvas (native drawing, 60fps page turns)
 *
 * This combines Legado's Canvas rendering speed with Compose's gesture system.
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
    onTapCenter: () -> Unit = {},
    onProgress: (Int) -> Unit = {},
    onNextChapter: () -> Unit = {},
    onPrevChapter: () -> Unit = {},
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

    // Layout pages
    val pages = remember(content, chapterTitle, screenWidthPx, screenHeightPx, fontSizePx, padHPx, padVPx) {
        if (content.isBlank()) return@remember listOf(TextPage(title = chapterTitle))
        val engine = PageLayoutEngine(
            screenWidthPx, screenHeightPx, padHPx, padHPx, padVPx, padVPx,
            titlePaint, contentPaint, textMeasure,
        )
        engine.layoutChapter(chapterTitle, content, chapterIndex)
    }

    val pageCount = pages.size.coerceAtLeast(1)
    val initialPage = if (startFromLastPage) pageCount - 1 else 0
    val pagerState = rememberPagerState(initialPage = initialPage, pageCount = { pageCount })
    val scope = rememberCoroutineScope()

    // Report progress
    LaunchedEffect(pagerState.currentPage, pageCount) {
        val pct = if (pageCount > 1) (pagerState.currentPage * 100) / (pageCount - 1) else 100
        onProgress(pct)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(backgroundColor)
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val third = size.width / 3f
                    when {
                        offset.x < third -> scope.launch {
                            if (pagerState.currentPage > 0) pagerState.animateScrollToPage(pagerState.currentPage - 1)
                            else onPrevChapter()
                        }
                        offset.x > third * 2 -> scope.launch {
                            if (pagerState.currentPage < pageCount - 1) pagerState.animateScrollToPage(pagerState.currentPage + 1)
                            else onNextChapter()
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
            val page = pages.getOrElse(pageIndex) { TextPage() }
            Canvas(modifier = Modifier.fillMaxSize()) {
                val canvas = drawContext.canvas.nativeCanvas
                // Draw text lines
                for (line in page.lines) {
                    val paint = if (line.isTitle) titlePaint else contentPaint
                    for (col in line.columns) {
                        canvas.drawText(col.text, col.x, line.y + paint.textSize, paint)
                    }
                }
                // Draw images
                for (img in page.images) {
                    val path = img.path.removePrefix("file://")
                    val file = File(path)
                    if (file.exists()) {
                        val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: continue
                        val srcBitmap = bitmap.asImageBitmap()
                        drawImage(
                            srcBitmap,
                            dstOffset = IntOffset(img.x.toInt(), img.y.toInt()),
                            dstSize = IntSize(img.width.toInt(), img.height.toInt()),
                        )
                        bitmap.recycle()
                    }
                }
            }
            // Page number
            Text(
                "${pageIndex + 1} / $pageCount",
                style = TextStyle(color = textColor.copy(alpha = 0.3f), fontSize = 11.sp),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
            )
        }
    }
}
