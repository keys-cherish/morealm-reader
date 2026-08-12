package com.morealm.app.ui.discover

import android.widget.Toast
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.morealm.app.domain.db.ExploreSourcePart
import com.morealm.app.domain.entity.SearchBook
import com.morealm.app.domain.entity.rule.ExploreKind
import com.morealm.app.presentation.discover.DiscoverBook
import com.morealm.app.presentation.discover.DiscoverViewModel
import com.morealm.app.presentation.discover.ExploreViewModel
import com.morealm.app.presentation.search.SearchResult
import com.morealm.app.presentation.search.SearchViewModel
import com.morealm.app.ui.theme.LocalMoRealmColors

/**
 * 发现页 —— 双视图（对照参照实现发现界面 + MoRealm 原推荐流）：
 *  - 分类浏览：书源列表，点击展开发现分类 chips，点分类进入分页书籍列表；
 *  - 今日推荐：跨源混合推荐流（原发现页行为）。
 */
@Composable
fun DiscoverScreen(
    onNavigateDetail: (String) -> Unit,
    onOpenExplore: (sourceUrl: String, title: String, exploreUrl: String) -> Unit,
    viewModel: DiscoverViewModel = hiltViewModel(),
    exploreViewModel: ExploreViewModel = hiltViewModel(),
    searchViewModel: SearchViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    // false = 分类浏览（默认，参照实现语义）；true = 今日推荐
    var showFeed by rememberSaveable { mutableStateOf(false) }

    val feedState by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel, showFeed) {
        if (showFeed) viewModel.refreshOnFirstDisplay()
    }
    LaunchedEffect(viewModel) {
        viewModel.refreshResults.collect { count ->
            Toast.makeText(context, "发现了 $count 本书", Toast.LENGTH_SHORT).show()
        }
    }

    val sources by exploreViewModel.sources.collectAsStateWithLifecycle()
    val groups by exploreViewModel.groups.collectAsStateWithLifecycle()
    val selectedGroup by exploreViewModel.selectedGroup.collectAsStateWithLifecycle()
    val searchQuery by exploreViewModel.searchQuery.collectAsStateWithLifecycle()
    val expanded by exploreViewModel.expanded.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item(key = "header") {
            DiscoverHeader(
                subtitle = if (showFeed) {
                    "${feedState.sourceCount} 个书源 · ${feedState.books.size} 本"
                } else {
                    "${sources.size} 个书源可浏览"
                },
                showRefresh = showFeed,
                onRefresh = viewModel::refresh,
            )
        }
        item(key = "mode_switch") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = !showFeed,
                    onClick = { showFeed = false },
                    label = { Text("分类浏览", style = MaterialTheme.typography.labelMedium) },
                    shape = MaterialTheme.shapes.small,
                )
                FilterChip(
                    selected = showFeed,
                    onClick = { showFeed = true },
                    label = { Text("今日推荐", style = MaterialTheme.typography.labelMedium) },
                    shape = MaterialTheme.shapes.small,
                )
            }
        }

        if (showFeed) {
            feedItems(
                state = feedState,
                onBookClick = { book ->
                    searchViewModel.viewBook(book.toSearchResult(), onBookReady = onNavigateDetail)
                },
            )
        } else {
            exploreItems(
                sources = sources,
                groups = groups,
                selectedGroup = selectedGroup,
                searchQuery = searchQuery,
                expanded = expanded,
                exploreViewModel = exploreViewModel,
                onOpenExplore = onOpenExplore,
            )
        }
    }
}

@Composable
private fun DiscoverHeader(
    subtitle: String,
    showRefresh: Boolean,
    onRefresh: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 20.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "发现",
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontFamily = FontFamily.Serif,
                    fontSize = 24.sp,
                    lineHeight = 29.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.sp,
                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                ),
            )
            Text(
                text = subtitle,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (showRefresh) {
            IconButton(onClick = onRefresh) {
                Icon(Icons.Default.Refresh, contentDescription = "刷新发现")
            }
        }
    }
}

// ── 分类浏览视图（参照实现 ExploreFragment / ExploreAdapter）──

