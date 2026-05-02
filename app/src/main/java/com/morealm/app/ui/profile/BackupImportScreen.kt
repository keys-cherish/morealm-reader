package com.morealm.app.ui.profile

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.morealm.app.presentation.profile.ProfileViewModel

/**
 * 导入备份选项页（与 [BackupExportScreen] 对称）。
 *
 * 流程：
 *  1. 进入页 — 还没选 zip，显示「请选择备份文件」+ 选择按钮（OpenDocument）。
 *  2. SAF 选完 → 调 ProfileViewModel.loadRestoreSections(uri) — 解密 / 解 zip / 解析
 *     生成预览：每段 itemCount + conflictCount；解析失败显示错误卡片 + 「重新选择」。
 *  3. 用户勾选要恢复的类别（默认全选）；底部「开始恢复」打开二次确认对话框，
 *     列出『将恢复 N 类，覆盖 M 条本地数据』。确认后调 runImportWithSelections。
 *  4. 结果（成功/失败 + 失败原因）统一由 BackupStatusBus → ProfileScreen 全局
 *     Toast 监听器吐出，本页不重复显示；恢复后用户可继续选择新 zip 或返回。
 *
 * 设计取舍：
 *  - 不在本页显示 backupStatus —— 与 BackupExportScreen 一致，避免和全局 Toast 重复。
 *  - 二次确认弹窗作为强烈提示，因为「恢复」是覆盖性操作；export 屏没有此弹窗。
 *  - SAF mime 类型同时接受 application/zip 和 application/octet-stream
 *    （加密备份的 .zip 在某些系统上被识别成 octet-stream）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupImportScreen(
    onBack: () -> Unit,
    profileViewModel: ProfileViewModel = hiltViewModel(),
) {
    val sections by profileViewModel.restoreSections.collectAsStateWithLifecycle()
    val selections by profileViewModel.restoreSelections.collectAsStateWithLifecycle()
    val loading by profileViewModel.restoreSectionsLoading.collectAsStateWithLifecycle()
    val pendingUri by profileViewModel.restorePendingUri.collectAsStateWithLifecycle()
    val previewError by profileViewModel.restorePreviewError.collectAsStateWithLifecycle()
    val passwordOverride by profileViewModel.restorePasswordOverride.collectAsStateWithLifecycle()
    val savedPassword by profileViewModel.backupPassword.collectAsStateWithLifecycle()

    var showConfirm by remember { mutableStateOf(false) }
    var passwordVisible by remember { mutableStateOf(false) }

    val pickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { profileViewModel.loadRestoreSections(it) }
    }

    val selectedCount = remember(sections, selections) {
        sections.count { it.key in selections }
    }
    val totalConflicts = remember(sections, selections) {
        sections.filter { it.key in selections }.sumOf { it.conflictCount }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("导入备份", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
        bottomBar = {
            // 底部主操作条 —— 仅在已选 zip 且至少勾了一项时启用。
            // 与 BackupExportScreen 一致的视觉：左摘要 + 右主按钮。
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
                        if (pendingUri == null) {
                            Text(
                                "未选择文件",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                            )
                        } else {
                            Text(
                                "已选 $selectedCount / ${sections.size} 项",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                            )
                            if (totalConflicts > 0) {
                                Text(
                                    "将覆盖 $totalConflicts 条本地数据",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                        }
                    }
                    Button(
                        enabled = pendingUri != null && selectedCount > 0 && !loading,
                        onClick = { showConfirm = true },
                    ) {
                        Text("开始恢复")
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
            // 选择文件卡片：始终在顶部，方便用户重新选另一个 zip
            FilePickCard(
                pendingUriDisplay = pendingUri?.lastPathSegment ?: pendingUri?.toString(),
                onPick = {
                    pickerLauncher.launch(arrayOf("application/zip", "application/octet-stream"))
                },
            )

            // 密码卡片：加密备份必填；非加密备份留空。预填 prefs 里的备份密码 —
            // 跟用户日常导出加密时用的 [backupPassword] 是一致的，正常情况一次到位；
            // 用户也可以临时改用别的密码（比如恢复别人发来的加密 zip），改动只在
            // 本次会话生效，不会写回 prefs。
            PasswordCard(
                password = passwordOverride.ifEmpty { savedPassword },
                visible = passwordVisible,
                onToggleVisible = { passwordVisible = !passwordVisible },
                onChange = { profileViewModel.setRestorePasswordOverride(it) },
                onApply = {
                    if (pendingUri != null) profileViewModel.reloadRestorePreview()
                },
                applyEnabled = pendingUri != null && !loading,
            )

            when {
                pendingUri == null && !loading -> {
                    EmptyStateCard("先选择一个备份文件，再决定要恢复哪些数据")
                }

                loading && sections.isEmpty() -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(48.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }

                previewError != null -> {
                    ErrorCard(
                        message = previewError ?: "未知错误",
                        onRetry = {
                            pickerLauncher.launch(arrayOf("application/zip", "application/octet-stream"))
                        },
                    )
                }

                sections.isEmpty() -> {
                    EmptyStateCard("此备份文件没有可识别的内容")
                }

                else -> {
                    // 全选 / 全不选
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = { profileViewModel.selectAllRestoreSections() },
                            enabled = !loading && sections.isNotEmpty(),
                            modifier = Modifier.weight(1f),
                        ) { Text("全选") }
                        OutlinedButton(
                            onClick = { profileViewModel.clearRestoreSelections() },
                            enabled = !loading && sections.isNotEmpty(),
                            modifier = Modifier.weight(1f),
                        ) { Text("全不选") }
                    }

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
                                        .clickable { profileViewModel.toggleRestoreSection(info.key) }
                                        .padding(horizontal = 16.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Checkbox(
                                        checked = checked,
                                        onCheckedChange = { profileViewModel.toggleRestoreSection(info.key) },
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
                                    if (info.conflictCount > 0) {
                                        // 「将覆盖 N 条」徽章 —— 用 error 色让用户警觉。
                                        // 0 冲突时不显示徽章（避免一片绿）。
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = MaterialTheme.colorScheme.errorContainer,
                                        ) {
                                            Text(
                                                "覆盖 ${info.conflictCount}",
                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.onErrorContainer,
                                                fontWeight = FontWeight.Medium,
                                            )
                                        }
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
            }

            Spacer(Modifier.height(16.dp))
        }
    }

    if (showConfirm) {
        ConfirmRestoreDialog(
            selectedCount = selectedCount,
            totalConflicts = totalConflicts,
            categoryLabels = sections.filter { it.key in selections }.map { it.label },
            onConfirm = {
                showConfirm = false
                profileViewModel.runImportWithSelections()
                onBack()
            },
            onCancel = { showConfirm = false },
        )
    }
}

@Composable
private fun FilePickCard(
    pendingUriDisplay: String?,
    onPick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .clickable { onPick() },
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
        ),
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.FolderOpen,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (pendingUriDisplay == null) "选择备份文件" else "已选择",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    pendingUriDisplay ?: "支持 ZIP 格式，含密码的备份会用「备份密码」自动解密",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    maxLines = 2,
                )
            }
            TextButton(onClick = onPick) {
                Text(if (pendingUriDisplay == null) "选择" else "更换")
            }
        }
    }
}

@Composable
private fun PasswordCard(
    password: String,
    visible: Boolean,
    onToggleVisible: () -> Unit,
    onChange: (String) -> Unit,
    onApply: () -> Unit,
    applyEnabled: Boolean,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "备份密码",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                "加密备份必填，未加密留空。改动仅本次有效，不写回设置。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = password,
                onValueChange = onChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = if (visible) VisualTransformation.None
                    else PasswordVisualTransformation(),
                placeholder = { Text("加密时填解密密码") },
                trailingIcon = {
                    IconButton(onClick = onToggleVisible) {
                        Icon(
                            if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (visible) "隐藏密码" else "显示密码",
                        )
                    }
                },
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onApply, enabled = applyEnabled) {
                    Text("应用密码并重新解析")
                }
            }
        }
    }
}

@Composable
private fun EmptyStateCard(message: String) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Text(
            message,
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
    }
}

@Composable
private fun ErrorCard(message: String, onRetry: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.WarningAmber,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "无法解析备份",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.85f),
            )
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = onRetry) {
                Text("重新选择文件")
            }
        }
    }
}

@Composable
private fun ConfirmRestoreDialog(
    selectedCount: Int,
    totalConflicts: Int,
    categoryLabels: List<String>,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = {
            Text("确认开始恢复？")
        },
        text = {
            Column {
                Text("将恢复 $selectedCount 类数据：")
                Spacer(Modifier.height(8.dp))
                // 列出具体类别 —— 让用户最后一眼能看到自己勾的是不是正确的几项
                Text(
                    categoryLabels.joinToString("、"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                )
                if (totalConflicts > 0) {
                    Spacer(Modifier.height(12.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.errorContainer)
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        Text(
                            "其中 $totalConflicts 条本地数据将被覆盖（不可撤销）",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = if (totalConflicts > 0) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary,
                ),
            ) {
                Text("确认恢复", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text("取消")
            }
        },
    )
}
