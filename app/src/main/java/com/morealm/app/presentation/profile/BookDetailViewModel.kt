package com.morealm.app.presentation.profile

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.morealm.app.core.log.AppLog
import com.morealm.app.domain.db.ChapterDao
import com.morealm.app.domain.db.SearchBookCacheDao
import com.morealm.app.domain.entity.Book
import com.morealm.app.domain.entity.BookFormat
import com.morealm.app.domain.repository.AutoGroupClassifier
import com.morealm.app.domain.repository.BookRepository
import com.morealm.app.domain.repository.SearchRepository
import com.morealm.app.domain.repository.SourceRepository
import com.morealm.app.domain.parser.EpubMetadataWriter
import com.morealm.app.presentation.source.ChangeSourceCandidate
import com.morealm.app.presentation.source.ChangeSourceController
import com.morealm.app.presentation.source.ChangeSourceProgress
import com.morealm.app.presentation.source.SearchStatus
import android.content.Context
import android.net.Uri
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TEXT_BOOK_SOURCE_TYPE = 0

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

    // ── Change-source: delegated to controller ─────────────────────────────
    // BookDetailScreen still reads these fields by their old names; we keep
    // them as aliases so the UI layer stays untouched. The controller is
    // shared in spirit (not in instance) with ReaderViewModel — each
    // ViewModel owns its own to keep dialog state independent across screens.
    private val changeSource = ChangeSourceController(
        scope = viewModelScope,
        bookRepo = bookRepo,
        sourceRepo = sourceRepo,
        searchRepo = searchRepo,
        searchBookCacheDao = searchBookCacheDao,
        chapterDao = chapterDao,
    )

    val showSourcePicker: StateFlow<Boolean> = changeSource.showPicker
    val changeSourceCandidates: StateFlow<List<ChangeSourceCandidate>> = changeSource.candidates
    val changeSourceProgress: StateFlow<List<ChangeSourceProgress>> = changeSource.progress
    val changeSourceSearching: StateFlow<Boolean> = changeSource.searching

    init {
        viewModelScope.launch(Dispatchers.IO) {
            _book.value = bookRepo.getById(bookId)
            _enabledSourcesCount.value = sourceRepo.getEnabledSourcesList().count {
                it.bookSourceType == TEXT_BOOK_SOURCE_TYPE
            }
            // 老化清理：每次进入详情页捎带做一次，开销可忽略。
            changeSource.pruneStaleCache()
            // Smart router 把 web 书的"打开"导到详情页，所以"已经看到这本书"的徽章清除
            // 也得在这里发生，否则用户看完 N 新章节再退出，红字徽章还在。
            // failure-tolerant：DB 异常不影响详情页其它信息。
            if (bookId.isNotBlank()) {
                runCatching { bookRepo.clearLastCheckCount(bookId) }
            }
        }
    }

    // ── Change-source action delegates ──────────────────────────────────────

    fun showSourcePicker() {
        val current = _book.value ?: return
        changeSource.openPicker(current)
    }

    fun hideSourcePicker() {
        changeSource.closePicker()
    }

    fun cancelCrossSourceSearch() {
        changeSource.cancelSearch()
    }

    fun applyChangedSource(candidate: ChangeSourceCandidate) {
        val current = _book.value ?: return
        changeSource.applyCandidate(current, candidate) { updated ->
            // Mirror to local _book so detail UI re-renders with the new source's
            // metadata immediately. Persistence already happened inside the controller.
            _book.value = updated
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
