package com.morealm.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.FindReplace
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.morealm.app.presentation.home.HomeViewModel
import com.morealm.app.ui.library.LibraryBookItem
import kotlinx.coroutines.delay

private const val COLUMNS = 3

/**
 * 首页只保留一行最近阅读，并提供最常用的功能入口。
 * 书架完整功能在独立书架 tab；书目卡复用图书馆的 [LibraryBookItem]。
 */
@Composable
fun HomeScreen(
    onBookClick: (String) -> Unit,
    onNavigateReadingSettings: () -> Unit,
    onNavigateBookmarks: () -> Unit,
    onNavigateCacheBook: () -> Unit,
    onNavigateReplaceRules: () -> Unit,
    onNavigateAppearance: () -> Unit,
    onNavigateAppLog: () -> Unit,
    /** 外部「继续阅读」请求计数（通知/桌面快捷方式），递增即打开最近一本。 */
    continueReadingRequest: Int = 0,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val lastRead by viewModel.lastReadBook.collectAsStateWithLifecycle()
    val recent by viewModel.recentBooks.collectAsStateWithLifecycle()
    val quickActions = listOf(
        HomeQuickAction(Icons.Default.MenuBook, "阅读设置", onNavigateReadingSettings),
        HomeQuickAction(Icons.Default.Bookmark, "我的书签", onNavigateBookmarks),
        HomeQuickAction(Icons.Default.CloudDownload, "离线缓存", onNavigateCacheBook),
        HomeQuickAction(Icons.Default.FindReplace, "正文净化", onNavigateReplaceRules),
        HomeQuickAction(Icons.Default.Wallpaper, "外观设置", onNavigateAppearance),
        HomeQuickAction(Icons.Default.BugReport, "应用日志", onNavigateAppLog),
    )

    // 续读请求消费（原 ShelfScreen 逻辑迁来：书架 tab 非默认组合页后由首页承接）
    var handledContinueRequest by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(continueReadingRequest, lastRead) {
        val book = lastRead
        if (continueReadingRequest > 0 && continueReadingRequest != handledContinueRequest && book != null) {
            handledContinueRequest = continueReadingRequest
            delay(250)
            onBookClick(book.id)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(COLUMNS),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 120.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "recent_title", span = { GridItemSpan(maxLineSpan) }) {
                SectionTitle(
                    text = "最近阅读",
                    modifier = Modifier.padding(top = 22.dp),
                )
            }
            if (recent.isNotEmpty()) {
                items(
                    count = recent.size,
                    key = { i -> "r_${recent[i].id}" },
                ) { i ->
                    val book = recent[i]
                    LibraryBookItem(book = book, onClick = { onBookClick(book.id) })
                }
            } else {
                item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        text = "还没有阅读记录，去书架挑一本开始吧",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 28.dp),
                    )
                }
            }

            item(key = "quick_actions_title", span = { GridItemSpan(maxLineSpan) }) {
                SectionTitle(
                    text = "常用功能",
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            items(
                count = quickActions.size,
                key = { index -> quickActions[index].label },
            ) { index ->
                val action = quickActions[index]
                QuickActionCard(action = action)
            }
        }
    }
}

private data class HomeQuickAction(
    val icon: ImageVector,
    val label: String,
    val onClick: () -> Unit,
)

@Composable
private fun SectionTitle(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.92f),
        modifier = modifier,
    )
}

@Composable
private fun QuickActionCard(action: HomeQuickAction) {
    Surface(
        onClick = action.onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(88.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = action.icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = action.label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
        }
    }
}
