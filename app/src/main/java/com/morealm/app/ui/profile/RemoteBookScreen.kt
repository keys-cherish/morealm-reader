package com.morealm.app.ui.profile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.morealm.app.domain.sync.FailedImport
import com.morealm.app.domain.sync.ImportState
import com.morealm.app.domain.sync.RemoteEntry
import com.morealm.app.presentation.profile.RemoteBookViewModel
import com.morealm.app.ui.profile.remote.RemoteBreadcrumb
import com.morealm.app.ui.profile.remote.RemoteFailureBanner
import com.morealm.app.ui.profile.remote.RemoteFailureDialog
import com.morealm.app.ui.profile.remote.RemoteFileRow
import com.morealm.app.ui.profile.remote.RemoteFolderRow
import com.morealm.app.ui.profile.remote.RemoteImportProgressBanner

/**
 * 远程书架 V2 —— 层级 lazy load 浏览 + 自由导入 + 失败重试 UI。
 *
 * 公开签名兼容 V1（`onBack: () -> Unit, viewModel`），上游 [com.morealm.app.ui.navigation.MoRealmNavHost]
 * 不必改 nav 路由。本 file 是**轻 shim**：把 V2 ViewModel 的 state 全部 collect 后
 * 传给 [RemoteBookScreenContent]（纯展示 / 受控版）。`onXxx` 全部走 ViewModel 方法。
 *
 * 浏览栈 / 选择 / 搜索 / 失败列表都由 [RemoteBookViewModel] 持有，Content 仅显示
 * 与回调 forwarding；从 Preview 测 UI 不必启动 Hilt。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteBookScreen(
    onBack: () -> Unit,
    viewModel: RemoteBookViewModel = hiltViewModel(),
) {
    val entries by viewModel.filteredEntries.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val importState by viewModel.importState.collectAsStateWithLifecycle()
    val selectionMode by viewModel.selectionMode.collectAsStateWithLifecycle()
    val selection by viewModel.selection.collectAsStateWithLifecycle()
    val failedImports by viewModel.failedImports.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()

    // pathStack 是 SnapshotStateList —— 直接读触发 Compose 重组，无需 collect。
    // derivedStateOf 内对 pathStack 的 snapshot 读会被观察到，refresh 时自动重算。
    val pathStack = viewModel.pathStack
    val breadcrumbSegments by remember {
        derivedStateOf {
            pathStack.map { it.substringAfterLast('/') }
        }
    }
    // selection 是 collect 出来的 State，每次 emit 都会推动重组；用 remember(selection)
    // 把 derived 结果绑到当前 emission，避免 stale closure（直接 remember { } 会捕获
    // 第一次的 selection 值，后续 emit 不更新）
    val selectedPaths = remember(selection) { selection.map { it.remotePath }.toSet() }

    RemoteBookScreenContent(
        title = "远程书架",
        breadcrumbSegments = breadcrumbSegments,
        entries = entries,
        loading = loading,
        importState = importState,
        failedImports = failedImports,
        searchQuery = searchQuery,
        selectionMode = selectionMode,
        selectedPaths = selectedPaths,
        onBack = onBack,
        onRefresh = { viewModel.refresh() },
        onSearchChange = { viewModel.search(it) },
        onToggleSelectionMode = { viewModel.toggleSelectionMode() },
        onBreadcrumbClick = { index -> viewModel.jumpToBreadcrumb(index) },
        onBreadcrumbBack = { viewModel.goBack() },
        onFolderClick = { folder -> viewModel.enterDir(folder) },
        onFolderImport = { folder -> viewModel.importFolder(folder) },
        onFileClick = { /* 文件单击：无 action，用 + 按钮显式导入 */ },
        onFileImport = { file -> viewModel.importFile(file) },
        onToggleSelection = { entry, _ -> viewModel.toggleSelection(entry) },
        onImportSelected = { viewModel.importSelected() },
        onClearSelection = { viewModel.clearSelection() },
        onCancelImport = { viewModel.cancelImport() },
        onRetryFailed = { viewModel.retryFailed() },
        onRetrySingleFailed = { /* 单条重试沿用 retryFailed 全跑；单测可独立后续优化 */
            viewModel.retryFailed()
        },
    )
}

