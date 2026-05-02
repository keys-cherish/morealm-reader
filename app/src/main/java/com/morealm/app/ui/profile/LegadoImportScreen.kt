package com.morealm.app.ui.profile

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.ImportExport
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.morealm.app.domain.sync.LegadoImporter
import com.morealm.app.presentation.profile.LegadoImportViewModel

/**
 * 「Legado 一键搬家」UI 入口页。流程：
 *
 * 1. 进入页 — 还没选 zip，Hero 卡片 + 「选择 Legado 备份 zip」按钮。
 * 2. SAF 选完 → 立即调 [LegadoImportViewModel.onZipPicked] 解 zip + 生成预览
 * 3. 预览完成 → 渲染统计卡（书架 / 书源 / 书签 / 分组 / 替换规则 / 朗读引擎）+ 冲突警告 +
 *    冲突策略 toggle（保守跳过 / 强制覆盖）+ 「开始导入」按钮
 * 4. 导入完成 → 渲染 ImportResult 摘要 + 「再选一个」/「完成」按钮
 *
 * 不做的事（与 [BackupImportScreen] 区别）：
 *  - 不支持加密（Legado 备份 zip 不加密；用户从 WebDav 拉的也是明文）
 *  - 不支持分类勾选（Legado 流程要"一键"，全选；个别项不想要可以导入后到对应页删）
 *  - 不接 BackupStatusBus（错误直接显示在本页 banner，不抢全局 Toast 通道）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LegadoImportScreen(
    onBack: () -> Unit,
    viewModel: LegadoImportViewModel = hiltViewModel(),
) {
    val pendingUri by viewModel.pendingUri.collectAsStateWithLifecycle()
    val preview by viewModel.preview.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val importing by viewModel.importing.collectAsStateWithLifecycle()
    val result by viewModel.result.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()
    val conflictStrategy by viewModel.conflictStrategy.collectAsStateWithLifecycle()

    // SAF 多 mime 兼容：Legado 备份 zip 在某些机型 mime 是 application/octet-stream
    val pickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) viewModel.onZipPicked(uri)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Legado 一键搬家") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(8.dp))

            // ── Hero 卡片：什么是搬家、能搬什么 ──
            HeroCard()

            Spacer(Modifier.height(16.dp))

            // ── 选择文件按钮 / 重选按钮 ──
            FilePickerButton(
                hasFile = pendingUri != null,
                loading = loading,
                onPick = {
                    pickerLauncher.launch(arrayOf(
                        "application/zip",
                        "application/octet-stream",
                        "*/*",
                    ))
                },
            )

            // ── 错误 banner ──
            errorMessage?.let { msg ->
                Spacer(Modifier.height(12.dp))
                ErrorCard(msg)
            }

            // ── 预览统计 ──
            preview?.let { pv ->
                Spacer(Modifier.height(16.dp))
                PreviewCard(pv)

                Spacer(Modifier.height(16.dp))
                ConflictStrategyCard(
                    strategy = conflictStrategy,
                    onChange = viewModel::setConflictStrategy,
                    hasConflicts = pv.bookConflicts + pv.bookSourceConflicts +
                        pv.bookGroupConflicts + pv.replaceRuleConflicts +
                        pv.httpTtsConflicts > 0,
                )

                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = viewModel::runImport,
                    enabled = !importing && result == null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                ) {
                    if (importing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text("正在导入…")
                    } else {
                        Icon(Icons.Default.ImportExport, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("开始导入")
                    }
                }
            }

            // ── 导入结果 ──
            result?.let { r ->
                Spacer(Modifier.height(16.dp))
                ResultCard(
                    result = r,
                    onAnother = viewModel::reset,
                    onDone = onBack,
                )
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun HeroCard() {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
        ),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.ImportExport,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "从 Legado 一键搬家",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "选择 Legado 应用导出的 .zip 备份，自动迁移：\n" +
                    "• 书架（含阅读进度）\n" +
                    "• 书源 + 替换规则 + 自定义朗读引擎\n" +
                    "• 书签 + 分组",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
                lineHeight = MaterialTheme.typography.bodyMedium.lineHeight,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "暂不支持：RSS、词典、键盘助手、阅读样式（Legado 不在备份内带这些字节，下版补）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.55f),
            )
        }
    }
}

