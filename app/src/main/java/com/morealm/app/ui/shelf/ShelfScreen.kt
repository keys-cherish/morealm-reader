package com.morealm.app.ui.shelf

import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items as lazyItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.morealm.app.domain.entity.Book
import com.morealm.app.domain.entity.BookFormat
import com.morealm.app.domain.entity.BookGroup
import com.morealm.app.presentation.shelf.FolderImportState
import com.morealm.app.ui.theme.LocalMoRealmColors
import com.morealm.app.presentation.shelf.ShelfViewModel
import com.morealm.app.ui.widget.ShelfGridSkeleton
import com.morealm.app.ui.widget.ThemedSnackbarHost
import androidx.activity.compose.BackHandler
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Calendar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShelfScreen(
    onBookClick: (String) -> Unit,
    onBookLongClick: (String) -> Unit,
    onSearch: () -> Unit,
    onToggleDayNight: () -> Unit = {},
    isNightTheme: Boolean = true,
    columns: Int = 3,
    continueReadingRequest: Int = 0,
    /**
     * Smart routing entry point. Defaults to [onBookClick] (always reader). When non-null
     * caller — typically AppNavHost — wants WEB books to land on the detail page first.
     */
    onBookOpen: ((Book) -> Unit)? = null,
    /** Navigate to the auto-grouping rule editor in Profile. */
    onNavigateAutoGroupRules: () -> Unit = {},
    viewModel: ShelfViewModel = hiltViewModel(),
) {
    val allBooks by viewModel.books.collectAsStateWithLifecycle()
    val booksLoaded by viewModel.booksLoaded.collectAsStateWithLifecycle()
    val lastRead by viewModel.lastReadBook.collectAsStateWithLifecycle()

    // Handle "continue reading" shortcut. A monotonically increasing request
    // avoids losing repeated singleTask intents after the activity is reused.
    var handledContinueRequest by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(continueReadingRequest, lastRead, booksLoaded) {
        if (continueReadingRequest > 0 &&
            continueReadingRequest != handledContinueRequest &&
            lastRead != null &&
            booksLoaded
        ) {
            handledContinueRequest = continueReadingRequest
            withFrameNanos { }
            delay(250)
            onBookClick(lastRead!!.id)
        }
    }
    val sortMode by viewModel.sortMode.collectAsStateWithLifecycle()
    val groupNames by viewModel.groupNames.collectAsStateWithLifecycle()
    val moColors = LocalMoRealmColors.current
    var showImportMenu by remember { mutableStateOf(false) }
    var isListView by rememberSaveable { mutableStateOf(false) }
    // Folder navigation state: null = root (show all groups + ungrouped)
    var currentFolderId by rememberSaveable { mutableStateOf<String?>(null) }

    // Inline search
    var showDeleteFolderConfirm by remember { mutableStateOf<String?>(null) }
    /**
     * 自定义封面长按菜单：值非 null 时弹出 [BookCoverActionDialog]，提供"设置封面 / 移除封面"。
     * 跟"批量选中"互斥 —— 进入 batchMode 时这里清空。
     */
    var bookActionTarget by remember { mutableStateOf<Book?>(null) }
    // Batch selection mode
    var batchMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    // Inline search overlay：showSearch 控制顶部搜索栏可见性，searchQuery 是
    // 当前输入值。声明于此（早于下面 navigateToFolder LaunchedEffect 引用它们的
    // 闭包），避免 Kotlin 向前引用错误。
    var showSearch by remember { mutableStateOf(false) }

    // SnackbarHost / scope 提前到这里，保证下方 LaunchedEffect（如 organizeReport
    // 上报）能在声明处访问。原本 host 放在中段会触发 Kotlin 向前引用错误。
    val snackbarHost = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // 外层（如 PillNavigationBar 长按"书架" tab 弹分组菜单）通过 ViewModel 的
    // navigateToFolder SharedFlow 请求切到指定分组；这里订阅后直接写回
    // currentFolderId。同时把 batchMode / showSearch 等"妨碍跳转可见性"的状态
    // 重置，让用户立刻看到目标分组的内容而不是上次的批量选中残留。
    LaunchedEffect(viewModel) {
        viewModel.navigateToFolder.collect { targetFolderId ->
            currentFolderId = targetFolderId
            batchMode = false
            selectedIds = emptySet()
            showSearch = false
            viewModel.setSearchQuery("")
        }
    }
    // UX-1: showBatchDeleteConfirm 已下线 — 删除改为「立即删 + Snackbar 撤销」内联到 onClick。
    // Group management
    var showCreateGroupDialog by remember { mutableStateOf(false) }
    var showMoveToGroupDialog by remember { mutableStateOf(false) }
    var showRenameGroupDialog by remember { mutableStateOf<String?>(null) }
    val allGroups by viewModel.allGroups.collectAsStateWithLifecycle()
    val folderBookCounts by viewModel.folderBookCounts.collectAsStateWithLifecycle()
    val folderCoverUrls by viewModel.folderCoverUrls.collectAsStateWithLifecycle()
    val folderImportState by viewModel.folderImportState.collectAsStateWithLifecycle()
    // 后台 toc 刷新状态：顶栏铃铛旋转 + 红点显示。两个 flow 都来自 ShelfRefreshController
    // / books 派生，没有额外订阅成本。
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val hasAnyUpdate by viewModel.hasAnyUpdate.collectAsStateWithLifecycle()
    val groupHasUpdate by viewModel.groupHasUpdate.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    // Web book long-press cache dialog
    var showCacheBookDialog by remember { mutableStateOf<Book?>(null) }
    val isDownloading by viewModel.isCacheDownloading.collectAsStateWithLifecycle()
    val downloadProgress by viewModel.downloadProgress.collectAsStateWithLifecycle()

    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val searchResults by viewModel.searchResults.collectAsStateWithLifecycle()

    LaunchedEffect(showSearch) {
        if (!showSearch) viewModel.setSearchQuery("")
    }

    LaunchedEffect(folderImportState.running, folderImportState.message, folderImportState.error) {
        if (!folderImportState.running && folderImportState.message.isNotBlank()) {
            delay(3500)
            viewModel.clearFolderImportMessage()
        }
    }

    // 订阅"立即整理"结果上报：ViewModel 写入 organizeReport 后弹 Toast 并消费掉，
    // 避免重复弹（recomposition 不会再次触发，因为消费后 flow 变 null）。
    val organizeReport by viewModel.organizeReport.collectAsStateWithLifecycle()
    LaunchedEffect(organizeReport) {
        organizeReport?.let { msg ->
            // Toast 改 Snackbar：和主屏 SnackbarHost 同一渠道，颜色随主题，不被 pill 遮。
            snackbarHost.showSnackbar(msg)
            viewModel.consumeOrganizeReport()
        }
    }

    // Derive display books based on current folder
    val displayBooks = remember(allBooks, currentFolderId) {
        if (currentFolderId != null) {
            allBooks.filter { it.folderId == currentFolderId }
        } else {
            allBooks.filter { it.folderId == null }
        }
    }
    val folderIds = remember(groupNames) { groupNames.keys.toList() }

    // Back handler: return to root when inside a folder
    BackHandler(enabled = currentFolderId != null || batchMode) {
        if (batchMode) { batchMode = false; selectedIds = emptySet() }
        else currentFolderId = null
    }

    // Resume last read book on first launch if setting is enabled
    val resumeLastRead by viewModel.resumeLastRead.collectAsStateWithLifecycle()
    var hasResumed by remember { mutableStateOf(false) }
    LaunchedEffect(resumeLastRead, lastRead, booksLoaded) {
        if (resumeLastRead && !hasResumed && lastRead != null && booksLoaded) {
            hasResumed = true
            withFrameNanos { }
            delay(500)
            onBookClick(lastRead!!.id)
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> uri?.let { viewModel.importLocalBook(it) } }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? -> uri?.let { viewModel.importFolder(it) } }

    // 默认打开 Download 目录，避免用户从根目录开始找
    val downloadUri: Uri = remember {
        DocumentsContract.buildDocumentUri(
            "com.android.externalstorage.documents",
            "primary:${Environment.DIRECTORY_DOWNLOADS}"
        )
    }

    // UX-1: Snackbar host 用于「批量删书」的撤销窗口（5s）。原 BatchDeleteDialog 二次确认已下线。
    // host 与 scope 已在函数顶部统一声明（见 organizeReport LaunchedEffect 上方），这里不再重复创建。
    // UX-8: 删除/进入批量模式 等关键交互配震动反馈
    val haptic = LocalHapticFeedback.current

    Box(modifier = Modifier.fillMaxSize()) {
    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        // Time-based greeting
        val greeting = remember {
            val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            when (hour) {
                in 5..11 -> "早上好"
                in 12..13 -> "中午好"
                in 14..17 -> "下午好"
                in 18..22 -> "晚上好"
                else -> "深夜好"
            }
        }

        TopAppBar(
            title = {
                if (batchMode) {
                    Text("已选 ${selectedIds.size} 本", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                } else {
                    Column {
                        Text(greeting, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("享受阅读时光", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f))
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
                titleContentColor = MaterialTheme.colorScheme.onBackground,
            ),
            navigationIcon = {
                if (batchMode) {
                    IconButton(onClick = { batchMode = false; selectedIds = emptySet() }) {
                        Icon(Icons.Default.Close, "取消", tint = MaterialTheme.colorScheme.onBackground)
                    }
                }
            },
            actions = {
                if (batchMode) {
                    IconButton(
                        onClick = { if (selectedIds.isNotEmpty()) showMoveToGroupDialog = true },
                        enabled = selectedIds.isNotEmpty(),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.DriveFileMove, "移动",
                            tint = if (selectedIds.isNotEmpty()) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f))
                    }
                    IconButton(
                        onClick = {
                            // UX-1: 立即软删 + Snackbar 撤销，不再弹 BatchDeleteDialog 二次确认。
                            // snapshot 在 viewModel.batchDeleteSoft 之前抓，DB 只删 row 不删封面文件，
                            // 撤销 → restoreBooks 把整批 re-insert；不撤销 → commitCoverDeletion 收尾。
                            if (selectedIds.isEmpty()) return@IconButton
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)  // UX-8
                            val ids = selectedIds
                            val snapshot = allBooks.filter { it.id in ids }
                            batchMode = false
                            selectedIds = emptySet()
                            if (snapshot.isEmpty()) return@IconButton
                            viewModel.batchDeleteSoft(ids)
                            scope.launch {
                                val r = snackbarHost.showSnackbar(
                                    message = "已删除 ${snapshot.size} 本书",
                                    actionLabel = "撤销",
                                    duration = SnackbarDuration.Short,
                                    withDismissAction = true,
                                )
                                if (r == SnackbarResult.ActionPerformed) {
                                    viewModel.restoreBooks(snapshot)
                                } else {
                                    viewModel.commitCoverDeletion(ids)
                                }
                            }
                        },
                        enabled = selectedIds.isNotEmpty(),
                    ) {
                        Icon(Icons.Default.Delete, "删除",
                            tint = if (selectedIds.isNotEmpty()) MaterialTheme.colorScheme.error
                                   else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f))
                    }
                } else {
                // ── 顶栏布局（学 Legado）──
                // 外面只保留 4 个高频图标：日夜间 / 排序 / 导入 / 三点 overflow。
                // 立即整理 / 视图切换 / 搜索 全部进 overflow，避免顶栏挤成 7 个图标。
                // 刷新按钮整体移除——后台静默刷新由 ShelfViewModel.init 触发，状态栏
                // 角标（BookGridItem 的 lastCheckCount badge）替代过去的"红点"提示。
                IconButton(onClick = onToggleDayNight) {
                    Icon(
                        if (isNightTheme) Icons.Default.LightMode else Icons.Default.DarkMode,
                        contentDescription = "切换日夜间",
                        tint = MaterialTheme.colorScheme.onBackground,
                    )
                }
                val isOrganizing by viewModel.isOrganizing.collectAsStateWithLifecycle()
                // ── 排序按钮 ──
                var showSortMenu by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { showSortMenu = true }) {
                        Icon(
                            Icons.Default.SortByAlpha,
                            contentDescription = "排序方式",
                            tint = MaterialTheme.colorScheme.onBackground,
                        )
                    }
                    DropdownMenu(
                        expanded = showSortMenu,
                        onDismissRequest = { showSortMenu = false },
                    ) {
                        listOf(
                            "recent" to "最近阅读",
                            "addTime" to "导入时间",
                            "title" to "书名排序",
                            "format" to "格式分类",
                        ).forEach { (key, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    viewModel.setSortMode(key)
                                    showSortMenu = false
                                },
                                trailingIcon = {
                                    if (sortMode == key) Icon(
                                        Icons.Default.Check, null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(16.dp),
                                    )
                                },
                            )
                        }
                    }
                }
                Box {
                    IconButton(onClick = { showImportMenu = true }) {
                        Icon(Icons.Default.Add, "导入", tint = MaterialTheme.colorScheme.onBackground)
                    }
                    DropdownMenu(expanded = showImportMenu, onDismissRequest = { showImportMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("导入文件") },
                            leadingIcon = { Icon(Icons.Default.Description, null) },
                            onClick = {
                                showImportMenu = false
                                filePickerLauncher.launch(arrayOf(
                                    "text/plain", "application/epub+zip", "application/pdf",
                                    "application/x-mobipocket-ebook", "application/octet-stream",
                                    "application/x-cbz", "application/vnd.comicbook+zip",
                                    "application/x-cbr", "application/vnd.comicbook-rar",
                                    "application/zip", "application/x-rar-compressed", "application/x-7z-compressed",
                                ))
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("导入文件夹") },
                            leadingIcon = { Icon(Icons.Default.Folder, null) },
                            onClick = { showImportMenu = false; folderPickerLauncher.launch(downloadUri) },
                        )
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        DropdownMenuItem(
                            text = { Text("新建分组") },
                            leadingIcon = { Icon(Icons.Default.CreateNewFolder, null) },
                            onClick = { showImportMenu = false; showCreateGroupDialog = true },
                        )
                    }
                }
                // ── Overflow 三点菜单 ── 学 Legado：低频或可选项全部下沉到这里。
                // 当前装：立即整理书架 / 切换视图 / 搜索。
                var showOverflowMenu by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { showOverflowMenu = true }) {
                        Icon(
                            Icons.Default.MoreVert,
                            contentDescription = "更多",
                            tint = MaterialTheme.colorScheme.onBackground,
                        )
                    }
                    DropdownMenu(
                        expanded = showOverflowMenu,
                        onDismissRequest = { showOverflowMenu = false },
                    ) {
                        // 立即整理书架（魔棒）—— 用户笔记说这是高频操作，但顶栏拥挤
                        // 时可以下沉。整理过程中文案改成"整理中…"并用进度色指示。
                        DropdownMenuItem(
                            text = { Text(if (isOrganizing) "整理中…" else "立即整理书架") },
                            leadingIcon = {
                                if (isOrganizing) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                } else {
                                    Icon(
                                        Icons.Default.AutoFixHigh,
                                        null,
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            },
                            enabled = !isOrganizing,
                            onClick = {
                                if (!isOrganizing) {
                                    scope.launch { snackbarHost.showSnackbar("开始整理书架…") }
                                    viewModel.organizeShelf()
                                }
                                showOverflowMenu = false
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(if (isListView) "切换为网格视图" else "切换为列表视图") },
                            leadingIcon = {
                                Icon(
                                    if (isListView) Icons.Default.GridView
                                    else Icons.AutoMirrored.Filled.ViewList,
                                    null,
                                )
                            },
                            onClick = {
                                isListView = !isListView
                                showOverflowMenu = false
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("搜索书架") },
                            leadingIcon = { Icon(Icons.Default.Search, null) },
                            onClick = {
                                showSearch = true
                                showOverflowMenu = false
                            },
                        )
                    }
                }
                } // end else (non-batch actions)
            },
        )

        if (folderImportState.running || folderImportState.message.isNotBlank()) {
            FolderImportBanner(
                state = folderImportState,
                onDismiss = viewModel::clearFolderImportMessage,
            )
        }

        // Breadcrumb (like HTML: 全部 / 科幻小说)
        if (currentFolderId != null) {
            Row(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("全部", style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable { currentFolderId = null })
                Text(" / ", style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f))
                Text(groupNames[currentFolderId] ?: "文件夹",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.SemiBold)
            }
        }

        // Continue reading card (only at root)
        if (currentFolderId == null) {
            lastRead?.let { book ->
                ContinueReadingCard(
                    book = book, onClick = { onBookClick(book.id) },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }

        // Helper lambdas for batch mode.
        // Manual taps go through onBookOpen (smart router: WEB → detail page first).
        // Auto-resume / continue-reading flows use onBookClick directly to land in reader.
        val bookClick: (String) -> Unit = { id ->
            if (batchMode) {
                selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
            } else {
                val book = allBooks.find { it.id == id }
                if (onBookOpen != null && book != null) onBookOpen(book) else onBookClick(id)
            }
        }
        val bookLongClick: (String) -> Unit = { id ->
            if (!batchMode) {
                val book = allBooks.find { it.id == id }
                if (book != null && book.format == BookFormat.WEB) {
                    showCacheBookDialog = book
                } else if (book != null) {
                    // 单本书长按：弹"自定义封面 / 进入多选"菜单（默认）；
                    // WEB 书走原 cache dialog（不变）
                    bookActionTarget = book
                }
            } else {
                selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
            }
        }

        // Grid/List content
        val hasContent = displayBooks.isNotEmpty() || (currentFolderId == null && folderIds.isNotEmpty())
        if (!booksLoaded) {
            // UX-9: 用 Shimmer 骨架替代 CircularProgressIndicator，让冷启动 / 大量书加载
            // 时的等待感更柔和（先看到"卡片轮廓"再填真实内容，体感比转圈快）
            ShelfGridSkeleton(modifier = Modifier.fillMaxSize())
        } else if (!hasContent) {
            EmptyShelf(
                onImportFile = { filePickerLauncher.launch(arrayOf("text/plain", "application/epub+zip", "application/pdf", "application/octet-stream")) },
                onImportFolder = { folderPickerLauncher.launch(downloadUri) },
                modifier = Modifier.fillMaxSize(),
            )
        } else if (isListView) {
            // List view
            LazyColumn(
                contentPadding = PaddingValues(top = 4.dp, bottom = 88.dp),
            ) {
                if (currentFolderId == null) {
                    lazyItems(folderIds, key = { "folder_$it" }, contentType = { "folder" }) { folderId ->
                        FolderListItem(
                            name = groupNames[folderId] ?: "文件夹",
                            bookCount = folderBookCounts[folderId] ?: 0,
                            coverUrl = folderCoverUrls[folderId]?.firstOrNull(),
                            hasUpdate = groupHasUpdate[folderId] == true,
                            onClick = { currentFolderId = folderId },
                            onLongClick = { showDeleteFolderConfirm = folderId },
                        )
                    }
                }
                lazyItems(displayBooks, key = { it.id }, contentType = { "book" }) { book ->
                    BookListItem(
                        book = book,
                        onClick = { bookClick(book.id) },
                        onLongClick = { bookLongClick(book.id) },
                        selected = batchMode && book.id in selectedIds,
                    )
                }
            }
        } else {
            // Grid view
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 88.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (currentFolderId == null) {
                    items(folderIds.size, key = { "folder_${folderIds[it]}" }, contentType = { "folder" }) { idx ->
                        val folderId = folderIds[idx]
                        val folderGroup = allGroups.firstOrNull { it.id == folderId }
                        FolderCard(
                            name = groupNames[folderId] ?: "文件夹",
                            bookCount = folderBookCounts[folderId] ?: 0,
                            coverUrls = folderCoverUrls[folderId] ?: emptyList(),
                            customCoverUrl = folderGroup?.customCoverUrl,
                            hasUpdate = groupHasUpdate[folderId] == true,
                            onClick = { currentFolderId = folderId },
                            onLongClick = { showDeleteFolderConfirm = folderId },
                        )
                    }
                }
                items(displayBooks.size, key = { displayBooks[it].id }, contentType = { "book" }) { idx ->
                    val book = displayBooks[idx]
                    BookGridItem(
                        book = book,
                        onClick = { bookClick(book.id) },
                        onLongClick = { bookLongClick(book.id) },
                        selected = batchMode && book.id in selectedIds,
                    )
                }
            }
        }
    }

    // Inline search dialog
    if (showSearch) {
        ShelfSearchDialog(
            query = searchQuery,
            results = searchResults,
            onQueryChange = { q ->
                viewModel.setSearchQuery(q)
            },
            onBookClick = { bookId ->
                showSearch = false
                viewModel.setSearchQuery("")
                onBookClick(bookId)
            },
            onDismiss = { showSearch = false; viewModel.setSearchQuery("") },
        )
    }

    // Folder delete/rename confirmation dialog
    showDeleteFolderConfirm?.let { folderId ->
        val group = allGroups.firstOrNull { it.id == folderId }
        // 分组封面 picker —— PickVisualMedia 走 Photo Picker，自动持久化只读权限
        val groupCoverPicker = rememberLauncherForActivityResult(
            ActivityResultContracts.PickVisualMedia()
        ) { uri ->
            if (uri != null) viewModel.setCustomGroupCover(folderId, uri)
            showDeleteFolderConfirm = null
        }
        ManageFolderDialog(
            folderName = groupNames[folderId] ?: "文件夹",
            autoKeywords = group?.autoKeywords.orEmpty(),
            hasCustomCover = !group?.customCoverUrl.isNullOrBlank(),
            onRename = { showRenameGroupDialog = folderId; showDeleteFolderConfirm = null },
            onReclassify = {
                viewModel.reclassifyUngroupedBooks()
                scope.launch { snackbarHost.showSnackbar("已按关键词重新归类未分组书籍") }
                showDeleteFolderConfirm = null
            },
            onSetCover = {
                groupCoverPicker.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                )
            },
            onClearCover = {
                viewModel.clearCustomGroupCover(folderId)
                showDeleteFolderConfirm = null
            },
            onDelete = {
                viewModel.deleteFolder(folderId)
                if (currentFolderId == folderId) currentFolderId = null
                showDeleteFolderConfirm = null
            },
            onDismiss = { showDeleteFolderConfirm = null },
        )
    }

    // 书籍长按菜单：自定义封面 / 进入多选
    bookActionTarget?.let { targetBook ->
        val bookCoverPicker = rememberLauncherForActivityResult(
            ActivityResultContracts.PickVisualMedia()
        ) { uri ->
            if (uri != null) viewModel.setCustomBookCover(targetBook.id, uri)
            bookActionTarget = null
        }
        BookActionDialog(
            bookTitle = targetBook.title,
            hasCustomCover = !targetBook.customCoverUrl.isNullOrBlank(),
            onSetCover = {
                bookCoverPicker.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                )
            },
            onClearCover = {
                viewModel.clearCustomBookCover(targetBook.id)
                bookActionTarget = null
            },
            onEnterBatchMode = {
                // UX-8: 进入批量模式 = 长按确认，触觉反馈跟系统 long-press 一致
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                batchMode = true
                selectedIds = setOf(targetBook.id)
                bookActionTarget = null
            },
            onDismiss = { bookActionTarget = null },
        )
    }

    // UX-1: 批量删书已迁移到删除按钮 onClick 内联（立即软删 + Snackbar 撤销），
    // 原 BatchDeleteDialog 弹窗及 showBatchDeleteConfirm 状态已下线。

    // Create group dialog
    if (showCreateGroupDialog) {
        CreateGroupDialog(
            onConfirm = { name, keywords ->
                viewModel.createGroup(name, keywords)
                showCreateGroupDialog = false
            },
            onDismiss = { showCreateGroupDialog = false },
        )
    }

    // Move to group dialog
    if (showMoveToGroupDialog) {
        MoveToGroupDialog(
            groups = allGroups,
            onSelect = { groupId ->
                viewModel.moveToGroup(selectedIds, groupId)
                showMoveToGroupDialog = false
                batchMode = false
                selectedIds = emptySet()
            },
            onDismiss = { showMoveToGroupDialog = false },
        )
    }

    // Rename group dialog
    showRenameGroupDialog?.let { groupId ->
        val group = allGroups.firstOrNull { it.id == groupId }
        RenameGroupDialog(
            currentName = group?.name ?: groupNames[groupId] ?: "",
            currentKeywords = group?.autoKeywords.orEmpty(),
            onConfirm = { newName, keywords ->
                viewModel.updateGroup(groupId, newName, keywords)
                showRenameGroupDialog = null
            },
            onDismiss = { showRenameGroupDialog = null },
        )
    }

    // Web book cache dialog (long-press on web book)
    showCacheBookDialog?.let { book ->
        val isThisBookDownloading = isDownloading && downloadProgress.bookId == book.id
        AlertDialog(
            onDismissRequest = { showCacheBookDialog = null },
            title = { Text(book.title, maxLines = 1) },
            text = {
                Column {
                    if (isThisBookDownloading) {
                        val prog = downloadProgress
                        val done = prog.completed + prog.failed + prog.cached
                        Text("下载中 $done/${prog.total}", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { if (prog.total > 0) done.toFloat() / prog.total else 0f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Text("来源: ${book.originName.ifBlank { "未知" }}", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    }
                }
            },
            confirmButton = {
                if (isThisBookDownloading) {
                    TextButton(onClick = {
                        viewModel.stopCacheBook()
                        showCacheBookDialog = null
                    }) { Text("停止下载", color = MaterialTheme.colorScheme.error) }
                } else {
                    TextButton(onClick = {
                        val sourceUrl = book.sourceUrl ?: book.sourceId ?: return@TextButton
                        viewModel.startCacheBook(book.id, sourceUrl)
                        scope.launch { snackbarHost.showSnackbar("开始缓存: ${book.title}") }
                        showCacheBookDialog = null
                    }) { Text("缓存全本") }
                }
            },
            dismissButton = {
                Row {
                    if (!isThisBookDownloading) {
                        TextButton(onClick = {
                            batchMode = true
                            selectedIds = setOf(book.id)
                            showCacheBookDialog = null
                        }) { Text("多选") }
                    }
                    TextButton(onClick = {
                        onBookLongClick(book.id)
                        showCacheBookDialog = null
                    }) { Text("详情") }
                }
            },
        )
    }
    // 浮在药丸导航栏之上：pill 高 64dp + 底 padding 16dp ≈ 80dp，
    // 这里给 96dp 让 Snackbar 与 pill 之间留 ~16dp 视觉间隙，避免提示被吞掉。
    ThemedSnackbarHost(
        snackbarHost,
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = 96.dp),
    )
    }
}

