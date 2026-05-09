package com.morealm.app.ui.reader

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.documentfile.provider.DocumentFile
import com.morealm.app.domain.entity.ThemeEntity
import com.morealm.app.ui.theme.LocalMoRealmColors
import com.morealm.app.ui.theme.toComposeColor
import com.morealm.app.presentation.reader.PageTurnMode
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ── Top Bar ─────────────────────────────────────────────

@Composable
fun ReaderTopBar(
    bookTitle: String,
    onBack: () -> Unit,
    onExport: () -> Unit = {},
    onBookmark: () -> Unit = {},
    /** 顶栏「书签列表」按钮 — 打开书签面板（与「添加书签」相邻，#2 反馈）。 */
    onBookmarkList: () -> Unit = {},
    /**
     * #5：「当前章生效规则」按钮 — 反馈认为属于低频操作，已收纳到右侧 ⋮ 溢出菜单，
     * 不再占顶栏主行图标位。保留 callback 以兼容现有调用方。
     */
    onEffectiveReplaces: () -> Unit = {},
    /** 顶栏「阅读设置」— 打开底部设置面板，方便用户从右上角快速进入。 */
    onSettings: () -> Unit = {},
) {
    val moColors = LocalMoRealmColors.current
    var showMenu by remember { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.95f),
    ) {
        Row(
            modifier = Modifier
                // ── 顶部 inset 合并：statusBars + displayCutout ──
                //
                // 之前只 only(displayCutout) → showStatusBar=true 时状态栏会盖住第一行
                // 文字（"返回总纲"被电池图标压一截）。现在并进 statusBars 让 ReaderTopBar
                // 自动避让状态栏；showStatusBar=false（沉浸模式）时 statusBars inset
                // 系统会自动归零，TopBar 自然贴到屏幕极顶——与「让 reader 路由抵消
                // Scaffold innerPadding」配合，组成完整的「reader 全屏沉浸」体验。
                .windowInsetsPadding(
                    WindowInsets.statusBars
                        .union(WindowInsets.displayCutout)
                        .only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                )
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.semantics {
                    contentDescription = "返回书架"
                    role = Role.Button
                },
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回",
                    tint = MaterialTheme.colorScheme.onSurface)
            }
            Text(
                bookTitle,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            // #2：添加书签 + 书签列表 相邻放置，避免「添加在顶栏，查看在底部章节面板」
            // 两端跑的体感问题。
            IconButton(
                onClick = onBookmark,
                modifier = Modifier.semantics {
                    contentDescription = "添加书签"
                    role = Role.Button
                },
            ) {
                Icon(Icons.Default.BookmarkAdd, "添加书签",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(20.dp))
            }
            IconButton(
                onClick = onBookmarkList,
                modifier = Modifier.semantics {
                    contentDescription = "书签列表"
                    role = Role.Button
                },
            ) {
                Icon(Icons.Default.Bookmarks, "书签列表",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(20.dp))
            }
            IconButton(
                onClick = onSettings,
                modifier = Modifier.semantics {
                    contentDescription = "阅读设置"
                    role = Role.Button
                },
            ) {
                Icon(Icons.Default.Settings, "设置",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(20.dp))
            }
            Box {
                IconButton(
                    onClick = { showMenu = true },
                    modifier = Modifier.semantics {
                        contentDescription = "更多操作"
                        role = Role.Button
                    },
                ) {
                    Icon(Icons.Default.MoreVert, "更多",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(20.dp))
                }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    // #5：低频「当前生效规则」收纳到溢出菜单
                    DropdownMenuItem(
                        text = { Text("当前生效规则") },
                        leadingIcon = { Icon(Icons.Default.FilterAlt, null) },
                        onClick = { showMenu = false; onEffectiveReplaces() },
                    )
                    DropdownMenuItem(
                        text = { Text("导出为 TXT") },
                        leadingIcon = { Icon(Icons.Default.FileDownload, null) },
                        onClick = { showMenu = false; onExport() },
                    )
                }
            }
        }
    }
}

// ── Bottom Control Bar (HTML prototype style: floating pill) ──

