package com.morealm.app.ui.detail

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import com.morealm.app.core.log.AppLog
import com.morealm.app.presentation.profile.BookDetailViewModel
import com.morealm.app.presentation.source.SearchStatus
import com.morealm.app.presentation.source.SourceLoginViewModel
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.morealm.app.domain.entity.BookFormat
import com.morealm.app.ui.source.SourceLoginOverlay
import com.morealm.app.ui.theme.LocalMoRealmColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookDetailScreen(
    bookId: String,
    onBack: () -> Unit,
    onRead: () -> Unit,
    viewModel: BookDetailViewModel = hiltViewModel(),
    /** 共享的书源登录 VM。详情页"登录源"按钮把源丢给它拉起登录对话框。 */
    loginViewModel: SourceLoginViewModel = hiltViewModel(),
) {
    val book by viewModel.book.collectAsStateWithLifecycle()
    val currentSource by viewModel.currentSource.collectAsStateWithLifecycle()
    val loginStatusMap by loginViewModel.loginStatusMap.collectAsStateWithLifecycle()
    val showSourcePicker by viewModel.isSourcePickerVisible.collectAsStateWithLifecycle()
    val enabledSourcesCount by viewModel.enabledSourcesCount.collectAsStateWithLifecycle()
    val changeCandidates by viewModel.changeSourceCandidates.collectAsStateWithLifecycle()
    val changeProgress by viewModel.changeSourceProgress.collectAsStateWithLifecycle()
    val changeSearching by viewModel.changeSourceSearching.collectAsStateWithLifecycle()
    val saving by viewModel.saving.collectAsStateWithLifecycle()
    val isPreparingRead by viewModel.isPreparingRead.collectAsStateWithLifecycle()
    val isReadReady by viewModel.isReadReady.collectAsStateWithLifecycle()
    val readPreparationError by viewModel.readPreparationError.collectAsStateWithLifecycle()
    val chapters by viewModel.chapters.collectAsStateWithLifecycle()
    val moColors = LocalMoRealmColors.current
    val context = LocalContext.current
    val isDownloading by viewModel.isCacheDownloading.collectAsStateWithLifecycle()
    val downloadProgress by viewModel.cacheDownloadProgress.collectAsStateWithLifecycle()
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf(false) }
    var showTopMenu by remember { mutableStateOf(false) }
    var introExpanded by remember { mutableStateOf(false) }
    var chapterDescending by remember { mutableStateOf(false) }
    // 预览态（inBookshelf=false）退出处理：读过才提示「加入书架?」，纯浏览静默清除（不留残记录）。
    var hasOpenedReader by remember { mutableStateOf(false) }
    var showAddPrompt by remember { mutableStateOf(false) }
    val leaveScreen = {
        val current = book
        if (current != null && !current.inBookshelf) {
            if (hasOpenedReader) showAddPrompt = true
            else { viewModel.deleteBook(); onBack() }
        } else onBack()
    }
    BackHandler { leaveScreen() }

    // 订阅换源失败事件 -> Toast 反馈。
    // 历史 bug：applyCandidate Step 1/2 失败时只 silent return，UI 看上去"点了没反应"。
    // 这里把 controller 发出的错误信息直接弹出，让用户立刻知道为什么没换成。
    LaunchedEffect(viewModel) {
        viewModel.changeSourceErrorEvents.collect { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }

    val loginSource = currentSource?.takeIf { !it.loginUrl.isNullOrBlank() }
    val sourceLoggedIn = loginSource?.let { loginStatusMap[it.bookSourceUrl] == true } == true
    // 详情页所有承载层均来自当前主题，避免浅色主题被固定白色卡片割裂。
    val detailBackgroundColor = MaterialTheme.colorScheme.background
    val detailSurfaceColor = MaterialTheme.colorScheme.surface
    val detailDividerColor = MaterialTheme.colorScheme.onSurface.copy(
        alpha = if (moColors.isNight) 0.12f else 0.065f,
    )
    val detailOutlineColor = MaterialTheme.colorScheme.onSurface.copy(
        alpha = if (moColors.isNight) 0.18f else 0.11f,
    )
    val detailCardElevation = if (moColors.isNight) 1.dp else 0.5.dp
    LaunchedEffect(loginSource?.bookSourceUrl) {
        loginSource?.let { loginViewModel.refreshLoginStatuses(listOf(it)) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = { leaveScreen() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { showTopMenu = true }) {
                            Icon(Icons.Default.MoreVert, "更多")
                        }
                        DropdownMenu(
                            expanded = showTopMenu,
                            onDismissRequest = { showTopMenu = false },
                        ) {
                            loginSource?.let { source ->
                                DropdownMenuItem(
                                    text = { Text(if (sourceLoggedIn) "退出书源登录" else "登录书源") },
                                    leadingIcon = { Icon(Icons.Default.Login, null) },
                                    onClick = {
                                        showTopMenu = false
                                        if (sourceLoggedIn) loginViewModel.logout(source)
                                        else loginViewModel.showLoginDialog(source)
                                    },
                                )
                            }
                            book?.takeIf { it.sourceId != null && enabledSourcesCount > 0 }?.let {
                                DropdownMenuItem(
                                    text = { Text("换源") },
                                    leadingIcon = { Icon(Icons.Default.SwapHoriz, null) },
                                    onClick = { showTopMenu = false; viewModel.showSourcePicker() },
                                )
                            }
                            book?.takeIf { it.format == BookFormat.WEB }?.let { current ->
                                val isThisBookDownloading = isDownloading && downloadProgress.bookId == current.id
                                DropdownMenuItem(
                                    text = { Text(if (isThisBookDownloading) "停止离线缓存" else "离线缓存全本") },
                                    leadingIcon = { Icon(Icons.Default.CloudDownload, null) },
                                    onClick = {
                                        showTopMenu = false
                                        if (isThisBookDownloading) viewModel.stopCacheBook()
                                        else (current.sourceUrl ?: current.sourceId)?.let {
                                            viewModel.startCacheBook(current.id, it)
                                        }
                                    },
                                )
                            }
                            if (book?.inBookshelf == true) {
                                DropdownMenuItem(
                                    text = { Text("编辑书籍信息") },
                                    leadingIcon = { Icon(Icons.Default.Edit, null) },
                                    onClick = { showTopMenu = false; showEditDialog = true },
                                )
                                DropdownMenuItem(
                                    text = { Text("移出书架", color = MaterialTheme.colorScheme.error) },
                                    leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
                                    onClick = { showTopMenu = false; showDeleteConfirm = true },
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = detailBackgroundColor,
                ),
                windowInsets = WindowInsets(0, 0, 0, 0),
            )
        },
        bottomBar = {
            book?.let { current ->
                Surface(
                    color = detailSurfaceColor,
                    shadowElevation = 3.dp,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 7.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        OutlinedButton(
                            onClick = viewModel::addToShelf,
                            enabled = !current.inBookshelf,
                            modifier = Modifier.weight(0.42f).height(44.dp),
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(0.75.dp, detailOutlineColor),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        ) {
                            Icon(
                                if (current.inBookshelf) Icons.Default.Check else Icons.Default.FavoriteBorder,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(7.dp))
                            Text(if (current.inBookshelf) "已在书架" else "加入书架")
                        }
                        Button(
                            onClick = {
                                if (current.format == BookFormat.WEB && !isReadReady) {
                                    viewModel.retryPrepareRead()
                                } else {
                                    hasOpenedReader = true
                                    onRead()
                                }
                            },
                            enabled = !isPreparingRead,
                            modifier = Modifier.weight(0.58f).height(44.dp),
                            shape = RoundedCornerShape(14.dp),
                        ) {
                            if (isPreparingRead) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(17.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                )
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(
                                when {
                                    isPreparingRead -> "加载目录中"
                                    current.format == BookFormat.WEB && !isReadReady -> "重新加载目录"
                                    current.lastReadChapter > 0 -> "继续阅读"
                                    else -> "开始阅读"
                                },
                            )
                        }
                    }
                }
            }
        },
        containerColor = detailBackgroundColor,
    ) { padding ->
        val current = book
        if (current == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
            }
        } else {
            Column(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
            ) {
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    Surface(
                        modifier = Modifier.size(width = 116.dp, height = 166.dp),
                        shape = RoundedCornerShape(7.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shadowElevation = 5.dp,
                    ) {
                        val coverToShow = current.customCoverUrl ?: current.coverUrl
                        if (!coverToShow.isNullOrBlank()) {
                            AsyncImage(
                                model = coverToShow,
                                contentDescription = current.title,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.AutoMirrored.Filled.MenuBook,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(42.dp),
                                )
                            }
                        }
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            current.title,
                            style = MaterialTheme.typography.titleLarge.copy(fontSize = 18.sp, lineHeight = 23.sp),
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (current.author.isNotBlank()) {
                            Spacer(Modifier.height(7.dp))
                            Text(
                                current.author,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.height(11.dp))
                        DetailMetaLine("类型", current.kind?.takeIf(String::isNotBlank) ?: "未分类")
                        DetailMetaLine("来源", current.originName.ifBlank { "本地书籍" })
                        DetailMetaLine("格式", current.format.name)
                        if (current.lastCheckTime > 0L) DetailMetaLine("状态", "已同步")
                        Spacer(Modifier.weight(1f))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            current.rating?.takeIf(String::isNotBlank)?.let { rating ->
                                Icon(
                                    Icons.Default.Star,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(rating, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                            } ?: Text(
                                text = if (current.totalChapters > 0) "${current.totalChapters} 章" else current.format.name,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            OutlinedButton(
                                onClick = viewModel::addToShelf,
                                enabled = !current.inBookshelf,
                                modifier = Modifier.height(30.dp),
                                shape = RoundedCornerShape(6.dp),
                                border = BorderStroke(
                                    0.75.dp,
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.36f),
                                ),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.primary,
                                ),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            ) {
                                Icon(
                                    if (current.inBookshelf) Icons.Default.Check else Icons.Default.FavoriteBorder,
                                    contentDescription = null,
                                    modifier = Modifier.size(13.dp),
                                )
                                Spacer(Modifier.width(3.dp))
                                Text(
                                    if (current.inBookshelf) "已收藏" else "收藏",
                                    fontSize = 10.sp,
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(18.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    color = detailSurfaceColor,
                    shadowElevation = detailCardElevation,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        StatItem("章节", if (isPreparingRead) "…" else "${current.totalChapters}")
                        VerticalDivider(
                            modifier = Modifier.height(42.dp),
                            thickness = 0.5.dp,
                            color = detailDividerColor,
                        )
                        StatItem("阅读进度", "${(current.readProgress * 100).toInt()}%")
                        VerticalDivider(
                            modifier = Modifier.height(42.dp),
                            thickness = 0.5.dp,
                            color = detailDividerColor,
                        )
                        StatItem("阅读格式", current.format.name)
                    }
                }

                Spacer(Modifier.height(10.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    color = detailSurfaceColor,
                    shadowElevation = detailCardElevation,
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("简介", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            current.description?.takeIf { it.length > 100 }?.let {
                                TextButton(onClick = { introExpanded = !introExpanded }) {
                                    Text(
                                        if (introExpanded) "收起" else "更多",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                                    )
                                }
                            }
                        }
                        Text(
                            current.description.displayDescription(),
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = if (introExpanded) Int.MAX_VALUE else 5,
                            overflow = TextOverflow.Ellipsis,
                            lineHeight = 18.sp,
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    color = detailSurfaceColor,
                    shadowElevation = detailCardElevation,
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("章节", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .clickable { chapterDescending = !chapterDescending }
                                    .padding(horizontal = 8.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    if (chapterDescending) "倒序" else "正序",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Spacer(Modifier.width(4.dp))
                                Icon(
                                    Icons.Default.Sort,
                                    contentDescription = "切换章节排序",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                                    modifier = Modifier.size(14.dp),
                                )
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        when {
                            isPreparingRead -> Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                Text("正在获取书籍详情与目录", style = MaterialTheme.typography.bodySmall)
                            }
                            chapters.isEmpty() -> Column(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text("暂无章节", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    readPreparationError ?: "章节会在作品更新后显示",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (readPreparationError == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                                )
                            }
                            else -> (if (chapterDescending) chapters.asReversed() else chapters)
                                .take(4)
                                .forEachIndexed { index, chapter ->
                                if (index > 0) {
                                    HorizontalDivider(
                                        thickness = 0.5.dp,
                                        color = detailDividerColor,
                                    )
                                }
                                Text(
                                    chapter.title,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    // 预览态退出提示：读过但未加入书架 → 问是否加入；选否则清除该临时记录。
    if (showAddPrompt) {
        AlertDialog(
            onDismissRequest = { showAddPrompt = false },
            title = { Text("加入书架") },
            text = { Text("是否将《${book?.title ?: ""}》加入书架？不加入将不保留阅读记录。") },
            confirmButton = {
                TextButton(onClick = { showAddPrompt = false; viewModel.addToShelf(); onBack() }) { Text("加入") }
            },
            dismissButton = {
                TextButton(onClick = { showAddPrompt = false; viewModel.deleteBook(); onBack() }) { Text("不加入") }
            },
        )
    }

    // Delete confirmation dialog
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("删除书籍") },
            text = { Text("确定要从书架移除《${book?.title ?: ""}》吗？本地文件不会被删除。") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    viewModel.deleteBook()
                    onBack()
                }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("取消")
                }
            },
        )
    }

    // Change-source dialog: cross-source search + real switch
    if (showSourcePicker) {
        AlertDialog(
            onDismissRequest = { viewModel.hideSourcePicker() },
            title = {
                // Row 而非 Column：title 区右侧塞一个「刷新」按钮，让用户在缓存
                // 30 分钟窗口内（默认走 cache）也能强制重搜。书名一行 + 进度一行
                // 改为左侧 Column.weight(1f)，避免被按钮挤丢副标题。
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("换源 · 搜索其他书源")
                        val total = changeProgress.size
                        val done = changeProgress.count { it.status == SearchStatus.DONE || it.status == SearchStatus.FAILED }
                        if (total > 0) {
                            Text(
                                "已搜索 $done/$total · 找到 ${changeCandidates.size} 个候选",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else if (changeCandidates.isNotEmpty()) {
                            // 进度行为空但已有候选 = 走了缓存窗口短路。给用户一个明确反馈，
                            // 否则会怀疑「为啥这么快」/「是不是没真搜」。
                            Text(
                                "缓存 ${changeCandidates.size} 条候选 · 点刷新可重新搜索",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    IconButton(
                        onClick = { viewModel.refreshSourcePicker() },
                        enabled = !changeSearching,
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = "重新搜索")
                    }
                }
            },
            text = {
                Column(modifier = Modifier.heightIn(max = 480.dp)) {
                    if (changeSearching && changeCandidates.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
                        }
                    }
                    if (changeCandidates.isEmpty() && !changeSearching) {
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
                        for (c in changeCandidates) {
                            val isCurrent = c.sourceUrl == book?.origin
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
                                    viewModel.applyChangedSource(c)
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
                TextButton(onClick = { viewModel.hideSourcePicker() }) { Text("关闭") }
            },
        )
    }

    // 书源登录流程 overlay —— 与书源管理页、阅读器共享同一套 state machine。
    // 详情页不方便做跳日志，onNavigateToLog 省略。
    SourceLoginOverlay(loginViewModel = loginViewModel)

    // Metadata edit dialog
    if (showEditDialog) {
        book?.let { b ->
            var editTitle by remember { mutableStateOf(b.title) }
            var editAuthor by remember { mutableStateOf(b.author) }
            var editDesc by remember { mutableStateOf(b.description ?: "") }
            val isEpub = b.format == BookFormat.EPUB

            AlertDialog(
                onDismissRequest = { if (!saving) showEditDialog = false },
                title = { Text("编辑书籍信息") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            value = editTitle,
                            onValueChange = { editTitle = it },
                            label = { Text("书名") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                cursorColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                        OutlinedTextField(
                            value = editAuthor,
                            onValueChange = { editAuthor = it },
                            label = { Text("作者") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                cursorColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                        OutlinedTextField(
                            value = editDesc,
                            onValueChange = { editDesc = it },
                            label = { Text("简介") },
                            maxLines = 4,
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                cursorColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                        if (isEpub) {
                            Text(
                                "修改将写入 EPUB 文件，重新导入后仍保留",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            viewModel.updateMetadata(editTitle, editAuthor, editDesc)
                            showEditDialog = false
                        },
                        enabled = !saving,
                    ) {
                        if (saving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        } else {
                            Text("保存", color = MaterialTheme.colorScheme.primary)
                        }
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { showEditDialog = false },
                        enabled = !saving,
                    ) {
                        Text("取消")
                    }
                },
            )
        }
    }
}

private fun String?.displayDescription(): String = this
    ?.trim()
    ?.replace("\\r", "")
    ?.replace("\\n", "\n")
    ?.ifBlank { "暂无简介" }
    ?: "暂无简介"

@Composable
private fun DetailMetaLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "$label：",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
        )
    }
}
