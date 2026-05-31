package com.morealm.app.ui.listen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.morealm.app.domain.entity.HttpTts
import com.morealm.app.domain.entity.TtsVoice
import com.morealm.app.domain.tts.SystemTtsEngine
import com.morealm.app.presentation.profile.ListenViewModel
import com.morealm.app.service.TtsEventBus
import com.morealm.app.service.TtsSystemSettings
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 听书 Tab（v2 单屏重构，反馈 #5）。
 *
 * # 设计目标
 *  1. 一屏直达：常用操作（播放控制 / 切发音人 / 定时 / 进 TTS 设置）全部在主屏一屏内，
 *     不需要滚动寻找设置。详细配置走 BottomSheet 抽屉，按需呼出。
 *  2. 封面伸缩：未播放 130dp、播放中扩到 200dp + 光晕加亮，[animateDpAsState] 驱动，
 *     不用 infiniteTransition —— 静态大圈比持续呼吸更耐看（旧版同样理由，见 git 历史）。
 *  3. 主题一致：顶部用 `verticalGradient(primary.alpha 0.14 → 0)` 叠一层薄主题色，
 *     日/夜随 MaterialTheme.colorScheme.primary 自动过渡；不做大面积色块，以免破坏
 *     GlobalBackgroundScaffold 的统一底色（书架/发现/我的共用那套）。
 *  4. 矢量图标：全部走 [androidx.compose.material.icons]，不新增静态 png/svg；颜色
 *     从 colorScheme 取，保证主题切换即时反映。
 *
 * # 与旧版差异
 *  - 移除 `"听书"` 大标题 + 副标题装饰块（节省 ~70dp 高度）
 *  - 移除主屏直出的引擎/语速/音色 chip，挪进 TTS 设置 / 发音人 Sheet
 *  - 移除"打开系统 TTS 设置"主屏入口，并入 TTS 设置 Sheet；用户出问题时位置依然可达
 *  - 引入定时关闭入口（sendCommand SetSleepMinutes），对齐 TtsPanel 已有能力
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
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
    val httpTtsList by viewModel.httpTtsList.collectAsStateWithLifecycle()
    val ttsErrorBanner by viewModel.ttsErrorBanner.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val snackbarHost = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val isActive = playback.bookTitle.isNotBlank()
    val progress = if (playback.totalParagraphs > 0)
        playback.paragraphIndex.toFloat() / playback.totalParagraphs else 0f
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(300, easing = FastOutSlowInEasing),
        label = "tts_progress",
    )

    // 共用一个 sheetState：避免多 sheet 同时挂起造成 z-order 抖动；通过 currentSheet
    // 切换 sheet 内容（Voice/Sleep/Settings）。
    var currentSheet by rememberSaveable { mutableStateOf<ListenSheet?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // 主题渐变背景 —— 只占顶部 ~35% 高度，底部透明露出 GlobalBackgroundScaffold。
    // 切日夜时 MaterialTheme.colorScheme.primary 随主题过渡，bgBrush 由 remember(primary)
    // 自动重算，整体视觉跟随主题不突兀。
    val primary = MaterialTheme.colorScheme.primary
    val bgBrush = remember(primary) {
        Brush.verticalGradient(
            0f to primary.copy(alpha = 0.14f),
            0.35f to primary.copy(alpha = 0.05f),
            1f to Color.Transparent,
        )
    }

    Box(modifier = Modifier.fillMaxSize().background(bgBrush)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                // 底部给 pill 导航 + Snackbar 留呼吸；主屏不滚动，只在窄屏时做 overflow
                .verticalScroll(rememberScrollState())
                .padding(bottom = 110.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(44.dp))

            // TTS 硬错误 banner（canOpenSettings=true 路径）
            ttsErrorBanner?.let { msg ->
                TtsErrorBanner(
                    message = msg,
                    onOpenSettings = {
                        TtsSystemSettings.open(context)
                        viewModel.dismissTtsErrorBanner()
                    },
                    onDismiss = { viewModel.dismissTtsErrorBanner() },
                )
                Spacer(Modifier.height(16.dp))
            }

            // 圆形封面 —— 播放前 130dp、播放中 200dp
            TtsCoverCircle(isPlaying = playback.isPlaying)

            Spacer(Modifier.height(22.dp))

            Text(
                text = if (isActive) playback.bookTitle else "未在播放",
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.SemiBold,
                ),
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 32.dp),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (isActive) playback.chapterTitle
                       else "在书架打开一本书，按 ▶ 开始朗读",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(
                    alpha = if (isActive) 0.6f else 0.45f,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 32.dp),
            )

            Spacer(Modifier.height(24.dp))

            // 段落进度条 ——「第 N 段 / 共 M 段」
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 36.dp),
            ) {
                LinearProgressIndicator(
                    progress = { animatedProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = if (isActive) "第 ${playback.paragraphIndex + 1} 段" else "--",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                    )
                    Text(
                        text = if (isActive) "共 ${playback.totalParagraphs} 段" else "--",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                    )
                }
            }

            Spacer(Modifier.height(28.dp))

            PlayerControlRow(
                isPlaying = playback.isPlaying,
                onPlayPause = viewModel::sendPlayPause,
                onPrevChapter = viewModel::sendPrevChapter,
                onPrevParagraph = viewModel::sendPrevParagraph,
                onNextParagraph = viewModel::sendNextParagraph,
                onNextChapter = viewModel::sendNextChapter,
            )

            Spacer(Modifier.height(28.dp))

            ListenActionRow(
                voiceLabel = voiceDisplayName(selectedVoice, voices),
                sleepMinutes = playback.sleepMinutes,
                onPickVoice = { currentSheet = ListenSheet.Voice },
                onPickSleep = { currentSheet = ListenSheet.Sleep },
                onOpenSettings = { currentSheet = ListenSheet.Settings },
            )
        }

        // Snackbar 层 —— 浮在 pill 之上
        com.morealm.app.ui.widget.TtsErrorSnackbarHost(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 96.dp),
        )
        com.morealm.app.ui.widget.ThemedSnackbarHost(
            hostState = snackbarHost,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 96.dp),
        )
    }

    if (currentSheet != null) {
        ModalBottomSheet(
            onDismissRequest = { currentSheet = null },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            when (currentSheet) {
                ListenSheet.Voice -> VoiceSheet(
                    voices = voices,
                    selectedVoice = selectedVoice,
                    engineId = selectedEngine,
                    isRefreshing = voicesRefreshing,
                    selectedSpeed = selectedSpeed,
                    onVoiceChange = viewModel::selectVoice,
                    onSpeedChange = viewModel::selectSpeed,
                    onRefresh = viewModel::refreshVoiceListNow,
                )
                ListenSheet.Sleep -> SleepSheet(
                    currentMinutes = playback.sleepMinutes,
                    onPick = { m ->
                        TtsEventBus.sendCommand(TtsEventBus.Command.SetSleepMinutes(m))
                        currentSheet = null
                    },
                )
                ListenSheet.Settings -> SettingsSheet(
                    selectedEngine = selectedEngine,
                    httpTtsList = httpTtsList,
                    onSelectEngine = viewModel::selectEngine,
                    onNavigateHttpTtsManage = {
                        currentSheet = null
                        onNavigateHttpTtsManage()
                    },
                    onOpenSystemSettings = { TtsSystemSettings.open(context) },
                    onPickSystemEnginePkg = { pkg ->
                        viewModel.selectSystemEnginePackage(pkg)
                        scope.launch { snackbarHost.showSnackbar("已切换 TTS 引擎") }
                    },
                    selectedSystemEnginePackageFlow = viewModel.selectedSystemEnginePackage,
                    systemEnginesFlow = viewModel.systemEngines,
                    onRefreshSystemEngines = viewModel::refreshSystemEngineList,
                )
                null -> Unit
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

// ───────────────────────── UI building blocks ─────────────────────────

private enum class ListenSheet { Voice, Sleep, Settings }

/**
 * 圆形封面：播放前 170dp、播放中 200dp；加外发光圈 + soft shadow。
 *
 * 动画链：
 *  - `coverSize` 双状态 [animateDpAsState] 过渡 520ms
 *  - `glowSize` / `glowAlpha` 同步，让外圈跟随呼吸
 *
 * 不用 infiniteTransition（长时间盯着会不舒服，见 v1 反馈）。
 * 封面常态 170dp —— 未播放时也要有视觉分量（对齐设计稿）。
 */
@Composable
private fun TtsCoverCircle(isPlaying: Boolean) {
    val accent = MaterialTheme.colorScheme.primary

    val coverSize by animateDpAsState(
        targetValue = if (isPlaying) 200.dp else 170.dp,
        animationSpec = tween(520, easing = FastOutSlowInEasing),
        label = "tts_cover_size",
    )
    val glowSize by animateDpAsState(
        targetValue = if (isPlaying) 230.dp else 194.dp,
        animationSpec = tween(520, easing = FastOutSlowInEasing),
        label = "tts_cover_glow",
    )
    val glowAlpha by animateFloatAsState(
        targetValue = if (isPlaying) 0.22f else 0.12f,
        animationSpec = tween(520, easing = FastOutSlowInEasing),
        label = "tts_cover_glow_alpha",
    )

    Box(contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(glowSize)
                .clip(CircleShape)
                .background(accent.copy(alpha = glowAlpha)),
        )
        Box(
            modifier = Modifier
                .size(coverSize)
                .shadow(
                    elevation = if (isPlaying) 28.dp else 20.dp,
                    shape = CircleShape,
                    ambientColor = accent.copy(alpha = 0.4f),
                    spotColor = accent.copy(alpha = 0.4f),
                )
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        listOf(
                            accent.copy(alpha = 0.78f),
                            accent,
                        ),
                    ),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.Headphones,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(if (isPlaying) 82.dp else 68.dp),
            )
        }
    }
}

