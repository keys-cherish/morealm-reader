package com.morealm.app.ui.profile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.morealm.app.R
import com.morealm.app.ui.theme.LocalMoRealmColors
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
    val moColors = LocalMoRealmColors.current
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
    ) {
        Box(Modifier.fillMaxWidth()) {
            Image(
                painter = painterResource(
                    if (moColors.isNight) R.drawable.profile_card_bg_night
                    else R.drawable.profile_card_bg_day,
                ),
                contentDescription = null,
                modifier = Modifier.matchParentSize(),
                contentScale = ContentScale.Crop,
            )
            Column(Modifier.fillMaxWidth().padding(20.dp)) {
                Text("阅读统计", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReadingMetric("读过的书", "${readBooks.size}", Modifier.weight(1f)) { detail = "读过的书" }
                    ReadingMetric("总时长", compactReadingDuration(totalReadMs), Modifier.weight(1f)) { detail = "每日阅读时长" }
                    ReadingMetric("连续天数", "$streak", Modifier.weight(1f)) { detail = "每日阅读时长" }
                }
                Spacer(Modifier.height(18.dp))
                Text(
                    "今日已读 ${formatReadingDuration(todayReadMs)}",
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                )
                TextButton(onClick = onAnnualReport, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text("查看年度报告 →", style = MaterialTheme.typography.labelSmall)
                }
            }
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
    Column(
        modifier = modifier.clickable(onClick = onClick).heightIn(min = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(Modifier.fillMaxWidth().heightIn(min = 40.dp), contentAlignment = Alignment.Center) {
            BasicText(
                text = value,
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                ),
                maxLines = 1,
                autoSize = TextAutoSize.StepBased(minFontSize = 12.sp, maxFontSize = 22.sp),
            )
        }
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), textAlign = TextAlign.Center)
    }
}

private fun compactReadingDuration(ms: Long): String {
    val minutes = ms.coerceAtLeast(0L) / 60_000L
    val hours = minutes / 60L
    return if (hours == 0L) "${minutes}m" else "${hours}h ${minutes % 60L}m"
}
