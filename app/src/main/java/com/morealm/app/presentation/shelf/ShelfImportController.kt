package com.morealm.app.presentation.shelf

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.morealm.app.domain.entity.Book
import com.morealm.app.domain.entity.BookFormat
import com.morealm.app.domain.entity.BookGroup
import com.morealm.app.domain.parser.ComicBookDetector
import com.morealm.app.domain.parser.EpubParser
import com.morealm.app.domain.parser.MobiParser
import com.morealm.app.domain.parser.PdfParser
import com.morealm.app.domain.repository.AutoGroupClassifier
import com.morealm.app.domain.repository.BookRepository
import com.morealm.app.domain.repository.BookGroupRepository
import com.morealm.app.domain.storage.BookFileHealthChecker
import com.morealm.app.domain.storage.FastFileScanner
import com.morealm.app.core.log.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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

        /**
         * Phase 2 enrichment 并发上限。每本要 EpubParser.withEpubBook + 封面解码 + bitmap
         * scale，CPU + IO 都占。4 个 worker 在中端机上是甜区（再多触发 IO 队列拥塞 +
         * GC 抖动）。
         */
        const val MAX_ENRICH_PARALLELISM = 4

        /** 批量入库 chunk 大小。50 是 SQLite SQL 文本长度 / 单事务时长的折中。 */
        const val DB_BATCH_SIZE = 50
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

            val docFile = DocumentFile.fromSingleUri(context, uri)
            if (docFile == null) {
                _folderImportState.value = FolderImportState(message = "无法读取所选文件")
                return@launch
            }
            val name = docFile.name ?: "Unknown"
            val ext = name.substringAfterLast('.', "").lowercase()
            val format = detectFormat(name)
            if (format == BookFormat.UNKNOWN) {
                _folderImportState.value = FolderImportState(
                    message = if (ext.isNotEmpty()) "暂不支持的格式：$ext" else "暂不支持的格式",
                )
                AppLog.warn("Import", "Unsupported format: $name")
                return@launch
            }
            // ── 文件健全性预检（按格式分发到对应 magic 校验） ──
            //
            // 实测有用户拿到 0 字节填充的 .epub 占位文件（下载残缺 / 备份恢复未完成），
            // SAF 入库时只看文件大小不验证内容，结果书架占位卡死无 cover、打开黑屏。
            if (!isValidFileHeader(uri, format)) {
                _folderImportState.value = FolderImportState(
                    message = "文件损坏或不是有效的 $format：$name",
                )
                AppLog.warn("Import", "Rejected invalid $format header: $name")
                return@launch
            }

            // ── 落地到私有目录：SAF Uri → filesDir/books/{hash}.{ext} → file:// Uri ──
            // 之后业务层用稳定 path，不再依赖 SAF 授权 / 不依赖原文件位置（详见
            // [com.morealm.app.domain.parser.LocalBookStorage]）。
            val localUri = com.morealm.app.domain.parser.LocalBookStorage
                .saveAsLocal(context, uri, ext) ?: run {
                _folderImportState.value = FolderImportState(message = "保存到本地失败：$name")
                AppLog.error("Import", "LocalBookStorage.saveAsLocal returned null for $name")
                return@launch
            }
            if (bookRepo.findByLocalPath(localUri.toString()) != null) {
                _folderImportState.value = FolderImportState(message = "已在书架：$name")
                AppLog.info("Import", "Already imported: $name")
                return@launch
            }

            // ── Phase 1: 用文件名秒入库（< 50ms），UI 立刻报「已导入」──
            //
            // 老路径同步等 metadata + 封面解码 + 漫画检测才入库，对 50MB 漫画 EPUB / 大型
            // 精品书来说这一步要数秒到十几秒，用户卡在「正在导入：xxx」无任何反馈。
            //
            // 与文件夹路径 importFilesWithDeferredCovers 对齐 —— 先 placeholder 入库，
            // 再后台补 metadata/cover/isComic；BookFormatProbeViewModel 的兜底 detect
            // 路径保证 Phase 2 没完成前用户点开也能走对应路由。
            val parsed = parseBookFilename(name)
            val placeholderBook = applyAutoGroup(
                Book(
                    id = UUID.randomUUID().toString(),
                    title = parsed.first,
                    author = parsed.second,
                    localPath = localUri.toString(),
                    format = format,
                    addedAt = System.currentTimeMillis(),
                )
            )
            try {
                bookRepo.insert(placeholderBook)
            } catch (e: Exception) {
                _folderImportState.value = FolderImportState(
                    message = "导入失败：${e.message?.take(40) ?: name}",
                    error = e.message,
                )
                AppLog.error("Import", "Placeholder insert failed: ${e.message}", e)
                return@launch
            }
            _folderImportState.value = FolderImportState(
                importedCount = 1,
                message = "已导入：${placeholderBook.title}",
            )
            AppLog.info("Import", "Phase 1 inserted: ${placeholderBook.title} ($format)")

            // ── Phase 2: 异步补 metadata / cover / isComic ──
            //
            // 失败不回滚 placeholder ——「文件名书」比「书消失」体验好得多；
            // 重要的 isComic 字段即便此处失败，BookFormatProbeViewModel 仍会兜底 detect。
            try {
                val enriched = enrichBookMetadata(placeholderBook, localUri, format)
                if (enriched != null && enriched != placeholderBook) {
                    bookRepo.update(applyAutoGroup(enriched))
                    AppLog.info(
                        "Import",
                        "Phase 2 enriched: ${enriched.title} isComic=${enriched.isComic}",
                    )
                }
            } catch (e: Exception) {
                AppLog.warn(
                    "Import",
                    "Phase 2 enrichment failed for ${placeholderBook.title}: ${e.message}",
                )
            }
        }
    }

    fun importFolder(uri: Uri) {
        _folderImportState.value = FolderImportState(running = true, message = "正在打开文件夹…")
        scope.launch(Dispatchers.IO) {
            val startTime = System.currentTimeMillis()
            tryGrantPermission(uri)
            try {
                // ── 用 FastFileScanner 替代 DocumentFile.listFiles ──
                //
                // 有 All-Files-Access → File API 直读（1000 文件 < 500ms）；
                // 无权限 → SAF DocumentsContract.query（比 DocumentFile.listFiles 快 3-5x）。
                val folderName = resolveTreeName(uri)
                _folderImportState.value = FolderImportState(
                    running = true,
                    folderName = folderName,
                    message = "正在扫描：$folderName",
                )
                AppLog.info("Import", "Scanning folder: $folderName")

                val topChildren = FastFileScanner.listImmediateChildren(context, uri)
                val subFolders = topChildren.filter { it.isDirectory }
                val directFiles = topChildren
                    .filter { !it.isDirectory && detectFormat(it.name) != BookFormat.UNKNOWN }
                    .map { ch -> FastFileScanner.ScanItem(uri = ch.uri, name = ch.name, filePath = ch.filePath) }
                AppLog.info("Import", "Found ${subFolders.size} sub-folders, ${directFiles.size} direct files")

                val importedCount = when {
                    subFolders.isNotEmpty() -> {
                        _folderImportState.value = _folderImportState.value.copy(
                            message = "正在导入 ${subFolders.size} 个子文件夹…",
                        )
                        val subCount = importSubFolders(subFolders)
                        val directCount = if (directFiles.isNotEmpty()) importAsGroup(directFiles, folderName) else 0
                        subCount + directCount
                    }
                    directFiles.isNotEmpty() -> importAsGroup(directFiles, folderName)
                    else -> importDeepScan(uri, folderName)
                }

                _folderImportState.value = FolderImportState(
                    folderName = folderName,
                    importedCount = importedCount,
                    message = if (importedCount > 0) "已导入 $importedCount 本书" else "没有发现可导入的书籍",
                )
                AppLog.info(
                    "Import",
                    "Folder import '$folderName' completed: $importedCount books in ${System.currentTimeMillis() - startTime}ms",
                )
            } catch (e: Exception) {
                _folderImportState.value = FolderImportState(message = "导入失败", error = e.message ?: "未知错误")
                AppLog.error("Import", "Folder import failed: ${e.message}", e)
            }
        }
    }

    /** 拿用户选的根 tree 文件夹名 —— File API 失败 fallback 到 DocumentFile.fromTreeUri。 */
    private fun resolveTreeName(treeUri: Uri): String {
        com.morealm.app.domain.storage.StorageAccessHelper.treeUriToFilePath(treeUri)?.let { path ->
            return java.io.File(path).name.ifBlank { "导入文件夹" }
        }
        return DocumentFile.fromTreeUri(context, treeUri)?.name ?: "导入文件夹"
    }

    /** 按 [BookFileHealthChecker] 校验文件头。耗时 < 5ms。详细规则见该 helper 注释。 */
    private fun isValidFileHeader(uri: Uri, format: BookFormat): Boolean =
        BookFileHealthChecker.isValid(context, uri, format)

    private fun tryGrantPermission(uri: Uri) {
        try {
            context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: Exception) {
            AppLog.warn("Import", "Permission grant failed: ${e.message}")
        }
    }

    private suspend fun importSubFolders(subFolders: List<FastFileScanner.ChildItem>, depth: Int = 0): Int {
        if (depth > MAX_FOLDER_IMPORT_DEPTH) return 0
        var importedCount = 0
        for (folder in subFolders) {
            val folderName = folder.name
            // 该子文件夹下所有可导入文件（递归到 FastFileScanner 内部，免去手写递归）
            val descendants = FastFileScanner.scanFolder(
                context = context,
                treeUri = folder.uri,
                isWanted = { detectFormat(it) != BookFormat.UNKNOWN },
                maxDepth = MAX_FOLDER_IMPORT_DEPTH,
            )
            if (descendants.isNotEmpty()) {
                importedCount += importAsGroup(descendants, folderName)
                _folderImportState.value = _folderImportState.value.copy(importedCount = importedCount)
            }
        }
        return importedCount
    }

    private suspend fun importAsGroup(files: List<FastFileScanner.ScanItem>, groupName: String): Int {
        if (files.isEmpty()) return 0

        _folderImportState.value = _folderImportState.value.copy(
            message = "正在导入：$groupName（${files.size} 个文件）",
        )
        val groupId = UUID.randomUUID().toString()
        groupRepo.insert(BookGroup(id = groupId, name = groupName))
        val importedCount = importFilesWithDeferredCovers(files, groupId)
        if (importedCount == 0) {
            groupRepo.deleteById(groupId)
        }
        _folderImportState.value = _folderImportState.value.copy(importedCount = importedCount)
        return importedCount
    }

    private suspend fun importDeepScan(treeUri: Uri, folderName: String): Int {
        _folderImportState.value = _folderImportState.value.copy(message = "正在深度扫描：$folderName")
        val allFiles = FastFileScanner.scanFolder(
            context = context,
            treeUri = treeUri,
            isWanted = { detectFormat(it) != BookFormat.UNKNOWN },
            maxDepth = MAX_FOLDER_IMPORT_DEPTH,
        )
        if (allFiles.isEmpty()) return 0
        return importAsGroup(allFiles, folderName)
    }

    /**
     * Phase 1：批量 placeholder 入库（一次事务）。
     * Phase 2：并发 enrichBookMetadata（[MAX_ENRICH_PARALLELISM] worker），收集后批量 update。
     *
     * 与旧实现对比（1000 文件量级）：
     * - 入库：1000 次单独 insert → 20 次批量 insertAll（5s → < 500ms）
     * - enrich：串行 for 循环 → 4 并发（数分钟 → 数十秒）
     * - UI 状态：每本一次 emit → 每批一次 emit（避免 recomposition 风暴）
     */
    private suspend fun importFilesWithDeferredCovers(
        files: List<FastFileScanner.ScanItem>,
        folderId: String,
    ): Int = coroutineScope {
        if (files.isEmpty()) return@coroutineScope 0
        AppLog.info("Import", "Processing ${files.size} files for group $folderId")

        data class PendingBook(
            val book: Book,
            val item: FastFileScanner.ScanItem,
            val format: BookFormat,
        )

        // ── Phase 1: header 预检 + 落地到私有目录 + 文件名解析 → placeholder Book → 批量 insert ──
        val pending = ArrayList<PendingBook>(files.size)
        for ((idx, item) in files.withIndex()) {
            val format = detectFormat(item.name)
            if (format == BookFormat.UNKNOWN) continue
            if (!isValidFileHeader(item.uri, format)) {
                AppLog.warn("Import", "Rejected invalid $format header: ${item.name}")
                continue
            }
            val ext = item.name.substringAfterLast('.', "").lowercase()
            val localUri = com.morealm.app.domain.parser.LocalBookStorage
                .saveAsLocal(context, item.uri, ext)
            if (localUri == null) {
                AppLog.warn("Import", "saveAsLocal failed for ${item.name}")
                continue
            }
            if (bookRepo.findByLocalPath(localUri.toString()) != null) continue

            val parsed = parseBookFilename(item.name)
            val book = applyAutoGroup(
                Book(
                    id = UUID.randomUUID().toString(),
                    title = parsed.first,
                    author = parsed.second,
                    localPath = localUri.toString(),
                    format = format,
                    folderId = folderId,
                    addedAt = System.currentTimeMillis() + idx,
                )
            )
            pending.add(PendingBook(book, item, format))
        }
        if (pending.isEmpty()) return@coroutineScope 0

        bookRepo.bulkInsert(pending.map { it.book }, batchSize = DB_BATCH_SIZE)
        _folderImportState.value = _folderImportState.value.copy(
            importedCount = pending.size,
            message = "已导入 ${pending.size} 本，正在后台补元数据…",
        )
        AppLog.info("Import", "Phase 1: bulk-inserted ${pending.size} placeholders")

        // ── Phase 2: 并发 enrich (MAX_ENRICH_PARALLELISM worker) + 批量 update ──
        val enrichDispatcher = Dispatchers.IO.limitedParallelism(MAX_ENRICH_PARALLELISM)
        val enrichJobs = pending.map { pb ->
            async(enrichDispatcher) {
                runCatching {
                    enrichBookMetadata(pb.book, Uri.parse(pb.book.localPath), pb.format)?.let { applyAutoGroup(it) }
                }.onFailure { t ->
                    AppLog.warn("Import", "enrich failed ${pb.book.title}: ${t.message}")
                }.getOrNull()
            }
        }
        val enriched = enrichJobs.awaitAll().filterNotNull()
        if (enriched.isNotEmpty()) {
            bookRepo.bulkUpdate(enriched, batchSize = DB_BATCH_SIZE)
            AppLog.info("Import", "Phase 2: bulk-updated ${enriched.size} books")
        }
        pending.size
    }

    /**
     * Phase 2 后台补全：metadata / cover / isComic。返回 null 表示该 format 无 enrichment
     * 需求（如 TXT/UMD/CBZ）。任意子步骤失败保持 placeholder 字段不变 —— 比丢书好。
     */
    private fun enrichBookMetadata(book: Book, uri: Uri, format: BookFormat): Book? {
        return when (format) {
            BookFormat.EPUB -> {
                // 合并到 1 次 withEpubBook 块内完成 metadata + cover + isComic 提取，
                // 避免每本 EPUB 走两次 PFD/ZIP/OPF 解析（per-uri LRU cache 在并发导入
                // 下反复 evict 会让 cache miss → 重开 PFD churn → 大批量 EPUB 慢 30s+）。
                val bundle = try {
                    EpubParser.extractAllForImport(context, uri)
                } catch (e: Exception) {
                    AppLog.warn("Import", "EPUB extractAll failed: ${e.message}")
                    EpubParser.ImportBundle()
                }
                book.copy(
                    title = bundle.metadata.title.takeIf { it.isNotBlank() } ?: book.title,
                    author = bundle.metadata.author.takeIf { it.isNotBlank() } ?: book.author,
                    description = bundle.metadata.description.takeIf { it.isNotBlank() } ?: book.description,
                    kind = bundle.metadata.subject.takeIf { it.isNotBlank() } ?: book.kind,
                    coverUrl = bundle.coverPath ?: book.coverUrl,
                    isComic = bundle.isComic,
                )
            }
            BookFormat.PDF -> {
                val cover = try { PdfParser.extractCover(context, uri) } catch (_: Exception) { null }
                book.copy(coverUrl = cover ?: book.coverUrl)
            }
            BookFormat.MOBI, BookFormat.AZW3 -> {
                val cover = try {
                    MobiParser.extractCover(context, uri)
                } catch (_: Exception) { null }
                val isComic = try {
                    ComicBookDetector.detect(context, uri, format)
                } catch (_: Exception) { false }
                book.copy(coverUrl = cover ?: book.coverUrl, isComic = isComic)
            }
            else -> null
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

    /**
     * 文件后缀 → BookFormat。
     *
     * MoRealm 定位为电子书（文字）阅读器，不发展为漫画 archive 解压器。下列 archive
     * 后缀在 v1.3 之后**不再识别**：
     * - `.cbz` / `.cbr` / `.rar` / `.7z` —— 漫画专用 archive，去掉对应解析路径
     * - 仅保留 `.zip` —— 给小部分用户的 ZIP-as-CBZ 历史习惯留一条入库通道（不做专门优化）
     *
     * 实际漫画支持靠 EPUB 内嵌 image 的检测（[EpubParser.detectIsComic]），不再
     * 解析外挂 archive。
     */
    fun detectFormat(filename: String): BookFormat {
        val ext = filename.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "txt" -> BookFormat.TXT
            "epub" -> BookFormat.EPUB
            "pdf" -> BookFormat.PDF
            "mobi" -> BookFormat.MOBI
            "azw3", "azw" -> BookFormat.AZW3
            "zip" -> BookFormat.CBZ   // 仅保留 .zip，cbz/cbr/rar/7z 不再识别
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
