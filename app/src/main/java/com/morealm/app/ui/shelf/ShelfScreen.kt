package com.morealm.app.ui.shelf

import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.activity.compose.BackHandler
import kotlinx.coroutines.delay
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
    var showSearch by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<Book>>(emptyList()) }
    var showDeleteFolderConfirm by remember { mutableStateOf<String?>(null) }
    // Batch selection mode
    var batchMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    var showBatchDeleteConfirm by remember { mutableStateOf(false) }
    // Group management
    var showCreateGroupDialog by remember { mutableStateOf(false) }
    var showMoveToGroupDialog by remember { mutableStateOf(false) }
    var showRenameGroupDialog by remember { mutableStateOf<String?>(null) }
    val allGroups by viewModel.allGroups.collectAsStateWithLifecycle()
    val folderBookCounts by viewModel.folderBookCounts.collectAsStateWithLifecycle()
    val folderCoverUrls by viewModel.folderCoverUrls.collectAsStateWithLifecycle()
    val folderImportState by viewModel.folderImportState.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    // Web book long-press cache dialog
    var showCacheBookDialog by remember { mutableStateOf<Book?>(null) }
    val isDownloading by viewModel.isCacheDownloading.collectAsStateWithLifecycle()
    val downloadProgress by viewModel.cacheDownloadProgress.collectAsStateWithLifecycle()

    LaunchedEffect(showSearch, searchQuery) {
        val query = searchQuery.trim()
        if (!showSearch || query.isEmpty()) {
            searchResults = emptyList()
            return@LaunchedEffect
        }
        delay(300)
        viewModel.searchBooks(query) { results ->
            if (showSearch && searchQuery.trim() == query) {
                searchResults = results
            }
        }
    }

    LaunchedEffect(folderImportState.running, folderImportState.message, folderImportState.error) {
        if (!folderImportState.running && folderImportState.message.isNotBlank()) {
            delay(3500)
            viewModel.clearFolderImportMessage()
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

    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
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
                        onClick = { if (selectedIds.isNotEmpty()) showBatchDeleteConfirm = true },
                        enabled = selectedIds.isNotEmpty(),
                    ) {
                        Icon(Icons.Default.Delete, "删除",
                            tint = if (selectedIds.isNotEmpty()) MaterialTheme.colorScheme.error
                                   else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f))
                    }
                } else {
                IconButton(onClick = onToggleDayNight) {
                    Icon(
                        if (isNightTheme) Icons.Default.LightMode else Icons.Default.DarkMode,
                        contentDescription = "切换日夜间",
                        tint = MaterialTheme.colorScheme.onBackground,
                    )
                }
                // 自动分组规则的配置入口走 Profile 页，顶栏不再放置田字格按钮。
                // 仅在此处持有 isOrganizing 给"立即整理"按钮 + 更多菜单同款项使用。
                val isOrganizing by viewModel.isOrganizing.collectAsStateWithLifecycle()
                // 立即整理书架（魔棒）— 高频操作上提到顶栏。
                // 整理过程中按钮位置不变，用 spinner 替换图标提供进度反馈。
                IconButton(
                    onClick = { viewModel.organizeShelf() },
                    enabled = !isOrganizing,
                ) {
                    if (isOrganizing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    } else {
                        Icon(
                            Icons.Default.AutoFixHigh,
                            contentDescription = "立即整理书架",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                // 列表 / 网格视图切换 — 高频操作上提到顶栏。
                IconButton(onClick = { isListView = !isListView }) {
                    Icon(
                        if (isListView) Icons.Default.GridView else Icons.AutoMirrored.Filled.ViewList,
                        contentDescription = if (isListView) "切换为网格视图" else "切换为列表视图",
                        tint = MaterialTheme.colorScheme.onBackground,
                    )
                }
                IconButton(onClick = { showSearch = true }) {
                    Icon(Icons.Default.Search, "搜索", tint = MaterialTheme.colorScheme.onBackground)
                }
                // ── More menu: 立即整理 / 检查更新 / 排序 / 视图 ──
                // 顶栏已有同款按钮，这里保留入口便于习惯走二级菜单的用户。
                val isRefreshingToc by viewModel.isRefreshing.collectAsStateWithLifecycle()
                var showMoreMenu by remember { mutableStateOf(false) }
                var showSortSubmenu by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { showMoreMenu = true }) {
                        Icon(Icons.Default.MoreVert, "更多", tint = MaterialTheme.colorScheme.onBackground)
                    }
                    DropdownMenu(expanded = showMoreMenu, onDismissRequest = {
                        showMoreMenu = false; showSortSubmenu = false
                    }) {
                        DropdownMenuItem(
                            text = { Text("立即整理书架") },
                            leadingIcon = {
                                if (isOrganizing) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp), strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                } else {
                                    Icon(Icons.Default.AutoFixHigh, null,
                                        tint = MaterialTheme.colorScheme.primary)
                                }
                            },
                            enabled = !isOrganizing,
                            onClick = { showMoreMenu = false; viewModel.organizeShelf() },
                        )
                        DropdownMenuItem(
                            text = { Text(if (isRefreshingToc) "停止检查更新" else "检查更新") },
                            leadingIcon = {
                                if (isRefreshingToc) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp), strokeWidth = 2.dp,
                                    )
                                } else {
                                    Icon(Icons.Default.Refresh, null)
                                }
                            },
                            onClick = {
                                showMoreMenu = false
                                if (isRefreshingToc) viewModel.cancelRefresh()
                                else viewModel.refreshAllBooks()
                            },
                        )
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        DropdownMenuItem(
                            text = { Text("排序方式") },
                            leadingIcon = { Icon(Icons.Default.SortByAlpha, null) },
                            trailingIcon = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
                            onClick = { showSortSubmenu = true },
                        )
                        DropdownMenuItem(
                            text = { Text(if (isListView) "网格视图" else "列表视图") },
                            leadingIcon = {
                                Icon(
                                    if (isListView) Icons.Default.GridView else Icons.AutoMirrored.Filled.ViewList,
                                    null,
                                )
                            },
                            onClick = { isListView = !isListView; showMoreMenu = false },
                        )
                    }
                    // Sort sub-menu rendered as a sibling Box so it overlays
                    // independently — Compose has no native nested DropdownMenu.
                    DropdownMenu(
                        expanded = showSortSubmenu,
                        onDismissRequest = {
                            showSortSubmenu = false; showMoreMenu = false
                        },
                    ) {
                        listOf("recent" to "最近阅读", "addTime" to "导入时间", "title" to "书名排序", "format" to "格式分类")
                            .forEach { (key, label) ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    onClick = {
                                        viewModel.setSortMode(key)
                                        showSortSubmenu = false
                                        showMoreMenu = false
                                    },
                                    trailingIcon = {
                                        if (sortMode == key) Icon(Icons.Default.Check, null,
                                            tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
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
                // Check if it's a web book — show cache dialog
                val book = allBooks.find { it.id == id }
                if (book != null && book.format == BookFormat.WEB) {
                    showCacheBookDialog = book
                } else {
                    batchMode = true
                    selectedIds = setOf(id)
                }
            } else {
                selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
            }
        }

        // Grid/List content
        val hasContent = displayBooks.isNotEmpty() || (currentFolderId == null && folderIds.isNotEmpty())
        if (!booksLoaded) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    modifier = Modifier.size(28.dp),
                    strokeWidth = 2.dp,
                )
            }
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
                        FolderCard(
                            name = groupNames[folderId] ?: "文件夹",
                            bookCount = folderBookCounts[folderId] ?: 0,
                            coverUrls = folderCoverUrls[folderId] ?: emptyList(),
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
                searchQuery = q
                if (q.isBlank()) {
                    searchResults = emptyList()
                }
            },
            onBookClick = { bookId ->
                showSearch = false
                searchQuery = ""
                searchResults = emptyList()
                onBookClick(bookId)
            },
            onDismiss = { showSearch = false; searchQuery = ""; searchResults = emptyList() },
        )
    }

    // Folder delete/rename confirmation dialog
    showDeleteFolderConfirm?.let { folderId ->
        val group = allGroups.firstOrNull { it.id == folderId }
        ManageFolderDialog(
            folderName = groupNames[folderId] ?: "文件夹",
            autoKeywords = group?.autoKeywords.orEmpty(),
            onRename = { showRenameGroupDialog = folderId; showDeleteFolderConfirm = null },
            onReclassify = {
                viewModel.reclassifyUngroupedBooks()
                Toast.makeText(context, "已按关键词重新归类未分组书籍", Toast.LENGTH_SHORT).show()
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

    // Batch delete confirmation dialog
    if (showBatchDeleteConfirm) {
        BatchDeleteDialog(
            count = selectedIds.size,
            onConfirm = {
                viewModel.batchDelete(selectedIds)
                showBatchDeleteConfirm = false
                batchMode = false
                selectedIds = emptySet()
            },
            onDismiss = { showBatchDeleteConfirm = false },
        )
    }

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
                        Toast.makeText(context, "开始缓存: ${book.title}", Toast.LENGTH_SHORT).show()
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
    onRename: () -> Unit,
    onReclassify: () -> Unit,
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
