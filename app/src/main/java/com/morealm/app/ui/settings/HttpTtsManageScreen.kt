package com.morealm.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.morealm.app.domain.entity.HttpTts
import com.morealm.app.presentation.settings.HttpTtsManageViewModel

/**
 * HttpTts 自定义朗读源管理屏。
 *
 * 设计要点：
 * - 列表用 [LazyColumn]，每行 = 名称 + URL 缩略 + 启用 [Switch] + 编辑 / 删除按钮。
 *   不再做长按手势——按钮直观，避免与 ListenScreen 上某些手势冲突。
 * - 顶栏 actions：导入（剪贴板 / 粘贴对话框）、新建。
 * - 编辑 / 新建共用 [HttpTtsEditDialog]：传入空 [HttpTts] = 新建。
 * - 试听按钮在编辑对话框里——刚保存好的源能立即试一段固定文本（"这是一段朗读
 *   测试文本"），收到通知栏前缀变化即视为成功；失败由 [TtsEventBus] 的 Error
 *   事件经 TtsErrorSnackbar 全局展示。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HttpTtsManageScreen(
    onBack: () -> Unit = {},
    viewModel: HttpTtsManageViewModel = hiltViewModel(),
) {
    val sources by viewModel.sources.collectAsStateWithLifecycle()
    val snackbarHost = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.toast.collect { snackbarHost.showSnackbar(it) }
    }

    var editing by remember { mutableStateOf<HttpTts?>(null) }
    var showImport by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<HttpTts?>(null) }
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text("自定义朗读源") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { showImport = true }) {
                        Icon(Icons.Default.ContentPaste, contentDescription = "导入")
                    }
                    IconButton(onClick = { editing = HttpTts(name = "", url = "") }) {
                        Icon(Icons.Default.Add, contentDescription = "新建")
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SnackbarHost(snackbarHost) },
    ) { padding ->
        if (sources.isEmpty()) {
            EmptyState(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize(),
                onCreate = { editing = HttpTts(name = "", url = "") },
                onImport = { showImport = true },
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(sources, key = { it.id }) { tts ->
                    SourceRow(
                        tts = tts,
                        onToggle = { viewModel.toggleEnabled(tts) },
                        onEdit = { editing = tts },
                        onDelete = { pendingDelete = tts },
                    )
                }
            }
        }
    }

    editing?.let { current ->
        HttpTtsEditDialog(
            initial = current,
            onDismiss = {
                editing = null
                viewModel.endPreview()
            },
            onSave = { saved ->
                viewModel.upsert(saved)
                editing = null
                viewModel.endPreview()
            },
            onPreview = { snapshot -> viewModel.preview(snapshot) },
        )
    }

    if (showImport) {
        ImportHttpTtsDialog(
            onDismiss = { showImport = false },
            onImport = { json ->
                viewModel.importFromJson(json)
                showImport = false
            },
        )
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除朗读源") },
            text = { Text("确定要删除「${target.name.ifBlank { "未命名" }}」？") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(target)
                    pendingDelete = null
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun EmptyState(
    modifier: Modifier = Modifier,
    onCreate: () -> Unit,
    onImport: () -> Unit,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "还没有自定义朗读源",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "粘贴 Legado 兼容的 JSON 一键导入，或手动新建一个 URL 模板",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FilledTonalButton(onClick = onImport) {
                Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("粘贴导入")
            }
            FilledTonalButton(onClick = onCreate) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("手动新建")
            }
        }
    }
}

@Composable
private fun SourceRow(
    tts: HttpTts,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onEdit),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 14.dp, vertical = 12.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    tts.name.ifBlank { "未命名朗读源" },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    tts.url.ifBlank { "（未设置 URL）" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    fontFamily = FontFamily.Monospace,
                )
            }
            Spacer(Modifier.width(8.dp))
            Switch(checked = tts.enabled, onCheckedChange = { onToggle() })
            IconButton(onClick = onEdit) {
                Icon(Icons.Default.Edit, contentDescription = "编辑")
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "删除",
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/**
 * 编辑/新建 HttpTts 的对话框。所有字段都做了空白容忍：URL 是唯一硬必填项。
 *
 * @param initial 传入空 [HttpTts] = 新建；传入现有 = 编辑（id 保留以走 upsert 而非新插）
 */
