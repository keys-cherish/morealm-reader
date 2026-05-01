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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.morealm.app.domain.render.BaseColumn
import com.morealm.app.domain.render.TextBaseColumn
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
    var reverseStartCursor by mutableStateOf(false)
    var reverseEndCursor by mutableStateOf(false)

    fun clear() {
        isActive = false
        startPos = null
        endPos = null
        reverseStartCursor = false
        reverseEndCursor = false
    }

    fun setSelection(start: TextPos, end: TextPos) {
        startPos = start
        endPos = end
        isActive = true
    }

    fun selectStartMoveIndex(textPos: TextPos) {
        startPos = textPos
        isActive = textPos.isSelected() && endPos?.isSelected() != false
    }

    fun selectEndMoveIndex(textPos: TextPos) {
        endPos = textPos
        isActive = startPos?.isSelected() != false && textPos.isSelected()
    }

    fun selectStartMove(textPos: TextPos) {
        val end = endPos
        if (end == null || textPos.compare(end) <= 0) {
            reverseStartCursor = false
            selectStartMoveIndex(textPos)
        } else {
            reverseStartCursor = true
            reverseEndCursor = false
            endPos = textPos
            startPos = end
            isActive = true
        }
    }

    fun selectEndMove(textPos: TextPos) {
        val start = startPos
        if (start == null || textPos.compare(start) >= 0) {
            reverseEndCursor = false
            selectEndMoveIndex(textPos)
        } else {
            reverseEndCursor = true
            reverseStartCursor = false
            startPos = textPos
            endPos = start
            isActive = true
        }
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

/** Legado ContentTextView.touchRough equivalent for selection handles. */
fun hitTestPageRough(page: TextPage, x: Float, y: Float, relativePagePos: Int = 0): TextPos? {
    val paddingTop = page.paddingTop
    for (lineIndex in page.lines.indices) {
        val line = page.lines[lineIndex]
        if (line.isTouchY(y - paddingTop)) {
            for (charIndex in line.columns.indices) {
                if (line.columns[charIndex].isTouch(x)) {
                    return TextPos(relativePagePos, lineIndex, charIndex)
                }
            }
            if (line.columns.isNotEmpty()) {
                val isLast = line.columns.first().start < x
                val charIndex = if (isLast) line.columns.lastIndex + 1 else -1
                return TextPos(relativePagePos, lineIndex, charIndex)
            }
        }
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
 * Ported from Legado ReadView.onLongPress() 鈥?uses BreakIterator to find word boundaries
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
            if (col is TextBaseColumn) {
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

    return TextPos(tapPos.relativePagePos, startMapping.lineIndex, startMapping.colIndex) to
            TextPos(tapPos.relativePagePos, endMapping.lineIndex, endMapping.colIndex)
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
 * Main row: 复制 | 朗读 | 更多
 * Extra row 1 (展开时): 翻译 | 分享 | 查词
 * Extra row 2 (展开时): 5 色高亮调色板 — 点哪个色就以那个色保存高亮
 */
@Composable
fun SelectionToolbar(
    offset: Offset,
    onCopy: () -> Unit,
    onSpeak: () -> Unit,
    onTranslate: () -> Unit,
    onShare: () -> Unit,
    onLookup: () -> Unit,
    /**
     * 色盘点击回调；参数为 ARGB Int。null 时不显示调色板（兼容老调用方）。
     * 实现方收到回调后应自行 commit Highlight + clear selection。
     */
    onHighlight: ((colorArgb: Int) -> Unit)? = null,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    var expanded by remember { mutableStateOf(false) }
    val arrowColor = MaterialTheme.colorScheme.surfaceContainerHigh

    // Position: center on offset, clamp to screen
    val xDp = with(density) { offset.x.toDp() }
    val yDp = with(density) { offset.y.toDp() }
    val screenWidth = configuration.screenWidthDp.dp
    val screenHeight = configuration.screenHeightDp.dp
    val menuWidth = 188.dp
    // 高度根据展开状态 + 是否有调色板浮动；展开后多一行 (~36.dp)。
    val paletteRow = onHighlight != null
    val expandedExtra = if (paletteRow) 78.dp else 40.dp
    val menuHeight = if (expanded) 46.dp + expandedExtra else 46.dp
    val showBelow = yDp - menuHeight - 12.dp < 8.dp
    val menuX = (xDp - menuWidth / 2).coerceIn(8.dp, screenWidth - menuWidth - 8.dp)
    val menuY = if (showBelow) {
        (yDp + 16.dp).coerceAtMost(screenHeight - menuHeight - 16.dp)
    } else {
        (yDp - menuHeight - 12.dp).coerceAtLeast(8.dp)
    }
    val arrowX = (xDp - menuX - 7.dp).coerceIn(18.dp, menuWidth - 18.dp)

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .offset(x = menuX, y = menuY)
                .width(menuWidth),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (showBelow) {
                ToolbarArrow(arrowColor, pointsDown = false, modifier = Modifier.offset(x = arrowX - menuWidth / 2))
            }
            // Pill body
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shadowElevation = 12.dp,
                tonalElevation = 2.dp,
                modifier = Modifier.widthIn(max = menuWidth),
            ) {
                Column {
                    // Main row
                    Row(
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        MenuBtn(Icons.Default.ContentCopy, "复制", onCopy)
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
                            // Highlight palette row — 5 preset colors. Tap any =
                            // commit a Highlight with that ARGB and dismiss the
                            // toolbar via the onHighlight callback (which is
                            // expected to clear selectionState too).
                            if (onHighlight != null) {
                                HorizontalDivider(
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f),
                                    thickness = 0.5.dp,
                                    modifier = Modifier.padding(horizontal = 8.dp),
                                )
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 8.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceEvenly,
                                ) {
                                    HighlightPalette.PRESETS.forEach { argb ->
                                        Box(
                                            modifier = Modifier
                                                .size(20.dp)
                                                .clip(CircleShape)
                                                .background(Color(argb))
                                                .clickable { onHighlight(argb) },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            // Arrow pointing down toward selection
            if (!showBelow) {
                ToolbarArrow(arrowColor, pointsDown = true, modifier = Modifier.offset(x = arrowX - menuWidth / 2))
            }
        }
    }
}

@Composable
private fun ToolbarArrow(
    color: Color,
    pointsDown: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .width(14.dp)
            .height(7.dp)
            .drawBehind {
                val path = androidx.compose.ui.graphics.Path().apply {
                    if (pointsDown) {
                        moveTo(0f, 0f)
                        lineTo(size.width / 2, size.height)
                        lineTo(size.width, 0f)
                    } else {
                        moveTo(0f, size.height)
                        lineTo(size.width / 2, 0f)
                        lineTo(size.width, size.height)
                    }
                    close()
                }
                drawPath(path, color = color)
            }
    )
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
 *
 * Coordinate handling
 * - The previous version computed the global drag target as
 *   `change.position + position - 8.dp`, where `change.position` is the
 *   pointer location within the handle's own bounds. Two problems combined
 *   to produce visible flicker / jumping while dragging:
 *     1. `position` is read inside a `pointerInput(Unit)` scope. Because the
 *        coroutine never restarts (key = Unit), the parameter captured
 *        there was the value from the FIRST composition — never updated.
 *     2. As `onDrag` updates the selection, the handle is re-laid-out at a
 *        new position, which shifts the coordinate origin used by
 *        `change.position`. The next event reports a position relative to
 *        the new origin, but the formula adds the OLD `position`, so the
 *        global value teleports between frames.
 *
 * Fix: snapshot the handle's anchor at drag start and accumulate framework-
 * provided `dragAmount` deltas (which are in screen-stable coordinates and
 * unaffected by the handle's own movement). [rememberUpdatedState] keeps the
 * snapshot honest if the handle anchor changes between drags.
 */
@Composable
fun CursorHandle(
    position: Offset,
    color: Color = MaterialTheme.colorScheme.primary,
    onDrag: (Offset) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val positionState = rememberUpdatedState(position)
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
                var draggedTo = Offset.Zero
                detectDragGestures(
                    onDragStart = {
                        // Take the latest anchor as the drag's starting global
                        // coordinate. From here on we only add framework
                        // dragAmount deltas, so the handle's own re-layout
                        // doesn't perturb the running total.
                        draggedTo = positionState.value
                    },
                ) { change, dragAmount ->
                    change.consume()
                    draggedTo += dragAmount
                    onDrag(draggedTo)
                }
            }
    )
}

/**
 * 高亮调色板预设。
 *
 * 5 色覆盖常用的「黄绿蓝粉紫」语义集，alpha 0.4 让底色透出文字 — 类似 Apple Books
 * / Legado 的可读性折衷：太浅看不见标记，太深盖住字。
 *
 * 色相按可见度排序（黄最显眼放最左，紫最低调放最右），用户从左到右选 = 由强到弱
 * 标记。每个 Int 是 ARGB；存到 Highlight.colorArgb。
 */
object HighlightPalette {
    /** 黄 — 重点 */
    const val YELLOW: Int = 0x66FFEB3B.toInt()
    /** 绿 — 已读 / 同意 */
    const val GREEN: Int = 0x6669F0AE.toInt()
    /** 蓝 — 待查 / 引用 */
    const val BLUE: Int = 0x6664B5F6.toInt()
    /** 粉 — 情感 / 喜欢 */
    const val PINK: Int = 0x66F48FB1.toInt()
    /** 紫 — 疑问 / 待思考 */
    const val PURPLE: Int = 0x66BA68C8.toInt()

    val PRESETS: List<Int> = listOf(YELLOW, GREEN, BLUE, PINK, PURPLE)
}

/**
 * 把页面坐标 (x, y) 映射回章节字符 offset；命中 line 外区域返回 null。
 *
 * 实现思路：先用现成的 [hitTestPage] 拿到 TextPos，再走
 * `page.chapterPosition + getPosByLineColumn` 转回 chapter-level 字符位置。
 * 这是 ReaderSelectionToolbar.selectedStartChapterPosition() 的复用版本，
 * 单独抽出来给"点击已存高亮"的命中检测路径用。
 */
fun chapterPositionAt(page: TextPage, x: Float, y: Float): Int? {
    val pos = hitTestPage(page, x, y) ?: return null
    return page.chapterPosition + page.getPosByLineColumn(pos.lineIndex, pos.columnIndex)
}

/**
 * 已存高亮的弹窗式动作菜单（删除 / 分享）。
 *
 * 与 [SelectionToolbar] 共用气泡造型，但功能精简到两按钮，避免和编辑选区时的
 * 主 toolbar 视觉混淆。颜色徽章左侧让用户瞥一眼就能确认点中了哪条高亮。
 */
@Composable
fun HighlightActionToolbar(
    offset: Offset,
    colorArgb: Int,
    onDelete: () -> Unit,
    onShare: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val arrowColor = MaterialTheme.colorScheme.surfaceContainerHigh

    val xDp = with(density) { offset.x.toDp() }
    val yDp = with(density) { offset.y.toDp() }
    val screenWidth = configuration.screenWidthDp.dp
    val screenHeight = configuration.screenHeightDp.dp
    val menuWidth = 156.dp
    val menuHeight = 46.dp
    val showBelow = yDp - menuHeight - 12.dp < 8.dp
    val menuX = (xDp - menuWidth / 2).coerceIn(8.dp, screenWidth - menuWidth - 8.dp)
    val menuY = if (showBelow) {
        (yDp + 16.dp).coerceAtMost(screenHeight - menuHeight - 16.dp)
    } else {
        (yDp - menuHeight - 12.dp).coerceAtLeast(8.dp)
    }
    val arrowX = (xDp - menuX - 7.dp).coerceIn(18.dp, menuWidth - 18.dp)

    Box(modifier = modifier.fillMaxSize()) {
        // Tap-outside dismiss layer — covers the whole screen with a transparent
        // box that swallows clicks. Without it the user has to tap precisely
        // outside the toolbar to dismiss; here ANY tap that isn't on a button
        // closes the menu (matches the behaviour of SelectionToolbar's
        // backdrop, except SelectionToolbar relies on selection.clear() being
        // called from each action).
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
        )
        Column(
            modifier = Modifier
                .offset(x = menuX, y = menuY)
                .width(menuWidth),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (showBelow) {
                ToolbarArrow(arrowColor, pointsDown = false, modifier = Modifier.offset(x = arrowX - menuWidth / 2))
            }
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shadowElevation = 12.dp,
                tonalElevation = 2.dp,
                modifier = Modifier.widthIn(max = menuWidth),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(14.dp)
                            .clip(CircleShape)
                            .background(Color(colorArgb)),
                    )
                    Spacer(Modifier.width(8.dp))
                    MenuBtn(Icons.Default.Share, "分享", onShare)
                    MenuSep()
                    MenuBtn(Icons.Default.Delete, "删除", onDelete)
                }
            }
            if (!showBelow) {
                ToolbarArrow(arrowColor, pointsDown = true, modifier = Modifier.offset(x = arrowX - menuWidth / 2))
            }
        }
    }
}
