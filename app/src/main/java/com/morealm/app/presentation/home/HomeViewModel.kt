package com.morealm.app.presentation.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.morealm.app.domain.entity.Book
import com.morealm.app.domain.repository.BookRepository
import com.morealm.app.domain.repository.ReadStatsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * 首页：问候 + 继续阅读 + 最近阅读。
 * 书架的完整功能（分组/导入/批量）在独立的书架 tab（ShelfScreen 原样保留）。
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    bookRepository: BookRepository,
    readStatsRepository: ReadStatsRepository,
) : ViewModel() {

    /** 今日阅读毫秒数（与 ShelfViewModel.todayReadMs 同算法：本地时区 yyyy-MM-dd 命中）。 */
    val todayReadMs: StateFlow<Long> = readStatsRepository.getRecent(7).map { stats ->
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        stats.find { it.date == today }?.readDurationMs ?: 0L
    }.stateIn(viewModelScope, SharingStarted.Lazily, 0L)

    /** 最近在读的第一本（继续阅读大卡）。 */
    val lastReadBook: StateFlow<Book?> = bookRepository.getLastReadBook()
        .stateIn(viewModelScope, SharingStarted.Lazily, null)

    /** 最近阅读列表（按 lastReadAt 降序，最多 24 本；不含从未打开的书）。 */
    val recentBooks: StateFlow<List<Book>> = bookRepository.getAllBooks().map { books ->
        books.filter { it.lastReadAt > 0L }.sortedByDescending { it.lastReadAt }.take(24)
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
}
