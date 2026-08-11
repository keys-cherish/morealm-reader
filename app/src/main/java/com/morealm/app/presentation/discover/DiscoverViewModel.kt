package com.morealm.app.presentation.discover

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.morealm.app.core.log.AppLog
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
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
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
    // 缓存要等数据库返回当前启用书源后才能展示；否则书源刚被关闭时，旧推荐会在
    // 冷启动首帧短暂回流到发现页。
    private val _state = MutableStateFlow(DiscoverUiState())
    val state: StateFlow<DiscoverUiState> = _state.asStateFlow()
    private val _refreshResults = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    val refreshResults: SharedFlow<Int> = _refreshResults.asSharedFlow()
    private var refreshJob: Job? = null
    private var refreshCycle = 0
    private var hasStartedInitialRefresh = false
    @Volatile
    private var activeSourceUrls: Set<String> = emptySet()

    init {
        // 书源管理页的启用开关会即时影响发现页；只监听 URL 投影，避免每次切换都加载完整规则。
        viewModelScope.launch(Dispatchers.IO) {
            sourceRepository.observeEnabledSourceUrls().collectLatest { urls ->
                val enabled = urls.toSet()
                activeSourceUrls = enabled
                pruneCaches(enabled)
                val current = _state.value
                val filtered = current.books.filter(::isBookFromEnabledSource)
                _state.value = current.copy(
                    sourceCount = enabled.size,
                    books = filtered,
                    message = if (enabled.isEmpty()) NO_SOURCE_MESSAGE else current.message,
                )
            }
        }
    }

    /** 发现页首次创建时刷新一次；缓存 Tab 再次显示不会重复触发。 */
    fun refreshOnFirstDisplay() {
        if (hasStartedInitialRefresh) return
        hasStartedInitialRefresh = true
        refresh()
    }

    fun refresh() {
        refreshJob?.cancel()
        val cycle = refreshCycle++
        refreshJob = viewModelScope.launch(Dispatchers.IO) {
            var fallbackBooks = emptyList<DiscoverBook>()
            var visibleFallback = fallbackBooks
            try {
                val enabledSourceUrls = sourceRepository.getEnabledSourcesLite()
                    .mapTo(HashSet()) { it.bookSourceUrl }
                activeSourceUrls = enabledSourceUrls
                val sourceCount = enabledSourceUrls.size
                // 先按当前启用状态过滤持久化缓存，再允许它进入渐进展示；禁用某一书源后，
                // 该源的 visible/prefetch/legacy 缓存都不能作为本轮 fallback。
                fallbackBooks = promotePreloadedCache(enabledSourceUrls)
                visibleFallback = fallbackBooks.rotateFrom((cycle + 1) * RESULT_ROTATION_STEP)
                val sourcePool = sourceRepository.getExploreSourcesLite(MAX_SOURCE_POOL)
                if (sourceCount == 0) {
                    // 没有书源时旧缓存已失去上下文，保留它会导致下次冷启动短暂显示过期推荐。
                    clearCaches()
                    _state.value = _state.value.copy(
                        sourceCount = 0,
                        books = emptyList(),
                        isRefreshing = false,
                        message = NO_SOURCE_MESSAGE,
                    )
                    return@launch
                }
                _state.value = _state.value.copy(
                    sourceCount = sourceCount,
                    books = visibleFallback,
                    isRefreshing = sourcePool.isNotEmpty(),
                    message = when {
                        sourcePool.isEmpty() && fallbackBooks.isNotEmpty() -> "书源没有发现规则，正在显示上次内容"
                        sourcePool.isEmpty() -> "已启用的书源没有发现规则"
                        else -> null
                    },
                )
                if (sourcePool.isEmpty()) return@launch

                // 串行按批补位：首轮打 MAX_SOURCES 个源，凑不够 DISPLAY_BOOKS 就从池中
                // 取下一批继续，最多 MAX_BACKFILL_ROUNDS 轮。首轮够书时开销与旧实现一致
                // （补位只在缺书时发生）；旧实现打完一批就收工，一批全挂即整页空。
                val resultMutex = Mutex()
                val progressiveBooks = mutableListOf<DiscoverBook>()
                val collected = mutableListOf<DiscoverBook>()
                // 池先按 cycle 旋转一次，批次再顺序切片：同一次刷新不会重复请求刚失败的源
                // （若每批各自 rotateFrom，池长小于 2×MAX_SOURCES 时第二批会绕回打旧源）。
                // 旋转本身用 floorMod，故排在 MAX_SOURCES 之后的源在若干轮刷新内必然被取到。
                val rotatedPool = sourcePool.rotateFrom(cycle * MAX_SOURCES)
                var attemptedSources = 0
                var failedSources = 0
                var round = 0
                while (
                    round < MAX_BACKFILL_ROUNDS &&
                    collected.distinctBy(DiscoverBook::cacheKey).size < DISPLAY_BOOKS
                ) {
                    val batch = rotatedPool.drop(attemptedSources).take(MAX_SOURCES)
                    if (batch.isEmpty()) break
                    attemptedSources += batch.size
                    round++

                    val gate = Semaphore(MAX_CONCURRENCY)
                    val batchResults = supervisorScope {
                        batch.mapIndexed { sourceIndex, lite ->
                            async {
                                gate.withPermit {
                                    val outcome = loadSourceBatch(
                                        sourceUrl = lite.bookSourceUrl,
                                        cycle = cycle,
                                        sourceIndex = sourceIndex,
                                    )
                                    if (outcome.books.isNotEmpty()) {
                                        resultMutex.withLock {
                                            progressiveBooks += outcome.books
                                            val immediateBooks = (
                                                progressiveBooks.distinctBy(DiscoverBook::cacheKey) +
                                                    visibleFallback
                                                )
                                                .filter(::isBookFromEnabledSource)
                                                .distinctBy(DiscoverBook::cacheKey)
                                                .take(DISPLAY_BOOKS)
                                            _state.value = _state.value.copy(
                                                books = immediateBooks,
                                                isRefreshing = true,
                                                message = null,
                                            )
                                        }
                                    }
                                    outcome
                                }
                            }
                        }.awaitAll()
                    }
                    failedSources += batchResults.count { it.failed }
                    collected += batchResults.flatMap { it.books }
                }
                if (round > 1) {
                    AppLog.info(
                        LOG_TAG,
                        "backfill: $round rounds / $attemptedSources sources tried " +
                            "($failedSources failed), " +
                            "${collected.distinctBy(DiscoverBook::cacheKey).size} books collected",
                    )
                }
                // 全部尝试过的源都是「请求失败」→ 网络/书源不可用，重试有意义；
                // 否则是站点响应了但发现规则没匹配上，重试无用（得换源或改规则）。
                val allSourcesFailed = attemptedSources > 0 && failedSources == attemptedSources

                val freshBooks = collected
                    .filter(::isBookFromEnabledSource)
                    .distinctBy { "${it.sourceUrl}|${it.bookUrl}|${it.title}" }
                    .rotateFrom((cycle + 1) * RESULT_ROTATION_STEP)
                    .take(TOTAL_CACHE_BOOKS)

                if (freshBooks.isNotEmpty()) {
                    val displayBooks = freshBooks.take(DISPLAY_BOOKS)
                    val preloadedBooks = freshBooks
                        .drop(DISPLAY_BOOKS)
                        .take(PRELOAD_BOOKS)
                    writeCaches(displayBooks, preloadedBooks)
                    _state.value = _state.value.copy(
                        books = displayBooks,
                        isRefreshing = false,
                        message = null,
                    )
                    _refreshResults.tryEmit(displayBooks.size)
                } else {
                    _state.value = _state.value.copy(
                        books = visibleFallback,
                        isRefreshing = false,
                        message = when {
                            fallbackBooks.isNotEmpty() -> "刷新未取得新内容，正在显示上次结果"
                            allSourcesFailed ->
                                "已试 $attemptedSources 个书源都没能连上，请检查网络后重试"
                            else -> "书源都响应了但没解析出书，可能是发现规则已失效"
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
     * 单源抓取结果。区分「请求失败」与「响应了但零结果」——整页空时前者应提示网络/书源
     * 不可用（可重试），后者应提示发现规则没匹配上（重试无用，得换源或修规则）。
     */
    private data class SourceOutcome(
        val books: List<DiscoverBook>,
        val failed: Boolean,
    )

    /**
     * 每轮切换书源分类和分页；分页规则失效时回退第一页，避免刷新后整页变空。
     */
    private suspend fun loadSourceBatch(
        sourceUrl: String,
        cycle: Int,
        sourceIndex: Int,
    ): SourceOutcome {
        return try {
            val source = sourceRepository.getByUrl(sourceUrl)
                ?: return SourceOutcome(emptyList(), failed = true)
            if (!source.enabled || source.bookSourceUrl !in activeSourceUrls) {
                return SourceOutcome(emptyList(), failed = false)
            }
            val urls = resolveExploreUrls(source)
                .rotateFrom(cycle + sourceIndex)
                .take(MAX_URLS_PER_SOURCE)
            if (urls.isEmpty()) {
                AppLog.info(LOG_TAG, "source='${source.bookSourceName}' no usable exploreUrl")
                return SourceOutcome(emptyList(), failed = false)
            }
            val page = cycle % EXPLORE_PAGE_WINDOW + 1
            // 每次 HTTP 尝试各自计时（见 loadExplorePage）。此前是外层一个 withTimeout 包住
            // 「首选页 + 退回第 1 页」两次请求，二次请求实际几乎没有预算可用。
            var anyFailure = false
            val books = urls.flatMap { url ->
                val preferred = loadExplorePage(source, url, page)
                val result = if (preferred.books.isNotEmpty() || page == 1) {
                    preferred
                } else {
                    loadExplorePage(source, url, 1)
                }
                if (result.failed) anyFailure = true
                result.books
            }
            if (books.isEmpty() && !anyFailure) {
                // 请求没抛错但零结果 = 站点响应了、发现规则没匹配上。与「请求失败」区分记录：
                // 它决定整页空时的用户文案走「规则不匹配」而不是「书源都没响应」。
                AppLog.info(LOG_TAG, "source='${source.bookSourceName}' responded with 0 books (rule mismatch?)")
            }
            SourceOutcome(books.map(::toDiscoverBook), failed = books.isEmpty() && anyFailure)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            AppLog.warn(LOG_TAG, "source=$sourceUrl batch failed: ${error.javaClass.simpleName} ${error.message}")
            SourceOutcome(emptyList(), failed = true)
        }
    }

    /**
     * 单次发现页请求，带独立超时预算。
     *
     * 🔴 [TimeoutCancellationException] 是 [CancellationException] 的子类，必须在
     * 它之前捕获：否则单源超时会被 `throw error` 重新抛出、经 awaitAll 冒泡到
     * [refresh] 的 catch，整轮刷新被当成协程取消终止 —— isRefreshing 永远停在 true，
     * fallback 与失败文案都不会展示（用户侧＝无限转圈 + 整页空）。
     * 真正的协程取消（VM 清理 / 用户再次下拉）仍要原样抛出，故两个分支都保留。
     */
    private suspend fun loadExplorePage(
        source: BookSource,
        url: String,
        page: Int,
    ): PageOutcome = try {
        val books = withTimeout(SOURCE_TIMEOUT_MS) {
            WebBook.exploreBookAwait(source, url, page)
        }
        PageOutcome(books, failed = false)
    } catch (_: TimeoutCancellationException) {
        AppLog.warn(LOG_TAG, "source='${source.bookSourceName}' page=$page timeout after ${SOURCE_TIMEOUT_MS}ms")
        PageOutcome(emptyList(), failed = true)
    } catch (error: CancellationException) {
        throw error
    } catch (error: IOException) {
        AppLog.warn(LOG_TAG, "source='${source.bookSourceName}' page=$page io: ${error.message}")
        PageOutcome(emptyList(), failed = true)
    } catch (error: Throwable) {
        AppLog.warn(LOG_TAG, "source='${source.bookSourceName}' page=$page rule/parse: ${error.message}")
        PageOutcome(emptyList(), failed = true)
    }

    /** 单页抓取结果，[failed] 为真表示请求/解析出错而非站点返回了空列表。 */
    private data class PageOutcome(
        val books: List<SearchBook>,
        val failed: Boolean,
    )

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
     * 参照实现书源的 exploreUrl 是“分类配置”，不保证是可直接请求的 URL：
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

    /** 冷启动优先消费上轮预加载；没有预加载时退回上次展示与旧版单缓存。 */
    private fun promotePreloadedCache(enabledSourceUrls: Set<String>): List<DiscoverBook> {
        fun validForCurrentSources(book: DiscoverBook): Boolean =
            book.sourceUrl.isBlank() || book.sourceUrl in enabledSourceUrls

        val preloaded = readCache(PREFETCH_CACHE_KEY).filter(::validForCurrentSources)
        val previousVisible = readCache(VISIBLE_CACHE_KEY).ifEmpty {
            readCache(LEGACY_CACHE_KEY)
        }.filter(::validForCurrentSources)
        val promoted = preloaded.ifEmpty { previousVisible }.take(DISPLAY_BOOKS)
        if (promoted.isNotEmpty()) {
            preferences.edit()
                .putString(VISIBLE_CACHE_KEY, encodeBooks(promoted))
                .remove(PREFETCH_CACHE_KEY)
                .apply()
        } else {
            // 所有缓存都来自已关闭书源时，主动清空三份缓存，避免下次启动再次读到旧结果。
            clearCaches()
        }
        return promoted
    }

    private fun isBookFromEnabledSource(book: DiscoverBook): Boolean =
        book.sourceUrl.isBlank() || book.sourceUrl in activeSourceUrls

    private fun pruneCaches(enabledSourceUrls: Set<String>) {
        fun keep(book: DiscoverBook): Boolean =
            book.sourceUrl.isBlank() || book.sourceUrl in enabledSourceUrls
        val visible = readCache(VISIBLE_CACHE_KEY).filter(::keep)
        val preloaded = readCache(PREFETCH_CACHE_KEY).filter(::keep)
        val legacy = readCache(LEGACY_CACHE_KEY).filter(::keep)
        preferences.edit()
            .putString(VISIBLE_CACHE_KEY, encodeBooks(visible))
            .putString(PREFETCH_CACHE_KEY, encodeBooks(preloaded))
            .putString(LEGACY_CACHE_KEY, encodeBooks(legacy))
            .apply()
    }

    private fun readCache(key: String): List<DiscoverBook> = runCatching {
        json.decodeFromString(
            ListSerializer(DiscoverBook.serializer()),
            preferences.getString(key, "[]").orEmpty(),
        )
    }.getOrDefault(emptyList())

    private fun writeCaches(
        visibleBooks: List<DiscoverBook>,
        preloadedBooks: List<DiscoverBook>,
    ) {
        preferences.edit()
            .putString(VISIBLE_CACHE_KEY, encodeBooks(visibleBooks))
            .putString(PREFETCH_CACHE_KEY, encodeBooks(preloadedBooks))
            .remove(LEGACY_CACHE_KEY)
            .apply()
    }

    private fun clearCaches() {
        preferences.edit()
            .remove(VISIBLE_CACHE_KEY)
            .remove(PREFETCH_CACHE_KEY)
            .remove(LEGACY_CACHE_KEY)
            .apply()
    }

    private fun encodeBooks(books: List<DiscoverBook>): String =
        json.encodeToString(ListSerializer(DiscoverBook.serializer()), books)

    companion object {
        private const val LOG_TAG = "Discover"
        private const val MAX_SOURCES = 8
        /**
         * 源池上限。旧值 24 是硬截断：装 100 个源时永远只有 customOrder 前 24 位可能
         * 进发现页，其余永不轮到。放宽到 200 实际等于不截断，同时给超大源库留查询上限。
         */
        private const val MAX_SOURCE_POOL = 200
        /** 结果不足 [DISPLAY_BOOKS] 时最多再取几批源补位（含首轮），见 refresh()。 */
        private const val MAX_BACKFILL_ROUNDS = 3
        private const val MAX_CONCURRENCY = 6
        /**
         * 单次发现请求预算。必须 ≥ OkHttpUtils 的 connect 超时（15s），否则握手慢的源
         * 在连接尚未建立时就被判死 —— 旧值 6s 且还是「首选页 + 退回第 1 页」两次请求共享。
         */
        private const val SOURCE_TIMEOUT_MS = 15_000L
        private const val MAX_URLS_PER_SOURCE = 1
        private const val EXPLORE_PAGE_WINDOW = 3
        private const val RESULT_ROTATION_STEP = 17
        private const val DISPLAY_BOOKS = 72
        private const val PRELOAD_BOOKS = 48
        private const val TOTAL_CACHE_BOOKS = DISPLAY_BOOKS + PRELOAD_BOOKS
        private const val LEGACY_CACHE_KEY = "cache"
        private const val VISIBLE_CACHE_KEY = "visible_cache"
        private const val PREFETCH_CACHE_KEY = "prefetch_cache"
        private const val NO_SOURCE_MESSAGE = "还没有启用书源，请先在书源管理中添加书源"
    }
}

private fun DiscoverBook.cacheKey(): String = "$sourceUrl|$bookUrl|$title"

internal fun <T> List<T>.rotateFrom(offset: Int): List<T> {
    if (size < 2) return this
    val start = Math.floorMod(offset, size)
    if (start == 0) return this
    return drop(start) + take(start)
}
