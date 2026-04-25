package com.morealm.app.presentation.shelf

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.morealm.app.domain.entity.Book
import com.morealm.app.domain.entity.BookFormat
import com.morealm.app.domain.entity.BookGroup
import com.morealm.app.domain.preference.AppPreferences
import com.morealm.app.domain.repository.AutoGroupClassifier
import com.morealm.app.domain.repository.BookRepository
import com.morealm.app.domain.repository.BookGroupRepository
import com.morealm.app.domain.parser.EpubParser
import com.morealm.app.domain.parser.PdfParser
import com.morealm.app.core.log.AppLog
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
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
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private companion object {
        const val MAX_FOLDER_IMPORT_DEPTH = 10
    }

    val lastReadBook: StateFlow<Book?> = bookRepo.getLastReadBook()
        .stateIn(viewModelScope, SharingStarted.Lazily, null)

    init {
        // On startup, check for books with stale cover paths (cache cleared)
        // and re-extract covers from the original files
        viewModelScope.launch(Dispatchers.IO) { refreshStaleCoverPaths() }
    }

    private suspend fun refreshStaleCoverPaths() {
        val allBooks = bookRepo.getAllBooksSync()
        for (book in allBooks) {
            val cover = book.coverUrl
            val canExtractCover = book.format == BookFormat.EPUB || book.format == BookFormat.PDF
            val needsRefresh = when {
                !canExtractCover -> false
                cover.isNullOrBlank() -> true
                cover.startsWith("/") -> !java.io.File(cover).exists()
                else -> false
            }
            if (!needsRefresh) continue

            val localPath = book.localPath ?: continue
            val uri = Uri.parse(localPath)
            val newCover = try {
                when (book.format) {
                    BookFormat.EPUB -> EpubParser.extractCover(context, uri)
                    BookFormat.PDF -> PdfParser.extractCover(context, uri)
                    else -> null
                }
            } catch (_: Exception) { null }
            if (newCover != null && newCover != cover) {
                bookRepo.update(book.copy(coverUrl = newCover))
            } else if (newCover == null && !cover.isNullOrBlank() && cover.startsWith("/")) {
                // Cover can't be re-extracted, clear the stale path
                bookRepo.update(book.copy(coverUrl = null))
            }
        }
    }

    val resumeLastRead: StateFlow<Boolean> = prefs.resumeLastRead
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _booksLoaded = MutableStateFlow(false)
    val booksLoaded: StateFlow<Boolean> = _booksLoaded.asStateFlow()

    private val _folderImportState = MutableStateFlow(FolderImportState())
    val folderImportState: StateFlow<FolderImportState> = _folderImportState.asStateFlow()

    fun clearFolderImportMessage() {
        val current = _folderImportState.value
        if (!current.running) _folderImportState.value = FolderImportState()
    }

    val allGroups: StateFlow<List<BookGroup>> = groupRepo.getAllGroups()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val groupNames: StateFlow<Map<String, String>> = groupRepo.getAllGroups()
        .map { groups -> groups.associate { it.id to it.name } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    private val _sortMode = MutableStateFlow("title")
    val sortMode: StateFlow<String> = _sortMode.asStateFlow()

    fun setSortMode(mode: String) { _sortMode.value = mode }

    /** All books as a simple Flow, sorted client-side */
    @OptIn(ExperimentalCoroutinesApi::class)
    val books: StateFlow<List<Book>> = _sortMode.flatMapLatest { sort ->
        bookRepo.getAllBooks().map { list -> sortBooks(list, sort) }
    }.onEach { _booksLoaded.value = true }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Folder book counts — derived from the books flow */
    val folderBookCounts: StateFlow<Map<String, Int>> = books
        .map { list ->
            list.filter { it.folderId != null }
                .groupBy { it.folderId!! }
                .mapValues { it.value.size }
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyMap())

    /** Folder cover URLs — first 4 cover URLs per folder for mosaic display */
    val folderCoverUrls: StateFlow<Map<String, List<String?>>> = books
        .map { list ->
            list.filter { it.folderId != null }
                .groupBy { it.folderId!! }
                .mapValues { entry ->
                    entry.value.take(4).map { it.coverUrl }
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

    fun importLocalBook(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            tryGrantPermission(uri)

            val docFile = DocumentFile.fromSingleUri(context, uri) ?: return@launch
            val name = docFile.name ?: "Unknown"
            val format = detectFormat(name)
            if (format == BookFormat.UNKNOWN) return@launch AppLog.warn("Import", "Unsupported format: $name")
            if (bookRepo.findByLocalPath(uri.toString()) != null) return@launch AppLog.info("Import", "Already imported: $name")

            val book = applyAutoGroup(buildBookFromFile(uri, name, format))
            bookRepo.insert(book)
            AppLog.info("Import", "Imported: ${book.title} ($format)")
        }
    }

    private fun buildBookFromFile(uri: Uri, fileName: String, format: BookFormat): Book {
        val parsed = parseBookFilename(fileName)
        val baseName = parsed.first
        val author = parsed.second
        return when (format) {
            BookFormat.EPUB -> try {
                val result = EpubParser.extractMetadataAndCover(context, uri)
                Book(
                    id = UUID.randomUUID().toString(),
                    title = result.metadata.title.ifBlank { baseName },
                    author = result.metadata.author.ifBlank { author },
                    description = result.metadata.description.ifBlank { null },
                    localPath = uri.toString(), format = format,
                    coverUrl = result.coverPath, addedAt = System.currentTimeMillis(),
                )
            } catch (_: Exception) {
                Book(id = UUID.randomUUID().toString(), title = baseName, author = author,
                    localPath = uri.toString(), format = format, addedAt = System.currentTimeMillis())
            }
            BookFormat.PDF -> {
                val cover = try { PdfParser.extractCover(context, uri) } catch (_: Exception) { null }
                Book(id = UUID.randomUUID().toString(), title = baseName, author = author,
                    localPath = uri.toString(), format = format,
                    coverUrl = cover, addedAt = System.currentTimeMillis())
            }
            else -> Book(id = UUID.randomUUID().toString(), title = baseName, author = author,
                localPath = uri.toString(), format = format, addedAt = System.currentTimeMillis())
        }
    }

    /**
     * Smart filename parsing — extract title and author from common patterns:
     * - "书名 - 作者.txt"
     * - "作者 - 书名.txt"  (if author part looks like a name)
     * - "[作者] 书名.epub"
     * - "书名(作者).mobi"
     * - "书名_作者.txt"
     */
    private fun parseBookFilename(fileName: String): Pair<String, String> {
        val base = fileName.substringBeforeLast('.').trim()

        // Pattern: [作者] 书名 or 【作者】书名
        val bracketMatch = Regex("^[\\[【](.+?)[\\]】]\\s*(.+)$").find(base)
        if (bracketMatch != null) {
            return bracketMatch.groupValues[2].trim() to bracketMatch.groupValues[1].trim()
        }

        // Pattern: 书名(作者) or 书名（作者）
        val parenMatch = Regex("^(.+?)[（(](.+?)[）)]$").find(base)
        if (parenMatch != null) {
            return parenMatch.groupValues[1].trim() to parenMatch.groupValues[2].trim()
        }

        // Pattern: title - author or author - title (split by " - ", " _ ", " — ")
        val sepMatch = Regex("^(.+?)\\s*[-_—]\\s*(.+)$").find(base)
        if (sepMatch != null) {
            val left = sepMatch.groupValues[1].trim()
            val right = sepMatch.groupValues[2].trim()
            // Heuristic: shorter part is likely the author
            return if (left.length <= right.length && left.length <= 6) {
                right to left
            } else {
                left to right
            }
        }

        return base to ""
    }

    fun importFolder(uri: Uri) {
        _folderImportState.value = FolderImportState(running = true, message = "正在打开文件夹…")
        viewModelScope.launch(Dispatchers.IO) {
            val startTime = System.currentTimeMillis()
            tryGrantPermission(uri)
            try {
                val tree = DocumentFile.fromTreeUri(context, uri)
                    ?: run {
                        _folderImportState.value = FolderImportState(message = "文件夹打开失败", error = "无法读取所选文件夹")
                        AppLog.error("Import", "Failed to open folder: $uri")
                        return@launch
                    }
                val folderName = tree.name ?: "导入文件夹"
                _folderImportState.value = FolderImportState(running = true, folderName = folderName, message = "正在扫描：$folderName")
                AppLog.info("Import", "Scanning folder: $folderName")

                val allChildren = tree.listFiles()
                val subFolders = allChildren.filter { it.isDirectory }
                val directFiles = allChildren.filter { !it.isDirectory && detectFormat(it.name ?: "") != BookFormat.UNKNOWN }
                AppLog.info("Import", "Found ${subFolders.size} sub-folders, ${directFiles.size} direct files")

                val importedCount = when {
                    subFolders.isNotEmpty() -> {
                        _folderImportState.value = FolderImportState(running = true, folderName = folderName, message = "正在导入 ${subFolders.size} 个子文件夹…")
                        val subCount = importSubFolders(subFolders)
                        val directCount = if (directFiles.isNotEmpty()) importAsGroup(directFiles, folderName) else 0
                        subCount + directCount
                    }
                    directFiles.isNotEmpty() -> importAsGroup(directFiles, folderName)
                    else -> importDeepScan(tree, folderName)
                }

                _folderImportState.value = FolderImportState(
                    folderName = folderName,
                    importedCount = importedCount,
                    message = if (importedCount > 0) "已导入 $importedCount 本书" else "没有发现可导入的书籍",
                )
                AppLog.info("Import", "Folder import '$folderName' completed in ${System.currentTimeMillis() - startTime}ms")
            } catch (e: Exception) {
                _folderImportState.value = FolderImportState(message = "导入失败", error = e.message ?: "未知错误")
                AppLog.error("Import", "Folder import failed: ${e.message}", e)
            }
        }
    }

    private fun tryGrantPermission(uri: Uri) {
        try {
            context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: Exception) {
            AppLog.warn("Import", "Permission grant failed: ${e.message}")
        }
    }

    private data class FolderChildren(
        val directFiles: List<DocumentFile>,
        val subFolders: List<DocumentFile>,
    )

    private suspend fun importSubFolders(subFolders: List<DocumentFile>, depth: Int = 0): Int {
        if (depth > MAX_FOLDER_IMPORT_DEPTH) return 0
        var importedCount = 0
        for (folder in subFolders) {
            val folderName = folder.name ?: "文件夹"
            val children = scanDirectChildren(folder)

            if (children.directFiles.isNotEmpty()) {
                importedCount += importAsGroup(children.directFiles, folderName)
                _folderImportState.value = _folderImportState.value.copy(importedCount = importedCount)
            }

            if (children.subFolders.isEmpty()) continue

            _folderImportState.value = _folderImportState.value.copy(
                message = "正在扫描：$folderName（${children.subFolders.size} 个子文件夹）",
            )
            importedCount += importSubFolders(children.subFolders, depth + 1)
            _folderImportState.value = _folderImportState.value.copy(importedCount = importedCount)
        }
        return importedCount
    }

    private fun scanDirectChildren(folder: DocumentFile): FolderChildren {
        val children = folder.listFiles()
        return FolderChildren(
            directFiles = children.filter { !it.isDirectory && detectFormat(it.name ?: "") != BookFormat.UNKNOWN },
            subFolders = children.filter { it.isDirectory },
        )
    }

    private suspend fun importAsGroup(files: List<DocumentFile>, groupName: String): Int {
        val importableFiles = files.filter { file ->
            val name = file.name ?: return@filter false
            detectFormat(name) != BookFormat.UNKNOWN && bookRepo.findByLocalPath(file.uri.toString()) == null
        }
        if (importableFiles.isEmpty()) return 0

        _folderImportState.value = _folderImportState.value.copy(message = "正在导入：$groupName（${importableFiles.size} 个文件）")
        val groupId = UUID.randomUUID().toString()
        groupRepo.insert(BookGroup(id = groupId, name = groupName))
        val importedCount = importFilesWithDeferredCovers(importableFiles, groupId)
        if (importedCount == 0) {
            groupRepo.deleteById(groupId)
        }
        _folderImportState.value = _folderImportState.value.copy(importedCount = importedCount)
        return importedCount
    }

    private suspend fun importDeepScan(tree: DocumentFile, folderName: String): Int {
        _folderImportState.value = _folderImportState.value.copy(message = "正在深度扫描：$folderName")
        val allFiles = mutableListOf<DocumentFile>()
        collectBookFiles(tree, allFiles, maxDepth = 10)
        if (allFiles.isEmpty()) return 0
        return importAsGroup(allFiles, folderName)
    }

    private suspend fun importFilesWithDeferredCovers(files: List<DocumentFile>, folderId: String): Int {
        AppLog.info("Import", "Processing ${files.size} files for group $folderId")

        data class PendingBook(val book: Book, val file: DocumentFile, val format: BookFormat)
        val pending = mutableListOf<PendingBook>()

        for ((idx, file) in files.withIndex()) {
            val name = file.name ?: continue
            val format = detectFormat(name)
            if (format == BookFormat.UNKNOWN) continue
            if (bookRepo.findByLocalPath(file.uri.toString()) != null) continue

            val book = applyAutoGroup(Book(
                id = UUID.randomUUID().toString(),
                title = name.substringBeforeLast('.'),
                localPath = file.uri.toString(),
                format = format,
                folderId = folderId,
                addedAt = System.currentTimeMillis() + idx,
            ))
            bookRepo.insert(book)
            pending.add(PendingBook(book, file, format))
        }
        AppLog.info("Import", "${pending.size} books added to shelf")

        for (pb in pending) {
            try {
                val updated = when (pb.format) {
                    BookFormat.EPUB -> {
                        val result = EpubParser.extractMetadataAndCover(context, pb.file.uri)
                        pb.book.copy(
                            title = result.metadata.title.ifBlank { pb.book.title },
                            author = result.metadata.author,
                            description = result.metadata.description.ifBlank { null },
                            coverUrl = result.coverPath,
                        )
                    }
                    BookFormat.PDF -> pb.book.copy(
                        coverUrl = PdfParser.extractCover(context, pb.file.uri),
                    )
                    else -> continue
                }
                bookRepo.update(applyAutoGroup(updated))
            } catch (e: Exception) {
                AppLog.warn("Import", "Metadata failed: ${pb.book.title} - ${e.message}")
            }
        }
        AppLog.info("Import", "All metadata extracted")
        return pending.size
    }

    private fun collectBookFiles(dir: DocumentFile, out: MutableList<DocumentFile>, maxDepth: Int, depth: Int = 0) {
        if (depth > maxDepth) return
        dir.listFiles().forEach { file ->
            if (file.isDirectory) {
                collectBookFiles(file, out, maxDepth, depth + 1)
            } else {
                val name = file.name ?: return@forEach
                if (detectFormat(name) != BookFormat.UNKNOWN) {
                    out.add(file)
                }
            }
        }
    }

    data class FileInfo(val uri: Uri, val name: String, val isDir: Boolean)

    private fun listFilesFast(treeUri: Uri): List<FileInfo> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri, DocumentsContract.getDocumentId(treeUri)
        )
        val results = mutableListOf<FileInfo>()
        var cursor: Cursor? = null
        try {
            cursor = context.contentResolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                ),
                null, null, null
            )
            cursor?.use {
                while (it.moveToNext()) {
                    val docId = it.getString(0)
                    val name = it.getString(1) ?: continue
                    val mime = it.getString(2) ?: ""
                    val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR
                    val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                    results.add(FileInfo(docUri, name, isDir))
                }
            }
        } catch (e: Exception) {
            AppLog.error("Import", "Fast list failed, falling back", e)
        }
        return results
    }

    fun searchBooks(keyword: String, onResult: (List<Book>) -> Unit) {
        viewModelScope.launch {
            val results = kotlinx.coroutines.withContext(Dispatchers.IO) {
                bookRepo.searchBooks(keyword)
            }
            onResult(results)
        }
    }

    fun deleteFolder(folderId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            bookRepo.deleteFolder(folderId)
            AppLog.info("Shelf", "Deleted folder: $folderId")
        }
    }

    fun batchDelete(bookIds: Set<String>) {
        viewModelScope.launch(Dispatchers.IO) {
            bookIds.forEach { bookRepo.deleteById(it) }
            AppLog.info("Shelf", "Batch deleted ${bookIds.size} books")
        }
    }

    fun createGroup(name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val group = BookGroup(
                id = UUID.randomUUID().toString(),
                name = name,
                sortOrder = (allGroups.value.maxOfOrNull { it.sortOrder } ?: 0) + 1,
            )
            groupRepo.insert(group)
            AppLog.info("Shelf", "Created group: $name")
        }
    }

    fun createGroup(name: String, autoKeywords: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val group = BookGroup(
                id = UUID.randomUUID().toString(),
                name = name,
                autoKeywords = autoKeywords,
                sortOrder = (allGroups.value.maxOfOrNull { it.sortOrder } ?: 0) + 1,
            )
            groupRepo.insert(group)
            reclassifyUngroupedBooks()
            AppLog.info("Shelf", "Created group: $name")
        }
    }

    fun renameGroup(groupId: String, newName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val group = groupRepo.getById(groupId) ?: return@launch
            groupRepo.insert(group.copy(name = newName))
            AppLog.info("Shelf", "Renamed group $groupId to $newName")
        }
    }

    fun updateGroup(groupId: String, newName: String, autoKeywords: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val group = groupRepo.getById(groupId) ?: return@launch
            groupRepo.insert(group.copy(name = newName, autoKeywords = autoKeywords))
            reclassifyUngroupedBooks()
            AppLog.info("Shelf", "Updated group $groupId")
        }
    }

    fun moveToGroup(bookIds: Set<String>, groupId: String?) {
        viewModelScope.launch(Dispatchers.IO) {
            bookIds.forEach { id ->
                val book = bookRepo.getById(id) ?: return@forEach
                bookRepo.update(book.copy(folderId = groupId))
            }
            AppLog.info("Shelf", "Moved ${bookIds.size} books to group $groupId")
        }
    }

    private suspend fun filterNewBooks(books: List<Book>): List<Book> {
        return books.filter { bookRepo.findByLocalPath(it.localPath ?: "") == null }
    }

    private fun detectFormat(filename: String): BookFormat {
        val ext = filename.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "txt" -> BookFormat.TXT
            "epub" -> BookFormat.EPUB
            "pdf" -> BookFormat.PDF
            "mobi" -> BookFormat.MOBI
            "azw3", "azw" -> BookFormat.AZW3
            "cbz", "zip", "cbr", "rar", "7z" -> BookFormat.CBZ
            "umd" -> BookFormat.UMD
            else -> BookFormat.UNKNOWN
        }
    }

    // ── Cache/download delegation ──

    val isCacheDownloading: StateFlow<Boolean> = cacheRepo.isDownloading
    val cacheDownloadProgress = cacheRepo.downloadProgress

    fun startCacheBook(bookId: String, sourceUrl: String) {
        cacheRepo.startDownload(bookId, sourceUrl)
    }

    fun stopCacheBook() {
        cacheRepo.stopDownload()
    }

    fun reclassifyUngroupedBooks() {
        viewModelScope.launch(Dispatchers.IO) {
            val groups = groupRepo.getAllGroupsSync()
            var moved = 0
            bookRepo.getAllBooksSync().filter { it.folderId == null }.forEach { book ->
                val target = autoGroupClassifier.matchGroup(book, groups)?.id ?: return@forEach
                bookRepo.update(book.copy(folderId = target))
                moved++
            }
            AppLog.info("Shelf", "Auto grouped $moved books")
        }
    }

    private suspend fun applyAutoGroup(book: Book): Book {
        val groupId = autoGroupClassifier.classify(book)
        return if (groupId != null && book.folderId == null) book.copy(folderId = groupId) else book
    }
}
