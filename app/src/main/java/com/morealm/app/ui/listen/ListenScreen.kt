package com.morealm.app.ui.listen

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.morealm.app.domain.entity.TtsVoice
import com.morealm.app.presentation.profile.ListenViewModel
import com.morealm.app.service.TtsSystemSettings

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ListenScreen(
    viewModel: ListenViewModel = hiltViewModel(),
    onNavigateHttpTtsManage: () -> Unit = {},
) {
    val playback by viewModel.playbackState.collectAsStateWithLifecycle()
    val selectedEngine by viewModel.selectedEngine.collectAsStateWithLifecycle()
    val selectedSpeed by viewModel.selectedSpeed.collectAsStateWithLifecycle()
    val voices by viewModel.voices.collectAsStateWithLifecycle()
    val selectedVoice by viewModel.selectedVoice.collectAsStateWithLifecycle()
    val voicesRefreshing by viewModel.voicesRefreshing.collectAsStateWithLifecycle()
    // HttpTts 已启用源列表，用来在引擎选择 chip 后面追加自定义源
    val httpTtsList by viewModel.httpTtsList.collectAsStateWithLifecycle()
    // Bug 4：TTS 硬错误持久化提示，替代旧的 Toast 路径
    val ttsErrorBanner by viewModel.ttsErrorBanner.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val isActive = playback.bookTitle.isNotBlank()
    val progress = if (playback.totalParagraphs > 0)
        playback.paragraphIndex.toFloat() / playback.totalParagraphs else 0f
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(300, easing = FastOutSlowInEasing),
        label = "ttsProgress",
    )

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
        Spacer(Modifier.height(64.dp))

        // Gradient title
        Text(
            "听书",
            style = MaterialTheme.typography.headlineMedium.copy(
                brush = Brush.linearGradient(
                    listOf(
                        MaterialTheme.colorScheme.onBackground,
                        MaterialTheme.colorScheme.primary,
                    )
                )
            ),
        )
        Text(
            "沉浸式聆听体验",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
        )

        // Bug 4：TTS 硬错误持久化 banner —— 仅在 canOpenSettings=true 的事件后显示。
        // 让位给系统 Toast（更权威，可点）后，UI 现场用这个 banner 兜底，避免用户错过提示。
        ttsErrorBanner?.let { msg ->
            Spacer(Modifier.height(12.dp))
            Surface(
                modifier = Modifier
                    .padding(horizontal = 24.dp)
                    .fillMaxWidth(),
                color = MaterialTheme.colorScheme.errorContainer,
                shape = MaterialTheme.shapes.medium,
                tonalElevation = 1.dp,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = msg,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(6.dp))
                    TextButton(
                        onClick = {
                            // 跳转系统 TTS 设置；TtsSystemSettings.open 内部已带 fallback
                            // (TTS_SETTINGS → VOICE_INPUT_SETTINGS → 应用详情页)
                            TtsSystemSettings.open(context)
                            viewModel.dismissTtsErrorBanner()
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    ) { Text("去设置", style = MaterialTheme.typography.labelMedium) }
                    IconButton(
                        onClick = { viewModel.dismissTtsErrorBanner() },
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "关闭提示",
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(28.dp))

        // Pulsing cover circle
        TtsCoverCircle(isPlaying = playback.isPlaying)

        Spacer(Modifier.height(28.dp))

        // Book title + chapter
        Text(
            if (isActive) playback.bookTitle else "未在播放",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        if (isActive) {
            Text(
                playback.chapterTitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                modifier = Modifier.padding(top = 3.dp),
            )
        }

        Spacer(Modifier.height(20.dp))

        // Progress bar
        Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp)) {
            LinearProgressIndicator(
                progress = { animatedProgress },
                modifier = Modifier.fillMaxWidth().height(4.dp)
                    .clip(MaterialTheme.shapes.extraSmall),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
            Spacer(Modifier.height(5.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    if (isActive) "第 ${playback.paragraphIndex + 1} 段" else "--",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f),
                )
                Text(
                    if (isActive) "共 ${playback.totalParagraphs} 段" else "--",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f),
                )
            }
        }

        Spacer(Modifier.height(24.dp))

        // Playback controls
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            // Previous chapter
            IconButton(onClick = { viewModel.sendPrevChapter() }, Modifier.size(48.dp)) {
                Icon(Icons.Default.SkipPrevious, "上一章",
                    modifier = Modifier.size(22.dp),
                    tint = MaterialTheme.colorScheme.onBackground)
            }
            // Rewind = 上一段 (TTS 没有"10秒"概念，复用 Replay10 图标表达"回退一段")
            IconButton(onClick = { viewModel.sendPrevParagraph() }, Modifier.size(48.dp)) {
                Icon(Icons.Default.Replay10, "上一段",
                    modifier = Modifier.size(22.dp),
                    tint = MaterialTheme.colorScheme.onBackground)
            }
            // Play / Pause
            FilledIconButton(
                onClick = { viewModel.sendPlayPause() },
                modifier = Modifier.size(60.dp),
                shape = CircleShape,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                ),
            ) {
                Icon(
                    if (playback.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (playback.isPlaying) "暂停" else "播放",
                    modifier = Modifier.size(28.dp),
                    tint = MaterialTheme.colorScheme.onPrimary,
                )
            }
            // Forward = 下一段
            IconButton(onClick = { viewModel.sendNextParagraph() }, Modifier.size(48.dp)) {
                Icon(Icons.Default.Forward10, "下一段",
                    modifier = Modifier.size(22.dp),
                    tint = MaterialTheme.colorScheme.onBackground)
            }
            // Next chapter
            IconButton(onClick = { viewModel.sendNextChapter() }, Modifier.size(48.dp)) {
                Icon(Icons.Default.SkipNext, "下一章",
                    modifier = Modifier.size(22.dp),
                    tint = MaterialTheme.colorScheme.onBackground)
            }
        }

        Spacer(Modifier.height(28.dp))

        // Engine selection
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "TTS 引擎",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f),
                )
                // HttpTts 自定义朗读源管理入口。这里不再单独写"添加源"——配合管理屏
                // 内的导入/新建对话框，所有 HttpTts CRUD 都集中在该屏完成。
                TextButton(
                    onClick = onNavigateHttpTtsManage,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                ) {
                    Icon(
                        Icons.Default.Settings,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("自定义朗读源", style = MaterialTheme.typography.labelSmall)
                }
            }
            Spacer(Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                data class EngineOpt(val id: String, val label: String)
                // 内置两个引擎 + 用户配置的所有 HttpTts 源（仅 enabled）。
                // 之前的占位 "OpenAI" / "自定义 API" chip 删掉——选了不响应反而误导。
                val engines = buildList {
                    add(EngineOpt("edge", "Edge TTS"))
                    add(EngineOpt("system", "系统 TTS"))
                    httpTtsList.filter { it.enabled }.forEach { tts ->
                        add(EngineOpt("http_${tts.id}", tts.name.ifBlank { "自定义源" }))
                    }
                }
                engines.forEach { eng ->
                    val isSelected = selectedEngine == eng.id
                    Surface(
                        onClick = { viewModel.selectEngine(eng.id) },
                        shape = MaterialTheme.shapes.small,
                        color = if (isSelected)
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        border = BorderStroke(
                            1.dp,
                            if (isSelected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outlineVariant,
                        ),
                    ) {
                        Text(
                            eng.label,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isSelected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        )
                    }
                }
            }

            // System-TTS troubleshooting entry: a one-tap shortcut to the OS's
            // TTS settings page. Useful when the system engine fails to bind
            // (the corresponding Toast also tells users to come here), or when
            // the user wants to switch the underlying engine / install voice data.
            // We surface it unconditionally rather than gating on `selectedEngine == "system"`
            // because Edge TTS users may still need to fall back to system TTS offline.
            val context = LocalContext.current
            Spacer(Modifier.height(6.dp))
            TextButton(
                onClick = { TtsSystemSettings.open(context) },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
            ) {
                Icon(
                    Icons.Default.Settings,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    "打开系统 TTS 设置",
                    style = MaterialTheme.typography.labelSmall,
                )
            }

            // 系统 TTS 引擎包绑定 —— 仅在选了 "system" 引擎时显示。
            // 解决 multiTTS / 讯飞 / 三星 TTS 装了却被默认引擎路径漏识别的问题；
            // 直接绑指定包 → TextToSpeech(ctx, listener, engineName) 路径。
            // 改动后需重启阅读器，TtsEngineHost 的 systemTtsEngine 是 lazy。
            if (selectedEngine == "system") {
                val systemEngines by viewModel.systemEngines.collectAsStateWithLifecycle()
                val selectedPkg by viewModel.selectedSystemEnginePackage.collectAsStateWithLifecycle()
                var showEnginePicker by remember { mutableStateOf(false) }

                LaunchedEffect(showEnginePicker) {
                    // 打开 picker 时主动拉一次最新引擎列表，刚装新引擎也能立刻看见
                    if (showEnginePicker) viewModel.refreshSystemEngineList()
                }

                Spacer(Modifier.height(4.dp))
                TextButton(
                    onClick = { showEnginePicker = true },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                ) {
                    Icon(
                        Icons.Default.RecordVoiceOver,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "系统 TTS 引擎包: ${
                            selectedPkg.ifBlank { "跟随系统默认" }
                        }",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }

                if (showEnginePicker) {
                    SystemEnginePickerDialog(
                        engines = systemEngines,
                        selected = selectedPkg,
                        onSelect = { pkg ->
                            viewModel.selectSystemEnginePackage(pkg)
                            showEnginePicker = false
                            // ViewModel 会通过 RebindSystemEngine command 即时换 host 引擎；
                            // 提示文案不再说"重启阅读器后生效"。
                            android.widget.Toast.makeText(
                                context,
                                "已切换 TTS 引擎",
                                android.widget.Toast.LENGTH_SHORT,
                            ).show()
                        },
                        onDismiss = { showEnginePicker = false },
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            TtsVoiceSelector(
                voices = voices,
                selectedVoice = selectedVoice,
                onVoiceChange = viewModel::selectVoice,
                engineId = selectedEngine,
                voicesCount = voices.size,
                isRefreshing = voicesRefreshing,
                onRefresh = viewModel::refreshVoiceListNow,
            )

            if (voices.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
            }

            // Speed selection
            Text("语速", style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                val speeds = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
                speeds.forEach { spd ->
                    val isSelected = selectedSpeed == spd
                    Surface(
                        onClick = { viewModel.selectSpeed(spd) },
                        shape = MaterialTheme.shapes.small,
                        color = if (isSelected)
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        border = BorderStroke(
                            1.dp,
                            if (isSelected) MaterialTheme.colorScheme.primary
                            else Color.Transparent,
                        ),
                    ) {
                        Text(
                            "${spd}x",
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isSelected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(120.dp))
    }

        com.morealm.app.ui.widget.TtsErrorSnackbarHost(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 8.dp)
        )
    }
}

@Composable
private fun TtsVoiceSelector(
    voices: List<TtsVoice>,
    selectedVoice: String,
    onVoiceChange: (String) -> Unit,
    engineId: String,
    voicesCount: Int,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
) {
    if (voices.isEmpty() && !isRefreshing && engineId !in setOf("edge", "system")) return

    var showVoiceMenu by remember { mutableStateOf(false) }
    val displayName = if (selectedVoice.isBlank()) {
        "默认"
    } else {
        voices.find { it.id == selectedVoice }?.name?.take(28)
            ?: selectedVoice.substringAfterLast("#").take(28)
    }

    // 标题行：左"语音"，右刷新按钮（仅 edge / system 可刷，其它引擎刷新无意义）
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "语音",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
        )
        if (engineId == "edge" || engineId == "system") {
            IconButton(
                onClick = onRefresh,
                enabled = !isRefreshing,
                modifier = Modifier.size(28.dp),
            ) {
                if (isRefreshing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 1.5.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = "刷新音色列表",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    Box {
        OutlinedButton(
            onClick = { showVoiceMenu = true },
            modifier = Modifier.fillMaxWidth().height(40.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
            enabled = voices.isNotEmpty(),
        ) {
            Text(
                displayName,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(Icons.Default.ArrowDropDown, null, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(
            expanded = showVoiceMenu,
            onDismissRequest = { showVoiceMenu = false },
        ) {
            DropdownMenuItem(
                text = {
                    Text("默认", fontWeight = if (selectedVoice.isBlank()) FontWeight.Bold else FontWeight.Normal)
                },
                onClick = { onVoiceChange(""); showVoiceMenu = false },
            )
            voices.take(30).forEach { voice ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(
                                voice.name.substringAfterLast("#").take(30),
                                fontWeight = if (selectedVoice == voice.id) FontWeight.Bold else FontWeight.Normal,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                voice.language,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            )
                        }
                    },
                    onClick = { onVoiceChange(voice.id); showVoiceMenu = false },
                )
            }
        }
    }
    // 数据来源 hint —— 让用户清楚音色列表是哪儿来的，刷新按钮才有"我能干预什么"的语义
    Spacer(Modifier.height(4.dp))
    val hint = when (engineId) {
        "edge" -> "数据来源：Edge 远程列表（24h 文件缓存，共 $voicesCount 个）"
        "system" -> "数据来源：本机已安装的 TTS 引擎（共 $voicesCount 个中文音色）"
        else -> ""
    }
    if (hint.isNotEmpty()) {
        Text(
            hint,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
        )
    }
}

/**
 * Large circular cover. 之前用 infiniteTransition 让 size 在 1.0~1.06 之间缓动
 * （类似呼吸效果），但视觉上是封面"上下浮动 / 缩放缓动"，长时间盯着会不舒服，
 * 用户反馈后去掉。保留静态外发光圈和阴影，仍能体现"在播"的氛围。
 */
@Composable
private fun TtsCoverCircle(isPlaying: Boolean) {
    val accent = MaterialTheme.colorScheme.primary
    val glowAlpha = 0.08f

    Box(contentAlignment = Alignment.Center) {
        // Outer glow ring
        Box(
            Modifier
                .size(196.dp)
                .clip(CircleShape)
                .background(accent.copy(alpha = glowAlpha))
        )
        // Cover circle
        Box(
            modifier = Modifier
                .size(180.dp)
                .shadow(24.dp, CircleShape, ambientColor = accent.copy(alpha = 0.35f))
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        listOf(
                            accent.copy(alpha = 0.3f),
                            accent.copy(alpha = 0.6f),
                        )
                    )
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text("📖", fontSize = 56.sp)
        }
    }
}

/**
 * 弹层让用户在已安装的系统 TTS 引擎里选一个绑定。空列表时提示去系统设置安装；
 * 永远附"跟随系统默认"作为第一项，让用户能撤销。
 */
@Composable
private fun SystemEnginePickerDialog(
    engines: List<com.morealm.app.domain.tts.SystemTtsEngine.EngineInfo>,
    selected: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择系统 TTS 引擎") },
        text = {
            Column {
                Text(
                    "改动后需重启阅读器生效。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
                Spacer(Modifier.height(12.dp))
                // 跟系统默认项
                EngineRow(
                    label = "跟随系统默认",
                    pkg = "",
                    selected = selected.isBlank(),
                    onClick = { onSelect("") },
                )
                if (engines.isEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "未检测到任何 TTS 引擎，请先到系统设置安装",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else {
                    engines.forEach { eng ->
                        EngineRow(
                            label = eng.label.ifBlank { eng.name },
                            pkg = eng.name,
                            selected = selected == eng.name,
                            onClick = { onSelect(eng.name) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

@Composable
private fun EngineRow(
    label: String,
    pkg: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    // ListItem 三槽位：leading 用 RadioButton 显示选中态，headline = 引擎名，
    // supporting = 包名（pkg 为空时整个 supporting 不渲染）。
    // 选中态的浅主题色背景由 ListItem.colors.containerColor 提供；点击效果由
    // Modifier.clickable 接收，与原 Surface(onClick=) 等价。
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface,
            )
        },
        supportingContent = pkg.takeIf { it.isNotBlank() }?.let {
            { Text(it, style = MaterialTheme.typography.labelSmall) }
        },
        leadingContent = {
            RadioButton(selected = selected, onClick = null)
        },
        colors = ListItemDefaults.colors(
            containerColor = if (selected)
                MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
            else
                androidx.compose.ui.graphics.Color.Transparent,
        ),
    )
}
