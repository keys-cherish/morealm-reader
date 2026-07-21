package com.morealm.app.presentation.discover

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.morealm.app.domain.entity.SearchBook
import com.morealm.app.domain.repository.SourceRepository
import com.morealm.app.domain.webbook.WebBook
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import javax.inject.Inject

@Serializable
data class DiscoverBook(
    val title: String,
    val author: String = "",
    val coverUrl: String? = null,
    val bookUrl: String,
    val sourceUrl: String,
    val sourceName: String,
    val sourceType: Int = 0,
    val intro: String = "",
    val kind: String? = null,
    val latestChapter: String? = null,
)

data class DiscoverUiState(
    val sourceCount: Int = 0,
    val books: List<DiscoverBook> = emptyList(),
    val isRefreshing: Boolean = false,
    val message: String? = null,
)

@HiltViewModel
class DiscoverViewModel @Inject constructor(
    @ApplicationContext context: Context,
    private val sourceRepository: SourceRepository,
) : ViewModel() {
    private val preferences = context.getSharedPreferences("discover_books", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val _state = MutableStateFlow(
        DiscoverUiState(books = readCache())
    )
    val state: StateFlow<DiscoverUiState> = _state.asStateFlow()
    private val mergeMutex = Mutex()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            val sourceCount = sourceRepository.getEnabledSourceCount()
            val sources = sourceRepository.getExploreSourcesLite(MAX_SOURCES)
            _state.value = _state.value.copy(
                sourceCount = sourceCount,
                isRefreshing = sources.isNotEmpty(),
                message = when {
                    sourceCount == 0 -> "还没有启用书源"
                    sources.isEmpty() -> "已启用的书源没有发现规则"
                    else -> null
                },
            )
            if (sources.isEmpty()) return@launch

            val gate = Semaphore(MAX_CONCURRENCY)
            supervisorScope {
                sources.map { lite ->
                    async {
                        gate.withPermit {
                            val batch = runCatching {
                                val source = sourceRepository.getByUrl(lite.bookSourceUrl)
                                    ?: return@runCatching emptyList()
                                val url = source.exploreUrl?.takeIf(String::isNotBlank)
                                    ?: return@runCatching emptyList()
                                withTimeout(SOURCE_TIMEOUT_MS) {
                                    WebBook.exploreBookAwait(source, url, 1)
                                }.map(::toDiscoverBook)
                            }.getOrDefault(emptyList())
                            if (batch.isNotEmpty()) {
                                mergeMutex.withLock {
                                    val merged = (_state.value.books + batch)
                                        .distinctBy { "${it.sourceUrl}|${it.bookUrl}|${it.title}" }
                                        .take(MAX_BOOKS)
                                    _state.value = _state.value.copy(books = merged)
                                }
                            }
                        }
                    }
                }.awaitAll()
            }
            writeCache(_state.value.books)
            _state.value = _state.value.copy(isRefreshing = false)
        }
    }

    private fun toDiscoverBook(book: SearchBook) = DiscoverBook(
        title = book.name,
        author = book.author,
        coverUrl = book.coverUrl,
        bookUrl = book.bookUrl,
        sourceUrl = book.origin,
        sourceName = book.originName,
        sourceType = book.type,
        intro = book.intro.orEmpty(),
        kind = book.kind,
        latestChapter = book.latestChapterTitle,
    )

    private fun readCache(): List<DiscoverBook> = runCatching {
        json.decodeFromString(
            ListSerializer(DiscoverBook.serializer()),
            preferences.getString("cache", "[]").orEmpty(),
        )
    }.getOrDefault(emptyList())

    private fun writeCache(books: List<DiscoverBook>) {
        val value = json.encodeToString(ListSerializer(DiscoverBook.serializer()), books)
        preferences.edit().putString("cache", value).apply()
    }

    companion object {
        private const val MAX_SOURCES = 12
        private const val MAX_CONCURRENCY = 3
        private const val SOURCE_TIMEOUT_MS = 6_000L
        private const val MAX_BOOKS = 120
    }
}
