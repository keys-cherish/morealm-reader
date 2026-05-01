package com.morealm.app.presentation.profile

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.morealm.app.core.log.AppLog
import com.morealm.app.domain.db.ChapterDao
import com.morealm.app.domain.db.SearchBookCacheDao
import com.morealm.app.domain.entity.Book
import com.morealm.app.domain.entity.BookChapter
import com.morealm.app.domain.entity.BookFormat
import com.morealm.app.domain.entity.BookSource
import com.morealm.app.domain.entity.SearchBook
import com.morealm.app.domain.entity.SearchBookCache
import com.morealm.app.domain.repository.AutoGroupClassifier
import com.morealm.app.domain.repository.BookRepository
import com.morealm.app.domain.repository.SearchRepository
import com.morealm.app.domain.repository.SourceRepository
import com.morealm.app.domain.parser.EpubMetadataWriter
import com.morealm.app.domain.webbook.ChapterMatcher
import com.morealm.app.domain.webbook.ChapterResult
import com.morealm.app.domain.webbook.WebBook
import android.content.Context
import android.net.Uri
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
import javax.inject.Inject

private const val SOURCE_SEARCH_TIMEOUT_MS = 30_000L
private const val SEARCH_PARALLELISM = 8
private const val TEXT_BOOK_SOURCE_TYPE = 0
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

