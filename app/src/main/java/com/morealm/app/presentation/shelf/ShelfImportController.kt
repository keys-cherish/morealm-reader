package com.morealm.app.presentation.shelf

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import com.morealm.app.core.log.AppLog
import com.morealm.app.domain.entity.Book
import com.morealm.app.domain.entity.BookFormat
import com.morealm.app.domain.parser.ComicBookDetector
import com.morealm.app.domain.parser.EpubParser
import com.morealm.app.domain.parser.MobiParser
import com.morealm.app.domain.parser.PdfParser
import com.morealm.app.domain.repository.AutoGroupClassifier
import com.morealm.app.domain.repository.BookRepository
import com.morealm.app.domain.repository.BookGroupRepository
import com.morealm.app.domain.storage.BookFileHealthChecker
import com.morealm.app.service.ImportService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * 书架导入控制器 —— 两条路径不同语义：
 *
 * - **[importLocalBook]**（单本）：< 50ms 任务，直接 `scope.launch(Dispatchers.IO)`
 *   原地秒入库。不上 Service —— 启动 Foreground Service 的 IPC 开销比导入还久。
 *   生命周期跟 [scope]（通常 viewModelScope），但单本任务时间极短，丢失风险可忽略。
 *
 * - **[importFolder]**（批量）：N 千本任务 5-20 分钟，必须挂 [ImportService] 前台
 *   保活，否则用户切走 / OEM 杀进程 → 已落盘的 GB 级文件 + 一个空书架。详见
 *   [ImportService] KDoc。
 *
 * UI 仍订阅 [folderImportState] 拿进度，本 controller 自己只代表"老 UI 文案"
 * 兼容层；真实进度由 [com.morealm.app.domain.sync.ImportStateBus] 广播，
 * [ShelfViewModel] 负责把 bus 映射到 [_folderImportState]（Step 5 接线）。
 */
class ShelfImportController(
    private val bookRepo: BookRepository,
    @Suppress("unused") private val groupRepo: BookGroupRepository,
    private val autoGroupClassifier: AutoGroupClassifier,
    private val context: Context,
    private val scope: CoroutineScope,
) {

    private val _folderImportState = MutableStateFlow(FolderImportState())
    val folderImportState: StateFlow<FolderImportState> = _folderImportState.asStateFlow()

    /**
     * 让 [ShelfViewModel] / [com.morealm.app.domain.sync.ImportStateBus] 反向写状态，
     * UI 现有订阅点不动。Step 5 接 bus 时使用。
     */
    fun setFolderImportState(state: FolderImportState) {
        _folderImportState.value = state
    }

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

            // ── 原位引用：localPath 直存原文件 uri，不再复制进私有目录 ──
            //
            // [tryGrantPermission] 已 takePersistableUriPermission → 重启后仍可读；
            // 文件被用户移动/删除时 reader 侧 loadBook 指纹探测给「文件已移动或删除」
            // 明确提示。dedup 按原路径判重（与 ImportEngine 批量路径一致）。
            // 存量书（filesDir/books 副本）不迁移，localPath 依旧有效。
            if (bookRepo.findByLocalPath(uri.toString()) != null) {
                _folderImportState.value = FolderImportState(message = "已在书架：$name")
                AppLog.info("Import", "Already imported: $name")
                return@launch
            }
            // 扫描指纹：章节 DB 缓存失效校验用（拿不到 = 0，首次解析后由 reader 回填）
            val fingerprint = com.morealm.app.domain.storage.LocalFileFingerprint.of(context, uri)

            // ── Phase 1: 用文件名秒入库（< 50ms），UI 立刻报「已导入」──
            //
            // 老路径同步等 metadata + 封面解码 + 漫画检测才入库，对 50MB 漫画 EPUB / 大型
            // 精品书来说这一步要数秒到十几秒，用户卡在「正在导入：xxx」无任何反馈。
            //
            // 与文件夹路径 ImportEngine.importBatch 对齐 —— 先 placeholder 入库，
            // 再后台补 metadata/cover/isComic；BookFormatProbeViewModel 的兜底 detect
            // 路径保证 Phase 2 没完成前用户点开也能走对应路由。
            val parsed = parseBookFilename(name)
            val placeholderBook = applyAutoGroup(
                Book(
                    id = UUID.randomUUID().toString(),
                    title = parsed.first,
                    author = parsed.second,
                    localPath = uri.toString(),
                    format = format,
                    addedAt = System.currentTimeMillis(),
                    fileSize = fingerprint?.size ?: 0L,
                    fileMtime = fingerprint?.mtime ?: 0L,
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
                val enriched = enrichBookMetadata(placeholderBook, uri, format)
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

    /**
     * 批量导入文件夹 —— v1.6 改造：dispatch 到 [ImportService] 前台服务跑，让导入
     * 跨 APP 切走 / 进程压力都不掉。详细行为见 ImportService.kt KDoc。
     *
     * Android 13+ POST_NOTIFICATIONS：服务能起，但没授权时通知栏看不到 progress，
     * OEM 杀进程概率上升。这里**不阻断**，只 warn —— 弹权限对话框由 UI 层做
     * （ShelfScreen 在用户首次按"导入文件夹"前 prompt）。
     */
    fun importFolder(uri: Uri) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                AppLog.warn(
                    "Import",
                    "POST_NOTIFICATIONS not granted — Service will run but no progress notification (OEM kill risk ↑)",
                )
            }
        }
        // 立刻给 UI 一个"正在准备…"提示，避免用户点完按钮以为没反应
        _folderImportState.value = FolderImportState(
            running = true,
            message = "正在准备导入…",
        )
        val intent = Intent(context, ImportService::class.java).apply {
            putExtra(ImportService.EXTRA_TREE_URI, uri)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
        AppLog.info("Import", "Dispatched folder import to ImportService: $uri")
    }

    /** 文件后缀 → BookFormat。详见 [importLocalBook] / [ImportService] 内同款实现。 */
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

    // ── Single-book helpers（importLocalBook 专用，批量路径已搬到 ImportEngine） ──

    /** 按 [BookFileHealthChecker] 校验文件头。耗时 < 5ms。详细规则见该 helper 注释。 */
    private fun isValidFileHeader(uri: Uri, format: BookFormat): Boolean =
        BookFileHealthChecker.isValid(context, uri, format)

    private fun tryGrantPermission(uri: Uri) {
        try {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: Exception) {
            AppLog.warn("Import", "Permission grant failed: ${e.message}")
        }
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

    private suspend fun applyAutoGroup(book: Book): Book {
        val groupId = autoGroupClassifier.classify(book)
        return if (groupId != null && book.folderId == null) book.copy(folderId = groupId) else book
    }
}
