package com.morealm.app.ui.profile

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.morealm.app.presentation.profile.ProfileViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 备份导出选项页。
 *
 * Why a separate screen instead of a dialog
 * - User can clearly see每类数据的项数和占用大小，从而决定是否裁剪。
 * - 选项较多 (8 类)，对话框纵向空间不足。
 *
 * 行为
 * - 进入时调用 [ProfileViewModel.loadBackupSections] 拉取每类预览（项数 + json 字节）。
 * - 默认全部勾选；用户可单独勾掉、"全选"、"全不选"。
 * - 顶部展示已选项总大小（实时合计 selected sections 的 estimatedBytes）。
 * - 「开始导出」打开 SAF CreateDocument，挑选位置后由 ProfileViewModel.exportBackup
 *   按当前 selections 生成 BackupOptions 落到 BackupManager。
 * - 导出结果由 ProfileScreen 上的全局 Toast 监听器触达，本页只显示加载/选择 UI。
 *
 * 设计取舍
 * - 不在本页展示 backupStatus —— 避免和 ProfileScreen 的 Toast 重复。
 *   ProfileScreen 始终在 Composition Tree 中（即使被这个 detail 屏盖住），
 *   `backupStatus` Flow 的 LaunchedEffect 仍会触发 Toast。
 * - 完成导出后不自动 onBack，让用户可以再次操作；返回由用户主动按返回键。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupExportScreen(
    onBack: () -> Unit,
    profileViewModel: ProfileViewModel = hiltViewModel(),
) {
    val sections by profileViewModel.backupSections.collectAsStateWithLifecycle()
    val selections by profileViewModel.backupSelections.collectAsStateWithLifecycle()
    val loading by profileViewModel.backupSectionsLoading.collectAsStateWithLifecycle()

    // 进入时刷新预览。Idempotent，重复进入也只是再读一次 DB。
    LaunchedEffect(Unit) { profileViewModel.loadBackupSections() }

    val backupExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        uri?.let { profileViewModel.exportBackup(it) }
    }

    // 实时合计已勾选类别的 json 字节数。空选 = 0 B。
    val selectedBytes = remember(sections, selections) {
        sections.filter { it.key in selections }.sumOf { it.estimatedBytes }
    }
    val selectedCount = remember(sections, selections) {
        sections.count { it.key in selections }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("导出备份", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
        bottomBar = {
            // 底部固定操作条：左侧合计，右侧主按钮。空选时禁用按钮，避免生成空 zip。
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 3.dp,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "已选 $selectedCount / ${sections.size} 项",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        )
                        Text(
                            "约 ${formatBytes(selectedBytes)}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Button(
                        enabled = selectedCount > 0 && !loading,
                        onClick = {
                            val ts = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault())
                                .format(Date())
                            backupExportLauncher.launch("MoRealm_backup_$ts.zip")
                        },
                    ) {
                        Text("开始导出")
                    }
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            // 总览卡片：所有类别合计大小（不论是否勾选），让用户一眼看到完整备份的体量。
            val totalBytes = remember(sections) { sections.sumOf { it.estimatedBytes } }
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                ),
            ) {
                Column(Modifier.padding(20.dp)) {
                    Text(
                        "完整备份大小（含全部类别）",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (loading) "估算中…" else formatBytes(totalBytes),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "未压缩 JSON 估算值；实际 ZIP 体积通常更小，密码加密会再增加少量头部。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
            }

            // 全选 / 全不选
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { profileViewModel.selectAllBackupSections() },
                    enabled = !loading && sections.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                ) { Text("全选") }
                OutlinedButton(
                    onClick = { profileViewModel.clearBackupSections() },
                    enabled = !loading && sections.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                ) { Text("全不选") }
            }

            Spacer(Modifier.height(12.dp))

            if (loading && sections.isEmpty()) {
                // 首次进入读取数据库时占位 loading；非首次（已有缓存）不阻塞。
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            } else if (sections.isEmpty()) {
                Text(
                    "暂无可导出数据",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            } else {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                ) {
                    Column {
                        sections.forEachIndexed { index, info ->
                            val checked = info.key in selections
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { profileViewModel.toggleBackupSection(info.key) }
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = {
                                        profileViewModel.toggleBackupSection(info.key)
                                    },
                                )
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        info.label,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Medium,
                                    )
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        "${info.itemCount} 项",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                    )
                                }
                                // 单类大小徽章；零项时显示淡灰，减少视觉噪音。
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (info.itemCount == 0) {
                                        MaterialTheme.colorScheme.surfaceVariant
                                    } else {
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                    },
                                ) {
                                    Text(
                                        formatBytes(info.estimatedBytes),
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (info.itemCount == 0) {
                                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                        } else {
                                            MaterialTheme.colorScheme.primary
                                        },
                                    )
                                }
                            }
                            if (index < sections.lastIndex) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(start = 56.dp),
                                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f),
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

/**
 * Human-readable byte size: B / KB / MB.
 *
 * 简化版，不需要 GB 量级（备份 JSON 极少超过几十 MB）。
 * 1024 进制以匹配文件资源管理器的常见显示风格。
 */
private fun formatBytes(bytes: Int): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    else -> String.format(Locale.US, "%.2f MB", bytes / 1024.0 / 1024.0)
}