@Composable
private fun PlayerControlRow(
    isPlaying: Boolean,
    onPlayPause: () -> Unit,
    onPrevChapter: () -> Unit,
    onPrevParagraph: () -> Unit,
    onNextParagraph: () -> Unit,
    onNextChapter: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        ControlIconButton(Icons.Default.SkipPrevious, "上一章", onPrevChapter)
        ControlIconButton(Icons.Default.Replay10, "上一段", onPrevParagraph)

        FilledIconButton(
            onClick = onPlayPause,
            modifier = Modifier.size(68.dp),
            shape = CircleShape,
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (isPlaying) "暂停" else "播放",
                modifier = Modifier.size(32.dp),
            )
        }

        ControlIconButton(Icons.Default.Forward10, "下一段", onNextParagraph)
        ControlIconButton(Icons.Default.SkipNext, "下一章", onNextChapter)
    }
}

@Composable
private fun ControlIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, modifier = Modifier.size(44.dp)) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.78f),
        )
    }
}

@Composable
private fun ListenActionRow(
    voiceLabel: String,
    sleepMinutes: Int,
    onPickVoice: () -> Unit,
    onPickSleep: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ListenActionPill(
            icon = Icons.Default.RecordVoiceOver,
            label = "发音人",
            value = voiceLabel,
            modifier = Modifier.weight(1f),
            onClick = onPickVoice,
        )
        ListenActionPill(
            icon = if (sleepMinutes > 0) Icons.Default.Timer else Icons.Default.TimerOff,
            label = "定时",
            value = if (sleepMinutes > 0) "${sleepMinutes}分后" else "关闭",
            highlight = sleepMinutes > 0,
            modifier = Modifier.weight(1f),
            onClick = onPickSleep,
        )
        ListenActionPill(
            icon = Icons.Default.Tune,
            label = "TTS 设置",
            value = "",
            modifier = Modifier.weight(1f),
            onClick = onOpenSettings,
        )
    }
}

