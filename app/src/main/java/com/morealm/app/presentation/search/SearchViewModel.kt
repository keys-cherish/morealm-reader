package com.morealm.app.presentation.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.morealm.app.domain.db.BookDao
import com.morealm.app.domain.entity.Book
import com.morealm.app.domain.entity.BookSource
import com.morealm.app.domain.entity.SearchBook
import com.morealm.app.domain.preference.AppPreferences
import com.morealm.app.domain.repository.SourceRepository
import com.morealm.app.domain.webbook.WebBook
import com.morealm.app.core.log.AppLog
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SearchResult(
    val title: String,
    val author: String = "",
    val coverUrl: String? = null,
    val bookUrl: String = "",
    val sourceName: String = "",
    val sourceUrl: String = "",
    val intro: String = "",
    val searchBook: SearchBook? = null,
)

data class SourceSearchProgress(
    val sourceUrl: String,
    val sourceName: String,
    val status: SourceStatus,
)

enum class SourceStatus { WAITING, SEARCHING, DONE, FAILED }

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val sourceRepo: SourceRepository,
    private val bookDao: BookDao,
    private val prefs: AppPreferences,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _localResults = MutableStateFlow<List<Book>>(emptyList())
    val localResults: StateFlow<List<Book>> = _localResults.asStateFlow()

    private val _results = MutableStateFlow<List<SearchResult>>(emptyList())
    val results: StateFlow<List<SearchResult>> = _results.asStateFlow()

    private val _sourceProgress = MutableStateFlow<List<SourceSearchProgress>>(emptyList())
    val sourceProgress: StateFlow<List<SourceSearchProgress>> = _sourceProgress.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    val disclaimerAccepted: StateFlow<Boolean> = prefs.disclaimerAccepted
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    fun acceptDisclaimer() {
        viewModelScope.launch { prefs.setDisclaimerAccepted() }
    }

    fun search(keyword: String) {
        if (keyword.isBlank()) return
        _query.value = keyword
        _results.value = emptyList()
        _localResults.value = emptyList()
        _isSearching.value = true

        viewModelScope.launch(Dispatchers.IO) {
            val localBooks = bookDao.searchBooks("%$keyword%")
            _localResults.value = localBooks
            AppLog.info("Search", "Local: ${localBooks.size} results for '$keyword'")

            val sources = sourceRepo.getEnabledSourcesList()
            if (sources.isEmpty()) {
                _isSearching.value = false
                return@launch
            }

            _sourceProgress.value = sources.map {
                SourceSearchProgress(it.bookSourceUrl, it.bookSourceName, SourceStatus.WAITING)
            }
            AppLog.info("Search", "Searching '$keyword' across ${sources.size} sources")

            sources.map { source ->
                launch {
                    updateSourceStatus(source.bookSourceUrl, SourceStatus.SEARCHING)
                    try {
                        val searchBooks = WebBook.searchBookAwait(source, keyword)
                        val mapped = searchBooks.map { sb ->
                            SearchResult(
                                title = sb.name,
                                author = sb.author,
                                coverUrl = sb.coverUrl,
                                bookUrl = sb.bookUrl,
                                sourceName = sb.originName,
                                sourceUrl = sb.origin,
                                intro = sb.intro ?: "",
                                searchBook = sb,
                            )
                        }
                        if (mapped.isNotEmpty()) {
                            _results.value = _results.value + mapped
                        }
                        updateSourceStatus(source.bookSourceUrl, SourceStatus.DONE)
                    } catch (e: Exception) {
                        updateSourceStatus(source.bookSourceUrl, SourceStatus.FAILED)
                        AppLog.warn("Search", "${source.bookSourceName} failed: ${e.message}")
                    }
                }
            }.forEach { it.join() }

            _isSearching.value = false
            AppLog.info("Search", "Search complete: ${_results.value.size} online results")
        }
    }

    private fun updateSourceStatus(sourceUrl: String, status: SourceStatus) {
        _sourceProgress.value = _sourceProgress.value.map {
            if (it.sourceUrl == sourceUrl) it.copy(status = status) else it
        }
    }
}
