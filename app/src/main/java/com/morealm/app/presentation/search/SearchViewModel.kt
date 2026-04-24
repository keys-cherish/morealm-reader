package com.morealm.app.presentation.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.morealm.app.domain.entity.Book
import com.morealm.app.domain.entity.BookFormat
import com.morealm.app.domain.entity.SearchBook
import com.morealm.app.domain.preference.AppPreferences
import com.morealm.app.domain.repository.BookRepository
import com.morealm.app.domain.repository.SearchRepository
import com.morealm.app.core.log.AppLog
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import kotlin.math.min

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
    private val searchRepo: SearchRepository,
    private val bookRepo: BookRepository,
    private val prefs: AppPreferences,
    private val cacheRepo: com.morealm.app.domain.repository.CacheRepository,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _localResults = MutableStateFlow<List<Book>>(emptyList())
    val localResults: StateFlow<List<Book>> = _localResults.asStateFlow()

    private val _results = MutableStateFlow<List<SearchResult>>(emptyList())
    val results: StateFlow<List<SearchResult>> = _results.asStateFlow()

    private val _sourceProgress = MutableStateFlow<List<SourceSearchProgress>>(emptyList())
    val sourceProgress: StateFlow<List<SourceSearchProgress>> = _sourceProgress.asStateFlow()

    private val _sourceCount = MutableStateFlow(0)
    val sourceCount: StateFlow<Int> = _sourceCount.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    private var searchJob: Job? = null
    private val threadCount = 4
    private val mergeMutex = Mutex()

    val disclaimerAccepted: StateFlow<Boolean> = prefs.disclaimerAccepted
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val count = searchRepo.getEnabledSources().size
            _sourceCount.value = count
        }
    }

    fun acceptDisclaimer() {
        viewModelScope.launch { prefs.setDisclaimerAccepted() }
    }

    fun cancelSearch() {
        searchJob?.cancel()
        searchJob = null
        _isSearching.value = false
    }

    fun search(keyword: String) {
        if (keyword.isBlank()) return
        searchJob?.cancel()
        _query.value = keyword
        _results.value = emptyList()
        _localResults.value = emptyList()
        _isSearching.value = true

        val threadIndex = AtomicInteger(1)
        val searchPool = Executors.newFixedThreadPool(
            min(threadCount, 16),
            ThreadFactory { runnable ->
                Thread(runnable, "SearchPool-${threadIndex.getAndIncrement()}").apply {
                    uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { thread, throwable ->
                        AppLog.logThreadException("Search", thread, throwable)
                    }
                }
            }
        ).asCoroutineDispatcher()

        searchJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val localBooks = searchRepo.searchLocalBooks(keyword)
                _localResults.value = localBooks
                AppLog.info("Search", "Local: ${localBooks.size} results for '$keyword'")

                val sources = searchRepo.getEnabledSources()
                if (sources.isEmpty()) {
                    _isSearching.value = false
                    return@launch
                }

                _sourceProgress.value = sources.map {
                    SourceSearchProgress(it.bookSourceUrl, it.bookSourceName, SourceStatus.WAITING)
                }
                AppLog.info("Search", "Searching '$keyword' across ${sources.size} sources")

                sources.map { source ->
                    launch(searchPool) {
                        updateSourceStatus(source.bookSourceUrl, SourceStatus.SEARCHING)
                        try {
                            val searchBooks = withTimeout(30000L) {
                                searchRepo.searchOnlineSource(source, keyword)
                            }
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
                            }.filter { result ->
                                // 过滤掉标题和作者都不包含关键词的结果
                                result.title.contains(keyword, ignoreCase = true) ||
                                    result.author.contains(keyword, ignoreCase = true)
                            }
                            if (mapped.isNotEmpty()) {
                                mergeResults(mapped, keyword)
                            }
                            updateSourceStatus(source.bookSourceUrl, SourceStatus.DONE)
                        } catch (e: Exception) {
                            updateSourceStatus(source.bookSourceUrl, SourceStatus.FAILED)
                            AppLog.warn("Search", "${source.bookSourceName} failed: ${e.message}", e)
                        }
                    }
                }.forEach { it.join() }

                _isSearching.value = false
                AppLog.info("Search", "Search complete: ${_results.value.size} online results")
            } finally {
                searchPool.close()
            }
        }
    }

    private suspend fun mergeResults(newItems: List<SearchResult>, keyword: String) {
        mergeMutex.withLock {
            val current = ArrayList(_results.value)
            for (item in newItems) {
                val existing = current.find {
                    (it.title == item.title && it.author == item.author) ||
                    (it.bookUrl.isNotBlank() && it.bookUrl == item.bookUrl)
                }
                if (existing != null) {
                    // Prefer the result with more info (cover, intro)
                    if (item.coverUrl != null && existing.coverUrl == null ||
                        item.intro.length > existing.intro.length) {
                        current[current.indexOf(existing)] = item
                    }
                } else {
                    current.add(item)
                }
            }
            val sorted = current.sortedWith(
                compareByDescending<SearchResult> {
                    it.title == keyword
                }.thenByDescending {
                    it.title.startsWith(keyword)
                }.thenByDescending {
                    it.title.contains(keyword)
                }.thenByDescending {
                    it.author == keyword
                }.thenByDescending {
                    it.coverUrl != null
                }.thenByDescending {
                    it.intro.length
                }
            )
            _results.value = sorted
        }
    }

    private fun updateSourceStatus(sourceUrl: String, status: SourceStatus) {
        _sourceProgress.value = _sourceProgress.value.map {
            if (it.sourceUrl == sourceUrl) it.copy(status = status) else it
        }
    }

    /**
     * Add an online search result to the local shelf as a web book.
     * If the book already exists (same bookUrl + sourceUrl), return its id.
     * Returns the bookId via callback for immediate navigation.
     */
    fun addToShelfAndRead(result: SearchResult, onBookReady: (String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val existing = bookRepo.findByBookUrl(result.bookUrl, result.sourceUrl)
                if (existing != null) {
                    AppLog.info("Search", "Already on shelf: ${result.title}")
                    withContext(Dispatchers.Main) { onBookReady(existing.id) }
                    return@launch
                }
                val bookId = java.util.UUID.randomUUID().toString()
                val book = Book(
                    id = bookId,
                    title = result.title,
                    author = result.author,
                    coverUrl = result.coverUrl,
                    sourceUrl = result.sourceUrl,
                    sourceId = result.sourceUrl,
                    bookUrl = result.bookUrl,
                    tocUrl = result.searchBook?.tocUrl?.takeIf { it.isNotBlank() },
                    origin = result.sourceUrl,
                    originName = result.sourceName,
                    description = result.intro.ifBlank { null },
                    format = BookFormat.WEB,
                    addedAt = System.currentTimeMillis(),
                )
                bookRepo.insert(book)
                AppLog.info("Search", "Added to shelf: ${result.title} from ${result.sourceName}")
                withContext(Dispatchers.Main) { onBookReady(bookId) }
            } catch (e: Exception) {
                AppLog.error("Search", "Failed to add to shelf: ${e.message}", e)
            }
        }
    }

    fun addToShelf(result: SearchResult) {
        addToShelfAndRead(result) { /* no-op navigation */ }
    }

    /**
     * Add to shelf and immediately start downloading all chapters.
     */
    fun addToShelfAndDownload(result: SearchResult) {
        addToShelfAndRead(result) { bookId ->
            cacheRepo.startDownload(bookId, result.sourceUrl)
        }
    }
}