/**
 * 底部三按钮：扁平图标 + 文字，不带边框/背景层。
 *
 * 设计稿确认（反馈 #6）：这三个入口只是进 BottomSheet 的快捷键，视觉上不需要与
 * 封面/播放键争焦点；去掉 Surface 那一层圆角胶囊壳，只留 Icon + 两行文字，
 * 用纯 clickable + ripple 指示可点击。highlight=true 时用 primary 色调强调。
 */
@Composable
private fun ListenActionPill(
    icon: ImageVector,
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    highlight: Boolean = false,
    onClick: () -> Unit,
) {
    val tint = if (highlight)
        MaterialTheme.colorScheme.primary
    else
        MaterialTheme.colorScheme.onBackground.copy(alpha = 0.78f)
    val subTint = if (highlight)
        MaterialTheme.colorScheme.primary
    else
        MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f)

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(24.dp),
        )
        Text(
            text = value.ifBlank { label },
            style = MaterialTheme.typography.labelSmall,
            color = subTint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            fontWeight = if (highlight) FontWeight.SemiBold else FontWeight.Medium,
        )
    }
}

@Composable
private fun TtsErrorBanner(
    message: String,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .padding(horizontal = 24.dp)
            .fillMaxWidth(),
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(12.dp),
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
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(6.dp))
            TextButton(
                onClick = onOpenSettings,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
            ) { Text("去设置", style = MaterialTheme.typography.labelMedium) }
            IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
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

