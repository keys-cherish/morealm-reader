package com.morealm.app.presentation.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.morealm.app.domain.entity.Book
import com.morealm.app.domain.repository.BookRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * 首页最近阅读数据。
 * 书架的完整功能（分组/导入/批量）在独立的书架 tab。
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    bookRepository: BookRepository,
) : ViewModel() {

    /** 最近在读的第一本，用于响应通知和桌面快捷方式的「继续阅读」请求。 */
    val lastReadBook: StateFlow<Book?> = bookRepository.getLastReadBook()
        .stateIn(viewModelScope, SharingStarted.Lazily, null)

    /** 最近阅读列表（按 lastReadAt 降序，只显示首页一行 3 本；不含从未打开的书）。 */
    val recentBooks: StateFlow<List<Book>> = bookRepository.getAllBooks().map { books ->
        books.filter { it.lastReadAt > 0L }.sortedByDescending { it.lastReadAt }.take(3)
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
}
