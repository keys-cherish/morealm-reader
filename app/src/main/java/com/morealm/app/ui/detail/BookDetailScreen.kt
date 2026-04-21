package com.morealm.app.ui.detail

import androidx.compose.foundation.background
import com.morealm.app.presentation.profile.BookDetailViewModel
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.morealm.app.domain.entity.BookFormat
import com.morealm.app.ui.theme.LocalMoRealmColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookDetailScreen(
    bookId: String,
    onBack: () -> Unit,
    onRead: () -> Unit,
    viewModel: BookDetailViewModel = hiltViewModel(),
) {
    val book by viewModel.book.collectAsState()
    val showSourcePicker by viewModel.showSourcePicker.collectAsState()
    val availableSources by viewModel.availableSources.collectAsState()
    val saving by viewModel.saving.collectAsState()
    val moColors = LocalMoRealmColors.current
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { showEditDialog = true }) {
                        Icon(Icons.Default.Edit, "编辑元数据",
                            tint = MaterialTheme.colorScheme.onBackground)
                    }
                    IconButton(onClick = { showDeleteConfirm = true }) {
                        Icon(Icons.Default.Delete, "删除",
                            tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        book?.let { b ->
            Column(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(16.dp))

                // Cover
                Box(
                    modifier = Modifier
                        .size(140.dp, 200.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(moColors.surfaceGlass),
                    contentAlignment = Alignment.Center,
                ) {
                    if (b.coverUrl != null) {
                        AsyncImage(
                            model = b.coverUrl,
                            contentDescription = b.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Icon(
                            Icons.AutoMirrored.Filled.MenuBook,
                            contentDescription = null,
                            tint = moColors.accent,
                            modifier = Modifier.size(48.dp),
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                Text(
                    b.title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                )

                if (b.author.isNotBlank()) {
                    Text(
                        b.author,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                    )
                }

                Spacer(Modifier.height(24.dp))

                // Stats row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    StatItem("章节", "${b.totalChapters}")
                    StatItem("进度", "${(b.readProgress * 100).toInt()}%")
                    StatItem("格式", b.format.name)
                }

                Spacer(Modifier.height(24.dp))

                Button(
                    onClick = onRead,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = moColors.accent,
                    ),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(
                        if (b.lastReadChapter > 0) "继续阅读" else "开始阅读",
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }

                // Source switch button (for online books)
                if (b.sourceId != null && availableSources.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { viewModel.showSourcePicker() },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Icon(Icons.Default.SwapHoriz, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("换源 (${b.originName ?: "未知"})")
                    }
                }

                b.description?.let { desc ->
                    Spacer(Modifier.height(24.dp))
                    Text(
                        "简介",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        desc,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                    )
                }

                Spacer(Modifier.height(32.dp))
            }
        }
    }

    // Delete confirmation dialog
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("删除书籍") },
            text = { Text("确定要从书架移除《${book?.title ?: ""}》吗？本地文件不会被删除。") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    viewModel.deleteBook()
                    onBack()
                }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("取消")
                }
            },
        )
    }

    // Source picker dialog
    if (showSourcePicker) {
        AlertDialog(
            onDismissRequest = { viewModel.hideSourcePicker() },
            title = { Text("选择书源") },
            text = {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.heightIn(max = 400.dp),
                ) {
                    items(availableSources, key = { it.bookSourceUrl }) { source ->
                        val isCurrent = source.bookSourceUrl == book?.sourceId
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.switchSource(source) },
                            shape = RoundedCornerShape(8.dp),
                            color = if (isCurrent) moColors.accent.copy(alpha = 0.12f)
                                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    source.bookSourceName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (isCurrent) moColors.accent
                                            else MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f),
                                )
                                if (isCurrent) {
                                    Text("当前", style = MaterialTheme.typography.labelSmall,
                                        color = moColors.accent)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.hideSourcePicker() }) {
                    Text("取消")
                }
            },
        )
    }

    // Metadata edit dialog
    if (showEditDialog) {
        book?.let { b ->
            var editTitle by remember { mutableStateOf(b.title) }
            var editAuthor by remember { mutableStateOf(b.author) }
            var editDesc by remember { mutableStateOf(b.description ?: "") }
            val isEpub = b.format == BookFormat.EPUB

            AlertDialog(
                onDismissRequest = { if (!saving) showEditDialog = false },
                title = { Text("编辑书籍信息") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            value = editTitle,
                            onValueChange = { editTitle = it },
                            label = { Text("书名") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = moColors.accent,
                                cursorColor = moColors.accent,
                            ),
                        )
                        OutlinedTextField(
                            value = editAuthor,
                            onValueChange = { editAuthor = it },
                            label = { Text("作者") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = moColors.accent,
                                cursorColor = moColors.accent,
                            ),
                        )
                        OutlinedTextField(
                            value = editDesc,
                            onValueChange = { editDesc = it },
                            label = { Text("简介") },
                            maxLines = 4,
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = moColors.accent,
                                cursorColor = moColors.accent,
                            ),
                        )
                        if (isEpub) {
                            Text(
                                "修改将写入 EPUB 文件，重新导入后仍保留",
                                style = MaterialTheme.typography.labelSmall,
                                color = moColors.accent.copy(alpha = 0.7f),
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            viewModel.updateMetadata(editTitle, editAuthor, editDesc)
                            showEditDialog = false
                        },
                        enabled = !saving,
                    ) {
                        if (saving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = moColors.accent,
                            )
                        } else {
                            Text("保存", color = moColors.accent)
                        }
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { showEditDialog = false },
                        enabled = !saving,
                    ) {
                        Text("取消")
                    }
                },
            )
        }
    }
}

@Composable
private fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
        )
    }
}
