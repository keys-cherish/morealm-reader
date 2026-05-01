package com.morealm.app.ui.source

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.morealm.app.domain.webbook.CheckSource

/**
 * CheckSource 完成后展示失效书源 + 询问删除（任务 #2）。
 *
 * 行为：
 *  - 仅当 ViewModel 检测到 invalid > 0 时由 SourceManageViewModel 触发显示。
 *  - 默认全选所有失效书源；用户可逐条取消勾选保留某些（如临时挂掉的源）。
 *  - 「删除选中 (N)」 → ViewModel.deleteInvalidSources(selectedUrls) 走 db 删除。
 *  - 「稍后处理」 → 关闭弹窗；errorMsg 已持久化到 BookSource，源管理页角标仍可看到。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CheckResultsDialog(
    invalidResults: List<CheckSource.CheckResult>,
    onDismiss: () -> Unit,
    onDelete: (selectedSourceUrls: Set<String>) -> Unit,
) {
    /**
     * 选中状态：默认全部勾选。键 = sourceUrl（与 BookSource 主键对齐）。
     * 用 mutableStateMapOf 让单条 toggle 不会重组整个 LazyColumn。
     */
    val selectedMap = remember(invalidResults) {
        mutableStateMapOf<String, Boolean>().apply {
            for (r in invalidResults) put(r.sourceUrl, true)
        }
    }
    val selectedCount = selectedMap.count { it.value }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.ErrorOutline,
                    null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text("校验完成", fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column {
                Text(
                    "${invalidResults.size} 个书源校验失败，是否删除？",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(
                    modifier = Modifier.heightIn(max = 380.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(invalidResults, key = { it.sourceUrl }) { result ->
                        InvalidResultRow(
                            result = result,
                            checked = selectedMap[result.sourceUrl] ?: false,
                            onCheckedChange = { selectedMap[result.sourceUrl] = it },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = selectedCount > 0,
                onClick = {
                    val urls = selectedMap.filter { it.value }.keys.toSet()
                    onDelete(urls)
                },
            ) {
                Text(
                    if (selectedCount > 0) "删除选中 ($selectedCount)" else "删除选中",
                    color = if (selectedCount > 0) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                    },
                    fontWeight = FontWeight.Bold,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("稍后处理", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
        },
    )
}

@Composable
private fun InvalidResultRow(
    result: CheckSource.CheckResult,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Surface(
        onClick = { onCheckedChange(!checked) },
        shape = RoundedCornerShape(8.dp),
        color = if (checked) {
            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = CheckboxDefaults.colors(
                    checkedColor = MaterialTheme.colorScheme.error,
                ),
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.width(4.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    result.sourceName.ifBlank { result.sourceUrl },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    result.error?.take(120) ?: "未知错误",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
