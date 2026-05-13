package com.morealm.app.ui.reader.toolbar

import androidx.compose.animation.*
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.morealm.app.domain.entity.ReaderTool
import com.morealm.app.domain.entity.ReaderToolLayout
import com.morealm.app.domain.entity.ReaderToolZone

/**
 * 编辑态底栏：显示 Bottom 区工具 + Hidden 区工具，支持 ± 切换和拖拽排序。
 * 非编辑态不渲染此 composable —— 由调用方根据 editing state 控制 visibility。
 */
@Composable
fun ReaderToolBarEditor(
    layout: ReaderToolLayout,
    onToggleVisibility: (ReaderTool) -> Unit,
    onReorder: (ReaderToolZone, Int, Int) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bottomTools = layout.toolsIn(ReaderToolZone.Bottom)
    val hiddenTools = layout.toolsIn(ReaderToolZone.Hidden)

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.97f),
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        tonalElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .navigationBarsPadding(),
        ) {
            // Header: "编辑工具栏" + Done button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "编辑工具栏",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                TextButton(onClick = onDone) {
                    Text("完成", fontWeight = FontWeight.SemiBold)
                }
            }

            Spacer(Modifier.height(8.dp))

            // Bottom zone label
            Text(
                "显示中",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))

            // Bottom tools row
            ToolZoneRow(
                tools = bottomTools,
                zone = ReaderToolZone.Bottom,
                onToggleVisibility = onToggleVisibility,
                onReorder = onReorder,
            )

            Spacer(Modifier.height(16.dp))

            // Hidden zone label
            Text(
                "更多工具",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))

            // Hidden tools row
            ToolZoneRow(
                tools = hiddenTools,
                zone = ReaderToolZone.Hidden,
                onToggleVisibility = onToggleVisibility,
                onReorder = onReorder,
            )
        }
    }
}

@Composable
private fun ToolZoneRow(
    tools: List<ReaderTool>,
    zone: ReaderToolZone,
    onToggleVisibility: (ReaderTool) -> Unit,
    onReorder: (ReaderToolZone, Int, Int) -> Unit,
) {
    val listState = rememberLazyListState()
    val haptic = LocalHapticFeedback.current

    // Drag state
    var draggedIndex by remember { mutableIntStateOf(-1) }
    var dragOffsetX by remember { mutableFloatStateOf(0f) }

    LazyRow(
        state = listState,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        itemsIndexed(tools, key = { _, tool -> tool.id }) { index, tool ->
            val isDragging = draggedIndex == index
            val elevation by animateDpAsState(
                if (isDragging) 8.dp else 0.dp,
                label = "drag_elevation",
            )

            Box(
                modifier = Modifier
                    .zIndex(if (isDragging) 1f else 0f)
                    .shadow(elevation, RoundedCornerShape(12.dp))
                    .animateItem()
                    .pointerInput(zone, tools.size) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                draggedIndex = index
                                dragOffsetX = 0f
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                dragOffsetX += dragAmount.x
                                val itemWidth = 72.dp.toPx()
                                val slots = (dragOffsetX / itemWidth).toInt()
                                if (slots != 0) {
                                    val target = (draggedIndex + slots).coerceIn(0, tools.size - 1)
                                    if (target != draggedIndex) {
                                        onReorder(zone, draggedIndex, target)
                                        draggedIndex = target
                                        dragOffsetX -= slots * itemWidth
                                    }
                                }
                            },
                            onDragEnd = {
                                draggedIndex = -1
                                dragOffsetX = 0f
                            },
                            onDragCancel = {
                                draggedIndex = -1
                                dragOffsetX = 0f
                            },
                        )
                    },
            ) {
                EditableToolItem(
                    tool = tool,
                    isInHidden = zone == ReaderToolZone.Hidden,
                    isDragging = isDragging,
                    onToggle = { onToggleVisibility(tool) },
                )
            }
        }
    }
}

@Composable
private fun EditableToolItem(
    tool: ReaderTool,
    isInHidden: Boolean,
    isDragging: Boolean,
    onToggle: () -> Unit,
) {
    val bgColor = if (isDragging) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }

    Box(contentAlignment = Alignment.TopEnd) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .width(68.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(bgColor)
                .padding(vertical = 8.dp, horizontal = 4.dp),
        ) {
            Icon(
                imageVector = tool.icon(),
                contentDescription = tool.label,
                modifier = Modifier.size(24.dp),
                tint = if (isInHidden) {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            Spacer(Modifier.height(4.dp))
            Text(
                tool.label,
                style = MaterialTheme.typography.labelSmall,
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                maxLines = 1,
                color = if (isInHidden) {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
        }

        // Badge: minus (remove from visible) or plus (add to visible)
        if (tool.removable) {
            Box(
                modifier = Modifier
                    .offset(x = 4.dp, y = (-4).dp)
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(
                        if (isInHidden) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error
                    )
                    .clickable(onClick = onToggle),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (isInHidden) Icons.Default.Add else Icons.Default.Remove,
                    contentDescription = if (isInHidden) "添加" else "移除",
                    modifier = Modifier.size(14.dp),
                    tint = if (isInHidden) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onError,
                )
            }
        }
    }
}

/**
 * 首次引导气泡 —— 在用户首次点击中间区域触发菜单时显示。
 * 主题色动态跟随 MaterialTheme.colorScheme.primary。
 */
@Composable
fun ReaderEditGuideTooltip(
    visible: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(300)) + slideInVertically(tween(300)) { it / 2 },
        exit = fadeOut(tween(200)),
        modifier = modifier,
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            tonalElevation = 4.dp,
            shadowElevation = 4.dp,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "长按底部工具栏可进入编辑模式，自定义按钮排列",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(24.dp),
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "关闭",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        }
    }
}
