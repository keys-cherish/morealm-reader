package com.morealm.app.ui.cache

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
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
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.morealm.app.domain.entity.Book
import com.morealm.app.presentation.cache.CacheBookViewModel
import com.morealm.app.service.CacheBookService

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CacheBookScreen(
    onBack: () -> Unit,
    /** 单击书卡片直接进阅读器 — 任务 #3。 */
    onOpenReader: (bookId: String) -> Unit = {},
    viewModel: CacheBookViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val webBooks by viewModel.webBooks.collectAsStateWithLifecycle()
    val cacheStats by viewModel.cacheStats.collectAsStateWithLifecycle()
    val isDownloading by viewModel.isDownloading.collectAsStateWithLifecycle()
    val progresses by viewModel.progresses.collectAsStateWithLifecycle()
    val exportState by viewModel.exportState.collectAsStateWithLifecycle()
    // #4 multi-select state
    val multiSelectMode by viewModel.multiSelectMode.collectAsStateWithLifecycle()
    val selectedIds by viewModel.selectedBookIds.collectAsStateWithLifecycle()
    val oneShotToast by viewModel.oneShotToast.collectAsStateWithLifecycle()
    // Stage A 范围导出对话框需要的章节预览数据。
    val chaptersForRange by viewModel.chaptersForRange.collectAsStateWithLifecycle()

    /** ⋮ 菜单展开状态（顶栏）。 */
    var showTopMenu by remember { mutableStateOf(false) }
    /** 「全部清空」二次确认对话框。 */
    var showClearAllConfirm by remember { mutableStateOf(false) }
    /** Stage A 范围导出对话框（顶部菜单触发）。 */
    var showRangeDialog by remember { mutableStateOf(false) }

    // 一次性 toast 消费
    LaunchedEffect(oneShotToast) {
        oneShotToast?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.consumeToast()
        }
    }

    // SAF document picker for TXT export. The MIME hint "text/plain" makes most file
    // managers default to .txt extension; the user-suggested filename is set per-launch.
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        val bookId = viewModel.pendingExportBookId
        val startIdx = viewModel.pendingExportStartIndex
        val endIdx = viewModel.pendingExportEndIndex
        viewModel.pendingExportBookId = null
        viewModel.pendingExportStartIndex = 0
        viewModel.pendingExportEndIndex = -1
        if (uri != null && bookId != null) {
            val book = webBooks.firstOrNull { it.id == bookId }
            if (book != null) {
                viewModel.exportTxt(book, uri, startIdx, endIdx)
            }
        }
    }

    // Stage B SAF launcher for EPUB. 跟 TXT 走分开的 launcher 因为 MIME 类型固定，
    // 又不希望每次提示用户「请选择导出格式」拖慢节奏 —— Per-book 菜单里两个入口
    // 各自直达。文件名后缀也分别决定（.txt / .epub）。
    val epubLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/epub+zip"),
    ) { uri ->
        val bookId = viewModel.pendingExportBookId
        val startIdx = viewModel.pendingExportStartIndex
        val endIdx = viewModel.pendingExportEndIndex
        viewModel.pendingExportBookId = null
        viewModel.pendingExportStartIndex = 0
        viewModel.pendingExportEndIndex = -1
        if (uri != null && bookId != null) {
            val book = webBooks.firstOrNull { it.id == bookId }
            if (book != null) {
                viewModel.exportEpub(book, uri, startIdx, endIdx)
            }
        }
    }

    // Stage C：EPUB 多卷文件夹 launcher。对话框里用户选了 EPUB + 分卷大小 > 0 时
    // 触发 —— 用户选一次目录，ViewModel 在该目录下批量建多个 .epub 卷文件。比起
    // 让用户手点 N 次 CreateDocument，这是 Legado 早期就用的体验。
    //
    // 取了 takePersistableUriPermission 让回调期间 ContentResolver 不丢权限；这条
    // 权限不长期持有（用完即弃，不污染用户的"应用持久授权列表"），所以没在退出
    // 时显式 release —— 系统进程清理或下次设备重启会自然回收。
    val epubFolderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { treeUri ->
        if (treeUri == null) {
            // 用户取消：丢弃 pending 状态。
            viewModel.pendingMultiVolume = null
            return@rememberLauncherForActivityResult
        }
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                treeUri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        viewModel.exportEpubMultiVolume(treeUri)
    }

    // Refresh stats when download completes
    LaunchedEffect(isDownloading) {
        if (!isDownloading) viewModel.loadCacheStats()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (multiSelectMode) {
                        Text("已选 ${selectedIds.size} 本", fontWeight = FontWeight.Bold)
                    } else {
                        Text("离线缓存", fontWeight = FontWeight.Bold)
                    }
                },
                navigationIcon = {
                    if (multiSelectMode) {
                        IconButton(onClick = { viewModel.exitMultiSelect() }) {
                            Icon(Icons.Default.Close, "退出多选")
                        }
                    } else {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                        }
                    }
                },
                actions = {
                    when {
                        multiSelectMode -> {
                            // 多选模式：全选 + 删除
                            IconButton(onClick = { viewModel.selectAll() }) {
                                Icon(Icons.Default.SelectAll, "全选")
                            }
                            IconButton(
                                onClick = { viewModel.clearSelectedCaches() },
                                enabled = selectedIds.isNotEmpty(),
                            ) {
                                Icon(
                                    Icons.Default.DeleteOutline,
                                    "清空选中",
                                    tint = if (selectedIds.isNotEmpty()) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                                    },
                                )
                            }
                        }
                        isDownloading -> {
                            IconButton(onClick = { viewModel.stopDownload() }) {
                                Icon(Icons.Default.Close, "停止下载")
                            }
                        }
                        else -> {
                            Box {
                                IconButton(onClick = { showTopMenu = true }) {
                                    Icon(Icons.Default.MoreVert, "更多")
                                }
                                DropdownMenu(
                                    expanded = showTopMenu,
                                    onDismissRequest = { showTopMenu = false },
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("选择多本") },
                                        leadingIcon = { Icon(Icons.Default.CheckBox, null) },
                                        onClick = {
                                            showTopMenu = false
                                            viewModel.enterMultiSelect()
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("按范围导出") },
                                        leadingIcon = { Icon(Icons.Default.FilterList, null) },
                                        onClick = {
                                            showTopMenu = false
                                            showRangeDialog = true
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("清理无效缓存") },
                                        leadingIcon = { Icon(Icons.Default.CleaningServices, null) },
                                        onClick = {
                                            showTopMenu = false
                                            viewModel.clearOrphanedCache()
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("全部清空", color = MaterialTheme.colorScheme.error) },
                                        leadingIcon = {
                                            Icon(
                                                Icons.Default.DeleteForever,
                                                null,
                                                tint = MaterialTheme.colorScheme.error,
                                            )
                                        },
                                        onClick = {
                                            showTopMenu = false
                                            showClearAllConfirm = true
                                        },
                                    )
                                }
                            }
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
                .padding(padding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            // 顶栏全局总进度 — 跨所有正在缓存的书聚合 sum。这条条只展示总览，
            // 不再表达任意一本书的状态；每本书的本地进度由 LazyColumn 中的卡片
            // 各自显示（progresses[book.id]）。两者不再冲突。
            if (isDownloading && progresses.isNotEmpty()) {
                val all = progresses.values
                val totalSum = all.sumOf { it.total }
                val doneSum = all.sumOf { it.completed + it.failed + it.cached }
                val failedSum = all.sumOf { it.failed }
                val activeBooks = all.count { !it.isComplete }
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            // 单本时退化成"下载中 X/Y"；多本时显示"N 本进行中 · X/Y 章"
                            if (activeBooks > 1) "$activeBooks 本进行中 · $doneSum/$totalSum 章"
                            else "下载中 $doneSum/$totalSum",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        if (failedSum > 0) {
                            Text(
                                "失败 $failedSum",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { if (totalSum > 0) doneSum.toFloat() / totalSum else 0f },
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            // Stats
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "共 ${webBooks.size} 本网络书籍",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                )
            }

            if (webBooks.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.CloudOff, null,
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f),
                            modifier = Modifier.size(48.dp),
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "书架上没有网络书籍",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                        )
                    }
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(webBooks, key = { it.id }) { book ->
                        val stat = cacheStats[book.id]
                        val export = exportState[book.id]
                        // 每本书取自己的进度（若不在 map 中则没在缓存）。
                        val bookProgress = progresses[book.id]
                        val bookIsDownloading = bookProgress != null && !bookProgress.isComplete
                        // Pop a toast once per export-completion and clear the message
                        LaunchedEffect(export?.running, export?.message) {
                            val msg = export?.message
                            if (export != null && !export.running && !msg.isNullOrBlank()) {
                                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                                viewModel.dismissExportMessage(book.id)
                            }
                        }
                        CacheBookItem(
                            book = book,
                            stat = stat,
                            isDownloading = bookIsDownloading,
                            downloadProgress = bookProgress,
                            exportState = export,
                            multiSelectMode = multiSelectMode,
                            isSelected = book.id in selectedIds,
                            onToggleSelect = { viewModel.toggleSelected(book.id) },
                            onOpenReader = { onOpenReader(book.id) },
                            onDownloadAll = {
                                viewModel.startDownload(book)
                            },
                            onDownloadFromCurrent = {
                                viewModel.startDownload(book, startIndex = book.lastReadChapter)
                            },
                            onClearCache = {
                                viewModel.clearCache(book)
                                Toast.makeText(context, "已清除缓存", Toast.LENGTH_SHORT).show()
                            },
                            onExportTxt = {
                                if (stat == null || stat.cachedChapters == 0) {
                                    Toast.makeText(
                                        context,
                                        "请先缓存章节再导出",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                    return@CacheBookItem
                                }
                                viewModel.pendingExportBookId = book.id
                                // Suggested filename: <书名>_<作者>.txt — sanitized for FAT/exFAT
                                val safeName = "${book.title}_${book.author.ifBlank { "未知" }}"
                                    .replace(Regex("""[\\/:*?"<>|]"""), "_")
                                    .take(80) + ".txt"
                                exportLauncher.launch(safeName)
                            },
                            onExportEpub = {
                                if (stat == null || stat.cachedChapters == 0) {
                                    Toast.makeText(
                                        context,
                                        "请先缓存章节再导出",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                    return@CacheBookItem
                                }
                                viewModel.pendingExportBookId = book.id
                                val safeName = "${book.title}_${book.author.ifBlank { "未知" }}"
                                    .replace(Regex("""[\\/:*?"<>|]"""), "_")
                                    .take(80) + ".epub"
                                epubLauncher.launch(safeName)
                            },
                        )
                    }
                }
            }
        }
    }

    // #4: 「全部清空」二次确认 — 危险操作，必须经用户再次同意才执行。
    if (showClearAllConfirm) {
        AlertDialog(
            onDismissRequest = { showClearAllConfirm = false },
            title = { Text("全部清空？") },
            text = { Text("将删除全部 ${webBooks.size} 本网络书的章节缓存，此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    showClearAllConfirm = false
                    viewModel.clearAllCaches()
                }) {
                    Text("全部清空", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearAllConfirm = false }) {
                    Text("取消")
                }
            },
        )
    }

    // Stage A/C 范围导出 — 顶部菜单触发后弹出，选书 + 选范围 + 选格式 + (EPUB 时)
    // 选分卷大小 → 启动对应 SAF + 调 ViewModel：
    //   TXT                     → exportLauncher       → exportTxt
    //   EPUB 单文件 (size == 0) → epubLauncher         → exportEpub
    //   EPUB 多卷  (size  > 0)  → epubFolderLauncher   → exportEpubMultiVolume
    if (showRangeDialog) {
        RangeExportDialog(
            books = webBooks,
            cacheStats = cacheStats,
            chaptersByBook = chaptersForRange,
            onLoadChapters = { viewModel.loadChaptersForRange(it) },
            onDismiss = { showRangeDialog = false },
            onConfirm = { book, fromIdx, toIdx, format, epubSize ->
                showRangeDialog = false
                // 文件名带范围标识，方便用户日后辨识。FAT/exFAT 禁字符同步过滤。
                val safeTitle = book.title
                    .replace(Regex("""[\\/:*?"<>|]"""), "_")
                    .take(60)
                val rangeTag = "_第${fromIdx + 1}-${toIdx + 1}章"
                when {
                    format == ExportFormat.TXT -> {
                        viewModel.pendingExportBookId = book.id
                        viewModel.pendingExportStartIndex = fromIdx
                        viewModel.pendingExportEndIndex = toIdx
                        exportLauncher.launch("$safeTitle$rangeTag.txt")
                    }
                    format == ExportFormat.EPUB && epubSize <= 0 -> {
                        viewModel.pendingExportBookId = book.id
                        viewModel.pendingExportStartIndex = fromIdx
                        viewModel.pendingExportEndIndex = toIdx
                        epubLauncher.launch("$safeTitle$rangeTag.epub")
                    }
                    else -> {
                        // EPUB 多卷：先存待办，让用户挑一个目录；ViewModel 在该目录下
                        // 建多个卷文件。OpenDocumentTree 的 input URI 用 null 表示"任选"。
                        viewModel.pendingMultiVolume = CacheBookViewModel.PendingMultiVolume(
                            bookId = book.id,
                            startIndex = fromIdx,
                            endIndex = toIdx,
                            volumeSize = epubSize,
                        )
                        epubFolderLauncher.launch(null)
                    }
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun CacheBookItem(
    book: Book,
    stat: CacheBookViewModel.CacheStat?,
    isDownloading: Boolean,
    downloadProgress: CacheBookService.DownloadProgress?,
    exportState: CacheBookViewModel.ExportState? = null,
    /** #4：是否处于多选模式。true → 行单击 = toggleSelect，左侧显示 Checkbox。 */
    multiSelectMode: Boolean = false,
    /** 多选模式下当前书是否被选中。 */
    isSelected: Boolean = false,
    onToggleSelect: () -> Unit = {},
    /** #3：普通模式下行单击直接进阅读器。 */
    onOpenReader: () -> Unit = {},
    onDownloadAll: () -> Unit,
    onDownloadFromCurrent: () -> Unit,
    onClearCache: () -> Unit,
    onExportTxt: () -> Unit = {},
    onExportEpub: () -> Unit = {},
) {
    /** 操作菜单展开状态（普通模式下右侧 ⋮ 按钮触发）。 */
    var menuExpanded by remember { mutableStateOf(false) }

    val rowBgColor = if (multiSelectMode && isSelected) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = rowBgColor,
    ) {
        Column {
            Row(
                modifier = Modifier
                    .clickable {
                        if (multiSelectMode) onToggleSelect() else onOpenReader()
                    }
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (multiSelectMode) {
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = { onToggleSelect() },
                        colors = CheckboxDefaults.colors(
                            checkedColor = MaterialTheme.colorScheme.primary,
                        ),
                        modifier = Modifier.size(28.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                }
                // Cover
                Box(
                    modifier = Modifier
                        .size(40.dp, 56.dp)
                        .clip(MaterialTheme.shapes.extraSmall)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    if (book.coverUrl != null) {
                        AsyncImage(
                            model = book.coverUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Icon(
                            Icons.Default.Book, null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }

                Spacer(Modifier.width(12.dp))

                Column(Modifier.weight(1f)) {
                    Text(
                        book.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row {
                        Text(
                            book.author.ifBlank { book.originName },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            maxLines = 1,
                        )
                    }
                    if (stat != null) {
                        // 下载该书过程中，stat 是开始前的快照（cachedChapters=0），实时进度由 service
                        // 推送的 downloadProgress 提供。否则 UI 顶部显示 123/1123 时这里却显示 0/1123。
                        // 下载结束后 LaunchedEffect(isDownloading) 会重新拉 stat，此时回到正常分支。
                        val realtimeOverride = isDownloading && downloadProgress != null
                        val displayCached: Int
                        val displayTotal: Int
                        if (realtimeOverride) {
                            displayCached = downloadProgress!!.completed + downloadProgress.cached
                            // total: 优先用 stat（全本章节数），缺失时退回 progress.total（仅当前批次）
                            displayTotal = if (stat.totalChapters > 0) stat.totalChapters else downloadProgress.total
                        } else {
                            displayCached = stat.cachedChapters
                            displayTotal = stat.totalChapters
                        }
                        val pct = if (displayTotal > 0) displayCached * 100 / displayTotal else 0
                        Text(
                            when {
                                displayTotal == 0 -> "未获取到章节，可能需要换源"
                                realtimeOverride -> "下载中 $displayCached/$displayTotal ($pct%)"
                                else -> "已缓存 $displayCached/$displayTotal ($pct%)"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (displayTotal == 0) {
                                MaterialTheme.colorScheme.error
                            } else if (pct >= 100) {
                                MaterialTheme.colorScheme.tertiary
                            } else {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                            },
                        )
                    }
                    if (!isDownloading && downloadProgress != null &&
                        (downloadProgress.failed > 0 || downloadProgress.message.isNotBlank())
                    ) {
                        Text(
                            downloadProgress.message.ifBlank { "缓存失败 ${downloadProgress.failed} 章" },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                if (isDownloading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else if (!multiSelectMode) {
                    // #3: 普通模式右侧 ⋮ 按钮 — 弹 DropdownMenu，包含原本展开后的 4 个动作。
                    Box {
                        IconButton(
                            onClick = { menuExpanded = true },
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(
                                Icons.Default.MoreVert, "操作菜单",
                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("全部缓存") },
                                leadingIcon = { Icon(Icons.Default.CloudDownload, null) },
                                onClick = { menuExpanded = false; onDownloadAll() },
                            )
                            DropdownMenuItem(
                                text = { Text("从当前章") },
                                leadingIcon = { Icon(Icons.Default.Download, null) },
                                onClick = { menuExpanded = false; onDownloadFromCurrent() },
                            )
                            DropdownMenuItem(
                                text = { Text("导出 TXT") },
                                leadingIcon = {
                                    Icon(Icons.Default.Article, null,
                                        tint = MaterialTheme.colorScheme.tertiary)
                                },
                                onClick = { menuExpanded = false; onExportTxt() },
                            )
                            DropdownMenuItem(
                                text = { Text("导出 EPUB") },
                                leadingIcon = {
                                    Icon(Icons.Default.MenuBook, null,
                                        tint = MaterialTheme.colorScheme.tertiary)
                                },
                                onClick = { menuExpanded = false; onExportEpub() },
                            )
                            DropdownMenuItem(
                                text = { Text("清除", color = MaterialTheme.colorScheme.error) },
                                leadingIcon = {
                                    Icon(Icons.Default.DeleteOutline, null,
                                        tint = MaterialTheme.colorScheme.error)
                                },
                                onClick = { menuExpanded = false; onClearCache() },
                            )
                        }
                    }
                }
            }

            // Download progress for this book
            if (isDownloading && downloadProgress != null) {
                val done = downloadProgress.completed + downloadProgress.failed + downloadProgress.cached
                LinearProgressIndicator(
                    progress = { if (downloadProgress.total > 0) done.toFloat() / downloadProgress.total else 0f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(8.dp))
            }

            // Export-in-progress row (per-book TXT export, separate from download progress)
            if (exportState?.running == true) {
                val pct = if (exportState.total > 0) {
                    exportState.done.toFloat() / exportState.total
                } else 0f
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Text(
                        "导出中 ${exportState.done}/${exportState.total}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                    Spacer(Modifier.height(2.dp))
                    LinearProgressIndicator(
                        progress = { pct },
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                Spacer(Modifier.height(8.dp))
            }

            // Expanded actions removed in #3 — 操作菜单移到顶部 Row 右侧的 ⋮ DropdownMenu。
        }
    }
}
