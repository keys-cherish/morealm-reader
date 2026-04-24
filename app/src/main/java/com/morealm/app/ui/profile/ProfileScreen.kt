package com.morealm.app.ui.profile

import androidx.activity.compose.rememberLauncherForActivityResult
import com.morealm.app.presentation.profile.ProfileViewModel
import com.morealm.app.presentation.profile.AnnualReport
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.combinedClickable
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import com.morealm.app.domain.entity.BuiltinThemes
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
    onNavigateCacheBook: () -> Unit = {},
    onNavigateThemeEditor: () -> Unit = {},
) {
    val moColors = LocalMoRealmColors.current
    val activeTheme by themeViewModel.activeTheme.collectAsStateWithLifecycle()
    val allThemes by themeViewModel.allThemes.collectAsStateWithLifecycle()
    val totalBooks by profileViewModel.totalBooks.collectAsStateWithLifecycle()
    val totalReadMs by profileViewModel.totalReadMs.collectAsStateWithLifecycle()
    val todayReadMs by profileViewModel.todayReadMs.collectAsStateWithLifecycle()
    val recentDays by profileViewModel.recentDays.collectAsStateWithLifecycle()
    val annualReport by profileViewModel.annualReport.collectAsStateWithLifecycle()
    var showDeleteThemeConfirm by remember { mutableStateOf<String?>(null) }
    var showAnnualReport by remember { mutableStateOf(false) }

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
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)),
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
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    TextButton(onClick = {
                        profileViewModel.loadAnnualReport()
                        showAnnualReport = true
                    }) {
                        Text("查看年度报告 →", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // Theme grid (3x2 like HTML prototype)
        SectionTitle("主题切换")
        Card(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
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
                    Text("自定义主题（长按删除）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                    Spacer(Modifier.height(8.dp))
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(customThemes, key = { it.id }) { theme ->
                            ThemeGridItem(theme = theme, isActive = activeTheme?.id == theme.id,
                                onClick = { themeViewModel.switchTheme(theme.id) },
                                onLongClick = { showDeleteThemeConfirm = theme.id },
                                modifier = Modifier.width(80.dp))
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = onNavigateThemeEditor,
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
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
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Icon(Icons.Default.Upload, null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("导出主题", style = MaterialTheme.typography.labelSmall)
                    }
                    OutlinedButton(
                        onClick = { themeImportLauncher.launch(arrayOf("application/json", "application/octet-stream", "text/plain")) },
                        modifier = Modifier.weight(1f),
                        shape = MaterialTheme.shapes.medium,
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
        SettingsCard(Icons.Default.CloudDownload, "离线缓存",
            "批量下载章节，支持离线阅读", onClick = onNavigateCacheBook)
        SettingsCard(Icons.Default.ImportExport, "Legado 一键搬家",
            "导入 Legado 备份，书源/书架/进度全部迁移", onClick = {})

        // Widget preview
        SettingsCard(Icons.Default.Widgets, "桌面小组件", "预览效果", onClick = {}) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("📖 继续阅读", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { 0.62f },
                        modifier = Modifier.fillMaxWidth().height(4.dp).clip(MaterialTheme.shapes.extraSmall),
                        color = MaterialTheme.colorScheme.primary, trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("今日已读 ${formatDuration(todayReadMs)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
                        Text("继续 →", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        SettingsSection("高级") {
            SettingsItem(Icons.Default.FindReplace, "净化替换规则",
                subtitle = "去广告、净化正文内容",
                onClick = onNavigateReplaceRules)
        }
        SettingsSection("关于") {
            SettingsItem(Icons.Default.Info, "关于墨境", onClick = onNavigateAbout)
            SettingsItem(Icons.Default.BugReport, "应用日志",
                subtitle = "查看运行日志和错误信息", onClick = onNavigateAppLog)
        }
        Spacer(Modifier.height(32.dp))
    }

    // Delete custom theme confirmation
    showDeleteThemeConfirm?.let { themeId ->
        val themeName = allThemes.find { it.id == themeId }?.name ?: "主题"
        AlertDialog(
            onDismissRequest = { showDeleteThemeConfirm = null },
            title = { Text("删除主题") },
            text = { Text("确定删除「$themeName」？") },
            confirmButton = {
                TextButton(onClick = {
                    themeViewModel.deleteCustomTheme(themeId)
                    showDeleteThemeConfirm = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteThemeConfirm = null }) { Text("取消") }
            },
        )
    }

    // Annual report dialog
    if (showAnnualReport) {
        AnnualReportDialog(
            report = annualReport,
            accentColor = MaterialTheme.colorScheme.primary,
            onDismiss = { showAnnualReport = false },
        )
    }
}

@Composable
private fun StatItem(value: String, label: String) {
    val moColors = LocalMoRealmColors.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.primary)
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ThemeGridItem(
    theme: ThemeEntity, isActive: Boolean, onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null, modifier: Modifier = Modifier,
) {
    val bgColor = theme.backgroundColor.toComposeColor()
    val accentColor = theme.accentColor.toComposeColor()
    Card(
        modifier = modifier, shape = MaterialTheme.shapes.medium,
        border = BorderStroke(
            width = if (isActive) 2.dp else 1.dp,
            color = if (isActive) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outlineVariant,
        ),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(
            Modifier
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.size(28.dp).clip(CircleShape).background(bgColor), contentAlignment = Alignment.Center) {
                Box(Modifier.size(12.dp).clip(CircleShape).background(accentColor))
            }
            Spacer(Modifier.height(6.dp))
            Text(theme.name, style = MaterialTheme.typography.labelSmall,
                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
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
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
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
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
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


/**
 * Annual reading report dialog — ported from HTML prototype.
 * All data is dynamic from ReadStats + Book tables.
 */
@Composable
private fun AnnualReportDialog(
    report: AnnualReport?,
    accentColor: Color,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
        title = null,
        text = {
            if (report == null) {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(32.dp))
                }
            } else {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    // Year label
                    Text(
                        "${report.year} 年度阅读报告",
                        style = MaterialTheme.typography.labelSmall,
                        color = accentColor,
                        letterSpacing = 2.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    // Big number
                    Text(
                        "${report.totalBooks} 本",
                        fontSize = 36.sp,
                        fontWeight = FontWeight.Black,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                    )
                    // Summary line
                    val wordText = if (report.totalWordsWan > 0) "共 ${report.totalWordsWan} 万字" else ""
                    val hourText = if (report.totalDurationHours > 0) "${report.totalDurationHours} 小时" else ""
                    val summary = listOf(wordText, hourText).filter { it.isNotBlank() }.joinToString(" · ")
                    if (summary.isNotBlank()) {
                        Text(summary, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                    }

                    Spacer(Modifier.height(16.dp))

                    // Habit row
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            Modifier.padding(12.dp).fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceAround,
                        ) {
                            HabitItem("\uD83C\uDF19", "最常 ${report.peakHour}")
                            HabitItem("⏱", "最长 ${report.longestSessionMin}m")
                            if (report.favoriteBook.isNotBlank()) {
                                HabitItem("\uD83C\uDFC6", report.favoriteBook.take(6))
                            }
                        }
                    }

                    // Tags
                    if (report.tags.isNotEmpty()) {
                        Spacer(Modifier.height(14.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            report.tags.forEach { tag ->
                                Surface(
                                    shape = MaterialTheme.shapes.small,
                                    color = accentColor.copy(alpha = 0.1f),
                                    modifier = Modifier.padding(horizontal = 3.dp),
                                ) {
                                    Text(
                                        tag,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = accentColor,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                    Text(
                        "墨境 MoRealm · 年度报告",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f),
                        letterSpacing = 1.sp,
                    )
                }
            }
        },
    )
}

@Composable
private fun HabitItem(emoji: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(emoji, fontSize = 16.sp)
        Spacer(Modifier.height(2.dp))
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
            fontSize = 9.sp)
    }
}