@Composable
private fun FolderImportBanner(
    state: FolderImportState,
    onDismiss: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(16.dp),
        color = if (state.error == null) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.errorContainer
        },
        tonalElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.running) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else {
                Icon(
                    if (state.error == null) Icons.Default.CheckCircle else Icons.Default.Error,
                    null,
                    tint = if (state.error == null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    state.message.ifBlank { if (state.running) "正在导入文件夹…" else "导入完成" },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                )
                val detail = when {
                    state.error != null -> state.error
                    state.running && state.importedCount > 0 -> "已加入 ${state.importedCount} 本，正在继续处理封面/元数据"
                    state.running -> "请稍候，正在后台扫描和导入"
                    state.importedCount > 0 -> "共导入 ${state.importedCount} 本书"
                    else -> "可以换个文件夹再试"
                }
                Text(
                    detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                )
            }
            if (!state.running) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, "关闭")
                }
            }
        }
    }
}

// region Extracted dialog composables

@Composable
private fun ShelfSearchDialog(
    query: String,
    results: List<Book>,
    onQueryChange: (String) -> Unit,
    onBookClick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("搜索书架") },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    placeholder = { Text("输入书名或作者") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        cursorColor = MaterialTheme.colorScheme.primary,
                    ),
                )
                if (results.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 420.dp),
                        contentPadding = PaddingValues(bottom = 8.dp),
                    ) {
                        lazyItems(results, key = { it.id }) { book ->
                            Text(
                                book.title,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onBookClick(book.id) }
                                    .padding(vertical = 12.dp, horizontal = 4.dp),
                                maxLines = 1,
                            )
                        }
                    }
                }
                if (query.isNotEmpty() && results.isEmpty()) {
                    Text(
                        "未找到匹配的书籍",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

@Composable
private fun ManageFolderDialog(
    folderName: String,
    autoKeywords: String,
    hasCustomCover: Boolean,
    onRename: () -> Unit,
    onReclassify: () -> Unit,
    onSetCover: () -> Unit,
    onClearCover: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("管理分组「$folderName」") },
        text = {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onRename() }
                        .padding(vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Edit, null, modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.width(12.dp))
                    Text("重命名", style = MaterialTheme.typography.bodyLarge)
                }
                if (autoKeywords.isNotBlank()) {
                    Text(
                        "自动关键词：${autoKeywords.lines().joinToString(" / ")}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // 自定义封面入口（设置 / 移除二选一）
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSetCover() }
                        .padding(vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Image, null, modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        if (hasCustomCover) "更换自定义封面" else "设置自定义封面",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                if (hasCustomCover) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onClearCover() }
                            .padding(vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.HideImage, null, modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurface)
                        Spacer(Modifier.width(12.dp))
                        Text("恢复默认封面", style = MaterialTheme.typography.bodyLarge)
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onReclassify() }
                        .padding(vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.AutoAwesome, null, modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Text("按关键词重新归类", style = MaterialTheme.typography.bodyLarge)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onDelete() }
                        .padding(vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Delete, null, modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(12.dp))
                    Text("删除分组", style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/**
 * 书籍长按菜单：自定义封面（设置/移除）+ 进入多选模式。
 *
 * 为什么合并在一个对话框：
 *  - 长按原本只进多选，现在加封面后两条路径共用一个入口，不分散心智
 *  - 对话框内 3 项而已，不需要独立菜单库
 */
@Composable
private fun BookActionDialog(
    bookTitle: String,
    hasCustomCover: Boolean,
    onSetCover: () -> Unit,
    onClearCover: () -> Unit,
    onEnterBatchMode: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                bookTitle.take(20) + if (bookTitle.length > 20) "…" else "",
                maxLines = 1,
            )
        },
        text = {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSetCover() }
                        .padding(vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Image, null, modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        if (hasCustomCover) "更换自定义封面" else "设置自定义封面",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                if (hasCustomCover) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onClearCover() }
                            .padding(vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.HideImage, null, modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurface)
                        Spacer(Modifier.width(12.dp))
                        Text("恢复默认封面", style = MaterialTheme.typography.bodyLarge)
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onEnterBatchMode() }
                        .padding(vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.CheckCircle, null, modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("进入多选模式", style = MaterialTheme.typography.bodyLarge)
                        // UX-5 (可发现性): 用户进入多选模式后顶栏会变化, 但当前用户不知道
                        // 多选能干什么 — 顺手补一行说明, 减少"进了多选才发现要的功能没有"的犹豫.
                        Text(
                            "可批量移动到分组 / 批量删除",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun BatchDeleteDialog(
    count: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("批量删除") },
        text = { Text("确定要从书架移除选中的 $count 本书吗？本地文件不会被删除。") },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("删除", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun CreateGroupDialog(
    onConfirm: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var groupName by remember { mutableStateOf("") }
    var keywords by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建分组") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = groupName,
                    onValueChange = { groupName = it },
                    placeholder = { Text("分组名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary, cursorColor = MaterialTheme.colorScheme.primary,
                    ),
                )
                OutlinedTextField(
                    value = keywords,
                    onValueChange = { keywords = it },
                    placeholder = { Text("自动归类关键词，如：修仙，玄幻，仙侠") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary, cursorColor = MaterialTheme.colorScheme.primary,
                    ),
                )
                Text("会匹配书名、作者、简介、分类/标签和路径；默认只归类未分组书籍。", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (groupName.isNotBlank()) onConfirm(groupName.trim(), keywords.trim()) },
                enabled = groupName.isNotBlank(),
            ) {
                Text("创建", color = if (groupName.isNotBlank()) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/** @param onSelect receives null for "ungrouped", or the group id */
@Composable
private fun MoveToGroupDialog(
    groups: List<BookGroup>,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("移动到分组") },
        text = {
            Column {
                // "Ungrouped" option
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(null) }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.FolderOff, null, modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                    Spacer(Modifier.width(12.dp))
                    Text("不分组", style = MaterialTheme.typography.bodyLarge)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                groups.forEach { group ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(group.id) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.Folder, null, modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f))
                        Spacer(Modifier.width(12.dp))
                        Text(group.name, style = MaterialTheme.typography.bodyLarge)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun RenameGroupDialog(
    currentName: String,
    currentKeywords: String,
    onConfirm: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var newName by remember { mutableStateOf(currentName) }
    var keywords by remember { mutableStateOf(currentKeywords) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名分组") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary, cursorColor = MaterialTheme.colorScheme.primary,
                    ),
                )
                OutlinedTextField(
                    value = keywords,
                    onValueChange = { keywords = it },
                    placeholder = { Text("自动归类关键词") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary, cursorColor = MaterialTheme.colorScheme.primary,
                    ),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (newName.isNotBlank()) onConfirm(newName.trim(), keywords.trim()) },
                enabled = newName.isNotBlank(),
            ) { Text("保存", color = MaterialTheme.colorScheme.primary) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

// endregion
