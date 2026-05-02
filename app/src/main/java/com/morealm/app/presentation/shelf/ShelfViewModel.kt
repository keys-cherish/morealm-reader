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
import com.morealm.app.domain.entity.SearchBook
import com.morealm.app.domain.preference.AppPreferences
import com.morealm.app.domain.repository.AutoGroupClassifier
import com.morealm.app.domain.repository.BookRepository
import com.morealm.app.domain.repository.BookGroupRepository
import com.morealm.app.domain.repository.SourceRepository
import com.morealm.app.domain.parser.EpubParser
import com.morealm.app.domain.parser.PdfParser
import com.morealm.app.domain.webbook.WebBook
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.yield
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
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
    private val refreshController: ShelfRefreshController,
    private val databaseSeeder: com.morealm.app.domain.db.DatabaseSeeder,
    private val sourceRepo: SourceRepository,
    private val coverStorage: com.morealm.app.domain.cover.CoverStorage,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private companion object {
        const val MAX_FOLDER_IMPORT_DEPTH = 10
    }

    val lastReadBook: StateFlow<Book?> = bookRepo.getLastReadBook()
        .stateIn(viewModelScope, SharingStarted.Lazily, null)

    init {
        // Seed built-in genre tags once per install (idempotent — no-op if seeded).
        viewModelScope.launch(Dispatchers.IO) {
            try { databaseSeeder.seedIfNeeded() } catch (e: Exception) {
                AppLog.warn("Shelf", "Tag seeder failed: ${e.message}")
            }
        }
        // Do not parse EPUB/PDF covers during the launch critical path. LDPlayer
        // reports null UI roots while startup restore competes with heavy cover
        // extraction, so refresh stale covers only after the shelf has had time
        // to draw.
        viewModelScope.launch(Dispatchers.IO) {
            delay(8_000L)
            refreshStaleCoverPaths(maxPerLaunch = 12)
        }
    }

    private suspend fun refreshStaleCoverPaths(maxPerLaunch: Int) {
        val allBooks = bookRepo.getAllBooksSync()
        var attempted = 0
        for (book in allBooks) {
            if (attempted >= maxPerLaunch) break
            yield()
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
            attempted++
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
        .map { groups ->
            withContext(Dispatchers.Default) {
                groups.associate { it.id to it.name }
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    private val _sortMode = MutableStateFlow("title")
    val sortMode: StateFlow<String> = _sortMode.asStateFlow()

    fun setSortMode(mode: String) { _sortMode.value = mode }

    /** All books as a simple Flow, sorted client-side */
    @OptIn(ExperimentalCoroutinesApi::class)
    val books: StateFlow<List<Book>> = _sortMode.flatMapLatest { sort ->
        bookRepo.getAllBooks().map { list ->
            withContext(Dispatchers.Default) {
                sortBooks(list, sort)
            }
        }
    }.onEach { _booksLoaded.value = true }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Folder book counts — derived from the books flow */
    val folderBookCounts: StateFlow<Map<String, Int>> = books
        .map { list ->
            withContext(Dispatchers.Default) {
                list.filter { it.folderId != null }
                    .groupBy { it.folderId!! }
                    .mapValues { it.value.size }
            }
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyMap())

    /** Folder cover URLs — first 4 cover URLs per folder for mosaic display */
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

    // ── 自定义封面（书籍 + 分组） ──
    //
    // 数据流：相册选图 → CoverStorage 异步处理（IO 线程降采样 + WebP 压缩 + 写文件）
    // → 返回 file:// URI → 写入对应 entity 的 customCoverUrl 字段。Compose 通过
    // bookRepo.getAllBooks() / groupRepo.getAllGroups() Flow 自动订阅刷新。
    //
    // 失败处理：CoverStorage.saveCover 返回 null 时不动 DB，UI 仍展示原 coverUrl。

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
                    kind = result.metadata.subject.ifBlank { null },
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
                            kind = result.metadata.subject.ifBlank { pb.book.kind },
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
            // If the folder was auto-created, remember its source tag so the
            // classifier doesn't recreate it next time matching books appear.
            // Auto-folder ids have the shape "auto:<tagId>" — strip the prefix.
            val group = groupRepo.getById(folderId)
            if (group?.auto == true && folderId.startsWith("auto:")) {
                val tagId = folderId.removePrefix("auto:")
                prefs.addAutoFolderIgnored(tagId)
                AppLog.info("Shelf", "Ignoring future auto-folder for tag $tagId")
            }
            // 删除分组时一并清理自定义封面文件（DB 级联由 bookRepo.deleteFolder 处理）
            coverStorage.deleteCover(com.morealm.app.domain.cover.CoverKind.GROUP, folderId)
            bookRepo.deleteFolder(folderId)
            AppLog.info("Shelf", "Deleted folder: $folderId")
        }
    }

    fun batchDelete(bookIds: Set<String>) {
        viewModelScope.launch(Dispatchers.IO) {
            // 删书时连带清理自定义封面文件，避免 filesDir/covers/BOOK/ 越积越大
            bookIds.forEach { id ->
                coverStorage.deleteCover(com.morealm.app.domain.cover.CoverKind.BOOK, id)
                bookRepo.deleteById(id)
            }
            AppLog.info("Shelf", "Batch deleted ${bookIds.size} books")
        }
    }

    /**
     * 软删除：仅从 DB 移除 Book，自定义封面文件留着。配合 UI Snackbar 撤销用：
     *  - 用户撤销 → 调 [restoreBooks]，封面还在，无副作用
     *  - 用户不撤销，Snackbar 自然消失 → UI 端再调 [commitCoverDeletion] 清理封面
     *
     * 这样设计避免「删了再撤销 → 封面丢了」的体验断层。代价是封面文件可能短暂滞留
     * (Snackbar 5s 内)，从存储清理角度可接受。
     */
    fun batchDeleteSoft(bookIds: Set<String>) {
        viewModelScope.launch(Dispatchers.IO) {
            bookIds.forEach { id -> bookRepo.deleteById(id) }
            AppLog.info("Shelf", "Batch soft-deleted ${bookIds.size} books (covers retained)")
        }
    }

    /**
     * 撤销 [batchDeleteSoft]：UI 在删除前 snapshot Book 列表，撤销时把整批 re-insert。
     * 走 [BookRepository.insertAll] 一次性写入避免 N 次事务。
     * 失败只打 warn，已经在用户视野外的"撤销"出错没必要 toast 干扰。
     */
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

    /**
     * 「软删除」的最终化：Snackbar 消失（用户没撤销）后调用，把封面文件物理删除。
     * 必须在 [restoreBooks] 不会被调用的时机才能跑，否则用户撤销后看到默认封面。
     */
    fun commitCoverDeletion(bookIds: Set<String>) {
        if (bookIds.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            bookIds.forEach { id ->
                coverStorage.deleteCover(com.morealm.app.domain.cover.CoverKind.BOOK, id)
            }
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

    private val _isOrganizing = MutableStateFlow(false)
    /** True while a full-shelf classify pass is in flight. UI shows a spinner. */
    val isOrganizing: StateFlow<Boolean> = _isOrganizing.asStateFlow()

    private val _organizeReport = MutableStateFlow<String?>(null)
    /** Last "moved N, created M folders" message — UI shows it as a snackbar then clears. */
    val organizeReport: StateFlow<String?> = _organizeReport.asStateFlow()
    fun consumeOrganizeReport() { _organizeReport.value = null }

    /**
     * "Organize shelf now" — re-runs [AutoGroupClassifier.classify] for every
     * book that hasn't been manually pinned, letting [AutoFolderManager]
     * promote any genres that have crossed the threshold since last pass.
     *
     * Skipped books:
     *   - `groupLocked = true` (user said hands off)
     *   - `tagsAssignedBy = MANUAL` AND `folderId != null` (user moved it to a folder by hand)
     *
     * The pass is idempotent — running twice in a row produces the same
     * shelf, just refreshes book_tags scores against latest metadata.
     */
    fun organizeShelf() {
        if (_isOrganizing.value) return
        viewModelScope.launch(Dispatchers.IO) {
            _isOrganizing.value = true
            try {
                val before = groupRepo.getAllGroupsSync().count { it.auto }

                // ── Stage A：metadata 预补全 ──
                // 部分 web 书源详情解析失败导致 kind/category/description 全空，
                // TagResolver 没有任何关键词字段可匹配 GENRE。先批量调
                // WebBook.getBookInfoAwait 重抓详情（仅对三字段都 blank 的
                // WEB 书），避免后续分类阶段全靠 title 撞关键词。
                val sparse = bookRepo.getAllBooksSync().filter { b ->
                    b.format == BookFormat.WEB &&
                        !b.groupLocked &&
                        b.kind.isNullOrBlank() &&
                        b.category.isNullOrBlank() &&
                        b.description.isNullOrBlank() &&
                        !b.sourceUrl.isNullOrBlank()
                }
                val refreshed = if (sparse.isNotEmpty()) prefetchWebMetadata(sparse) else 0

                // ── Stage B：分类 + 自动建组（含 source 兜底）──
                var touched = 0
                for (book in bookRepo.getAllBooksSync()) {
                    if (book.groupLocked) continue
                    if (book.folderId != null && book.tagsAssignedBy == "MANUAL") continue
                    val newId = autoGroupClassifier.classify(book)
                    if (newId != book.folderId) {
                        bookRepo.update(book.copy(folderId = newId))
                        touched++
                    }
                }

                // ── Stage C：保底「无法识别」分组 ──
                // 仍 folderId == null 的 WEB 书统一塞入 auto:unrecognized，
                // 让用户在书架上能看到这批被算法漏掉的书，而不是默默留在根目录。
                val orphans = bookRepo.getAllBooksSync().filter { b ->
                    b.format == BookFormat.WEB &&
                        b.folderId == null &&
                        !b.groupLocked &&
                        b.tagsAssignedBy != "MANUAL"
                }
                var rescued = 0
                if (orphans.isNotEmpty()) {
                    val unId = "auto:unrecognized"
                    if (groupRepo.getById(unId) == null) {
                        val nextOrder = (groupRepo.getAllGroupsSync().maxOfOrNull { it.sortOrder } ?: 0) + 1
                        groupRepo.insert(
                            BookGroup(
                                id = unId,
                                name = "无法识别",
                                emoji = "❓",
                                sortOrder = nextOrder,
                                auto = true,
                            )
                        )
                    }
                    orphans.forEach {
                        bookRepo.update(it.copy(folderId = unId))
                        rescued++
                    }
                }

                val after = groupRepo.getAllGroupsSync().count { it.auto }
                val newFolders = (after - before).coerceAtLeast(0)
                // 报告聚合：拼出「补全 X / 移动 Y / 收纳 Z / 新建 N」之类的串
                val parts = buildList {
                    if (refreshed > 0) add("补全 $refreshed 本")
                    if (touched > 0) add("移动 $touched 本")
                    if (rescued > 0) add("收纳 $rescued 本到「无法识别」")
                    if (newFolders > 0) add("新建 $newFolders 个文件夹")
                }
                _organizeReport.value = if (parts.isEmpty()) "书架已是最新状态"
                else "整理完成：" + parts.joinToString("，")
                AppLog.info(
                    "Shelf",
                    "Organize: refreshed=$refreshed touched=$touched rescued=$rescued newFolders=$newFolders",
                )
            } catch (e: Exception) {
                AppLog.error("Shelf", "Organize failed: ${e.message}", e)
                _organizeReport.value = "整理失败：${e.message?.take(60)}"
            } finally {
                _isOrganizing.value = false
            }
        }
    }

    /**
     * 并发度 4，对元数据稀疏的 web 书重新拉取 BookInfo，把 kind / description /
     * coverUrl / wordCount 合并写回 DB。返回真正发生变化的本数。
     *
     * 失败容错：单本失败 (网络 / 书源失效 / 规则报错) 只 warn，不影响其他书。
     */
    private suspend fun prefetchWebMetadata(books: List<Book>): Int {
        var refreshed = 0
        coroutineScope {
            val semaphore = Semaphore(4)
            books.map { book ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        try {
                            val srcUrl = book.sourceUrl ?: return@withPermit false
                            val source = sourceRepo.getByUrl(srcUrl) ?: return@withPermit false
                            val sb = SearchBook(
                                bookUrl = book.bookUrl,
                                origin = book.origin,
                                originName = book.originName,
                                name = book.title,
                                author = book.author,
                                kind = book.kind,
                                coverUrl = book.coverUrl,
                                intro = book.description,
                                tocUrl = book.tocUrl.orEmpty(),
                            )
                            val updated = WebBook.getBookInfoAwait(source, sb)
                            val merged = book.copy(
                                kind = updated.kind?.takeIf { it.isNotBlank() } ?: book.kind,
                                description = updated.intro?.takeIf { it.isNotBlank() } ?: book.description,
                                coverUrl = updated.coverUrl?.takeIf { it.isNotBlank() } ?: book.coverUrl,
                                wordCount = updated.wordCount?.takeIf { it.isNotBlank() } ?: book.wordCount,
                            )
                            if (merged != book) {
                                bookRepo.update(merged)
                                true
                            } else false
                        } catch (e: Exception) {
                            AppLog.warn(
                                "Shelf",
                                "Metadata prefetch 失败 ${book.title}: ${e.message?.take(40)}",
                            )
                            false
                        }
                    }
                }
            }.awaitAll().forEach { if (it) refreshed++ }
        }
        return refreshed
    }

    private suspend fun applyAutoGroup(book: Book): Book {
        val groupId = autoGroupClassifier.classify(book)
        return if (groupId != null && book.folderId == null) book.copy(folderId = groupId) else book
    }

    // ── Batch toc refresh (Legado parity, see ShelfRefreshController) ──

    /** True while the controller is draining its refresh queue. */
    val isRefreshing: kotlinx.coroutines.flow.StateFlow<Boolean> = refreshController.isRefreshing

    /** (done, total) for UI progress bar. */
    val refreshProgress: kotlinx.coroutines.flow.StateFlow<Pair<Int, Int>> = refreshController.progress

    /**
     * Trigger toc refresh for every WEB book on the shelf with canUpdate=true.
     * Uses the books snapshot from [books]; a refresh while books load is a no-op
     * (we'd refresh nothing, which is fine).
     */
    fun refreshAllBooks() {
        refreshController.refresh(books.value)
    }

    /** User canceled — drop the queue, keep in-flight fetches running so we don't half-update. */
    fun cancelRefresh() {
        refreshController.cancel()
    }

    /** Clear "N 新" badge when user opens a book. Called from AppNavHost on navigate. */
    fun clearNewChapterBadge(bookId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                bookRepo.clearLastCheckCount(bookId)
            } catch (_: Exception) {}
        }
    }

    // ── Update-indicator state (per-group + global) ──────────────────────────────
    //
    // Legado 的红点设计：单本封面右上角 "N 新" 徽章已经在 BookGridItem 渲染了。
    // MoRealm 在此之上再做两层聚合：
    //  1) 分组卡片右上角的小红点 — 只要分组内任意书 lastCheckCount > 0 就亮
    //  2) 顶栏刷新按钮上的 BadgedBox — 只要全书架任意书有更新就亮
    //
    // 两个 flow 都从 books 派生，免去额外 DB 读 — books 已经是 Eagerly stateIn。
    // distinctUntilChanged 防止地图引用变了但内容没变时触发重组。

    /** 每个 folderId 是否有"待读新章节"：folderId → hasUpdate。无 folderId 的书不参与。 */
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

    /** 全书架是否有任意书 lastCheckCount > 0。 */
    val hasAnyUpdate: StateFlow<Boolean> = books
        .map { list -> list.any { it.lastCheckCount > 0 } }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // ── Auto-refresh on cold start ───────────────────────────────────────────────
    //
    // 性能优先策略：仅在 ViewModel 首次创建时（= 应用冷启动 / 进程被 kill 后重新拉起）
    // 触发一次后台批量刷新，每个会话最多一次。理由：
    //  - 不在 ON_RESUME 上钩 — 用户切到 Profile 再切回来不应该触发额外网络请求
    //  - 不和启动关键路径竞争 — 延迟到 books flow 第一次 emit 之后再发起，
    //    避免冷启动 IO 风暴（封面解析 + 标签 seeder + 自动分组都在 init 里跑）
    //  - ShelfRefreshController 自身有 inFlight Set 去重 + bounded parallelism = 4，
    //    重复 refresh 调用是幂等的，所以这里出错不会"漏刷"
    init {
        viewModelScope.launch {
            // 等首屏书目加载完，再延 5s 让 UI 稳定 — 用户已经看到书架，后台拉 toc 不打扰
            books.first { it.isNotEmpty() }
            delay(5_000L)
            refreshAllBooks()
        }
    }
}
