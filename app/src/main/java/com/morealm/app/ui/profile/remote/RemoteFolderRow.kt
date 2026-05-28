package com.morealm.app.ui.profile.remote

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.morealm.app.domain.sync.RemoteEntry

/** 文件夹图标的橄榄绿色调，与截图的暖色文件夹一致 —— olive 700 (#7CB342)。 */
private val FolderIconTint = Color(0xFF7CB342)

/**
 * 远程书架文件夹行 —— 整 Card 点击 = 进入文件夹（pathStack push）；右侧 + 按钮 =
 * 整夹递归导入（决策见 prd Q3 = 文件夹也有 +）；多选模式下行首显示 checkbox，
 * 整 Card 点击改为 toggle 选中（不进入）。
 *
 * 按截图配色：surfaceVariant alpha 0.4f 弱化卡片背景（不抢眼），文件夹 icon
 * 橄榄绿 [FolderIconTint] 与截图一致。点击区域：整个 Card 是主要交互区，+ 按钮
 * 是次要快捷键，checkbox 仅多选模式可见。
 *
 * @param folder 远程目录条目
 * @param selectionMode true = 多选模式，行首显 checkbox + 点击 = toggle
 * @param isSelected 多选模式下当前是否被勾选
 * @param onClick 点 Card：非多选 = 进入文件夹；多选 = toggle
 * @param onPlusClick 点右侧 + 按钮：整夹递归导入；多选下隐藏（用底部 FAB 替代）
 * @param onCheckedChange 多选模式 checkbox 状态变化
 */
@Composable
fun RemoteFolderRow(
    folder: RemoteEntry.Folder,
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
                Icons.Default.Folder,
                contentDescription = null,
                tint = FolderIconTint,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = folder.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (!selectionMode) {
                IconButton(onClick = onPlusClick) {
                    Icon(
                        Icons.Outlined.AddCircleOutline,
                        contentDescription = "导入整个文件夹",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true, name = "Normal")
@Composable
private fun RemoteFolderRowPreview() {
    MaterialTheme {
        RemoteFolderRow(
            folder = RemoteEntry.Folder(
                name = "示例文件夹 A",
                remotePath = "/books/示例文件夹 A",
                lastModifiedEpoch = 0L,
            ),
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
private fun RemoteFolderRowSelectedPreview() {
    MaterialTheme {
        RemoteFolderRow(
            folder = RemoteEntry.Folder(
                name = "测试 B",
                remotePath = "/books/测试 B",
                lastModifiedEpoch = 0L,
            ),
            selectionMode = true,
            isSelected = true,
            onClick = {},
            onPlusClick = {},
            onCheckedChange = {},
        )
    }
}
