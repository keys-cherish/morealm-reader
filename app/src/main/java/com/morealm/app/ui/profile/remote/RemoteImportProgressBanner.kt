package com.morealm.app.ui.profile.remote

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.morealm.app.domain.sync.ImportState

/**
 * 远程书架导入进度条 —— 复用 [ImportStateBus] 的进度状态。
 *
 * 仅在 `state` 非 null 且非终态（[ImportState.Done] / [ImportState.Error] /
 * [ImportState.Idle]）时显示；终态由调用方自行 dismiss（通常停 N 秒后清空）。
 *
 * 视觉：顶部 tonal Card，含进度文字 + LinearProgressIndicator + Cancel 按钮。
 * Cancel 触发 [com.morealm.app.domain.sync.ImportStateBus.requestCancel]，
 * ImportEngine 在 chunk 边界停止后续 chunk。
 *
 * @param state 当前导入状态；null = 隐藏 banner
 * @param onCancel 用户点取消按钮
 */
@Composable
fun RemoteImportProgressBanner(
    state: ImportState?,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state == null || state is ImportState.Idle || state is ImportState.Done || state is ImportState.Error) {
        return
    }
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = formatStateLine(state),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                val progress = computeProgress(state)
                if (progress != null) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                    )
                }
            }
            IconButton(onClick = onCancel) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "取消导入",
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

/** 把 ImportState 翻译成人话进度行。 */
private fun formatStateLine(state: ImportState): String = when (state) {
    is ImportState.Scanning -> "正在扫描 ${state.folderName}…"
    is ImportState.Phase1 -> {
        val cancelHint = if (state.cancelled) " (正在取消…)" else ""
        "正在导入 ${state.imported}/${state.total} — ${state.folderName}$cancelHint"
    }
    is ImportState.Phase2 -> "正在补元数据 ${state.enriched}/${state.total} — ${state.folderName}"
    else -> ""  // Done / Error / Idle 已在调用方过滤
}

/** Determinate progress (Phase1/Phase2)；Scanning 阶段 total 未知返 null。 */
private fun computeProgress(state: ImportState): Float? = when (state) {
    is ImportState.Phase1 -> if (state.total > 0) state.imported.toFloat() / state.total else null
    is ImportState.Phase2 -> if (state.total > 0) state.enriched.toFloat() / state.total else null
    else -> null
}

@Preview(showBackground = true, name = "Phase1")
@Composable
private fun RemoteImportProgressBannerPhase1Preview() {
    MaterialTheme {
        RemoteImportProgressBanner(
            state = ImportState.Phase1(
                folderName = "示例文件夹 A",
                imported = 12,
                total = 87,
            ),
            onCancel = {},
        )
    }
}

@Preview(showBackground = true, name = "Scanning")
@Composable
private fun RemoteImportProgressBannerScanningPreview() {
    MaterialTheme {
        RemoteImportProgressBanner(
            state = ImportState.Scanning(folderName = "测试 B"),
            onCancel = {},
        )
    }
}