@Composable
private fun HttpTtsEditDialog(
    initial: HttpTts,
    onDismiss: () -> Unit,
    onSave: (HttpTts) -> Unit,
    onPreview: (HttpTts) -> Unit,
) {
    var name by rememberSaveable(initial.id) { mutableStateOf(initial.name) }
    var url by rememberSaveable(initial.id) { mutableStateOf(initial.url) }
    var contentType by rememberSaveable(initial.id) { mutableStateOf(initial.contentType.orEmpty()) }
    var header by rememberSaveable(initial.id) { mutableStateOf(initial.header.orEmpty()) }
    var loginUrl by rememberSaveable(initial.id) { mutableStateOf(initial.loginUrl.orEmpty()) }
    var loginCheckJs by rememberSaveable(initial.id) {
        mutableStateOf(initial.loginCheckJs.orEmpty())
    }
    var concurrentRate by rememberSaveable(initial.id) {
        mutableStateOf(initial.concurrentRate.orEmpty())
    }

    fun snapshot(): HttpTts = initial.copy(
        name = name.trim(),
        url = url.trim(),
        contentType = contentType.takeIf { it.isNotBlank() },
        header = header.takeIf { it.isNotBlank() },
        loginUrl = loginUrl.takeIf { it.isNotBlank() },
        loginCheckJs = loginCheckJs.takeIf { it.isNotBlank() },
        concurrentRate = concurrentRate.takeIf { it.isNotBlank() },
        enabled = initial.enabled,
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.url.isBlank() && initial.name.isBlank()) "新建朗读源" else "编辑朗读源") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                LabeledTextField("名称", name, { name = it }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                LabeledTextField(
                    "URL（支持 {{speakText}} {{speakSpeed}} {{encode}}）",
                    url,
                    { url = it },
                    singleLine = false,
                    minLines = 2,
                )
                Spacer(Modifier.height(8.dp))
                LabeledTextField(
                    "Content-Type 校验（可选，正则）",
                    contentType,
                    { contentType = it },
                    singleLine = true,
                    placeholder = "audio/.*",
                )
                Spacer(Modifier.height(8.dp))
                LabeledTextField(
                    "请求头 JSON（可选）",
                    header,
                    { header = it },
                    singleLine = false,
                    minLines = 2,
                    fontMono = true,
                    placeholder = """{"Authorization":"Bearer xxx"}""",
                )
                Spacer(Modifier.height(8.dp))
                LabeledTextField(
                    "登录 URL（可选）",
                    loginUrl,
                    { loginUrl = it },
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                LabeledTextField(
                    "鉴权预检 JS（可选）",
                    loginCheckJs,
                    { loginCheckJs = it },
                    singleLine = false,
                    minLines = 2,
                    fontMono = true,
                )
                Spacer(Modifier.height(8.dp))
                LabeledTextField(
                    "并发限速（可选，如 1/1000）",
                    concurrentRate,
                    { concurrentRate = it },
                    singleLine = true,
                    placeholder = "1/1000",
                )

                Spacer(Modifier.height(12.dp))
                FilledTonalButton(
                    onClick = {
                        // 试听需要先保存（host 通过 dao.getById 拉配置），所以
                        // 这里点试听会同时触发 onSave 写盘 + 临时切引擎试听
                        val saved = snapshot()
                        if (saved.url.isBlank()) return@FilledTonalButton
                        onSave(saved)
                        onPreview(saved)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = url.isNotBlank(),
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("保存并试听")
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(snapshot()) },
                enabled = url.isNotBlank(),
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun LabeledTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    singleLine: Boolean,
    minLines: Int = 1,
    fontMono: Boolean = false,
    placeholder: String? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = singleLine,
        minLines = minLines,
        modifier = Modifier.fillMaxWidth(),
        placeholder = placeholder?.let { { Text(it, fontFamily = if (fontMono) FontFamily.Monospace else null) } },
        textStyle = if (fontMono) {
            MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
        } else MaterialTheme.typography.bodyMedium,
    )
}

/**
 * JSON 粘贴导入对话框。识别 Legado 的三种格式：单对象 / 数组 / `{"body":[...]}`。
 * 解析失败/字段缺失由 [HttpTtsManageViewModel.importFromJson] 通过 toast 反馈。
 */
@Composable
private fun ImportHttpTtsDialog(
    onDismiss: () -> Unit,
    onImport: (String) -> Unit,
) {
    var text by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("粘贴 JSON 导入") },
        text = {
            Column {
                Text(
                    "支持单对象、数组、{\"body\":[...]} 三种 Legado 格式",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 200.dp),
                    label = { Text("JSON 内容") },
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onImport(text) },
                enabled = text.isNotBlank(),
            ) { Text("导入") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