private fun androidx.compose.foundation.lazy.LazyListScope.exploreItems(
    sources: List<ExploreSourcePart>,
    groups: List<String>,
    selectedGroup: String?,
    searchQuery: String,
    expanded: ExploreViewModel.ExpandedSource?,
    exploreViewModel: ExploreViewModel,
    onOpenExplore: (String, String, String) -> Unit,
) {
    item(key = "explore_filter") {
        Column {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = exploreViewModel::setSearchQuery,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("筛选书源", fontSize = 13.sp) },
                leadingIcon = {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { exploreViewModel.setSearchQuery("") }) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "清空",
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(10.dp),
                textStyle = MaterialTheme.typography.bodyMedium,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            )
            if (groups.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = selectedGroup == null,
                        onClick = { exploreViewModel.selectGroup(null) },
                        label = { Text("全部", style = MaterialTheme.typography.labelMedium) },
                        shape = MaterialTheme.shapes.small,
                    )
                    groups.forEach { group ->
                        FilterChip(
                            selected = selectedGroup == group,
                            onClick = {
                                exploreViewModel.selectGroup(if (selectedGroup == group) null else group)
                            },
                            label = { Text(group, style = MaterialTheme.typography.labelMedium) },
                            shape = MaterialTheme.shapes.small,
                        )
                    }
                }
            }
        }
    }
    if (sources.isEmpty()) {
        item(key = "explore_empty") {
            Box(
                modifier = Modifier.fillMaxWidth().height(240.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = if (searchQuery.isBlank() && selectedGroup == null) {
                            "没有带发现规则的书源"
                        } else {
                            "没有匹配的书源"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = if (searchQuery.isBlank() && selectedGroup == null) {
                            "在书源管理导入带发现规则的书源后，这里会列出可浏览的分类"
                        } else {
                            "换个关键字或分组试试"
                        },
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                    )
                }
            }
        }
    }
    items(
        items = sources,
        key = { "explore_${it.bookSourceUrl}" },
    ) { source ->
        ExploreSourceRow(
            source = source,
            expandedState = expanded.takeIf { it?.sourceUrl == source.bookSourceUrl },
            onToggle = { exploreViewModel.toggleExpand(source.bookSourceUrl) },
            onKindClick = { kind ->
                kind.url?.takeIf { it.isNotBlank() }?.let { url ->
                    onOpenExplore(source.bookSourceUrl, kind.title, url)
                }
            },
            onMoveTop = { exploreViewModel.moveToTop(source.bookSourceUrl) },
            onRefreshKinds = { exploreViewModel.refreshKinds(source.bookSourceUrl) },
            onHide = { exploreViewModel.hideFromExplore(source.bookSourceUrl) },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ExploreSourceRow(
    source: ExploreSourcePart,
    expandedState: ExploreViewModel.ExpandedSource?,
    onToggle: () -> Unit,
    onKindClick: (ExploreKind) -> Unit,
    onMoveTop: () -> Unit,
    onRefreshKinds: () -> Unit,
    onHide: () -> Unit,
) {
    val isNight = LocalMoRealmColors.current.isNight
    val isExpanded = expandedState != null
    var showMenu by remember { mutableStateOf(false) }
    var errorKind by remember { mutableStateOf<ExploreKind?>(null) }

    Surface(
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
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize(),
        ) {
            Box {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            onClick = onToggle,
                            onLongClick = { showMenu = true },
                        )
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            source.bookSourceName,
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontFamily = FontFamily.Serif,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = 0.sp,
                                platformStyle = PlatformTextStyle(includeFontPadding = false),
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        source.bookSourceGroup?.takeIf { it.isNotBlank() }?.let { group ->
                            Spacer(Modifier.height(2.dp))
                            Text(
                                group,
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (expandedState?.loading == true) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 1.5.dp,
                        )
                        Spacer(Modifier.size(6.dp))
                    }
                    Icon(
                        imageVector = if (isExpanded) {
                            Icons.Default.KeyboardArrowDown
                        } else {
                            Icons.AutoMirrored.Filled.KeyboardArrowRight
                        },
                        contentDescription = if (isExpanded) "收起" else "展开",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.size(18.dp),
                    )
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("置顶") },
                        onClick = { showMenu = false; onMoveTop() },
                    )
                    DropdownMenuItem(
                        text = { Text("刷新分类") },
                        onClick = { showMenu = false; onRefreshKinds() },
                    )
                    DropdownMenuItem(
                        text = { Text("从发现隐藏") },
                        onClick = { showMenu = false; onHide() },
                    )
                }
            }
            if (isExpanded && expandedState != null && !expandedState.loading) {
                if (expandedState.kinds.isEmpty()) {
                    Text(
                        "该书源没有解析出分类",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                        modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                    )
                } else {
                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        expandedState.kinds.forEach { kind ->
                            ExploreKindChip(
                                kind = kind,
                                onClick = {
                                    if (kind.title.startsWith("ERROR:")) {
                                        errorKind = kind
                                    } else {
                                        onKindClick(kind)
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    errorKind?.let { kind ->
        AlertDialog(
            onDismissRequest = { errorKind = null },
            title = { Text("分类解析出错") },
            text = {
                Column(Modifier.fillMaxWidth()) {
                    Text(
                        kind.title.removePrefix("ERROR:"),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        kind.url.orEmpty().take(2000),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 12,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { errorKind = null }) { Text("知道了") }
            },
        )
    }
}

/**
 * 单个分类 chip。url 为空的项是"分节标题"（参照实现语义），降级为无背景文字。
 * ERROR: 项用 error 配色提示可点击查看详情。
 */
@Composable
private fun ExploreKindChip(
    kind: ExploreKind,
    onClick: () -> Unit,
) {
    val clickable = !kind.url.isNullOrBlank()
    val isError = kind.title.startsWith("ERROR:")
    when {
        !clickable -> Text(
            kind.title,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 2.dp, vertical = 6.dp),
        )

        else -> Surface(
            onClick = onClick,
            shape = RoundedCornerShape(14.dp),
            color = if (isError) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
            },
        ) {
            Text(
                if (isError) "解析出错，点击查看" else kind.title,
                fontSize = 11.sp,
                color = if (isError) {
                    MaterialTheme.colorScheme.onErrorContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }
    }
}

// ── 今日推荐视图（原发现页混合流）──

private fun androidx.compose.foundation.lazy.LazyListScope.feedItems(
    state: com.morealm.app.presentation.discover.DiscoverUiState,
    onBookClick: (DiscoverBook) -> Unit,
) {
    if (state.isRefreshing) {
        item(key = "feed_progress") {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(2.dp))
        }
    }
    if (state.books.isEmpty() && !state.isRefreshing) {
        item(key = "feed_empty") {
            Box(
                modifier = Modifier.fillMaxWidth().height(280.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "暂无发现内容",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = state.message ?: "添加书源后即可获取推荐书籍",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                    )
                }
            }
        }
    } else {
        state.message?.let { message ->
            item(key = "feed_message") {
                Text(message, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    items(
        items = state.books,
        key = { "feed_${it.sourceUrl}|${it.bookUrl}|${it.title}" },
    ) { book ->
        DiscoverBookRow(
            book = book,
            onClick = { onBookClick(book) },
        )
    }
}

@Composable
private fun DiscoverBookRow(book: DiscoverBook, onClick: () -> Unit) {
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
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier.size(width = 56.dp, height = 76.dp),
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                if (!book.coverUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = book.coverUrl,
                        contentDescription = book.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            book.title,
                            fontSize = 9.sp,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(5.dp),
                        )
                    }
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    book.title,
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
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    book.author.ifBlank { book.sourceName },
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
                Spacer(Modifier.height(4.dp))
                Text(
                    book.displaySummary(),
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Serif,
                        fontSize = 9.sp,
                        lineHeight = 12.sp,
                        letterSpacing = 0.sp,
                        platformStyle = PlatformTextStyle(includeFontPadding = false),
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.32f),
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

private fun DiscoverBook.displaySummary(): String = intro
    .trim()
    .removePrefix("简介：")
    .removePrefix("简介:")
    .replace("\\r", "")
    .replace("\\n", " ")
    .trim()
    .ifBlank {
        latestChapter?.takeIf(String::isNotBlank)?.let { "最新章节：$it" }
            ?: kind?.takeIf(String::isNotBlank)?.let { "$it 题材作品，等待你开启故事。" }
            ?: "来自 $sourceName 的精选作品。"
    }

private fun DiscoverBook.toSearchResult() = SearchResult(
    title = title,
    author = author,
    coverUrl = coverUrl,
    bookUrl = bookUrl,
    sourceName = sourceName,
    sourceUrl = sourceUrl,
    sourceType = sourceType,
    intro = intro,
    kind = kind,
    wordCount = wordCount,
    latestChapter = latestChapter,
    searchBook = SearchBook(
        bookUrl = bookUrl,
        origin = sourceUrl,
        originName = sourceName,
        type = sourceType,
        name = title,
        author = author,
        kind = kind,
        coverUrl = coverUrl,
        intro = intro,
        wordCount = wordCount,
        latestChapterTitle = latestChapter,
        tocUrl = tocUrl.orEmpty(),
        variable = variable,
    ),
)
