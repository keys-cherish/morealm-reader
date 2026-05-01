package com.morealm.app.ui.source

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.morealm.app.core.log.AppLog
import com.morealm.app.domain.entity.BookSource
import com.morealm.app.domain.webbook.CheckSource
import com.morealm.app.presentation.source.BookSourceManageViewModel
import com.morealm.app.ui.theme.LocalMoRealmColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookSourceManageScreen(
    onBack: () -> Unit,
    viewModel: BookSourceManageViewModel = hiltViewModel(),
    loginViewModel: com.morealm.app.presentation.source.SourceLoginViewModel = hiltViewModel(),
) {
    val moColors = LocalMoRealmColors.current
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val sources by viewModel.sources.collectAsStateWithLifecycle()
    val isImporting by viewModel.isImporting.collectAsStateWithLifecycle()
    val importProgress by viewModel.importProgress.collectAsStateWithLifecycle()
    val importResult by viewModel.importResult.collectAsStateWithLifecycle()
    val isChecking by viewModel.isChecking.collectAsStateWithLifecycle()
    val checkProgress by viewModel.checkProgress.collectAsStateWithLifecycle()
    val checkTotal by viewModel.checkTotal.collectAsStateWithLifecycle()
    val checkResults by viewModel.checkResults.collectAsStateWithLifecycle()
    val loginUiState by loginViewModel.uiState.collectAsStateWithLifecycle()
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
                Toast.makeText(context, "读取文件失败: ${e.message}", Toast.LENGTH_SHORT).show()
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("书源管理", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                actions = {
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
                        // Paste from clipboard
                        val clip = clipboardManager.getText()?.text ?: ""
                        if (clip.isNotBlank()) {
                            viewModel.importFromJson(clip)
                        } else {
                            Toast.makeText(context, "剪贴板为空", Toast.LENGTH_SHORT).show()
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
                            Spacer(Modifier.height(8.dp))
                            Text("点击右上角 + 导入书源",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f))
                        }
                    }
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(filteredSources, key = { it.bookSourceUrl }) { source ->
                        val checkResult = checkResults[source.bookSourceUrl]
                        val isLoggedIn = remember(source) { loginViewModel.checkLoginStatus(source) }
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
                    OutlinedTextField(
                        value = importUrl,
                        onValueChange = { importUrl = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("URL 或 JSON") },
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
                fields = state.fields,
                onDismiss = { loginViewModel.dismissDialog() },
                onLogin = { fieldValues ->
                    loginViewModel.login(state.source, fieldValues)
                },
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
