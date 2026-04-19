package com.morealm.app.presentation.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.morealm.app.domain.db.BookDao
import com.morealm.app.domain.db.BookSourceDao
import com.morealm.app.domain.entity.Book
import com.morealm.app.domain.entity.BookSource
import com.morealm.app.domain.preference.AppPreferences
import com.morealm.app.core.log.AppLog
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class SearchResult(
    val title: String,
    val author: String = "",
    val coverUrl: String? = null,
    val bookUrl: String = "",
    val sourceName: String = "",
    val sourceId: String = "",
    val intro: String = "",
)

data class SourceSearchProgress(
    val sourceId: String,
    val sourceName: String,
    val status: SourceStatus,
)

enum class SourceStatus { WAITING, SEARCHING, DONE, FAILED }

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val sourceDao: BookSourceDao,
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
            // Local shelf search first (instant)
            val localBooks = bookDao.searchBooks("%$keyword%")
            _localResults.value = localBooks
            AppLog.info("Search", "Local: ${localBooks.size} results for '$keyword'")

            // Then online source search
            val sources = sourceDao.getEnabledSourcesList()
            if (sources.isEmpty()) {
                _isSearching.value = false
                return@launch
            }

            _sourceProgress.value = sources.map {
                SourceSearchProgress(it.id, it.name, SourceStatus.WAITING)
            }

            AppLog.info("Search", "Searching '$keyword' across ${sources.size} sources")

            sources.map { source ->
                launch {
                    updateSourceStatus(source.id, SourceStatus.SEARCHING)
                    try {
                        val results = searchSource(source, keyword)
                        if (results.isNotEmpty()) {
                            _results.value = _results.value + results
                        }
                        updateSourceStatus(source.id, SourceStatus.DONE)
                    } catch (e: Exception) {
                        updateSourceStatus(source.id, SourceStatus.FAILED)
                        AppLog.warn("Search", "${source.name} failed: ${e.message}")
                    }
                }
            }.forEach { it.join() }

            _isSearching.value = false
            AppLog.info("Search", "Search complete: ${_results.value.size} online results")
        }
    }

    private fun updateSourceStatus(sourceId: String, status: SourceStatus) {
        _sourceProgress.value = _sourceProgress.value.map {
            if (it.sourceId == sourceId) it.copy(status = status) else it
        }
    }

    private suspend fun searchSource(source: BookSource, keyword: String): List<SearchResult> {
        val ruleJson = try {
            kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                .decodeFromString<com.morealm.app.domain.source.LegadoBookSource>(source.ruleJson)
        } catch (_: Exception) { return emptyList() }

        val searchUrl = ruleJson.searchUrl ?: return emptyList()
        if (searchUrl.isBlank()) return emptyList()
        // Skip JS-based rules — requires a JS engine which we don't support yet
        if (searchUrl.contains("@js:") || searchUrl.contains("<js>")) return emptyList()

        val ruleSearch = ruleJson.ruleSearch ?: return emptyList()
        val bookListRule = ruleSearch.bookList ?: return emptyList()

        // Build URL
        val url = searchUrl
            .replace("{{key}}", java.net.URLEncoder.encode(keyword, "UTF-8"))
            .replace("{{page}}", "1")
            .replace("\n.*".toRegex(), "")
        val finalUrl = if (url.startsWith("http")) url
            else source.url.trimEnd('/') + "/" + url.trimStart('/')

        // Fetch content
        val response = withContext(Dispatchers.IO) {
            okhttp3.OkHttpClient.Builder().build()
                .newCall(okhttp3.Request.Builder().url(finalUrl)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36")
                    .build())
                .execute()
        }
        val body = response.body?.string() ?: return emptyList()

        // Parse with RuleEngine
        val engine = com.morealm.app.domain.rule.RuleEngine()
        engine.setContent(body, finalUrl)
        val elements = engine.getElements(bookListRule)
        if (elements.isEmpty()) return emptyList()

        return elements.mapNotNull { el ->
            try {
                val child = engine.createChild(el)
                val name = ruleSearch.name?.let { child.getString(it) }?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                val author = ruleSearch.author?.let { child.getString(it) } ?: ""
                val bookUrl = ruleSearch.bookUrl?.let { child.getString(it) } ?: ""
                val coverUrl = ruleSearch.coverUrl?.let { child.getString(it) }
                val intro = ruleSearch.intro?.let { child.getString(it) } ?: ""

                SearchResult(
                    title = name,
                    author = author,
                    coverUrl = coverUrl,
                    bookUrl = if (bookUrl.startsWith("http")) bookUrl
                        else source.url.trimEnd('/') + "/" + bookUrl.trimStart('/'),
                    sourceName = source.name,
                    sourceId = source.id,
                    intro = intro,
                )
            } catch (_: Exception) { null }
        }.take(20)
    }
}
