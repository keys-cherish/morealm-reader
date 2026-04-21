package com.morealm.app.ui.source

import android.widget.Toast
import com.morealm.app.presentation.source.BookSourceManageViewModel
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
import com.morealm.app.domain.entity.BookSource
import com.morealm.app.ui.theme.LocalMoRealmColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookSourceManageScreen(
    onBack: () -> Unit,
    viewModel: BookSourceManageViewModel = hiltViewModel(),
) {
    val moColors = LocalMoRealmColors.current
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val sources by viewModel.sources.collectAsState()
    val isImporting by viewModel.isImporting.collectAsState()
    val importResult by viewModel.importResult.collectAsState()
    var showImportDialog by remember { mutableStateOf(false) }
    var importUrl by remember { mutableStateOf("") }
    var searchQuery by remember { mutableStateOf("") }

    // PLACEHOLDER_SCAFFOLD

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
                shape = RoundedCornerShape(8.dp),
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
                shape = RoundedCornerShape(12.dp),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = moColors.accent,
                    cursorColor = moColors.accent),
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
                    color = moColors.accent.copy(alpha = 0.7f))
            }

            if (isImporting) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    color = moColors.accent)
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
                        SourceItem(
                            source = source,
                            onToggle = { viewModel.toggleSource(source) },
                            onDelete = { viewModel.deleteSource(source) },
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
                        shape = RoundedCornerShape(8.dp),
                        minLines = 3,
                        maxLines = 6,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = moColors.accent,
                            cursorColor = moColors.accent),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showImportDialog = false
                    if (importUrl.isNotBlank()) {
                        viewModel.importFromUrl(importUrl)
                        importUrl = ""
                    }
                }) { Text("导入", color = moColors.accent) }
            },
            dismissButton = {
                TextButton(onClick = { showImportDialog = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun SourceItem(
    source: BookSource,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
) {
    val moColors = LocalMoRealmColors.current
    var showMenu by remember { mutableStateOf(false) }
    val bgColor by animateColorAsState(
        if (source.enabled) moColors.surfaceGlass
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        label = "sourceBg"
    )

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = bgColor,
    ) {
        Row(
            modifier = Modifier
                .clickable { showMenu = !showMenu }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    source.bookSourceName.ifBlank { source.bookSourceUrl },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = if (source.enabled) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row {
                    if (!source.bookSourceGroup.isNullOrBlank()) {
                        Text(
                            source.bookSourceGroup!!,
                            style = MaterialTheme.typography.labelSmall,
                            color = moColors.accent.copy(alpha = 0.6f),
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
            }
            Switch(
                checked = source.enabled,
                onCheckedChange = { onToggle() },
                colors = SwitchDefaults.colors(checkedTrackColor = moColors.accent),
                modifier = Modifier.padding(start = 8.dp),
            )
            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Delete, "删除",
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                    modifier = Modifier.size(18.dp))
            }
        }
    }
}
