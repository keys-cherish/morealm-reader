package com.morealm.app.ui.shelf

import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Sun
import com.composables.icons.lucide.Moon
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.EllipsisVertical
import com.composables.icons.lucide.ArrowDownNarrowWide
import com.composables.icons.lucide.LayoutGrid
import com.composables.icons.lucide.List
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
import androidx.compose.material.icons.automirrored.outlined.Sort
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
import com.morealm.app.presentation.shelf.ImportPhase
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
    // 视图模式从 ViewModel 取持久化值（DataStore 读到的最近一次用户选择）。
    // 旧实现用 rememberSaveable 仅 Bundle 持久，冷启动回退到默认；现在切换写入
    // AppPreferences，下次进入应用直接看到上次的视图模式。
    val shelfViewMode by viewModel.shelfViewMode.collectAsStateWithLifecycle()
    val isListView = shelfViewMode == "list"
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
    // 分组批量选择模式（与 batchMode 互斥）。长按分组进入；只在根目录可用，
    // 因为分组卡只在根目录显示。两套状态分开：避免 selectedIds 同时混着 bookId / groupId。
    var folderBatchMode by remember { mutableStateOf(false) }
    var selectedFolderIds by remember { mutableStateOf(setOf<String>()) }
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
            folderBatchMode = false
            selectedFolderIds = emptySet()
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

    LaunchedEffect(folderImportState.running, folderImportState.phase, folderImportState.message, folderImportState.error) {
        // 终态停留 3.5s 让用户看清"完成 N 本"再自动隐藏；Bus state 同步 reset 防止
        // 重进书架还残留旧的 Done 状态。
        val terminal = !folderImportState.running &&
            (folderImportState.phase == com.morealm.app.presentation.shelf.ImportPhase.Done ||
                folderImportState.phase == com.morealm.app.presentation.shelf.ImportPhase.Error)
        if (terminal) {
            delay(3500)
            viewModel.resetFolderImportState()
        } else if (!folderImportState.running && folderImportState.message.isNotBlank()) {
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
    BackHandler(enabled = currentFolderId != null || batchMode || folderBatchMode) {
        when {
            folderBatchMode -> { folderBatchMode = false; selectedFolderIds = emptySet() }
            batchMode -> { batchMode = false; selectedIds = emptySet() }
            else -> currentFolderId = null
        }
    }

    // Resume last read book on first launch if setting is enabled.
    //
    // 状态放在 ViewModel 里（hasResumedOnLaunch）—— 同一 Activity 进程下只触发一次。
    // 不要用 remember/rememberSaveable：ShelfScreen 跳到 ReaderScreen 再返回时，
    // composable 会重组，remember 会被清零导致死循环重入。
    //
    // LaunchedEffect 只依赖 lastRead/booksLoaded，不依赖 resumeLastRead ——
    // 用户在设置里切换开关时不应立即跳转，只在下次启动应用时生效。
    val resumeLastRead by viewModel.resumeLastRead.collectAsStateWithLifecycle()
    LaunchedEffect(lastRead, booksLoaded) {
        if (resumeLastRead && viewModel.shouldResumeOnLaunch() && lastRead != null && booksLoaded) {
            viewModel.markResumedOnLaunch()
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
                when {
                    folderBatchMode -> Text(
                        "已选 ${selectedFolderIds.size} 个分组",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    batchMode -> Text(
                        "已选 ${selectedIds.size} 本",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    else -> {
                        // 副文本从静态"享受阅读时光"改成今日阅读时长。
                        //   - 0 分钟：保留原有问候副文本，避免显示"今日已阅读 0 分钟"过于冷淡
                        //   - 1-59 分钟：显示分钟数
                        //   - 60+ 分钟：显示"X 小时 Y 分钟"或仅"X 小时"（整点）
                        val todayMs by viewModel.todayReadMs.collectAsStateWithLifecycle()
                        val subtitle = remember(todayMs) {
                            if (todayMs <= 0L) {
                                "享受阅读时光"
                            } else {
                                val totalMin = (todayMs / 60_000L).toInt()
                                val h = totalMin / 60
                                val m = totalMin % 60
                                when {
                                    h == 0 -> "今日已阅读 $m 分钟"
                                    m == 0 -> "今日已阅读 $h 小时"
                                    else -> "今日已阅读 $h 小时 $m 分钟"
                                }
                            }
                        }
                        Column {
                            // greeting 字号从 titleLarge 降到中间档（headlineSmall? 不，
                            // 改用 titleMedium 加大字号 + Bold）—— 用户要求降低但仍大于
                            // 「我的书架」(titleMedium SemiBold)。用 22.sp + Bold 是夹在
                            // titleLarge(22sp default) 和 titleMedium(16sp) 之间的轻量值。
                            Text(
                                greeting,
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onBackground,
                            )
                            Text(subtitle, style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f))
                        }
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
                titleContentColor = MaterialTheme.colorScheme.onBackground,
            ),
            windowInsets = WindowInsets(0, 0, 0, 0),
            navigationIcon = {
                if (batchMode || folderBatchMode) {
                    IconButton(onClick = {
                        batchMode = false; selectedIds = emptySet()
                        folderBatchMode = false; selectedFolderIds = emptySet()
                    }) {
                        Icon(Icons.Default.Close, "取消", tint = MaterialTheme.colorScheme.onBackground)
                    }
                }
            },
            actions = {
                if (folderBatchMode) {
                    // 分组多选：只提供"删除分组（连同书）"。Snackbar 撤销 5s 内可恢复。
                    IconButton(
                        onClick = {
                            if (selectedFolderIds.isEmpty()) return@IconButton
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            val ids = selectedFolderIds
                            // snapshot 必须在 batchDeleteFolders 之前抓——否则 DB 删完 allBooks/allGroups 流就空了。
                            val groupsSnapshot = allGroups.filter { it.id in ids }
                            val booksSnapshot = allBooks.filter { it.folderId in ids }
                            folderBatchMode = false
                            selectedFolderIds = emptySet()
                            if (groupsSnapshot.isEmpty() && booksSnapshot.isEmpty()) return@IconButton
                            viewModel.batchDeleteFolders(ids)
                            scope.launch {
                                val msg = "已删除 ${groupsSnapshot.size} 个分组" +
                                    (if (booksSnapshot.isNotEmpty()) "（${booksSnapshot.size} 本书）" else "")
                                val r = snackbarHost.showSnackbar(
                                    message = msg,
                                    actionLabel = "撤销",
                                    duration = SnackbarDuration.Short,
                                    withDismissAction = true,
                                )
                                if (r == SnackbarResult.ActionPerformed) {
                                    viewModel.restoreFolders(groupsSnapshot, booksSnapshot)
                                }
                            }
                        },
                        enabled = selectedFolderIds.isNotEmpty(),
                    ) {
                        Icon(Icons.Default.Delete, "删除分组",
                            tint = if (selectedFolderIds.isNotEmpty()) MaterialTheme.colorScheme.error
                                   else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f))
                    }
                } else if (batchMode) {
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
                // ── 顶栏 actions：胶囊容器包 3 个轻量图标 ──
                //
                // 视觉风格：圆角胶囊（外层 surfaceContainer/0.12 alpha）+ 3 个 Lucide 线性
                // 图标（日夜 / 导入 / 三点）+ 两条 0.5dp 竖线分隔。极度轻量、透气、干净，
                // 比之前 3 个独立 IconButton 视觉聚拢得多（截图 20）。
                Row(
                    modifier = Modifier
                        .padding(end = 6.dp)
                        .clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.06f))
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CapsuleAction(
                        icon = if (isNightTheme) Lucide.Sun else Lucide.Moon,
                        contentDescription = "切换日夜间",
                        onClick = onToggleDayNight,
                    )
                    CapsuleDivider()
                    val isOrganizing by viewModel.isOrganizing.collectAsStateWithLifecycle()
                    Box {
                        CapsuleAction(
                            icon = Lucide.Plus,
                            contentDescription = "导入",
                            onClick = { showImportMenu = true },
                        )
                        DropdownMenu(expanded = showImportMenu, onDismissRequest = { showImportMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("导入文件") },
                                leadingIcon = { Icon(Icons.Default.Description, null) },
                                onClick = {
                                    showImportMenu = false
                                    filePickerLauncher.launch(arrayOf("*/*"))
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
                    CapsuleDivider()
                    var showOverflowMenu by remember { mutableStateOf(false) }
                    Box {
                        CapsuleAction(
                            icon = Lucide.EllipsisVertical,
                            contentDescription = "更多",
                            onClick = { showOverflowMenu = true },
                        )
                        DropdownMenu(
                            expanded = showOverflowMenu,
                            onDismissRequest = { showOverflowMenu = false },
                        ) {
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
                                text = { Text("搜索书架") },
                                leadingIcon = { Icon(Icons.Default.Search, null) },
                                onClick = {
                                    showSearch = true
                                    showOverflowMenu = false
                                },
                            )
                        }
                    }
                }
                } // end else (non-batch actions)
            },
        )

        if (folderImportState.running || folderImportState.message.isNotBlank()) {
            FolderImportBanner(
                state = folderImportState,
                onDismiss = viewModel::clearFolderImportMessage,
                onCancel = viewModel::requestCancelFolderImport,
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

            // ── "我的书架"标题行 ──
            // 在「继续阅读」卡片下方，「书籍列表」上方，作为视觉锚点+控制条。
            // 排序 / 切换视图入口集中在此（顶栏不再放，overflow 也不再放，避免
            // 同一动作多入口）。线性矢量图标 (AutoMirrored.Outlined / Outlined)
            // 匹配图 3 极简轻量风格。
            //
            // batchMode / folderBatchMode 下隐藏：批量模式的视觉重心是顶栏的
            // 选中数 + 删除/移动按钮，标题行此时多余甚至干扰。
            if (!batchMode && !folderBatchMode) {
                var showShelfSortMenu by remember { mutableStateOf(false) }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        // 字号 titleMedium → SemiBold；比 greeting (22sp Bold) 小一档，
                        // 形成"主-次"层级（截图 21 视觉关系）。
                        "我的书架",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.92f),
                        modifier = Modifier.weight(1f),
                    )
                    // 排序入口（独立、轻量、Lucide 线性矢量）—— 截图 21 排序按钮在胶囊外。
                    Box {
                        androidx.compose.foundation.layout.Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(50))
                                .clickable { showShelfSortMenu = true },
                            contentAlignment = Alignment.Center,
                        ) {
                            androidx.compose.material3.Icon(
                                imageVector = Lucide.ArrowDownNarrowWide,
                                contentDescription = "排序方式",
                                tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f),
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        DropdownMenu(
                            expanded = showShelfSortMenu,
                            onDismissRequest = { showShelfSortMenu = false },
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
                                        showShelfSortMenu = false
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
                    Spacer(Modifier.width(8.dp))
                    // 视图切换：胶囊 segmented 容器，列表 / 网格两个按钮（截图 21）。
                    // 选中态用 primary container 暗示，未选用 transparent；按钮间无分隔
                    // 线（segmented 风格本身就有圆角差异区分边界）。
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.06f))
                            .padding(3.dp),
                    ) {
                        SegmentedViewButton(
                            icon = Lucide.List,
                            contentDescription = "列表视图",
                            selected = isListView,
                            onClick = { viewModel.setShelfViewMode("list") },
                        )
                        SegmentedViewButton(
                            icon = Lucide.LayoutGrid,
                            contentDescription = "网格视图",
                            selected = !isListView,
                            onClick = { viewModel.setShelfViewMode("grid") },
                        )
                    }
                }
            }
        }

        // Helper lambdas for batch mode.
        // Manual taps go through onBookOpen (smart router: WEB → detail page first).
        // Auto-resume / continue-reading flows use onBookClick directly to land in reader.
        val bookClick: (String) -> Unit = { id ->
            when {
                // 分组多选模式下书籍点击不响应：避免错把书加进 selectedFolderIds（语义不同），
                // 也避免用户在分组多选时不小心打开了一本书。
                folderBatchMode -> Unit
                batchMode -> {
                    selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
                }
                else -> {
                    val book = allBooks.find { it.id == id }
                    if (onBookOpen != null && book != null) onBookOpen(book) else onBookClick(id)
                }
            }
        }
        val bookLongClick: (String) -> Unit = { id ->
            when {
                folderBatchMode -> Unit  // 同 bookClick：分组多选时书籍长按也不响应
                !batchMode -> {
                    val book = allBooks.find { it.id == id }
                    if (book != null && book.format == BookFormat.WEB) {
                        showCacheBookDialog = book
                    } else if (book != null) {
                        // 单本书长按：弹"自定义封面 / 进入多选"菜单（默认）；
                        // WEB 书走原 cache dialog（不变）
                        bookActionTarget = book
                    }
                }
                else -> {
                    selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
                }
            }
        }
        // 分组卡的点击 / 长按：folderBatchMode 时统一 toggle 选中；普通时分别打开 / 弹 dialog。
        val folderClick: (String) -> Unit = { folderId ->
            if (folderBatchMode) {
                selectedFolderIds = if (folderId in selectedFolderIds) selectedFolderIds - folderId else selectedFolderIds + folderId
            } else {
                currentFolderId = folderId
            }
        }
        val folderLongClick: (String) -> Unit = { folderId ->
            if (folderBatchMode) {
                selectedFolderIds = if (folderId in selectedFolderIds) selectedFolderIds - folderId else selectedFolderIds + folderId
            } else {
                showDeleteFolderConfirm = folderId
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
                onImportFile = { filePickerLauncher.launch(arrayOf("*/*")) },
                onImportFolder = { folderPickerLauncher.launch(downloadUri) },
                modifier = Modifier.fillMaxSize(),
            )
        } else if (isListView) {
            // List view
            LazyColumn(
                contentPadding = PaddingValues(top = 4.dp, bottom = 88.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
            ) {
                if (currentFolderId == null) {
                    lazyItems(folderIds, key = { "folder_$it" }, contentType = { "folder" }) { folderId ->
                        FolderListItem(
                            name = groupNames[folderId] ?: "文件夹",
                            bookCount = folderBookCounts[folderId] ?: 0,
                            coverUrl = folderCoverUrls[folderId]?.firstOrNull(),
                            customCoverUrl = allGroups.firstOrNull { it.id == folderId }?.customCoverUrl,
                            hasUpdate = groupHasUpdate[folderId] == true,
                            onClick = { folderClick(folderId) },
                            onLongClick = { folderLongClick(folderId) },
                            selected = folderBatchMode && folderId in selectedFolderIds,
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
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 88.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
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
                            onClick = { folderClick(folderId) },
                            onLongClick = { folderLongClick(folderId) },
                            selected = folderBatchMode && folderId in selectedFolderIds,
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
            onEnterBatchMode = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                folderBatchMode = true
                selectedFolderIds = setOf(folderId)
                // 互斥：进分组多选先关掉书籍多选
                batchMode = false
                selectedIds = emptySet()
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
                // 互斥：进书籍多选先关掉分组多选
                folderBatchMode = false
                selectedFolderIds = emptySet()
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
                            // 互斥：进书籍多选先关掉分组多选
                            folderBatchMode = false
                            selectedFolderIds = emptySet()
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
            .navigationBarsPadding()
            .padding(bottom = 96.dp),
    )
    }
}

@Composable
private fun FolderImportBanner(
    state: FolderImportState,
    onDismiss: () -> Unit,
    onCancel: () -> Unit = {},
) {
    val showProgressBar = state.total > 0 &&
        (state.phase == ImportPhase.Phase1 || state.phase == ImportPhase.Phase2)
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
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
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
                        // 新进度路径：有 total 时给"X / Y 本"，比通用"正在继续处理"信息量大
                        showProgressBar -> "已导入 ${state.imported} / ${state.total} 本"
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
            // ── 进度条 + 取消按钮（仅 Phase1/Phase2 有 total 时显示） ──
            if (showProgressBar) {
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = {
                        if (state.total > 0) state.imported.toFloat() / state.total else 0f
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onCancel) { Text("取消导入") }
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
    onEnterBatchMode: () -> Unit,
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
                // 进入分组多选模式：与 BookActionDialog 中"进入多选模式"对齐 —
                // 长按一个分组，把它选中并切到顶栏的"分组多选"模式，再去逐个点选其他分组。
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
                        Text("多选模式", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "可批量删除分组（连同分组里的书）",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                        )
                    }
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

// ── 顶栏胶囊 helper composables ──────────────────────────────────────────────
//
// 把书架顶栏右侧 3 个按钮（日夜 / 导入 / 三点）+ 「我的书架」行的视图切换包成
// 圆角胶囊容器（截图 20 / 21 设计）。统一图标尺寸 18dp + Lucide 线性矢量风格，
// 视觉极轻量、透气、干净。

/** 胶囊容器内的单个动作按钮 —— 36dp 触发区 + 18dp 图标。 */
@Composable
private fun CapsuleAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(50))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.material3.Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.78f),
            modifier = Modifier.size(18.dp),
        )
    }
}

/** 胶囊内分隔细线（0.5dp，纵向）。 */
@Composable
private fun CapsuleDivider() {
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .padding(vertical = 8.dp)
            .width(0.5.dp)
            .height(18.dp)
            .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.22f)),
    )
}

/** 「我的书架」行视图切换的 segmented 按钮 —— 选中态用 surface 暗示。 */
@Composable
private fun SegmentedViewButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .size(width = 36.dp, height = 28.dp)
            .clip(RoundedCornerShape(50))
            .background(
                if (selected) MaterialTheme.colorScheme.surface
                else androidx.compose.ui.graphics.Color.Transparent
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.material3.Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (selected) MaterialTheme.colorScheme.onBackground
                   else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
            modifier = Modifier.size(16.dp),
        )
    }
}
