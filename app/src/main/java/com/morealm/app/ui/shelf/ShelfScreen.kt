package com.morealm.app.ui.shelf

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items as lazyItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.morealm.app.domain.entity.Book
import com.morealm.app.domain.entity.BookGroup
import com.morealm.app.ui.theme.LocalMoRealmColors
import com.morealm.app.presentation.shelf.ShelfViewModel
import androidx.activity.compose.BackHandler
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
    continueReading: Boolean = false,
    viewModel: ShelfViewModel = hiltViewModel(),
) {
    val books by viewModel.books.collectAsState()
    val lastRead by viewModel.lastReadBook.collectAsState()

    // Handle "continue reading" shortcut
    var handledContinue by remember { mutableStateOf(false) }
    LaunchedEffect(continueReading, lastRead) {
        if (continueReading && !handledContinue && lastRead != null) {
            handledContinue = true
            onBookClick(lastRead!!.id)
        }
    }
    val sortMode by viewModel.sortMode.collectAsState()
    val groupNames by viewModel.groupNames.collectAsState()
    val moColors = LocalMoRealmColors.current
    var showSortMenu by remember { mutableStateOf(false) }
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
    val allGroups by viewModel.allGroups.collectAsState()

    // Back handler: return to root when inside a folder
    BackHandler(enabled = currentFolderId != null || batchMode) {
        if (batchMode) { batchMode = false; selectedIds = emptySet() }
        else currentFolderId = null
    }

    // Resume last read book on first launch if setting is enabled
    val resumeLastRead by viewModel.resumeLastRead.collectAsState()
    var hasResumed by remember { mutableStateOf(false) }
    LaunchedEffect(resumeLastRead, lastRead) {
        if (resumeLastRead && !hasResumed && lastRead != null) {
            hasResumed = true
            onBookClick(lastRead!!.id)
        }
    }

    val sortedBooks = remember(books, sortMode) { viewModel.sortedBooks(books) }
    val displayBooks = remember(sortedBooks, currentFolderId) {
        if (currentFolderId == null) sortedBooks.filter { it.folderId == null }
        else sortedBooks.filter { it.folderId == currentFolderId }
            .sortedWith(NaturalOrderComparator)
    }
    val folderIds = remember(sortedBooks) {
        sortedBooks.mapNotNull { it.folderId }.distinct()
    }
    val folderBookCounts = remember(sortedBooks) {
        sortedBooks.filter { it.folderId != null }
            .groupingBy { it.folderId!! }.eachCount()
    }
    val folderCoverUrls = remember(sortedBooks) {
        sortedBooks.filter { it.folderId != null }
            .groupBy { it.folderId!! }
            .mapValues { (_, books) -> books.take(4).map { it.coverUrl } }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> uri?.let { viewModel.importLocalBook(it) } }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? -> uri?.let { viewModel.importFolder(it) } }

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
                    TextButton(onClick = {
                        selectedIds = displayBooks.map { it.id }.toSet()
                    }) { Text("全选", color = moColors.accent) }
                    IconButton(
                        onClick = { if (selectedIds.isNotEmpty()) showMoveToGroupDialog = true },
                        enabled = selectedIds.isNotEmpty(),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.DriveFileMove, "移动",
                            tint = if (selectedIds.isNotEmpty()) moColors.accent
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
                Box {
                    IconButton(onClick = { showSortMenu = true }) {
                        Icon(Icons.Default.SortByAlpha, "排序",
                            tint = MaterialTheme.colorScheme.onBackground)
                    }
                    DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {
                        listOf("recent" to "最近阅读", "addTime" to "导入时间", "title" to "书名排序", "format" to "格式分类")
                            .forEach { (key, label) ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    onClick = { viewModel.setSortMode(key); showSortMenu = false },
                                    trailingIcon = {
                                        if (sortMode == key) Icon(Icons.Default.Check, null,
                                            tint = moColors.accent, modifier = Modifier.size(16.dp))
                                    },
                                )
                            }
                    }
                }
                IconButton(onClick = { isListView = !isListView }) {
                    Icon(
                        if (isListView) Icons.Default.GridView else Icons.AutoMirrored.Filled.ViewList,
                        contentDescription = if (isListView) "网格视图" else "列表视图",
                        tint = MaterialTheme.colorScheme.onBackground,
                    )
                }
                IconButton(onClick = { showSearch = true }) {
                    Icon(Icons.Default.Search, "搜索", tint = MaterialTheme.colorScheme.onBackground)
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
                                ))
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("导入文件夹") },
                            leadingIcon = { Icon(Icons.Default.Folder, null) },
                            onClick = { showImportMenu = false; folderPickerLauncher.launch(null) },
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

        // Breadcrumb (like HTML: 全部 / 科幻小说)
        if (currentFolderId != null) {
            Row(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("全部", style = MaterialTheme.typography.bodyLarge,
                    color = moColors.accent, fontWeight = FontWeight.SemiBold,
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

        // Helper lambdas for batch mode
        val bookClick: (String) -> Unit = { id ->
            if (batchMode) {
                selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
            } else onBookClick(id)
        }
        val bookLongClick: (String) -> Unit = { id ->
            if (!batchMode) {
                batchMode = true
                selectedIds = setOf(id)
            } else {
                selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
            }
        }

        // Grid/List content
        if (sortedBooks.isEmpty()) {
            EmptyShelf(
                onImportFile = { filePickerLauncher.launch(arrayOf("text/plain", "application/epub+zip")) },
                onImportFolder = { folderPickerLauncher.launch(null) },
                modifier = Modifier.fillMaxSize(),
            )
        } else if (isListView) {
            // List view
            LazyColumn(
                contentPadding = PaddingValues(vertical = 4.dp),
            ) {
                if (currentFolderId == null) {
                    lazyItems(folderIds, key = { "folder_$it" }) { folderId ->
                        FolderCard(
                            name = groupNames[folderId] ?: "文件夹",
                            bookCount = folderBookCounts[folderId] ?: 0,
                            coverUrls = folderCoverUrls[folderId] ?: emptyList(),
                            onClick = { currentFolderId = folderId },
                            onLongClick = { showDeleteFolderConfirm = folderId },
                        )
                    }
                }
                lazyItems(displayBooks, key = { it.id }) { book ->
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
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (currentFolderId == null) {
                    items(folderIds, key = { "folder_$it" }) { folderId ->
                        FolderCard(
                            name = groupNames[folderId] ?: "文件夹",
                            bookCount = folderBookCounts[folderId] ?: 0,
                            coverUrls = folderCoverUrls[folderId] ?: emptyList(),
                            onClick = { currentFolderId = folderId },
                            onLongClick = { showDeleteFolderConfirm = folderId },
                        )
                    }
                    items(displayBooks, key = { it.id }) { book ->
                        BookGridItem(
                            book = book,
                            onClick = { bookClick(book.id) },
                            onLongClick = { bookLongClick(book.id) },
                            selected = batchMode && book.id in selectedIds,
                        )
                    }
                } else {
                    items(displayBooks, key = { it.id }) { book ->
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
    }

    // Inline search dialog
    if (showSearch) {
        AlertDialog(
            onDismissRequest = { showSearch = false; searchQuery = ""; searchResults = emptyList() },
            title = { Text("搜索书架") },
            text = {
                Column {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { q ->
                            searchQuery = q
                            if (q.length >= 1) {
                                viewModel.searchBooks(q) { searchResults = it }
                            } else {
                                searchResults = emptyList()
                            }
                        },
                        placeholder = { Text("输入书名或作者") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = moColors.accent,
                            cursorColor = moColors.accent,
                        ),
                    )
                    Spacer(Modifier.height(8.dp))
                    searchResults.take(10).forEach { book ->
                        Text(
                            book.title,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showSearch = false
                                    searchQuery = ""
                                    searchResults = emptyList()
                                    onBookClick(book.id)
                                }
                                .padding(vertical = 8.dp, horizontal = 4.dp),
                            maxLines = 1,
                        )
                    }
                    if (searchQuery.isNotEmpty() && searchResults.isEmpty()) {
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
                TextButton(onClick = { showSearch = false; searchQuery = ""; searchResults = emptyList() }) {
                    Text("关闭")
                }
            },
        )
    }

    // Folder delete/rename confirmation dialog
    showDeleteFolderConfirm?.let { folderId ->
        val folderName = groupNames[folderId] ?: "文件夹"
        val bookCount = books.count { it.folderId == folderId }
        AlertDialog(
            onDismissRequest = { showDeleteFolderConfirm = null },
            title = { Text("管理分组「$folderName」") },
            text = {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showRenameGroupDialog = folderId; showDeleteFolderConfirm = null }
                            .padding(vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.Edit, null, modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurface)
                        Spacer(Modifier.width(12.dp))
                        Text("重命名", style = MaterialTheme.typography.bodyLarge)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                viewModel.deleteFolder(folderId)
                                if (currentFolderId == folderId) currentFolderId = null
                                showDeleteFolderConfirm = null
                            }
                            .padding(vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.Delete, null, modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(12.dp))
                        Text("删除分组及 $bookCount 本书", style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showDeleteFolderConfirm = null }) { Text("取消") }
            },
        )
    }

    // Batch delete confirmation dialog
    if (showBatchDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showBatchDeleteConfirm = false },
            title = { Text("批量删除") },
            text = { Text("确定要从书架移除选中的 ${selectedIds.size} 本书吗？本地文件不会被删除。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.batchDelete(selectedIds)
                    showBatchDeleteConfirm = false
                    batchMode = false
                    selectedIds = emptySet()
                }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showBatchDeleteConfirm = false }) {
                    Text("取消")
                }
            },
        )
    }

    // Create group dialog
    if (showCreateGroupDialog) {
        var groupName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreateGroupDialog = false },
            title = { Text("新建分组") },
            text = {
                OutlinedTextField(
                    value = groupName,
                    onValueChange = { groupName = it },
                    placeholder = { Text("分组名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = moColors.accent, cursorColor = moColors.accent,
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (groupName.isNotBlank()) {
                            viewModel.createGroup(groupName.trim())
                            showCreateGroupDialog = false
                        }
                    },
                    enabled = groupName.isNotBlank(),
                ) { Text("创建", color = if (groupName.isNotBlank()) moColors.accent else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)) }
            },
            dismissButton = {
                TextButton(onClick = { showCreateGroupDialog = false }) { Text("取消") }
            },
        )
    }

    // Move to group dialog
    if (showMoveToGroupDialog) {
        AlertDialog(
            onDismissRequest = { showMoveToGroupDialog = false },
            title = { Text("移动到分组") },
            text = {
                Column {
                    // "Ungrouped" option
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                viewModel.moveToGroup(selectedIds, null)
                                showMoveToGroupDialog = false
                                batchMode = false
                                selectedIds = emptySet()
                            }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.FolderOff, null, modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                        Spacer(Modifier.width(12.dp))
                        Text("不分组", style = MaterialTheme.typography.bodyLarge)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                    allGroups.forEach { group ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.moveToGroup(selectedIds, group.id)
                                    showMoveToGroupDialog = false
                                    batchMode = false
                                    selectedIds = emptySet()
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Default.Folder, null, modifier = Modifier.size(20.dp),
                                tint = moColors.accent.copy(alpha = 0.7f))
                            Spacer(Modifier.width(12.dp))
                            Text(group.name, style = MaterialTheme.typography.bodyLarge)
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showMoveToGroupDialog = false }) { Text("取消") }
            },
        )
    }

    // Rename group dialog
    showRenameGroupDialog?.let { groupId ->
        val currentName = groupNames[groupId] ?: ""
        var newName by remember { mutableStateOf(currentName) }
        AlertDialog(
            onDismissRequest = { showRenameGroupDialog = null },
            title = { Text("重命名分组") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = moColors.accent, cursorColor = moColors.accent,
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (newName.isNotBlank()) {
                            viewModel.renameGroup(groupId, newName.trim())
                            showRenameGroupDialog = null
                        }
                    },
                    enabled = newName.isNotBlank(),
                ) { Text("保存", color = moColors.accent) }
            },
            dismissButton = {
                TextButton(onClick = { showRenameGroupDialog = null }) { Text("取消") }
            },
        )
    }
}

