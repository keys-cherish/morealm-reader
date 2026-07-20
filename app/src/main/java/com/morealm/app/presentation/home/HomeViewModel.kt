package com.morealm.app.presentation.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.morealm.app.domain.entity.Book
import com.morealm.app.domain.repository.BookRepository
import com.morealm.app.domain.repository.DailyQuote
import com.morealm.app.domain.repository.DailyQuoteRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 首页最近阅读数据。
 * 书架的完整功能（分组/导入/批量）在独立的书架 tab。
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    bookRepository: BookRepository,
    dailyQuoteRepository: DailyQuoteRepository,
) : ViewModel() {

    /** 最近在读的第一本，用于响应通知和桌面快捷方式的「继续阅读」请求。 */
    val lastReadBook: StateFlow<Book?> = bookRepository.getLastReadBook()
        .stateIn(viewModelScope, SharingStarted.Lazily, null)

    /** 所有实际打开过的书，含未加入书架的试读记录；最近访问排在最前。 */
    val readingHistory: StateFlow<List<Book>> = bookRepository.getReadingHistory()
        .map(::sortReadingHistoryByLru)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 首页保持固定 3 列，只消费 LRU 头部，避免再发起第二条数据库查询。 */
    val recentBooks: StateFlow<List<Book>> = readingHistory.map { it.take(3) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _dailyQuote = MutableStateFlow(DailyQuoteRepository.fallbackForDay())
    val dailyQuote: StateFlow<DailyQuote> = _dailyQuote.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            _dailyQuote.value = dailyQuoteRepository.getToday()
        }
    }
}

internal fun sortReadingHistoryByLru(books: List<Book>): List<Book> =
    books.asSequence()
        .filter { it.lastReadAt > 0L }
        .sortedWith(compareByDescending<Book> { it.lastReadAt }.thenByDescending { it.addedAt })
        .toList()
