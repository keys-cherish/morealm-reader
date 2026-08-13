package com.morealm.app.ui.profile

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import com.morealm.app.domain.entity.ThemeEntity
import com.morealm.app.domain.render.CssParser
import com.morealm.app.presentation.theme.ThemeViewModel
import com.morealm.app.ui.reader.CssEditorSection
import com.morealm.app.ui.theme.toComposeColor

/**
 * Full-screen theme editor, replacing the old cramped AlertDialog.
 * Provides space for color pickers, CSS editor, and live preview.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemeEditorScreen(
    themeViewModel: ThemeViewModel,
    onBack: () -> Unit,
    editThemeId: String? = null,
) {
    val allThemes by themeViewModel.allThemes.collectAsStateWithLifecycle()
    val activeTheme by themeViewModel.activeTheme.collectAsStateWithLifecycle()

    val customThemes = remember(allThemes) { allThemes.filter { !it.isBuiltin } }
    var selectedThemeId by remember(editThemeId) { mutableStateOf(editThemeId) }
    val selectedSourceTheme = remember(selectedThemeId, allThemes) {
        selectedThemeId?.let { id -> allThemes.find { it.id == id } }
    }
    // 自定义主题原位编辑；内置主题只能作为模板复制，绝不能拿同 id 覆盖内置行。
    val editingTheme = selectedSourceTheme?.takeUnless { it.isBuiltin }
    val cloningBuiltinTheme = selectedSourceTheme?.takeIf { it.isBuiltin }

    var themeName by remember { mutableStateOf("我的主题") }
    var isNight by remember { mutableStateOf(false) }
    // 用户是否手动切过 isNight 开关。一旦切过就不再让 bgColor 联动覆盖；否则 bgColor
    // 改深 / 改浅时自动同步 isNight，省得用户做完浅色主题忘记关掉「暗色主题」开关，
    // 结果保存后只在「夜晚默认主题」对话框里能看到（白天对话框按 !isNightTheme 过滤）。
    var userOverrodeIsNight by remember { mutableStateOf(false) }
    var bgColor by remember { mutableStateOf("FF0A0A0F") }
    var textColor by remember { mutableStateOf("FFEDEDEF") }
    var accentColor by remember { mutableStateOf("FF818CF8") }
    var customCss by remember { mutableStateOf("") }
    var editingColor by remember { mutableStateOf<String?>(null) }
    // v1.3：阅读器背景图绑到主题。null 表示无背景图（透明 / 走背景色）。
    // SAF Uri 字符串；rememberAsyncImagePainter 直接消费。
    var backgroundImageUri by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(selectedSourceTheme, activeTheme) {
        val sourceTheme = selectedSourceTheme ?: activeTheme
        themeName = when {
            editingTheme != null -> editingTheme.name
            cloningBuiltinTheme != null -> "${cloningBuiltinTheme.name}副本"
            else -> "我的主题"
        }
        // 从阅读器专用字段初始化，保证长按配色球看到的就是球内实际文字/背景。
        bgColor = sourceTheme?.readerBackground?.removePrefix("#") ?: "FF0A0A0F"
        textColor = sourceTheme?.readerTextColor?.removePrefix("#") ?: "FFEDEDEF"
        accentColor = sourceTheme?.accentColor?.removePrefix("#") ?: "FF818CF8"
        customCss = sourceTheme?.customCss.orEmpty()
        backgroundImageUri = sourceTheme?.backgroundImageUri
        // 编辑现有主题：尊重 entity 已保存的 isNightTheme，并视为「用户已选定」不再联动；
        // 新建主题：以当前 bgColor 的亮度做默认值，让浅色背景默认归到「白天」、深色归到
        // 「夜晚」。比之前盲目拷 active.isNightTheme=true 友好得多——active 是 moRealm
        // 时所有新建主题默认夜间，是用户报「白天对话框看不到自定义主题」的根因。
        if (selectedSourceTheme != null) {
            isNight = selectedSourceTheme.isNightTheme
            userOverrodeIsNight = true
        } else {
            isNight = "#$bgColor".toComposeColor().luminance() < 0.5f
            userOverrodeIsNight = false
        }
    }

    // bgColor 改了 → 没手动切过 isNight 时自动跟随。一旦用户手动切过 Switch，他/她
    // 知道自己在干嘛（例如想做一个浅色但归类为夜间的「莫兰迪夜读」主题），就不再覆盖。
    LaunchedEffect(bgColor) {
        if (!userOverrodeIsNight) {
            isNight = "#$bgColor".toComposeColor().luminance() < 0.5f
        }
    }

    val bgPalette = listOf(
        "FFFDFBF7", "FFF5F0E8", "FFE8F5E9", "FFE3F2FD", "FFFCE4EC", "FFFFF8E1",
        "FFFFFFFF", "FFF0F0F0", "FFE0E0E0",
        "FF0A0A0F", "FF1B2A1B", "FF0D1117", "FF1A1A2E", "FF000000", "FF121212",
    )
    val textPalette = listOf(
        "FF1A1A1A", "FF2D2D2D", "FF333333", "FF1B5E20", "FF0D47A1", "FF880E4F",
        "FFEDEDEF", "FFDCE8DC", "FFC9D1D9", "FFA0A0A0", "FFB0B0B0", "FFE0E0E0",
    )
    val accentPalette = listOf(
        "FFD97706", "FF4CAF50", "FF2196F3", "FFE91E63", "FF7C5CFC", "FF81C784",
        "FF818CF8", "FF58A6FF", "FFFF2D95", "FF6366F1", "FF555555", "FFFF5722",
    )
    val previewBgColor = "#$bgColor".toComposeColor()
    val previewTextColor = "#$textColor".toComposeColor()
    val previewAccentColor = "#$accentColor".toComposeColor()
    val isPreviewDark = previewBgColor.luminance() < 0.5f
    val editorCardColor = if (isPreviewDark) {
        Color.White.copy(alpha = 0.08f)
    } else {
        Color.Black.copy(alpha = 0.04f)
    }
    val editorDividerColor = previewTextColor.copy(alpha = 0.10f)
    val cssOverrides = remember(customCss) { CssParser.parse(customCss) }
    val previewFontSize = cssOverrides.fontSize?.sp ?: 24.sp
    val previewLineHeight = ((cssOverrides.lineSpacingExtra ?: 1.6f) * previewFontSize.value).sp
    val previewParagraphSpacing = (cssOverrides.paragraphSpacing ?: 8).dp
    val previewTextAlign = when (cssOverrides.textAlign) {
        "center" -> androidx.compose.ui.text.style.TextAlign.Center
        "right" -> androidx.compose.ui.text.style.TextAlign.End
        else -> androidx.compose.ui.text.style.TextAlign.Start
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when {
                            editingTheme != null -> "编辑主题"
                            cloningBuiltinTheme != null -> "基于${cloningBuiltinTheme.name}自定义"
                            else -> "自定义主题"
                        },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                actions = {
                    TextButton(onClick = {
                        val theme = ThemeEntity(
                            id = editingTheme?.id ?: "custom_${System.currentTimeMillis()}",
                            name = themeName,
                            author = "用户自定义",
                            isBuiltin = false,
                            isNightTheme = isNight,
                            primaryColor = "#$accentColor",
                            accentColor = "#$accentColor",
                            backgroundColor = "#$bgColor",
                            surfaceColor = "#$bgColor",
                            onBackgroundColor = "#$textColor",
                            bottomBackground = "#$bgColor",
                            readerBackground = "#$bgColor",
                            readerTextColor = "#$textColor",
                            backgroundImageUri = backgroundImageUri,
                            customCss = customCss,
                        )
                        themeViewModel.importCustomTheme(theme)
                        onBack()
                    }) {
                        Text("保存并应用", color = previewAccentColor,
                            fontWeight = FontWeight.Bold)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = previewBgColor,
                    titleContentColor = previewTextColor,
                    navigationIconContentColor = previewTextColor,
                    actionIconContentColor = previewAccentColor,
                ),
            )
        },
        containerColor = previewBgColor,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(previewBgColor)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            if (customThemes.isNotEmpty()) {
                Text("已保存主题", style = MaterialTheme.typography.titleSmall,
                    color = previewTextColor, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(customThemes, key = { it.id }) { theme ->
                        ThemeGridItem(
                            theme = theme,
                            isActive = selectedThemeId == theme.id,
                            onClick = { selectedThemeId = theme.id },
                            modifier = Modifier.width(88.dp),
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { selectedThemeId = null },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = previewAccentColor),
                ) {
                    Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("新建主题")
                }
                Spacer(Modifier.height(16.dp))
            }

            // Theme name
            OutlinedTextField(
                value = themeName,
                onValueChange = { themeName = it },
                label = { Text("主题名称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = previewTextColor,
                    unfocusedTextColor = previewTextColor,
                    focusedLabelColor = previewAccentColor,
                    unfocusedLabelColor = previewTextColor.copy(alpha = 0.65f),
                    focusedBorderColor = previewAccentColor,
                    unfocusedBorderColor = previewTextColor.copy(alpha = 0.45f),
                    cursorColor = previewAccentColor),
            )
            Spacer(Modifier.height(16.dp))

            // Night mode toggle
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.cardColors(
                    containerColor = editorCardColor),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (isNight) Icons.Default.DarkMode else Icons.Default.LightMode,
                            null, tint = previewAccentColor)
                        Spacer(Modifier.width(12.dp))
                        Text("暗色主题", style = MaterialTheme.typography.bodyLarge,
                            color = previewTextColor,
                            modifier = Modifier.weight(1f))
                        Switch(
                            checked = isNight,
                            onCheckedChange = {
                                isNight = it
                                userOverrodeIsNight = true
                            },
                            colors = SwitchDefaults.colors(checkedTrackColor = previewAccentColor),
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "决定「跟随系统」时此主题归到白天 / 夜晚分类。默认按背景亮度自动判断，可手动覆盖。",
                        style = MaterialTheme.typography.bodySmall,
                        color = previewTextColor.copy(alpha = 0.6f),
                    )
                }
            }
            Spacer(Modifier.height(16.dp))

            // Color pickers
            Text("颜色配置", style = MaterialTheme.typography.titleSmall,
                color = previewTextColor, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.cardColors(
                    containerColor = editorCardColor),
            ) {
                Column(Modifier.padding(16.dp)) {
                    ThemeColorPickRow("背景色", bgColor, editingColor == "bg",
                        { editingColor = if (editingColor == "bg") null else "bg" },
                        bgPalette, previewTextColor, previewAccentColor) { bgColor = it }
                    HorizontalDivider(Modifier.padding(vertical = 8.dp),
                        color = editorDividerColor)
                    ThemeColorPickRow("文字色", textColor, editingColor == "text",
                        { editingColor = if (editingColor == "text") null else "text" },
                        textPalette, previewTextColor, previewAccentColor) { textColor = it }
                    HorizontalDivider(Modifier.padding(vertical = 8.dp),
                        color = editorDividerColor)
                    ThemeColorPickRow("强调色", accentColor, editingColor == "accent",
                        { editingColor = if (editingColor == "accent") null else "accent" },
                        accentPalette, previewTextColor, previewAccentColor) { accentColor = it }
                }
            }
            Spacer(Modifier.height(24.dp))

            // ── 阅读背景图：与 readerTextColor / readerBackground 同属一份 ThemeEntity ──
            //
            // 阅读设置的配色球按主题整组切换三者；旧版按 ReaderStyle / day-night prefs
            // 单独保存的背景仅作升级兼容，用户选择配色球后即清除覆盖。
            //
            // SAF takePersistableUriPermission 让 URI 重启后仍可访问；
            // 失败（用户撤销权限/老 URI）静默吞，rememberAsyncImagePainter 会画灰底兜底。
            Text("阅读背景图", style = MaterialTheme.typography.titleSmall,
                color = previewTextColor, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            val ctxForBg = LocalContext.current
            val bgImageLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument()
            ) { uri ->
                uri?.let {
                    try {
                        ctxForBg.contentResolver.takePersistableUriPermission(
                            it, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    } catch (_: Exception) {}
                    backgroundImageUri = it.toString()
                }
            }
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.cardColors(containerColor = editorCardColor),
            ) {
                ThemeBgImageRow(
                    imageUri = backgroundImageUri.orEmpty(),
                    contentColor = previewTextColor,
                    accentColor = previewAccentColor,
                    onPick = { bgImageLauncher.launch(arrayOf("image/*")) },
                    onClear = { backgroundImageUri = null },
                )
            }
            Spacer(Modifier.height(24.dp))

            // Theme CSS
            Text("阅读 CSS", style = MaterialTheme.typography.titleSmall,
                color = previewTextColor, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.cardColors(
                    containerColor = editorCardColor),
            ) {
                CssEditorSection(
                    css = customCss,
                    onCssChange = { customCss = it },
                    liveUpdate = true,
                    contentColor = previewTextColor,
                    accentColor = previewAccentColor,
                    containerColor = previewTextColor.copy(alpha = if (isPreviewDark) 0.08f else 0.06f),
                    modifier = Modifier.padding(16.dp),
                )
            }
            Spacer(Modifier.height(24.dp))

            // Live preview
            Text("实时预览", style = MaterialTheme.typography.titleSmall,
                color = previewTextColor, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Card(
                modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp),
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.cardColors(containerColor = previewBgColor),
            ) {
                Column(Modifier.fillMaxWidth().padding(20.dp)) {
                    Text("预览效果", style = MaterialTheme.typography.labelSmall,
                        color = previewAccentColor)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        listOf(
                            "${cssOverrides.paragraphIndent.orEmpty()}天地玄黄，宇宙洪荒。",
                            "${cssOverrides.paragraphIndent.orEmpty()}日月盈昃，辰宿列张。",
                            "${cssOverrides.paragraphIndent.orEmpty()}寒来暑往，秋收冬藏。",
                        ).joinToString("\n"),
                        style = MaterialTheme.typography.bodyLarge,
                        fontSize = previewFontSize,
                        lineHeight = previewLineHeight,
                        color = previewTextColor,
                        textAlign = previewTextAlign,
                        modifier = Modifier.fillMaxWidth(),
                        softWrap = true,
                    )
                    if (previewParagraphSpacing > 0.dp) Spacer(Modifier.height(previewParagraphSpacing))
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

/** A row with color swatch + hex input + expandable palette grid */
@Composable
private fun ThemeColorPickRow(
    label: String,
    currentHex: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    palette: List<String>,
    contentColor: Color,
    accentColor: Color,
    onColorPick: (String) -> Unit,
) {
    var hexInput by remember(currentHex) { mutableStateOf(currentHex.takeLast(6)) }

    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle)
                .padding(vertical = 8.dp),
        ) {
            Box(Modifier.size(28.dp).clip(CircleShape)
                .background("#$currentHex".toComposeColor()))
            Spacer(Modifier.width(12.dp))
            Text(label, style = MaterialTheme.typography.bodyLarge,
                color = contentColor, modifier = Modifier.weight(1f))
            Text("#${currentHex.takeLast(6)}", style = MaterialTheme.typography.bodySmall,
                color = contentColor.copy(alpha = 0.55f))
            Spacer(Modifier.width(4.dp))
            Icon(
                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                null, modifier = Modifier.size(20.dp),
                tint = contentColor.copy(alpha = 0.55f))
        }
        if (expanded) {
            Spacer(Modifier.height(8.dp))
            // Hex input
            OutlinedTextField(
                value = hexInput,
                onValueChange = { v ->
                    val clean = v.replace("#", "").take(6)
                    hexInput = clean
                    if (clean.length == 6 && clean.all { it in "0123456789abcdefABCDEF" }) {
                        onColorPick("FF$clean".uppercase())
                    }
                },
                label = { Text("Hex 色值") },
                prefix = { Text("#") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = contentColor,
                    unfocusedTextColor = contentColor,
                    focusedLabelColor = accentColor,
                    unfocusedLabelColor = contentColor.copy(alpha = 0.65f),
                    focusedBorderColor = accentColor,
                    unfocusedBorderColor = contentColor.copy(alpha = 0.35f),
                    cursorColor = accentColor),
            )
            // Color grid, 6 per row
            val rows = palette.chunked(6)
            for (row in rows) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { hex ->
                        val selected = currentHex == hex
                        Box(
                            Modifier.weight(1f).aspectRatio(1f)
                                .clip(MaterialTheme.shapes.small)
                                .background("#$hex".toComposeColor())
                                .clickable { onColorPick(hex); hexInput = hex.takeLast(6) },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (selected) {
                                Icon(Icons.Default.Check, null,
                                    tint = if (hex.takeLast(6).take(2).toIntOrNull(16) ?: 128 > 128)
                                        Color.Black.copy(alpha = 0.7f) else Color.White.copy(alpha = 0.7f),
                                    modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

/**
 * 主题编辑器内的背景图选择行 — 缩略图 + 选择/清除按钮。
 *
 * 与 ReadingSettingsScreen 的 BgImageRow 视觉一致，但上下文色彩跟 themeEditor 的
 * preview 颜色走（contentColor / accentColor）—— 编辑器画的是「主题预览」，控件本身
 * 也得在该主题色下可读，不然换深色主题时 outline 看不清。
 */
@Composable
private fun ThemeBgImageRow(
    imageUri: String,
    contentColor: Color,
    accentColor: Color,
    onPick: () -> Unit,
    onClear: () -> Unit,
) {
    val hasImage = imageUri.isNotEmpty()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onPick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (hasImage) {
            Image(
                painter = rememberAsyncImagePainter(
                    ImageRequest.Builder(LocalContext.current)
                        .data(imageUri)
                        .size(120, 160)
                        .crossfade(true)
                        .build()
                ),
                contentDescription = "首页背景图",
                modifier = Modifier
                    .size(48.dp, 64.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .border(1.dp, contentColor.copy(alpha = 0.2f), RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Crop,
            )
        } else {
            Box(
                modifier = Modifier
                    .size(48.dp, 64.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(contentColor.copy(alpha = 0.08f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.Image, null,
                    modifier = Modifier.size(20.dp),
                    tint = contentColor.copy(alpha = 0.35f))
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(if (hasImage) "已设置" else "未设置",
                style = MaterialTheme.typography.bodyLarge,
                color = contentColor)
            Text(
                if (hasImage) "点击更换图片" else "点击选择图片",
                style = MaterialTheme.typography.bodySmall,
                color = contentColor.copy(alpha = 0.55f),
            )
        }
        if (hasImage) {
            TextButton(
                onClick = onClear,
                colors = ButtonDefaults.textButtonColors(contentColor = accentColor),
            ) { Text("清除", style = MaterialTheme.typography.labelSmall) }
        }
    }
}
