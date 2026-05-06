package com.morealm.app.presentation.source

import com.morealm.app.core.log.AppLog
import com.morealm.app.domain.db.ChapterDao
import com.morealm.app.domain.db.SearchBookCacheDao
import com.morealm.app.domain.entity.Book
import com.morealm.app.domain.entity.BookChapter
import com.morealm.app.domain.entity.BookSource
import com.morealm.app.domain.entity.SearchBook
import com.morealm.app.domain.entity.SearchBookCache
import com.morealm.app.domain.preference.AppPreferences
import com.morealm.app.domain.repository.BookRepository
import com.morealm.app.domain.repository.SearchRepository
import com.morealm.app.domain.repository.SourceRepository
import com.morealm.app.domain.webbook.ChapterMatcher
import com.morealm.app.domain.webbook.ChapterResult
import com.morealm.app.domain.webbook.WebBook
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap

private const val TEXT_BOOK_SOURCE_TYPE = 0
// SOURCE_SEARCH_TIMEOUT_MS / SEARCH_PARALLELISM 已迁移到 AppPreferences —
// 详情见 SearchViewModel 顶部注释。
/** 缓存老化阈值：7 天前的换源候选缓存清掉，避免库无限增长。 */
private const val SEARCH_CACHE_TTL_MS = 7L * 24 * 3600 * 1000

/**
 * 换源候选结果（封装 SearchBook + 进度状态 + 排序键）。
 *
 * `originOrder` 来自 BookSource.customOrder，越小越靠前；
 * `responseTime` 是搜索耗时，作为同源同序的次级 tiebreaker。
 * `fromCache` 表示该候选是从 db 缓存复活而非本次搜索得来 — UI 可加角标提示「上次结果」。
 */
data class ChangeSourceCandidate(
    val sourceUrl: String,
    val sourceName: String,
    val searchBook: SearchBook,
    val originOrder: Int = 0,
    val responseTime: Long = 0L,
    val fromCache: Boolean = false,
)

/** 单源搜索进度 — 与 SearchViewModel 同形态便于 UI 复用 */
data class ChangeSourceProgress(
    val sourceUrl: String,
    val sourceName: String,
    val status: SearchStatus,
    val errorMessage: String? = null,
)

enum class SearchStatus { WAITING, SEARCHING, DONE, FAILED }

/**
 * 换源功能的可复用 controller —— 同时被 BookDetailViewModel（书详情页）和
 * ReaderViewModel（阅读器内）持有。每个 ViewModel 持自己的实例（不共享 state），
 * 因为：
 *  - 详情页的换源对话框和阅读器内的换源对话框是独立 UX，不应该串扰。
 *  - 如果用户在详情页搜了一半切换到阅读器，应当独立重新搜，而不是带过去半成品。
 *
 * Lifecycle 由调用方的 [scope] 决定：当 ViewModel `onCleared` 时它的 viewModelScope
 * 被取消，本 controller 内的 `changeSourceJob` 也会随之取消。
 *
 * 所有副作用（DB 读写、网络）都在 [Dispatchers.IO] 上跑；StateFlow 暴露给 UI 时
 * 主线程订阅即可，Compose 会自动处理。
 */
