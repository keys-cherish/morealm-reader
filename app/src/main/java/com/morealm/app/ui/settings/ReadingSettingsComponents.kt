package com.morealm.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.launch
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
// ── 线性矢量图标（Outlined 系列）—— 让设置行不再"干巴巴"，每行左侧用同款 ──
// 图标 + 圆角底色块统一视觉节奏。Outlined 比 Filled 更轻、更现代，跟 Material3
// 的整体审美一致。下面这一组是设置页用到的全部图标，用 alphabetical 排序方便维护。
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.Animation
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.FormatAlignCenter
import androidx.compose.material.icons.outlined.FormatColorText
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PlayCircleOutline
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.ScreenLockPortrait
import androidx.compose.material.icons.outlined.SyncAlt
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.ViewColumn
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.morealm.app.presentation.settings.ReadingSettingsViewModel
import com.morealm.app.ui.theme.LocalMoRealmColors

@Composable
internal fun ReadingSectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
        modifier = Modifier.padding(start = 24.dp, end = 16.dp, top = 18.dp, bottom = 8.dp),
    )
}

/**
 * Section 内的卡片包裹器。同 section 的所有 Row 组合成一张圆角卡片，
 * 内部 Row 自管 padding，不需要 Divider。
 */
@Composable
internal fun ReadingSettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(vertical = 4.dp),
            content = content,
        )
    }
}

/**
 * 行首图标块 —— 36dp 圆角方形容器 + 20dp Outlined 矢量图标，
 * 统一所有设置行的视觉节奏（对应 Image 15 设计）。底色用 surfaceVariant 半透明
 * 让图标在卡片背景上有微妙的"凹陷"感，不喧宾夺主。
 */
@Composable
internal fun RowIcon(icon: ImageVector) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon, null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
internal fun SettingsClickRow(
    icon: ImageVector,
    title: String,
    value: String,
    subtitle: String? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowIcon(icon)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface)
            val summary = listOf(value, subtitle.orEmpty()).filter { it.isNotBlank() }.joinToString(" · ")
            if (summary.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(summary, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(8.dp))
        Icon(Icons.Default.ChevronRight, contentDescription = null,
            modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)

    }
}

@Composable
internal fun SettingsToggleRow(
    icon: ImageVector,
    title: String,
    checked: Boolean,
    subtitle: String? = null,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowIcon(icon)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f))
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = MaterialTheme.colorScheme.primary,
                checkedThumbColor = Color.White,
            ),
        )
    }
}

// ── Dialogs ──

internal fun pageAnimLabel(key: String): String = when (key) {
    "cover" -> "覆盖"
    "simulation" -> "仿真"
    "slide" -> "平移"
    "vertical" -> "上下"
    "fade" -> "淡入"
    "none" -> "无"
    else -> key
}

internal fun screenTimeoutLabel(seconds: Int): String = when (seconds) {
    -1 -> "无"
    0 -> "跟随系统"
    60 -> "1分钟"
    120 -> "2分钟"
    300 -> "5分钟"
    600 -> "10分钟"
    else -> "${seconds}秒"
}

internal fun volumeKeyLongPressLabel(mode: String): String = when (mode) {
    "off" -> "默认（系统按键重复）"
    "page" -> "连续翻页"
    "chapter" -> "翻章"
    else -> "默认"
}

/** 章节标题对齐方式标签：0=左 / 1=中 / 2=右；其他值兜底为「左对齐」。 */
internal fun titleAlignLabel(mode: Int): String = when (mode) {
    1 -> "居中"
    2 -> "右对齐"
    else -> "左对齐"
}

internal fun innerSearchModeLabel(mode: String): String = when (mode) {
    "forward" -> "前向（之前）"
    "backward" -> "后向（之后）"
    else -> "全文"
}

/**
 * 章节标题对齐弹窗 —— 三选一（左/中/右），选完立即落 DataStore。
 *
 * 用 AlertDialog + RadioButton（与 [ScreenTimeoutDialog] 保持一致），
 * 不用 BottomSheetPicker 是因为它是 String-key，这里用 Int 更直接。
 */
