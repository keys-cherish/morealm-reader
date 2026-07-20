package com.morealm.app.ui.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Search
import com.morealm.app.R
import com.morealm.app.domain.entity.Book
import com.morealm.app.domain.repository.DailyQuote
import com.morealm.app.presentation.home.HomeViewModel
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val COLUMNS = 3

/**
 * 首页只保留一行最近阅读，并提供最常用的功能入口。
 * 完整阅读历史只包含真正打开过的书，与可能包含未读书的书架列表保持独立。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onBookClick: (String) -> Unit,
    onNavigateSearch: () -> Unit,
    onNavigateReadingSettings: () -> Unit,
    onNavigateBookmarks: () -> Unit,
    onNavigateCacheBook: () -> Unit,
    onNavigateReplaceRules: () -> Unit,
    onNavigateAppearance: () -> Unit,
    onNavigateAppLog: () -> Unit,
    isEinkTheme: Boolean = false,
    /** 外部「继续阅读」请求计数（通知/桌面快捷方式），递增即打开最近一本。 */
    continueReadingRequest: Int = 0,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val lastRead by viewModel.lastReadBook.collectAsStateWithLifecycle()
    val recent by viewModel.recentBooks.collectAsStateWithLifecycle()
    val history by viewModel.readingHistory.collectAsStateWithLifecycle()
    val dailyQuote by viewModel.dailyQuote.collectAsStateWithLifecycle()
    var showReadingHistory by rememberSaveable { mutableStateOf(false) }
    val quickActions = listOf(
        HomeQuickAction("阅读设置", onNavigateReadingSettings),
        HomeQuickAction("我的书签", onNavigateBookmarks),
        HomeQuickAction("离线缓存", onNavigateCacheBook),
        HomeQuickAction("正文净化", onNavigateReplaceRules),
        HomeQuickAction("外观设置", onNavigateAppearance),
        HomeQuickAction("应用日志", onNavigateAppLog),
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
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "welcome", span = { GridItemSpan(maxLineSpan) }) {
                HomeWelcomeHeader(
                    onSearchClick = onNavigateSearch,
                    modifier = Modifier.padding(top = 20.dp),
                )
            }
            item(key = "daily_quote", span = { GridItemSpan(maxLineSpan) }) {
                DailyQuoteCard(
                    quote = dailyQuote,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            item(key = "quick_actions", span = { GridItemSpan(maxLineSpan) }) {
                QuickActionsBar(
                    actions = quickActions,
                    isEinkTheme = isEinkTheme,
                )
            }
            item(key = "recent_title", span = { GridItemSpan(maxLineSpan) }) {
                ContinueReadingHeader(
                    onViewAll = { showReadingHistory = true },
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (recent.isNotEmpty()) {
                items(
                    count = recent.size,
                    key = { i -> "r_${recent[i].id}" },
                ) { i ->
                    val book = recent[i]
                    ContinueReadingBookItem(
                        book = book,
                        isLastRead = i == 0,
                        onClick = { onBookClick(book.id) },
                    )
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

        }
    }

    if (showReadingHistory) {
        ReadingHistorySheet(
            books = history,
            onDismiss = { showReadingHistory = false },
            onBookClick = { bookId ->
                showReadingHistory = false
                onBookClick(bookId)
            },
        )
    }
}

@Composable
private fun HomeWelcomeHeader(
    onSearchClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(modifier = Modifier.weight(0.86f)) {
            Text(
                text = "欢迎回来 👋",
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 19.sp,
                lineHeight = 24.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "今天也要加油阅读哦",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.sp,
                lineHeight = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Surface(
            onClick = onSearchClick,
            modifier = Modifier
                .weight(1.14f)
                .height(40.dp),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Icon(
                    imageVector = Lucide.Search,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.68f),
                )
                Text(
                    text = "搜索书籍 / 作者 / 标签",
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.68f),
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun DailyQuoteCard(
    quote: DailyQuote,
    modifier: Modifier = Modifier,
) {
    // 自定义主题不一定跟随系统深浅色，直接按当前实际背景亮度选择对应图片。
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.45f
    val backgroundRes = if (isDark) {
        R.drawable.home_daily_quote_darkball
    } else {
        R.drawable.home_daily_quote_flower
    }
    val contentColor = if (isDark) Color.White else Color(0xFF201D1F)
    val overlayColor = if (isDark) Color.Black.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.18f)

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(136.dp),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shadowElevation = 1.dp,
    ) {
        Box(Modifier.fillMaxSize()) {
            Image(
                painter = painterResource(backgroundRes),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(overlayColor),
            )
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(0.72f)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(contentColor.copy(alpha = if (isDark) 0.14f else 0.10f))
                        .padding(horizontal = 7.dp, vertical = 3.dp),
                ) {
                    Text(
                        text = "每日一句",
                        color = contentColor.copy(alpha = 0.86f),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Text(
                    text = quote.text,
                    color = contentColor,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "—— ${quote.source}",
                    color = contentColor.copy(alpha = 0.68f),
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun ContinueReadingHeader(
    onViewAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "继续阅读",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.92f),
        )
        Spacer(Modifier.weight(1f))
        Row(
            modifier = Modifier
                .clip(MaterialTheme.shapes.small)
                .clickable(onClick = onViewAll)
                .padding(start = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "查看全部",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(17.dp),
            )
        }
    }
}

@Composable
private fun ContinueReadingBookItem(
    book: Book,
    isLastRead: Boolean,
    onClick: () -> Unit,
) {
    val progress = book.readProgress.coerceIn(0f, 1f)
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .width(86.dp)
                .clickable(onClick = onClick),
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 2.9f),
                shape = RoundedCornerShape(6.dp),
                shadowElevation = 1.dp,
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Box(Modifier.fillMaxSize()) {
                    val cover = book.displayCoverUrl
                    if (!cover.isNullOrBlank()) {
                        AsyncImage(
                            model = cover,
                            contentDescription = book.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = book.title,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 10.sp,
                                lineHeight = 14.sp,
                                maxLines = 4,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(7.dp),
                            )
                        }
                    }
                    if (isLastRead) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .background(
                                    color = Color.Black.copy(alpha = 0.66f),
                                    shape = RoundedCornerShape(topStart = 6.dp, bottomEnd = 5.dp),
                                )
                                .padding(horizontal = 5.dp, vertical = 3.dp),
                        ) {
                            Text(
                                text = "上次阅读",
                                color = Color.White,
                                fontSize = 8.sp,
                                lineHeight = 9.sp,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = book.title,
                fontSize = 11.sp,
                lineHeight = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = continueReadingChapterLabel(book),
                fontSize = 9.sp,
                lineHeight = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(5.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .weight(1f)
                        .height(2.5.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                    drawStopIndicator = {},
                )
                Text(
                    text = "${(progress * 100f).toInt()}%",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 8.sp,
                        fontFeatureSettings = "tnum",
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReadingHistorySheet(
    books: List<Book>,
    onDismiss: () -> Unit,
    onBookClick: (String) -> Unit,
) {
    val timeFormatter = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Text(
            text = "阅读历史",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
        )
        if (books.isEmpty()) {
            Text(
                text = "还没有阅读记录",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 28.dp),
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp),
                contentPadding = PaddingValues(bottom = 28.dp),
            ) {
                items(
                    items = books,
                    key = { book -> book.id },
                ) { book ->
                    ReadingHistoryRow(
                        book = book,
                        visitedAt = timeFormatter.format(Date(book.lastReadAt)),
                        onClick = { onBookClick(book.id) },
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 78.dp, end = 20.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
                    )
                }
            }
        }
    }
}

@Composable
private fun ReadingHistoryRow(
    book: Book,
    visitedAt: String,
    onClick: () -> Unit,
) {
    val progress = book.readProgress.coerceIn(0f, 1f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Surface(
            modifier = Modifier
                .width(44.dp)
                .aspectRatio(2f / 2.9f),
            shape = RoundedCornerShape(4.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            val cover = book.displayCoverUrl
            if (!cover.isNullOrBlank()) {
                AsyncImage(
                    model = cover,
                    contentDescription = book.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = book.title,
                        fontSize = 8.sp,
                        lineHeight = 10.sp,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(4.dp),
                    )
                }
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = book.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = "${continueReadingChapterLabel(book)} · ${(progress * 100f).toInt()}%",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = "上次访问 $visitedAt",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.62f),
            modifier = Modifier.size(18.dp),
        )
    }
}

internal fun continueReadingChapterLabel(book: Book): String = when {
    book.totalChapters > 0 -> "第 ${book.lastReadChapter + 1} 章"
    book.readProgress > 0f -> "已读 ${(book.readProgress.coerceIn(0f, 1f) * 100f).toInt()}%"
    else -> "尚未开始"
}

private data class HomeQuickAction(
    val label: String,
    val onClick: () -> Unit,
)

@Composable
private fun QuickActionsBar(
    actions: List<HomeQuickAction>,
    isEinkTheme: Boolean,
) {
    val isDarkTheme = MaterialTheme.colorScheme.background.luminance() < 0.45f
    val backgroundRes = when (quickActionsThemeRow(isDarkTheme, isEinkTheme)) {
        2 -> R.drawable.home_quick_actions_eink
        1 -> R.drawable.home_quick_actions_dark
        else -> R.drawable.home_quick_actions_light
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1280f / 227f),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.64f),
        shadowElevation = 1.dp,
    ) {
        Box(Modifier.fillMaxSize()) {
            Image(
                painter = painterResource(backgroundRes),
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.fillMaxSize(),
            )
            Row(Modifier.fillMaxSize()) {
                actions.forEach { action ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clickable(
                                role = Role.Button,
                                onClick = action.onClick,
                            )
                            .semantics { contentDescription = action.label },
                    )
                }
            }
        }
    }
}

/** 精灵图2从上到下依次为浅色、低饱和深色和墨水屏单色。 */
internal fun quickActionsThemeRow(
    isDarkTheme: Boolean,
    isEinkTheme: Boolean,
): Int = when {
    isEinkTheme -> 2
    isDarkTheme -> 1
    else -> 0
}
