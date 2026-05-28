package com.morealm.app.ui.profile.remote

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.morealm.app.domain.entity.BookFormat
import com.morealm.app.domain.sync.RemoteEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 远程书架文件行 —— 显示文件 icon + 名 + FORMAT 小标签 + 「大小 · 时间」+ + 按钮。
 *
 * **正在下载态**：右侧 + 按钮被 [CircularProgressIndicator] 替换；Card 仍可点击但
 * 调用方决定 noop（避免重复下载）。
 *
 * **多选态**：行首 checkbox；整 Card 点击 = toggle 选中而非导入。
 *
 * FORMAT 标签复用项目现有的 primary tonal Surface 风格（参考 ShelfComponents 的
 * 「在线 / 本地」徽章），不引入额外组件库。UNKNOWN 格式不显标签（避免视觉噪音）。
 *
 * @param file 远程书形文件条目
 * @param isDownloading true = 正在 download，右侧显进度指示器
 * @param selectionMode true = 多选模式，行首显 checkbox
 * @param isSelected 多选下当前是否被勾选
 * @param onClick 点 Card：非多选 = 调用方 noop（用 + 按钮主动导入）；多选 = toggle
 * @param onPlusClick 点 + 按钮 → 单本导入（download + importBatch）
 * @param onCheckedChange 多选 checkbox 状态变化
 */
@Composable
fun RemoteFileRow(
    file: RemoteEntry.File,
    isDownloading: Boolean,
    selectionMode: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onPlusClick: () -> Unit,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        ),
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selectionMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = onCheckedChange,
                )
                Spacer(Modifier.width(4.dp))
            }
            Icon(
                Icons.AutoMirrored.Outlined.InsertDriveFile,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = file.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (file.format != BookFormat.UNKNOWN) {
                        FormatBadge(file.format)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        text = formatRowMeta(file),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
            }
            if (isDownloading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                )
            } else if (!selectionMode) {
                IconButton(onClick = onPlusClick) {
                    Icon(
                        Icons.Outlined.AddCircleOutline,
                        contentDescription = "导入此书",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

/** 与 ShelfComponents 中"在线/本地"徽章同款 tonal Surface，labelSmall 字号。 */
@Composable
private fun FormatBadge(format: BookFormat) {
    Surface(
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text = format.name,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

private fun formatRowMeta(file: RemoteEntry.File): String {
    val parts = mutableListOf<String>()
    if (file.size > 0) parts += formatSize(file.size)
    if (file.lastModifiedEpoch > 0) {
        parts += SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            .format(Date(file.lastModifiedEpoch))
    }
    return parts.joinToString(" · ").ifEmpty { "—" }
}

private fun formatSize(bytes: Long): String = when {
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024 -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    else -> "%.2f GB".format(bytes / 1024.0 / 1024.0 / 1024.0)
}

@Preview(showBackground = true, name = "Normal")
@Composable
private fun RemoteFileRowPreview() {
    MaterialTheme {
        RemoteFileRow(
            file = RemoteEntry.File(
                name = "test_demo.epub",
                remotePath = "/books/test_demo.epub",
                size = 2_345_678L,
                lastModifiedEpoch = 1_700_000_000_000L,
                format = BookFormat.EPUB,
            ),
            isDownloading = false,
            selectionMode = false,
            isSelected = false,
            onClick = {},
            onPlusClick = {},
            onCheckedChange = {},
        )
    }
}

@Preview(showBackground = true, name = "Multi-select checked")
@Composable
private fun RemoteFileRowSelectedPreview() {
    MaterialTheme {
        RemoteFileRow(
            file = RemoteEntry.File(
                name = "示例 LN A.mobi",
                remotePath = "/books/示例 LN A.mobi",
                size = 5_000_000L,
                lastModifiedEpoch = 1_705_000_000_000L,
                format = BookFormat.MOBI,
            ),
            isDownloading = false,
            selectionMode = true,
            isSelected = true,
            onClick = {},
            onPlusClick = {},
            onCheckedChange = {},
        )
    }
}

@Preview(showBackground = true, name = "Downloading")
@Composable
private fun RemoteFileRowDownloadingPreview() {
    MaterialTheme {
        RemoteFileRow(
            file = RemoteEntry.File(
                name = "测试作者 A 集.txt",
                remotePath = "/books/测试作者 A 集.txt",
                size = 1_024L,
                lastModifiedEpoch = 0L,
                format = BookFormat.TXT,
            ),
            isDownloading = true,
            selectionMode = false,
            isSelected = false,
            onClick = {},
            onPlusClick = {},
            onCheckedChange = {},
        )
    }
}
