package com.morealm.app.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
    val existingTheme = remember(editThemeId, allThemes) {
        editThemeId?.let { id -> allThemes.find { it.id == id } }
    }

    val customThemes = remember(allThemes) { allThemes.filter { !it.isBuiltin } }
    var selectedThemeId by remember(editThemeId) { mutableStateOf(editThemeId) }
    val editingTheme = remember(selectedThemeId, allThemes) {
        selectedThemeId?.let { id -> allThemes.find { it.id == id && !it.isBuiltin } }
    } ?: existingTheme

    var themeName by remember { mutableStateOf("我的主题") }
    var isNight by remember { mutableStateOf(false) }
    var bgColor by remember { mutableStateOf("FF0A0A0F") }
    var textColor by remember { mutableStateOf("FFEDEDEF") }
    var accentColor by remember { mutableStateOf("FF818CF8") }
    var customCss by remember { mutableStateOf("") }
    var editingColor by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(editingTheme, activeTheme) {
        val sourceTheme = editingTheme ?: activeTheme
        themeName = editingTheme?.name ?: "我的主题"
        isNight = sourceTheme?.isNightTheme ?: true
        bgColor = sourceTheme?.backgroundColor?.removePrefix("#") ?: "FF0A0A0F"
        textColor = sourceTheme?.onBackgroundColor?.removePrefix("#") ?: "FFEDEDEF"
        accentColor = sourceTheme?.accentColor?.removePrefix("#") ?: "FF818CF8"
        customCss = editingTheme?.customCss ?: sourceTheme?.customCss.orEmpty()
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
                title = { Text(if (editingTheme != null) "编辑主题" else "自定义主题") },
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
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(if (isNight) Icons.Default.DarkMode else Icons.Default.LightMode,
                        null, tint = previewAccentColor)
                    Spacer(Modifier.width(12.dp))
                    Text("暗色主题", style = MaterialTheme.typography.bodyLarge,
                        color = previewTextColor,
                        modifier = Modifier.weight(1f))
                    Switch(checked = isNight, onCheckedChange = { isNight = it },
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = previewAccentColor))
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
