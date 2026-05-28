package com.morealm.app.ui.profile.remote

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/**
 * 失败汇总 banner —— 顶部 errorContainer 色块 Card，含「N 本失败」+ [重试] [详情] 按钮。
 *
 * 与 [RemoteImportProgressBanner] 互补：进度 banner 显示**正在跑**的导入进度；
 * 失败 banner 显示**已结束**的失败集合（用户决定重试或查看详情）。两者可同时显示
 * （比如批量导入有部分失败但还在继续）。
 *
 * @param failedCount 失败本数；0 时调用方应不渲染本组件（本组件不自检 0）
 * @param onRetry 点重试 → ViewModel.retryFailed()
 * @param onShowDetail 点详情 → 弹 [RemoteFailureDialog]
 */
@Composable
fun RemoteFailureBanner(
    failedCount: Int,
    onRetry: () -> Unit,
    onShowDetail: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                Icons.Default.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                text = "$failedCount 本失败",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRetry) {
                Text(
                    "重试",
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            TextButton(onClick = onShowDetail) {
                Text(
                    "详情",
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun RemoteFailureBannerPreview() {
    MaterialTheme {
        RemoteFailureBanner(
            failedCount = 12,
            onRetry = {},
            onShowDetail = {},
        )
    }
}
