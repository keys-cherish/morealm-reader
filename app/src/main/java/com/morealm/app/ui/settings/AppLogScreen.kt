package com.morealm.app.ui.settings

import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.morealm.app.core.log.AppLog
import com.morealm.app.core.log.CleanupReport
import com.morealm.app.core.log.LogLevel
import com.morealm.app.core.log.LogRecord
import com.morealm.app.core.log.LogTagCatalog
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppLogScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val logs by AppLog.logs.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf<LogRecord?>(null) }
    val timeFmt = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.US) }
    val dateFmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.US) }

    var selectedTab by remember { mutableIntStateOf(0) }
    val crashFiles = remember { mutableStateOf(AppLog.getCrashFiles()) }
    var selectedCrashFile by remember { mutableStateOf<File?>(null) }
    var crashContent by remember { mutableStateOf("") }
    var recordLog by remember { mutableStateOf(AppLog.isRecordLogEnabled()) }

    // Tag filter — null 表示「全部」。可选 tag 列表从当前 logs 动态推算，
    // 这样新出现的 tag（比如临时埋点 PageTurnFlicker）会自动出现在 chip 行
    // 里，不需要硬编码。当前选中的 tag 在 logs 变化时若已不存在（如清空后），
    // 会被自动重置为 null —— 见 LaunchedEffect。
    var tagFilter by remember { mutableStateOf<String?>(null) }
    val availableTags by remember(logs) {
        derivedStateOf {
            logs.map { it.tag }.distinct().sorted()
        }
    }
    LaunchedEffect(availableTags) {
        if (tagFilter != null && tagFilter !in availableTags) tagFilter = null
    }

    // 搜索：仅作用于「运行日志」tab；切到崩溃记录 tab 时关闭搜索栏并清空 query。
    // searchExpanded 控制搜索栏是否在 TabRow 下方展开；点 actions 区的 Search
    // 图标 toggle，再点一次（已展开）则收起并清空——这样手指不需要去点 ✕。
    // 注意声明顺序：必须在 filteredLogs 之前，Kotlin 不允许向前引用 var。
    var searchExpanded by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    // tag 速查 dialog：用户点顶栏 ? 图标弹出，列出所有已知 tag 的中文用途
    // 和「出什么问题看它」。这是 LogTagCatalog 的可视化窗口，避免用户对着
    // 一堆生疏 tag 不知道挑哪个看。
    var tagCatalogOpen by remember { mutableStateOf(false) }

    val filteredLogs = remember(logs, tagFilter, searchQuery) {
        val byTag = if (tagFilter == null) logs else logs.filter { it.tag == tagFilter }
        if (searchQuery.isBlank()) byTag else {
            // 不区分大小写，匹配 tag / message / throwable 任一字段。
            // contains 比 regex 简单稳定 —— 用户输入"speak"想找"speakLoop"
            // 不需要他自己写 ".*speak.*"。throwable 也搜进去是因为崩溃堆栈关
            // 键词（如 "NullPointerException"）经常是用户最想定位的线索。
            val q = searchQuery.trim()
            byTag.filter { record ->
                record.message.contains(q, ignoreCase = true) ||
                    record.tag.contains(q, ignoreCase = true) ||
                    record.throwable?.contains(q, ignoreCase = true) == true
            }
        }
    }

    // 顶层提一份 clipboard / Toast 触发器：批量复制按钮（actions 区）和 LogListTab
    // 单条长按复制（item 区）都用这一份；避免每个调用点都各自去 LocalComposition 拿。
    //
    // 直接走 Android 原生 ClipboardManager + ClipData.newPlainText(String)，
    // 不用 Compose 的 LocalClipboardManager。原因：
    //   1. LocalClipboardManager.setText(AnnotatedString) 会把 SpanStyle/
    //      ParagraphStyle 一起序列化进 Parcel，对纯文本而言是无谓开销，
    //      且会显著放大 IPC payload。
    //   2. 大体量日志（几千条）的拼接结果接近甚至超过 Binder 单次事务上
    //      限，AnnotatedString 包装更容易把它推过限值；超限时 ClipboardService
    //      静默丢包，调用看起来是成功的、Toast 也照弹，剪贴板里实则是空的。
    //   3. 改用原生 String 路径后，Android 26+ ClipboardService 自带的
    //      ashmem 机制会替超大 ClipData 走共享内存通道，几千条普通日志
    //      不再有这个问题。catastrophic 失败时下面 try/catch 会兜底提示。
    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
        as android.content.ClipboardManager

    /**
     * 写入剪贴板的统一入口；失败时把异常吞掉但写一条错误日志 + Toast 真实
     * 反馈。绝对避免「Toast 假装成功，剪贴板空空」这种最难排查的形态。
     */
    fun copyToClipboard(text: String, successMsg: String) {
        try {
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("MoRealm Logs", text))
            Toast.makeText(context, successMsg, Toast.LENGTH_SHORT).show()
        } catch (t: Throwable) {
            AppLog.error("AppLog", "clipboard copy failed (size=${text.length})", t)
            Toast.makeText(context, "复制失败：内容过大或系统拒绝", Toast.LENGTH_SHORT).show()
        }
    }

    // 多选模式：长按 item 进入；selectionMode = true 时点击 = toggle 选中。
    // selectedIds 用 LogRecord.id（AtomicLong 自增，进程内唯一）做 key —— 但
    // logs 是 ring buffer 会被淘汰，所以渲染时 selectedIds 与 filteredLogs
    // 取交集即可，不必主动清理 selectedIds（旧 id 自然被忽略）。
    var selectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }

    LaunchedEffect(selectedTab) {
        // 切 tab 时复位搜索 + 退出多选（多选/搜索只对运行日志生效）。
        if (selectedTab != 0) {
            searchExpanded = false
            searchQuery = ""
            selectionMode = false
            selectedIds = emptySet()
        }
    }
    val selectedRecords = remember(filteredLogs, selectedIds) {
        filteredLogs.filter { it.id in selectedIds }
    }

    // 「日志清理」折叠面板状态。展开时显示 4 个 slider + 立即清理按钮。
    // 折叠时只是一行可点击的标题条（避免占空间影响日志列表）。
    var cleanupExpanded by remember { mutableStateOf(false) }
    // 当前限额从 AppLog 读取一次作为初始值；用户调整 slider 时本地 state
    // 立即更新（驱动 UI），松手或点保存时才回写 AppLog（避免 prefs.apply
    // 在每个 slider tick 上拍写）。这里直接 collect 一份，因为面板第一次
    // 展开时才 mount，不会引起初始化期开销。
    var limits by remember { mutableStateOf(AppLog.getLogLimits()) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { LogScreenTitle(selectionMode, selectedRecords.size) },
                navigationIcon = {
                    LogScreenNavIcon(
                        selectionMode = selectionMode,
                        onBack = onBack,
                        onExitSelection = {
                            selectionMode = false
                            selectedIds = emptySet()
                        },
                    )
                },
                actions = {
                    if (selectionMode) {
                        SelectionModeActions(
                            selectedRecords = selectedRecords,
                            filteredLogs = filteredLogs,
                            allSelected = selectedIds.size == filteredLogs.size && filteredLogs.isNotEmpty(),
                            onCopy = {
                                copyToClipboard(
                                    text = joinRecordsText(selectedRecords),
                                    successMsg = "已复制 ${selectedRecords.size} 条",
                                )
                                selectionMode = false
                                selectedIds = emptySet()
                            },
                            onToggleSelectAll = {
                                selectedIds = if (selectedIds.size == filteredLogs.size)
                                    emptySet()
                                else filteredLogs.map { it.id }.toSet()
                            },
                        )
                    } else {
                        NormalModeActions(
                            tabIsLogs = selectedTab == 0,
                            filteredLogs = filteredLogs,
                            tagFilter = tagFilter,
                            searchActive = searchExpanded || searchQuery.isNotEmpty(),
                            onToggleSearch = {
                                if (searchExpanded) {
                                    // 收起：把 query 也清空，避免"看不见的过滤"造成困惑
                                    searchExpanded = false
                                    searchQuery = ""
                                } else {
                                    searchExpanded = true
                                }
                            },
                            onOpenTagCatalog = { tagCatalogOpen = true },
                            onCopyAll = {
                                val label = if (tagFilter != null) "tag=$tagFilter" else "全部"
                                copyToClipboard(
                                    text = joinRecordsText(filteredLogs),
                                    successMsg = "已复制 ${filteredLogs.size} 条（$label）",
                                )
                            },
                            onExportTxt = { exportLogTxt(context, filteredLogs, tagFilter) },
                            onClear = { AppLog.clear() },
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // Tab row: 运行日志 | 崩溃记录
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                contentColor = MaterialTheme.colorScheme.primary,
            ) {
                Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }) {
                    Text("运行日志", modifier = Modifier.padding(vertical = 12.dp),
                        style = MaterialTheme.typography.labelLarge)
                }
                Tab(selected = selectedTab == 1, onClick = {
                    selectedTab = 1
                    crashFiles.value = AppLog.getCrashFiles()
                }) {
                    val count = crashFiles.value.size
                    Text(
                        if (count > 0) "崩溃记录 ($count)" else "崩溃记录",
                        modifier = Modifier.padding(vertical = 12.dp),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }

            // RecordLog toggle
            Row(
                modifier = Modifier.fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("详细日志记录", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    modifier = Modifier.weight(1f))
                Switch(
                    checked = recordLog,
                    onCheckedChange = {
                        recordLog = it
                        AppLog.setRecordLog(it)
                    },
                    modifier = Modifier.height(32.dp),
                )
            }

            // ── 日志清理面板 ──
            // 折叠的标题条 + 展开后的 4 个 slider + 立即清理按钮。
            // 仅在「运行日志」tab 下显示——崩溃 tab 上没必要露这个 UI，
            // 用户在那里只关心崩溃文件本身。
            if (selectedTab == 0) {
                LogCleanupPanel(
                    expanded = cleanupExpanded,
                    onToggleExpanded = { cleanupExpanded = !cleanupExpanded },
                    limits = limits,
                    onLimitsChanged = { newLimits ->
                        // 每次拖动结束 / 输入变化都立即写回 prefs。setLogLimits
                        // 内部已 clamp + persist + 应用到 live sinks。
                        // 写回后再读一次（拿到 clamped 值）防止 UI 显示
                        // 用户输入的非法值（比如 0 条内存）。
                        AppLog.setLogLimits(newLimits)
                        limits = AppLog.getLogLimits()
                    },
                    onCleanupNow = {
                        val report = AppLog.cleanupNow()
                        scope.launch {
                            snackbarHostState.showSnackbar(
                                cleanupReportMessage(report),
                                withDismissAction = true,
                            )
                        }
                    },
                )
            }

            // 搜索栏：仅在「运行日志」tab + 用户点 actions 区 Search 图标后展开。
            // 选用普通 OutlinedTextField 而不是 SearchBar：SearchBar 是带建议
            // 列表的全屏组件，吃高度太多；这里只需要纯文本过滤。
            if (selectedTab == 0 && searchExpanded) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    placeholder = {
                        Text("搜索 message / tag / 异常堆栈…",
                            style = MaterialTheme.typography.bodySmall)
                    },
                    leadingIcon = { Icon(Icons.Default.Search, null, modifier = Modifier.size(18.dp)) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Close, "清空", modifier = Modifier.size(18.dp))
                            }
                        }
                    },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        imeAction = ImeAction.Search,
                    ),
                    textStyle = MaterialTheme.typography.bodyMedium,
                )
            }

            // Tag 过滤 chip 行：仅在「运行日志」tab 显示。空列表时整行隐藏。
            // FilterChip + LazyRow 横向滚动；选中的 chip 会再调一次 onClick
            // 取消选中（toggle 语义），便于一键回到「全部」。
            if (selectedTab == 0 && availableTags.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier.fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    item {
                        FilterChip(
                            selected = tagFilter == null,
                            onClick = { tagFilter = null },
                            label = { Text("全部 (${logs.size})", style = MaterialTheme.typography.labelSmall) },
                        )
                    }
                    items(availableTags, key = { it }) { tag ->
                        val count = logs.count { it.tag == tag }
                        FilterChip(
                            selected = tagFilter == tag,
                            onClick = { tagFilter = if (tagFilter == tag) null else tag },
                            label = {
                                Text("$tag ($count)", style = MaterialTheme.typography.labelSmall)
                            },
                        )
                    }
                }
            }

            when (selectedTab) {
                0 -> LogListTab(
                    logs = filteredLogs,
                    selected = selected,
                    timeFmt = timeFmt,
                    selectionMode = selectionMode,
                    selectedIds = selectedIds,
                    emptyMessage = when {
                        searchQuery.isNotEmpty() -> "无匹配「$searchQuery」"
                        tagFilter != null -> "tag = $tagFilter 下暂无日志"
                        else -> "暂无日志"
                    },
                    onItemClick = { record ->
                        if (selectionMode) {
                            selectedIds = if (record.id in selectedIds)
                                selectedIds - record.id
                            else
                                selectedIds + record.id
                        } else {
                            selected = if (selected == record) null else record
                        }
                    },
                    onItemLongPress = { record ->
                        // 普通模式长按：进入多选并选中本条。多选模式下长按无操作，
                        // 避免和「单击切换选中」语义打架（Android 系统图库 / 文件
                        // 管理器的常用手势惯例）。
                        if (!selectionMode) {
                            selectionMode = true
                            selectedIds = setOf(record.id)
                        }
                    },
                )
                1 -> CrashFilesTab(
                    crashFiles = crashFiles.value,
                    selectedFile = selectedCrashFile,
                    crashContent = crashContent,
                    dateFmt = dateFmt,
                    onFileClick = { file ->
                        if (selectedCrashFile == file) {
                            selectedCrashFile = null
                            crashContent = ""
                        } else {
                            selectedCrashFile = file
                            crashContent = try { file.readText() } catch (_: Exception) { "读取失败" }
                        }
                    },
                )
            }
        }
    }

    // tag 速查 dialog —— 放 Scaffold 之外，由顶栏 Help 按钮控制。
    if (tagCatalogOpen) {
        TagCatalogDialog(onDismiss = { tagCatalogOpen = false })
    }
}

