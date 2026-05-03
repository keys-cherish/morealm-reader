package com.morealm.app.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.morealm.app.presentation.search.SearchResult

/**
 * 「加入书架」预览对话框 —— 复刻 Legado-MD3 的 dialog_add_to_bookshelf 设计哲学。
 *
 * 触发：搜索结果项**长按**触发（短按保持现有"立刻进阅读器 + Snackbar 反馈"路径）。
 * 这样把两种交互形态分开：
 *  - 短按 = 果断者：默认行为，最少操作；
 *  - 长按 = 犹豫者：弹 dialog 显示书的元信息，最后核对一次再决定加不加 / 是否立即读。
 *
 * UI 元素（自上而下）：
 *  1. 标题 "加入书架"
 *  2. 大封面 + 书名 + 作者(带 icon) + 来源(带 icon) + 简介摘要
 *  3. 三个动作（M3 AlertDialog 的 confirm / dismiss / neutral 槽位）：
 *     - 取消        → onDismiss
 *     - 仅加入      → onConfirm（仅加，不跳）
 *     - 加入并阅读   → onConfirmAndRead（加 + 跳到阅读器）
 *
 * Dialog 是无状态的（pure UI），加书的副作用 + Snackbar 反馈由调用方在
 * onConfirm/onConfirmAndRead 里走 ViewModel 的 addToShelf / addToShelfAndRead——
 * 与短按路径共用 ShelfAddedEvent，一致的 Snackbar 提示链路。
 */
@Composable
fun AddToShelfDialog(
    result: SearchResult,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    onConfirmAndRead: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "加入书架",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.Top) {
                    // 封面：64×88 — 比搜索列表的 90×128 略小，符合 dialog 紧凑空间约束
                    if (!result.coverUrl.isNullOrBlank()) {
                        AsyncImage(
                            model = result.coverUrl,
                            contentDescription = result.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(width = 64.dp, height = 88.dp)
                                .clip(RoundedCornerShape(6.dp)),
                        )
                    } else {
                        // 无封面回退：占位 box 保持布局稳定，不留白屏
                        Box(
                            modifier = Modifier
                                .size(width = 64.dp, height = 88.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("📖", style = MaterialTheme.typography.titleLarge)
                        }
                    }

                    Spacer(Modifier.width(12.dp))

                    Column(modifier = Modifier.fillMaxWidth()) {
                        // 书名：粗体 titleSmall，最多 2 行折行
                        Text(
                            result.title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(6.dp))
                        // 作者行：icon + 文本
                        if (result.author.isNotBlank()) {
                            MetaRow(
                                icon = Icons.Default.Person,
                                text = result.author,
                            )
                            Spacer(Modifier.height(2.dp))
                        }
                        // 来源行：icon + 文本（书源名称）
                        if (result.sourceName.isNotBlank()) {
                            MetaRow(
                                icon = Icons.Default.Public,
                                text = result.sourceName,
                            )
                        }
                    }
                }

                // 简介摘要 —— 仅在非空时显示。dialog 高度受 M3 内置 max 约束，
                // 长简介自动滚动；这里 maxLines=4 是体感平衡（再多用户也不会读完）。
                val intro = result.intro.takeIf { it.isNotBlank() }
                if (intro != null) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        intro,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        confirmButton = {
            // 主行动 = "加入并阅读"（最常用 = 加完就想看）
            TextButton(onClick = onConfirmAndRead) {
                Text("加入并阅读")
            }
        },
        dismissButton = {
            // 中性区放 "取消" + "仅加入"。M3 AlertDialog 只暴露 confirm/dismiss 两槽位，
            // 这里把两个次级动作并排塞到 dismiss 槽，主次清晰。
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onDismiss) {
                    Text("取消")
                }
                TextButton(onClick = onConfirm) {
                    Text("仅加入")
                }
            }
        },
    )
}

/**
 * 元信息行（icon + text），用于 dialog 中作者 / 来源等标签的统一呈现。
 */
@Composable
private fun MetaRow(
    icon: ImageVector,
    text: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
