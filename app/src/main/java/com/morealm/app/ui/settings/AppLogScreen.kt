package com.morealm.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.morealm.app.ui.theme.LocalMoRealmColors
import com.morealm.app.core.log.AppLog
import com.morealm.app.core.log.LogLevel
import com.morealm.app.core.log.LogRecord
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppLogScreen(onBack: () -> Unit) {
    val moColors = LocalMoRealmColors.current
    val logs by AppLog.logs.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf<LogRecord?>(null) }
    val timeFmt = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.US) }

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
                    IconButton(onClick = { AppLog.clear() }) {
                        Icon(Icons.Default.Delete, "清空")
                    }
                },
            )
        }
    ) { padding ->
        if (logs.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("暂无日志", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(logs.reversed(), key = { "${it.time}_${it.message.hashCode()}" }) { record ->
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
                            .clickable { selected = if (selected == record) null else record }
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
}
