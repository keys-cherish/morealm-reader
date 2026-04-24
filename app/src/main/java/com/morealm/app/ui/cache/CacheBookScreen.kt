package com.morealm.app.ui.cache

import android.widget.Toast
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
    viewModel: CacheBookViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val webBooks by viewModel.webBooks.collectAsStateWithLifecycle()
    val cacheStats by viewModel.cacheStats.collectAsStateWithLifecycle()
    val isDownloading by viewModel.isDownloading.collectAsStateWithLifecycle()
    val downloadProgress by viewModel.downloadProgress.collectAsStateWithLifecycle()

    // Refresh stats when download completes
    LaunchedEffect(isDownloading) {
        if (!isDownloading) viewModel.loadCacheStats()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("离线缓存", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                actions = {
                    if (isDownloading) {
                        IconButton(onClick = { viewModel.stopDownload() }) {
                            Icon(Icons.Default.Close, "停止下载")
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
            // Global download progress
            if (isDownloading) {
                val prog = downloadProgress
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    val done = prog.completed + prog.failed + prog.cached
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            "下载中 $done/${prog.total}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        if (prog.failed > 0) {
                            Text(
                                "失败 ${prog.failed}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { if (prog.total > 0) done.toFloat() / prog.total else 0f },
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
                        CacheBookItem(
                            book = book,
                            stat = stat,
                            isDownloading = isDownloading && downloadProgress.bookId == book.id,
                            downloadProgress = if (downloadProgress.bookId == book.id) downloadProgress else null,
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
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CacheBookItem(
    book: Book,
    stat: CacheBookViewModel.CacheStat?,
    isDownloading: Boolean,
    downloadProgress: CacheBookService.DownloadProgress?,
    onDownloadAll: () -> Unit,
    onDownloadFromCurrent: () -> Unit,
    onClearCache: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column {
            Row(
                modifier = Modifier
                    .clickable { expanded = !expanded }
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
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
                        val pct = if (stat.totalChapters > 0) stat.cachedChapters * 100 / stat.totalChapters else 0
                        Text(
                            "已缓存 ${stat.cachedChapters}/${stat.totalChapters} ($pct%)",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (pct >= 100) MaterialTheme.colorScheme.tertiary
                            else MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                        )
                    }
                }

                if (isDownloading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        null,
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                    )
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

            // Expanded actions
            if (expanded && !isDownloading) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = onDownloadAll,
                        modifier = Modifier.weight(1f),
                        shape = MaterialTheme.shapes.small,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Icon(Icons.Default.CloudDownload, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("全部缓存", style = MaterialTheme.typography.labelSmall)
                    }
                    OutlinedButton(
                        onClick = onDownloadFromCurrent,
                        modifier = Modifier.weight(1f),
                        shape = MaterialTheme.shapes.small,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Icon(Icons.Default.Download, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("从当前章", style = MaterialTheme.typography.labelSmall)
                    }
                    OutlinedButton(
                        onClick = onClearCache,
                        modifier = Modifier.weight(1f),
                        shape = MaterialTheme.shapes.small,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                    ) {
                        Icon(Icons.Default.DeleteOutline, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("清除", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}
