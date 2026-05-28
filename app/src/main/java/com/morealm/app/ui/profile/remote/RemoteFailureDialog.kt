package com.morealm.app.ui.profile.remote

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.morealm.app.domain.sync.FailedImport

/**
 * 失败详情对话框 —— 列出全部 [FailedImport]（path + reason），每条带 [重试] 按钮。
 *
 * 底部两个固定按钮：[全部重试] / [关闭]。失败列表数量大时 LazyColumn 限制最大
 * 高度 360dp 避免对话框撑满屏幕；超出可滚动。
 *
 * @param failures 失败列表；空时调用方不应展开本对话框
 * @param onDismiss 点关闭按钮 / 点对话框外侧
 * @param onRetry 点某条失败的"重试" → 只跑这一条
 * @param onRetryAll 点底部"全部重试" → 跑所有失败条
 */
@Composable
fun RemoteFailureDialog(
    failures: List<FailedImport>,
    onDismiss: () -> Unit,
    onRetry: (FailedImport) -> Unit,
    onRetryAll: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导入失败 (${failures.size})") },
        text = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(failures, key = { it.remotePath }) { failure ->
                    FailureRow(failure = failure, onRetry = { onRetry(failure) })
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onRetryAll) {
                Text("全部重试")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        },
    )
}

@Composable
private fun FailureRow(
    failure: FailedImport,
    onRetry: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = failure.fileName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
            if (!failure.reason.isNullOrBlank()) {
                Text(
                    text = failure.reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                )
            }
        }
        TextButton(onClick = onRetry) {
            Text("重试")
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun RemoteFailureDialogPreview() {
    MaterialTheme {
        RemoteFailureDialog(
            failures = listOf(
                FailedImport(
                    remotePath = "/books/示例文件夹 A/test_demo.epub",
                    fileName = "test_demo.epub",
                    reason = "网络中断",
                ),
                FailedImport(
                    remotePath = "/books/测试 B/示例 LN A.mobi",
                    fileName = "示例 LN A.mobi",
                    reason = "401 鉴权失败",
                ),
            ),
            onDismiss = {},
            onRetry = {},
            onRetryAll = {},
        )
    }
}