/**
 * 「日志 tag 速查」对话框：按模块分节列出所有已知 tag 的中文用途和「出问题
 * 看它」。这是 LogTagCatalog 的可视化窗口，让用户对着陌生 tag 时不必查代码。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TagCatalogDialog(onDismiss: () -> Unit) {
    val groups = LogTagCatalog.allEntriesByModule
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
        title = {
            Column {
                Text("日志 tag 速查", style = MaterialTheme.typography.titleMedium)
                Text(
                    "出问题时按模块找对应 tag，配合顶部 tag 过滤 / 搜索使用",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
        },
        text = {
            // 用 LazyColumn 而不是 verticalScroll(rememberScrollState())，避免
            // 因为内容较长造成 dialog 一次性测量超大尺寸。
            LazyColumn(
                modifier = Modifier.heightIn(max = 480.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                groups.forEach { (module, entries) ->
                    item(key = "header-$module") {
                        Text(
                            module,
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.SemiBold,
                            ),
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    items(entries, key = { "row-$module-${it.first}" }) { (tag, entry) ->
                        Column(modifier = Modifier.padding(start = 8.dp, bottom = 2.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    tag,
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontFamily = FontFamily.Monospace,
                                    ),
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "→  ${entry.canonical}",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                    ),
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                )
                            }
                            Text(
                                entry.purpose,
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                            )
                            Text(
                                "看它：${entry.whenToCheck}",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                            )
                        }
                    }
                }
            }
        },
    )
}

// ── TopAppBar slots — 抽出来避免 Scaffold lambda 嵌套到 4 层。每块只关心
//    自己的 state，主屏幕只负责把回调连起来。

@Composable
private fun LogScreenTitle(selectionMode: Boolean, selectedCount: Int) {
    Text(
        if (selectionMode) "已选 $selectedCount 条" else "应用日志",
        style = if (selectionMode) MaterialTheme.typography.titleMedium
                else MaterialTheme.typography.titleLarge,
    )
}

@Composable
private fun LogScreenNavIcon(
    selectionMode: Boolean,
    onBack: () -> Unit,
    onExitSelection: () -> Unit,
) {
    // 多选时返回键退出多选而不是 pop screen — 配合系统返回键的预期手势更顺。
    IconButton(onClick = if (selectionMode) onExitSelection else onBack) {
        Icon(
            if (selectionMode) Icons.Default.Close else Icons.AutoMirrored.Filled.ArrowBack,
            if (selectionMode) "退出多选" else "返回",
        )
    }
}

@Composable
private fun SelectionModeActions(
    selectedRecords: List<LogRecord>,
    filteredLogs: List<LogRecord>,
    allSelected: Boolean,
    onCopy: () -> Unit,
    onToggleSelectAll: () -> Unit,
) {
    IconButton(onClick = onCopy, enabled = selectedRecords.isNotEmpty()) {
        Icon(
            Icons.Default.ContentCopy, "复制选中",
            tint = if (selectedRecords.isNotEmpty()) MaterialTheme.colorScheme.primary
                   else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
        )
    }
    TextButton(onClick = onToggleSelectAll, enabled = filteredLogs.isNotEmpty()) {
        Text(
            if (allSelected) "取消全选" else "全选",
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun NormalModeActions(
    tabIsLogs: Boolean,
    filteredLogs: List<LogRecord>,
    @Suppress("UNUSED_PARAMETER") tagFilter: String?,
    searchActive: Boolean,
    onToggleSearch: () -> Unit,
    onOpenTagCatalog: () -> Unit,
    onCopyAll: () -> Unit,
    onExportTxt: () -> Unit,
    onClear: () -> Unit,
) {
    // 顶栏按钮顺序：搜索 / tag 速查 / 复制全部 / 导出 TXT / 清空。
    // 多选按钮被移除——长按列表项即可进入多选（LogListTab onLongClick 处理）；
    // 顶栏按钮太多挤掉了 TopAppBar 标题，去掉常用度最低的入口。
    if (tabIsLogs) {
        IconButton(onClick = onToggleSearch) {
            Icon(
                if (searchActive) Icons.Default.Close else Icons.Default.Search,
                if (searchActive) "关闭搜索" else "搜索",
                tint = if (searchActive) MaterialTheme.colorScheme.primary
                       else LocalContentColor.current,
            )
        }
        // tag 速查：弹 dialog 列出已知 tag 的中文含义。比 chip 行更适合查询陌生 tag。
        IconButton(onClick = onOpenTagCatalog) {
            Icon(Icons.AutoMirrored.Filled.HelpOutline, "tag 速查")
        }
    }
    if (tabIsLogs && filteredLogs.isNotEmpty()) {
        // 复制全部当前过滤结果（配合上面的 tag chip 用 —— 选 PageTurnFlicker
        // 后点这个就能一键把整段诊断发我）。
        IconButton(onClick = onCopyAll) {
            Icon(Icons.Default.ContentCopy, "复制全部")
        }
        // 导出 TXT：把当前过滤后的日志 + 设备头（型号/分辨率/Android 版本/
        // 应用版本/堆/ABI 等）写到一个 .txt 文件，走系统分享面板让用户保存
        // 或发给开发者。复制粘贴在大体量日志（>1MB）下经常被剪贴板截断 / Toast
        // 假成功，文件分享路径绕过这个坑。
        IconButton(onClick = onExportTxt) {
            Icon(Icons.Default.SaveAlt, "导出 TXT")
        }
    }
    if (tabIsLogs) {
        IconButton(onClick = onClear) {
            Icon(Icons.Default.Delete, "清空")
        }
    }
}

/** Concatenate records with throwable appended. Shared by selection-mode
 *  copy-selected and normal-mode copy-all to keep formatting identical. */
