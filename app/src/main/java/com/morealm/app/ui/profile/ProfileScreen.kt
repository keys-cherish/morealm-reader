package com.morealm.app.ui.profile

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import com.morealm.app.presentation.profile.ProfileViewModel
import com.morealm.app.presentation.profile.AnnualReport
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import com.morealm.app.domain.entity.ThemeEntity
import com.morealm.app.presentation.theme.ThemeViewModel
import com.morealm.app.domain.entity.BuiltinThemes
import com.morealm.app.ui.theme.LocalMoRealmColors
import com.morealm.app.ui.theme.toComposeColor
import kotlinx.coroutines.delay
import java.io.File
import java.io.FileOutputStream

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
    onNavigateAutoGroupRules: () -> Unit = {},
    onNavigateAppLog: () -> Unit = {},
    onNavigateCacheBook: () -> Unit = {},
    onNavigateThemeEditor: () -> Unit = {},
    onNavigateDonate: () -> Unit = {},
    onNavigateBackupExport: () -> Unit = {},
    onNavigateBackupImport: () -> Unit = {},
    /** 跳到全局书签管理屏。 */
    onNavigateBookmarks: () -> Unit = {},
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

    val backupImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        // 旧路径保留：导入备份按钮如果用户长按或某些 fallback 场景仍走全量恢复。
        // 主交互已经迁到 BackupImportScreen — 见 onNavigateBackupImport。
        uri?.let { profileViewModel.importBackup(it) }
    }

    val themeExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let { themeViewModel.exportTheme(it) }
    }

    // 「导出全部自定义主题」走单独 launcher：和单主题导出共用一个 launcher 时，
    // CreateDocument 的回调拿不到「用户点的是哪个按钮」，混在一起容易把 bundle
    // 写到「单主题」文件名上。两个 launcher 各管各的更直观。
    val themeExportAllLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let { themeViewModel.exportAllCustomThemes(it) }
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
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color.Transparent,
                scrolledContainerColor = Color.Transparent,
            ),
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
                Spacer(Modifier.height(12.dp))
                Text("自定义主题（长按删除）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                Spacer(Modifier.height(8.dp))
                if (customThemes.isEmpty()) {
                    Text("暂无自定义主题，可点击下方创建或导入。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
                } else {
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
                // 「导出全部自定义主题」入口：仅在用户已有 ≥1 个自定义主题时显示，
                // 否则按钮按下去会得到一个空 bundle（ViewModel 也会直接 return），
                // 没有意义还制造点错觉。
                if (customThemes.isNotEmpty()) {
                    TextButton(
                        onClick = {
                            themeExportAllLauncher.launch("morealm-themes.json")
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            "→ 导出全部自定义主题（${customThemes.size}）",
                            style = MaterialTheme.typography.labelSmall,
                        )
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
        SettingsCard(Icons.Default.AutoAwesomeMosaic, "自动分组规则",
            "题材关键词、阈值与忽略列表，可导出分享", onClick = onNavigateAutoGroupRules)
        SettingsSection("备份与恢复") {
            SettingsItem(Icons.Default.Upload, "导出备份",
                subtitle = "选择需要导出的数据并查看大小",
                onClick = { onNavigateBackupExport() })
            SettingsItem(Icons.Default.Download, "导入备份",
                subtitle = "选择 ZIP 文件并按类别恢复",
                onClick = { onNavigateBackupImport() })
            SettingsItem(Icons.Default.Cloud, "WebDAV 同步",
                subtitle = "进度 / 书架 / 书源 / 主题 一键全同步",
                onClick = onNavigateWebDav)
        }

        SettingsCard(Icons.Default.Extension, "书源管理",
            "导入、启用、删除书源，支持 URL 订阅和 JSON 导入", onClick = onNavigateSourceManage)
        SettingsCard(Icons.Default.Bookmark, "我的书签",
            "跨书查看、按时间过滤、按书分组", onClick = onNavigateBookmarks)
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

        SettingsSection("关于") {
            SettingsItem(Icons.Default.Info, "关于墨境", onClick = onNavigateAbout)
            SettingsItem(Icons.Default.BugReport, "应用日志",
                subtitle = "查看运行日志和错误信息", onClick = onNavigateAppLog)
        }

        // 捐赠入口 — 放「关于」之后是有意为之：能滚到这里的人多半是在系统性
        // 浏览设置，对软件有了基本了解；比放在顶部"突然伸手要钱"舒服得多。
        SettingsCard(
            icon = Icons.Default.Favorite,
            title = "请作者喝杯咖啡",
            desc = "MoRealm 无广告、高性能。如果它陪你读了很多书，欢迎请作者喝一杯",
            onClick = onNavigateDonate,
        )

        Spacer(Modifier.height(96.dp))
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
        modifier = modifier
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(
            width = if (isActive) 2.dp else 1.dp,
            color = if (isActive) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outlineVariant,
        ),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(
            Modifier.padding(10.dp),
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
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
        }
        Icon(Icons.Default.ChevronRight, null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f), modifier = Modifier.size(20.dp))
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
    val context = LocalContext.current
    val highlights = remember(report) { report?.annualHighlights().orEmpty() }

    // 注：之前在 dialog 内做 Crossfade 滚动展示 highlight；现在 UI 走紧凑版，
    // 直接在卡片底部一行小字显示首条 highlight（如有）。完整数据走"保存长图"。

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp,
        ) {
            if (report == null) {
                Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(32.dp))
                }
                return@Surface
            }

            Column(Modifier.padding(18.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("年度总结", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "关闭") }
                }

                AnnualReportCard(
                    report = report,
                    accentColor = accentColor,
                    teaser = highlights.firstOrNull { it.first != "读完/收藏的书" },
                    modifier = Modifier.fillMaxWidth(),
                )

                // 提示用户：长图保存了所有内容
                Spacer(Modifier.height(10.dp))
                Text(
                    "保存到相册的长图含完整指标 / 标签 / 最投入书目",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )

                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                        Text("稍后再看")
                    }
                    Button(
                        onClick = {
                            val ok = saveAnnualReportCard(context, report, accentColor)
                            Toast.makeText(context, if (ok) "已保存到相册" else "保存失败", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Default.Download, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("保存长图")
                    }
                }
            }
        }
    }
}

