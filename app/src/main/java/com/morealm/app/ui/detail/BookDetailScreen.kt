package com.morealm.app.ui.detail

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
    val moColors = LocalMoRealmColors.current
    val context = LocalContext.current
    val isDownloading by viewModel.isCacheDownloading.collectAsStateWithLifecycle()
    val downloadProgress by viewModel.cacheDownloadProgress.collectAsStateWithLifecycle()
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf(false) }
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
                    // B2：只有当前书是 web 书且源配了 loginUrl 时显示"登录源"入口。
                    // 已登录态展示已登录 tint + 点击走退出；未登录态走登录。状态流共享
                    // SourceLoginViewModel.loginStatusMap，与书源管理页 chip 即时联动。
                    currentSource?.takeIf { !it.loginUrl.isNullOrBlank() }?.let { src ->
                        val loggedIn = loginStatusMap[src.bookSourceUrl] == true
                        // 进屏幕时触发一次状态预算。仅刷当前源，避免全表跑 JS。
                        LaunchedEffect(src.bookSourceUrl) {
                            loginViewModel.refreshLoginStatuses(listOf(src))
                        }
                        IconButton(onClick = {
                            if (loggedIn) loginViewModel.logout(src)
                            else loginViewModel.showLoginDialog(src)
                        }) {
                            Icon(
                                Icons.Default.Login,
                                contentDescription = if (loggedIn) "退出登录" else "登录源",
                                tint = if (loggedIn) MaterialTheme.colorScheme.primary
                                       else MaterialTheme.colorScheme.onBackground,
                            )
                        }
                    }
                    // 编辑元数据 / 删除（移出书架）只对已在架的书有意义；
                    // 预览态（搜索查看但未加入）隐藏，避免误删 + 减少干扰。
                    if (book?.inBookshelf == true) {
                        IconButton(onClick = { showEditDialog = true }) {
                            Icon(Icons.Default.Edit, "编辑元数据",
                                tint = MaterialTheme.colorScheme.onBackground)
                        }
                        IconButton(onClick = { showDeleteConfirm = true }) {
                            Icon(Icons.Default.Delete, "删除",
                                tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f))
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        book?.let { b ->
            Column(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(16.dp))

                // Cover
                Box(
                    modifier = Modifier
                        .size(140.dp, 200.dp)
                        .clip(MaterialTheme.shapes.medium)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                    contentAlignment = Alignment.Center,
                ) {
                    // 优先级：customCoverUrl > coverUrl > 默认图标
                    val coverToShow = b.customCoverUrl ?: b.coverUrl
                    if (coverToShow != null) {
                        AsyncImage(
                            model = coverToShow,
                            contentDescription = b.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Icon(
                            Icons.AutoMirrored.Filled.MenuBook,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(48.dp),
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                Text(
                    b.title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                )

                if (b.author.isNotBlank()) {
                    Text(
                        b.author,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                    )
                }

                Spacer(Modifier.height(24.dp))

                // Stats row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    StatItem("章节", "${b.totalChapters}")
                    StatItem("进度", "${(b.readProgress * 100).toInt()}%")
                    StatItem("格式", b.format.name)
                }

                Spacer(Modifier.height(24.dp))

                Button(
                    onClick = { hasOpenedReader = true; onRead() },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                    ),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Text(
                        if (b.lastReadChapter > 0) "继续阅读" else "开始阅读",
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }

                // 预览态：未加入书架的书显示「加入书架」（点击翻 inBookshelf=true）。
                if (!b.inBookshelf) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { viewModel.addToShelf() },
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Text("加入书架", modifier = Modifier.padding(vertical = 4.dp))
                    }
                }

                // Source switch button (for online books)
                if (b.sourceId != null && enabledSourcesCount > 0) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { viewModel.showSourcePicker() },
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Icon(Icons.Default.SwapHoriz, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("换源 (${b.originName ?: "未知"})")
                    }

                    // Download / cache button
                    Spacer(Modifier.height(8.dp))
                    val isThisBookDownloading = isDownloading && downloadProgress.bookId == b.id
                    OutlinedButton(
                        onClick = {
                            if (isThisBookDownloading) {
                                viewModel.stopCacheBook()
                            } else {
                                val sourceUrl = b.sourceUrl ?: b.sourceId ?: return@OutlinedButton
                                viewModel.startCacheBook(b.id, sourceUrl)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        if (isThisBookDownloading) {
                            val prog = downloadProgress
                            val done = prog.completed + prog.failed + prog.cached
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("下载中 $done/${prog.total}")
                        } else {
                            Icon(Icons.Default.CloudDownload, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("离线缓存全本")
                        }
                    }
                }

                b.description?.let { desc ->
                    Spacer(Modifier.height(24.dp))
                    Text(
                        "简介",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        desc,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                    )
                }

                Spacer(Modifier.height(32.dp))
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
