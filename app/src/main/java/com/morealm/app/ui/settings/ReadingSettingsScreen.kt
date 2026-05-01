package com.morealm.app.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.morealm.app.presentation.settings.ReadingSettingsViewModel
import com.morealm.app.ui.theme.LocalMoRealmColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadingSettingsScreen(
    onBack: () -> Unit = {},
    viewModel: ReadingSettingsViewModel = hiltViewModel(),
) {
    val moColors = LocalMoRealmColors.current
    val pageAnim by viewModel.pageAnim.collectAsStateWithLifecycle()
    val tapLeftAction by viewModel.tapLeftAction.collectAsStateWithLifecycle()
    val volumeKeyPage by viewModel.volumeKeyPage.collectAsStateWithLifecycle()
    val volumeKeyReverse by viewModel.volumeKeyReverse.collectAsStateWithLifecycle()
    val headsetButtonPage by viewModel.headsetButtonPage.collectAsStateWithLifecycle()
    val volumeKeyLongPress by viewModel.volumeKeyLongPress.collectAsStateWithLifecycle()
    val resumeLastRead by viewModel.resumeLastRead.collectAsStateWithLifecycle()
    val longPressUnderline by viewModel.longPressUnderline.collectAsStateWithLifecycle()
    val screenTimeout by viewModel.screenTimeout.collectAsStateWithLifecycle()
    val showStatusBar by viewModel.showStatusBar.collectAsStateWithLifecycle()
    val showChapterName by viewModel.showChapterName.collectAsStateWithLifecycle()
    val showTimeBattery by viewModel.showTimeBattery.collectAsStateWithLifecycle()
    val customTxtChapterRegex by viewModel.customTxtChapterRegex.collectAsStateWithLifecycle()

    // Dialog states
    var showAnimDialog by remember { mutableStateOf(false) }
    var showTapLeftDialog by remember { mutableStateOf(false) }
    var showTimeoutDialog by remember { mutableStateOf(false) }
    var showLongPressDialog by remember { mutableStateOf(false) }
    var showSelectionMenuDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("阅读设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            // ── 操作 ──
            SectionHeader("操作")

            SettingsClickRow(
                title = "翻页动画",
                value = pageAnimLabel(pageAnim),
                onClick = { showAnimDialog = true },
            )
            SettingsDivider()
            SettingsClickRow(
                title = "轻按页面左侧",
                value = if (tapLeftAction == "next") "翻到下一页" else "翻到上一页",
                onClick = { showTapLeftDialog = true },
            )
            SettingsDivider()
            SettingsToggleRow("音量键翻页", volumeKeyPage) { viewModel.setVolumeKeyPage(it) }
            SettingsDivider()
            // 仅在开启了"音量键翻页"时显示反转和长按选项，避免页面塞太多无意义条目
            if (volumeKeyPage) {
                SettingsToggleRow(
                    title = "音量键方向反转",
                    checked = volumeKeyReverse,
                ) { viewModel.setVolumeKeyReverse(it) }
                SettingsDivider()
                SettingsClickRow(
                    title = "音量键长按",
                    value = volumeKeyLongPressLabel(volumeKeyLongPress),
                    onClick = { showLongPressDialog = true },
                )
                SettingsDivider()
            }
            SettingsToggleRow(
                title = "耳机/蓝牙翻页器",
                checked = headsetButtonPage,
            ) { viewModel.setHeadsetButtonPage(it) }
            SettingsDivider()
            SettingsToggleRow("启动后继续上次阅读", resumeLastRead) { viewModel.setResumeLastRead(it) }
            SettingsDivider()
            SettingsToggleRow("长按文字划线", longPressUnderline) { viewModel.setLongPressUnderline(it) }
            SettingsDivider()
            // 选区 mini-menu 按钮自定义入口 —— 显示当前主行项数作 hint，
            // 让用户不打开 dialog 也能瞄一眼当前配置。
            run {
                val cfg by viewModel.selectionMenuConfig.collectAsStateWithLifecycle()
                SettingsClickRow(
                    title = "选区菜单按钮",
                    value = "主行 ${cfg.mainCount()}/3",
                    onClick = {
                        com.morealm.app.core.log.AppLog.debug(
                            "SelectionMenu", "open config dialog (current: ${cfg.summary()})",
                        )
                        showSelectionMenuDialog = true
                    },
                )
            }

            Spacer(Modifier.height(16.dp))

            // ── 阅读界面 ──
            SectionHeader("阅读界面")

            SettingsClickRow(
                title = "屏幕关闭时间",
                value = screenTimeoutLabel(screenTimeout),
                onClick = { showTimeoutDialog = true },
            )
            SettingsDivider()
            SettingsToggleRow("显示系统状态", showStatusBar) { viewModel.setShowStatusBar(it) }
            SettingsDivider()
            SettingsToggleRow("显示章节名", showChapterName) { viewModel.setShowChapterName(it) }
            SettingsDivider()
            SettingsToggleRow("显示时间电量", showTimeBattery) { viewModel.setShowTimeBattery(it) }

            Spacer(Modifier.height(16.dp))

            // ── 阅读器背景 ──
            SectionHeader("阅读器背景")

            val readerBgDay by viewModel.readerBgImageDay.collectAsStateWithLifecycle()
            val readerBgNight by viewModel.readerBgImageNight.collectAsStateWithLifecycle()
            val context = LocalContext.current

            val dayBgLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument()
            ) { uri ->
                uri?.let {
                    try { context.contentResolver.takePersistableUriPermission(it, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
                    viewModel.setReaderBgImageDay(it.toString())
                }
            }
            val nightBgLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument()
            ) { uri ->
                uri?.let {
                    try { context.contentResolver.takePersistableUriPermission(it, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
                    viewModel.setReaderBgImageNight(it.toString())
                }
            }

            BgImageRow(
                label = "日间背景",
                imageUri = readerBgDay,
                onPick = { dayBgLauncher.launch(arrayOf("image/*")) },
                onClear = { viewModel.setReaderBgImageDay("") },
            )
            SettingsDivider()
            BgImageRow(
                label = "夜间背景",
                imageUri = readerBgNight,
                onPick = { nightBgLauncher.launch(arrayOf("image/*")) },
                onClear = { viewModel.setReaderBgImageNight("") },
            )

            Text(
                "设置后阅读器背景会随日/夜主题自动切换",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )

            Spacer(Modifier.height(16.dp))

            // ── TXT 章节规则 ──
            SectionHeader("TXT 章节识别")

            var txtRegex by remember { mutableStateOf(customTxtChapterRegex) }
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                Text("自定义章节正则", style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(4.dp))
                Text("留空使用内置规则（第X章/Chapter X 等）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = txtRegex,
                    onValueChange = { txtRegex = it },
                    placeholder = { Text("^\\\\s*第[\\\\d]+章.*", style = MaterialTheme.typography.bodySmall) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary, cursorColor = MaterialTheme.colorScheme.primary),
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(
                        onClick = { viewModel.setCustomTxtChapterRegex(txtRegex) },
                    ) { Text("保存") }
                    if (txtRegex.isNotEmpty()) {
                        TextButton(onClick = { txtRegex = ""; viewModel.setCustomTxtChapterRegex("") }) {
                            Text("清除")
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // ── TTS 朗读规则 ──
            SectionHeader("TTS 朗读")

            val ttsSkipPattern by viewModel.ttsSkipPattern.collectAsStateWithLifecycle()
            var ttsSkip by remember { mutableStateOf(ttsSkipPattern) }
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                Text("跳过内容正则", style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(4.dp))
                Text("匹配的段落在朗读时会被跳过（如作者注、广告）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = ttsSkip,
                    onValueChange = { ttsSkip = it },
                    placeholder = { Text("(作者|本章).*", style = MaterialTheme.typography.bodySmall) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary, cursorColor = MaterialTheme.colorScheme.primary),
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(
                        onClick = { viewModel.setTtsSkipPattern(ttsSkip) },
                    ) { Text("保存") }
                    if (ttsSkip.isNotEmpty()) {
                        TextButton(onClick = { ttsSkip = ""; viewModel.setTtsSkipPattern("") }) {
                            Text("清除")
                        }
                    }
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }

    // ── Dialogs ──
    if (showAnimDialog) {
        PageAnimDialog(
            current = pageAnim,
            onSelect = { viewModel.setPageAnim(it); showAnimDialog = false },
            onDismiss = { showAnimDialog = false },
        )
    }
    if (showTapLeftDialog) {
        TapLeftDialog(
            current = tapLeftAction,
            onSelect = { viewModel.setTapLeftAction(it); showTapLeftDialog = false },
            onDismiss = { showTapLeftDialog = false },
        )
    }
    if (showTimeoutDialog) {
        ScreenTimeoutDialog(
            current = screenTimeout,
            onSelect = { viewModel.setScreenTimeout(it); showTimeoutDialog = false },
            onDismiss = { showTimeoutDialog = false },
        )
    }
    if (showLongPressDialog) {
        VolumeKeyLongPressDialog(
            current = volumeKeyLongPress,
            onSelect = { viewModel.setVolumeKeyLongPress(it); showLongPressDialog = false },
            onDismiss = { showLongPressDialog = false },
        )
    }
    if (showSelectionMenuDialog) {
        val cfg by viewModel.selectionMenuConfig.collectAsStateWithLifecycle()
        SelectionMenuConfigDialog(
            config = cfg,
            onSave = { viewModel.setSelectionMenuConfig(it) },
            onDismiss = { showSelectionMenuDialog = false },
        )
    }
}

// ── Helper composables ──

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

@Composable
private fun SettingsClickRow(title: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(value, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
            Spacer(Modifier.width(4.dp))
            Icon(Icons.Default.ChevronRight, null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f))
        }
    }
}

@Composable
private fun SettingsToggleRow(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val moColors = LocalMoRealmColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface)
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = MaterialTheme.colorScheme.primary,
                checkedThumbColor = MaterialTheme.colorScheme.surface,
            ),
        )
    }
}

