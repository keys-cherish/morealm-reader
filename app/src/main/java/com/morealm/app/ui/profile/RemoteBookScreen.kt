package com.morealm.app.ui.profile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.morealm.app.domain.sync.RemoteBookFile
import com.morealm.app.presentation.profile.RemoteBookViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Cloud-bookshelf screen — lists every book file under
 * `<webDavDir>/books/` and lets the user one-tap import any of them
 * into the local shelf. Mirrors Legado's "我的WebDav书架" UX so users
 * coming from Legado see their books in the expected place.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteBookScreen(
    onBack: () -> Unit,
    viewModel: RemoteBookViewModel = hiltViewModel(),
) {
    val files by viewModel.files.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val downloading by viewModel.downloading.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("WebDav 书架", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refresh() }, enabled = !loading) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新")
                    }
                },
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            if (status.isNotEmpty()) {
                Text(
                    status,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (status.contains("失败")) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }

            if (loading && files.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (files.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "没有可导入的书籍\n请把 epub / txt 等放进 WebDav 的 books/ 目录",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    )
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(files, key = { it.remotePath }) { file ->
                        RemoteBookRow(
                            file = file,
                            isDownloading = file.name in downloading,
                            onClick = { viewModel.downloadAndImport(file) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RemoteBookRow(
    file: RemoteBookFile,
    isDownloading: Boolean,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        ),
        onClick = onClick,
        enabled = !isDownloading,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    file.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    formatRowMeta(file),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
            if (isDownloading) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            } else {
                Icon(
                    Icons.Default.CloudDownload,
                    contentDescription = "下载并导入",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/** "1.2 MB · 2026-04-20 14:33" — falls back gracefully when fields missing. */
private fun formatRowMeta(file: RemoteBookFile): String {
    val parts = mutableListOf<String>()
    if (file.size > 0) parts += formatSize(file.size)
    if (file.lastModifiedEpoch > 0) {
        parts += SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            .format(Date(file.lastModifiedEpoch))
    } else if (file.lastModified.isNotBlank()) {
        parts += file.lastModified
    }
    return parts.joinToString(" · ").ifEmpty { "—" }
}

private fun formatSize(bytes: Long): String = when {
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024 -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    else -> "%.2f GB".format(bytes / 1024.0 / 1024.0 / 1024.0)
}
