package com.morealm.app.ui.source

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.morealm.app.core.log.AppLog
import com.morealm.app.presentation.source.ChangeSourceCandidate
import com.morealm.app.presentation.source.ChangeSourceProgress
import com.morealm.app.presentation.source.SearchStatus

/**
 * 换源对话框 —— 详情页与阅读器共用。
 *
 * 从 BookDetailScreen 原地抽出，行为逐像素保持不变；两处 UI 坑的注释一并搬来，
 * 它们各自对应一个用户报过的真实 bug，别在「清理代码」时删掉。
 *
 * @param currentSourceUrl 当前书源 url，用于把候选里的当前源标灰并禁用点击。
 */
@Composable
fun ChangeSourceDialog(
    candidates: List<ChangeSourceCandidate>,
    progress: List<ChangeSourceProgress>,
    isSearching: Boolean,
    currentSourceUrl: String?,
    onRefresh: () -> Unit,
    onApply: (ChangeSourceCandidate) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            // Row 而非 Column：title 区右侧塞一个「刷新」按钮，让用户在缓存
            // 30 分钟窗口内（默认走 cache）也能强制重搜。书名一行 + 进度一行
            // 改为左侧 Column.weight(1f)，避免被按钮挤丢副标题。
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("换源 · 搜索其他书源")
                    val total = progress.size
                    val done = remember(progress) {
                        progress.count { it.status == SearchStatus.DONE || it.status == SearchStatus.FAILED }
                    }
                    if (total > 0) {
                        Text(
                            "已搜索 $done/$total · 找到 ${candidates.size} 个候选",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else if (candidates.isNotEmpty()) {
                        // 进度行为空但已有候选 = 走了缓存窗口短路。给用户一个明确反馈，
                        // 否则会怀疑「为啥这么快」/「是不是没真搜」。
                        Text(
                            "缓存 ${candidates.size} 条候选 · 点刷新可重新搜索",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                IconButton(onClick = onRefresh, enabled = !isSearching) {
                    Icon(Icons.Filled.Refresh, contentDescription = "重新搜索")
                }
            }
        },
        text = {
            Column(modifier = Modifier.heightIn(max = 480.dp)) {
                if (isSearching && candidates.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
                    }
                }
                if (candidates.isEmpty() && !isSearching) {
                    Text(
                        "没有在其他书源中找到该书。\n可能是书源关闭了/搜索规则不匹配。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 16.dp),
                    )
                }
                // 候选列表 —— 用 Column + verticalScroll 而不是 LazyColumn。
                // 历史教训：M3 AlertDialog 的 text slot 默认 wrap content + max height，
                // **不会**自动滚动（旧注释把它写成"自带 verticalScroll"是错的，
                // 用户报「列表点不到下面的源」就是因为外层只 heightIn(max=480.dp)、
                // 内层没滚动 → 超出部分被裁掉无法滑）。LazyColumn 在 unbounded
                // 高度下又只渲 1-2 项 + 嵌套滚动手势冲突，所以最稳的做法是
                // 「外层 heightIn 限高 + 内层 Column.verticalScroll」。候选 ≤50 条，
                // 一次性渲染开销可忽略。
                Column(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                ) {
                    for (c in candidates) {
                        val isCurrent = c.sourceUrl == currentSourceUrl
                        // 关键：用 Surface(onClick=...) 而非 Modifier.clickable —— Material3
                        // 推荐写法，会自动接入 minimumInteractiveComponentSize +
                        // interactionSource，在 AlertDialog 这种嵌套滚动 / 触摸目标受限的
                        // 容器里点击命中率更高。历史 bug：用户报"点 52书库 没反应"，
                        // 复现条件包含 candidate 多到 480.dp 之外被半截渲染、Surface 被
                        // 父级 verticalScroll 偷走 ACTION_DOWN，等等。改 onClick 形式后，
                        // 同时 enabled 显式置 false 时连 ripple 都不会触发，UI 反馈更清晰。
                        Surface(
                            onClick = {
                                AppLog.info(
                                    "ChangeSource",
                                    "candidate clicked: name=${c.sourceName} url=${c.sourceUrl}"
                                )
                                onApply(c)
                            },
                            enabled = !isCurrent,
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.small,
                            color = if (isCurrent) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        c.sourceName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                        color = if (isCurrent) MaterialTheme.colorScheme.primary
                                                else MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.weight(1f),
                                    )
                                    if (isCurrent) {
                                        Text(
                                            "当前",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                                val sb = c.searchBook
                                val sub = buildString {
                                    if (sb.author.isNotBlank()) append(sb.author)
                                    sb.latestChapterTitle?.takeIf { it.isNotBlank() }?.let {
                                        if (isNotEmpty()) append(" · ")
                                        append("最新: ").append(it)
                                    }
                                    sb.wordCount?.takeIf { it.isNotBlank() }?.let {
                                        if (isNotEmpty()) append(" · ")
                                        append(it)
                                    }
                                }
                                if (sub.isNotEmpty()) {
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        sub,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}