// ── Dialogs ──

private fun pageAnimLabel(key: String): String = when (key) {
    "cover" -> "覆盖"
    "simulation" -> "仿真"
    "slide" -> "平移"
    "vertical" -> "上下"
    "fade" -> "淡入"
    "none" -> "无"
    else -> key
}

private fun screenTimeoutLabel(seconds: Int): String = when (seconds) {
    -1 -> "无"
    0 -> "跟随系统"
    60 -> "1分钟"
    120 -> "2分钟"
    300 -> "5分钟"
    600 -> "10分钟"
    else -> "${seconds}秒"
}

private fun volumeKeyLongPressLabel(mode: String): String = when (mode) {
    "off" -> "默认（系统按键重复）"
    "page" -> "连续翻页"
    "chapter" -> "翻章"
    else -> "默认"
}

@Composable
private fun VolumeKeyLongPressDialog(
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
private fun PageAnimDialog(current: String, onSelect: (String) -> Unit, onDismiss: () -> Unit) {
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

@Composable
private fun TapLeftDialog(current: String, onSelect: (String) -> Unit, onDismiss: () -> Unit) {
    val options = listOf(
        "next" to "翻到下一页",
        "prev" to "翻到上一页",
    )
    BottomSheetPicker("轻按页面左侧", options, current, onSelect, onDismiss)
}

@Composable
private fun ScreenTimeoutDialog(current: Int, onSelect: (Int) -> Unit, onDismiss: () -> Unit) {
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
private fun BgImageRow(
    label: String,
    imageUri: String,
    onPick: () -> Unit,
    onClear: () -> Unit,
) {
    val hasImage = imageUri.isNotEmpty()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onPick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Thumbnail preview
        if (hasImage) {
            Image(
                painter = rememberAsyncImagePainter(
                    ImageRequest.Builder(LocalContext.current)
                        .data(imageUri)
                        .size(120, 160)
                        .crossfade(true)
                        .build()
                ),
                contentDescription = label,
                modifier = Modifier
                    .size(48.dp, 64.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Crop,
            )
        } else {
            Box(
                modifier = Modifier
                    .size(48.dp, 64.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.Image, null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f))
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface)
            Text(
                if (hasImage) "已设置 · 点击更换" else "点击选择图片",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
            )
        }
        if (hasImage) {
            TextButton(onClick = onClear) {
                Text("清除", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun BottomSheetPicker(
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
private fun SelectionMenuConfigDialog(
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
private fun SelectionMenuItemRow(
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
private fun PositionChip(
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
