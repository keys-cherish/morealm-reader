package com.morealm.app.ui.profile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.morealm.app.domain.entity.Book
import com.morealm.app.domain.entity.ReadStats
import com.morealm.app.presentation.profile.formatReadingDuration

@Composable
internal fun ReadingStatsCard(
    readBooks: List<Book>,
    completedBooks: List<Book>,
    dailyStats: List<ReadStats>,
    totalReadMs: Long,
    todayReadMs: Long,
    streak: Int,
    onOpenBook: (String) -> Unit,
    onAnnualReport: () -> Unit,
) {
    var detail by remember { mutableStateOf<String?>(null) }
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("阅读统计", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("今日阅读 ${formatReadingDuration(todayReadMs)}", color = MaterialTheme.colorScheme.primary)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ReadingMetric("读过", "${readBooks.size} 本", Modifier.weight(1f)) { detail = "读过的书" }
                ReadingMetric("读完", "${completedBooks.size} 本", Modifier.weight(1f)) { detail = "读完的书" }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ReadingMetric("累计阅读", formatReadingDuration(totalReadMs), Modifier.weight(1f)) { detail = "每日阅读时长" }
                ReadingMetric("连续阅读", "$streak 天", Modifier.weight(1f)) { detail = "每日阅读时长" }
            }
            TextButton(onClick = onAnnualReport, modifier = Modifier.align(Alignment.End)) { Text("查看年度报告") }
        }
    }
    detail?.let { title ->
        val books = if (title == "读完的书") completedBooks else readBooks
        AlertDialog(
            onDismissRequest = { detail = null },
            title = { Text(title) },
            text = {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                    if (title == "每日阅读时长") {
                        if (dailyStats.isEmpty()) item { Text("开始阅读后，这里会记录每天的时长") }
                        items(dailyStats, key = { it.date }) { day ->
                            ListItem(headlineContent = { Text(day.date) },
                                supportingContent = { Text(formatReadingDuration(day.readDurationMs)) })
                        }
                    } else {
                        if (books.isEmpty()) item { Text(if (title == "读完的书") "读完一本书后会显示在这里" else "还没有阅读记录") }
                        items(books, key = { it.id }) { book ->
                            ListItem(
                                headlineContent = { Text(book.title) },
                                supportingContent = {
                                    Text(if (book.readProgress >= 1f) "已读完" else "已读 ${(book.readProgress.coerceIn(0f, 1f) * 100).toInt()}%")
                                },
                                modifier = Modifier.clickable { detail = null; onOpenBook(book.id) },
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { detail = null }) { Text("关闭") } },
        )
    }
}

@Composable
private fun ReadingMetric(label: String, value: String, modifier: Modifier, onClick: () -> Unit) {
    Surface(modifier.clickable(onClick = onClick), shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(12.dp).heightIn(min = 60.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}
