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
import androidx.activity.compose.rememberLauncherForActivityResult
import com.morealm.app.presentation.profile.ProfileStatsViewModel
import com.morealm.app.presentation.profile.AnnualReport
import com.morealm.app.presentation.appearance.GlobalBgViewModel
import com.morealm.app.presentation.update.UpdateViewModel
import com.morealm.app.BuildConfig
import com.morealm.app.R
import com.morealm.app.ui.common.LocalCardAlpha
import com.morealm.app.ui.common.LocalCardBlur
import com.morealm.app.ui.common.supportsBlur
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
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
import com.morealm.app.widget.WidgetPinHelper
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    themeViewModel: ThemeViewModel = hiltViewModel(),
    profileViewModel: ProfileStatsViewModel = hiltViewModel(),
    updateViewModel: UpdateViewModel = hiltViewModel(),
    onNavigateWebDav: () -> Unit = {},
    onNavigateAbout: () -> Unit = {},
    onNavigateSourceManage: () -> Unit = {},
    onNavigateReadingSettings: () -> Unit = {},
    onNavigateSearchSettings: () -> Unit = {},
    onNavigateReplaceRules: () -> Unit = {},
    onNavigateAutoGroupRules: () -> Unit = {},
    onNavigateAppLog: () -> Unit = {},
    onNavigateCacheBook: () -> Unit = {},
    onNavigateThemeEditor: () -> Unit = {},
    onNavigateDonate: () -> Unit = {},
    onNavigateBackupExport: () -> Unit = {},
    onNavigateBackupImport: () -> Unit = {},
    /** 跳到 Legado 一键搬家页（独立流程，不复用备份导入页的状态）。 */
    onNavigateLegadoImport: () -> Unit = {},
    /** 跳到外观设置页。 */
    onNavigateAppearance: () -> Unit = {},
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
    var showAnnualReport by remember { mutableStateOf(false) }
    // 「跟随系统」开启时，给用户配置日 / 夜默认主题的选择对话框可见性。
    // 两个独立 state 而非一个 enum，是为了允许（极端情况下）两个 dialog 同时
    // 出现不会互相干扰；常规用户从一个流程进，互不影响。
    var showAutoDayPicker by remember { mutableStateOf(false) }
    var showAutoNightPicker by remember { mutableStateOf(false) }

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

    // UX-1: Snackbar host 用于「删除主题」的撤销窗口
    val snackbarHost = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // 主题导入消息订阅 —— ViewModel 的 importMessage SharedFlow 通过 snackbar 提示用户：
    //   - 成功："已导入主题：XXX"
    //   - ReadConfig 命中："检测到「阅读样式配置」..."（关键 toast，告知用户 JSON 类型识别情况）
    //   - 失败："主题导入失败：XXX"
    // 用 snackbarHost 而非 Toast：与现有「删除主题撤销」共用一个 host，UI 上不重叠
    androidx.compose.runtime.LaunchedEffect(themeViewModel) {
        themeViewModel.importMessage.collect { msg ->
            snackbarHost.showSnackbar(msg)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
    Column(
        modifier = Modifier.fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        TopAppBar(
            title = { Text("我的", fontWeight = FontWeight.Bold) },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color.Transparent,
                scrolledContainerColor = Color.Transparent,
            ),
            windowInsets = WindowInsets(0, 0, 0, 0),
        )

        // Reading stats card (real data)
        // 白天 / 夜间各自一张插画背景（profile_card_bg_day / night）。containerColor 透明，
        // Image 通过 Card shape 自动 clip 成 16dp 圆角。
        Card(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        ) {
            Box {
                Image(
                    painter = painterResource(
                        if (moColors.isNight) R.drawable.profile_card_bg_night
                        else R.drawable.profile_card_bg_day
                    ),
                    contentDescription = null,
                    modifier = Modifier.matchParentSize(),
                    contentScale = ContentScale.Crop,
                )
                Column(modifier = Modifier.padding(20.dp)) {
                    Text("阅读统计", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                        StatItem(value = "$totalBooks", label = "本书")
                        StatItem(value = formatDuration(totalReadMs), label = "总时长")
                        StatItem(value = "$recentDays", label = "连续天数")
                    }
                    // UX-6 (亲密性): 「主指标块 (标题+3 个数字)」与「辅助块 (今日+年度报告)」
                    // 原本三个 12dp 同等间距, 视觉上四件平铺. 主→辅 拉到 18dp, 辅内紧密 4dp.
                    Spacer(Modifier.height(18.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        Text("今日已读 ${formatDuration(todayReadMs)}",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                    Spacer(Modifier.height(4.dp))
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
                // 「跟随系统」开关 —— 开启后系统暗色模式变化（用户改了 Android
                // 系统设置 → 显示 → 深色主题，或日落自动切换）会驱动这里在内置
                // 日间 / 夜间主题之间切。手动点下面任何主题瓦片都会自动关掉这个
                // 开关（switchTheme 内部会调 setFollowSystemTheme(false)），
                // 把主题选择权交还用户。
                run {
                    val follow by themeViewModel.followSystemTheme.collectAsStateWithLifecycle()
                    val systemIsDark = androidx.compose.foundation.isSystemInDarkTheme()
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("跟随系统主题",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium)
                            Text(
                                if (follow) "已开启 · 系统切到${if (systemIsDark) "深色" else "浅色"}时自动切换"
                                else "关闭 · 用下方瓦片手动选择主题",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            )
                        }
                        Switch(
                            checked = follow,
                            onCheckedChange = { enabled ->
                                themeViewModel.setFollowSystemTheme(enabled, systemIsDark)
                            },
                        )
                    }
                    // 跟随系统打开后才暴露日 / 夜默认主题选择 —— 关闭时这两行没意义
                    // 而且会让 Card 永久长高，加重 profile 拥挤问题。用简单 if 而非
                    // AnimatedVisibility，避免引入新的 import 和动画时序耦合。
                    if (follow) {
                        Spacer(Modifier.height(12.dp))
                        AutoThemePickerRow(
                            label = "白天默认主题",
                            currentThemeName = run {
                                val id by themeViewModel.autoDayThemeId.collectAsStateWithLifecycle()
                                allThemes.find { it.id == id }?.name ?: "内置 · 米色书页"
                            },
                            onClick = { showAutoDayPicker = true },
                        )
                        Spacer(Modifier.height(8.dp))
                        AutoThemePickerRow(
                            label = "夜晚默认主题",
                            currentThemeName = run {
                                val id by themeViewModel.autoNightThemeId.collectAsStateWithLifecycle()
                                allThemes.find { it.id == id }?.name ?: "内置 · 墨境"
                            },
                            onClick = { showAutoNightPicker = true },
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    Spacer(Modifier.height(12.dp))
                }
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
                                onLongClick = {
                                    // UX-1: 长按 → 立即删 + Snackbar 撤销，不再弹 AlertDialog。
                                    // customThemes 来源已过滤 isBuiltin = false，repo 也有兜底。
                                    val snapshot = theme
                                    themeViewModel.deleteCustomTheme(theme.id)
                                    scope.launch {
                                        val r = snackbarHost.showSnackbar(
                                            message = "已删除主题「${snapshot.name}」",
                                            actionLabel = "撤销",
                                            duration = SnackbarDuration.Short,
                                            withDismissAction = true,
                                        )
                                        if (r == SnackbarResult.ActionPerformed) {
                                            themeViewModel.restoreCustomTheme(snapshot)
                                        }
                                    }
                                },
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

        // UX-2 (分组直觉): 4 张外观/阅读类卡片之前加 SectionTitle, 给用户清晰的「这一组都管偏好」锚点.
        SectionTitle("外观与阅读")

        @Suppress("DEPRECATION")
        SettingsCard(Icons.Default.Wallpaper, "外观",
            "全局背景图、透明度、模糊度", onClick = onNavigateAppearance)
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

        // UX-2 (分组直觉): 书源 / 书签 / 缓存 / Legado 搬家 / 小组件 也加锚点.
        SectionTitle("内容管理")

        SettingsCard(Icons.Default.Extension, "书源管理",
            "导入、启用、删除书源，支持 URL 订阅和 JSON 导入", onClick = onNavigateSourceManage)
        SettingsCard(Icons.Default.Search, "搜索设置",
            "并发搜索数量、单源超时，根据网络情况调节", onClick = onNavigateSearchSettings)
        SettingsCard(Icons.Default.Bookmark, "我的书签",
            "跨书查看、按时间过滤、按书分组", onClick = onNavigateBookmarks)
        SettingsCard(Icons.Default.CloudDownload, "离线缓存",
            "批量下载章节，支持离线阅读", onClick = onNavigateCacheBook)
        SettingsCard(Icons.Default.ImportExport, "Legado 一键搬家",
            "导入 Legado 备份，书源/书架/进度全部迁移", onClick = onNavigateLegadoImport)

        // Widget preview — 点击尝试请求把「继续阅读」小组件 pin 到桌面；
        // Launcher 不支持 (API 23~25 或部分老版 OEM) 时弹引导 Dialog 教用户长按桌面手动添加。
        // SDK < 23 (Android 6.0) 整张卡片置灰：Glance 不支持，receiver 由
        // res/values/widget_bools.xml 资源守卫禁用。
        val widgetContext = LocalContext.current
        val widgetSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
        var showWidgetGuide by remember { mutableStateOf(false) }
        SettingsCard(
            Icons.Default.Widgets,
            "桌面小组件",
            if (widgetSupported) "把「继续阅读」放到桌面，一键回到上次位置"
            else "需要 Android 6.0 或以上系统",
            onClick = if (widgetSupported) {
                {
                    val pinned = WidgetPinHelper.requestPin(widgetContext)
                    if (!pinned) {
                        // Launcher 不支持 requestPinAppWidget → 弹引导 Dialog
                        showWidgetGuide = true
                    }
                    // 成功投递时由 Launcher 自己弹「添加到桌面」对话框，无需
                    // 我们再 Toast，避免重复信息。
                }
            } else {
                { /* SDK 不支持，整张卡片不可交互（点击无反应） */ }
            },
        ) {
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

        // 「不支持自动添加」的回退 Dialog —— 教用户长按桌面手动添加
        if (showWidgetGuide) {
            AlertDialog(
                onDismissRequest = { showWidgetGuide = false },
                title = { Text("手动添加小组件") },
                text = {
                    Text(
                        "你的桌面 Launcher 不支持一键添加。请：\n" +
                            "1. 长按桌面空白处\n" +
                            "2. 选择「小组件」/「Widgets」\n" +
                            "3. 找到「墨境·继续阅读」，拖到桌面",
                    )
                },
                confirmButton = {
                    TextButton(onClick = { showWidgetGuide = false }) { Text("知道了") }
                },
            )
        }

        SettingsSection("关于") {
            SettingsItem(Icons.Default.Info, "关于墨境", onClick = onNavigateAbout)
            SettingsItem(
                icon = Icons.Default.SystemUpdate,
                title = "检查更新",
                subtitle = "网盘下载最新版（当前 v${BuildConfig.VERSION_NAME}）",
                onClick = { updateViewModel.showChannels() },
            )
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

    // UX-1: 删除主题已迁移到 onLongClick 内联处理（立即删 + Snackbar 撤销），
    // 原 AlertDialog + showDeleteThemeConfirm 状态已下线。

    // 「跟随系统主题」日 / 夜默认主题选择对话框。candidates 按 isNightTheme 过滤，
    // 用户选「使用内置默认」时写入空串 = ThemeViewModel 会 fallback 到 paper / moRealm。
    if (showAutoDayPicker) {
        val selectedId by themeViewModel.autoDayThemeId.collectAsStateWithLifecycle()
        AutoThemePickerDialog(
            title = "选择白天默认主题",
            candidates = allThemes.filter { !it.isNightTheme },
            currentSelectedId = selectedId,
            fallbackLabel = "使用内置默认（米色书页）",
            onPick = { id ->
                themeViewModel.setAutoDayThemeId(id)
                showAutoDayPicker = false
            },
            onDismiss = { showAutoDayPicker = false },
        )
    }
    if (showAutoNightPicker) {
        val selectedId by themeViewModel.autoNightThemeId.collectAsStateWithLifecycle()
        AutoThemePickerDialog(
            title = "选择夜晚默认主题",
            candidates = allThemes.filter { it.isNightTheme },
            currentSelectedId = selectedId,
            fallbackLabel = "使用内置默认（墨境）",
            onPick = { id ->
                themeViewModel.setAutoNightThemeId(id)
                showAutoNightPicker = false
            },
            onDismiss = { showAutoNightPicker = false },
        )
    }

    // Annual report dialog
    if (showAnnualReport) {
        AnnualReportDialog(
            report = annualReport,
            accentColor = MaterialTheme.colorScheme.primary,
            onSaveResult = { ok ->
                // 保存结果走主屏 Snackbar（颜色随主题、避开 pill），原来用裸 Toast。
                scope.launch {
                    snackbarHost.showSnackbar(if (ok) "已保存到相册" else "保存失败")
                }
            },
            onDismiss = { showAnnualReport = false },
        )
    }
    // 检查更新：点击直接弹三网盘下载渠道（不查版本，详见 UpdateViewModel / UpdateDialogHost）。
    val updateState by updateViewModel.state.collectAsStateWithLifecycle()
    UpdateDialogHost(
        state = updateState,
        onDismiss = updateViewModel::dismiss,
    )

    // 浮在药丸导航栏之上：pill 高 64dp + 底 padding 16dp ≈ 80dp，
    // 这里给 96dp 让 Snackbar 与 pill 之间留 ~16dp 视觉间隙，避免提示被吞掉。
    com.morealm.app.ui.widget.ThemedSnackbarHost(
        snackbarHost,
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = 96.dp),
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

// 时长格式：用国际通用 h/m 缩写代替「小时/分钟」中文长字 ——
// 统计卡片格子小，长字会换行/截断。Image 12 风格：「9h 53m」
// 比「9小时53分」更紧凑、信息密度高。
private fun formatDuration(ms: Long): String {
    val minutes = ms / 60_000
    return when {
        minutes < 1 -> "0m"
        minutes < 60 -> "${minutes}m"
        else -> {
            val h = minutes / 60
            val m = minutes % 60
            if (m == 0L) "${h}h" else "${h}h ${m}m"
        }
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
        // Bug 1 修复：原本 Column 没 fillMaxWidth + horizontalAlignment=CenterHorizontally，
        // 导致 Column 宽度被最宽子节点决定 —— "墨境/纸上" 等 2 字主题文字 ≤ 28dp 圆点，圆点
        // 视觉贴左；而 "赛博朋克" 4 字撑宽 Column，圆点 CenterHorizontally 居中于"文字宽度"
        // 而非"卡片宽度"，导致它和其他卡片不对齐。改 fillMaxWidth + Start 后所有圆点都贴左。
        Column(
            Modifier.padding(10.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.Start,
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
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
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

/**
 * 「跟随系统主题」开启时，给用户挑选日 / 夜默认主题的入口行。
 *
 * 视觉对齐 SettingsItem 但不能复用 —— SettingsItem 强制 Card containerColor=transparent
 * 假设父级是 SettingsSection；本组件直接放在主题 Card 内，需要透明背景 + 缩进与
 * 其它 Card 内文字对齐，所以手写 Row 而非 ListItem。trailing 用 ChevronRight 暗示
 * 「点击进二级选择」。
 */
@Composable
private fun AutoThemePickerRow(
    label: String,
    currentThemeName: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                currentThemeName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight, null,
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * 主题选择对话框 —— 列出符合 [filterIsNight] 的所有主题（内置 + 自定义）+ 一项
 * 「使用内置默认」让用户能取消自定义回退到默认值。
 *
 * 不用 BottomSheet：profile 是滚动页面，BottomSheet 在长滚动场景下手势容易冲突，
 * AlertDialog 是 ProfileScreen 现有 UX 已采用的统一选择 pattern（年度报告 / 删除
 * 确认都是 AlertDialog），保持一致比追求新颖更重要。
 */
@Composable
private fun AutoThemePickerDialog(
    title: String,
    candidates: List<com.morealm.app.domain.entity.ThemeEntity>,
    currentSelectedId: String,
    fallbackLabel: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            // heightIn 限高 + 内部 Column.verticalScroll —— 与换源对话框同款套路。
            // M3 AlertDialog text slot 不会自动滚动，必须自己包 verticalScroll，否则
            // 候选超出可视区会被裁掉无法滑（这是 BookDetailScreen 已经踩过的坑）。
            Column(
                modifier = Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                ThemePickerOption(
                    name = fallbackLabel,
                    selected = currentSelectedId.isBlank(),
                    onClick = { onPick("") },
                )
                for (theme in candidates) {
                    ThemePickerOption(
                        name = theme.name,
                        selected = theme.id == currentSelectedId,
                        onClick = { onPick(theme.id) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

@Composable
private fun ThemePickerOption(name: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.small,
        color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                name,
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            if (selected) {
                Icon(
                    Icons.Default.Check, null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/**
 * 设置项行 —— Material 3 [ListItem] 三槽位封装。
 *
 * 三槽位语义对应：
 *   - leading  → 主题色 icon
 *   - headline → 标题
 *   - supporting → 副标题（可选）
 *   - trailing → 「>」箭头（统一引导用户「这是个可点的入口」）
 *
 * 为什么用 ListItem 而不是手写 Row：
 *   - 主题色 / 间距 / 文本对齐自动跟随 Material 3 token，不再写散落的硬编码 dp
 *   - 触摸热区由 ListItem 给出（M3 默认 56-72dp），无障碍触达更稳
 *   - 升级 material3 时新增的 token（如 fixed container roles）会自动生效
 *
 * containerColor 显式 transparent —— 调用方通常包在 [SettingsSection] 的 Card 里，
 * Card 已经提供 surfaceContainerHigh 背景，ListItem 不能再叠一层 surface 否则双重底色。
 */
@Composable
fun SettingsItem(icon: ImageVector, title: String, subtitle: String? = null, onClick: () -> Unit = {}) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = { Text(title, style = MaterialTheme.typography.bodyMedium) },
        supportingContent = subtitle?.let {
            { Text(it, style = MaterialTheme.typography.bodySmall) }
        },
        leadingContent = {
            Icon(
                icon, null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
        },
        trailingContent = {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight, null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.size(20.dp),
            )
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}


/**
 * Annual reading report dialog — ported from HTML prototype.
 * All data is dynamic from ReadStats + Book tables.
 */
@Composable
private fun AnnualReportDialog(
    report: AnnualReport?,
    accentColor: Color,
    /** 保存长图结果回调：true = 保存成功；调用方负责弹 Snackbar / Toast 反馈。 */
    onSaveResult: (Boolean) -> Unit,
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
                            onSaveResult(ok)
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

/**
 * AppearanceCard 已迁移到独立 AppearanceScreen，本文件不再保留实现。
 */