/**
 * Seek preview 保留时长——见 LaunchedEffect(pendingChapter) 内部注释。
 * 900ms 是经验值，覆盖本地 + 一般网络章节的 loadChapter + LazyScroll restore
 * + scrollProgress emit 全程。极慢章节超过这个值时 seekValue 会先被清，slider
 * 短暂回到 baseProgress（已部分恢复）—— 比错位回弹温和。
 */
private const val SEEK_PREVIEW_RETENTION_MS = 900L

@Composable
fun ReaderControlBar(
    currentChapter: Int, totalChapters: Int, chapterTitle: String,
    readProgress: String = "0.0%",
    scrollProgress: Int = 0,
    onBack: () -> Unit, onPrevChapter: () -> Unit, onNextChapter: () -> Unit,
    onTts: () -> Unit, onSettings: () -> Unit, onChapterSelect: () -> Unit,
    onSearch: () -> Unit = {},
    onAutoPage: () -> Unit = {},
    /**
     * #3 进度条拖动跳转：松手时调一次。
     *
     * 参数：
     *  - `chapterIdx`：目标章节下标 [0, totalChapters)
     *  - `withinChapterPercent`：章内进度 0..100（用作 ReaderViewModel.loadChapter
     *    的 restoreProgress 参数，等价于 ReaderProgressController 的 scrollProgress）。
     *
     * 默认 no-op 让旧调用方仍能编译，但会回退到只读进度条体验。
     *
     * 历史：原签名是 `(Int) -> Unit` 只跳章。后来按用户反馈改成全书 0-100%
     * 拖动（静读天下 / Moon+ Reader 风格），单条 Slider 同时承载章 + 章内位置。
     */
    onSeekFullBook: (chapterIdx: Int, withinChapterPercent: Int) -> Unit = { _, _ -> },
    /**
     * 拖动时拿目标章节标题用于预览气泡。lambda 接收章节下标返回标题文本，
     * 让 ControlBar 不必直接持有 List<BookChapter>。
     */
    getChapterTitle: (Int) -> String = { "" },
) {
    val moColors = LocalMoRealmColors.current
    // Combine chapter progress with scroll progress for a smooth overall %
    val chapterFraction = if (totalChapters > 0) currentChapter.toFloat() / totalChapters else 0f
    val scrollFraction = if (totalChapters > 0) scrollProgress / 100f / totalChapters else 0f
    val baseProgress = (chapterFraction + scrollFraction).coerceIn(0f, 1f)
    val barShape = MaterialTheme.shapes.extraLarge

    // ── #3 拖动状态 ──
    // 拖动期间 [seekValue] != null：滑块视觉、预览气泡都用它；松手后**不立刻清**，
    // 等下面的 LaunchedEffect 看到 currentChapter 已经切到拖动目标 [pendingChapter]
    // 之后再清——避免「立刻清 → sliderValue 退回 baseProgress 旧值 → 几百 ms 后
    // currentChapter 才到位」造成 thumb 短暂弹回老位置（用户看到「松手回弹然后
    // 恢复」）。loadChapter 是异步的，preview 必须撑到真值跟上。
    // sliderValue ∈ 0..1 表示全书进度。映射规则：
    //   rawProgress = slider * totalChapters
    //   targetChapter = floor(rawProgress)        // [0, totalChapters)
    //   withinChapterPct = (rawProgress - targetChapter) * 100  // [0, 100)
    var seekValue: Float? by remember { mutableStateOf(null) }
    var pendingChapter: Int? by remember { mutableStateOf(null) }
    val sliderValue = seekValue ?: baseProgress
    val rawProgress = sliderValue * totalChapters
    val previewIdx = if (totalChapters > 0)
        rawProgress.toInt().coerceIn(0, totalChapters - 1)
    else 0
    val previewWithinPct = if (totalChapters > 0)
        ((rawProgress - previewIdx) * 100f).toInt().coerceIn(0, 99)
    else 0
    val previewBookPct = (sliderValue * 100f).coerceIn(0f, 100f)

    // 当用户松手发起 seek 后，等 [SEEK_PREVIEW_RETENTION_MS] 让 viewModel 跑完
     // loadChapter + LazyScroll restoreProgress JUMP + scrollProgress emit 一整套，
     // 然后再清 seekValue / pendingChapter。期间 sliderValue 保持 = seekValue
     // （用户拖到的位置），thumb 不会先跳回 baseProgress 再跳到目标——前者就是
     // bug「松手回弹然后恢复」/「拖动没反应」的根因（SCROLL 模式下
     // visiblePage.chapterIndex 在 LazyScroll JUMP 那一刻就流到目标章，
     // 但 scrollProgress 还是 0%，baseProgress = chapterFraction + 0 → thumb
     // 跳到目标章首；几十 ms 后 scrollProgress 才到 withinPct）。900ms 覆盖
     // 大部分本地 + 网络章节加载；连点 seek 时新点击会重启延迟，旧 coroutine
     // 被 cancel，seekValue 始终保持为最近一次拖动到的位置。
    LaunchedEffect(pendingChapter) {
        if (pendingChapter != null) {
            kotlinx.coroutines.delay(SEEK_PREVIEW_RETENTION_MS)
            seekValue = null
            pendingChapter = null
        }
    }

    // Floating pill bar like HTML prototype's .r-bar
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(barShape)
            .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.88f))
            .clickable(
                indication = null,
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                onClick = {},
            ),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            // Icon row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .size(32.dp)
                        .semantics {
                            contentDescription = "返回书架"
                            role = Role.Button
                        },
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(18.dp))
                }
                IconButton(
                    onClick = onChapterSelect,
                    modifier = Modifier
                        .size(32.dp)
                        .semantics {
                            contentDescription = "目录"
                            role = Role.Button
                        },
                ) {
                    @Suppress("DEPRECATION")
                    Icon(Icons.Default.FormatListBulleted, "目录",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(18.dp))
                }
                IconButton(
                    onClick = onSearch,
                    modifier = Modifier
                        .size(32.dp)
                        .semantics {
                            contentDescription = "全文搜索"
                            role = Role.Button
                        },
                ) {
                    Icon(Icons.Default.Search, "搜索",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(18.dp))
                }
                // Center: progress / 拖动预览
                Column(
                    modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (seekValue != null && totalChapters > 0) {
                        // #3 拖动时实时显示「全书 X.X% · 第N章 · 章内Y%」
                        val previewTitle = getChapterTitle(previewIdx).ifBlank { "第${previewIdx + 1}章" }
                        Text(
                            "→ ${"%.1f".format(previewBookPct)}% · ${previewTitle.take(14)} · 章内${previewWithinPct}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    } else {
                        Text(
                            "${chapterTitle.ifBlank { "第${currentChapter + 1}章" }} · $readProgress",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                IconButton(
                    onClick = onTts,
                    modifier = Modifier
                        .size(32.dp)
                        .semantics {
                            contentDescription = "朗读"
                            role = Role.Button
                        },
                ) {
                    Icon(Icons.Default.RecordVoiceOver, "朗读",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp))
                }
                IconButton(
                    onClick = onAutoPage,
                    modifier = Modifier
                        .size(32.dp)
                        .semantics {
                            contentDescription = "自动翻页"
                            role = Role.Button
                        },
                ) {
                    Icon(Icons.Default.Timer, "自动翻页",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(18.dp))
                }
                IconButton(
                    onClick = onSettings,
                    modifier = Modifier
                        .size(32.dp)
                        .semantics {
                            contentDescription = "阅读设置"
                            role = Role.Button
                        },
                ) {
                    Icon(Icons.Default.TextFields, "设置",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(18.dp))
                }
            }
            // ── #3 章节进度条（可拖动） ──
            // 单章节情况下不渲染 Slider（valueRange 0..0 不合法），保留旧的小提示就够用。
            if (totalChapters > 1) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("上一章",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                        modifier = Modifier.clickable(onClick = onPrevChapter)
                            .padding(vertical = 4.dp, horizontal = 2.dp),
                    )
                    Slider(
                        value = sliderValue,
                        onValueChange = { seekValue = it },
                        onValueChangeFinished = {
                            seekValue?.let { v ->
                                val raw = (v * totalChapters).coerceIn(0f, totalChapters.toFloat())
                                val idx = raw.toInt().coerceIn(0, totalChapters - 1)
                                val withinPct = ((raw - idx) * 100f).toInt().coerceIn(0, 99)
                                // 注意：即使章号没变也要触发 — 用户可能在本章内拖位置。
                                // 旧实现 `if (idx != currentChapter)` 会吃掉章内 seek。
                                pendingChapter = idx
                                onSeekFullBook(idx, withinPct)
                            }
                            // seekValue 不在这里清——上面的 LaunchedEffect 等
                            // currentChapter == pendingChapter 后再清，避免 thumb
                            // 弹回旧位置再跳到新位置。
                        },
                        valueRange = 0f..1f,
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                        colors = SliderDefaults.colors(
                            thumbColor = MaterialTheme.colorScheme.primary,
                            activeTrackColor = MaterialTheme.colorScheme.primary,
                            inactiveTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                        ),
                    )
                    Text("下一章",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                        modifier = Modifier.clickable(onClick = onNextChapter)
                            .padding(vertical = 4.dp, horizontal = 2.dp),
                    )
                }
            } else {
                // 单章场景仅画细线进度条做装饰
                LinearProgressIndicator(
                    progress = { baseProgress },
                    modifier = Modifier.fillMaxWidth().height(3.dp)
                        .padding(horizontal = 8.dp)
                        .clip(MaterialTheme.shapes.extraSmall),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                )
            }
        }
    }
}

