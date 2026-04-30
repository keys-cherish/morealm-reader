package com.morealm.app.presentation.profile

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.morealm.app.core.log.AppLog
import com.morealm.app.domain.entity.Book
import com.morealm.app.domain.entity.BookFormat
import com.morealm.app.domain.entity.BookSource
import com.morealm.app.domain.entity.SearchBook
import com.morealm.app.domain.repository.AutoGroupClassifier
import com.morealm.app.domain.repository.BookRepository
import com.morealm.app.domain.repository.SearchRepository
import com.morealm.app.domain.repository.SourceRepository
import com.morealm.app.domain.parser.EpubMetadataWriter
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
import javax.inject.Inject

private const val SOURCE_SEARCH_TIMEOUT_MS = 30_000L
private const val SEARCH_PARALLELISM = 8
private const val TEXT_BOOK_SOURCE_TYPE = 0

/**
 * 换源候选结果（封装 SearchBook + 进度状态）。
 */
data class ChangeSourceCandidate(
    val sourceUrl: String,
    val sourceName: String,
    val searchBook: SearchBook,
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

    init {
        viewModelScope.launch(Dispatchers.IO) {
            _book.value = bookRepo.getById(bookId)
            _enabledSourcesCount.value = sourceRepo.getEnabledSourcesList().count {
                it.bookSourceType == TEXT_BOOK_SOURCE_TYPE
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
        try {
            val results = withTimeout(SOURCE_SEARCH_TIMEOUT_MS) {
                searchRepo.searchOnlineSource(source, keyword)
            }
            // Filter: must roughly match by title (loose) AND ignore current source (already on this source).
            val filtered = results.filter {
                it.type == TEXT_BOOK_SOURCE_TYPE &&
                    (it.name.contains(keyword, ignoreCase = true) ||
                        keyword.contains(it.name, ignoreCase = true)) &&
                    (book.author.isBlank() || it.author.contains(book.author, ignoreCase = true) ||
                        book.author.contains(it.author, ignoreCase = true)) &&
                    it.origin != book.origin
            }
            if (filtered.isNotEmpty()) {
                mergeMutex.withLock {
                    val current = _changeSourceCandidates.value.toMutableList()
                    for (sb in filtered) {
                        if (current.none { it.sourceUrl == sb.origin && it.searchBook.bookUrl == sb.bookUrl }) {
                            current.add(
                                ChangeSourceCandidate(
                                    sourceUrl = sb.origin,
                                    sourceName = sb.originName.ifBlank { source.bookSourceName },
                                    searchBook = sb,
                                )
                            )
                        }
                    }
                    _changeSourceCandidates.value = current
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
     * 真正应用换源：用 SearchBook 的 origin/bookUrl/tocUrl/coverUrl/intro 替换原书。
     * 章节数 / lastReadChapter 不动 — 保留原阅读进度（按章节序号匹配新源）。如新源章节较少，
     * 后续阅读时章节加载会落到末章。
     */
    fun applyChangedSource(candidate: ChangeSourceCandidate) {
        viewModelScope.launch(Dispatchers.IO) {
            val current = _book.value ?: return@launch
            val sb = candidate.searchBook
            // Resolve tocUrl (may need a detail-page fetch later if SearchBook.tocUrl is empty;
            // for v1 we rely on bookUrl as the toc anchor — most sources use the same URL).
            val tocUrl = sb.tocUrl.ifBlank { sb.bookUrl }
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
                // Reset cached chapter list (caller must re-fetch toc on next open)
                totalChapters = 0,
                hasDetail = true,
            )
            bookRepo.update(updated)
            _book.value = updated
            _showSourcePicker.value = false
            cancelCrossSourceSearch()
            AppLog.info(
                "ChangeSource",
                "Switched '${updated.title}' to ${updated.originName} (${updated.bookUrl})"
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