/**
 * 纯展示 / 受控版本 —— 不依赖 ViewModel，所有 state 通过 param 注入。
 *
 * 用途：
 *  - 让 [RemoteBookScreen] shim 把 VM 状态拍平后注入
 *  - 让单测 / Preview 喂 mock state 完整验证布局
 *
 * 注意：本 Composable 不持有任何业务状态（搜索 / 多选 / 选中集都受控），只保留
 * 纯 UI-only 的 transient state（menuExpanded / showFailureDialog / isGridView）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RemoteBookScreenContent(
    title: String,
    breadcrumbSegments: List<String>,
    entries: List<RemoteEntry>,
    loading: Boolean,
    importState: ImportState,
    failedImports: List<FailedImport>,
    searchQuery: String,
    selectionMode: Boolean,
    selectedPaths: Set<String>,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onSearchChange: (String) -> Unit,
    onToggleSelectionMode: () -> Unit,
    onBreadcrumbClick: (Int) -> Unit,
    onBreadcrumbBack: () -> Unit,
    onFolderClick: (RemoteEntry.Folder) -> Unit,
    onFolderImport: (RemoteEntry.Folder) -> Unit,
    onFileClick: (RemoteEntry.File) -> Unit,
    onFileImport: (RemoteEntry.File) -> Unit,
    onToggleSelection: (RemoteEntry, Boolean) -> Unit,
    onImportSelected: () -> Unit,
    onClearSelection: () -> Unit,
    onCancelImport: () -> Unit,
    onRetryFailed: () -> Unit,
    onRetrySingleFailed: (FailedImport) -> Unit,
) {
    // ── UI-only transient state ──
    var menuExpanded by remember { mutableStateOf(false) }
    var showFailureDialog by remember { mutableStateOf(false) }
    var isGridView by remember { mutableStateOf(false) }

    // 多选 / 系统返回的优先级处理。breadcrumb size > 1 表示在子层（root 不响应 back）
    BackHandler(enabled = selectionMode || breadcrumbSegments.size > 1) {
        when {
            selectionMode -> onClearSelection()
            breadcrumbSegments.size > 1 -> onBreadcrumbBack()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (selectionMode) "已选 ${selectedPaths.size}" else title,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (selectionMode) onClearSelection() else onBack()
                    }) {
                        Icon(
                            if (selectionMode) Icons.Default.Close else Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = if (selectionMode) "取消多选" else "返回",
                        )
                    }
                },
                actions = {
                    if (!selectionMode) {
                        IconButton(onClick = { /* TODO: 搜索 focus；当前直接展开搜索栏 */ }) {
                            Icon(Icons.Default.Search, contentDescription = "搜索")
                        }
                        IconButton(onClick = { isGridView = !isGridView }) {
                            Icon(
                                if (isGridView) Icons.AutoMirrored.Filled.List else Icons.Default.GridView,
                                contentDescription = "切换视图（占位）",
                            )
                        }
                    }
                    IconButton(onClick = onToggleSelectionMode) {
                        Icon(Icons.Default.SelectAll, contentDescription = "多选")
                    }
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "更多")
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("刷新") },
                            leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                onRefresh()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("排序方式（占位）") },
                            onClick = { menuExpanded = false },
                            enabled = false,
                        )
                        DropdownMenuItem(
                            text = { Text("设置（占位）") },
                            onClick = { menuExpanded = false },
                            enabled = false,
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            if (selectionMode && selectedPaths.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    text = { Text("导入选中 (${selectedPaths.size})") },
                    icon = {
                        Icon(
                            Icons.Default.SelectAll,
                            contentDescription = null,
                        )
                    },
                    onClick = onImportSelected,
                )
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            // ── 搜索胶囊 ──
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchChange,
                placeholder = { Text("搜索") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                shape = MaterialTheme.shapes.large,
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    unfocusedBorderColor = androidx.compose.ui.graphics.Color.Transparent,
                    focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )

            // ── 面包屑 ──
            RemoteBreadcrumb(
                segments = breadcrumbSegments,
                onSegmentClick = onBreadcrumbClick,
                onBackClick = onBreadcrumbBack,
                modifier = Modifier.fillMaxWidth(),
            )

            // ── 进度 / 失败 banner ──
            RemoteImportProgressBanner(
                state = importState,
                onCancel = onCancelImport,
            )
            if (failedImports.isNotEmpty()) {
                RemoteFailureBanner(
                    failedCount = failedImports.size,
                    onRetry = onRetryFailed,
                    onShowDetail = { showFailureDialog = true },
                )
            }

            // ── 列表 ──
            when {
                loading && entries.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                entries.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = if (searchQuery.isBlank()) "没有可显示的内容" else "没有匹配「$searchQuery」的项",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                        )
                    }
                }
                else -> {
                    LazyColumn(
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(entries, key = { it.remotePath }) { entry ->
                            when (entry) {
                                is RemoteEntry.Folder -> RemoteFolderRow(
                                    folder = entry,
                                    selectionMode = selectionMode,
                                    isSelected = entry.remotePath in selectedPaths,
                                    onClick = {
                                        if (selectionMode) {
                                            onToggleSelection(entry, entry.remotePath !in selectedPaths)
                                        } else onFolderClick(entry)
                                    },
                                    onPlusClick = { onFolderImport(entry) },
                                    onCheckedChange = { checked ->
                                        onToggleSelection(entry, checked)
                                    },
                                )
                                is RemoteEntry.File -> RemoteFileRow(
                                    file = entry,
                                    isDownloading = false,  // 单本下载态由 ImportStateBus banner 反映
                                    selectionMode = selectionMode,
                                    isSelected = entry.remotePath in selectedPaths,
                                    onClick = {
                                        if (selectionMode) {
                                            onToggleSelection(entry, entry.remotePath !in selectedPaths)
                                        } else onFileClick(entry)
                                    },
                                    onPlusClick = { onFileImport(entry) },
                                    onCheckedChange = { checked ->
                                        onToggleSelection(entry, checked)
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showFailureDialog) {
        RemoteFailureDialog(
            failures = failedImports,
            onDismiss = { showFailureDialog = false },
            onRetry = { failure ->
                onRetrySingleFailed(failure)
            },
            onRetryAll = {
                showFailureDialog = false
                onRetryFailed()
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@androidx.compose.ui.tooling.preview.Preview(showBackground = true)
@Composable
private fun RemoteBookScreenContentPreview() {
    MaterialTheme {
        RemoteBookScreenContent(
            title = "远程书架",
            breadcrumbSegments = listOf("示例网盘", "MoRealm", "books"),
            entries = listOf(
                RemoteEntry.Folder(
                    name = "示例文件夹 A",
                    remotePath = "/books/示例文件夹 A",
                    lastModifiedEpoch = 1_700_000_000_000L,
                ),
                RemoteEntry.Folder(
                    name = "测试 B",
                    remotePath = "/books/测试 B",
                    lastModifiedEpoch = 1_690_000_000_000L,
                ),
                RemoteEntry.File(
                    name = "test_demo.epub",
                    remotePath = "/books/test_demo.epub",
                    size = 2_345_678L,
                    lastModifiedEpoch = 1_705_000_000_000L,
                    format = com.morealm.app.domain.entity.BookFormat.EPUB,
                ),
                RemoteEntry.File(
                    name = "示例 LN A.mobi",
                    remotePath = "/books/示例 LN A.mobi",
                    size = 5_000_000L,
                    lastModifiedEpoch = 1_704_000_000_000L,
                    format = com.morealm.app.domain.entity.BookFormat.MOBI,
                ),
            ),
            loading = false,
            importState = ImportState.Idle,
            failedImports = emptyList(),
            searchQuery = "",
            selectionMode = false,
            selectedPaths = emptySet(),
            onBack = {},
            onRefresh = {},
            onSearchChange = {},
            onToggleSelectionMode = {},
            onBreadcrumbClick = {},
            onBreadcrumbBack = {},
            onFolderClick = {},
            onFolderImport = {},
            onFileClick = {},
            onFileImport = {},
            onToggleSelection = { _, _ -> },
            onImportSelected = {},
            onClearSelection = {},
            onCancelImport = {},
            onRetryFailed = {},
            onRetrySingleFailed = {},
        )
    }
}