@Composable
internal fun TitleAlignDialog(
    current: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val options = listOf(
        0 to "左对齐",
        1 to "居中",
        2 to "右对齐",
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("章节标题对齐") },
        text = {
            Column {
                options.forEach { (value, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(value) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = current == value,
                            onClick = { onSelect(value) },
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(label)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/**
 * 文内搜索方向弹窗 —— 三选一（全文 / 前向 / 后向），点完立即落 DataStore。
 * 每项带副文案说明语义，避免「前向/后向」这种容易在中文里混淆方向的术语让用户犹豫。
 */
@Composable
internal fun InnerSearchModeDialog(
    current: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    data class Opt(val value: String, val label: String, val sub: String)
    val options = listOf(
        Opt("all", "全文搜索", "显示全部命中，不区分位置"),
        Opt("forward", "前向搜索", "只显示当前页之前的命中"),
        Opt("backward", "后向搜索", "只显示当前页之后的命中"),
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("文内搜索方向") },
        text = {
            Column {
                options.forEach { opt ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(opt.value) }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = current == opt.value,
                            onClick = { onSelect(opt.value) },
                        )
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(opt.label, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                opt.sub,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
internal fun VolumeKeyLongPressDialog(
    current: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val options = listOf(
        "off" to "默认（系统按键重复）",
        "page" to "连续翻页",
        "chapter" to "翻章（长按 ~1 秒）",
    )
    BottomSheetPicker("音量键长按", options, current, onSelect, onDismiss)
}

@Composable
internal fun PageAnimDialog(current: String, onSelect: (String) -> Unit, onDismiss: () -> Unit) {
    val options = listOf(
        "cover" to "覆盖",
        "simulation" to "仿真",
        "slide" to "平移",
        "vertical" to "上下",
        "fade" to "淡入",
        "none" to "无",
    )
    BottomSheetPicker("翻页动画", options, current, onSelect, onDismiss)
}

/**
 * 点击区域九宫格编辑器 —— 每格一个按钮显示当前动作，点格弹动作菜单即改即存；
 * 「恢复默认」清 READER_TAP_GRID 回四角合成矩阵。改动会被拒绝当且仅当它会移除
 * 最后一格「呼出菜单」（防止用户把自己锁在菜单外）。
 */
@Composable
internal fun TapZoneGridDialog(
    currentGrid: List<String>,
    onSave: (List<String>) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val zones = com.morealm.app.ui.reader.ReaderTapZones
    var grid by remember(currentGrid) { mutableStateOf(currentGrid) }
    var pickerFor by remember { mutableStateOf(-1) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("点击区域") },
        text = {
            Column {
                Text(
                    "点任意分区修改其动作，改动立即生效。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
                Spacer(Modifier.height(10.dp))
                for (row in 0..2) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        for (col in 0..2) {
                            val idx = row * 3 + col
                            Box(Modifier.weight(1f)) {
                                OutlinedButton(
                                    onClick = { pickerFor = idx },
                                    modifier = Modifier.fillMaxWidth(),
                                    contentPadding = PaddingValues(horizontal = 2.dp, vertical = 10.dp),
                                ) {
                                    Text(
                                        zones.labelOf(grid.getOrElse(idx) { zones.ACTION_MENU }),
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1,
                                    )
                                }
                                DropdownMenu(
                                    expanded = pickerFor == idx,
                                    onDismissRequest = { pickerFor = -1 },
                                ) {
                                    zones.ACTIONS.forEach { action ->
                                        DropdownMenuItem(
                                            text = { Text(zones.labelOf(action)) },
                                            onClick = {
                                                pickerFor = -1
                                                val next = grid.toMutableList()
                                                    .also { it[idx] = action }
                                                if (next.none { t -> t == zones.ACTION_MENU }) {
                                                    android.widget.Toast.makeText(
                                                        context,
                                                        "至少保留一格「呼出菜单」",
                                                        android.widget.Toast.LENGTH_SHORT,
                                                    ).show()
                                                } else {
                                                    grid = next
                                                    onSave(next)
                                                }
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("完成") }
        },
        dismissButton = {
            TextButton(onClick = { onReset(); onDismiss() }) { Text("恢复默认") }
        },
    )
}

@Composable
internal fun ReadingDirectionDialog(
    current: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val options = listOf(
        "horizontal" to "横排",
        "vertical_rl" to "竖排（章标在右、正文在左）",
        "vertical_lr" to "竖排（章标在左、正文在右）",
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("竖排版") },
        text = {
            Column {
                options.forEach { (value, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(value) }
                            .padding(vertical = 14.dp),
                    ) {
                        Text(
                            label,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (current == value) LocalMoRealmColors.current.accent
                                    else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    if (value != options.last().first) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        },
        confirmButton = {},
    )
}

@Composable
internal fun ScreenTimeoutDialog(current: Int, onSelect: (Int) -> Unit, onDismiss: () -> Unit) {
    val options = listOf(
        -1 to "无",
        0 to "跟随系统",
        60 to "1分钟",
        120 to "2分钟",
        300 to "5分钟",
        600 to "10分钟",
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("屏幕关闭时间") },
        text = {
            Column {
                options.forEach { (value, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(value) }
                            .padding(vertical = 14.dp),
                    ) {
                        Text(
                            label,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (current == value) LocalMoRealmColors.current.accent
                                    else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    if (value != options.last().first) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        },
        confirmButton = {},
    )
}

@Composable
internal fun BottomSheetPicker(
    title: String,
    options: List<Pair<String, String>>,
    current: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEach { (key, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(key) }
                            .padding(vertical = 14.dp),
                    ) {
                        Text(
                            label,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (current == key) LocalMoRealmColors.current.accent
                                    else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    if (key != options.last().first) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        },
        confirmButton = {},
    )
}

// ── 选区菜单按钮自定义 ─────────────────────────────────────────────────
//
// 设计：用户在 6 个 SelectionMenuItem 之间分配位置（MAIN/EXPANDED/HIDDEN），
// 通过上下箭头调整列表顺序。位置选择用三段 chip；项数硬约束 "MAIN ≤ 3"，
// 超额时用 Toast 反馈而不是默默拒绝（用户体验上不让点击没反应）。列表顺序
// 保存时一并落 DataStore；同位置桶内的相对顺序就是渲染顺序。
//
// 日志埋点（tag = SelectionMenu）：
//   - 打开 dialog → DEBUG（在调用方）
//   - MAIN 满 3 触发 Toast 拦截 → DEBUG（带尝试升级的 item 名，方便看用户点了什么）
//   - 重置默认 → INFO（草稿层面，未必落盘）
//   - 保存 → INFO（在 ViewModel 里打）
//   - 取消（草稿改过但未保存）→ DEBUG，避免"打开就关"也刷一行
@Composable
internal fun SelectionMenuConfigDialog(
    config: com.morealm.app.domain.entity.SelectionMenuConfig,
    onSave: (com.morealm.app.domain.entity.SelectionMenuConfig) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    // 本地草稿 —— 用户操作直接改它，按"保存"才回调 onSave；按"取消"丢弃。
    // SnapshotStateList 让 swap/replace 自动触发重组。
    val draft = remember(config) {
        androidx.compose.runtime.mutableStateListOf<com.morealm.app.domain.entity.SelectionMenuConfig.SelectionMenuEntry>().apply {
            addAll(config.items)
        }
    }
    val mainCount = draft.count { it.position == com.morealm.app.domain.entity.SelectionMenuPosition.MAIN }

    // 关闭时统一走 onDismiss，按需打 DEBUG（仅在草稿改动过才打）。
    val handleDismiss = {
        if (draft.toList() != config.items) {
            com.morealm.app.core.log.AppLog.debug(
                "SelectionMenu", "dismiss without save (draft differed from saved)",
            )
        }
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = handleDismiss,
        title = { Text("选区菜单按钮") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "主行最多 3 个，始终可见；折叠行点「更多」展开；隐藏不渲染。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
                Spacer(Modifier.height(12.dp))
                draft.forEachIndexed { index, entry ->
                    SelectionMenuItemRow(
                        entry = entry,
                        canMoveUp = index > 0,
                        canMoveDown = index < draft.lastIndex,
                        onPositionChange = { newPos ->
                            // 想升级到 MAIN 但已满 3 个 → Toast 拦截，draft 不变；
                            // 同时打 DEBUG 让排查"为什么我点了没反应"有据可查。
                            if (newPos == com.morealm.app.domain.entity.SelectionMenuPosition.MAIN &&
                                entry.position != com.morealm.app.domain.entity.SelectionMenuPosition.MAIN &&
                                mainCount >= 3
                            ) {
                                com.morealm.app.core.log.AppLog.debug(
                                    "SelectionMenu",
                                    "block promote-to-MAIN: item=${entry.item.name} (mainCount already 3)",
                                )
                                android.widget.Toast.makeText(
                                    context, "主行最多 3 个，先把其他按钮移出主行",
                                    android.widget.Toast.LENGTH_SHORT,
                                ).show()
                                return@SelectionMenuItemRow
                            }
                            draft[index] = entry.copy(position = newPos)
                        },
                        onMoveUp = {
                            if (index > 0) {
                                val tmp = draft[index]
                                draft[index] = draft[index - 1]
                                draft[index - 1] = tmp
                            }
                        },
                        onMoveDown = {
                            if (index < draft.lastIndex) {
                                val tmp = draft[index]
                                draft[index] = draft[index + 1]
                                draft[index + 1] = tmp
                            }
                        },
                    )
                    if (index < draft.lastIndex) {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                // ViewModel.setSelectionMenuConfig 内部会打 INFO 日志，这里不重复
                onSave(
                    com.morealm.app.domain.entity.SelectionMenuConfig(draft.toList()).normalize(),
                )
                onDismiss()
            }) { Text("保存") }
        },
        dismissButton = {
            // 重置 + 取消并排：重置只改 draft（INFO 记录意图），用户还能继续编辑或保存；
            // 取消直接关闭丢弃。
            Row {
                TextButton(onClick = {
                    com.morealm.app.core.log.AppLog.info(
                        "SelectionMenu", "draft reset to DEFAULT (not yet persisted)",
                    )
                    draft.clear()
                    draft.addAll(com.morealm.app.domain.entity.SelectionMenuConfig.DEFAULT.items)
                }) { Text("重置") }
                TextButton(onClick = handleDismiss) { Text("取消") }
            }
        },
    )
}

/**
 * 单行：item 名称 + 三段位置选择 + 上/下移动。
 * 在 AlertDialog 里宽度有限，用紧凑 chip 比下拉菜单点击次数更少。
 */
@Composable
internal fun SelectionMenuItemRow(
    entry: com.morealm.app.domain.entity.SelectionMenuConfig.SelectionMenuEntry,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onPositionChange: (com.morealm.app.domain.entity.SelectionMenuPosition) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    Column(modifier = Modifier.padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                entry.item.displayName,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = onMoveUp,
                enabled = canMoveUp,
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    Icons.Default.KeyboardArrowUp,
                    contentDescription = "上移",
                    modifier = Modifier.size(20.dp),
                )
            }
            IconButton(
                onClick = onMoveDown,
                enabled = canMoveDown,
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    Icons.Default.KeyboardArrowDown,
                    contentDescription = "下移",
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            PositionChip(
                label = "主行",
                selected = entry.position == com.morealm.app.domain.entity.SelectionMenuPosition.MAIN,
                onClick = { onPositionChange(com.morealm.app.domain.entity.SelectionMenuPosition.MAIN) },
                modifier = Modifier.weight(1f),
            )
            PositionChip(
                label = "折叠",
                selected = entry.position == com.morealm.app.domain.entity.SelectionMenuPosition.EXPANDED,
                onClick = { onPositionChange(com.morealm.app.domain.entity.SelectionMenuPosition.EXPANDED) },
                modifier = Modifier.weight(1f),
            )
            PositionChip(
                label = "隐藏",
                selected = entry.position == com.morealm.app.domain.entity.SelectionMenuPosition.HIDDEN,
                onClick = { onPositionChange(com.morealm.app.domain.entity.SelectionMenuPosition.HIDDEN) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** 紧凑的位置标签 chip —— 选中态 primary，未选 surfaceVariant。 */
@Composable
internal fun PositionChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val container = if (selected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.surfaceVariant
    val content = if (selected) MaterialTheme.colorScheme.onPrimary
        else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        onClick = onClick,
        color = container,
        contentColor = content,
        shape = RoundedCornerShape(8.dp),
        modifier = modifier,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}