// ───────────────────────── BottomSheets ─────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VoiceSheet(
    voices: List<TtsVoice>,
    selectedVoice: String,
    engineId: String,
    isRefreshing: Boolean,
    selectedSpeed: Float,
    onVoiceChange: (String) -> Unit,
    onSpeedChange: (Float) -> Unit,
    onRefresh: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "选择发音人",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            if (engineId == "edge" || engineId == "system") {
                IconButton(onClick = onRefresh, enabled = !isRefreshing) {
                    if (isRefreshing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    } else {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "刷新发音人",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
        val hint = when (engineId) {
            "edge" -> "Edge 远程列表 · 共 ${voices.size} 个"
            "system" -> "本机 TTS 引擎 · 共 ${voices.size} 个中文音色"
            else -> "自定义朗读源"
        }
        Text(
            hint,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
        )

        Spacer(Modifier.height(12.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 320.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            SheetRadioRow(
                label = "默认",
                sub = "跟随引擎默认音色",
                selected = selectedVoice.isBlank(),
                onClick = { onVoiceChange("") },
            )
            voices.take(60).forEach { v ->
                SheetRadioRow(
                    label = v.name.substringAfterLast("#").take(30),
                    sub = v.language,
                    selected = selectedVoice == v.id,
                    onClick = { onVoiceChange(v.id) },
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        Text(
            "语速",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(6.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f).forEach { spd ->
                val selected = selectedSpeed == spd
                Surface(
                    onClick = { onSpeedChange(spd) },
                    shape = RoundedCornerShape(10.dp),
                    color = if (selected)
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                    else
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    border = BorderStroke(
                        1.dp,
                        if (selected) MaterialTheme.colorScheme.primary
                        else Color.Transparent,
                    ),
                ) {
                    Text(
                        "${spd}x",
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    )
                }
            }
        }
    }
}

@Composable
private fun SheetRadioRow(
    label: String,
    sub: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface,
            )
            if (sub.isNotBlank()) {
                Text(
                    sub,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                )
            }
        }
    }
}

@Composable
private fun SleepSheet(
    currentMinutes: Int,
    onPick: (Int) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        Text(
            "定时关闭",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            "到点后自动停止朗读",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
        )
        Spacer(Modifier.height(12.dp))
        listOf(
            0 to "不定时",
            15 to "15 分钟",
            30 to "30 分钟",
            60 to "1 小时",
            90 to "1.5 小时",
            120 to "2 小时",
        ).forEach { (min, label) ->
            SheetRadioRow(
                label = label,
                sub = "",
                selected = currentMinutes == min,
                onClick = { onPick(min) },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingsSheet(
    selectedEngine: String,
    httpTtsList: List<HttpTts>,
    onSelectEngine: (String) -> Unit,
    onNavigateHttpTtsManage: () -> Unit,
    onOpenSystemSettings: () -> Unit,
    onPickSystemEnginePkg: (String) -> Unit,
    selectedSystemEnginePackageFlow: StateFlow<String>,
    systemEnginesFlow: StateFlow<List<SystemTtsEngine.EngineInfo>>,
    onRefreshSystemEngines: () -> Unit,
) {
    val selectedPkg by selectedSystemEnginePackageFlow.collectAsStateWithLifecycle()
    val systemEngines by systemEnginesFlow.collectAsStateWithLifecycle()
    var showEnginePicker by remember { mutableStateOf(false) }

    // 首次打开「系统 TTS 引擎包」picker 时拉一次引擎列表（getEngines 有开销，按需拉）
    LaunchedEffect(showEnginePicker) {
        if (showEnginePicker) onRefreshSystemEngines()
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Text(
            "TTS 设置",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(12.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "TTS 引擎",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
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
        Spacer(Modifier.height(6.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            data class EngineOpt(val id: String, val label: String)
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
                    onClick = { onSelectEngine(eng.id) },
                    shape = RoundedCornerShape(10.dp),
                    color = if (isSelected)
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                    else
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    border = BorderStroke(
                        1.dp,
                        if (isSelected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outlineVariant,
                    ),
                ) {
                    Text(
                        eng.label,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (isSelected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        TextButton(
            onClick = onOpenSystemSettings,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
        ) {
            Icon(
                Icons.Default.Settings,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(6.dp))
            Text("打开系统 TTS 设置", style = MaterialTheme.typography.labelMedium)
        }

        // 系统引擎包 picker 入口 —— 仅 selectedEngine=system 时才显示
        AnimatedVisibility(
            visible = selectedEngine == "system",
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            Column {
                Spacer(Modifier.height(4.dp))
                TextButton(
                    onClick = { showEnginePicker = true },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                ) {
                    Icon(
                        Icons.Default.RecordVoiceOver,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "系统 TTS 引擎包: ${selectedPkg.ifBlank { "跟随系统默认" }}",
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
    }

    if (showEnginePicker) {
        SystemEnginePickerDialog(
            engines = systemEngines,
            selected = selectedPkg,
            onSelect = { pkg ->
                onPickSystemEnginePkg(pkg)
                showEnginePicker = false
            },
            onDismiss = { showEnginePicker = false },
        )
    }
}

// ───────────────────────── Helpers ─────────────────────────

/** 底部"发音人"按钮的显示值：最长 10 字符，去掉 #XX 前缀。 */
private fun voiceDisplayName(selectedVoice: String, voices: List<TtsVoice>): String {
    if (selectedVoice.isBlank()) return "默认"
    return voices.find { it.id == selectedVoice }?.name?.substringAfterLast("#")?.take(10)
        ?: selectedVoice.substringAfterLast("#").take(10)
}

@Composable
private fun SystemEnginePickerDialog(
    engines: List<SystemTtsEngine.EngineInfo>,
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
                    "改动后即时生效，无需重启。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
                Spacer(Modifier.height(12.dp))
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
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
private fun EngineRow(
    label: String,
    pkg: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
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
        leadingContent = { RadioButton(selected = selected, onClick = null) },
        colors = ListItemDefaults.colors(
            containerColor = if (selected)
                MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
            else
                Color.Transparent,
        ),
    )
}
