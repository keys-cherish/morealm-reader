package com.morealm.app.presentation.shelf

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.morealm.app.domain.entity.Book
import com.morealm.app.domain.entity.BookGroup
import com.morealm.app.domain.preference.AppPreferences
import com.morealm.app.domain.repository.AutoGroupClassifier
import com.morealm.app.domain.repository.BookRepository
import com.morealm.app.domain.repository.BookGroupRepository
import com.morealm.app.domain.repository.SourceRepository
import com.morealm.app.core.log.AppLog
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class FolderImportState(
    val running: Boolean = false,
    val folderName: String = "",
    val importedCount: Int = 0,
    val message: String = "",
    val error: String? = null,
)

@HiltViewModel
class ShelfViewModel @Inject constructor(
    private val bookRepo: BookRepository,
    private val groupRepo: BookGroupRepository,
    private val autoGroupClassifier: AutoGroupClassifier,
    private val prefs: AppPreferences,
    private val cacheRepo: com.morealm.app.domain.repository.CacheRepository,
    private val refreshController: ShelfRefreshController,
    private val databaseSeeder: com.morealm.app.domain.db.DatabaseSeeder,
    private val sourceRepo: SourceRepository,
    private val coverStorage: com.morealm.app.domain.cover.CoverStorage,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    // ── Extracted Controllers ──
    val import = ShelfImportController(
        bookRepo = bookRepo,
        groupRepo = groupRepo,
        autoGroupClassifier = autoGroupClassifier,
        context = context,
        scope = viewModelScope,
    )

    val organize = ShelfOrganizeController(
        bookRepo = bookRepo,
        groupRepo = groupRepo,
        autoGroupClassifier = autoGroupClassifier,
        sourceRepo = sourceRepo,
        scope = viewModelScope,
    )

    val lastReadBook: StateFlow<Book?> = bookRepo.getLastReadBook()
        .stateIn(viewModelScope, SharingStarted.Lazily, null)

    init {
        viewModelScope.launch(Dispatchers.IO) {
            try { databaseSeeder.seedIfNeeded() } catch (e: Exception) {
                AppLog.warn("Shelf", "Tag seeder failed: ${e.message}")
            }
        }
    }

    val resumeLastRead: StateFlow<Boolean> = prefs.resumeLastRead
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun resumeLastRead(onNavigate: (String) -> Unit) {
        viewModelScope.launch {
            val book = lastReadBook.value ?: return@launch
            onNavigate(book.id)
        }
    }

    private val _booksLoaded = MutableStateFlow(false)
    val booksLoaded: StateFlow<Boolean> = _booksLoaded.asStateFlow()

    val folderImportState: StateFlow<FolderImportState> = import.folderImportState
    fun clearFolderImportMessage() = import.clearFolderImportMessage()

    val allGroups: StateFlow<List<BookGroup>> = groupRepo.getAllGroups()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val groupNames: StateFlow<Map<String, String>> = allGroups
        .map { groups -> groups.associate { it.id to it.name } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    private val _navigateToFolder = MutableSharedFlow<String?>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    val navigateToFolder: SharedFlow<String?> = _navigateToFolder.asSharedFlow()

    fun requestNavigateToFolder(folderId: String?) {
        viewModelScope.launch { _navigateToFolder.emit(folderId) }
    }

    private val _sortMode = MutableStateFlow("title")
    val sortMode: StateFlow<String> = _sortMode.asStateFlow()

    fun setSortMode(mode: String) { _sortMode.value = mode }

    @OptIn(ExperimentalCoroutinesApi::class)
    val books: StateFlow<List<Book>> = _sortMode.flatMapLatest { sort ->
        bookRepo.getAllBooks().map { list ->
            withContext(Dispatchers.Default) {
                sortBooks(list, sort)
            }
        }
    }.onEach { _booksLoaded.value = true }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val folderBookCounts: StateFlow<Map<String, Int>> = books
        .map { list ->
            withContext(Dispatchers.Default) {
                list.filter { it.folderId != null }
                    .groupBy { it.folderId!! }
                    .mapValues { it.value.size }
            }
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyMap())

    val folderCoverUrls: StateFlow<Map<String, List<String?>>> = books
        .map { list ->
            withContext(Dispatchers.Default) {
                list.filter { it.folderId != null }
                    .groupBy { it.folderId!! }
                    .mapValues { entry ->
                        entry.value.take(4).map { it.coverUrl }
                    }
            }
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyMap())

    private fun sortBooks(list: List<Book>, sort: String): List<Book> = when (sort) {
        "recent" -> list.sortedByDescending { it.lastReadAt }
        "addTime" -> list.sortedByDescending { it.addedAt }
        "format" -> list.sortedWith(compareBy<Book> { it.format.name }.thenBy { it.title.lowercase() })
        else -> list.sortedBy { it.title.lowercase() }
    }

    fun togglePinBook(bookId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val book = bookRepo.getById(bookId) ?: return@launch
            bookRepo.update(book.copy(pinned = !book.pinned))
        }
    }

    fun togglePinFolder(folderId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val group = groupRepo.getById(folderId) ?: return@launch
            groupRepo.insert(group.copy(pinned = !group.pinned))
        }
    }

    fun setCustomBookCover(bookId: String, sourceUri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val book = bookRepo.getById(bookId) ?: return@launch
            val savedUri = coverStorage.saveCover(
                sourceUri,
                com.morealm.app.domain.cover.CoverKind.BOOK,
                bookId,
            ) ?: return@launch
            bookRepo.update(book.copy(customCoverUrl = savedUri))
        }
    }

    fun clearCustomBookCover(bookId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val book = bookRepo.getById(bookId) ?: return@launch
            coverStorage.deleteCover(com.morealm.app.domain.cover.CoverKind.BOOK, bookId)
            bookRepo.update(book.copy(customCoverUrl = null))
        }
    }

    fun setCustomGroupCover(groupId: String, sourceUri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val group = groupRepo.getById(groupId) ?: return@launch
            val savedUri = coverStorage.saveCover(
                sourceUri,
                com.morealm.app.domain.cover.CoverKind.GROUP,
                groupId,
            ) ?: return@launch
            groupRepo.insert(group.copy(customCoverUrl = savedUri))
        }
    }

    fun clearCustomGroupCover(groupId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val group = groupRepo.getById(groupId) ?: return@launch
            coverStorage.deleteCover(com.morealm.app.domain.cover.CoverKind.GROUP, groupId)
            groupRepo.insert(group.copy(customCoverUrl = null))
        }
    }

    fun importLocalBook(uri: Uri) = import.importLocalBook(uri)
    fun importFolder(uri: Uri) = import.importFolder(uri)

    // ── Search (Flow-based) ──
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val searchResults: StateFlow<List<Book>> = _searchQuery
        .debounce(300)
        .flatMapLatest { q ->
            if (q.isBlank()) flowOf(emptyList())
            else flow { emit(withContext(Dispatchers.IO) { bookRepo.searchBooks(q) }) }
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun setSearchQuery(query: String) { _searchQuery.value = query }

    fun deleteFolder(folderId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val group = groupRepo.getById(folderId)
            if (group?.auto == true && folderId.startsWith("auto:")) {
                val tagId = folderId.removePrefix("auto:")
                prefs.addAutoFolderIgnored(tagId)
                AppLog.info("Shelf", "Ignoring future auto-folder for tag $tagId")
            }
            coverStorage.deleteCover(com.morealm.app.domain.cover.CoverKind.GROUP, folderId)
            bookRepo.deleteFolder(folderId)
            AppLog.info("Shelf", "Deleted folder: $folderId")
        }
    }

    fun batchDelete(bookIds: Set<String>) {
        viewModelScope.launch(Dispatchers.IO) {
            bookIds.forEach { id ->
                coverStorage.deleteCover(com.morealm.app.domain.cover.CoverKind.BOOK, id)
                bookRepo.deleteById(id)
            }
            AppLog.info("Shelf", "Batch deleted ${bookIds.size} books")
        }
    }

    fun batchDeleteSoft(bookIds: Set<String>) {
        viewModelScope.launch(Dispatchers.IO) {
            bookIds.forEach { id -> bookRepo.deleteById(id) }
            AppLog.info("Shelf", "Batch soft-deleted ${bookIds.size} books (covers retained)")
        }
    }

    fun restoreBooks(books: List<Book>) {
        if (books.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                bookRepo.insertAll(books)
                AppLog.info("Shelf", "Restored ${books.size} books")
            } catch (e: Exception) {
                AppLog.warn("Shelf", "Restore failed: ${e.message}")
            }
        }
    }

    fun commitCoverDeletion(bookIds: Set<String>) {
        if (bookIds.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            bookIds.forEach { id ->
                coverStorage.deleteCover(com.morealm.app.domain.cover.CoverKind.BOOK, id)
            }
        }
    }

    fun createGroup(name: String, keywords: String = "") {
        viewModelScope.launch(Dispatchers.IO) {
            val nextOrder = (groupRepo.getAllGroupsSync().maxOfOrNull { it.sortOrder } ?: 0) + 1
            groupRepo.insert(
                BookGroup(
                    id = java.util.UUID.randomUUID().toString(),
                    name = name,
                    sortOrder = nextOrder,
                    autoKeywords = keywords.takeIf { it.isNotBlank() } ?: "",
                )
            )
        }
    }

    fun renameGroup(groupId: String, newName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val group = groupRepo.getById(groupId) ?: return@launch
            groupRepo.insert(group.copy(name = newName))
        }
    }

    fun updateGroup(groupId: String, newName: String, keywords: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val group = groupRepo.getById(groupId) ?: return@launch
            groupRepo.insert(group.copy(name = newName, autoKeywords = keywords.takeIf { it.isNotBlank() } ?: ""))
        }
    }

    fun updateGroup(group: BookGroup) {
        viewModelScope.launch(Dispatchers.IO) {
            groupRepo.insert(group)
        }
    }

    fun moveToGroup(bookIds: Set<String>, targetFolderId: String?) {
        viewModelScope.launch(Dispatchers.IO) {
            bookIds.forEach { id ->
                val book = bookRepo.getById(id) ?: return@forEach
                bookRepo.update(book.copy(folderId = targetFolderId))
            }
        }
    }

    fun reclassifyUngroupedBooks() = organize.reclassifyUngroupedBooks()

    val isOrganizing: StateFlow<Boolean> = organize.isOrganizing
    val organizeReport: StateFlow<String?> = organize.organizeReport
    fun consumeOrganizeReport() = organize.consumeOrganizeReport()

    fun organizeShelf() = organize.organizeShelf()

    // ── Refresh ──
    val isRefreshing: StateFlow<Boolean> = refreshController.isRefreshing
    val refreshProgress: StateFlow<Pair<Int, Int>> = refreshController.progress
    val refreshErrorCount: StateFlow<Int> = refreshController.errorCount

    fun refreshAllBooks() = refreshController.refresh(books.value)
    fun cancelRefresh() = refreshController.cancel()

    fun clearNewChapterBadge(bookId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            bookRepo.clearLastCheckCount(bookId)
        }
    }

    // ── Cache download ──
    val isCacheDownloading: StateFlow<Boolean> = cacheRepo.isDownloading
    val downloadProgress: StateFlow<com.morealm.app.service.CacheBookService.DownloadProgress> =
        cacheRepo.progresses.map { it.values.firstOrNull() ?: com.morealm.app.service.CacheBookService.DownloadProgress() }
            .stateIn(viewModelScope, SharingStarted.Lazily, com.morealm.app.service.CacheBookService.DownloadProgress())

    fun startCacheBook(bookId: String, sourceUrl: String) {
        cacheRepo.startDownload(bookId, sourceUrl)
    }

    fun stopCacheBook() {
        cacheRepo.stopDownload()
    }

    // ── Update badges ──
    val groupHasUpdate: StateFlow<Map<String, Boolean>> = books
        .map { list ->
            withContext(Dispatchers.Default) {
                list.asSequence()
                    .filter { it.folderId != null && it.lastCheckCount > 0 }
                    .map { it.folderId!! }
                    .toSet()
                    .associateWith { true }
            }
        }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    val hasAnyUpdate: StateFlow<Boolean> = books
        .map { list -> list.any { it.lastCheckCount > 0 } }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // ── Auto-refresh on cold start ──
    init {
        viewModelScope.launch {
            books.first { it.isNotEmpty() }
            delay(5_000L)
            refreshAllBooks()
        }
    }
}
