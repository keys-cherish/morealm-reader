package com.morealm.app.ui.discover

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.morealm.app.domain.entity.SearchBook
import com.morealm.app.presentation.discover.ExploreShowViewModel
import com.morealm.app.presentation.search.SearchResult
import com.morealm.app.presentation.search.SearchViewModel
import com.morealm.app.ui.theme.LocalMoRealmColors

/**
 * 发现分类书籍列表（对照参照实现 ExploreShowActivity）。
 * 无限滚动分页：滚到接近底部自动 loadMore；失败显示错误行 + 重试。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExploreShowScreen(
    onBack: () -> Unit,
    onNavigateDetail: (String) -> Unit,
    viewModel: ExploreShowViewModel = hiltViewModel(),
    searchViewModel: SearchViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val shelfKeys by viewModel.shelfKeys.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    // 滚动到距底部 3 个 item 内时预取下一页。
    val shouldLoadMore by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            lastVisible >= info.totalItemsCount - 4
        }
    }
    LaunchedEffect(shouldLoadMore, state.books.size) {
        if (shouldLoadMore && state.books.isNotEmpty()) viewModel.loadMore()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            state.title.ifBlank { "发现" },
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (state.sourceName.isNotBlank()) {
                            Text(
                                state.sourceName,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            itemsIndexed(
                items = state.books,
                key = { _, book -> "${book.bookUrl}|${book.name}|${book.author}" },
            ) { _, book ->
                ExploreBookRow(
                    book = book,
                    inShelf = viewModel.isInBookshelf(book, shelfKeys),
                    onClick = {
                        searchViewModel.viewBook(book.toSearchResult(), onBookReady = onNavigateDetail)
                    },
                )
            }
            item(key = "footer") {
                ExploreFooter(
                    isLoading = state.isLoading,
                    error = state.error,
                    noMore = state.noMore,
                    isEmpty = state.books.isEmpty(),
                    onRetry = viewModel::retry,
                )
            }
        }
    }
}

@Composable
private fun ExploreFooter(
    isLoading: Boolean,
    error: String?,
    noMore: Boolean,
    isEmpty: Boolean,
    onRetry: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = if (isEmpty) 80.dp else 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        when {
            isLoading -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text(
                    "加载中…",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            error != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    error,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                TextButton(onClick = onRetry) { Text("重试") }
            }

            noMore -> Text(
                if (isEmpty) "这个分类下没有解析到书籍" else "没有更多了",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
            )
        }
    }
}

@Composable
private fun ExploreBookRow(
    book: SearchBook,
    inShelf: Boolean,
    onClick: () -> Unit,
) {
    val isNight = LocalMoRealmColors.current.isNight
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = if (isNight) {
            MaterialTheme.colorScheme.surface
        } else {
            Color.White.copy(alpha = 0.76f)
        },
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Surface(
                modifier = Modifier.size(width = 56.dp, height = 76.dp),
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                if (!book.coverUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = book.coverUrl,
                        contentDescription = book.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            book.name,
                            fontSize = 9.sp,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(5.dp),
                        )
                    }
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        book.name,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontFamily = FontFamily.Serif,
                            fontSize = 14.sp,
                            lineHeight = 18.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.sp,
                            platformStyle = PlatformTextStyle(includeFontPadding = false),
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (inShelf) {
                        Spacer(Modifier.width(6.dp))
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                        ) {
                            Text(
                                "在书架",
                                fontSize = 9.sp,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    listOfNotNull(
                        book.author.takeIf { it.isNotBlank() },
                        book.kind?.takeIf { it.isNotBlank() }?.split(",")?.take(2)?.joinToString(" · "),
                        book.wordCount?.takeIf { it.isNotBlank() },
                    ).joinToString(" · ").ifBlank { book.originName },
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontFamily = FontFamily.Serif,
                        fontSize = 10.sp,
                        lineHeight = 13.sp,
                        letterSpacing = 0.sp,
                        platformStyle = PlatformTextStyle(includeFontPadding = false),
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                book.latestChapterTitle?.takeIf { it.isNotBlank() }?.let { latest ->
                    Spacer(Modifier.height(3.dp))
                    Text(
                        "最新：$latest",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = 9.sp,
                            lineHeight = 12.sp,
                            platformStyle = PlatformTextStyle(includeFontPadding = false),
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                book.intro?.trim()?.takeIf { it.isNotBlank() }?.let { intro ->
                    Spacer(Modifier.height(3.dp))
                    Text(
                        intro.replace("\n", " "),
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Serif,
                            fontSize = 9.sp,
                            lineHeight = 12.sp,
                            letterSpacing = 0.sp,
                            platformStyle = PlatformTextStyle(includeFontPadding = false),
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private fun SearchBook.toSearchResult() = SearchResult(
    title = name,
    author = author,
    coverUrl = coverUrl,
    bookUrl = bookUrl,
    sourceName = originName,
    sourceUrl = origin,
    sourceType = type,
    intro = intro.orEmpty(),
    kind = kind,
    wordCount = wordCount,
    latestChapter = latestChapterTitle,
    searchBook = this,
)