class ChangeSourceController(
    private val scope: CoroutineScope,
    private val bookRepo: BookRepository,
    private val sourceRepo: SourceRepository,
    private val searchRepo: SearchRepository,
    private val searchBookCacheDao: SearchBookCacheDao,
    private val chapterDao: ChapterDao,
    private val prefs: AppPreferences,
) {
    // ── Public state ──────────────────────────────────────────────────────

    private val _showPicker = MutableStateFlow(false)
    val showPicker: StateFlow<Boolean> = _showPicker.asStateFlow()

    private val _candidates = MutableStateFlow<List<ChangeSourceCandidate>>(emptyList())
    val candidates: StateFlow<List<ChangeSourceCandidate>> = _candidates.asStateFlow()

    private val _progress = MutableStateFlow<List<ChangeSourceProgress>>(emptyList())
    val progress: StateFlow<List<ChangeSourceProgress>> = _progress.asStateFlow()

    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()

    /**
     * 用户可见的错误提示流（典型：换源 Step 1 找不到源、Step 2 toc 拉不到 / 为空）。
     *
     * 为什么用 SharedFlow 而非 StateFlow：
     * - 同一类错误可能连续触发（用户连点 N 次都失败），需要"每次都通知 UI"语义；
     *   StateFlow 重复值会被去重，UI 收不到第二次。
     * - replay = 0 + onBufferOverflow = DROP_OLDEST：UI 没在监听时（对话框已关）就把
     *   错误丢弃，不会在重新打开对话框时弹一堆陈年 toast。
     */
    private val _errorEvents = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val errorEvents: SharedFlow<String> = _errorEvents.asSharedFlow()

    // ── Internal state ────────────────────────────────────────────────────

    private var changeSourceJob: Job? = null
    private val mergeMutex = Mutex()

    /**
     * 候选源 toc 内存缓存 — 在 [applyCandidate] 时优先复用，避免再发一次
     * `getChapterListAwait`。 key = origin + bookUrl。生命周期与对话框同步：
     * [closePicker] 清空。
     *
     * 注意：v1 不在搜索阶段预拉所有候选 toc（与 Legado changeSourceLoadToc 配置项不同），
     * 只在用户选定某候选后即时拉一次并写入此 cache —— 既减少无效请求，也保证应用换源时有 toc。
     */
    private val tocCache = ConcurrentHashMap<String, List<ChapterResult>>()

    /**
     * 排序：有最新章节 → 含字数 → originOrder ASC → responseTime ASC。
     * 「有最新章节」放第一位，因为这是用户最在乎的「换源后能跟上进度」信号。
     */
    private val candidateComparator = compareBy<ChangeSourceCandidate>(
        { it.searchBook.latestChapterTitle.isNullOrBlank() },
        { it.searchBook.wordCount.isNullOrBlank() },
        { it.originOrder },
        { it.responseTime },
    )

    // ── Public API ────────────────────────────────────────────────────────

    /** 打开换源对话框并启动跨源搜索。 */
    fun openPicker(book: Book) {
        _showPicker.value = true
        startSearch(book)
    }

    /** 关闭对话框 + 取消搜索 + 清 toc cache。 */
    fun closePicker() {
        _showPicker.value = false
        cancelSearch()
        tocCache.clear()
    }

    /** 仅取消搜索协程，不动对话框可见性。 */
    fun cancelSearch() {
        changeSourceJob?.cancel()
        changeSourceJob = null
        _searching.value = false
    }

    /**
     * 真正应用换源 — 「换完接着读不跳章」的关键路径。
     *
     * 流程（任意一步失败都不破坏旧状态：换源会被取消并保留原书数据）：
     *  1. 拉新源的 BookSource（必须存在）
     *  2. 拉新源 toc（优先复用 [tocCache]，没命中就现拉一次并落 cache）
     *  3. 读旧源章节列表（chapter db），拿到 oldChapters + oldIndex
     *  4. [ChapterMatcher.findBestMatch] 计算新源中应当落到的章节序号
     *  5. 把新 toc 写到 chapter db（清旧 + 插新），id 保持 `${bookId}_$index` 兼容现有 reader
     *  6. Book.copy(...) 写回 — origin/bookUrl/tocUrl/lastReadChapter/totalChapters 一并更新
     *  7. 通过 [onApplied] 回调把更新后的 Book 交给调用方（详情页刷新 _book / 阅读器重载章节）
     *
     * 失败回退：只要 1/2 失败（典型：源被禁用、网络超时），整个换源动作中止 + UI 提示。
     */
    fun applyCandidate(
        book: Book,
        candidate: ChangeSourceCandidate,
        onApplied: (Book) -> Unit,
    ) {
        AppLog.info(
            "ChangeSource",
            "applyCandidate enter: book='${book.title}' (origin=${book.origin}) → " +
                "candidate sourceUrl=${candidate.sourceUrl} sourceName=${candidate.sourceName} " +
                "fromCache=${candidate.fromCache} bookUrl=${candidate.searchBook.bookUrl}"
        )
        scope.launch(Dispatchers.IO) {
            val sb = candidate.searchBook
            val tocUrl = sb.tocUrl.ifBlank { sb.bookUrl }

            // Step 1: 找新源
            val newSource = sourceRepo.getByUrl(candidate.sourceUrl) ?: run {
                AppLog.warn(
                    "ChangeSource",
                    "applyCandidate Step 1 abort: BookSource '${candidate.sourceUrl}' not in DB. " +
                        "Likely cache stale or source was deleted/re-imported with different URL."
                )
                _errorEvents.tryEmit("书源「${candidate.sourceName}」已不存在或被删除")
                return@launch
            }

            // Step 2: 拉/复用新 toc。
            val cacheKey = candidate.sourceUrl + "|" + sb.bookUrl
            val newToc: List<ChapterResult> = try {
                tocCache[cacheKey] ?: withTimeout(prefs.getSourceSearchTimeoutMs()) {
                    WebBook.getChapterListAwait(newSource, sb.bookUrl, tocUrl)
                }.also { tocCache[cacheKey] = it }
            } catch (e: Exception) {
                AppLog.warn(
                    "ChangeSource",
                    "applyCandidate Step 2 abort: failed to fetch toc on new source " +
                        "'${newSource.bookSourceName}' bookUrl=${sb.bookUrl}: ${e.message}"
                )
                _errorEvents.tryEmit("拉取目录失败：${e.message?.take(60) ?: "未知错误"}")
                return@launch
            }
            if (newToc.isEmpty()) {
                AppLog.warn(
                    "ChangeSource",
                    "applyCandidate Step 2 abort: empty toc from '${newSource.bookSourceName}' " +
                        "bookUrl=${sb.bookUrl}"
                )
                _errorEvents.tryEmit("「${newSource.bookSourceName}」返回空目录，换源中止")
                return@launch
            }

            // Step 3: 读旧章节，跑章节智能匹配
            val oldChapters = runCatching { chapterDao.getChaptersList(book.id) }
                .getOrDefault(emptyList())
            val oldIndex = book.lastReadChapter
            val newIndex = ChapterMatcher.findBestMatch(oldChapters, oldIndex, newToc)
            AppLog.info(
                "ChangeSource",
                "Chapter remap: old[$oldIndex/${oldChapters.size}] → new[$newIndex/${newToc.size}]"
            )

            // Step 4: 持久化新 toc 到 chapter db，避免 reader 重拉一次。
            //   id 规则与 ReaderChapterController.fetchTocFromWeb 一致："${bookId}_$i"，
            //   reader 在 onCreate 后能直接通过 chapterDao.getChaptersList(bookId) 复用。
            val newChapters = newToc.mapIndexed { i, ch ->
                BookChapter(
                    id = "${book.id}_$i",
                    bookId = book.id,
                    index = i,
                    title = ch.title,
                    url = ch.url,
                )
            }
            runCatching { bookRepo.saveChapters(book.id, newChapters) }
                .onFailure { AppLog.warn("ChangeSource", "saveChapters failed: ${it.message}") }

            // Step 5: 写回 Book
            val updated = book.copy(
                bookUrl = sb.bookUrl,
                tocUrl = tocUrl.ifBlank { null },
                origin = sb.origin,
                originName = sb.originName.ifBlank { candidate.sourceName },
                sourceId = sb.origin,
                sourceUrl = sb.origin,
                coverUrl = sb.coverUrl ?: book.coverUrl,
                description = sb.intro?.ifBlank { null } ?: book.description,
                kind = sb.kind ?: book.kind,
                wordCount = sb.wordCount ?: book.wordCount,
                totalChapters = newToc.size,
                lastReadChapter = newIndex,
                // 章节序号变了，章内字偏移失效 → 归零。让 reader 落到新章节首屏。
                lastReadPosition = 0,
                lastReadOffset = 0f,
                hasDetail = true,
            )
            bookRepo.update(updated)
            _showPicker.value = false
            cancelSearch()
            tocCache.clear()
            AppLog.info(
                "ChangeSource",
                "Switched '${updated.title}' to ${updated.originName} (${updated.bookUrl}); " +
                    "chapter $oldIndex → $newIndex (${newToc.size} total)"
            )
            onApplied(updated)
        }
    }

    /**
     * 老化清理：把 7 天前的搜索缓存清掉。调用方应在合适的时机（如详情页/阅读器初始化）
     * 触发一次。失败不抛错。
     */
    suspend fun pruneStaleCache() {
        runCatching {
            searchBookCacheDao.deleteOlderThan(System.currentTimeMillis() - SEARCH_CACHE_TTL_MS)
        }
    }

    // ── Internal helpers ──────────────────────────────────────────────────

    private fun startSearch(book: Book) {
        cancelSearch()
        _candidates.value = emptyList()
        _progress.value = emptyList()
        _searching.value = true

        val keyword = book.title
        if (keyword.isBlank()) {
            _searching.value = false
            return
        }

        changeSourceJob = scope.launch(Dispatchers.IO) {
            try {
                // ── Phase 1: 立刻从 db 缓存吐出上次结果，让用户秒看到列表。 ──
                val cached = runCatching {
                    searchBookCacheDao.getByBook(book.title, book.author)
                }.getOrDefault(emptyList())
                if (cached.isNotEmpty()) {
                    val asCandidates = cached
                        .filter { it.origin != book.origin }  // 排除当前源
                        .map { it.toCandidate(fromCache = true) }
                    mergeMutex.withLock {
                        _candidates.value = asCandidates.sortedWith(candidateComparator)
                    }
                }

                // ── Phase 2: 拉所有启用文本源做新一轮搜索。 ──
                val sources = sourceRepo.getEnabledSourcesList()
                    .filter { it.bookSourceType == TEXT_BOOK_SOURCE_TYPE }
                if (sources.isEmpty()) {
                    _searching.value = false
                    return@launch
                }
                _progress.value = sources.map {
                    ChangeSourceProgress(
                        sourceUrl = it.bookSourceUrl,
                        sourceName = it.bookSourceName,
                        status = SearchStatus.WAITING,
                    )
                }
                val parallelism = prefs.getSearchParallelism()
                val timeoutMs = prefs.getSourceSearchTimeoutMs()
                val semaphore = Semaphore(parallelism.coerceAtMost(sources.size).coerceAtLeast(1))
                supervisorScope {
                    val jobs = sources.map { source ->
                        launch {
                            semaphore.withPermit {
                                searchOne(source, keyword, book, timeoutMs)
                            }
                        }
                    }
                    jobs.joinAll()
                }
                AppLog.info("ChangeSource", "Found ${_candidates.value.size} candidates for '$keyword'")
            } finally {
                _searching.value = false
            }
        }
    }

    private suspend fun searchOne(source: BookSource, keyword: String, book: Book, timeoutMs: Long) {
        updateProgress(source.bookSourceUrl, SearchStatus.SEARCHING)
        val startTime = System.currentTimeMillis()
        try {
            val results = withTimeout(timeoutMs) {
                searchRepo.searchOnlineSource(source, keyword)
            }
            val elapsed = System.currentTimeMillis() - startTime
            // 候选过滤策略 —— 与 Legado MD3 行为对齐，比旧实现更宽松：
            //
            // 旧版 bug：
            //   1) `it.name.contains(keyword) || keyword.contains(it.name)` 反向 contains
            //      会把 it.name="圣"、""、单字碎片也算作匹配，引入垃圾候选；
            //   2) `(book.author.isBlank() || it.author.contains(book.author) ||
            //       book.author.contains(it.author))` 当 book.author 非空但 it.author
            //      是不同作者（典型："未知"、错误别名、源解析失败兜底值）时，整本被滤掉。
            //      这是用户报"50 个源里有 1 本，候选只剩 1 个"的主因。
            //
            // 新策略：
            //   - 书名：单向 forward `it.name.contains(keyword)`，去掉反向；
            //   - 作者：双向缺失任一都通过，仅当两边都填且都对不上才滤掉；
            //   - 类型 + 排除当前源：保持不变。
            val filtered = results.filter {
                if (it.type != TEXT_BOOK_SOURCE_TYPE) return@filter false
                if (it.origin == book.origin) return@filter false
                val nameMatch = it.name.equals(keyword, ignoreCase = true) ||
                    it.name.contains(keyword, ignoreCase = true)
                if (!nameMatch) return@filter false
                val authorMatch = book.author.isBlank() || it.author.isBlank() ||
                    it.author.contains(book.author, ignoreCase = true) ||
                    book.author.contains(it.author, ignoreCase = true)
                authorMatch
            }
            if (results.isNotEmpty() && filtered.size < results.size) {
                AppLog.debug(
                    "ChangeSource",
                    "Filter on '${source.bookSourceName}': ${results.size} -> ${filtered.size} " +
                        "(name='$keyword' author='${book.author}')"
                )
            }
            if (filtered.isNotEmpty()) {
                // 1. 写 db cache 持久化（先写库再 merge UI，避免崩溃丢候选）。
                val cacheRows = filtered.map {
                    SearchBookCache(
                        bookUrl = it.bookUrl,
                        origin = it.origin,
                        originName = it.originName.ifBlank { source.bookSourceName },
                        bookName = book.title,
                        author = book.author,
                        type = it.type,
                        coverUrl = it.coverUrl,
                        intro = it.intro,
                        kind = it.kind,
                        wordCount = it.wordCount,
                        latestChapterTitle = it.latestChapterTitle,
                        tocUrl = it.tocUrl,
                        originOrder = source.customOrder,
                        responseTime = elapsed,
                    )
                }
                runCatching { searchBookCacheDao.insertAll(cacheRows) }
                    .onFailure { AppLog.warn("ChangeSource", "Cache insert failed: ${it.message}") }

                // 2. 合并到 candidates StateFlow（替换旧 cache 项 + 排序）。
                mergeMutex.withLock {
                    val current = _candidates.value.toMutableList()
                    for (sb in filtered) {
                        // 同 (origin, bookUrl) 去重 — 替换旧 cache 项为最新搜索结果（fromCache=false）。
                        current.removeAll {
                            it.searchBook.bookUrl == sb.bookUrl && it.sourceUrl == sb.origin
                        }
                        current.add(
                            ChangeSourceCandidate(
                                sourceUrl = sb.origin,
                                sourceName = sb.originName.ifBlank { source.bookSourceName },
                                searchBook = sb,
                                originOrder = source.customOrder,
                                responseTime = elapsed,
                                fromCache = false,
                            )
                        )
                    }
                    _candidates.value = current.sortedWith(candidateComparator)
                }
            }
            updateProgress(source.bookSourceUrl, SearchStatus.DONE)
        } catch (_: TimeoutCancellationException) {
            updateProgress(source.bookSourceUrl, SearchStatus.FAILED, "超时")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            updateProgress(source.bookSourceUrl, SearchStatus.FAILED, e.message?.take(80))
        }
    }

    private fun updateProgress(sourceUrl: String, status: SearchStatus, errorMessage: String? = null) {
        _progress.update { list ->
            list.map {
                if (it.sourceUrl == sourceUrl) it.copy(status = status, errorMessage = errorMessage) else it
            }
        }
    }
}

/** SearchBookCache → ChangeSourceCandidate 转换：把持久化字段还原为运行时候选项。 */
private fun SearchBookCache.toCandidate(fromCache: Boolean): ChangeSourceCandidate {
    val sb = SearchBook(
        bookUrl = bookUrl,
        origin = origin,
        originName = originName,
        type = type,
        name = bookName,
        author = author,
        kind = kind,
        coverUrl = coverUrl,
        intro = intro,
        wordCount = wordCount,
        latestChapterTitle = latestChapterTitle,
        tocUrl = tocUrl,
        time = time,
    )
    return ChangeSourceCandidate(
        sourceUrl = origin,
        sourceName = originName,
        searchBook = sb,
        originOrder = originOrder,
        responseTime = responseTime,
        fromCache = fromCache,
    )
}
