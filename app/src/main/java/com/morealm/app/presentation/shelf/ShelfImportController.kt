package com.morealm.app.presentation.shelf

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.morealm.app.domain.entity.Book
import com.morealm.app.domain.entity.BookFormat
import com.morealm.app.domain.entity.BookGroup
import com.morealm.app.domain.parser.EpubParser
import com.morealm.app.domain.parser.PdfParser
import com.morealm.app.domain.repository.AutoGroupClassifier
import com.morealm.app.domain.repository.BookRepository
import com.morealm.app.domain.repository.BookGroupRepository
import com.morealm.app.core.log.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import java.util.UUID

class ShelfImportController(
    private val bookRepo: BookRepository,
    private val groupRepo: BookGroupRepository,
    private val autoGroupClassifier: AutoGroupClassifier,
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private companion object {
        const val MAX_FOLDER_IMPORT_DEPTH = 10
    }

    private val _folderImportState = MutableStateFlow(FolderImportState())
    val folderImportState: StateFlow<FolderImportState> = _folderImportState.asStateFlow()

    fun clearFolderImportMessage() {
        val current = _folderImportState.value
        if (!current.running) _folderImportState.value = FolderImportState()
    }

    fun importLocalBook(uri: Uri) {
        scope.launch(Dispatchers.IO) {
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

    fun importFolder(uri: Uri) {
        _folderImportState.value = FolderImportState(running = true, message = "正在打开文件夹…")
        scope.launch(Dispatchers.IO) {
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

    private fun parseBookFilename(fileName: String): Pair<String, String> {
        val base = fileName.substringBeforeLast('.').trim()

        val bracketMatch = Regex("^[\\[【](.+?)[\\]】]\\s*(.+)$").find(base)
        if (bracketMatch != null) {
            return bracketMatch.groupValues[2].trim() to bracketMatch.groupValues[1].trim()
        }

        val parenMatch = Regex("^(.+?)[（(](.+?)[）)]$").find(base)
        if (parenMatch != null) {
            return parenMatch.groupValues[1].trim() to parenMatch.groupValues[2].trim()
        }

        val sepMatch = Regex("^(.+?)\\s*[-_—]\\s*(.+)$").find(base)
        if (sepMatch != null) {
            val left = sepMatch.groupValues[1].trim()
            val right = sepMatch.groupValues[2].trim()
            return if (left.length <= right.length && left.length <= 6) {
                right to left
            } else {
                left to right
            }
        }

        return base to ""
    }

    fun detectFormat(filename: String): BookFormat {
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

    data class FileInfo(val uri: Uri, val name: String, val isDir: Boolean)

    fun listFilesFast(treeUri: Uri): List<FileInfo> {
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

    private suspend fun applyAutoGroup(book: Book): Book {
        val groupId = autoGroupClassifier.classify(book)
        return if (groupId != null && book.folderId == null) book.copy(folderId = groupId) else book
    }
}
