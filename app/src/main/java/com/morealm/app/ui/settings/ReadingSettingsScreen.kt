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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadingSettingsScreen(
    onBack: () -> Unit = {},
    onNavigateRuleColor: () -> Unit = {},
    onNavigateThemeEditor: () -> Unit = {},
    viewModel: ReadingSettingsViewModel = hiltViewModel(),
) {
    val moColors = LocalMoRealmColors.current
    val pageAnim by viewModel.pageAnim.collectAsStateWithLifecycle()
    val readingDirection by viewModel.readingDirection.collectAsStateWithLifecycle()
    val tapLeftAction by viewModel.tapLeftAction.collectAsStateWithLifecycle()
    val readerTapGrid by viewModel.readerTapGrid.collectAsStateWithLifecycle()
    val tapTL by viewModel.tapActionTopLeft.collectAsStateWithLifecycle()
    val tapTR by viewModel.tapActionTopRight.collectAsStateWithLifecycle()
    val tapBL by viewModel.tapActionBottomLeft.collectAsStateWithLifecycle()
    val tapBR by viewModel.tapActionBottomRight.collectAsStateWithLifecycle()
    val volumeKeyPage by viewModel.volumeKeyPage.collectAsStateWithLifecycle()
    val volumeKeyReverse by viewModel.volumeKeyReverse.collectAsStateWithLifecycle()
    val headsetButtonPage by viewModel.headsetButtonPage.collectAsStateWithLifecycle()
    val volumeKeyLongPress by viewModel.volumeKeyLongPress.collectAsStateWithLifecycle()
    val resumeLastRead by viewModel.resumeLastRead.collectAsStateWithLifecycle()
    val longPressUnderline by viewModel.longPressUnderline.collectAsStateWithLifecycle()
    val screenTimeout by viewModel.screenTimeout.collectAsStateWithLifecycle()
    val showStatusBar by viewModel.isStatusBarVisible.collectAsStateWithLifecycle()
    val showChapterName by viewModel.isChapterNameVisible.collectAsStateWithLifecycle()
    val showTimeBattery by viewModel.isTimeBatteryVisible.collectAsStateWithLifecycle()
    val titleAlign by viewModel.titleAlign.collectAsStateWithLifecycle()
    val innerSearchMode by viewModel.innerSearchMode.collectAsStateWithLifecycle()
    val customTxtChapterRegex by viewModel.customTxtChapterRegex.collectAsStateWithLifecycle()
    val ruleColorEnabled by viewModel.ruleColorEnabled.collectAsStateWithLifecycle()

    var settingsQuery by rememberSaveable { mutableStateOf("") }
    val settingsList = rememberLazyListState()
    val navigationScope = rememberCoroutineScope()
    val sections = remember(settingsQuery) { readingSettingsSections.filter { it.matches(settingsQuery) } }
    val selectedSection by remember { derivedStateOf { settingsList.firstVisibleItemIndex } }
    LaunchedEffect(settingsQuery) { settingsList.scrollToItem(0) }
    val ttsSkipPattern by viewModel.ttsSkipPattern.collectAsStateWithLifecycle()
    var txtRegex by rememberSaveable(customTxtChapterRegex) { mutableStateOf(customTxtChapterRegex) }
    var ttsSkip by rememberSaveable(ttsSkipPattern) { mutableStateOf(ttsSkipPattern) }

    // Dialog states
    var showAnimDialog by remember { mutableStateOf(false) }
    var showTapZoneDialog by remember { mutableStateOf(false) }
    var showTimeoutDialog by remember { mutableStateOf(false) }
    var showLongPressDialog by remember { mutableStateOf(false) }
    var showSelectionMenuDialog by remember { mutableStateOf(false) }
    var showReadingDirectionDialog by remember { mutableStateOf(false) }
    // 章节标题对齐方式选择弹窗（左/中/右）。
    var showTitleAlignDialog by remember { mutableStateOf(false) }
    // 文内搜索方向选择弹窗（全文 / 前向 / 后向）。
    var showInnerSearchModeDialog by remember { mutableStateOf(false) }

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
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = settingsQuery,
                onValueChange = { settingsQuery = it },
                placeholder = { Text("搜索阅读设置") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                sections.forEachIndexed { index, section ->
                    FilterChip(
                        selected = selectedSection == index,
                        onClick = { navigationScope.launch { settingsList.animateScrollToItem(index) } },
                        label = { Text(section.title, maxLines = 1) },
                    )
                }
            }
            Box(Modifier.weight(1f).imePadding()) {
                LazyColumn(state = settingsList, modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 72.dp)) {
                    if (sections.isEmpty()) {
                        item { Text("没有找到相关设置", modifier = Modifier.padding(24.dp)) }
                    }
                    sections.forEach { section ->
                        item(key = section.title) {
                            when (section.title) {
                                "屏幕交互" -> {
            ReadingSectionHeader("屏幕交互")
            ReadingSettingsCard {
                SettingsClickRow(
                    icon = Icons.Outlined.Animation,
                    title = "翻页动画",
                    value = pageAnimLabel(pageAnim),
                    onClick = { showAnimDialog = true },
                )
                SettingsClickRow(
                    icon = Icons.Outlined.TouchApp,
                    title = "点击区域",
                    value = if (com.morealm.app.ui.reader.ReaderTapZones.parseGrid(readerTapGrid) != null) {
                        "自定义"
                    } else if (tapLeftAction == "next") {
                        "默认 · 左侧下一页"
                    } else {
                        "默认 · 左侧上一页"
                    },
                    onClick = { showTapZoneDialog = true },
                )
            }

            // ── 实体按键与设备 ──
                                }
                                "实体按键与设备" -> {
            ReadingSectionHeader("实体按键与设备")
            ReadingSettingsCard {
                SettingsToggleRow(
                    icon = Icons.Outlined.VolumeUp,
                    title = "音量键翻页",
                    checked = volumeKeyPage,
                    onCheckedChange = { viewModel.setVolumeKeyPage(it) },
                )
                // 仅在开启了"音量键翻页"时显示反转和长按选项，避免页面塞太多无意义条目
                if (volumeKeyPage) {
                    SettingsToggleRow(
                        icon = Icons.Outlined.SyncAlt,
                        title = "音量键方向反转",
                        subtitle = "下键翻上一页",
                        checked = volumeKeyReverse,
                        onCheckedChange = { viewModel.setVolumeKeyReverse(it) },
                    )
                    SettingsClickRow(
                        icon = Icons.Outlined.Timer,
                        title = "音量键长按",
                        value = volumeKeyLongPressLabel(volumeKeyLongPress),
                        onClick = { showLongPressDialog = true },
                    )
                }
                SettingsToggleRow(
                    icon = Icons.Outlined.Headphones,
                    title = "耳机 / 蓝牙翻页器",
                    checked = headsetButtonPage,
                    onCheckedChange = { viewModel.setHeadsetButtonPage(it) },
                )
            }

            // ── 启动偏好 ──
                                }
                                "启动偏好" -> {
            ReadingSectionHeader("启动偏好")
            ReadingSettingsCard {
                SettingsToggleRow(
                    icon = Icons.Outlined.PlayCircleOutline,
                    title = "启动后继续上次阅读",
                    checked = resumeLastRead,
                    onCheckedChange = { viewModel.setResumeLastRead(it) },
                )
            }

            // ── 选区与高亮 ──
                                }
                                "选区与高亮" -> {
            ReadingSectionHeader("选区与高亮")
            ReadingSettingsCard {
                SettingsToggleRow(
                    icon = Icons.Outlined.FormatColorText,
                    title = "长按文字划线",
                    checked = longPressUnderline,
                    onCheckedChange = { viewModel.setLongPressUnderline(it) },
                )
                // 选区 mini-menu 按钮自定义入口 —— 显示当前主行项数作 hint
                run {
                    val cfg by viewModel.selectionMenuConfig.collectAsStateWithLifecycle()
                    SettingsClickRow(
                        icon = Icons.Outlined.Tune,
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
            }

            // ── 阅读界面 ──
                                }
                                "阅读界面" -> {
            ReadingSectionHeader("阅读界面")
            ReadingSettingsCard {
                // 竖排版 3 态：横排（默认）/ 竖排右起（章标在右、正文在左，日式古典）/
                // 竖排左起（章标在左、正文在右）。3 选项用 SettingsClickRow + Dialog
                // 而不是 Switch，因为状态空间从 2 → 3。
                SettingsClickRow(
                    icon = Icons.Outlined.ViewColumn,
                    title = "竖排版",
                    value = when (readingDirection) {
                        "vertical_rl" -> "竖排（章标在右）"
                        "vertical_lr" -> "竖排（章标在左）"
                        else -> "横排"
                    },
                    onClick = { showReadingDirectionDialog = true },
                )
                SettingsClickRow(
                    icon = Icons.Outlined.ScreenLockPortrait,
                    title = "屏幕关闭时间",
                    value = screenTimeoutLabel(screenTimeout),
                    onClick = { showTimeoutDialog = true },
                )
                SettingsToggleRow(
                    icon = Icons.Outlined.Notifications,
                    title = "显示系统状态",
                    checked = showStatusBar,
                    onCheckedChange = { viewModel.setShowStatusBar(it) },
                )
                SettingsToggleRow(
                    icon = Icons.Outlined.TextFields,
                    title = "显示章节名",
                    checked = showChapterName,
                    onCheckedChange = { viewModel.setShowChapterName(it) },
                )
                SettingsToggleRow(
                    icon = Icons.Outlined.AccessTime,
                    title = "显示时间电量",
                    checked = showTimeBattery,
                    onCheckedChange = { viewModel.setShowTimeBattery(it) },
                )
                // 章节标题对齐 — 排版引擎在画第 X 章标题时按这个值决定 startXOffset。
                // 枚举: 0=左 / 1=中 / 2=右；在阅读器内立即重新排版生效。
                SettingsClickRow(
                    icon = Icons.Outlined.FormatAlignCenter,
                    title = "章节标题对齐",
                    value = titleAlignLabel(titleAlign),
                    onClick = { showTitleAlignDialog = true },
                )
            }

            // ── 文字上色 ──
                                }
                                "文字上色" -> {
            ReadingSectionHeader("文字上色")
            ReadingSettingsCard {
                SettingsClickRow(
                    icon = Icons.Outlined.Palette,
                    title = "文字上色",
                    subtitle = "标点 / 数字 / 字母 / 引号内…按规则着色",
                    value = if (ruleColorEnabled) "已开" else "已关",
                    onClick = onNavigateRuleColor,
                )
            }

            // ── 文内搜索 ──
                                }
                                "文内搜索" -> {
            ReadingSectionHeader("文内搜索")
            ReadingSettingsCard {
                SettingsClickRow(
                    icon = Icons.Outlined.Search,
                    title = "搜索方向",
                    subtitle = "前向 = 当前页之前；后向 = 当前页之后",
                    value = innerSearchModeLabel(innerSearchMode),
                    onClick = { showInnerSearchModeDialog = true },
                )
            }

            // ── 阅读配色组 ──
                                }
                                "阅读配色" -> {
            ReadingSectionHeader("阅读配色")
            ReadingSettingsCard {
                SettingsClickRow(
                    icon = Icons.Outlined.Palette,
                    title = "文字颜色和背景",
                    value = "",
                    subtitle = "文字色、背景色、背景图整组管理",
                    onClick = onNavigateThemeEditor,
                )
            }

            Text(
                "设置后阅读器背景会随日/夜主题自动切换",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )

            // ── TXT 章节规则 ──
                                }
                                "TXT 章节识别" -> {
            ReadingSectionHeader("TXT 章节识别")

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
                                }
                                "TTS 朗读" -> {
            ReadingSectionHeader("TTS 朗读")

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

            // ── 锁屏保活 CPU ──
            // 默认关；遇到锁屏后断声的国产 ROM 用户可手动开启换稳定性。详见
            // [AppPreferences.ttsKeepCpuAwake] 的偏好头注释。
            Spacer(Modifier.height(8.dp))
            val ttsKeepCpuAwake by viewModel.ttsKeepCpuAwake.collectAsStateWithLifecycle()
            ReadingSettingsCard {
                SettingsToggleRow(
                    icon = Icons.Outlined.Lock,
                    title = "锁屏保活 CPU",
                    subtitle = "开启后耗电略高；仅当锁屏偶发断声时建议开启",
                    checked = ttsKeepCpuAwake,
                    onCheckedChange = { viewModel.setTtsKeepCpuAwake(it) },
                )
            }
                                }
                            }
                        }
                    }
                }
                if (selectedSection > 1) {
                    SmallFloatingActionButton(
                        onClick = { navigationScope.launch { settingsList.animateScrollToItem(0) } },
                        modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                    ) { Icon(Icons.Default.KeyboardArrowUp, "回到顶部") }
                }
            }
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
    if (showTapZoneDialog) {
        TapZoneGridDialog(
            currentGrid = com.morealm.app.ui.reader.ReaderTapZones.effectiveGrid(
                readerTapGrid, tapTL, tapTR, tapBL, tapBR,
            ),
            onSave = { grid ->
                viewModel.setReaderTapGrid(com.morealm.app.ui.reader.ReaderTapZones.encodeGrid(grid))
            },
            onReset = { viewModel.setReaderTapGrid("") },
            onDismiss = { showTapZoneDialog = false },
        )
    }
    if (showTimeoutDialog) {
        ScreenTimeoutDialog(
            current = screenTimeout,
            onSelect = { viewModel.setScreenTimeout(it); showTimeoutDialog = false },
            onDismiss = { showTimeoutDialog = false },
        )
    }
    if (showReadingDirectionDialog) {
        ReadingDirectionDialog(
            current = readingDirection,
            onSelect = { viewModel.setReadingDirection(it); showReadingDirectionDialog = false },
            onDismiss = { showReadingDirectionDialog = false },
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
    if (showTitleAlignDialog) {
        TitleAlignDialog(
            current = titleAlign,
            onSelect = { viewModel.setTitleAlign(it); showTitleAlignDialog = false },
            onDismiss = { showTitleAlignDialog = false },
        )
    }
    if (showInnerSearchModeDialog) {
        InnerSearchModeDialog(
            current = innerSearchMode,
            onSelect = { viewModel.setInnerSearchMode(it); showInnerSearchModeDialog = false },
            onDismiss = { showInnerSearchModeDialog = false },
        )
    }
}

// ── Helper composables ──
//
// 视觉重构（Image 15 设计参考）：
//   - SectionHeader：subtle gray label, 24dp left padding 与 Card 的 16dp 形成
//     "section header 退一格" 的层次
//   - SettingsCard：把同 section 的多行包成一张 surfaceContainer 卡片，去掉行间 Divider
//   - RowIcon：左侧 36dp 圆角块 + 20dp Outlined 矢量图标，统一节奏
//   - SettingsClickRow / SettingsToggleRow：必传 icon，可选 subtitle
//
// 旧 SettingsDivider 已删除——卡片+行内自然 padding 取代了行间分割线。
