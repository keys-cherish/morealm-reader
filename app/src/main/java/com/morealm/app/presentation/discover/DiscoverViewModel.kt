package com.morealm.app.presentation.discover

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.morealm.app.domain.entity.SearchBook
import com.morealm.app.domain.entity.BookSource
import com.morealm.app.domain.analyzeRule.AnalyzeUrl
import com.morealm.app.domain.analyzeRule.RuleData
import com.morealm.app.domain.repository.SourceRepository
import com.morealm.app.domain.webbook.WebBook
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.coroutineContext
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
    val wordCount: String? = null,
    val latestChapter: String? = null,
    val tocUrl: String? = null,
    val variable: String? = null,
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
    private var refreshJob: Job? = null
    private var refreshCycle = 0

    fun refresh() {
        refreshJob?.cancel()
        val cycle = refreshCycle++
        refreshJob = viewModelScope.launch(Dispatchers.IO) {
            val fallbackBooks = _state.value.books
            val visibleFallback = fallbackBooks.rotateFrom((cycle + 1) * RESULT_ROTATION_STEP)
            try {
                val sourceCount = sourceRepository.getEnabledSourceCount()
                val sourcePool = sourceRepository.getExploreSourcesLite(MAX_SOURCE_POOL)
                val sources = sourcePool
                    .rotateFrom(cycle * MAX_SOURCES)
                    .take(MAX_SOURCES)
                _state.value = _state.value.copy(
                    sourceCount = sourceCount,
                    books = visibleFallback,
                    isRefreshing = sources.isNotEmpty(),
                    message = when {
                        sourceCount == 0 && fallbackBooks.isNotEmpty() -> "还没有启用书源，正在显示上次内容"
                        sourceCount == 0 -> "还没有启用书源"
                        sources.isEmpty() && fallbackBooks.isNotEmpty() -> "书源没有发现规则，正在显示上次内容"
                        sources.isEmpty() -> "已启用的书源没有发现规则"
                        else -> null
                    },
                )
                if (sources.isEmpty()) return@launch

                val gate = Semaphore(MAX_CONCURRENCY)
                val batches = supervisorScope {
                    sources.mapIndexed { sourceIndex, lite ->
                        async {
                            gate.withPermit {
                                loadSourceBatch(
                                    sourceUrl = lite.bookSourceUrl,
                                    cycle = cycle,
                                    sourceIndex = sourceIndex,
                                )
                            }
                        }
                    }.awaitAll()
                }
                val freshBooks = batches
                    .flatten()
                    .distinctBy { "${it.sourceUrl}|${it.bookUrl}|${it.title}" }
                    .rotateFrom((cycle + 1) * RESULT_ROTATION_STEP)
                    .take(MAX_BOOKS)

                if (freshBooks.isNotEmpty()) {
                    writeCache(freshBooks)
                    _state.value = _state.value.copy(
                        books = freshBooks,
                        isRefreshing = false,
                        message = null,
                    )
                } else {
                    _state.value = _state.value.copy(
                        books = visibleFallback,
                        isRefreshing = false,
                        message = if (fallbackBooks.isEmpty()) {
                            "书源暂时没有返回内容"
                        } else {
                            "刷新未取得新内容，正在显示上次结果"
                        },
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                _state.value = _state.value.copy(
                    books = visibleFallback,
                    isRefreshing = false,
                    message = if (fallbackBooks.isEmpty()) {
                        "发现内容加载失败，请稍后刷新"
                    } else {
                        "加载失败，正在显示上次结果"
                    },
                )
            }
        }
    }

    /**
     * 每轮切换书源分类和分页；分页规则失效时回退第一页，避免刷新后整页变空。
     */
    private suspend fun loadSourceBatch(
        sourceUrl: String,
        cycle: Int,
        sourceIndex: Int,
    ): List<DiscoverBook> {
        return try {
            val source = sourceRepository.getByUrl(sourceUrl) ?: return emptyList()
            val urls = resolveExploreUrls(source)
                .rotateFrom(cycle + sourceIndex)
                .take(MAX_URLS_PER_SOURCE)
            if (urls.isEmpty()) return emptyList()
            val page = cycle % EXPLORE_PAGE_WINDOW + 1
            withTimeout(SOURCE_TIMEOUT_MS) {
                urls.flatMap { url ->
                    val preferred = loadExplorePage(source, url, page)
                    if (preferred.isNotEmpty() || page == 1) {
                        preferred
                    } else {
                        loadExplorePage(source, url, 1)
                    }
                }
            }.map(::toDiscoverBook)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private suspend fun loadExplorePage(
        source: BookSource,
        url: String,
        page: Int,
    ): List<SearchBook> = try {
        WebBook.exploreBookAwait(source, url, page)
    } catch (error: CancellationException) {
        throw error
    } catch (_: Throwable) {
        emptyList()
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
        wordCount = book.wordCount,
        latestChapter = book.latestChapterTitle,
        tocUrl = book.tocUrl.takeIf { it.isNotBlank() },
        variable = book.variable,
    )

    /**
     * Legado 书源的 exploreUrl 是“分类配置”，不保证是可直接请求的 URL：
     * 它可能是 JSON 数组、`标题::URL` 多行文本，或返回前两者的 @js/<js> 脚本。
     */
    private suspend fun resolveExploreUrls(source: BookSource): List<String> {
        val configured = source.exploreUrl?.trim().orEmpty()
        if (configured.isEmpty()) return emptyList()
        val resolved = if (configured.startsWith("@js:") || configured.startsWith("<js>")) {
            runCatching {
                AnalyzeUrl(
                    mUrl = configured,
                    page = 1,
                    baseUrl = source.bookSourceUrl,
                    source = source,
                    ruleData = RuleData(),
                    coroutineContext = coroutineContext,
                ).ruleUrl
            }.getOrDefault("")
        } else {
            configured
        }.trim()
        if (resolved.isEmpty()) return emptyList()

        parseExploreJson(resolved).takeIf { it.isNotEmpty() }?.let { return it }
        val titled = resolved.lineSequence().mapNotNull { line ->
            val trimmed = line.trim().trimEnd(',')
            val separator = trimmed.indexOf("::")
            if (separator < 0) return@mapNotNull null
            trimmed.substring(separator + 2).trim().takeIf { it.isNotEmpty() }
        }.toList()
        if (titled.isNotEmpty()) return titled.distinct()

        return listOf(resolved).filterNot { it.startsWith("[") || it.startsWith("{") }
    }

    private fun parseExploreJson(value: String): List<String> = runCatching {
        val root = json.parseToJsonElement(value)
        val entries = when (root) {
            is JsonArray -> root
            is JsonObject -> root["data"] as? JsonArray ?: JsonArray(emptyList())
            else -> JsonArray(emptyList())
        }
        entries.mapNotNull { element ->
            (element as? JsonObject)?.get("url")?.jsonPrimitive?.content
                ?.trim()?.takeIf(String::isNotEmpty)
        }.distinct()
    }.getOrDefault(emptyList())

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
        private const val MAX_SOURCE_POOL = 36
        private const val MAX_CONCURRENCY = 3
        private const val SOURCE_TIMEOUT_MS = 10_000L
        private const val MAX_URLS_PER_SOURCE = 2
        private const val EXPLORE_PAGE_WINDOW = 3
        private const val RESULT_ROTATION_STEP = 17
        private const val MAX_BOOKS = 120
    }
}

internal fun <T> List<T>.rotateFrom(offset: Int): List<T> {
    if (size < 2) return this
    val start = Math.floorMod(offset, size)
    if (start == 0) return this
    return drop(start) + take(start)
}
