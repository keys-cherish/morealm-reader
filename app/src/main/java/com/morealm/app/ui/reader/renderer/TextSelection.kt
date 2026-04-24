package com.morealm.app.ui.reader.renderer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.morealm.app.domain.render.BaseColumn
import com.morealm.app.domain.render.TextColumn
import com.morealm.app.domain.render.TextLine
import com.morealm.app.domain.render.TextPage
import com.morealm.app.domain.render.TextPos
import java.text.BreakIterator
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Text selection state holder.
 */
class SelectionState {
    var isActive by mutableStateOf(false)
    var startPos by mutableStateOf<TextPos?>(null)
    var endPos by mutableStateOf<TextPos?>(null)

    fun clear() {
        isActive = false
        startPos = null
        endPos = null
    }

    fun setSelection(start: TextPos, end: TextPos) {
        startPos = start
        endPos = end
        isActive = true
    }
}

/**
 * Hit-test: find the TextPos at a given (x, y) coordinate on a page.
 */
fun hitTestPage(page: TextPage, x: Float, y: Float): TextPos? {
    val paddingTop = page.paddingTop
    for (lineIndex in page.lines.indices) {
        val line = page.lines[lineIndex]
        if (line.isTouchY(y - paddingTop)) {
            val colIndex = line.columnAtX(x)
            if (colIndex >= 0) {
                return TextPos(0, lineIndex, colIndex)
            }
            // If no exact column hit, find closest
            val closest = line.columns.indices.minByOrNull {
                val col = line.columns[it]
                abs((col.start + col.end) / 2 - x)
            }
            if (closest != null) {
                return TextPos(0, lineIndex, closest)
            }
        }
    }
    // Find closest line
    val closestLine = page.lines.indices.minByOrNull {
        abs(page.lines[it].lineTop + paddingTop + (page.lines[it].lineBottom - page.lines[it].lineTop) / 2 - y)
    }
    if (closestLine != null) {
        val line = page.lines[closestLine]
        val closest = line.columns.indices.minByOrNull {
            val col = line.columns[it]
            abs((col.start + col.end) / 2 - x)
        } ?: 0
        return TextPos(0, closestLine, closest)
    }
    return null
}

/**
 * Hit-test: find the actual BaseColumn at a given (x, y) coordinate on a page.
 * Used to detect taps on ImageColumn for image preview (ported from Legado ContentTextView.click).
 */
fun hitTestColumn(page: TextPage, x: Float, y: Float): BaseColumn? {
    val paddingTop = page.paddingTop
    for (line in page.lines) {
        if (line.isTouchY(y - paddingTop)) {
            val colIndex = line.columnAtX(x)
            if (colIndex >= 0) {
                return line.columns[colIndex]
            }
        }
    }
    return null
}

/**
 * Find word boundaries around a tap position.
 * Ported from Legado ReadView.onLongPress() — uses BreakIterator to find word boundaries
 * across the paragraph containing the tapped position.
 */
fun findWordRange(page: TextPage, tapPos: TextPos): Pair<TextPos, TextPos> {
    val tapLine = page.lines.getOrNull(tapPos.lineIndex)
        ?: return tapPos to tapPos

    // Collect the full paragraph text and map character indices back to (line, column)
    var tapCharIndex = 0
    var lineStart = tapPos.lineIndex
    var lineEnd = tapPos.lineIndex

    // Walk backward to find paragraph start
    for (i in tapPos.lineIndex - 1 downTo 0) {
        if (page.lines[i].isParagraphEnd) break
        lineStart = i
    }
    // Walk forward to find paragraph end
    for (i in tapPos.lineIndex until page.lines.size) {
        lineEnd = i
        if (page.lines[i].isParagraphEnd) break
    }

    // Build paragraph text and track the character index of the tap position
    data class CharMapping(val lineIndex: Int, val colIndex: Int)
    val charMap = mutableListOf<CharMapping>()
    val sb = StringBuilder()

    for (li in lineStart..lineEnd) {
        val line = page.lines[li]
        for (ci in line.columns.indices) {
            val col = line.columns[ci]
            if (col is TextColumn) {
                if (li == tapPos.lineIndex && ci == tapPos.columnIndex) {
                    tapCharIndex = charMap.size
                }
                for (ch in col.charData) {
                    charMap.add(CharMapping(li, ci))
                    sb.append(ch)
                }
            }
        }
    }

    if (sb.isEmpty()) return tapPos to tapPos

    // Use BreakIterator to find word boundaries
    val boundary = BreakIterator.getWordInstance(Locale.getDefault())
    boundary.setText(sb.toString())
    var start = boundary.first()
    var end = boundary.next()
    var wordStart = 0
    var wordEnd = sb.length

    while (end != BreakIterator.DONE) {
        if (tapCharIndex in start until end) {
            wordStart = start
            wordEnd = end
            break
        }
        start = end
        end = boundary.next()
    }

    // Map character indices back to TextPos
    val startMapping = charMap.getOrNull(wordStart) ?: return tapPos to tapPos
    val endMapping = charMap.getOrNull((wordEnd - 1).coerceAtLeast(0)) ?: return tapPos to tapPos

    return TextPos(0, startMapping.lineIndex, startMapping.colIndex) to
            TextPos(0, endMapping.lineIndex, endMapping.colIndex)
}

