package com.morealm.app.ui.profile

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.morealm.app.BuildConfig
import com.morealm.app.domain.update.Pan123Info
import com.morealm.app.domain.update.UpdateResult
import com.morealm.app.presentation.update.UpdateViewModel

/**
 * 「检查更新」UI 总入口：根据 [UpdateViewModel.UiState] 决定弹 Dialog 还是 Snackbar。
 *
 * 设计：
 *  - **正在检查 / 已是最新 / 检查失败**：用 Snackbar 一行字提示，不打断用户。
 *  - **有新版本**：用 AlertDialog 展示版本号 + release notes 摘要 + 下载入口
 *    （GitHub 必有；123 网盘当 [UpdateResult.Available.pan123] 非空时也显示）。
 *
 * Snackbar 共用 [ProfileScreen] 主 Snackbar 主机，避免叠两个 host。
 *
 * 「GitHub 下载」走 [Intent.ACTION_VIEW] 打开 release html_url；「123 网盘下载」
 * 同样走 ACTION_VIEW 打开 share URL，若 manifest 提供了提取码会自动复制到剪贴板
 * 并 Toast 提示——用户在 123 网盘网页粘贴即可。
 */
@Composable
fun UpdateDialogHost(
    state: UpdateViewModel.UiState,
    onDismiss: () -> Unit,
    snackbarHost: SnackbarHostState,
) {
    when (state) {
        UpdateViewModel.UiState.Idle -> Unit

        UpdateViewModel.UiState.Checking -> {
            // 一次性 Snackbar 提示「正在检查」。LaunchedEffect 以 state 为 key，
            // 同一次检查只触发一次，避免 recompose 时反复弹。
            LaunchedEffect(state) {
                snackbarHost.showSnackbar("正在检查更新…")
            }
        }

        is UpdateViewModel.UiState.HasResult -> when (val r = state.result) {
            UpdateResult.UpToDate -> {
                LaunchedEffect(state) {
                    snackbarHost.showSnackbar("当前已是最新版本 v${BuildConfig.VERSION_NAME}")
                    onDismiss()
                }
            }
            is UpdateResult.Failed -> {
                LaunchedEffect(state) {
                    snackbarHost.showSnackbar("检查失败：${r.reason}")
                    onDismiss()
                }
            }
            is UpdateResult.Available -> {
                AvailableDialog(result = r, onDismiss = onDismiss)
            }
        }
    }
}

@Composable
private fun AvailableDialog(
    result: UpdateResult.Available,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "发现新版本 v${result.latestVersion}" +
                    if (result.isPrerelease) "（预发布）" else "",
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = "当前 v${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
                Spacer(Modifier.height(12.dp))
                // release body 多为 markdown，本期暂以纯文本渲染（开发者写的内容可控，
                // 不至于把 # 标题当字面字符不可读）。markdown 渲染留独立任务。
                Text(
                    text = result.body.ifBlank { result.title },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            // confirmButton 槽位横向排两个下载按钮：GitHub 始终在；123 网盘按 manifest
            // 配置存在时显示。AlertDialog 的标准 button 行宽度有限，两个 TextButton
            // + dismissButton 在 360dp 屏宽下也放得下；如果未来要加更多源（蓝奏 / 阿里 ...）
            // 再考虑改成 dropdown menu。
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, result.htmlUrl.toUri()).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    onDismiss()
                }) {
                    Text(if (result.pan123 != null) "GitHub" else "去下载")
                }
                result.pan123?.let { pan ->
                    TextButton(onClick = {
                        openPan123(context, pan)
                        onDismiss()
                    }) {
                        Text(pan.label.ifBlank { "123 网盘" })
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("稍后再说")
            }
        },
    )
}

/**
 * 打开 123 网盘 share URL，若 manifest 配置了提取码则同步复制到剪贴板并 Toast。
 *
 * 之所以不直接拼提取码到 URL：123 网盘的 share URL 不支持 query string 自动填提取码
 * （网页端是单独的输入框）。剪贴板 + Toast 是当前最低摩擦的引导路径。
 */
private fun openPan123(context: Context, pan: Pan123Info) {
    if (pan.password.isNotBlank()) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText("MoRealm 123 网盘提取码", pan.password))
        Toast.makeText(context, "已复制提取码 ${pan.password}", Toast.LENGTH_LONG).show()
    }
    val intent = Intent(Intent.ACTION_VIEW, pan.shareUrl.toUri()).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
}