/** Natural order comparator: sorts "第1章" before "第10章", "vol2" before "vol10" */
private object NaturalOrderComparator : Comparator<Book> {
    private val numPattern = Regex("(\\d+)")

    override fun compare(a: Book, b: Book): Int {
        val sa = a.title
        val sb = b.title
        val partsA = numPattern.split(sa)
        val partsB = numPattern.split(sb)
        val numsA = numPattern.findAll(sa).map { it.value }.toList()
        val numsB = numPattern.findAll(sb).map { it.value }.toList()

        val maxParts = maxOf(partsA.size + numsA.size, partsB.size + numsB.size)
        var ia = 0; var na = 0; var ib = 0; var nb = 0
        for (i in 0 until maxParts) {
            val isNum = i % 2 == 1
            if (isNum) {
                val numA = numsA.getOrNull(na)?.toLongOrNull() ?: -1L
                val numB = numsB.getOrNull(nb)?.toLongOrNull() ?: -1L
                na++; nb++
                val cmp = numA.compareTo(numB)
                if (cmp != 0) return cmp
            } else {
                val pa = partsA.getOrElse(ia) { "" }
                val pb = partsB.getOrElse(ib) { "" }
                ia++; ib++
                val cmp = pa.compareTo(pb, ignoreCase = true)
                if (cmp != 0) return cmp
            }
        }
        return sa.compareTo(sb, ignoreCase = true)
    }
}