/**
 * Extract selected text from a page given start and end positions.
 */
fun getSelectedText(page: TextPage, start: TextPos, end: TextPos): String {
    val (s, e) = if (start.lineIndex < end.lineIndex ||
        (start.lineIndex == end.lineIndex && start.columnIndex <= end.columnIndex)
    ) start to end else end to start
    return page.getTextBetween(s.lineIndex, s.columnIndex, e.lineIndex, e.columnIndex)
}

/**
 * Bubble-style selection mini menu — pill shape with arrow, two-row expandable.
 * Ported from MoRealm HTML prototype.
 *
 * Main row:  复制 | 高亮 | 笔记 | 朗读 | ⋯
 * Extra row: 翻译 | 分享 | 查词  (shown on ⋯ tap)
 */
@Composable
fun SelectionToolbar(
    offset: Offset,
    onCopy: () -> Unit,
    onHighlight: () -> Unit,
    onNote: () -> Unit,
    onSpeak: () -> Unit,
    onTranslate: () -> Unit,
    onShare: () -> Unit,
    onLookup: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    var expanded by remember { mutableStateOf(false) }
    val arrowColor = MaterialTheme.colorScheme.surfaceContainerHigh

    // Position: center on offset, clamp to screen
    val xDp = with(density) { offset.x.toDp() }
    val yDp = with(density) { offset.y.toDp() }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .offset(
                    x = (xDp - 120.dp).coerceIn(8.dp, 240.dp),
                    y = (yDp - 56.dp).coerceAtLeast(8.dp),
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Pill body
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shadowElevation = 12.dp,
                tonalElevation = 2.dp,
            ) {
                Column {
                    // Main row
                    Row(
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        MenuBtn(Icons.Default.ContentCopy, "复制", onCopy)
                        MenuSep()
                        MenuBtn(Icons.Default.Edit, "高亮", onHighlight)
                        MenuSep()
                        MenuBtn(Icons.Default.ChatBubbleOutline, "笔记", onNote)
                        MenuSep()
                        MenuBtn(Icons.Default.VolumeUp, "朗读", onSpeak)
                        MenuSep()
                        // More button (icon only)
                        IconButton(
                            onClick = { expanded = !expanded },
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(
                                Icons.Default.MoreHoriz, "更多",
                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                    // Extra row
                    androidx.compose.animation.AnimatedVisibility(visible = expanded) {
                        Column {
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f),
                                thickness = 0.5.dp,
                                modifier = Modifier.padding(horizontal = 8.dp),
                            )
                            Row(
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                MenuBtn(Icons.Default.Translate, "翻译", onTranslate)
                                MenuSep()
                                MenuBtn(Icons.Default.Share, "分享", onShare)
                                MenuSep()
                                MenuBtn(Icons.Default.Search, "查词", onLookup)
                            }
                        }
                    }
                }
            }
            // Arrow pointing down toward selection
            Box(
                modifier = Modifier
                    .width(14.dp)
                    .height(7.dp)
                    .drawBehind {
                        val path = androidx.compose.ui.graphics.Path().apply {
                            moveTo(0f, 0f)
                            lineTo(size.width / 2, size.height)
                            lineTo(size.width, 0f)
                            close()
                        }
                        drawPath(
                            path,
                            color = arrowColor,
                        )
                    }
            )
        }
    }
}

@Composable
private fun MenuBtn(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            icon, label,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.height(1.dp))
        Text(
            label,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun MenuSep() {
    Box(
        Modifier
            .width(0.5.dp)
            .height(22.dp)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
    )
}

/**
 * Cursor handle composable — a small draggable circle.
 */
@Composable
fun CursorHandle(
    position: Offset,
    color: Color = MaterialTheme.colorScheme.primary,
    onDrag: (Offset) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    Box(
        modifier = modifier
            .offset {
                IntOffset(
                    (position.x - 8.dp.toPx()).roundToInt(),
                    (position.y).roundToInt(),
                )
            }
            .size(16.dp)
            .background(color, CircleShape)
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onDrag(Offset(change.position.x + position.x - 8.dp.toPx(), change.position.y + position.y))
                }
            }
    )
}