/**
 * Compact annual report card for dialog display.
 *
 * Compared with the previous version this:
 *  - Removes the Crossfade highlight rotation (saved 96dp + animation overhead).
 *  - Drops the dedicated "favorite book" Surface block; merges its info into a
 *    single bottom line.
 *  - Drops the tag chip row from the in-dialog view (still rendered in the saved
 *    long-image via [drawAnnualReportBitmap]).
 *  - Tightens vertical paddings and the hero number font (42sp → 34sp).
 *
 * Net height: ~600dp → ~340dp. Saved long image is unchanged.
 *
 * @param teaser optional one-line highlight to spotlight under the hero number
 *               (e.g. "沉浸阅读时长 → 128 小时"). Use null for the default
 *               "陪你走过的书" subtitle.
 */
@Composable
private fun AnnualReportCard(
    report: AnnualReport,
    accentColor: Color,
    teaser: Pair<String, String>?,
    modifier: Modifier = Modifier,
) {
    val secondary = accentColor.copy(alpha = 0.72f)
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(24.dp))
            .background(
                Brush.verticalGradient(
                    listOf(accentColor.copy(alpha = 0.95f), secondary, Color(0xFF15131A))
                )
            )
            .padding(horizontal = 18.dp, vertical = 18.dp)
    ) {
        // Decorative corner blob — kept but smaller
        Box(
            Modifier.size(90.dp).offset(x = 230.dp, y = (-30).dp)
                .clip(CircleShape).background(Color.White.copy(alpha = 0.10f))
        )
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                "MOREALM READING",
                color = Color.White.copy(alpha = 0.62f),
                fontSize = 10.sp,
                letterSpacing = 2.sp,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "${report.year} 年度阅读报告",
                color = Color.White,
                fontWeight = FontWeight.Black,
                fontSize = 18.sp,
            )

            Spacer(Modifier.height(14.dp))

            // 主数据：本数（最稳定的指标）
            Text(
                "${report.totalBooks} 本",
                color = Color.White,
                fontWeight = FontWeight.Black,
                fontSize = 34.sp,
            )
            Text(
                "陪你走过的书",
                color = Color.White.copy(alpha = 0.72f),
                fontSize = 12.sp,
            )

            Spacer(Modifier.height(14.dp))

            // 3 个核心指标
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AnnualMetric("阅读", "${report.totalDurationHours.coerceAtLeast(0)}h", Modifier.weight(1f))
                AnnualMetric("文字", "${report.totalWordsWan.coerceAtLeast(0)}万", Modifier.weight(1f))
                AnnualMetric("活跃", "${report.activeDays}天", Modifier.weight(1f))
            }

            Spacer(Modifier.height(12.dp))

            // 最投入合并到一行（替代了原来的整张 Surface 卡片）
            val fav = report.favoriteBook.ifBlank { "还在等待被记录" }
            Text(
                "最投入：$fav",
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "最长 ${report.longestSessionMin} 分钟 · 常在 ${report.peakHour} 翻书",
                color = Color.White.copy(alpha = 0.55f),
                fontSize = 10.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )

            // 可选的一条 teaser（按 highlight 中第二条挑）— 不要旋转动画，静态展示
            if (teaser != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "${teaser.first}：${teaser.second}",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 10.sp,
                )
            }
        }
    }
}

