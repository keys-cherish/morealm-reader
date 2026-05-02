package com.morealm.app.presentation.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.morealm.app.domain.entity.Book
import com.morealm.app.domain.entity.Bookmark
import com.morealm.app.domain.repository.BookRepository
import com.morealm.app.domain.repository.BookmarkRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 全局书签屏 ViewModel。
 *
 * 输入：
 *   - [BookmarkRepository.getAll]  全部书签 Flow（按 createdAt 倒序）
 *   - [BookRepository.getAllBooks] 全部书 Flow（用于 join 出书名 / 封面 / 删书时同步移除）
 *
 * 输出：
 *   - [items]      已 join + 已应用过滤的展示项
 *   - [filter]     当前时间过滤（全部/今日/本周/本月）
 *   - [groupByBook] 是否按书分组
 *
 * 实现要点：
 *   - 用 [combine] 组合两个 Flow：bookmarks × books → 一次出 BookmarkItem 列表，
 *     避免 UI 端做 N+1 查询。
 *   - 已删除的书：bookmark 仍能展示（书名 fallback "已删除的书"），但点击进不去；
 *     用户可主动删除 orphan 书签。
 */
@HiltViewModel
class BookmarksViewModel @Inject constructor(
    private val bookmarkRepo: BookmarkRepository,
    private val bookRepo: BookRepository,
) : ViewModel() {

    enum class TimeFilter { ALL, TODAY, WEEK, MONTH }

    data class BookmarkItem(
        val bookmark: Bookmark,
        val bookTitle: String,
        val bookAuthor: String,
        val coverUrl: String?,
        val bookExists: Boolean,
    )

    private val _filter = MutableStateFlow(TimeFilter.ALL)
    val filter: StateFlow<TimeFilter> = _filter

    private val _groupByBook = MutableStateFlow(false)
    val groupByBook: StateFlow<Boolean> = _groupByBook

    /** 已 join + 时间过滤的展示项。group 是 UI 层负责的渲染细节，这里只出平铺。 */
    val items: StateFlow<List<BookmarkItem>> = combine(
        bookmarkRepo.getAll(),
        bookRepo.getAllBooks(),
        _filter,
    ) { bookmarks, books, filter ->
        val byBookId = books.associateBy { it.id }
        val now = System.currentTimeMillis()
        val cutoff = when (filter) {
            TimeFilter.ALL -> 0L
            TimeFilter.TODAY -> now - 24L * 3600_000
            TimeFilter.WEEK -> now - 7L * 24 * 3600_000
            TimeFilter.MONTH -> now - 30L * 24 * 3600_000
        }
        bookmarks
            .asSequence()
            .filter { it.createdAt >= cutoff }
            .map { bm ->
                val book = byBookId[bm.bookId]
                BookmarkItem(
                    bookmark = bm,
                    bookTitle = book?.title ?: "已删除的书",
                    bookAuthor = book?.author ?: "",
                    coverUrl = book?.coverUrl,
                    bookExists = book != null,
                )
            }
            .toList()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun setFilter(f: TimeFilter) {
        _filter.value = f
    }

    fun toggleGroupByBook() {
        _groupByBook.value = !_groupByBook.value
    }

    fun deleteBookmark(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            bookmarkRepo.deleteById(id)
        }
    }
}
