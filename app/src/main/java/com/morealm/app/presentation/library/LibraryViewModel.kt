package com.morealm.app.presentation.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.morealm.app.domain.entity.Book
import com.morealm.app.domain.repository.BookRepository
import com.morealm.app.util.PinyinInitials
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** 图书馆状态过滤维度。 */
enum class LibraryFilter(val label: String) {
    ALL("全部"),
    READING("在读"),
    UNSTARTED("未开始"),
}

/** 一个拼音字母分段：段头字母 + 该段书目。 */
data class LibrarySection(
    val letter: Char,
    val books: List<Book>,
)

data class LibraryUiState(
    val totalCount: Int = 0,
    val sections: List<LibrarySection> = emptyList(),
    val filter: LibraryFilter = LibraryFilter.ALL,
    val query: String = "",
    /** 各过滤维度的计数（chips 上的小数字）。 */
    val filterCounts: Map<LibraryFilter, Int> = emptyMap(),
)

/**
 * 图书馆页：全量藏书按拼音首字母分段浏览 + 首字母检索。
 *
 * 分组/检索都在内存对已加载列表做——书目上限场景由后续 FTS + 有界查询接管，
 * 当前先保证交互形态正确。排序与分组使用 [PinyinInitials] 单点实现。
 */
@HiltViewModel
class LibraryViewModel @Inject constructor(
    bookRepository: BookRepository,
) : ViewModel() {

    private val _filter = MutableStateFlow(LibraryFilter.ALL)
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    /** title → 首字母串缓存，避免每次输入全量重算 Collator 比较。 */
    private val initialsCache = HashMap<String, String>()

    private fun initialsOf(text: String): String =
        initialsCache.getOrPut(text) { PinyinInitials.initials(text) }

    val uiState: StateFlow<LibraryUiState> =
        combine(bookRepository.getAllBooks(), _filter, _query) { books, filter, query ->
            val counts = mapOf(
                LibraryFilter.ALL to books.size,
                LibraryFilter.READING to books.count { it.lastReadAt > 0L },
                LibraryFilter.UNSTARTED to books.count { it.lastReadAt == 0L },
            )
            val filtered = when (filter) {
                LibraryFilter.ALL -> books
                LibraryFilter.READING -> books.filter { it.lastReadAt > 0L }
                LibraryFilter.UNSTARTED -> books.filter { it.lastReadAt == 0L }
            }
            val q = query.trim()
            val searched = if (q.isEmpty()) filtered else {
                val qUpper = q.uppercase()
                filtered.filter { book ->
                    book.title.contains(q, ignoreCase = true) ||
                        book.author.contains(q, ignoreCase = true) ||
                        initialsOf(book.title).contains(qUpper)
                }
            }
            val grouped = searched.groupBy { PinyinInitials.groupOf(it.title) }
            val sections = PinyinInitials.GROUPS.mapNotNull { letter ->
                grouped[letter]
                    ?.sortedWith(compareBy({ initialsOf(it.title) }, { it.title }))
                    ?.let { LibrarySection(letter, it) }
            }
            LibraryUiState(
                totalCount = books.size,
                sections = sections,
                filter = filter,
                query = query,
                filterCounts = counts,
            )
        }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

    fun setFilter(filter: LibraryFilter) {
        _filter.value = filter
    }

    fun setQuery(query: String) {
        _query.value = query
    }
}
