package com.morealm.app.ui.profile

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.morealm.app.presentation.profile.BookmarksViewModel
import com.morealm.app.presentation.profile.BookmarksViewModel.BookmarkItem
import com.morealm.app.presentation.profile.BookmarksViewModel.TimeFilter

/**
 * 全局书签屏。
 *
 * - 顶部 chip：[全部] [今日] [本周] [本月]
 * - 顶栏右侧 ⋯ 菜单：切换"按书分组"
 * - 平铺模式：按 createdAt 倒序，每条卡片显示书名 + 章节 + 摘录 + 时间 + 封面缩略图
 * - 分组模式：按 bookId 折叠，组头显示书名 + 数量，点击展开
 * - 点击卡片：打开 reader/{bookId}（如果书还在）
 * - 长按 / 删除按钮：删除该书签
 *
 * 与 Legado AllBookmarkActivity 思路一致 —— 数据来自 BookmarkRepository.getAll()，
 * book 信息从 BookRepository join。orphan 书签（书已删）保留显示，但点击禁用。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookmarksScreen(
    onBack: () -> Unit,
    onOpenBook: (bookId: String, chapterIndex: Int) -> Unit,
    viewModel: BookmarksViewModel = hiltViewModel(),
) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val groupByBook by viewModel.groupByBook.collectAsStateWithLifecycle()
    var menuExpanded by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("书签 (${items.size})") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(Icons.Default.MoreVert, "更多")
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(if (groupByBook) "切换为平铺" else "按书分组") },
                                leadingIcon = {
                                    Icon(
                                        if (groupByBook) Icons.Default.ViewList else Icons.Default.ViewModule,
                                        null,
                                    )
                                },
                                onClick = {
                                    viewModel.toggleGroupByBook()
                                    menuExpanded = false
                                },
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // 时间过滤 chip
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChipItem("全部", filter == TimeFilter.ALL) { viewModel.setFilter(TimeFilter.ALL) }
                FilterChipItem("今日", filter == TimeFilter.TODAY) { viewModel.setFilter(TimeFilter.TODAY) }
                FilterChipItem("本周", filter == TimeFilter.WEEK) { viewModel.setFilter(TimeFilter.WEEK) }
                FilterChipItem("本月", filter == TimeFilter.MONTH) { viewModel.setFilter(TimeFilter.MONTH) }
            }

            if (items.isEmpty()) {
                EmptyState()
            } else if (groupByBook) {
                GroupedList(items = items, onOpenBook = onOpenBook, onDelete = viewModel::deleteBookmark)
            } else {
                FlatList(items = items, onOpenBook = onOpenBook, onDelete = viewModel::deleteBookmark)
            }
        }
    }
}

@Composable
private fun RowScope.FilterChipItem(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
    )
}

@Composable
private fun EmptyState() {
    Box(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Default.Bookmark,
                null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "还没有书签",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "在阅读器里点书签按钮即可添加",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
            )
        }
    }
}

@Composable
private fun FlatList(
    items: List<BookmarkItem>,
    onOpenBook: (String, Int) -> Unit,
    onDelete: (String) -> Unit,
) {
    LazyColumn(
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(items, key = { it.bookmark.id }) { item ->
            BookmarkCard(item = item, onOpen = onOpenBook, onDelete = onDelete)
        }
        item { Spacer(Modifier.height(80.dp)) }
    }
}

@Composable
private fun GroupedList(
    items: List<BookmarkItem>,
    onOpenBook: (String, Int) -> Unit,
    onDelete: (String) -> Unit,
) {
    val groups = remember(items) {
        items.groupBy { it.bookmark.bookId }
            .toList()
            .sortedByDescending { it.second.firstOrNull()?.bookmark?.createdAt ?: 0L }
    }
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    LazyColumn(
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for ((bookId, list) in groups) {
            val first = list.first()
            val open = expanded[bookId] ?: true
            item(key = "h_$bookId") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { expanded[bookId] = !open }
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        first.bookTitle,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${list.size} 条",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
            }
            if (open) {
                items(list, key = { it.bookmark.id }) { item ->
                    BookmarkCard(item = item, onOpen = onOpenBook, onDelete = onDelete, compact = true)
                }
            }
        }
        item { Spacer(Modifier.height(80.dp)) }
    }
}

@Composable
private fun BookmarkCard(
    item: BookmarkItem,
    onOpen: (String, Int) -> Unit,
    onDelete: (String) -> Unit,
    compact: Boolean = false,
) {
    val bm = item.bookmark
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = item.bookExists) {
                onOpen(bm.bookId, bm.chapterIndex)
            },
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier.padding(if (compact) 10.dp else 12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            // 封面缩略图（紧凑模式不显示）
            if (!compact) {
                AsyncImage(
                    model = item.coverUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(width = 44.dp, height = 60.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                )
                Spacer(Modifier.width(12.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                if (!compact) {
                    Text(
                        item.bookTitle + if (!item.bookExists) "（已删除）" else "",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = if (item.bookExists)
                            MaterialTheme.colorScheme.onSurface
                        else
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    )
                    Spacer(Modifier.height(2.dp))
                }
                Text(
                    bm.chapterTitle.ifBlank { "第 ${bm.chapterIndex + 1} 章" },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (bm.content.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        bm.content,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    DateUtils.getRelativeTimeSpanString(
                        bm.createdAt,
                        System.currentTimeMillis(),
                        DateUtils.MINUTE_IN_MILLIS,
                    ).toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                )
            }
            IconButton(onClick = { onDelete(bm.id) }) {
                Icon(
                    Icons.Default.Delete,
                    "删除",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                )
            }
        }
    }
}