private fun joinRecordsText(records: List<LogRecord>): String =
    records.joinToString("\n") { record ->
        buildString {
            append(record.format())
            if (record.throwable != null) {
                append('\n')
                append(record.throwable)
            }
        }
    }

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LogListTab(
    logs: List<LogRecord>,
    selected: LogRecord?,
    timeFmt: SimpleDateFormat,
    selectionMode: Boolean,
    selectedIds: Set<Long>,
    emptyMessage: String = "暂无日志",
    onItemClick: (LogRecord) -> Unit,
    onItemLongPress: (LogRecord) -> Unit,
) {
    if (logs.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(emptyMessage, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(logs.reversed(), key = { it.id }) { record ->
                val color = when (record.level) {
                    LogLevel.FATAL -> MaterialTheme.colorScheme.error
                    LogLevel.ERROR -> MaterialTheme.colorScheme.error
                    LogLevel.WARN -> MaterialTheme.colorScheme.tertiary
                    LogLevel.INFO -> MaterialTheme.colorScheme.primary
                    LogLevel.DEBUG -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    LogLevel.VERBOSE -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                }
                val isChecked = record.id in selectedIds
                val rowBg = when {
                    isChecked -> MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                    record.level.priority >= LogLevel.WARN.priority -> color.copy(alpha = 0.06f)
                    else -> Color.Transparent
                }
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .combinedClickable(
                            onClick = { onItemClick(record) },
                            onLongClick = { onItemLongPress(record) },
                        )
                        .background(rowBg, MaterialTheme.shapes.extraSmall)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (selectionMode) {
                        Checkbox(
                            checked = isChecked,
                            onCheckedChange = { onItemClick(record) },
                            modifier = Modifier.size(28.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(timeFmt.format(Date(record.time)),
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
                            Spacer(Modifier.width(6.dp))
                            Text(record.level.label,
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp), color = color)
                            Spacer(Modifier.width(6.dp))
                            Text(record.tag,
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                        }
                        Text(record.message,
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = if (selected == record) Int.MAX_VALUE else 2,
                            overflow = TextOverflow.Ellipsis)
                        if (selected == record && record.throwable != null) {
                            Spacer(Modifier.height(4.dp))
                            Text(record.throwable,
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp, fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.error.copy(alpha = 0.8f))
                        }
                        // 单条选中时副文显示 tag 中文用途：用户看到「TtsHost: speak error」
                        // 时不一定知道 TtsHost 是什么；这一行立刻告诉他「TTS/Host —
                        // TTS 引擎宿主」。未注册的 tag 不显示，避免视觉噪音。
                        if (selected == record) {
                            LogTagCatalog.describe(record.tag)?.let { hint ->
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "ⓘ $hint",
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CrashFilesTab(
    crashFiles: List<File>,
    selectedFile: File?,
    crashContent: String,
    dateFmt: SimpleDateFormat,
    onFileClick: (File) -> Unit,
) {
    if (crashFiles.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("无崩溃记录", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
                Spacer(Modifier.height(4.dp))
                Text("这是好事", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f))
            }
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(crashFiles, key = { it.name }) { file ->
                val isSelected = selectedFile == file
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.small,
                    colors = CardDefaults.cardColors(
                        containerColor = if (isSelected) MaterialTheme.colorScheme.errorContainer
                        else MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                ) {
                    Column(
                        modifier = Modifier.clickable { onFileClick(file) }
                            .padding(12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                file.name.removePrefix("crash_").removeSuffix(".txt"),
                                style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.error,
                                fontWeight = FontWeight.Medium,
                            )
                            Spacer(Modifier.weight(1f))
                            Text(
                                dateFmt.format(Date(file.lastModified())),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                            )
                        }
                        Text(
                            "${file.length() / 1024}KB",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                        )
                        if (isSelected && crashContent.isNotBlank()) {
                            Spacer(Modifier.height(8.dp))
                            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                            Spacer(Modifier.height(8.dp))
                            Text(
                                crashContent,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                                ),
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 把当前过滤后的日志导出为带设备头的 TXT，走系统分享面板交给用户。
 *
 * 文件格式：
 *   ```
 *   === MoRealm Log Export ===
 *   Exported: 2026-05-03 14:21:08
 *   Filter: tag=PageTurnFlicker (search: "speak")    // 没过滤就省略
 *   Entries: 312
 *
 *   --- Device ---
 *   Manufacturer: ...                                 // AppLog.deviceInfo 全量
 *   Resolution: 1080x2400 px
 *   Density: 3.0 (480 dpi)
 *   ...
 *
 *   --- Logs ---
 *   [HH:mm:ss.SSS] L/Tag [thread]: message
 *   ...
 *   ```
 *
 * 设计选择：
 *   - 文件而不是剪贴板：大体量日志（数千条）会突破剪贴板 Binder 单次事务上
 *     限，触发静默丢包；走 FileProvider + ACTION_SEND 没有这个问题。
 *   - 写入 cacheDir/log_export/：FileProvider 已配的可分享目录之一；OS 会按
 *     需清理，不需要主动 GC。
 *   - 失败时打 ERROR 日志 + Toast；不静默吞异常，否则用户不知道为什么没弹分享。
 */
private fun exportLogTxt(
    context: android.content.Context,
    records: List<LogRecord>,
    tagFilter: String?,
) {
    try {
        val cacheDir = File(context.cacheDir, "log_export").apply { mkdirs() }
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val txtFile = File(cacheDir, "MoRealm_log_$ts.txt")

        val expFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val content = buildString {
            appendLine("=== MoRealm Log Export ===")
            appendLine("Exported: ${expFmt.format(Date())}")
            if (tagFilter != null) appendLine("Filter: tag=$tagFilter")
            appendLine("Entries: ${records.size}")
            appendLine()
            appendLine("--- Device ---")
            // AppLog.getDeviceInfo() 已经以 key:value 平铺、末尾自带换行；
            // 直接 append（而不是 appendLine）避免多出一个空行。
            append(AppLog.getDeviceInfo())
            appendLine()
            appendLine("--- Logs ---")
            if (records.isEmpty()) appendLine("(empty)")
            else appendLine(joinRecordsText(records))
        }

        txtFile.writeText(content)

        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", txtFile,
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "MoRealm log $ts")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "导出日志 TXT"))
    } catch (e: Exception) {
        AppLog.error("LogExport", "Failed to export TXT", e)
        Toast.makeText(context, "导出失败：${e.message ?: "未知错误"}", Toast.LENGTH_SHORT).show()
    }
}

// ── 日志清理面板 ────────────────────────────────────────

/**
 * 折叠的「日志清理」面板：
 *   - 标题条（点击 toggle 展开）
 *   - 4 个 slider：内存条数 / 单文件 MB / 目录总 MB / 保留天数
 *   - 「立即清理」按钮 → 触发 [onCleanupNow]，调用方负责弹 SnackBar
 *
 * Slider 参数边界（与 AppLog.setLogLimits 内的 clamp 一致）：
 *   memoryEntries:    50…5000
 *   maxFileSizeBytes: 1…32 MB
 *   maxTotalDirBytes: 0 (off) | 10…2000 MB
 *   maxAgeDays:       1…30
 *
 * 设计选择：
 *   - 用普通 Slider 而不是 RangeSlider —— 单值输入更直观。
 *   - 不内置「保存」按钮：onValueChangeFinished 即写回 prefs，避免用户调
 *     完忘记保存。每次 finish 都触发一次 prefs.apply()，但 4 个 slider 的
 *     拍写频率人手最多每秒几次，远低于 SharedPreferences 的写阈值。
 *   - 目录总大小 0 表示「不限」——slider 最左端用 ∞ 字样提示，避免
 *     用户误以为 0 = 立刻删光。
 */
@Composable
private fun LogCleanupPanel(
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    limits: AppLog.LogLimits,
    onLimitsChanged: (AppLog.LogLimits) -> Unit,
    onCleanupNow: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        // 标题条 —— 整行可点击；右侧角标显示 expand 箭头方向。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggleExpanded)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.CleaningServices,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "日志清理",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                modifier = Modifier.weight(1f),
            )
            // 收起态额外显示一行简要状态；展开时省掉避免冗余。
            if (!expanded) {
                Text(
                    summarizeLimits(limits),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(4.dp))
            }
            Icon(
                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expanded) "收起" else "展开",
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(),
            exit = shrinkVertically(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                // 1) 内存最大条数 —— ring buffer 大小，影响日志查看器滚动长度。
                LimitSlider(
                    label = "内存最大条数",
                    valueText = "${limits.memoryEntries} 条",
                    value = limits.memoryEntries.toFloat(),
                    valueRange = 50f..5000f,
                    steps = 0,
                    onValueChange = {
                        onLimitsChanged(limits.copy(memoryEntries = it.toInt()))
                    },
                )

                // 2) 单文件大小 (MB) —— 触发 rotate 的阈值。
                val fileSizeMb = (limits.maxFileSizeBytes / 1024.0 / 1024.0).toFloat()
                LimitSlider(
                    label = "单个滚动文件大小",
                    valueText = "%.1f MB".format(fileSizeMb),
                    value = fileSizeMb,
                    valueRange = 1f..32f,
                    steps = 0,
                    onValueChange = { mb ->
                        val bytes = (mb * 1024 * 1024).toLong()
                        onLimitsChanged(limits.copy(maxFileSizeBytes = bytes))
                    },
                )

                // 3) 目录总大小上限 (MB) —— 0 = 不限。最左端 0 显示「∞」。
                val totalMb = (limits.maxTotalDirBytes / 1024.0 / 1024.0).toFloat()
                LimitSlider(
                    label = "日志目录总大小上限",
                    valueText = if (totalMb < 1f) "不限制" else "%.0f MB".format(totalMb),
                    // 用 0..2000 的范围；UI 在 <1MB 时显示「不限」，
                    // 实际写入时也走「<5MB → 0」的归零逻辑（见下）。
                    value = totalMb.coerceIn(0f, 2000f),
                    valueRange = 0f..2000f,
                    steps = 0,
                    onValueChange = { mb ->
                        // <5MB 视为「不限」——避免用户拖到 1~2MB 这种过于
                        // 激进的值导致每次写入都触发删除风暴。AppLog 内
                        // 的 clamp 也会兜底。
                        val bytes = if (mb < 5f) 0L else (mb * 1024 * 1024).toLong()
                        onLimitsChanged(limits.copy(maxTotalDirBytes = bytes))
                    },
                )

                // 4) 保留天数。
                LimitSlider(
                    label = "保留天数",
                    valueText = "${limits.maxAgeDays} 天",
                    value = limits.maxAgeDays.toFloat(),
                    valueRange = 1f..30f,
                    steps = 28,  // 离散刻度——天数没有 0.5 天
                    onValueChange = {
                        onLimitsChanged(limits.copy(maxAgeDays = it.toInt()))
                    },
                )

                Spacer(Modifier.height(4.dp))

                // 「立即清理」按钮——按当前限额跑一次同步 enforce。
                FilledTonalButton(
                    onClick = onCleanupNow,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                ) {
                    Icon(
                        Icons.Default.AutoFixHigh,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("按当前限额立即清理")
                }

                // 副文：解释自动清理的触发条件，帮用户建立心智模型。
                Text(
                    "自动清理：每写入 200 条日志或每 30 分钟触发一次（在日志写入线程里执行，闲时不耗资源）。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                    modifier = Modifier.padding(top = 2.dp, bottom = 6.dp),
                )
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
    }
}

/**
 * 单个限额 slider 行 —— 标题 + 当前值 + slider。封一层是为了让
 * [LogCleanupPanel] 主体保持「四块业务」结构，每块只调一次本组件。
 */
@Composable
private fun LimitSlider(
    label: String,
    valueText: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                modifier = Modifier.weight(1f),
            )
            Text(
                valueText,
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            modifier = Modifier.height(28.dp),
        )
    }
}

/** 折叠态摘要："500 条 · 4MB · 7d"。压缩成一行让用户一眼看到当前配置。 */
private fun summarizeLimits(limits: AppLog.LogLimits): String {
    val mb = limits.maxFileSizeBytes / 1024 / 1024
    val totalMb = limits.maxTotalDirBytes / 1024 / 1024
    val totalText = if (totalMb <= 0) "" else " · 总 ${totalMb}MB"
    return "${limits.memoryEntries} 条 · ${mb}MB$totalText · ${limits.maxAgeDays}d"
}

/** 把 [CleanupReport] 翻译成给用户看的中文 SnackBar 文案。零删除时
 *  也明确告知，避免用户怀疑按钮没生效。 */
private fun cleanupReportMessage(report: CleanupReport): String {
    if (report.totalDeleted == 0) return "已是当前限额，无需清理"
    val parts = buildList {
        if (report.deletedLogFiles > 0) add("${report.deletedLogFiles} 个日志文件")
        if (report.deletedCrashFiles > 0) add("${report.deletedCrashFiles} 个崩溃文件")
    }
    val freed = if (report.freedBytes >= 1024 * 1024)
        "%.1f MB".format(report.freedMb)
    else
        "${report.freedBytes / 1024} KB"
    return "已删 ${parts.joinToString("、")}，回收 $freed"
}
