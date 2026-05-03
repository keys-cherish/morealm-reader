package com.morealm.app.presentation.search

import android.os.Build
import android.text.Html
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.morealm.app.domain.entity.Book
import com.morealm.app.domain.entity.BookSource
import com.morealm.app.domain.entity.BookFormat
import com.morealm.app.domain.entity.SearchBook
import com.morealm.app.domain.preference.AppPreferences
import com.morealm.app.domain.repository.AutoGroupClassifier
import com.morealm.app.domain.repository.BookRepository
import com.morealm.app.domain.repository.SearchRepository
import com.morealm.app.core.log.AppLog
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import java.io.IOException

private const val TEXT_BOOK_SOURCE_TYPE = 0
// SOURCE_SEARCH_TIMEOUT_MS / SEARCH_PARALLELISM 已迁移到 AppPreferences —
// 用户可在「设置 → 搜索设置」里调。搜索入口处通过 prefs.getSearchParallelism()
// 一次性快照，搜索期间不响应配置变更。

data class SearchResult(
    val title: String,
    val author: String = "",
    val coverUrl: String? = null,
    val bookUrl: String = "",
    val sourceName: String = "",
    val sourceUrl: String = "",
    val sourceType: Int = TEXT_BOOK_SOURCE_TYPE,
    val intro: String = "",
    val kind: String? = null,
    val wordCount: String? = null,
    val latestChapter: String? = null,
    val searchBook: SearchBook? = null,
)

data class SourceSearchProgress(
    val sourceUrl: String,
    val sourceName: String,
    val status: SourceStatus,
    val errorMessage: String? = null,
)

enum class SourceStatus { WAITING, SEARCHING, DONE, FAILED }

/**
 * 「加入书架」事件 — 由 [SearchViewModel.addToShelfAndRead] /
 * [SearchViewModel.addToShelfAndDownload] 在 [bookRepo] 写入成功后发射；
 * UI 端订阅后弹 Snackbar「已加入书架·《title》[撤销]」。
 *
 * 设计点：
 *  - SharedFlow.replay = 1 让用户从阅读器返回 SearchScreen 时能补看上一次反馈；
 *    UI 用 [timestamp] 做 60s 过滤，避免 replay 出"半小时前的"陈旧 Snackbar。
 *  - 已经存在的书重复点击不 emit —— Snackbar 只在「真正新加」时弹。
 *  - [withDownload] 区分文案 / 撤销时是否同步 stopDownload。
 */
