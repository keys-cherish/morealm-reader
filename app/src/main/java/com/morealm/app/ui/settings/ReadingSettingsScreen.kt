package com.morealm.app.ui.settings

import androidx.compose.foundation.clickable
import com.morealm.app.presentation.settings.ReadingSettingsViewModel
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
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
            SettingsToggleRow("启动后继续上次阅读", resumeLastRead) { viewModel.setResumeLastRead(it) }
            SettingsDivider()
            SettingsToggleRow("长按文字划线", longPressUnderline) { viewModel.setLongPressUnderline(it) }

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
