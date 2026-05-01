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
 *
 * 渲染由用户在「阅读设置 → 选区菜单按钮」配置的 [SelectionMenuConfig] 决定：
 *   - MAIN 行：始终可见，最多 3 个动作；如果 [SelectionMenuPosition.EXPANDED]
 *     桶里至少有一个动作，主行尾部追加「更多」按钮切换展开行可见性。
 *   - EXPANDED 行：点开「更多」后显示。
 *   - HIGHLIGHT：作为一个普通按钮放进 MAIN 或 EXPANDED；点击不直接保存，
 *     而是切换底部的 5 色调色板浮层，用户挑色后才落 DB。
 *   - HIDDEN 桶里的动作根本不渲染。
 *
 * 没有传 [config] 时退回 [com.morealm.app.domain.entity.SelectionMenuConfig.DEFAULT]，保持和老调用方等价。
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
     * 色盘点击回调；参数为 ARGB Int。null 时调色板永远不显示，HIGHLIGHT 按钮
     * 自动从渲染列表里摘掉（即便 config 把它放在 MAIN）。
     */
    onHighlight: ((colorArgb: Int) -> Unit)? = null,
    onDismiss: () -> Unit,
    config: com.morealm.app.domain.entity.SelectionMenuConfig =
        com.morealm.app.domain.entity.SelectionMenuConfig.DEFAULT,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    var expanded by remember { mutableStateOf(false) }
    /** HIGHLIGHT 按钮点开调色板的开关。和 [expanded] 解耦：用户在主行点
     *  HIGHLIGHT 不展开扩展行；在扩展行点 HIGHLIGHT 也不收起扩展行。 */
    var paletteVisible by remember { mutableStateOf(false) }
    val arrowColor = MaterialTheme.colorScheme.surfaceContainerHigh

    // 按 position 切分，并把 HIDDEN / 不可用的 HIGHLIGHT 摘掉。如果 onHighlight
    // 为 null，HIGHLIGHT 这个动作即使在 config 里被设为 MAIN/EXPANDED 也忽略。
    val grouped = remember(config, onHighlight) {
        val raw = config.groupedByPosition()
        val filterHighlight = onHighlight == null
        fun List<com.morealm.app.domain.entity.SelectionMenuItem>?.cleaned() =
            this.orEmpty().filter {
                !filterHighlight || it != com.morealm.app.domain.entity.SelectionMenuItem.HIGHLIGHT
            }
        val main = raw[com.morealm.app.domain.entity.SelectionMenuPosition.MAIN].cleaned().take(3)
        val ext = raw[com.morealm.app.domain.entity.SelectionMenuPosition.EXPANDED].cleaned()
        main to ext
    }
    val mainItems = grouped.first
    val expandedItems = grouped.second
    val hasExpandedRow = expandedItems.isNotEmpty()

    // 动态宽度：每个 MenuBtn ~52dp + 「更多」按钮 ~36dp + 两侧内边距 ~12dp。
    // 下限 120dp 保证箭头 / 调色板不被挤瘦得难看；上限不越屏。
    val mainColumns = mainItems.size + if (hasExpandedRow) 1 else 0
    val menuWidthDp = (mainColumns.coerceAtLeast(1) * 56 + 16)
        .coerceAtLeast(120)
        .coerceAtMost(configuration.screenWidthDp - 16)
    val menuWidth = menuWidthDp.dp

    // Position: center on offset, clamp to screen
    val xDp = with(density) { offset.x.toDp() }
    val yDp = with(density) { offset.y.toDp() }
    val screenWidth = configuration.screenWidthDp.dp
    val screenHeight = configuration.screenHeightDp.dp
    // 高度估算：主行 46dp；扩展行（如果展开且有内容）+~40dp；调色板（如果可见
    // 且 HIGHLIGHT 存在于某个可见行）+~38dp。这是 menu 整体下边界检测用，
    // 不影响实际渲染高度。
    val highlightVisibleInMainOrExpanded =
        com.morealm.app.domain.entity.SelectionMenuItem.HIGHLIGHT in mainItems ||
            (expanded && com.morealm.app.domain.entity.SelectionMenuItem.HIGHLIGHT in expandedItems)
    var menuHeightDp = 46
    if (expanded && hasExpandedRow) menuHeightDp += 40
    if (paletteVisible && onHighlight != null && highlightVisibleInMainOrExpanded) menuHeightDp += 38
    val menuHeight = menuHeightDp.dp
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
                    // ── Main row ──
                    Row(
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        mainItems.forEachIndexed { idx, item ->
                            if (idx > 0) MenuSep()
                            ItemButton(
                                item = item,
                                onCopy = onCopy,
                                onSpeak = onSpeak,
                                onTranslate = onTranslate,
                                onShare = onShare,
                                onLookup = onLookup,
                                onToggleHighlight = { paletteVisible = !paletteVisible },
                            )
                        }
                        if (hasExpandedRow) {
                            if (mainItems.isNotEmpty()) MenuSep()
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
                    }
                    // ── Expanded row ──
                    androidx.compose.animation.AnimatedVisibility(visible = expanded && hasExpandedRow) {
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
                                expandedItems.forEachIndexed { idx, item ->
                                    if (idx > 0) MenuSep()
                                    ItemButton(
                                        item = item,
                                        onCopy = onCopy,
                                        onSpeak = onSpeak,
                                        onTranslate = onTranslate,
                                        onShare = onShare,
                                        onLookup = onLookup,
                                        onToggleHighlight = { paletteVisible = !paletteVisible },
                                    )
                                }
                            }
                        }
                    }
                    // ── Highlight palette row ──
                    // 仅当 onHighlight 非空、调色板被显式打开、且 HIGHLIGHT 按钮
                    // 当前真的在某个可见行（主行 / 已展开的扩展行）才渲染，
                    // 防止"HIGHLIGHT 在 EXPANDED 但用户没点更多"的情况下空显示。
                    val showPalette = paletteVisible && onHighlight != null &&
                        (com.morealm.app.domain.entity.SelectionMenuItem.HIGHLIGHT in mainItems ||
                            (expanded && com.morealm.app.domain.entity.SelectionMenuItem.HIGHLIGHT in expandedItems))
                    androidx.compose.animation.AnimatedVisibility(visible = showPalette) {
                        Column {
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
                                            .clickable { onHighlight?.invoke(argb) },
                                    )
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

/** 把 [com.morealm.app.domain.entity.SelectionMenuItem] 映射到对应的图标 +
 *  标签 + 点击回调。HIGHLIGHT 的点击不直接落 DB —— 而是切换调色板浮层，由
 *  用户在调色板里挑色才真正保存。 */
@Composable
private fun ItemButton(
    item: com.morealm.app.domain.entity.SelectionMenuItem,
    onCopy: () -> Unit,
    onSpeak: () -> Unit,
    onTranslate: () -> Unit,
    onShare: () -> Unit,
    onLookup: () -> Unit,
    onToggleHighlight: () -> Unit,
) {
    when (item) {
        com.morealm.app.domain.entity.SelectionMenuItem.COPY ->
            MenuBtn(Icons.Default.ContentCopy, "复制", onCopy)
        com.morealm.app.domain.entity.SelectionMenuItem.SPEAK ->
            MenuBtn(Icons.Default.VolumeUp, "朗读", onSpeak)
        com.morealm.app.domain.entity.SelectionMenuItem.TRANSLATE ->
            MenuBtn(Icons.Default.Translate, "翻译", onTranslate)
        com.morealm.app.domain.entity.SelectionMenuItem.SHARE ->
            MenuBtn(Icons.Default.Share, "分享", onShare)
        com.morealm.app.domain.entity.SelectionMenuItem.LOOKUP ->
            MenuBtn(Icons.Default.Search, "查词", onLookup)
        com.morealm.app.domain.entity.SelectionMenuItem.HIGHLIGHT ->
            MenuBtn(Icons.Default.FormatColorFill, "高亮", onToggleHighlight)
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
