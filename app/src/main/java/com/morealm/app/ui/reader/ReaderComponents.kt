package com.morealm.app.ui.reader

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.FormatListBulleted
import androidx.compose.material.icons.filled.*
// ── 阅读控制栏底部按钮线性矢量图标 (Image 16 设计) ────────────────────
// 全部走 Outlined 系列让底栏视觉更轻、更现代；TTS 按钮从「人 + 声波」
// (RecordVoiceOver) 换成传统麦克风形状 (Mic)，和大多数音频/朗读类 App 对齐。
// 注意：Icons.Outlined.* 与 Icons.Default.* (= Icons.Filled.*) 是扩展属性，
// 不能用 `import ... as Alias` 重命名当独立标识符用——只能用完整路径
// `Icons.Outlined.Xxx`。这里只 import 包让属性可被解析；引用时写完整路径。
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.ui.input.pointer.pointerInput
import com.morealm.app.domain.entity.ReaderTool
import com.morealm.app.domain.entity.ReaderToolLayout
import com.morealm.app.domain.entity.ReaderToolZone
import com.morealm.app.ui.reader.toolbar.icon
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
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
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.runtime.snapshotFlow
import coil.compose.AsyncImage

// ── Settings Panel (font, page turn mode) ───────────────

@OptIn(ExperimentalFoundationApi::class)
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
    onThemeLongPress: (String) -> Unit = {},
    brightness: Float = -1f,
    onBrightnessChange: (Float) -> Unit = {},
    paragraphSpacing: Int = 8,
    onParagraphSpacingChange: (Int) -> Unit = {},
    /** 首行缩进字符数：0=顶格 / 1 / 2（默认两字）。见 [ReaderSettingsController.setFirstLineIndent]。 */
    firstLineIndent: Int = 2,
    onFirstLineIndentChange: (Int) -> Unit = {},
    /** EPUB 无图模式：隐藏全部插图与背景图（重排版不留空洞）。 */
    epubHideImages: Boolean = false,
    onEpubHideImagesChange: (Boolean) -> Unit = {},
    /** EPUB 排版来源：false = 跟随书籍（原书 CSS 优先）；true = 用户定义（缩进/行距/段距用用户值）。 */
    epubTypographyOverride: Boolean = false,
    onEpubTypographyOverrideChange: (Boolean) -> Unit = {},
    /** 本书「屏蔽此图」张数；>0 时显示恢复入口。 */
    blockedImageCount: Int = 0,
    onClearBlockedImages: () -> Unit = {},
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
    /** 升级前独立背景仍在覆盖配色组；点击任意配色球后由调用方清除。 */
    hasLegacyBackgroundOverride: Boolean = false,
    readerStyles: List<com.morealm.app.domain.entity.ReaderStyle> = emptyList(),
    activeStyleId: String = "",
    onStyleChange: (String) -> Unit = {},
    /**
     * 排版预设导入 / 导出（SAF JSON，与主题 MoRealmThemeBundle 同款信封）。
     * 见 [ReaderSettingsController.exportActiveStyle] / [ReaderSettingsController.importStylesFromUri]。
     */
    onExportStyle: (android.net.Uri) -> Unit = {},
    onImportStyle: (android.net.Uri) -> Unit = {},
    screenOrientation: Int = -1,
    onScreenOrientationChange: (Int) -> Unit = {},
    textSelectable: Boolean = true,
    onTextSelectableChange: (Boolean) -> Unit = {},
    chineseConvertMode: Int = 0,
    onChineseConvertModeChange: (Int) -> Unit = {},
    customTxtChapterRegex: String = "",
    onCustomTxtChapterRegexSave: (String) -> Unit = {},
    showTxtChapterRegex: Boolean = false,
    footerRight: String = "page_progress",
    onFooterRightChange: (String) -> Unit = {},
    /**
     * #1：「恢复默认」一键回退所有阅读相关设置。
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
                         "恢复默认",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            Spacer(Modifier.height(4.dp))

            if (showTxtChapterRegex) {
                var txtRegex by remember(customTxtChapterRegex) {
                    mutableStateOf(customTxtChapterRegex)
                }
                Text(
                    "目录正则（本书）",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = txtRegex,
                    onValueChange = { txtRegex = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("留空使用默认分章规则") },
                    supportingText = { Text("保存后会立即重新生成本书目录") },
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(
                        onClick = { onCustomTxtChapterRegexSave(txtRegex) },
                        enabled = txtRegex != customTxtChapterRegex,
                    ) {
                        Text("保存目录规则")
                    }
                }
            }

            // ── Reader Style Presets ──
            if (readerStyles.isNotEmpty()) {
                val styleExportLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.CreateDocument("application/json")
                ) { uri -> uri?.let(onExportStyle) }
                val styleImportLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocument()
                ) { uri -> uri?.let(onImportStyle) }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    // #4：原「阅读样式」与下方「主题」名字撞，改为「排版预设」表明此处只切排版
                    Text("排版预设", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                    Spacer(Modifier.weight(1f))
                    // 文件管理器常把 .json 报成 octet-stream，OpenDocument 过滤只收
                    // application/json 会让文件灰掉选不了——放宽到三类，格式由解析端判别。
                    TextButton(
                        onClick = {
                            styleImportLauncher.launch(
                                arrayOf("application/json", "text/*", "application/octet-stream"),
                            )
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Icon(
                            Icons.Default.FileDownload, null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(2.dp))
                        Text("导入", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary)
                    }
                    TextButton(
                        onClick = { styleExportLauncher.launch("morealm-styles.json") },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Icon(
                            Icons.Default.FileUpload, null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(2.dp))
                        Text("导出", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary)
                    }
                }
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
            // 字号用 −/＋ 步进按钮（每点 1px 精细微调，替代 Slider 拖动不够精准）。范围 12..100px。
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("A", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface)
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = { val v = (fontSize - 1f).coerceIn(12f, 100f); fontSize = v; onFontSizeChange(v) },
                        enabled = fontSize > 12f,
                    ) {
                        Icon(Icons.Default.Remove, "减小字号", tint = MaterialTheme.colorScheme.primary)
                    }
                    Text("${fontSize.toInt()}px",
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.widthIn(min = 56.dp).padding(horizontal = 8.dp))
                    IconButton(
                        onClick = { val v = (fontSize + 1f).coerceIn(12f, 100f); fontSize = v; onFontSizeChange(v) },
                        enabled = fontSize < 100f,
                    ) {
                        Icon(Icons.Default.Add, "增大字号", tint = MaterialTheme.colorScheme.primary)
                    }
                }
                Text("A", style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface)
            }
            if (fontSize > 50f) {
                Text("⚠ 超大字号可能影响排版",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary)
            }

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
            // 楷体 / 仿宋未打包 ttf 子集，资源缺失时 FontRepository 走系统 fallback：
            // 楷体 → SERIF italic、仿宋 → SERIF normal，肉眼有差异但非真字体。
            // 用户群里反馈过「楷体和仿宋显示一样」就是早期都 fallback 同一个 SERIF
            // 导致；现在给个提示让用户知道想要真效果该走「字体管理 → 导入」。
            if (selectedFont in setOf("kaiti", "fangsong") && customFontName.isEmpty()) {
                Text(
                    text = "未内置字体文件，当前为近似 fallback。如需真正字体请在「字体管理」导入 .ttf",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
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

            Spacer(Modifier.height(16.dp))

            // ── First-line indent ──
            // 独立的「首行缩进」控件（用户反馈：之前找不到缩进设置）。值 = 全角空格个数，
            // 写 ReaderStyle.paragraphIndent。TXT 预埋进正文、EPUB 走引擎 config 自带优先，
            // 详见 ReaderSettingsController.setFirstLineIndent。selected 直接比对入参，
            // 与行距 / 段距 chip 同款单一真值流，无 local state。
            Text("首行缩进", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0 to "顶格", 1 to "1 字", 2 to "2 字").forEach { (v, l) ->
                    FilterChip(
                        selected = firstLineIndent == v,
                        onClick = { onFirstLineIndentChange(v) },
                        label = { Text(l) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                            selectedLabelColor = MaterialTheme.colorScheme.primary),
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // ── EPUB 图片与原书排版 ──
            // 无图模式 + 排版来源（跟随书籍/用户定义）+ 恢复已屏蔽图片。三者都走引擎
            // 参数（hideImages / blockedImageSrcs / overrideAuthoredSpacing），改了即重排版。
            Text("图片与原书排版", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.weight(1f)) {
                    Text("无图模式", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "隐藏书内全部插图与背景图，正文重新排版",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    )
                }
                Switch(checked = epubHideImages, onCheckedChange = onEpubHideImagesChange)
            }
            Spacer(Modifier.height(8.dp))
            Text("排版来源", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = !epubTypographyOverride,
                    onClick = { onEpubTypographyOverrideChange(false) },
                    label = { Text("跟随书籍") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                        selectedLabelColor = MaterialTheme.colorScheme.primary),
                )
                FilterChip(
                    selected = epubTypographyOverride,
                    onClick = { onEpubTypographyOverrideChange(true) },
                    label = { Text("用户定义") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                        selectedLabelColor = MaterialTheme.colorScheme.primary),
                )
            }
            Text(
                if (epubTypographyOverride) "首行缩进 / 行距 / 段距按上面的设置，忽略书内声明"
                else "书内声明的缩进 / 行距 / 段距优先（字号始终用你的）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            )
            if (blockedImageCount > 0) {
                TextButton(
                    onClick = onClearBlockedImages,
                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
                ) {
                    Text(
                        "恢复本书已屏蔽的 $blockedImageCount 张图",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
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

            // ── 阅读配色组：文字色 + 背景色 + 背景图 ──
            if (allThemes.isNotEmpty()) {
                Text("文字颜色和背景（长按自定义）", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                Spacer(Modifier.height(6.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                ) {
                    allThemes.forEach { theme ->
                        val isActive = theme.id == activeThemeId
                        val bgColor = theme.readerBackground.toComposeColor()
                        val fgColor = theme.readerTextColor.toComposeColor()
                        val previewImage = theme.backgroundImageUri
                            ?.takeUnless { it.startsWith("texture:") }
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .width(58.dp)
                                .semantics {
                                    contentDescription = "配色主题：${theme.name}"
                                    role = Role.Button
                                }
                                .combinedClickable(
                                    onClick = { onThemeChange(theme.id) },
                                    onLongClick = { onThemeLongPress(theme.id) },
                                ),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(52.dp)
                                    .clip(CircleShape)
                                    .background(bgColor)
                                    .border(
                                        width = if (isActive) 2.dp else 1.dp,
                                        color = if (isActive) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f),
                                        shape = CircleShape,
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (!previewImage.isNullOrBlank()) {
                                    AsyncImage(
                                        model = previewImage,
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.matchParentSize(),
                                    )
                                    Box(
                                        Modifier.matchParentSize()
                                            .background(bgColor.copy(alpha = 0.18f)),
                                    )
                                }
                                Text(
                                    "文字",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = fgColor,
                                    fontWeight = FontWeight.Bold,
                                )
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
                if (hasLegacyBackgroundOverride) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "当前仍保留旧版独立背景；点击任意配色球后将改为整组跟随。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // 自定义 CSS 编辑入口已迁移到「我的」→「主题编辑」(ThemeEditorScreen)，
            // 阅读器底部抽屉只保留即时可见效果的常用设置（字号 / 行距 / 边距 / 配色），
            // 长文本编辑挪到全屏页面避免拥挤。

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
    onSave: () -> Unit,
    onShare: () -> Unit,
    onDismiss: () -> Unit,
) {
    var showActions by remember(imageSrc) { mutableStateOf(false) }
    val openActions by rememberUpdatedState(newValue = { showActions = true })
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
                        setOnLongClickListener {
                            openActions()
                            true
                        }
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
            if (showActions) {
                ImageViewerActionsPopup(
                    onSave = {
                        showActions = false
                        onSave()
                    },
                    onShare = {
                        showActions = false
                        onShare()
                    },
                    onDismiss = { showActions = false },
                )
            }
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
