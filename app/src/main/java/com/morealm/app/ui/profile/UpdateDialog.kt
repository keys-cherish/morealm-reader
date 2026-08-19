package com.morealm.app.ui.profile

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import com.morealm.app.BuildConfig
import com.morealm.app.presentation.update.UpdateViewModel

/**
 * 「检查更新」UI —— 点击后展示百度、夸克下载渠道，用户自行取最新安装包。
 *
 * 不做版本检查（详见 [UpdateViewModel]）。链接来自 [BuildConfig]（由 local.properties
 * 注入，不进 git）；某渠道链接为空（未在 local.properties / CI env 配置）时其入口自动隐藏。
 * 百度链接已带 `?pwd=` 提取码，打开后网页 / 客户端通常自动填充。
 *
 * 图标当前用「品牌色圆 + 文字标识」（百度蓝度 / 夸克紫夸），无需图片资源；
 * 若日后要换真品牌 logo，把 [DownloadChannel.badge] 渲染处替换为 painterResource(drawable) 即可。
 */
@Composable
fun UpdateDialogHost(
    state: UpdateViewModel.UiState,
    onDismiss: () -> Unit,
) {
    when (state) {
        UpdateViewModel.UiState.Idle -> Unit
        UpdateViewModel.UiState.ShowChannels -> DownloadChannelsDialog(onDismiss = onDismiss)
    }
}

/** 一个下载渠道：名称 + 链接 + 圆形角标文字 + 品牌色。 */
private data class DownloadChannel(
    val label: String,
    val url: String,
    val badge: String,
    val color: Color,
)

@Composable
private fun DownloadChannelsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    // 链接为空的渠道直接过滤掉（未配置时不显示）。
    val channels = remember {
        listOf(
            DownloadChannel("百度网盘", BuildConfig.PAN_BAIDU_URL, "度", Color(0xFF3385FF)),
            DownloadChannel("夸克网盘", BuildConfig.PAN_QUARK_URL, "夸", Color(0xFF5B6CFF)),
        ).filter { it.url.isNotBlank() }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("下载最新版") },
        text = {
            Column {
                Text(
                    text = if (channels.isEmpty()) {
                        "暂无可用下载渠道，请联系作者获取。"
                    } else {
                        "当前 v${BuildConfig.VERSION_NAME}。选择网盘获取最新安装包："
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                channels.forEach { ch ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 14.dp)
                            .clickable {
                                val intent = Intent(Intent.ACTION_VIEW, ch.url.toUri())
                                    .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                                context.startActivity(intent)
                                onDismiss()
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(ch.color),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = ch.badge,
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = if (ch.badge.length > 1) 11.sp else 17.sp,
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(ch.label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}
