package com.morealm.app.ui.profile

import androidx.activity.compose.rememberLauncherForActivityResult
import com.morealm.app.presentation.profile.ProfileViewModel
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.morealm.app.domain.entity.ThemeEntity
import com.morealm.app.presentation.theme.ThemeViewModel
import com.morealm.app.ui.theme.BuiltinThemes
import com.morealm.app.ui.theme.LocalMoRealmColors
import com.morealm.app.ui.theme.toComposeColor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    themeViewModel: ThemeViewModel = hiltViewModel(),
    profileViewModel: ProfileViewModel = hiltViewModel(),
    onNavigateWebDav: () -> Unit = {},
    onNavigateAbout: () -> Unit = {},
    onNavigateSourceManage: () -> Unit = {},
    onNavigateReadingSettings: () -> Unit = {},
    onNavigateReplaceRules: () -> Unit = {},
    onNavigateAppLog: () -> Unit = {},
) {
    val moColors = LocalMoRealmColors.current
    val activeTheme by themeViewModel.activeTheme.collectAsState()
    val allThemes by themeViewModel.allThemes.collectAsState()
    val totalBooks by profileViewModel.totalBooks.collectAsState()
    val totalReadMs by profileViewModel.totalReadMs.collectAsState()
    val todayReadMs by profileViewModel.todayReadMs.collectAsState()
    val recentDays by profileViewModel.recentDays.collectAsState()
    var showCustomThemeEditor by remember { mutableStateOf(false) }

    val backupExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        uri?.let { profileViewModel.exportBackup(it) }
    }

    val backupImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { profileViewModel.importBackup(it) }
    }

    val themeExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let { themeViewModel.exportTheme(it) }
    }

    val themeImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { themeViewModel.importThemeFromUri(it) }
    }

    Column(
        modifier = Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
    ) {
        TopAppBar(
            title = { Text("我的", fontWeight = FontWeight.Bold) },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        )

        // Reading stats card (real data)
        Card(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = moColors.accent.copy(alpha = 0.08f)),
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text("阅读统计", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                    StatItem(value = "$totalBooks", label = "本书")
                    StatItem(value = formatDuration(totalReadMs), label = "总时长")
                    StatItem(value = "$recentDays", label = "连续天数")
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    Text("今日已读 ${formatDuration(todayReadMs)}",
                        style = MaterialTheme.typography.labelSmall, color = moColors.accent)
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // Theme grid (3x2 like HTML prototype)
        SectionTitle("主题切换")
        Card(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = moColors.surfaceGlass),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("点击切换主题，实时预览效果",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                Spacer(Modifier.height(12.dp))
                val builtinThemes = remember { BuiltinThemes.all() }
                val customThemes = allThemes.filter { !it.isBuiltin }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    builtinThemes.take(3).forEach { theme ->
                        ThemeGridItem(theme = theme, isActive = activeTheme?.id == theme.id,
                            onClick = { themeViewModel.switchTheme(theme.id) },
                            modifier = Modifier.weight(1f))
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    builtinThemes.drop(3).forEach { theme ->
                        ThemeGridItem(theme = theme, isActive = activeTheme?.id == theme.id,
                            onClick = { themeViewModel.switchTheme(theme.id) },
                            modifier = Modifier.weight(1f))
                    }
                }
                // Custom themes section
                if (customThemes.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text("自定义主题",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                    Spacer(Modifier.height(8.dp))
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(customThemes, key = { it.id }) { theme ->
                            ThemeGridItem(theme = theme, isActive = activeTheme?.id == theme.id,
                                onClick = { themeViewModel.switchTheme(theme.id) },
                                modifier = Modifier.width(80.dp))
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = { showCustomThemeEditor = true },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Icon(Icons.Default.Palette, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("自定义主题")
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            val name = activeTheme?.name ?: "theme"
                            themeExportLauncher.launch("${name}.json")
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Icon(Icons.Default.Upload, null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("导出主题", style = MaterialTheme.typography.labelSmall)
                    }
                    OutlinedButton(
                        onClick = { themeImportLauncher.launch(arrayOf("application/json", "application/octet-stream", "text/plain")) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Icon(Icons.Default.Download, null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("导入主题", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        @Suppress("DEPRECATION")
        SettingsCard(Icons.Default.MenuBook, "阅读设置",
            "翻页动画、音量键翻页、屏幕常亮、界面显示", onClick = onNavigateReadingSettings)
        SettingsCard(Icons.Default.FindReplace, "正文替换净化",
            "去广告、净化正文内容，支持正则替换", onClick = onNavigateReplaceRules)
        SettingsSection("备份与恢复") {
            SettingsItem(Icons.Default.Upload, "导出备份",
                subtitle = "书架/进度/书源/书签导出为 ZIP",
                onClick = {
                    val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmm", java.util.Locale.getDefault())
                        .format(java.util.Date())
                    backupExportLauncher.launch("MoRealm_backup_$ts.zip")
                })
            SettingsItem(Icons.Default.Download, "导入备份",
                subtitle = "从 ZIP 文件恢复数据",
                onClick = { backupImportLauncher.launch(arrayOf("application/zip", "application/octet-stream")) })
            SettingsItem(Icons.Default.Cloud, "WebDAV 同步",
                subtitle = "进度 / 书架 / 书源 / 主题 一键全同步",
                onClick = onNavigateWebDav)
        }

        SettingsCard(Icons.Default.Extension, "书源管理",
            "导入、启用、删除书源，支持 URL 订阅和 JSON 导入", onClick = onNavigateSourceManage)
        SettingsCard(Icons.Default.ImportExport, "Legado 一键搬家",
            "导入 Legado 备份，书源/书架/进度全部迁移", onClick = {})

        // Widget preview
        SettingsCard(Icons.Default.Widgets, "桌面小组件", "预览效果", onClick = {}) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("📖 继续阅读", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { 0.62f },
                        modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                        color = moColors.accent, trackColor = moColors.accent.copy(alpha = 0.15f))
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("今日已读 ${formatDuration(todayReadMs)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
                        Text("继续 →", style = MaterialTheme.typography.labelSmall,
                            color = moColors.accent, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        SettingsSection("高级") {
            SettingsItem(Icons.Default.Extension, "内容解析规则", subtitle = "管理自定义解析配置")
            @Suppress("DEPRECATION")
            SettingsItem(Icons.Default.Rule, "TXT 目录规则")
            SettingsItem(Icons.Default.FindReplace, "净化替换规则")
        }
        SettingsSection("关于") {
            SettingsItem(Icons.Default.Info, "关于墨境", onClick = onNavigateAbout)
            SettingsItem(Icons.Default.BugReport, "应用日志",
                subtitle = "查看运行日志和错误信息", onClick = onNavigateAppLog)
        }
        Spacer(Modifier.height(32.dp))
    }

    // Custom theme editor dialog
    if (showCustomThemeEditor) {
        CustomThemeEditorDialog(
            onDismiss = { showCustomThemeEditor = false },
            onSave = { theme ->
                themeViewModel.importCustomTheme(theme)
                showCustomThemeEditor = false
            },
        )
    }
}

@Composable
private fun StatItem(value: String, label: String) {
    val moColors = LocalMoRealmColors.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = moColors.accent)
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
    }
}

private fun formatDuration(ms: Long): String {
    val minutes = ms / 60_000
    return when {
        minutes < 1 -> "0分钟"
        minutes < 60 -> "${minutes}分钟"
        else -> "${minutes / 60}小时${minutes % 60}分"
    }
}

@Composable
fun ThemeGridItem(
    theme: ThemeEntity, isActive: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier,
) {
    val bgColor = theme.backgroundColor.toComposeColor()
    val accentColor = theme.accentColor.toComposeColor()
    val moColors = LocalMoRealmColors.current
    Card(
        onClick = onClick, modifier = modifier, shape = RoundedCornerShape(12.dp),
        border = if (isActive) CardDefaults.outlinedCardBorder().copy(
            brush = androidx.compose.ui.graphics.SolidColor(moColors.accent)) else null,
        colors = CardDefaults.cardColors(containerColor = moColors.surfaceGlass),
    ) {
        Column(Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(28.dp).clip(CircleShape).background(bgColor), contentAlignment = Alignment.Center) {
                Box(Modifier.size(12.dp).clip(CircleShape).background(accentColor))
            }
            Spacer(Modifier.height(6.dp))
            Text(theme.name, style = MaterialTheme.typography.labelSmall,
                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                color = if (isActive) moColors.accent else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        }
    }
}

@Composable
fun SettingsCard(
    icon: ImageVector, title: String, desc: String, onClick: () -> Unit,
    extra: @Composable ColumnScope.() -> Unit = {},
) {
    val moColors = LocalMoRealmColors.current
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = moColors.surfaceGlass),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = moColors.accent, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(4.dp))
            Text(desc, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
            extra()
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
}

@Composable
fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    SectionTitle(title)
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = LocalMoRealmColors.current.surfaceGlass),
    ) { Column(Modifier.padding(vertical = 4.dp)) { content() } }
    Spacer(Modifier.height(16.dp))
}

@Composable
fun SettingsItem(icon: ImageVector, title: String, subtitle: String? = null, onClick: () -> Unit = {}) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = if (subtitle != null) 12.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = LocalMoRealmColors.current.accent, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
        }
        Icon(Icons.Default.ChevronRight, null,
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f), modifier = Modifier.size(20.dp))
    }
}

/** Custom theme editor — color pickers for bg/text/accent */
@Composable
private fun CustomThemeEditorDialog(
    onDismiss: () -> Unit,
    onSave: (ThemeEntity) -> Unit,
) {
    val moColors = LocalMoRealmColors.current
    var themeName by remember { mutableStateOf("我的主题") }
    var isNight by remember { mutableStateOf(false) }
    var bgColor by remember { mutableStateOf("FFFDFBF7") }
    var textColor by remember { mutableStateOf("FF2D2D2D") }
    var accentColor by remember { mutableStateOf("FFD97706") }
    // Which color is being edited: null / "bg" / "text" / "accent"
    var editingColor by remember { mutableStateOf<String?>(null) }

    // Common colors for quick pick
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

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("自定义主题") },
        text = {
            Column {
                OutlinedTextField(
                    value = themeName,
                    onValueChange = { themeName = it },
                    label = { Text("主题名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = moColors.accent, cursorColor = moColors.accent),
                )
                Spacer(Modifier.height(12.dp))

                // Night mode toggle
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("暗色主题", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.weight(1f))
                    Switch(checked = isNight, onCheckedChange = { isNight = it },
                        colors = SwitchDefaults.colors(checkedTrackColor = moColors.accent))
                }
                Spacer(Modifier.height(8.dp))

                // Color buttons — tap to expand color grid
                ColorPickRow("背景色", bgColor, editingColor == "bg",
                    { editingColor = if (editingColor == "bg") null else "bg" },
                    bgPalette) { bgColor = it }
                ColorPickRow("文字色", textColor, editingColor == "text",
                    { editingColor = if (editingColor == "text") null else "text" },
                    textPalette) { textColor = it }
                ColorPickRow("强调色", accentColor, editingColor == "accent",
                    { editingColor = if (editingColor == "accent") null else "accent" },
                    accentPalette) { accentColor = it }

                Spacer(Modifier.height(12.dp))
                // Live preview
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = "#$bgColor".toComposeColor()),
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text("预览效果", style = MaterialTheme.typography.labelSmall,
                            color = "#$accentColor".toComposeColor())
                        Spacer(Modifier.height(6.dp))
                        Text("天地玄黄，宇宙洪荒。\n日月盈昃，辰宿列张。",
                            style = MaterialTheme.typography.bodyMedium,
                            lineHeight = 24.sp,
                            color = "#$textColor".toComposeColor())
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val theme = ThemeEntity(
                    id = "custom_${System.currentTimeMillis()}",
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
                onSave(theme)
            }) { Text("保存并应用", color = moColors.accent) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/** A row with color swatch + hex input + expandable palette grid */
@Composable
private fun ColorPickRow(
    label: String,
    currentHex: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    palette: List<String>,
    onColorPick: (String) -> Unit,
) {
    val moColors = LocalMoRealmColors.current
    var hexInput by remember(currentHex) { mutableStateOf(currentHex.takeLast(6)) }

    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle)
                .padding(vertical = 6.dp),
        ) {
            Box(Modifier.size(24.dp).clip(CircleShape)
                .background("#$currentHex".toComposeColor())
                .clickable(onClick = onToggle))
            Spacer(Modifier.width(10.dp))
            Text(label, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            Text("#${currentHex.takeLast(6)}", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
            Icon(
                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                null, modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
        }
        if (expanded) {
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
                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = moColors.accent, cursorColor = moColors.accent),
            )
            // Color grid
            val rows = palette.chunked(6)
            for (row in rows) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    row.forEach { hex ->
                        val selected = currentHex == hex
                        Box(
                            Modifier.weight(1f).aspectRatio(1f)
                                .clip(RoundedCornerShape(6.dp))
                                .background("#$hex".toComposeColor())
                                .then(if (selected) Modifier.padding(2.dp) else Modifier)
                                .clickable { onColorPick(hex); hexInput = hex.takeLast(6) },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (selected) {
                                Icon(Icons.Default.Check, null,
                                    tint = if (hex.takeLast(6).take(2).toIntOrNull(16) ?: 128 > 128)
                                        Color.Black.copy(alpha = 0.7f) else Color.White.copy(alpha = 0.7f),
                                    modifier = Modifier.size(14.dp))
                            }
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}
