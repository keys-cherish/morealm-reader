package com.morealm.app.ui.source

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
    val sources by viewModel.sources.collectAsStateWithLifecycle()
    val isImporting by viewModel.isImporting.collectAsStateWithLifecycle()
    val importProgress by viewModel.importProgress.collectAsStateWithLifecycle()
    val importResult by viewModel.importResult.collectAsStateWithLifecycle()
    val isChecking by viewModel.isChecking.collectAsStateWithLifecycle()
    val checkProgress by viewModel.checkProgress.collectAsStateWithLifecycle()
    val checkTotal by viewModel.checkTotal.collectAsStateWithLifecycle()
    val checkResults by viewModel.checkResults.collectAsStateWithLifecycle()
    val loginUiState by loginViewModel.uiState.collectAsStateWithLifecycle()
    // 预算缓存：进屏 + sources 变化时后台跑 evalJS，UI 只 O(1) 查表，
    // 滚动不再卡顿。详见 SourceLoginViewModel.refreshLoginStatuses。
    val loginStatusMap by loginViewModel.loginStatusMap.collectAsStateWithLifecycle()
    LaunchedEffect(sources) {
        if (sources.isNotEmpty()) loginViewModel.refreshLoginStatuses(sources)
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

    // Show import result toast
    LaunchedEffect(importResult) {
        importResult?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.clearImportResult()
        }
    }

    val filteredSources = remember(sources, searchQuery) {
        if (searchQuery.isBlank()) sources
        else sources.filter {
            it.bookSourceName.contains(searchQuery, ignoreCase = true) ||
            it.bookSourceUrl.contains(searchQuery, ignoreCase = true) ||
            (it.bookSourceGroup ?: "").contains(searchQuery, ignoreCase = true)
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
    val sortedSources = remember(filteredSources, sortKey, sortAsc) {
        filteredSources.sortedBySourceKey(sortKey, sortAsc)
    }

    // ── 分组模式（持久化字符串 → 强类型 enum） ─────────────────────────
    // 字符串容错：未知值 fromKey 落到 NONE，旧版本写脏值或用户手改 DataStore 不会崩。
    val groupModeStr by viewModel.groupMode.collectAsStateWithLifecycle()
    val groupMode = SourceGroupMode.fromKey(groupModeStr)
    // 分组结果只在源/搜索/模式变化时重算一次，避免每次重组遍历整个 source 列表。
    val grouped = remember(sortedSources, groupMode) {
        groupSources(sortedSources, groupMode)
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
        topBar = {
            TopAppBar(
                title = { Text("书源管理", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                actions = {
                    // Sort menu — 五维度 + 升降反转。当前选中维度前缀✓ ；
                    // 点同一维度切升降，点不同维度只换 sort key 保留方向。
                    var sortMenuExpanded by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { sortMenuExpanded = true }) {
                            Icon(Icons.Default.Sort, "排序")
                        }
                        DropdownMenu(
                            expanded = sortMenuExpanded,
                            onDismissRequest = { sortMenuExpanded = false },
                        ) {
                            // Header — "升序 / 降序" 切换条目，独立于维度选择，避免每次反向都要再点一次维度。
                            DropdownMenuItem(
                                text = {
                                    Text(if (sortAsc) "当前：升序（点切降序）" else "当前：降序（点切升序）")
                                },
                                leadingIcon = {
                                    Icon(
                                        if (sortAsc) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                                        null, modifier = Modifier.size(18.dp),
                                    )
                                },
                                onClick = {
                                    viewModel.setSortAscending(!sortAsc)
                                    sortMenuExpanded = false
                                },
                            )
                            HorizontalDivider()
                            for (k in SourceSortKey.entries) {
                                val isSelected = k == sortKey
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            (if (isSelected) "✓ " else "  ") + k.label,
                                            color = if (isSelected) MaterialTheme.colorScheme.primary
                                                    else MaterialTheme.colorScheme.onSurface,
                                        )
                                    },
                                    onClick = {
                                        // 点同一维度 = 翻方向；点新维度 = 切到该维度，方向保留。
                                        if (isSelected) {
                                            viewModel.setSortAscending(!sortAsc)
                                        } else {
                                            viewModel.setSortBy(k.key)
                                        }
                                        sortMenuExpanded = false
                                    },
                                )
                            }
                        }
                    }
                    // Check sources button
                    IconButton(onClick = {
                        if (isChecking) viewModel.cancelCheckSources()
                        else viewModel.startCheckSources()
                    }) {
                        Icon(
                            if (isChecking) Icons.Default.Close else Icons.Default.CheckCircle,
                            if (isChecking) "停止校验" else "校验书源",
                        )
                    }
                    IconButton(onClick = {
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
                    }) {
                        Icon(Icons.Default.ContentPaste, "粘贴导入")
                    }
                    IconButton(onClick = { showImportDialog = true }) {
                        Icon(Icons.Default.Add, "添加书源")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background),
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
                            val checkResult = checkResults[source.bookSourceUrl]
                            // 不再 inline 跑 evalJS —— 直接查 ViewModel 预算的 map。
                            val isLoggedIn = loginStatusMap[source.bookSourceUrl] == true
                            SourceItem(
                                source = source,
                                checkResult = checkResult,
                                isLoggedIn = isLoggedIn,
                                onToggle = { viewModel.toggleSource(source) },
                                onEdit = { editingSource = source },
                                onDelete = { viewModel.deleteSource(source) },
                                onLogin = { loginViewModel.showLoginDialog(source) },
                                onLogout = { loginViewModel.logout(source) },
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
                                        checkResult = checkResult,
                                        isLoggedIn = isLoggedIn,
                                        onToggle = { viewModel.toggleSource(source) },
                                        onEdit = { editingSource = source },
                                        onDelete = { viewModel.deleteSource(source) },
                                        onLogin = { loginViewModel.showLoginDialog(source) },
                                        onLogout = { loginViewModel.logout(source) },
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

    // Login dialog and state handling
    when (val state = loginUiState) {
        is com.morealm.app.presentation.source.LoginUiState.ShowDialog -> {
            SourceLoginDialog(
                source = state.source,
                fields = state.rows,
                onDismiss = { loginViewModel.dismissDialog() },
                onLogin = { fieldValues ->
                    loginViewModel.login(state.source, fieldValues)
                },
                onActionJs = { actionJs, currentValues ->
                    loginViewModel.runActionJs(state.source, actionJs, currentValues)
                },
                onNavigateToLog = onNavigateToLog,
                uiPatchFlow = loginViewModel.uiPatch,
                uiRebuildFlow = loginViewModel.uiRebuild,
            )
        }
        is com.morealm.app.presentation.source.LoginUiState.ShowWebView -> {
            WebViewLoginScreen(
                source = state.source,
                loginUrl = state.url,
                headerMap = state.headerMap,
                onDismiss = { loginViewModel.dismissDialog() },
                onLoginComplete = {
                    loginViewModel.dismissDialog()
                    Toast.makeText(context, "登录完成", Toast.LENGTH_SHORT).show()
                },
            )
        }
        is com.morealm.app.presentation.source.LoginUiState.Loading -> {
            AlertDialog(
                onDismissRequest = {},
                title = { Text("登录中") },
                text = { Text(state.message) },
                confirmButton = {},
            )
        }
        is com.morealm.app.presentation.source.LoginUiState.Success -> {
            LaunchedEffect(state) {
                Toast.makeText(context, state.message, Toast.LENGTH_SHORT).show()
                loginViewModel.dismissDialog()
            }
        }
        is com.morealm.app.presentation.source.LoginUiState.Error -> {
            LaunchedEffect(state) {
                Toast.makeText(context, state.message, Toast.LENGTH_LONG).show()
                loginViewModel.dismissDialog()
            }
        }
        com.morealm.app.presentation.source.LoginUiState.Idle -> {}
    }

    // ── #2 CheckSource 完成弹窗 ──
    val showInvalidDialog by viewModel.showInvalidResultsDialog.collectAsStateWithLifecycle()
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
}

@Composable
private fun SourceItem(
    source: BookSource,
    checkResult: CheckSource.CheckResult? = null,
    isLoggedIn: Boolean = false,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onLogin: () -> Unit = {},
    onLogout: () -> Unit = {},
) {
    val moColors = LocalMoRealmColors.current
    var showMenu by remember { mutableStateOf(false) }
    val bgColor by animateColorAsState(
        if (source.enabled) MaterialTheme.colorScheme.surfaceContainerHigh
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        label = "sourceBg"
    )

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = bgColor,
    ) {
        Row(
            modifier = Modifier
                .clickable { showMenu = !showMenu }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        source.bookSourceName.ifBlank { source.bookSourceUrl },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = if (source.enabled) MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (checkResult != null) {
                        Spacer(Modifier.width(6.dp))
                        val scoreColor = when {
                            checkResult.score >= 4 -> MaterialTheme.colorScheme.tertiary
                            checkResult.score >= 2 -> MaterialTheme.colorScheme.secondary
                            else -> MaterialTheme.colorScheme.error
                        }
                        Text(
                            "${checkResult.score}/4",
                            style = MaterialTheme.typography.labelSmall,
                            color = scoreColor,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                Row {
                    if (!source.bookSourceGroup.isNullOrBlank()) {
                        Text(
                            source.bookSourceGroup!!,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        source.bookSourceUrl,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // Show check error if present
                if (checkResult?.error != null) {
                    Text(
                        checkResult.error!!,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Switch(
                checked = source.enabled,
                onCheckedChange = { onToggle() },
                colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.primary),
                modifier = Modifier.padding(start = 8.dp),
            )
            // Login button (only show if source has loginUrl)
            if (!source.loginUrl.isNullOrBlank()) {
                IconButton(
                    onClick = { if (isLoggedIn) onLogout() else onLogin() },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        if (isLoggedIn) Icons.Default.Lock else Icons.Default.LockOpen,
                        if (isLoggedIn) "已登录" else "未登录",
                        tint = if (isLoggedIn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Edit, "编辑",
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Delete, "删除",
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                    modifier = Modifier.size(18.dp))
            }
        }
    }
}

// ─── 分组功能 ────────────────────────────────────────────────────────────
//
// 这一段在文件尾部独立成块：
//   1. SourceGroupMode 与 BookSourceManageScreen 强耦合（key 字符串和 AppPreferences
//      约定一一对应），不值得抽到 domain 层；
//   2. 与现有 SourceItem 一样属于"内部组件"，对外不暴露；
//   3. 顺序上把 enum/helpers 放在 composable 之前，让阅读时先看到状态模型。

/**
 * 列表分组方式。`key` 字段用于 DataStore 持久化，[label] 用于 chip UI 显示。
 *
 * 为什么用 `String` 而不是 `enum.ordinal`？序列化稳定性：今后增删枚举值（比如加
 * "按可用性"、"按响应时间"分组）时，旧用户的持久化值依旧能正确解析或安全 fallback；
 * 用 ordinal 需要小心增减位置。
 */
private enum class SourceGroupMode(val key: String, val label: String) {
    NONE("none", "不分组"),
    GROUP_NAME("group_name", "按分组"),
    DOMAIN("domain", "按域名"),
    TYPE("type", "按类型"),
    ;

    companion object {
        fun fromKey(k: String): SourceGroupMode =
            entries.firstOrNull { it.key == k } ?: NONE
    }
}

/**
 * BookSource.bookSourceType → 中文 label。
 *
 * 当前 schema（见 BookSource.kt 注释）只定义了 0..3 四类；超出范围的值（旧版本写脏数据
 * 或 Legado 备份带来非标准类型）一律落到「其他」组而不是丢失/崩溃。
 */
private fun typeLabel(t: Int): String = when (t) {
    0 -> "文本"
    1 -> "音频"
    2 -> "图片"
    3 -> "文件"
    else -> "其他"
}

/**
 * 从 `bookSourceUrl` 提取主机名。`java.net.URI` 解析失败（用户在 url 里写了协议外的
 * 奇形怪状字符）时降级为整段 URL —— 比抛异常或留空都友好：用户至少能看到一个唯一的
 * 分组，自己识别去补全。
 */
private fun extractDomain(url: String): String =
    runCatching {
        val uri = java.net.URI(url.trim())
        uri.host?.takeIf { it.isNotBlank() } ?: url
    }.getOrElse { url }

/**
 * 把一组书源按 [mode] 划分成 `(label, sources)` 列表。
 *
 * - NONE：返回空（caller 自己用 items 平铺）；
 * - GROUP_NAME：以 `bookSourceGroup` trim 后的全字符串为 key；空字符串归到「未分组」；
 *               不拆分逗号分隔（与 Legado 一致：多 tag 是同一个组）；
 * - DOMAIN：以 [extractDomain] 结果为 key，按字典序输出；
 * - TYPE：以 [typeLabel] 结果为 key，输出顺序固定为「文本→音频→图片→文件→其他」，
 *         比字典序更符合用户期望（用户最常用的"文本"永远排第一）。
 */
private fun groupSources(
    sources: List<BookSource>,
    mode: SourceGroupMode,
): List<Pair<String, List<BookSource>>> = when (mode) {
    SourceGroupMode.NONE -> emptyList()
    SourceGroupMode.GROUP_NAME -> sources
        .groupBy { (it.bookSourceGroup ?: "").trim().ifBlank { "未分组" } }
        .toSortedMap()
        .map { (k, v) -> k to v }
    SourceGroupMode.DOMAIN -> sources
        .groupBy { extractDomain(it.bookSourceUrl) }
        .toSortedMap()
        .map { (k, v) -> k to v }
    SourceGroupMode.TYPE -> {
        val groups = sources.groupBy { typeLabel(it.bookSourceType) }
        // 固定顺序：文本/音频/图片/文件/其他。空组不出现（mapNotNull）。
        listOf("文本", "音频", "图片", "文件", "其他")
            .mapNotNull { name -> groups[name]?.let { name to it } }
    }
}

/**
 * 分组方式 chip 行。横向滚动避免在小屏 / 大字号下挤压。
 *
 * 不用 `SegmentedButton` 是因为 SegmentedButton 不支持「不勾选任何项」的状态，
 * 而 FilterChip 多选/单选语义都够用。这里用单选。
 */
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
