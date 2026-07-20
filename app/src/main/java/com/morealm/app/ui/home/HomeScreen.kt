package com.morealm.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.morealm.app.presentation.home.HomeViewModel
import com.morealm.app.ui.library.LibraryBookItem
import com.morealm.app.ui.shelf.ContinueReadingCard
import kotlinx.coroutines.delay
import java.util.Calendar

private const val COLUMNS = 3

/**
 * 首页：问候 + 今日阅读时长 + 继续阅读大卡 + 最近阅读网格。
 * 书架完整功能在独立书架 tab；本页只做「回到上次的阅读」。
 * 书目卡复用图书馆的 [LibraryBookItem]，继续阅读卡复用书架的 [ContinueReadingCard]。
 */
@Composable
fun HomeScreen(
    onBookClick: (String) -> Unit,
    /** 外部「继续阅读」请求计数（通知/桌面快捷方式），递增即打开最近一本。 */
    continueReadingRequest: Int = 0,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val todayReadMs by viewModel.todayReadMs.collectAsStateWithLifecycle()
    val lastRead by viewModel.lastReadBook.collectAsStateWithLifecycle()
    val recent by viewModel.recentBooks.collectAsStateWithLifecycle()

    // 续读请求消费（原 ShelfScreen 逻辑迁来：书架 tab 非默认组合页后由首页承接）
    var handledContinueRequest by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(continueReadingRequest, lastRead) {
        val book = lastRead
        if (continueReadingRequest > 0 && continueReadingRequest != handledContinueRequest && book != null) {
            handledContinueRequest = continueReadingRequest
            delay(250)
            onBookClick(book.id)
        }
    }

    val greeting = remember {
        when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
            in 5..11 -> "早上好"
            in 12..13 -> "中午好"
            in 14..17 -> "下午好"
            in 18..22 -> "晚上好"
            else -> "深夜好"
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(COLUMNS),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 120.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "greeting", span = { GridItemSpan(maxLineSpan) }) {
                Column(modifier = Modifier.padding(top = 18.dp)) {
                    Text(
                        text = greeting,
                        fontWeight = FontWeight.Bold,
                        fontSize = 30.sp,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    val minutes = (todayReadMs / 60_000L).toInt()
                    if (minutes > 0) {
                        val h = minutes / 60
                        val m = minutes % 60
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = when {
                                h == 0 -> "今日已阅读 $m 分钟"
                                m == 0 -> "今日已阅读 $h 小时"
                                else -> "今日已阅读 $h 小时 $m 分钟"
                            },
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            lastRead?.let { book ->
                item(key = "continue", span = { GridItemSpan(maxLineSpan) }) {
                    ContinueReadingCard(
                        book = book,
                        onClick = { onBookClick(book.id) },
                    )
                }
            }
            if (recent.isNotEmpty()) {
                item(key = "recent_title", span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        text = "最近阅读",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.92f),
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                items(
                    count = recent.size,
                    key = { i -> "r_${recent[i].id}" },
                ) { i ->
                    val book = recent[i]
                    LibraryBookItem(book = book, onClick = { onBookClick(book.id) })
                }
            } else if (lastRead == null) {
                item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        text = "还没有阅读记录，去书架挑一本开始吧",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                    )
                }
            }
        }
    }
}