@Composable
private fun FilePickerButton(
    hasFile: Boolean,
    loading: Boolean,
    onPick: () -> Unit,
) {
    OutlinedButton(
        onClick = onPick,
        enabled = !loading,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
            )
            Spacer(Modifier.width(10.dp))
            Text("正在解析…")
        } else {
            Icon(Icons.Default.FolderOpen, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(if (hasFile) "重新选择 Legado 备份 zip" else "选择 Legado 备份 zip")
        }
    }
}

@Composable
private fun ErrorCard(message: String) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.WarningAmber,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

@Composable
private fun PreviewCard(pv: LegadoImporter.Preview) {
    Card(shape = RoundedCornerShape(16.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "检测到内容",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(10.dp))
            PreviewRow("书架", pv.bookCount, pv.bookConflicts)
            PreviewRow("书源", pv.bookSourceCount, pv.bookSourceConflicts)
            PreviewRow("书签", pv.bookmarkCount, conflicts = -1) // 书签按 bookName 反查没有 PK 冲突概念
            PreviewRow("分组", pv.bookGroupCount, pv.bookGroupConflicts)
            PreviewRow("替换规则", pv.replaceRuleCount, pv.replaceRuleConflicts)
            PreviewRow("朗读引擎", pv.httpTtsCount, pv.httpTtsConflicts)

            if (pv.warnings.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                pv.warnings.forEach { w ->
                    Text(
                        "• $w",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }

            if (pv.skippedFiles.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "忽略的项：${pv.skippedFiles.joinToString("、")}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PreviewRow(label: String, count: Int, conflicts: Int) {
    if (count == 0) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Text(
            "$count 项",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
        if (conflicts > 0) {
            Spacer(Modifier.width(8.dp))
            Surface(
                color = MaterialTheme.colorScheme.tertiaryContainer,
                shape = RoundedCornerShape(8.dp),
            ) {
                Text(
                    "冲突 $conflicts",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun ConflictStrategyCard(
    strategy: LegadoImporter.ConflictStrategy,
    onChange: (LegadoImporter.ConflictStrategy) -> Unit,
    hasConflicts: Boolean,
) {
    Card(shape = RoundedCornerShape(16.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "遇到本机已存在的同名条目时…",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            if (!hasConflicts) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "本次导入未检测到主键冲突，下面的开关此次无效",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        if (strategy == LegadoImporter.ConflictStrategy.SKIP)
                            "保守跳过（推荐）" else "用 Legado 数据覆盖本机",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        if (strategy == LegadoImporter.ConflictStrategy.SKIP)
                            "本机已有的条目原封不动，只追加新内容"
                        else "本机已有的同 ID 条目会被 Legado 那份替换",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = strategy == LegadoImporter.ConflictStrategy.OVERWRITE,
                    onCheckedChange = { checked ->
                        onChange(
                            if (checked) LegadoImporter.ConflictStrategy.OVERWRITE
                            else LegadoImporter.ConflictStrategy.SKIP
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun ResultCard(
    result: LegadoImporter.ImportResult,
    onAnother: () -> Unit,
    onDone: () -> Unit,
) {
    val hasError = result.errors.isNotEmpty()
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (hasError)
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f)
            else MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
        ),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (hasError) Icons.Default.WarningAmber else Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = if (hasError) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (hasError) "导入完成（部分错误）" else "导入完成",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                result.summarize().ifBlank { "没有可导入的内容" },
                style = MaterialTheme.typography.bodySmall,
                lineHeight = MaterialTheme.typography.bodyMedium.lineHeight,
            )
            if (hasError) {
                Spacer(Modifier.height(8.dp))
                result.errors.forEach { e ->
                    Text(
                        "• $e",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            Row {
                OutlinedButton(
                    onClick = onAnother,
                    modifier = Modifier.weight(1f),
                ) { Text("再选一个") }
                Spacer(Modifier.width(10.dp))
                Button(
                    onClick = onDone,
                    modifier = Modifier.weight(1f),
                ) { Text("完成") }
            }
        }
    }
}
