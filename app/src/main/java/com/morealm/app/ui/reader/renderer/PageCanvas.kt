package com.morealm.app.ui.reader.renderer

import android.graphics.BitmapFactory
import android.graphics.Paint
import android.text.TextPaint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.morealm.app.domain.render.BaseColumn
import com.morealm.app.domain.render.ImageColumn
import com.morealm.app.domain.render.TextColumn
import com.morealm.app.domain.render.TextLine
import com.morealm.app.domain.render.TextPage
import com.morealm.app.domain.render.TextPos
import java.io.File

/**
 * Single-page Canvas drawing composable.
 * Renders text lines, images, selection highlights, TTS highlights,
 * search result highlights, and bookmark indicators.
 *
 * Drawing order (back to front):
 * 1. Search result highlight rectangles
 * 2. Selection highlight rectangles
 * 3. TTS read-aloud highlight rectangles
 * 4. Text characters
 * 5. Images
 * 6. Bookmark indicator
 */
@Composable
fun PageCanvas(
    page: TextPage,
    titlePaint: TextPaint,
    contentPaint: TextPaint,
    selectionStart: TextPos? = null,
    selectionEnd: TextPos? = null,
    selectionColor: Color = Color(0x4D2196F3),
    aloudLineIndex: Int = -1,
    aloudColor: Color = Color(0x3300C853),
    searchResultColor: Color = Color(0x40FFEB3B),
    hasBookmark: Boolean = false,
    bookmarkColor: Color = Color(0xFFFF5252),
    modifier: Modifier = Modifier,
) {
    val selColorArgb = selectionColor.toArgb()
    val aloudColorArgb = aloudColor.toArgb()
    val searchColorArgb = searchResultColor.toArgb()
    val bmColorArgb = bookmarkColor.toArgb()

    Canvas(modifier = modifier.fillMaxSize()) {
        val canvas = drawContext.canvas.nativeCanvas
        val highlightPaint = Paint().apply {
            style = Paint.Style.FILL
            isAntiAlias = true
        }

        for ((lineIndex, line) in page.lines.withIndex()) {
            val paint = if (line.isTitle) titlePaint else contentPaint

            // 1. Search result highlights
            for (col in line.columns) {
                if (col is TextColumn && col.isSearchResult) {
                    highlightPaint.color = searchColorArgb
                    canvas.drawRect(
                        col.start, line.lineTop + page.paddingTop,
                        col.end, line.lineBottom + page.paddingTop,
                        highlightPaint,
                    )
                }
            }

            // 2. Selection highlights
            if (selectionStart != null && selectionEnd != null) {
                val startLine = selectionStart.lineIndex
                val endLine = selectionEnd.lineIndex
                if (lineIndex in startLine..endLine) {
                    val colStart = if (lineIndex == startLine) selectionStart.columnIndex else 0
                    val colEnd = if (lineIndex == endLine) selectionEnd.columnIndex else line.columns.lastIndex
                    if (colStart <= colEnd && colStart < line.columns.size) {
                        val left = line.columns[colStart.coerceIn(0, line.columns.lastIndex)].start
                        val right = line.columns[colEnd.coerceIn(0, line.columns.lastIndex)].end
                        highlightPaint.color = selColorArgb
                        canvas.drawRect(
                            left, line.lineTop + page.paddingTop,
                            right, line.lineBottom + page.paddingTop,
                            highlightPaint,
                        )
                    }
                }
            }

            // 3. TTS read-aloud highlight
            if (lineIndex == aloudLineIndex) {
                val left = line.columns.firstOrNull()?.start ?: 0f
                val right = line.columns.lastOrNull()?.end ?: 0f
                highlightPaint.color = aloudColorArgb
                canvas.drawRect(
                    left, line.lineTop + page.paddingTop,
                    right, line.lineBottom + page.paddingTop,
                    highlightPaint,
                )
            }

            // 4. Draw text / images
            if (line.isImage) {
                for (col in line.columns) {
                    if (col is ImageColumn) {
                        drawImageColumn(canvas, col, line, page.paddingTop)
                    }
                }
            } else {
                // Apply extra letter spacing if justified
                val drawPaint = if (line.extraLetterSpacing != 0f) {
                    TextPaint(paint).apply {
                        letterSpacing = this.letterSpacing + line.extraLetterSpacing
                    }
                } else paint

                for (col in line.columns) {
                    if (col is TextColumn) {
                        val x = col.start + line.extraLetterSpacingOffsetX
                        canvas.drawText(
                            col.charData, x,
                            line.lineBase + page.paddingTop,
                            drawPaint,
                        )
                    }
                }
            }
        }

        // 6. Bookmark indicator (small triangle in top-right corner)
        if (hasBookmark) {
            highlightPaint.color = bmColorArgb
            val path = android.graphics.Path().apply {
                moveTo(size.width - 40f, 0f)
                lineTo(size.width, 0f)
                lineTo(size.width, 40f)
                close()
            }
            canvas.drawPath(path, highlightPaint)
        }
    }
}

private fun drawImageColumn(
    canvas: android.graphics.Canvas,
    col: ImageColumn,
    line: TextLine,
    paddingTop: Int,
) {
    try {
        val path = col.src.removePrefix("file://")
        val file = File(path)
        if (file.exists()) {
            val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return
            canvas.drawBitmap(
                bitmap,
                null,
                android.graphics.RectF(
                    col.start, line.lineTop + paddingTop,
                    col.end, line.lineBottom + paddingTop,
                ),
                null,
            )
            bitmap.recycle()
        }
    } catch (_: Exception) {}
}