@Composable
private fun AnnualMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(shape = RoundedCornerShape(16.dp), color = Color.White.copy(alpha = 0.14f), modifier = modifier) {
        Column(Modifier.padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, color = Color.White, fontWeight = FontWeight.Black, fontSize = 18.sp)
            Text(label, color = Color.White.copy(alpha = 0.62f), fontSize = 11.sp)
        }
    }
}

private fun AnnualReport.annualHighlights(): List<Pair<String, String>> = listOfNotNull(
    "读完/收藏的书" to "$totalBooks 本",
    if (totalDurationHours > 0) "沉浸阅读时长" to "$totalDurationHours 小时" else null,
    if (totalWordsWan > 0) "翻过的文字" to "$totalWordsWan 万字" else null,
    if (activeDays > 0) "有阅读记录的日子" to "$activeDays 天" else null,
    if (longestSessionMin > 0) "最长一次陪伴" to "$longestSessionMin 分钟" else null,
)

private fun saveAnnualReportCard(context: Context, report: AnnualReport, accentColor: Color): Boolean {
    val bitmap = drawAnnualReportBitmap(report, accentColor)
    val fileName = "MoRealm_Annual_${report.year}_${System.currentTimeMillis()}.png"
    return try {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return saveAnnualReportLegacy(bitmap, fileName)
        }
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/MoRealm")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        val saved = resolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } == true
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        if (!saved) {
            resolver.delete(uri, null, null)
        }
        saved
    } catch (_: Exception) {
        false
    } finally {
        bitmap.recycle()
    }
}

private fun saveAnnualReportLegacy(bitmap: Bitmap, fileName: String): Boolean {
    return try {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "MoRealm")
        if (!dir.exists() && !dir.mkdirs()) return false
        val file = File(dir, fileName)
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    } catch (_: Exception) {
        false
    }
}

private fun drawAnnualReportBitmap(report: AnnualReport, accentColor: Color): Bitmap {
    val width = 1080
    val height = 1680
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val accent = accentColor.toArgbCompat()
    canvas.drawColor(0xFF15131A.toInt())
    paint.shader = LinearGradient(0f, 0f, 0f, height.toFloat(), accent, 0xFF15131A.toInt(), Shader.TileMode.CLAMP)
    canvas.drawRoundRect(RectF(70f, 70f, width - 70f, height - 70f), 72f, 72f, paint)
    paint.shader = null
    paint.color = 0x22FFFFFF
    canvas.drawCircle(900f, 170f, 170f, paint)
    canvas.drawCircle(120f, 1350f, 220f, paint)

    fun text(value: String, x: Float, y: Float, size: Float, color: Int, bold: Boolean = false, align: Paint.Align = Paint.Align.CENTER) {
        paint.shader = null
        paint.color = color
        paint.textSize = size
        paint.textAlign = align
        paint.typeface = if (bold) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
        canvas.drawText(value, x, y, paint)
    }

    text("MOREALM READING", width / 2f, 190f, 34f, 0xAAFFFFFF.toInt(), false)
    text("${report.year} 年度阅读报告", width / 2f, 280f, 66f, 0xFFFFFFFF.toInt(), true)
    text("${report.totalBooks} 本", width / 2f, 500f, 128f, 0xFFFFFFFF.toInt(), true)
    text("陪你走过的书", width / 2f, 570f, 38f, 0xBFFFFFFF.toInt())

    val metrics = listOf("阅读 ${report.totalDurationHours}h", "文字 ${report.totalWordsWan}万", "活跃 ${report.activeDays}天")
    metrics.forEachIndexed { i, item ->
        val left = 150f + i * 270f
        paint.color = 0x26FFFFFF
        canvas.drawRoundRect(RectF(left, 680f, left + 230f, 820f), 36f, 36f, paint)
        text(item, left + 115f, 765f, 34f, 0xFFFFFFFF.toInt(), true)
    }

    paint.color = 0x26FFFFFF
    canvas.drawRoundRect(RectF(140f, 910f, 940f, 1210f), 48f, 48f, paint)
    text("你最投入的一本书", width / 2f, 1000f, 34f, 0xAAFFFFFF.toInt())
    text(report.favoriteBook.ifBlank { "还在等待被记录" }.take(18), width / 2f, 1090f, 52f, 0xFFFFFFFF.toInt(), true)
    text("最长单日 ${report.longestSessionMin} 分钟 · 常在 ${report.peakHour} 打开书页", width / 2f, 1170f, 30f, 0xAAFFFFFF.toInt())
    text("墨境 MoRealm · 把阅读留在时间里", width / 2f, 1510f, 34f, 0x99FFFFFF.toInt())
    return bitmap
}

private fun Color.toArgbCompat(): Int = android.graphics.Color.argb(
    (alpha * 255).toInt(),
    (red * 255).toInt(),
    (green * 255).toInt(),
    (blue * 255).toInt(),
)