// ── Settings Panel (font, page turn mode) ───────────────

@Composable
fun ReaderSettingsPanel(
    currentMode: PageTurnMode,
    onModeChange: (PageTurnMode) -> Unit,
    pageAnim: String = "slide",
    onPageAnimChange: (String) -> Unit = {},
    currentFont: String = "noto_serif_sc",
    onFontChange: (String) -> Unit = {},
    currentFontSize: Float = 17f,
    onFontSizeChange: (Float) -> Unit = {},
    currentLineHeight: Float = 2.0f,
    onLineHeightChange: (Float) -> Unit = {},
    customFontName: String = "",
    onImportFont: (android.net.Uri, String) -> Unit = { _, _ -> },
    onClearCustomFont: () -> Unit = {},
    /** 打开字体管理页（FontManagerScreen）。由 ReaderScreen 注入 navController.navigate。 */
    onOpenFontManager: () -> Unit = {},
    allThemes: List<ThemeEntity> = emptyList(),
    activeThemeId: String = "",
    onThemeChange: (String) -> Unit = {},
    brightness: Float = -1f,
    onBrightnessChange: (Float) -> Unit = {},
    paragraphSpacing: Int = 8,
    onParagraphSpacingChange: (Int) -> Unit = {},
    marginHorizontal: Int = 24,
    /**
     * 松手时回写 prefs 触发 reflow。设计上**不**走"拖动期间实时预览"——
     * CanvasRenderer 的 reflow 是 onCompleted 才 atomic swap 的设计，拖动期间高频
     * 重排会被取消重启永远完不成；改为松手生效后体验明确：thumb 跟手移动 + 数值
     * 实时刷新，松手内容才重排一次。
     */
    onMarginHorizontalCommit: (Int) -> Unit = {},
    marginTop: Int = 24,
    onMarginTopCommit: (Int) -> Unit = {},
    marginBottom: Int = 24,
    onMarginBottomCommit: (Int) -> Unit = {},
    customCss: String = "",
    onCustomCssChange: (String) -> Unit = {},
    customBgImage: String = "",
    onCustomBgImageChange: (String) -> Unit = {},
    readerStyles: List<com.morealm.app.domain.entity.ReaderStyle> = emptyList(),
    activeStyleId: String = "",
    onStyleChange: (String) -> Unit = {},
    screenOrientation: Int = -1,
    onScreenOrientationChange: (Int) -> Unit = {},
    textSelectable: Boolean = true,
    onTextSelectableChange: (Boolean) -> Unit = {},
    chineseConvertMode: Int = 0,
    onChineseConvertModeChange: (Int) -> Unit = {},
    footerRight: String = "page_progress",
    onFooterRightChange: (String) -> Unit = {},
    /**
     * #1：「恢复出厂」一键回退所有阅读相关设置。
     * 触发 [com.morealm.app.presentation.reader.ReaderSettingsController.resetAllToFactoryDefaults]，
     * 范围包含排版、配色、自定义 CSS / 背景图、繁简、动画、屏幕方向、tap zone 等。
     */
    onResetStyle: () -> Unit = {},
    onDismiss: () -> Unit,
) {
    val moColors = LocalMoRealmColors.current
    var fontSize by remember { mutableFloatStateOf(currentFontSize) }
    var selectedFont by remember { mutableStateOf(currentFont) }

    Surface(
        modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp),
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.97f),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        tonalElevation = 12.dp,
    ) {
        Column(modifier = Modifier
            .navigationBarsPadding()
            .padding(20.dp)
            .verticalScroll(rememberScrollState())
        ) {
            // Drag handle
            Box(Modifier.width(40.dp).height(4.dp).clip(MaterialTheme.shapes.extraSmall)
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f))
                .align(Alignment.CenterHorizontally))

            Spacer(Modifier.height(12.dp))

            // ── #1 恢复出厂 — 一行右对齐紧凑按钮 ──
            //
            // 放在面板顶部、drag handle 之下：用户拖参数翻车想"重置一切"时第一眼看到。
            // 用 TextButton + 小字号 + 右对齐，避免抢主操作区的视觉权重。
            // 范围：所有 ReaderStyle 字段 + 所有阅读相关 prefs（详见
            // [com.morealm.app.presentation.reader.ReaderSettingsController.resetAllToFactoryDefaults]）。
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = onResetStyle,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Icon(
                        Icons.Default.RestartAlt, null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "恢复出厂",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            Spacer(Modifier.height(4.dp))

            // ── Reader Style Presets ──
            if (readerStyles.isNotEmpty()) {
                // #4：原「阅读样式」与下方「主题」名字撞，改为「排版预设」表明此处只切排版
                Text("排版预设", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                Spacer(Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    readerStyles.forEach { style ->
                        val isActive = style.id == activeStyleId
                        // v29 起 ReaderStyle 不再带颜色，瓦片预览改为按 preset 的
                        // textSize 视觉缩放 "Aa" 字样：
                        //   - 默认 17 → 12sp、紧凑 15 → 11sp、大字 20 → 14sp
                        // 直观传达"这是排版差异"而非"颜色差异"。瓦片底色统一用主题
                        // surfaceContainerHigh，跟当前主题协调。
                        val previewFontSize = (style.textSize / 1.4f).coerceIn(10f, 16f).sp
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .semantics {
                                    contentDescription = "排版预设：${style.name}"
                                    role = Role.Button
                                }
                                .clickable { onStyleChange(style.id) },
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                    .then(
                                        if (isActive) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
                                        else Modifier.border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f), CircleShape)
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("Aa",
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontSize = previewFontSize,
                                    fontWeight = FontWeight.Bold)
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(style.name,
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                color = if (isActive) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                maxLines = 1)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            // ── Page animation (翻页动画) — 提前到样式预设之后，用户最常调 ──
            Text("翻页动画", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
            Spacer(Modifier.height(6.dp))
            val animOptions = listOf(
                "slide" to "平移",
                "cover" to "覆盖",
                "simulation" to "仿真",
                "vertical" to "上下滚动",
                "none" to "无动画",
            )
            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                animOptions.forEach { (key, label) ->
                    FilterChip(
                        selected = pageAnim == key,
                        onClick = { onPageAnimChange(key) },
                        label = { Text(label, style = MaterialTheme.typography.bodySmall) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                            selectedLabelColor = MaterialTheme.colorScheme.primary),
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // ── 页码显示 ──
            Text("页码显示", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
            Spacer(Modifier.height(6.dp))
            val pageDisplayOptions = listOf(
                "page_progress" to "本章页码+进度",
                "page" to "本章页码",
                "chapter_progress" to "全书章节进度",
                "progress" to "仅百分比",
                "none" to "关闭",
            )
            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                pageDisplayOptions.forEach { (key, label) ->
                    FilterChip(
                        selected = footerRight == key,
                        onClick = { onFooterRightChange(key) },
                        label = { Text(label, style = MaterialTheme.typography.bodySmall) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                            selectedLabelColor = MaterialTheme.colorScheme.primary),
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // ── Brightness ──
            Text("亮度", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
            var brightnessVal by remember { mutableFloatStateOf(brightness) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.BrightnessLow, null,
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    modifier = Modifier.size(18.dp))
                Slider(
                    value = if (brightnessVal < 0f) 0.5f else brightnessVal,
                    onValueChange = { brightnessVal = it; onBrightnessChange(it) },
                    valueRange = 0.01f..1f,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary, activeTrackColor = MaterialTheme.colorScheme.primary),
                )
                Icon(Icons.Default.BrightnessHigh, null,
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    modifier = Modifier.size(18.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilterChip(
                    selected = brightnessVal < 0f,
                    onClick = {
                        brightnessVal = -1f
                        onBrightnessChange(-1f)
                    },
                    label = { Text("跟随系统") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                        selectedLabelColor = MaterialTheme.colorScheme.primary),
                )
            }

            Spacer(Modifier.height(12.dp))

            // ── Font size ──
            Text("字号", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("A", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface)
                Slider(
                    value = fontSize, onValueChange = { fontSize = it; onFontSizeChange(it) },
                    valueRange = 12f..100f, steps = 0,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary, activeTrackColor = MaterialTheme.colorScheme.primary),
                )
                Text("A", style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface)
            }
            Text("${fontSize.toInt()}px" + if (fontSize > 50f) " ⚠ 超大字号可能影响排版" else "",
                style = MaterialTheme.typography.labelSmall,
                color = if (fontSize > 50f) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))

            Spacer(Modifier.height(12.dp))

            // ── Font family ──
            Text("字体", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
            Spacer(Modifier.height(6.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                data class FontOption(val key: String, val label: String)
                val builtinFonts = listOf(
                    FontOption("noto_serif_sc", "宋体"),
                    FontOption("noto_sans_sc", "黑体"),
                    FontOption("kaiti", "楷体"),
                    FontOption("fangsong", "仿宋"),
                )
                builtinFonts.forEach { font ->
                    FilterChip(
                        selected = selectedFont == font.key && customFontName.isEmpty(),
                        onClick = {
                            // 选内置字体时清掉用户自定义路径，避免两者并存优先级不明
                            if (customFontName.isNotEmpty()) onClearCustomFont()
                            selectedFont = font.key
                            onFontChange(font.key)
                        },
                        label = { Text(font.label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                            selectedLabelColor = MaterialTheme.colorScheme.primary),
                    )
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                listOf("crimson_pro" to "Crimson", "inter" to "Inter", "system" to "系统").forEach { (key, label) ->
                    FilterChip(
                        selected = selectedFont == key && customFontName.isEmpty(),
                        onClick = {
                            if (customFontName.isNotEmpty()) onClearCustomFont()
                            selectedFont = key
                            onFontChange(key)
                        },
                        label = { Text(label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                            selectedLabelColor = MaterialTheme.colorScheme.primary),
                    )
                }
            }
            // 自定义字体 chip：仅在用户已挑选自定义字体时出现，显示当前字体名 + ×清除。
            // 「字体管理…」按钮始终在第二行右侧，跳到 FontManagerScreen 处理批量导入与切换。
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (customFontName.isNotEmpty()) {
                    FilterChip(
                        selected = true,
                        onClick = { /* 已选中，点击不切换 */ },
                        label = { Text(customFontName) },
                        trailingIcon = {
                            Icon(Icons.Default.Close, "清除",
                                modifier = Modifier.size(14.dp)
                                    .clickable { onClearCustomFont(); selectedFont = "noto_serif_sc" })
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                            selectedLabelColor = MaterialTheme.colorScheme.primary),
                    )
                }
                FilterChip(
                    selected = false,
                    onClick = onOpenFontManager,
                    label = { Text("字体管理…") },
                    leadingIcon = { Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp)) },
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)),
                )
            }

            Spacer(Modifier.height(16.dp))

            // ── Line height ──
            // chip selected 直接对入参 currentLineHeight 比对，去掉 local state +
            // debounce —— 之前的 lineHeightJob 防抖结合 local state（无 key）会让
            // chip 选中态与外部 StateFlow 不同步，且段距走相同模式被 #6 修复时一并
            // 简化为「立即派发，单一来源真值流」。
            Text("行距", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1.5f to "紧凑", 1.8f to "适中", 2.0f to "宽松", 2.4f to "超宽").forEach { (v, l) ->
                    FilterChip(
                        selected = currentLineHeight == v,
                        onClick = { onLineHeightChange(v) },
                        label = { Text(l) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                            selectedLabelColor = MaterialTheme.colorScheme.primary),
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // ── Paragraph spacing ──
            // chip 值是 px (Int)，直接对应 ReaderStyle.paragraphSpacing。selected 直接
            // 比对入参，无需 local state——避免「local 与入参不同步导致选中错位」。
            // 旧实现用 0.5/1.0/1.4/2.0f 倍率值，setter 走 toInt 后变成 0/1/1/2，
            // 「适中」和「宽松」在 Room 里是同一个值，是 bug 6 段距部分的根因。
            Text("段间距", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(4 to "紧凑", 8 to "适中", 16 to "宽松", 24 to "超宽").forEach { (v, l) ->
                    FilterChip(
                        selected = paragraphSpacing == v,
                        onClick = { onParagraphSpacingChange(v) },
                        label = { Text(l) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                            selectedLabelColor = MaterialTheme.colorScheme.primary),
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // ── Margins ──
            // 加 key=外部值：commit 后外部 StateFlow 回流时 thumb 会同步；
            // 同时切样式预设带来的边距变化也能立刻反映到滑块视觉位置。
            var mH by remember(marginHorizontal) { mutableIntStateOf(marginHorizontal) }
            var mT by remember(marginTop) { mutableIntStateOf(marginTop) }
            var mB by remember(marginBottom) { mutableIntStateOf(marginBottom) }
            Text("页边距", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("左右", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    modifier = Modifier.width(32.dp))
                Slider(
                    value = mH.toFloat(),
                    onValueChange = { mH = it.toInt() },
                    onValueChangeFinished = { onMarginHorizontalCommit(mH) },
                    valueRange = 8f..64f, steps = 0,
                    modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.primary, activeTrackColor = MaterialTheme.colorScheme.primary),
                )
                Text("${mH}px", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                    modifier = Modifier.width(36.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("上", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    modifier = Modifier.width(32.dp))
                Slider(
                    value = mT.toFloat(),
                    onValueChange = { mT = it.toInt() },
                    onValueChangeFinished = { onMarginTopCommit(mT) },
                    valueRange = 8f..64f, steps = 0,
                    modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.primary, activeTrackColor = MaterialTheme.colorScheme.primary),
                )
                Text("${mT}px", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                    modifier = Modifier.width(36.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("下", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    modifier = Modifier.width(32.dp))
                Slider(
                    value = mB.toFloat(),
                    onValueChange = { mB = it.toInt() },
                    onValueChangeFinished = { onMarginBottomCommit(mB) },
                    valueRange = 8f..64f, steps = 0,
                    modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.primary, activeTrackColor = MaterialTheme.colorScheme.primary),
                )
                Text("${mB}px", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                    modifier = Modifier.width(36.dp))
            }

            Spacer(Modifier.height(16.dp))

            // ── Theme ──
            if (allThemes.isNotEmpty()) {
                // #4：与上方「排版预设」做对照，改为「配色主题」明确语义。
                Text("配色主题", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                Spacer(Modifier.height(6.dp))
                // #4：选中标签原本只在卡片下方居中显示「当前主题名」一行，
                // 用户容易误以为「整组卡片都叫这个名字」。改为每张卡下面都显示自己的
                // 名字（与上面「排版预设」对齐）；选中卡名字用主色 + 加粗即可，
                // 不再额外画底部一行汇总。
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    allThemes.forEach { theme ->
                        val isActive = theme.id == activeThemeId
                        val bgColor = theme.readerBackground.toComposeColor()
                        val fgColor = theme.readerTextColor.toComposeColor()
                        val acColor = theme.accentColor.toComposeColor()
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .semantics {
                                    contentDescription = "配色主题：${theme.name}"
                                    role = Role.Button
                                }
                                .clickable { onThemeChange(theme.id) },
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(MaterialTheme.shapes.small)
                                    .background(bgColor),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    "文",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = fgColor,
                                    fontWeight = FontWeight.Bold,
                                )
                                if (isActive) {
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.BottomCenter)
                                            .fillMaxWidth()
                                            .height(3.dp)
                                            .background(
                                                acColor,
                                                RoundedCornerShape(bottomStart = 8.dp, bottomEnd = 8.dp),
                                            ),
                                    )
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                theme.name,
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                color = if (isActive) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // ── Custom background image ──
            // 之前的 `val context = LocalContext.current` 在字体区被删后，这里的
            // `val ctx = context` 引用悬空 —— 在 Composable 顶部就近补一份，
            // SAF 持久化权限必须用真 Context（lambda 内部不能直接 LocalContext.current）。
            val bgCtx = LocalContext.current
            val bgImageLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument()
            ) { uri ->
                uri?.let {
                    // Take persistable permission
                    try {
                        bgCtx.contentResolver.takePersistableUriPermission(
                            it, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    } catch (_: Exception) {}
                    onCustomBgImageChange(it.toString())
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("背景图片", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                Spacer(Modifier.weight(1f))
                if (customBgImage.isNotEmpty()) {
                    FilterChip(
                        selected = false,
                        onClick = { onCustomBgImageChange("") },
                        label = { Text("清除") },
                    )
                    Spacer(Modifier.width(8.dp))
                }
                FilterChip(
                    selected = customBgImage.isNotEmpty(),
                    onClick = { bgImageLauncher.launch(arrayOf("image/*")) },
                    label = { Text(if (customBgImage.isNotEmpty()) "更换" else "选择图片") },
                    leadingIcon = { Icon(Icons.Default.Image, null, modifier = Modifier.size(16.dp)) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                        selectedLabelColor = MaterialTheme.colorScheme.primary),
                )
            }

            Spacer(Modifier.height(16.dp))

            // ── Custom CSS ──
            CssEditorSection(
                css = customCss,
                onCssChange = onCustomCssChange,
            )

            Spacer(Modifier.height(16.dp))

            // ── Screen orientation ──
            Text("屏幕方向", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(-1 to "自动", 0 to "竖屏", 1 to "横屏").forEach { (v, l) ->
                    FilterChip(
                        selected = screenOrientation == v,
                        onClick = { onScreenOrientationChange(v) },
                        label = { Text(l) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                            selectedLabelColor = MaterialTheme.colorScheme.primary),
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // ── Text selectable ──
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("文字可选择", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                Spacer(Modifier.weight(1f))
                Switch(
                    checked = textSelectable,
                    onCheckedChange = onTextSelectableChange,
                    colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.primary),
                )
            }

            Spacer(Modifier.height(8.dp))

            // ── Chinese conversion ──
            Text("繁简转换", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0 to "关闭", 1 to "简→繁", 2 to "繁→简").forEach { (v, l) ->
                    FilterChip(
                        selected = chineseConvertMode == v,
                        onClick = { onChineseConvertModeChange(v) },
                        label = { Text(l) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                            selectedLabelColor = MaterialTheme.colorScheme.primary),
                    )
                }
            }

        }
    }
}

// ── Image Viewer Dialog ──────────────────────────────────

@Composable
fun ImageViewerDialog(
    imageSrc: String,
    onDismiss: () -> Unit,
) {
    // Native PhotoView + Coil — no WebView needed.
    val filePath = remember(imageSrc) {
        when {
            imageSrc.startsWith("file:///") -> imageSrc.removePrefix("file://")
            imageSrc.startsWith("file://") -> imageSrc.removePrefix("file://")
            imageSrc.startsWith("/") -> imageSrc
            else -> null
        }
    }

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim),
            contentAlignment = Alignment.Center,
        ) {
            AndroidView(
                factory = { ctx ->
                    com.morealm.app.ui.widget.image.PhotoView(ctx).apply {
                        setBackgroundColor(android.graphics.Color.BLACK)
                        setMaxScale(5f)
                    }
                },
                update = { photoView ->
                    val model: Any = if (filePath != null) {
                        java.io.File(filePath)
                    } else {
                        imageSrc
                    }
                    val request = coil.request.ImageRequest.Builder(photoView.context)
                        .data(model)
                        .target(photoView)
                        .crossfade(true)
                        .build()
                    // 走全局 ImageLoader（MoRealmApp.newImageLoader 提供），
                    // 自动复用磁盘 + 内存缓存。比之前 `coil.ImageLoader(ctx)`
                    // 临时 new 实例每次都从网络重下要省得多。
                    coil.Coil.imageLoader(photoView.context).enqueue(request)
                },
                modifier = Modifier.fillMaxSize(),
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)
                    .statusBarsPadding(),
            ) {
                Icon(
                    Icons.Default.Close, "关闭",
                    tint = MaterialTheme.colorScheme.inverseOnSurface,
                    modifier = Modifier.size(28.dp),
                )
            }
        }
    }
}
