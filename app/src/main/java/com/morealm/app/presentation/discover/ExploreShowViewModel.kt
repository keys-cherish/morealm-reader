package com.morealm.app.presentation.discover

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.morealm.app.core.log.AppLog
import com.morealm.app.domain.entity.BookSource
import com.morealm.app.domain.entity.SearchBook
import com.morealm.app.domain.repository.BookRepository
import com.morealm.app.domain.repository.SourceRepository
import com.morealm.app.domain.webbook.WebBook
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import javax.inject.Inject

/**
 * 发现分类书籍列表（对照参照实现 ExploreShowViewModel）。
 *
 * 无限滚动分页：UI 滚到底部调 [loadMore]，page 自增；单页结果为空或全部去重后
 * 无新增视为「没有更多」。失败保留已加载内容并暴露 [ExploreShowUiState.error]，
 * 点重试继续当前页。
 */
@HiltViewModel
class ExploreShowViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val sourceRepository: SourceRepository,
    private val bookRepository: BookRepository,
) : ViewModel() {

    data class ExploreShowUiState(
        val title: String = "",
        val sourceName: String = "",
        val books: List<SearchBook> = emptyList(),
        val isLoading: Boolean = false,
        val noMore: Boolean = false,
        val error: String? = null,
    )

    private val sourceUrl: String = savedStateHandle.get<String>("sourceUrl").orEmpty()
    private val exploreUrl: String = savedStateHandle.get<String>("exploreUrl").orEmpty()
    private val exploreTitle: String = savedStateHandle.get<String>("title").orEmpty()

    private val _state = MutableStateFlow(ExploreShowUiState(title = exploreTitle))
    val state: StateFlow<ExploreShowUiState> = _state.asStateFlow()

    /** 书架去重 key：`title-author` 与 bookUrl 双索引（对照参照实现 isInBookShelf）。 */
    private val _shelfKeys = MutableStateFlow<Set<String>>(emptySet())
    val shelfKeys: StateFlow<Set<String>> = _shelfKeys.asStateFlow()

    private var bookSource: BookSource? = null
    private var page = 1
    private val loadedBooks = linkedSetOf<SearchBook>()
    private val seenKeys = hashSetOf<String>()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            bookRepository.getAllBooks().collect { books ->
                _shelfKeys.value = buildSet {
                    books.forEach { book ->
                        add("${book.title}-${book.author}")
                        if (book.bookUrl.isNotBlank()) add(book.bookUrl)
                    }
                }
            }
        }
        loadMore()
    }

    fun isInBookshelf(book: SearchBook, keys: Set<String> = _shelfKeys.value): Boolean =
        "${book.name}-${book.author}" in keys || book.bookUrl in keys

    /** 加载下一页；已在加载或已到底时静默忽略（滚动回调会高频触发）。 */
    fun loadMore() {
        val current = _state.value
        if (current.isLoading || current.noMore) return
        if (sourceUrl.isBlank() || exploreUrl.isBlank()) {
            _state.value = current.copy(noMore = true, error = "发现地址为空")
            return
        }
        _state.value = current.copy(isLoading = true, error = null)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val source = bookSource
                    ?: sourceRepository.getByUrl(sourceUrl)?.also { bookSource = it }
                    ?: throw IllegalStateException("书源不存在，可能已被删除")
                if (_state.value.sourceName.isBlank()) {
                    _state.value = _state.value.copy(sourceName = source.bookSourceName)
                }
                val pageBooks = withTimeout(PAGE_TIMEOUT_MS) {
                    WebBook.exploreBookAwait(source, exploreUrl, page)
                }
                val fresh = pageBooks.filter { seenKeys.add("${it.bookUrl}|${it.name}|${it.author}") }
                loadedBooks.addAll(fresh)
                page++
                _state.value = _state.value.copy(
                    books = loadedBooks.toList(),
                    isLoading = false,
                    // 空页或全是重复条目 → 站点翻页到头了（很多站点对超范围页码返回第一页）。
                    noMore = fresh.isEmpty(),
                )
            } catch (e: TimeoutCancellationException) {
                onLoadError("请求超时（${PAGE_TIMEOUT_MS / 1000}s），请重试")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                AppLog.warn("ExploreShow", "page=$page failed: ${e.javaClass.simpleName} ${e.message}")
                onLoadError(e.message ?: "加载失败")
            }
        }
    }

    /** 失败后的重试：error 清空重新拉当前页。 */
    fun retry() {
        _state.value = _state.value.copy(error = null, noMore = false)
        loadMore()
    }

    private fun onLoadError(message: String) {
        _state.value = _state.value.copy(isLoading = false, error = message)
    }

    companion object {
        private const val PAGE_TIMEOUT_MS = 30_000L
    }
}
