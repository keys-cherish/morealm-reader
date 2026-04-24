package com.morealm.app.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.morealm.app.domain.entity.ThemeEntity
import com.morealm.app.presentation.theme.ThemeViewModel
import com.morealm.app.ui.theme.toComposeColor

/**
 * Full-screen theme editor — replaces the old cramped AlertDialog.
 * Provides ample space for color pickers, CSS editor, and live preview.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemeEditorScreen(
    themeViewModel: ThemeViewModel,
    onBack: () -> Unit,
    editThemeId: String? = null,
) {
    val allThemes by themeViewModel.allThemes.collectAsStateWithLifecycle()
    val existingTheme = remember(editThemeId, allThemes) {
        editThemeId?.let { id -> allThemes.find { it.id == id } }
    }

    var themeName by remember { mutableStateOf(existingTheme?.name ?: "我的主题") }
    var isNight by remember { mutableStateOf(existingTheme?.isNightTheme ?: false) }
    var bgColor by remember { mutableStateOf(existingTheme?.backgroundColor?.removePrefix("#") ?: "FFFDFBF7") }
    var textColor by remember { mutableStateOf(existingTheme?.onBackgroundColor?.removePrefix("#") ?: "FF2D2D2D") }
    var accentColor by remember { mutableStateOf(existingTheme?.accentColor?.removePrefix("#") ?: "FFD97706") }
    var editingColor by remember { mutableStateOf<String?>(null) }

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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (existingTheme != null) "编辑主题" else "自定义主题") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                actions = {
                    TextButton(onClick = {
                        val theme = ThemeEntity(
                            id = existingTheme?.id ?: "custom_${System.currentTimeMillis()}",
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
                        )
                        themeViewModel.importCustomTheme(theme)
                        onBack()
                    }) {
                        Text("保存并应用", color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            // ── Theme name ──
            OutlinedTextField(
                value = themeName,
                onValueChange = { themeName = it },
                label = { Text("主题名称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    cursorColor = MaterialTheme.colorScheme.primary),
            )
            Spacer(Modifier.height(16.dp))

            // ── Night mode toggle ──
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(if (isNight) Icons.Default.DarkMode else Icons.Default.LightMode,
                        null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Text("暗色主题", style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f))
                    Switch(checked = isNight, onCheckedChange = { isNight = it },
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = MaterialTheme.colorScheme.primary))
                }
            }
            Spacer(Modifier.height(16.dp))

            // ── Color pickers ──
            Text("颜色配置", style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            ) {
                Column(Modifier.padding(16.dp)) {
                    ThemeColorPickRow("背景色", bgColor, editingColor == "bg",
                        { editingColor = if (editingColor == "bg") null else "bg" },
                        bgPalette) { bgColor = it }
                    HorizontalDivider(Modifier.padding(vertical = 8.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                    ThemeColorPickRow("文字色", textColor, editingColor == "text",
                        { editingColor = if (editingColor == "text") null else "text" },
                        textPalette) { textColor = it }
                    HorizontalDivider(Modifier.padding(vertical = 8.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                    ThemeColorPickRow("强调色", accentColor, editingColor == "accent",
                        { editingColor = if (editingColor == "accent") null else "accent" },
                        accentPalette) { accentColor = it }
                }
            }
            Spacer(Modifier.height(24.dp))

            // ── Live preview ──
            Text("实时预览", style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Card(
                modifier = Modifier.fillMaxWidth().height(200.dp),
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.cardColors(
                    containerColor = "#$bgColor".toComposeColor()),
            ) {
                Column(Modifier.padding(20.dp)) {
                    Text("预览效果", style = MaterialTheme.typography.labelSmall,
                        color = "#$accentColor".toComposeColor())
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "天地玄黄，宇宙洪荒。\n日月盈昃，辰宿列张。\n寒来暑往，秋收冬藏。",
                        style = MaterialTheme.typography.bodyLarge,
                        lineHeight = 28.sp,
                        color = "#$textColor".toComposeColor(),
                    )
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
                color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            Text("#${currentHex.takeLast(6)}", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
            Spacer(Modifier.width(4.dp))
            Icon(
                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                null, modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
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
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    cursorColor = MaterialTheme.colorScheme.primary),
            )
            // Color grid — 6 per row
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
