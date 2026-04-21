package com.morealm.app.ui.reader.renderer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.morealm.app.domain.render.TextColumn
import com.morealm.app.domain.render.TextLine
import com.morealm.app.domain.render.TextPage
import com.morealm.app.domain.render.TextPos
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
 * Extract selected text from a page given start and end positions.
 */
fun getSelectedText(page: TextPage, start: TextPos, end: TextPos): String {
    val (s, e) = if (start.lineIndex < end.lineIndex ||
        (start.lineIndex == end.lineIndex && start.columnIndex <= end.columnIndex)
    ) start to end else end to start
    return page.getTextBetween(s.lineIndex, s.columnIndex, e.lineIndex, e.columnIndex)
}

/**
 * Floating selection toolbar with Copy / Speak / Lookup actions.
 */
@Composable
fun SelectionToolbar(
    offset: Offset,
    onCopy: () -> Unit,
    onSpeak: () -> Unit,
    onLookup: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val xDp = with(density) { offset.x.toDp() }
    val yDp = with(density) { offset.y.toDp() }

    Box(modifier = modifier.fillMaxSize()) {
        Surface(
            modifier = Modifier
                .offset(
                    x = (xDp - 80.dp).coerceAtLeast(8.dp),
                    y = (yDp - 48.dp).coerceAtLeast(8.dp),
                ),
            shape = RoundedCornerShape(12.dp),
            color = Color(0xF0212121),
            shadowElevation = 8.dp,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                ToolbarButton(icon = Icons.Default.ContentCopy, label = "复制", onClick = onCopy)
                ToolbarButton(icon = Icons.Default.RecordVoiceOver, label = "朗读", onClick = onSpeak)
                ToolbarButton(icon = Icons.Default.Translate, label = "查词", onClick = onLookup)
            }
        }
    }
}

@Composable
private fun ToolbarButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, label, tint = Color.White, modifier = Modifier.size(18.dp))
        Text(label, color = Color.White.copy(alpha = 0.8f), fontSize = 10.sp)
    }
}

/**
 * Cursor handle composable — a small draggable circle.
 */
@Composable
fun CursorHandle(
    position: Offset,
    color: Color = Color(0xFF2196F3),
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