@HiltViewModel
class BookDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val bookRepo: BookRepository,
    private val sourceRepo: SourceRepository,
    private val searchRepo: SearchRepository,
    private val cacheRepo: com.morealm.app.domain.repository.CacheRepository,
    private val searchBookCacheDao: SearchBookCacheDao,
    private val chapterDao: ChapterDao,
    private val autoGroupClassifier: AutoGroupClassifier,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val bookId: String = savedStateHandle["bookId"] ?: ""

    private val _book = MutableStateFlow<Book?>(null)
    val book: StateFlow<Book?> = _book.asStateFlow()

    /** Enabled sources count — used to gate the change-source button. */
    private val _enabledSourcesCount = MutableStateFlow(0)
    val enabledSourcesCount: StateFlow<Int> = _enabledSourcesCount.asStateFlow()

    // ── Change-source dialog state ──

    private val _showSourcePicker = MutableStateFlow(false)
    val showSourcePicker: StateFlow<Boolean> = _showSourcePicker.asStateFlow()

    private val _changeSourceCandidates = MutableStateFlow<List<ChangeSourceCandidate>>(emptyList())
    val changeSourceCandidates: StateFlow<List<ChangeSourceCandidate>> = _changeSourceCandidates.asStateFlow()

    private val _changeSourceProgress = MutableStateFlow<List<ChangeSourceProgress>>(emptyList())
    val changeSourceProgress: StateFlow<List<ChangeSourceProgress>> = _changeSourceProgress.asStateFlow()

    private val _changeSourceSearching = MutableStateFlow(false)
    val changeSourceSearching: StateFlow<Boolean> = _changeSourceSearching.asStateFlow()

    private var changeSourceJob: Job? = null
    private val mergeMutex = Mutex()

    /**
     * 候选源 toc 内存缓存 — 在 applyChangedSource 时优先复用，避免 ReaderViewModel 再发一次
     * `getChapterListAwait`。 key = origin + bookUrl。生命周期与对话框同步：hideSourcePicker 清空。
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

    init {
        viewModelScope.launch(Dispatchers.IO) {
            _book.value = bookRepo.getById(bookId)
            _enabledSourcesCount.value = sourceRepo.getEnabledSourcesList().count {
                it.bookSourceType == TEXT_BOOK_SOURCE_TYPE
            }
            // 老化清理：每次进入详情页捎带做一次，开销可忽略。
            runCatching {
                searchBookCacheDao.deleteOlderThan(System.currentTimeMillis() - SEARCH_CACHE_TTL_MS)
            }
        }
    }

    // ── Change source: open dialog, kick off cross-source search ──

    fun showSourcePicker() {
        val current = _book.value ?: return
        _showSourcePicker.value = true
        startCrossSourceSearch(current)
    }

    fun hideSourcePicker() {
        _showSourcePicker.value = false
        cancelCrossSourceSearch()
        tocCache.clear()
    }

    private fun startCrossSourceSearch(book: Book) {
        cancelCrossSourceSearch()
        _changeSourceCandidates.value = emptyList()
        _changeSourceProgress.value = emptyList()
        _changeSourceSearching.value = true

        val keyword = book.title
        if (keyword.isBlank()) {
            _changeSourceSearching.value = false
            return
        }

        changeSourceJob = viewModelScope.launch(Dispatchers.IO) {
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
                        _changeSourceCandidates.value = asCandidates.sortedWith(candidateComparator)
                    }
                }

                // ── Phase 2: 拉所有启用文本源做新一轮搜索。 ──
                val sources = sourceRepo.getEnabledSourcesList()
                    .filter { it.bookSourceType == TEXT_BOOK_SOURCE_TYPE }
                if (sources.isEmpty()) {
                    _changeSourceSearching.value = false
                    return@launch
                }
                _changeSourceProgress.value = sources.map {
                    ChangeSourceProgress(
                        sourceUrl = it.bookSourceUrl,
                        sourceName = it.bookSourceName,
                        status = SearchStatus.WAITING,
                    )
                }
                val semaphore = Semaphore(SEARCH_PARALLELISM.coerceAtMost(sources.size).coerceAtLeast(1))
                supervisorScope {
                    val jobs = sources.map { source ->
                        launch {
                            semaphore.withPermit {
                                searchOne(source, keyword, book)
                            }
                        }
                    }
                    jobs.joinAll()
                }
                AppLog.info("ChangeSource", "Found ${_changeSourceCandidates.value.size} candidates for '$keyword'")
            } finally {
                _changeSourceSearching.value = false
            }
        }
    }

    fun cancelCrossSourceSearch() {
        changeSourceJob?.cancel()
        changeSourceJob = null
        _changeSourceSearching.value = false
    }

    private suspend fun searchOne(source: BookSource, keyword: String, book: Book) {
        updateProgress(source.bookSourceUrl, SearchStatus.SEARCHING)
        val startTime = System.currentTimeMillis()
        try {
            val results = withTimeout(SOURCE_SEARCH_TIMEOUT_MS) {
                searchRepo.searchOnlineSource(source, keyword)
            }
            val elapsed = System.currentTimeMillis() - startTime
            // Filter: title 模糊匹配 + author 模糊匹配（任一方可选） + 排除当前源。
            val filtered = results.filter {
                it.type == TEXT_BOOK_SOURCE_TYPE &&
                    (it.name.contains(keyword, ignoreCase = true) ||
                        keyword.contains(it.name, ignoreCase = true)) &&
                    (book.author.isBlank() || it.author.contains(book.author, ignoreCase = true) ||
                        book.author.contains(it.author, ignoreCase = true)) &&
                    it.origin != book.origin
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
                    val current = _changeSourceCandidates.value.toMutableList()
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
                    _changeSourceCandidates.value = current.sortedWith(candidateComparator)
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
        _changeSourceProgress.update { list ->
            list.map {
                if (it.sourceUrl == sourceUrl) it.copy(status = status, errorMessage = errorMessage) else it
            }
        }
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
     *
     * 失败回退：只要 1/2 失败（典型：源被禁用、网络超时），整个换源动作中止 + UI 提示。
     */
    fun applyChangedSource(candidate: ChangeSourceCandidate) {
        viewModelScope.launch(Dispatchers.IO) {
            val current = _book.value ?: return@launch
            val sb = candidate.searchBook
            val tocUrl = sb.tocUrl.ifBlank { sb.bookUrl }

            // Step 1: 找新源
            val newSource = sourceRepo.getByUrl(candidate.sourceUrl) ?: run {
                AppLog.warn("ChangeSource", "Source ${candidate.sourceUrl} not found, abort")
                return@launch
            }

            // Step 2: 拉/复用新 toc。
            val cacheKey = candidate.sourceUrl + "|" + sb.bookUrl
            val newToc: List<ChapterResult> = try {
                tocCache[cacheKey] ?: withTimeout(SOURCE_SEARCH_TIMEOUT_MS) {
                    WebBook.getChapterListAwait(newSource, sb.bookUrl, tocUrl)
                }.also { tocCache[cacheKey] = it }
            } catch (e: Exception) {
                AppLog.warn("ChangeSource", "Failed to fetch toc on new source: ${e.message}")
                return@launch
            }
            if (newToc.isEmpty()) {
                AppLog.warn("ChangeSource", "New source returned empty toc, abort")
                return@launch
            }

            // Step 3: 读旧章节，跑章节智能匹配
            val oldChapters = runCatching { chapterDao.getChaptersList(bookId) }
                .getOrDefault(emptyList())
            val oldIndex = current.lastReadChapter
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
                    id = "${bookId}_$i",
                    bookId = bookId,
                    index = i,
                    title = ch.title,
                    url = ch.url,
                )
            }
            runCatching { bookRepo.saveChapters(bookId, newChapters) }
                .onFailure { AppLog.warn("ChangeSource", "saveChapters failed: ${it.message}") }

            // Step 5: 写回 Book
            val updated = current.copy(
                bookUrl = sb.bookUrl,
                tocUrl = tocUrl.ifBlank { null },
                origin = sb.origin,
                originName = sb.originName.ifBlank { candidate.sourceName },
                sourceId = sb.origin,
                sourceUrl = sb.origin,
                coverUrl = sb.coverUrl ?: current.coverUrl,
                description = sb.intro?.ifBlank { null } ?: current.description,
                kind = sb.kind ?: current.kind,
                wordCount = sb.wordCount ?: current.wordCount,
                totalChapters = newToc.size,
                lastReadChapter = newIndex,
                // 章节序号变了，章内字偏移失效 → 归零。让 reader 落到新章节首屏。
                lastReadPosition = 0,
                lastReadOffset = 0f,
                hasDetail = true,
            )
            bookRepo.update(updated)
            _book.value = updated
            _showSourcePicker.value = false
            cancelCrossSourceSearch()
            tocCache.clear()
            AppLog.info(
                "ChangeSource",
                "Switched '${updated.title}' to ${updated.originName} (${updated.bookUrl}); " +
                    "chapter $oldIndex → $newIndex (${newToc.size} total)"
            )
        }
    }

    // ── Existing functionality preserved below ──

    fun deleteBook() {
        viewModelScope.launch(Dispatchers.IO) {
            bookRepo.deleteById(bookId)
        }
    }

    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

    /**
     * Update book metadata. For EPUB books, also writes changes back to the EPUB file
     * so that re-imports will reflect the edits.
     */
    fun updateMetadata(
        title: String,
        author: String,
        description: String,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            _saving.value = true
            val current = _book.value ?: return@launch
            val rawUpdated = current.copy(
                title = title.ifBlank { current.title },
                author = author,
                description = description.ifBlank { null },
            )
            val updated = if (rawUpdated.folderId == null) {
                rawUpdated.copy(folderId = autoGroupClassifier.classify(rawUpdated))
            } else rawUpdated
            bookRepo.update(updated)
            _book.value = updated

            if (current.format == BookFormat.EPUB && current.localPath != null) {
                try {
                    val uri = Uri.parse(current.localPath)
                    val epubUpdate = EpubMetadataWriter.MetadataUpdate(
                        title = title.ifBlank { null },
                        author = author.ifBlank { null },
                        description = description.ifBlank { null },
                    )
                    EpubMetadataWriter.updateMetadata(context, uri, epubUpdate)
                } catch (e: Exception) {
                    AppLog.error("Detail", "Failed to write EPUB metadata: ${e.message}")
                }
            }
            _saving.value = false
        }
    }

    val isCacheDownloading = cacheRepo.isDownloading
    val cacheDownloadProgress = cacheRepo.downloadProgress

    fun startCacheBook(bookId: String, sourceUrl: String) = cacheRepo.startDownload(bookId, sourceUrl)
    fun stopCacheBook() = cacheRepo.stopDownload()
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
