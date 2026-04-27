package com.morealm.app.ui.settings

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.morealm.app.core.log.AppLog
import com.morealm.app.core.log.LogLevel
import com.morealm.app.core.log.LogRecord
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

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
    var recordLog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("应用日志") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                actions = {
                    // Export/share button
                    IconButton(onClick = {
                        shareLogZip(context)
                    }) {
                        Icon(Icons.Default.Share, "导出日志")
                    }
                    if (selectedTab == 0) {
                        IconButton(onClick = { AppLog.clear() }) {
                            Icon(Icons.Default.Delete, "清空")
                        }
                    }
                },
            )
        }
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

            when (selectedTab) {
                0 -> LogListTab(logs, selected, timeFmt) { record ->
                    selected = if (selected == record) null else record
                }
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
}

@Composable
private fun LogListTab(
    logs: List<LogRecord>,
    selected: LogRecord?,
    timeFmt: SimpleDateFormat,
    onSelect: (LogRecord) -> Unit,
) {
    if (logs.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("暂无日志", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
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
                Column(
                    modifier = Modifier.fillMaxWidth()
                        .clickable { onSelect(record) }
                        .background(
                            if (record.level.priority >= LogLevel.WARN.priority) color.copy(alpha = 0.06f) else Color.Transparent,
                            MaterialTheme.shapes.extraSmall,
                        )
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
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
 * Zip all log + crash files and share via system share sheet.
 */
private fun shareLogZip(context: android.content.Context) {
    try {
        val logDir = AppLog.getLogDir() ?: return
        val cacheDir = File(context.cacheDir, "log_export").apply { mkdirs() }
        val ts = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
        val zipFile = File(cacheDir, "MoRealm_logs_$ts.zip")

        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            logDir.listFiles()?.forEach { file ->
                if (file.isFile && file.length() > 0) {
                    zos.putNextEntry(ZipEntry(file.name))
                    file.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
            // Also include in-memory logs
            val memText = AppLog.getLogText()
            if (memText.isNotBlank()) {
                zos.putNextEntry(ZipEntry("memory_logs.txt"))
                zos.write(memText.toByteArray())
                zos.closeEntry()
            }
        }

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", zipFile)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "导出日志"))
    } catch (e: Exception) {
        AppLog.error("LogExport", "Failed to export logs", e)
    }
}
