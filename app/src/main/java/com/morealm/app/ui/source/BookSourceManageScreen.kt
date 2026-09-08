package com.morealm.app.ui.source

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.morealm.app.core.error.ErrorMessages
import com.morealm.app.core.log.AppLog
import com.morealm.app.domain.entity.BookSource
import com.morealm.app.domain.webbook.CheckSource
import com.morealm.app.presentation.source.BookSourceManageViewModel
import com.morealm.app.presentation.source.SourceSortKey
import com.morealm.app.presentation.source.sortedBySourceKey
import com.morealm.app.ui.theme.LocalMoRealmColors
import com.morealm.app.ui.widget.swipeBackEdge

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookSourceManageScreen(
    onBack: () -> Unit,
    viewModel: BookSourceManageViewModel = hiltViewModel(),
    loginViewModel: com.morealm.app.presentation.source.SourceLoginViewModel = hiltViewModel(),
    /** 登录 dialog "查看日志"菜单项触发；null 时菜单项隐藏。 */
    onNavigateToLog: (() -> Unit)? = null,
) {
    val moColors = LocalMoRealmColors.current
    val context = LocalContext.current
    // 用 SourcesSnapshot wrapper（reference-equality）绕开 StateFlow / Compose State 内置
    // 的 structural equality dedup —— combine 输出 list 内容相同时会被 dedup 导致 UI 不重组。
    // 修用户反馈"toggle 不实时生效"：之前 `val sources = state.value.items` 一次性 unwrap
    // 让 List 跟 SourcesSnapshot State 解耦 — recompose 后 sources 是 new List ref 但
    // LazyColumn items() 内部按 key dedup item slot 时基于 list 元素结构相等。
    // 用 derivedStateOf 让 sources 是 State<List>，Compose snapshot 自动 track .value 读取。
    val sourcesSnapshotState = viewModel.sources.collectAsStateWithLifecycle()
    // referentialEqualityPolicy 修 toggle 不实时根因（2026-05-18 日志铁证）：
    // BookSource.equals 只比 bookSourceUrl 无视 enabled（L84-87 BookSource.kt）→
    // derivedStateOf 默认 structuralEqualityPolicy 走 List.equals 走 BookSource.equals →
    // toggle 后所有 url 相同 → equals=true → 判"结果没变"不通知依赖 → Screen 不重组。
    // 改成 referentialEqualityPolicy：每次 combine emit 都 new List ref（srcList.map 结果），
    // ref 必不等 → 触发依赖重组 → Screen / items / SourceItem / Switch 全链路刷新。
    val sources by remember(sourcesSnapshotState) {
        androidx.compose.runtime.derivedStateOf(androidx.compose.runtime.referentialEqualityPolicy()) {
            sourcesSnapshotState.value.items
        }
    }
    // [DIAG SourceToggle 2026-05-18] 屏顶 SideEffect：每次 recompose 都跑，记录
    // 上游 sources 实际状态。若 enabled count 在 toggle 后变化 → 上游 OK；若不变
    // → ViewModel combine 没真 emit 新值，或 StateFlow dedup 吞了。
    androidx.compose.runtime.SideEffect {
        val enabledCnt = sources.count { it.enabled }
        com.morealm.app.core.log.AppLog.info(
            "SourceToggleDiag",
            "Screen RECOMPOSE sources.size=${sources.size} enabled=$enabledCnt snapshotRef=${System.identityHashCode(sourcesSnapshotState.value)}",
        )
    }
    val isImporting by viewModel.isImporting.collectAsStateWithLifecycle()
    val importProgress by viewModel.importProgress.collectAsStateWithLifecycle()
    val importResult by viewModel.importResult.collectAsStateWithLifecycle()
    val isChecking by viewModel.isChecking.collectAsStateWithLifecycle()
    val checkProgress by viewModel.checkProgress.collectAsStateWithLifecycle()
    val checkTotal by viewModel.checkTotal.collectAsStateWithLifecycle()
    val checkResults by viewModel.checkResults.collectAsStateWithLifecycle()
    // 预算缓存：进屏 + sources 变化时后台跑 evalJS，UI 只 O(1) 查表，
    // 滚动不再卡顿。详见 SourceLoginViewModel.refreshLoginStatuses。
    val loginStatusMap by loginViewModel.loginStatusMap.collectAsStateWithLifecycle()
    val loginUiState by loginViewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(sources, isImporting, loginUiState) {
        if (!isImporting && loginUiState == com.morealm.app.presentation.source.LoginUiState.Idle) {
            loginViewModel.refreshLoginStatuses(sources)
        }
    }
    // 监听 action JS 一次性事件（button / toggle / select 触发后回显结果）
    LaunchedEffect(loginViewModel) {
        loginViewModel.toast.collect { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }
    var showImportDialog by remember { mutableStateOf(false) }
    var importUrl by remember { mutableStateOf("") }
    var searchQuery by remember { mutableStateOf("") }
    var editingSource by remember { mutableStateOf<BookSource?>(null) }

    // ── 多选删除态 ────────────────────────────────────────────
    // selectionMode 决定 TopAppBar 形态、列表项是否显示选中边框、Switch 是否禁用。
    // selectedUrls 用 SnapshotStateList + rememberSaveable 双保险：
    //   - SnapshotStateList：Compose 快照系统能追踪增减，LazyColumn item 里
    //     `url in selectedUrls` 会随之 invalidate，单选 / 全选 UI 即时刷新。
    //     之前用普通 MutableSet 时，列表大到需要复用 item 的场景下，除最近点过的
    //     前两三张卡之外不会重绘，描边对不上选中状态。
    //   - rememberSaveable + listSaver：旋转 / 进程死亡保留选中。
    var selectionMode by rememberSaveable { mutableStateOf(false) }
    val selectedUrls = rememberSaveable(
        saver = listSaver<SnapshotStateList<String>, String>(
            save = { it.toList() },
            restore = { mutableStateListOf<String>().apply { addAll(it) } },
        ),
    ) { mutableStateListOf<String>() }
    val selectedCount = selectedUrls.size
    var showBatchDeleteDialog by remember { mutableStateOf(false) }
    val exitSelection: () -> Unit = {
        selectionMode = false
        selectedUrls.clear()
    }
    val toggleSelect: (String) -> Unit = { url ->
        if (url in selectedUrls) selectedUrls.remove(url) else selectedUrls.add(url)
    }
    // 系统返回键优先退出多选态；否则回到上层。
    BackHandler(enabled = selectionMode) { exitSelection() }

    // File picker for importing local JSON files
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            try {
                val json = context.contentResolver.openInputStream(it)?.use { stream ->
                    stream.bufferedReader().readText()
                }
                if (json != null) {
                    viewModel.importFromUri(it) { json }
                } else {
                    Toast.makeText(context, "文件内容为空", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                AppLog.error("SourceManage", "读取文件失败", e)
                Toast.makeText(context, ErrorMessages.forUser("读取文件", e), Toast.LENGTH_SHORT).show()
            }
        }
    }

    // 导出 SAF — 触发时携带要导出的 url 集合：null 代表"导出全部"，非空代表"导出选中"。
    // 用 var 在 launch 前先写入，回调里读取——CreateDocument 的回调是异步的，必须用
    // state/字段把"上一次点击的导出范围"传过去。重置成 null 防止下次点"导出全部"被
    // 上一次的"导出选中"集污染。
    var pendingExportUrls by remember { mutableStateOf<Set<String>?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        uri?.let { viewModel.exportToUri(it, pendingExportUrls) }
        pendingExportUrls = null
    }
    // exportResult 单事件流：成功显示导出条数，失败显示错误。LaunchedEffect 收一次即用。
    LaunchedEffect(Unit) {
        viewModel.exportResult.collect { result ->
            result.onSuccess { count ->
                Toast.makeText(context, "已导出 $count 个书源", Toast.LENGTH_SHORT).show()
            }.onFailure { e ->
                Toast.makeText(context, "导出失败：${e.message?.take(60)}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Show import result toast
    LaunchedEffect(importResult) {
        importResult?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.clearImportResult()
        }
    }

    // Toggle 失败回滚提示 —— atomic UPDATE 异常 / rows=0 时 ViewModel rollback overlay 并
    // emit 错误描述。SharedFlow extraBufferCapacity=2 保证 UI 不丢消息。
    LaunchedEffect(Unit) {
        viewModel.toggleError.collect { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }

    // filteredSources 必须用 derivedStateOf(referentialEqualityPolicy()) 而非 remember：
    // remember(sources, ...) 等价比较走 List.equals → BookSource.equals 只比 url → toggle 后
    // remember 命中返回旧 list ref → LazyColumn items 拿旧 source 实例 → Switch stale。
    // derivedStateOf 让 Screen 重组时按 referential 触发重算（每次 combine emit 新 list ref）。
    val filteredSources by remember(searchQuery) {
        androidx.compose.runtime.derivedStateOf(androidx.compose.runtime.referentialEqualityPolicy()) {
            if (searchQuery.isBlank()) sources
            else sources.filter {
                it.bookSourceName.contains(searchQuery, ignoreCase = true) ||
                it.bookSourceUrl.contains(searchQuery, ignoreCase = true) ||
                (it.bookSourceGroup ?: "").contains(searchQuery, ignoreCase = true)
            }
        }
    }

    // ── 排序（持久化字符串 → 强类型 enum + 升降序） ───────────────────
    // 排序在过滤之后、分组之前；这样：
    //  - 搜索仍按原始 customOrder 命中（用户键入精准词最快收敛），
    //  - 排好后再分组，每组内部就直接是用户期望顺序，无需 group header 内再排一次。
    // 排序结果用 remember 让 sources/sortBy/asc 任一变化才重算，否则重组不动。
    val sortByStr by viewModel.sortBy.collectAsStateWithLifecycle()
    val sortAsc by viewModel.sortAscending.collectAsStateWithLifecycle()
    val sortKey = SourceSortKey.fromKey(sortByStr)
    // sortedSources 同 filteredSources：用 derivedStateOf(referentialEqualityPolicy()) 绕开
    // BookSource.equals 只比 url 的去重陷阱。
    val sortedSources by remember(sortKey, sortAsc) {
        androidx.compose.runtime.derivedStateOf(androidx.compose.runtime.referentialEqualityPolicy()) {
            filteredSources.sortedBySourceKey(sortKey, sortAsc)
        }
    }

    // ── 分组模式（持久化字符串 → 强类型 enum） ─────────────────────────
    // 字符串容错：未知值 fromKey 落到 NONE，旧版本写脏值或用户手改 DataStore 不会崩。
    val groupModeStr by viewModel.groupMode.collectAsStateWithLifecycle()
    val groupMode = SourceGroupMode.fromKey(groupModeStr)
    // 分组结果只在源/搜索/模式变化时重算一次，避免每次重组遍历整个 source 列表。
    // 同 filteredSources / sortedSources：用 derivedStateOf(referentialEqualityPolicy())
    // 绕 BookSource.equals 只比 url 的陷阱。
    val grouped by remember(groupMode) {
        androidx.compose.runtime.derivedStateOf(androidx.compose.runtime.referentialEqualityPolicy()) {
            groupSources(sortedSources, groupMode)
        }
    }
    // 折叠组的 key 集合：rememberSaveable 让旋转 / 进程死亡也能保留状态。
    // 切换分组方式时 key 含义变了（比如从域名切到类型），旧 key 全部失效，主动清空。
    val collapsedGroups = rememberSaveable(
        saver = listSaver<MutableSet<String>, String>(
            save = { it.toList() },
            restore = { it.toMutableSet() },
        ),
    ) { mutableSetOf() }
    LaunchedEffect(groupMode) { collapsedGroups.clear() }

    Scaffold(
        // UX-10：左缘水平拖动 → 返回
        modifier = Modifier.swipeBackEdge(onBack = onBack),
        topBar = topBar@{
            if (selectionMode) {
                TopAppBar(
                    title = {
                        Text(
                            "已选 $selectedCount",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontWeight = FontWeight.Bold,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = exitSelection) {
                            Icon(Icons.Default.Close, "退出多选")
                        }
                    },
                    actions = {
                        IconButton(onClick = {
                            // 已在 selectedUrls 里的跳过，SnapshotStateList.addAll 不会自动去重。
                            sortedSources.forEach { s ->
                                if (s.bookSourceUrl !in selectedUrls) {
                                    selectedUrls.add(s.bookSourceUrl)
                                }
                            }
                        }) {
                            Icon(Icons.Default.SelectAll, "全选")
                        }
                        IconButton(
                            onClick = {
                                pendingExportUrls = selectedUrls.toSet()
                                exportLauncher.launch("morealm_sources_${selectedCount}.json")
                            },
                            enabled = selectedCount > 0,
                        ) {
                            Icon(
                                Icons.Default.FileDownload,
                                "导出选中",
                                tint = if (selectedCount > 0)
                                    MaterialTheme.colorScheme.onSurface
                                else
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                            )
                        }
                        IconButton(
                            onClick = { showBatchDeleteDialog = true },
                            enabled = selectedCount > 0,
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                "删除选中",
                                tint = if (selectedCount > 0)
                                    MaterialTheme.colorScheme.error
                                else
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background),
                )
                return@topBar
            }
            SourceManagementTopBar(
                sortKey = sortKey,
                sortAscending = sortAsc,
                sourceCount = sortedSources.size,
                isChecking = isChecking,
                onBack = onBack,
                onAdd = { showImportDialog = true },
                onSelect = { selectionMode = true },
                onCheck = {
                    if (isChecking) viewModel.cancelCheckSources() else viewModel.startCheckSources()
                },
                onSort = { key ->
                    if (key == sortKey) viewModel.setSortAscending(!sortAsc)
                    else viewModel.setSortBy(key.key)
                },
                onPaste = {
                        // Read clipboard via Android's native ClipboardManager
                        // — Compose's LocalClipboardManager.getText() returns
                        // null on Android 12+ when the focused field isn't a
                        // text field, missing perfectly valid clipboard data.
                        // Native API is also more permissive about reading
                        // non-text clip mime types as plaintext.
                        val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                            as? android.content.ClipboardManager
                        val clip = cm?.primaryClip?.takeIf { it.itemCount > 0 }
                            ?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty().trim()
                        when {
                            clip.isBlank() -> {
                                Toast.makeText(context, "剪贴板为空或无文本", Toast.LENGTH_SHORT).show()
                            }
                            clip.startsWith("[") || clip.startsWith("{") -> {
                                viewModel.importFromJson(clip)
                            }
                            clip.startsWith("http://", true) || clip.startsWith("https://", true) -> {
                                viewModel.importFromUrl(clip)
                            }
                            else -> {
                                Toast.makeText(
                                    context,
                                    "剪贴板内容既不是 JSON 也不是 URL",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        }
                },
                onExport = {
                    pendingExportUrls = null
                    exportLauncher.launch("morealm_sources_${sortedSources.size}.json")
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            // Disclaimer banner
            Surface(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f),
            ) {
                Text(
                    "免责声明：书源由用户自行导入和管理，MoRealm 不提供任何内容，" +
                    "不对书源内容的合法性、准确性负责。请遵守当地法律法规，" +
                    "仅用于获取已购买或公开授权的内容。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(10.dp),
                )
            }

            // Search bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("搜索书源") },
                leadingIcon = { Icon(Icons.Default.Search, null, Modifier.size(20.dp)) },
                shape = MaterialTheme.shapes.medium,
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    cursorColor = MaterialTheme.colorScheme.primary),
            )

            // 分组方式 chip 行 —— 与搜索框联动（先过滤后分组），4 选 1。
            // 横向滚动避免在小屏 / 大字号设置下挤压。
            GroupModeChips(
                selected = groupMode,
                onSelect = { viewModel.setGroupMode(it.key) },
            )

            // Stats bar
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("共 ${sources.size} 个书源",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                Text("启用 ${sources.count { it.enabled }}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f))
            }

            if (isImporting) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    if (importProgress.total > 0) {
                        Text(
                            "导入中 ${importProgress.current}/${importProgress.total}" +
                                if (importProgress.sourceName.isBlank()) "" else " · ${importProgress.sourceName}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                    LinearProgressIndicator(
                        progress = {
                            if (importProgress.total > 0) importProgress.current.toFloat() / importProgress.total else 0f
                        },
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            // Check progress bar
            if (isChecking) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("校验中 $checkProgress/$checkTotal",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary)
                        TextButton(onClick = { viewModel.cancelCheckSources() },
                            contentPadding = PaddingValues(0.dp),
                            modifier = Modifier.height(20.dp),
                        ) {
                            Text("取消", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    LinearProgressIndicator(
                        progress = { if (checkTotal > 0) checkProgress.toFloat() / checkTotal else 0f },
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            // Show "remove invalid" button after check completes
            if (!isChecking && checkResults.isNotEmpty()) {
                val invalidCount = checkResults.values.count { !it.isValid }
                if (invalidCount > 0) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("$invalidCount 个书源不可用",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { viewModel.removeInvalidSources() }) {
                            Text("删除不可用", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }

            // Source list
            if (filteredSources.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Extension, null,
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f),
                            modifier = Modifier.size(48.dp))
                        Spacer(Modifier.height(12.dp))
                        Text(if (sources.isEmpty()) "暂无书源" else "无匹配结果",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
                        if (sources.isEmpty()) {
                            Spacer(Modifier.height(12.dp))
                            // UX-6: 空态提供 CTA — 直接跳转到导入对话框，比让用户找右上角 + 更直接。
                            Button(
                                onClick = { showImportDialog = true },
                                shape = MaterialTheme.shapes.medium,
                            ) {
                                Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("导入书源")
                            }
                        }
                    }
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (groupMode == SourceGroupMode.NONE) {
                        // 不分组：保留旧行为，单层 items。
                        items(filteredSources, key = { it.bookSourceUrl }) { source ->
                            // [DIAG SourceToggle 2026-05-18] items lambda 调用日志。
                            // LazyColumn 即使 key 不变也应该 invoke 内容 lambda（除非整列
                            // skip）。这条日志只在 lambda 真被调时打——若 toggle 后没出 →
                            // LazyColumn 没看到 list 变化（list 引用相等？snapshot 没流到？）。
                            com.morealm.app.core.log.AppLog.info(
                                "SourceToggleDiag",
                                "items LAMBDA url=...${source.bookSourceUrl.takeLast(20)} enabled=${source.enabled}",
                            )
                            val checkResult = checkResults[source.bookSourceUrl]
                            // 不再 inline 跑 evalJS —— 直接查 ViewModel 预算的 map。
                            val isLoggedIn = loginStatusMap[source.bookSourceUrl] == true
                            SourceItem(
                                source = source,
                                enabled = source.enabled,
                                checkResult = checkResult,
                                isLoggedIn = isLoggedIn,
                                selected = source.bookSourceUrl in selectedUrls,
                                selectionMode = selectionMode,
                                onToggle = { viewModel.toggleSource(source.bookSourceUrl) },
                                onEdit = { editingSource = source },
                                onDelete = { viewModel.deleteSource(source) },
                                onLogin = { loginViewModel.showLoginDialog(source) },
                                onLogout = { loginViewModel.logout(source) },
                                onLongPress = {
                                    if (!selectionMode) selectionMode = true
                                    toggleSelect(source.bookSourceUrl)
                                },
                            )
                        }
                    } else {
                        // 分组：每组一个 GroupHeader item，下面跟（折叠时不渲染）该组的 items。
                        // SourceItem 的 LazyColumn key 加 label 前缀避免 group_name 模式下
                        // 「同一书源在多个分组重复出现」（实际单字段不会重复，但当作不变量
                        // 维护，以后扩到多 tag 分组时也安全）。
                        //
                        // 解构变量起名 `groupItems`，故意不叫 `items` —— 否则会遮蔽
                        // LazyListScope 上的 `items()` DSL 函数，编译期 unresolved。
                        grouped.forEach { (label, groupItems) ->
                            item(key = "header_$label") {
                                GroupHeader(
                                    label = label,
                                    total = groupItems.size,
                                    enabled = groupItems.count { it.enabled },
                                    collapsed = label in collapsedGroups,
                                    onToggleCollapsed = {
                                        if (label in collapsedGroups) collapsedGroups.remove(label)
                                        else collapsedGroups.add(label)
                                    },
                                    onEnableAll = {
                                        viewModel.setEnabledForUrls(groupItems.map { it.bookSourceUrl }, true)
                                    },
                                    onDisableAll = {
                                        viewModel.setEnabledForUrls(groupItems.map { it.bookSourceUrl }, false)
                                    },
                                )
                            }
                            if (label !in collapsedGroups) {
                                items(groupItems, key = { "${label}::${it.bookSourceUrl}" }) { source ->
                                    val checkResult = checkResults[source.bookSourceUrl]
                                    val isLoggedIn = loginStatusMap[source.bookSourceUrl] == true
                                    SourceItem(
                                        source = source,
                                        enabled = source.enabled,
                                        checkResult = checkResult,
                                        isLoggedIn = isLoggedIn,
                                        selected = source.bookSourceUrl in selectedUrls,
                                        selectionMode = selectionMode,
                                        onToggle = { viewModel.toggleSource(source.bookSourceUrl) },
                                        onEdit = { editingSource = source },
                                        onDelete = { viewModel.deleteSource(source) },
                                        onLogin = { loginViewModel.showLoginDialog(source) },
                                        onLogout = { loginViewModel.logout(source) },
                                        onLongPress = {
                                            if (!selectionMode) selectionMode = true
                                            toggleSelect(source.bookSourceUrl)
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Import dialog
    if (showImportDialog) {
        AlertDialog(
            onDismissRequest = { showImportDialog = false },
            title = { Text("导入书源") },
            text = {
                Column {
                    Text("输入书源 JSON 或订阅 URL",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    Spacer(Modifier.height(12.dp))
                    // UX-3: 弹出导入对话框 → 自动聚焦到 URL/JSON 输入框 + 弹软键盘
                    val importFocusRequester = remember { FocusRequester() }
                    LaunchedEffect(Unit) { importFocusRequester.requestFocus() }
                    // UX-5: 剪贴板嗅探 — 弹出对话框时检测剪贴板里是否含 http(s) URL 或 JSON，
                    // 是则展示一行「使用剪贴板内容」chip，省一次粘贴。
                    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
                    var clipHint by remember { mutableStateOf<String?>(null) }
                    LaunchedEffect(Unit) {
                        if (importUrl.isNotBlank()) return@LaunchedEffect
                        val raw = clipboardManager.getText()?.text?.trim().orEmpty()
                        // 200 字以内的合法 URL 直接吸；JSON 长，>200 也允许（书源 JSON 通常很长）
                        val looksUrl = raw.startsWith("http://", true) || raw.startsWith("https://", true)
                        val looksJson = raw.startsWith("[") || raw.startsWith("{")
                        if ((looksUrl && raw.length < 500) || (looksJson && raw.length > 30)) {
                            clipHint = raw
                        }
                    }
                    if (clipHint != null) {
                        AssistChip(
                            onClick = {
                                importUrl = clipHint!!
                                clipHint = null
                            },
                            label = {
                                val preview = clipHint!!.take(40).replace("\n", " ") + if (clipHint!!.length > 40) "…" else ""
                                Text("使用剪贴板：$preview", style = MaterialTheme.typography.labelSmall)
                            },
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }
                    // UX-4: 内联校验 — 输入空白不报错，否则要么 http(s) URL 要么 JSON 起首
                    val trimmed = importUrl.trim()
                    val looksLikeUrl = trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)
                    val looksLikeJson = trimmed.startsWith("[") || trimmed.startsWith("{")
                    val importError = trimmed.isNotEmpty() && !looksLikeUrl && !looksLikeJson
                    OutlinedTextField(
                        value = importUrl,
                        onValueChange = { importUrl = it },
                        modifier = Modifier.fillMaxWidth().focusRequester(importFocusRequester),
                        placeholder = { Text("URL 或 JSON") },
                        isError = importError,
                        supportingText = if (importError) {
                            { Text("应以 http(s):// 开头或粘贴 JSON 数组/对象", color = MaterialTheme.colorScheme.error) }
                        } else null,
                        shape = MaterialTheme.shapes.small,
                        minLines = 3,
                        maxLines = 6,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            cursorColor = MaterialTheme.colorScheme.primary),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            showImportDialog = false
                            filePickerLauncher.launch("*/*")
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.FileOpen, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("选择本地文件")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showImportDialog = false
                    if (importUrl.isNotBlank()) {
                        viewModel.importFromUrl(importUrl)
                        importUrl = ""
                    }
                }) { Text("导入", color = MaterialTheme.colorScheme.primary) }
            },
            dismissButton = {
                TextButton(onClick = { showImportDialog = false }) { Text("取消") }
            },
        )
    }

    // Edit source screen (full-screen overlay)
    editingSource?.let { source ->
        val debugStepsState by viewModel.debugSteps.collectAsStateWithLifecycle()
        val isDebuggingState by viewModel.isDebugging.collectAsStateWithLifecycle()
        BookSourceEditScreen(
            source = source,
            onBack = {
                viewModel.cancelDebug()
                editingSource = null
            },
            onSave = { updated ->
                viewModel.saveSource(updated)
                editingSource = null
            },
            debugSteps = debugStepsState,
            isDebugging = isDebuggingState,
            onDebug = { src, keyword -> viewModel.debugSource(src, keyword) },
            onCancelDebug = { viewModel.cancelDebug() },
        )
    }

    // Login dialog and state handling —— 共享 overlay，阅读器、详情页同款。
    SourceLoginOverlay(
        loginViewModel = loginViewModel,
        onNavigateToLog = onNavigateToLog,
    )

    // ── #2 CheckSource 完成弹窗 ──
    val showInvalidDialog by viewModel.isInvalidResultsDialogVisible.collectAsStateWithLifecycle()
    if (showInvalidDialog) {
        val invalidResults by viewModel.invalidCheckResults.collectAsStateWithLifecycle()
        CheckResultsDialog(
            invalidResults = invalidResults,
            onDismiss = { viewModel.dismissInvalidResultsDialog() },
            onDelete = { selectedUrls ->
                viewModel.deleteInvalidSources(selectedUrls)
            },
        )
    }

    // ── 多选删除确认弹窗 ──────────────────────────────────────────
    // 不止是 UX 问题：删书源不可逆，重手操作必须二次确认。
    if (showBatchDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showBatchDeleteDialog = false },
            title = { Text("删除选中书源") },
            text = {
                Text(
                    "确定删除选中的 $selectedCount 个书源吗？此操作不可撤销。",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val snapshot = selectedUrls.toList()
                    viewModel.deleteSources(snapshot)
                    showBatchDeleteDialog = false
                    exitSelection()
                    Toast.makeText(
                        context,
                        "已删除 ${snapshot.size} 个书源",
                        Toast.LENGTH_SHORT,
                    ).show()
                }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showBatchDeleteDialog = false }) { Text("取消") }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun GroupModeChips(
    selected: SourceGroupMode,
    onSelect: (SourceGroupMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SourceGroupMode.entries.forEach { mode ->
            FilterChip(
                selected = selected == mode,
                onClick = { onSelect(mode) },
                label = { Text(mode.label, style = MaterialTheme.typography.labelMedium) },
                shape = MaterialTheme.shapes.small,
            )
        }
    }
}

/**
 * 一个分组的标题行：折叠箭头 + 组名 + 「启用/总数」徽标 + 三点菜单（全启用/全停用）。
 *
 * 整行点击切折叠态；批量操作走右上角 DropdownMenu，避免误触。菜单项里把数量
 * 直接写进文案（"全部启用 (12)"），让用户在确认前就看到影响范围。
 */
@Composable
private fun GroupHeader(
    label: String,
    total: Int,
    enabled: Int,
    collapsed: Boolean,
    onToggleCollapsed: () -> Unit,
    onEnableAll: () -> Unit,
    onDisableAll: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggleCollapsed)
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (collapsed) Icons.Default.KeyboardArrowRight else Icons.Default.KeyboardArrowDown,
            null,
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            label,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "$enabled / $total",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
        )
        Spacer(Modifier.weight(1f))
        Box {
            IconButton(
                onClick = { menuExpanded = true },
                modifier = Modifier.size(28.dp),
            ) {
                Icon(
                    Icons.Default.MoreVert,
                    "组操作",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                )
            }
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
            ) {
                DropdownMenuItem(
                    text = { Text("全部启用 ($total)") },
                    leadingIcon = {
                        Icon(Icons.Default.CheckCircle, null, modifier = Modifier.size(18.dp))
                    },
                    onClick = {
                        onEnableAll()
                        menuExpanded = false
                    },
                )
                DropdownMenuItem(
                    text = { Text("全部停用 ($total)") },
                    leadingIcon = {
                        Icon(Icons.Default.Cancel, null, modifier = Modifier.size(18.dp))
                    },
                    onClick = {
                        onDisableAll()
                        menuExpanded = false
                    },
                )
            }
        }
    }
}