data class ShelfAddedEvent(
    val bookId: String,
    val title: String,
    val withDownload: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
)

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val searchRepo: SearchRepository,
    private val bookRepo: BookRepository,
    private val prefs: AppPreferences,
    private val cacheRepo: com.morealm.app.domain.repository.CacheRepository,
    private val autoGroupClassifier: AutoGroupClassifier,
    private val searchHistoryRepo: com.morealm.app.domain.repository.SearchKeywordRepository,
    /**
     * 加书后立刻拉 toc：保证 totalChapters 第一时间填上，免得用户看到一本"0 章"的书。
     * 进入书详情/阅读页时也不再被迫先发一次同步请求。
     */
    private val refreshController: com.morealm.app.presentation.shelf.ShelfRefreshController,
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

    /**
     * 加书事件流 —— UI 订阅后用 Snackbar 反馈。replay=1 让用户跳到阅读器再返回时
     * 仍能看到（UI 自己用 timestamp 过滤陈旧事件）。详见 [ShelfAddedEvent]。
     */
    private val _shelfAddedEvents = MutableSharedFlow<ShelfAddedEvent>(
        replay = 1,
        extraBufferCapacity = 0,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val shelfAddedEvents: SharedFlow<ShelfAddedEvent> = _shelfAddedEvents.asSharedFlow()

    private var searchJob: Job? = null
    private val searchGeneration = AtomicInteger(0)
    private val mergeMutex = Mutex()

    val disclaimerAccepted: StateFlow<Boolean> = prefs.disclaimerAccepted
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    /**
     * 搜索历史 Flow —— 由 [com.morealm.app.domain.repository.SearchKeywordRepository] 暴露，
     * UI 端在输入框聚焦且 query 为空时下拉显示。Lazily 启动让没人观察时不维持订阅，
     * 减少冷启动 IO；首次 collect 时才会拉一次 DB。
     */
    val searchHistory: StateFlow<List<com.morealm.app.domain.entity.SearchKeyword>> =
        searchHistoryRepo.observeAll()
            .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    /** 删除单条历史；UI 长按 / 滑动删除调它。 */
    fun deleteHistory(word: String) {
        viewModelScope.launch(Dispatchers.IO) { searchHistoryRepo.delete(word) }
    }

    /** 清空所有历史；UI 二次确认后调用。 */
    fun clearHistory() {
        viewModelScope.launch(Dispatchers.IO) { searchHistoryRepo.clear() }
    }

    /**
     * 撤销「清空历史」：UI 在清空前 snapshot 列表，用户点 Snackbar 的「撤销」时
     * 调本方法重新写入。Repo 层走 upsert 保留原计数和时间戳。
     */
    fun restoreHistory(keywords: List<com.morealm.app.domain.entity.SearchKeyword>) {
        if (keywords.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) { searchHistoryRepo.restoreAll(keywords) }
    }

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val count = searchRepo.getEnabledSources()
                .count { it.bookSourceType == TEXT_BOOK_SOURCE_TYPE }
            _sourceCount.value = count
        }
    }

    fun acceptDisclaimer() {
        viewModelScope.launch { prefs.setDisclaimerAccepted() }
    }

    fun cancelSearch() {
        searchGeneration.incrementAndGet()
        searchJob?.cancel()
        searchJob = null
        markUnfinishedSourcesFailed("搜索已取消")
        _isSearching.value = false
    }

    fun search(keyword: String) {
        if (keyword.isBlank()) return
        // 历史记录入库 —— 与搜索流程并行，不阻塞用户操作；写库失败不会冒泡。
        // 放在 generation/job 重置之前，避免一次连续搜索把同一关键词重复 record。
        viewModelScope.launch(Dispatchers.IO) { searchHistoryRepo.record(keyword) }
        val searchId = searchGeneration.incrementAndGet()
        searchJob?.cancel()
        _query.value = keyword
        _results.value = emptyList()
        _localResults.value = emptyList()
        _sourceProgress.value = emptyList()
        _isSearching.value = true

        searchJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val localBooks = searchRepo.searchLocalBooks(keyword)
                if (!isCurrentSearch(searchId)) return@launch
                _localResults.value = localBooks
                AppLog.info("Search", "Local: ${localBooks.size} results for '$keyword'")

                val allSources = searchRepo.getEnabledSources()
                if (!isCurrentSearch(searchId)) return@launch
                val skipped = allSources.filter { it.bookSourceType != TEXT_BOOK_SOURCE_TYPE }
                if (skipped.isNotEmpty()) {
                    AppLog.info(
                        "Search",
                        "Skipped ${skipped.size} non-text sources: ${skipped.joinToString { decodeHtmlEntities(it.bookSourceName) }}",
                    )
                }
                val sources = allSources.filter { it.bookSourceType == TEXT_BOOK_SOURCE_TYPE }
                if (sources.isEmpty()) {
                    if (isCurrentSearch(searchId)) _isSearching.value = false
                    return@launch
                }

                _sourceProgress.value = sources.map {
                    SourceSearchProgress(
                        sourceUrl = it.bookSourceUrl,
                        sourceName = decodeHtmlEntities(it.bookSourceName),
                        status = SourceStatus.WAITING,
                    )
                }
                AppLog.info("Search", "Searching '$keyword' across ${sources.size} sources")

                val parallelism = prefs.getSearchParallelism()
                val timeoutMs = prefs.getSourceSearchTimeoutMs()
                val semaphore = Semaphore(parallelism.coerceAtMost(sources.size).coerceAtLeast(1))
                supervisorScope {
                    val sourceJobs = sources.map { source ->
                        launch {
                            semaphore.withPermit {
                                searchSingleSource(searchId, source, keyword, timeoutMs)
                            }
                        }
                    }
                    sourceJobs.joinAll()
                }
                AppLog.info("Search", "Search complete: ${_results.value.size} online results")
            } catch (e: kotlinx.coroutines.CancellationException) {
                markUnfinishedSourcesFailed(searchId, "搜索已取消")
                throw e
            } finally {
                if (isCurrentSearch(searchId)) {
                    markUnfinishedSourcesFailed(searchId, "搜索已结束")
                    _isSearching.value = false
                    searchJob = null
                }
            }
        }
    }

    private suspend fun searchSingleSource(searchId: Int, source: BookSource, keyword: String, timeoutMs: Long) {
        if (!isCurrentSearch(searchId)) return
        updateSourceStatus(searchId, source.bookSourceUrl, SourceStatus.SEARCHING)
        try {
            val searchBooks = withTimeout(timeoutMs) {
                searchRepo.searchOnlineSource(source, keyword)
            }
            if (!isCurrentSearch(searchId)) return
            val mapped = searchBooks.map { sb ->
                SearchResult(
                    title = sb.name,
                    author = sb.author,
                    coverUrl = sb.coverUrl,
                    bookUrl = sb.bookUrl,
                    sourceName = decodeHtmlEntities(sb.originName.ifBlank { source.bookSourceName }),
                    sourceUrl = sb.origin,
                    sourceType = sb.type,
                    intro = sb.intro ?: "",
                    kind = sb.kind,
                    wordCount = sb.wordCount,
                    latestChapter = sb.latestChapterTitle,
                    searchBook = sb,
                )
            }.filter { result ->
                // 只把 Legado 默认文本书源送入小说阅读器，并过滤掉标题和作者都不包含关键词的结果。
                result.sourceType == TEXT_BOOK_SOURCE_TYPE &&
                    (result.title.contains(keyword, ignoreCase = true) ||
                    result.author.contains(keyword, ignoreCase = true)
                    )
            }
            if (mapped.isNotEmpty()) {
                mergeResults(searchId, mapped, keyword)
            }
            updateSourceStatus(searchId, source.bookSourceUrl, SourceStatus.DONE)
        } catch (e: TimeoutCancellationException) {
            updateSourceStatus(searchId, source.bookSourceUrl, SourceStatus.FAILED, "超时")
            AppLog.warn("Search", "${decodeHtmlEntities(source.bookSourceName)} timeout")
        } catch (e: Exception) {
            val message = e.toSearchErrorMessage()
            updateSourceStatus(searchId, source.bookSourceUrl, SourceStatus.FAILED, message)
            if (e is IOException) {
                AppLog.warn("Search", "${decodeHtmlEntities(source.bookSourceName)} failed: ${e.message}")
            } else {
                AppLog.warn("Search", "${decodeHtmlEntities(source.bookSourceName)} failed: ${e.message}", e)
            }
        }
    }

    private suspend fun mergeResults(searchId: Int, newItems: List<SearchResult>, keyword: String) {
        mergeMutex.withLock {
            if (!isCurrentSearch(searchId)) return@withLock
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

    private fun isCurrentSearch(searchId: Int): Boolean = searchGeneration.get() == searchId

    private fun updateSourceStatus(searchId: Int, sourceUrl: String, status: SourceStatus, errorMessage: String? = null) {
        _sourceProgress.update { progress ->
            if (!isCurrentSearch(searchId)) return@update progress
            progress.map {
                if (it.sourceUrl == sourceUrl) {
                    it.copy(status = status, errorMessage = errorMessage)
                } else {
                    it
                }
            }
        }
    }

    private fun markUnfinishedSourcesFailed(searchId: Int, message: String) {
        _sourceProgress.update { progress ->
            if (!isCurrentSearch(searchId)) return@update progress
            markUnfinished(progress, message)
        }
    }

    private fun markUnfinishedSourcesFailed(message: String) {
        _sourceProgress.update { progress -> markUnfinished(progress, message) }
    }

    private fun markUnfinished(progress: List<SourceSearchProgress>, message: String): List<SourceSearchProgress> {
        return progress.map {
            when (it.status) {
                SourceStatus.WAITING, SourceStatus.SEARCHING -> it.copy(
                    status = SourceStatus.FAILED,
                    errorMessage = message,
                )
                SourceStatus.DONE, SourceStatus.FAILED -> it
            }
        }
    }

    /**
     * Add an online search result to the local shelf as a web book.
     * If the book already exists (same bookUrl + sourceUrl), return its id.
     * Returns the bookId via callback for immediate navigation.
     *
     * 仅在「真正新加」时通过 [_shelfAddedEvents] 发射事件 —— 已在书架的书第二次
     * 点击不弹 Snackbar，避免误以为加了第二次。withDownload=false 表示这是
     * "点搜索结果直接进阅读器"路径，UI 决定 Snackbar 文案。
     */
    fun addToShelfAndRead(result: SearchResult, onBookReady: (String) -> Unit) {
        addToShelfInternal(result, withDownload = false, onBookReady = onBookReady)
    }

    fun addToShelf(result: SearchResult) {
        addToShelfAndRead(result) { /* no-op navigation */ }
    }

    /**
     * Add to shelf and immediately start downloading all chapters.
     * 与 [addToShelfAndRead] 调同一私有实现，但传 withDownload=true 让 Snackbar
     * 显示「已加入书架并开始缓存」文案；撤销时同步停止下载。
     */
    fun addToShelfAndDownload(result: SearchResult) {
        addToShelfInternal(result, withDownload = true) { bookId ->
            cacheRepo.startDownload(bookId, result.sourceUrl)
        }
    }

    /**
     * 加书核心逻辑。三个公开入口（[addToShelfAndRead] / [addToShelf] /
     * [addToShelfAndDownload]）都委托到这里，确保 ShelfAddedEvent 的发射时机
     * 单一可控（仅在 [bookRepo.insert] 成功后、回调之前）。
     */
    private fun addToShelfInternal(
        result: SearchResult,
        withDownload: Boolean,
        onBookReady: (String) -> Unit,
    ) {
        if (result.sourceType != TEXT_BOOK_SOURCE_TYPE) {
            AppLog.warn("Search", "Blocked non-text source result: ${result.title} from ${result.sourceName}")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val existing = bookRepo.findByBookUrl(result.bookUrl, result.sourceUrl)
                if (existing != null) {
                    AppLog.info("Search", "Already on shelf: ${result.title}")
                    withContext(Dispatchers.Main) { onBookReady(existing.id) }
                    return@launch
                }
                val bookId = java.util.UUID.randomUUID().toString()
                val rawBook = Book(
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
                    kind = result.kind?.takeIf { it.isNotBlank() },
                    wordCount = result.wordCount?.takeIf { it.isNotBlank() },
                    format = BookFormat.WEB,
                    addedAt = System.currentTimeMillis(),
                )
                val book = rawBook.copy(folderId = autoGroupClassifier.classify(rawBook))
                bookRepo.insert(book)
                AppLog.info("Search", "Added to shelf: ${result.title} from ${result.sourceName}")
                // 加书事件 — UI 端订阅后弹 Snackbar 反馈+撤销。emit 在 onBookReady
                // 之前，让 SearchScreen 在用户被 navigate 走之前就先收到事件
                // （加上 SharedFlow.replay=1 + UI 时间戳过滤，跨 navigate 也能补显示）。
                _shelfAddedEvents.emit(
                    ShelfAddedEvent(
                        bookId = bookId,
                        title = result.title,
                        withDownload = withDownload,
                    )
                )
                // 实时同步 toc：触发后台单本刷新，让 totalChapters / lastChapter 字段立即落库。
                // ShelfRefreshController.refreshOne 在 oldTotal == 0 时跳过 lastCheckCount 写入，
                // 所以新书首屏不会蹦出"N 新"红字徽章。
                refreshController.refresh(listOf(book), parallelism = 1)
                withContext(Dispatchers.Main) { onBookReady(bookId) }
            } catch (e: Exception) {
                AppLog.error("Search", "Failed to add to shelf: ${e.message}", e)
            }
        }
    }

    /**
     * 撤销加书。删除 [bookId] 对应记录；若 [stopDownload]=true 同步停止当前缓存
     * 任务（CacheBookService 是单例，stopDownload 没有 bookId 维度，简单粗暴
     * stop 当前下载即可——撤销动作发生在加书后短时间内，命中率高，误伤其他下载
     * 的概率低）。
     *
     * 删除走 [bookRepo.deleteById]，由 Repository 内部级联清理章节 / 缓存等
     * 关联数据。失败不抛 — UI 已经 dismiss Snackbar，再弹错也意义不大；日志兜底。
     */
    fun undoAddToShelf(bookId: String, stopDownload: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (stopDownload) {
                    runCatching { cacheRepo.stopDownload() }
                }
                bookRepo.deleteById(bookId)
                AppLog.info("Search", "Undo addToShelf: removed bookId=$bookId")
            } catch (e: Exception) {
                AppLog.warn("Search", "Undo addToShelf failed: ${e.message}", e)
            }
        }
    }

    private fun decodeHtmlEntities(value: String): String {
        if (value.isBlank()) return value
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Html.fromHtml(value, Html.FROM_HTML_MODE_LEGACY).toString()
        } else {
            @Suppress("DEPRECATION")
            Html.fromHtml(value).toString()
        }
    }

    private fun Exception.toSearchErrorMessage(): String {
        return when (this) {
            is IOException -> message?.take(80)?.let { "网络错误：$it" } ?: "网络错误"
            else -> message?.take(80) ?: this::class.java.simpleName
        }
    }
}
