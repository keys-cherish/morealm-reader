package com.morealm.app.domain.sync

import android.content.Context
import android.net.Uri
import com.morealm.app.core.log.AppLog
import com.morealm.app.domain.entity.Book
import com.morealm.app.domain.entity.BookFormat
import com.morealm.app.domain.parser.ComicBookDetector
import com.morealm.app.domain.parser.EpubParser
import com.morealm.app.domain.parser.MobiParser
import com.morealm.app.domain.parser.PdfParser
import com.morealm.app.domain.repository.AutoGroupClassifier
import com.morealm.app.domain.repository.BookRepository
import com.morealm.app.domain.storage.BookFileHealthChecker
import com.morealm.app.domain.storage.FastFileScanner
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SAF 批量导入核心引擎 —— 纯逻辑、可单测、无 Service / ViewModel 依赖。
 *
 * ## 与 ShelfImportController 的责任分工
 *
 * - **ShelfImportController**（Step 4 改造后）：
 *   - 接收用户 SAF 选 folder 的 intent → 调 startForegroundService(ImportService)
 *   - 单本 importLocalBook（< 50ms 任务）仍直接 ApplicationScope.launch 不上 Service
 *   - 处理 sub-folder 递归 + group 创建（importAsGroup / importSubFolders / importDeepScan）
 *   - 把每批扫描出的 List<ScanItem> 喂给 [importBatch]
 *
 * - **ImportEngine**（本 class）：
 *   - 只暴露 [importBatch] 一个 suspend 公开 API
 *   - chunked(50) 内部循环：每 chunk 校验 + bulkInsert 完立刻 emit Bus 进度
 *   - chunk 边界消费 [ImportStateBus.consumeCancelFlag] —— 取消请求立即生效
 *   - Phase 2 enrich 并发 worker = [MAX_ENRICH_PARALLELISM]
 *
 * ## 与原 importFilesWithDeferredCovers 的关键差异
 *
 * 原实现：所有文件 `saveAsLocal` 全量复制（5-20 分钟、磁盘翻倍）→ 单次
 * `bulkInsert(全部)` → emit 一次"已导入 X 本"。中途 cancel / 进程被杀 →
 * 已复制的文件落盘但 Book row 全丢。
 *
 * 现实现（原位引用后）：**零复制**，localPath 直存原文件 uri；每 50 个纯 DB
 * insert → emit Phase1 (imported=Σ)。1GB 单文件与万本批量的耗时都只剩
 * header 校验 + insert；中途 cancel 已 insert 的本数保留在书架。
 *
 * ## 短期 helper 复制
 *
 * [detectFormat] / [parseBookFilename] / [isValidFileHeader] / [enrichBookMetadata] /
 * [applyAutoGroup] 复制自 ShelfImportController，**Step 4 controller 路由改造时
 * 会删 controller 内同名 private 函数**，避免长期 duplication。
 */
@Singleton
class ImportEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bookRepo: BookRepository,
    private val autoGroupClassifier: AutoGroupClassifier,
) {

    companion object {
        private const val TAG = "ImportEngine"

        /** Phase 1 chunk 大小。与 [BookRepository.bulkInsert] 默认 batchSize 一致。 */
        private const val PHASE1_CHUNK_SIZE = 50

        /**
         * Phase 2 enrichment 并发上限。每本要 EpubParser.withEpubBook + 封面解码 + bitmap
         * scale，CPU + IO 都占。4 个 worker 在中端机上是甜区（再多触发 IO 队列拥塞 +
         * GC 抖动）。
         */
        private const val MAX_ENRICH_PARALLELISM = 4

        /**
         * ScanItem → 原位引用 Book（Phase 1 placeholder）。
         *
         * 契约（单测 ImportEngineInPlaceBookTest 锁定）：
         * - `localPath == item.uri.toString()` —— 不复制、不改写，file:// 与 content:// 同样成立
         * - `fileSize/fileMtime == 扫描带出的指纹` —— 首次解析后章节 DB 缓存即可命中
         * - title/author 来自 [parseBookFilename]
         *
         * 放 companion：纯函数、不碰注入依赖，单测无需构造带 Hilt 依赖的实例。
         */
        internal fun buildInPlaceBook(
            item: FastFileScanner.ScanItem,
            format: BookFormat,
            folderId: String?,
            addedAtStamp: Long,
        ): Book {
            val parsed = parseBookFilename(item.name)
            return Book(
                id = UUID.randomUUID().toString(),
                title = parsed.first,
                author = parsed.second,
                localPath = item.uri.toString(),
                format = format,
                folderId = folderId,
                addedAt = addedAtStamp,
                fileSize = item.size,
                fileMtime = item.lastModified,
            )
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
    }

    /**
     * 给一批已扫描的可导入 file，做 Phase 1 incremental insert + Phase 2 并发 enrich。
     *
     * **不**emit 起始 [ImportState.Scanning]（caller 已 emit）；**不**emit [ImportState.Done]
     * (caller 在所有 batch 完成后 emit，因为可能多次调 importBatch 处理 sub-folder)。
     * 本函数只在内部 emit [ImportState.Phase1] 和 [ImportState.Phase2] 实时进度。
     *
     * @param files       已通过 FastFileScanner 扫出的可导入 file
     * @param folderId    关联的 BookGroup id（caller 已创建 group），null = 不入 group
     * @param folderName  显示用文件夹名（emit Bus state 用）
     * @param progressOffset 已入库本数偏移（多次调本函数累积 imported 用）
     * @return Pair<Phase1 入库本数, Phase2 enriched 本数>。失败 file 不算入。
     */
    suspend fun importBatch(
        files: List<FastFileScanner.ScanItem>,
        folderId: String?,
        folderName: String,
        progressOffset: Int = 0,
        totalForProgress: Int = files.size,
    ): BatchResult = coroutineScope {
        if (files.isEmpty()) return@coroutineScope BatchResult(0, 0)
        AppLog.info(TAG, "importBatch start files=${files.size} folder=$folderName")

        // ── Phase 1: chunked(50) 校验 + bulkInsert + emit（原位引用，无文件复制）──
        //
        // 每 chunk = 1 DB 事务 = 用户书架 +50 本立即可见。chunk 边界消费 cancel
        // 信号让取消"立刻"生效（chunk 内只有 header 校验 + insert，本来就快）。
        var importedInBatch = 0
        val pendingForEnrich = ArrayList<PendingBook>(files.size)
        // 已在书架、但本次需归入导入组的旧书（dedup 命中且原先散落）。统一末尾 bulkUpdate。
        val regroupAll = ArrayList<Book>()

        for (chunk in files.chunked(PHASE1_CHUNK_SIZE)) {
            // chunk 边界：消费取消请求。返回 true 则停止后续 chunk。
            if (ImportStateBus.consumeCancelFlag()) {
                AppLog.info(TAG, "cancel requested at chunk boundary, stopping")
                ImportStateBus.update(
                    ImportState.Phase1(
                        folderName = folderName,
                        imported = progressOffset + importedInBatch,
                        total = totalForProgress,
                        cancelled = true,
                    )
                )
                break
            }

            val chunkResult = processChunk(chunk, folderId)
            regroupAll.addAll(chunkResult.regroup)
            val chunkPending = chunkResult.pending
            if (chunkPending.isEmpty()) continue

            bookRepo.bulkInsert(chunkPending.map { it.book }, batchSize = PHASE1_CHUNK_SIZE)
            importedInBatch += chunkPending.size
            pendingForEnrich.addAll(chunkPending)

            ImportStateBus.update(
                ImportState.Phase1(
                    folderName = folderName,
                    imported = progressOffset + importedInBatch,
                    total = totalForProgress,
                )
            )
            AppLog.info(TAG, "Phase1 chunk done +${chunkPending.size} (total imported=$importedInBatch)")
        }

        // ── 把 dedup 命中的散落旧书归入本次导入组 ──
        //
        // 必须放在下面 Phase 2 early-return 之前：否则「重导文件夹、书全已存在」场景
        // pendingForEnrich 为空会直接 return，regroup 永不落库 → 用户重导看不到变化。
        // 只动原先未归组（folderId==null）的书，不抢已属于其它组的书。
        if (regroupAll.isNotEmpty()) {
            bookRepo.bulkUpdate(regroupAll, batchSize = PHASE1_CHUNK_SIZE)
            AppLog.info(TAG, "Regrouped ${regroupAll.size} existing book(s) into folder=$folderName")
        }

        // ── Phase 2: 并发 enrich + bulkUpdate ──
        //
        // Phase 2 不消费 cancel flag —— Phase 1 已入库的书 enrich 失败也不回滚，
        // 让用户立刻能看到书在书架，metadata/cover 是次要的（[BookFormatProbeViewModel]
        // 兜底 detect）。
        if (pendingForEnrich.isEmpty()) return@coroutineScope BatchResult(importedInBatch, 0, regroupAll.size)

        val enrichDispatcher = Dispatchers.IO.limitedParallelism(MAX_ENRICH_PARALLELISM)
        val enrichJobs = pendingForEnrich.map { pb ->
            async(enrichDispatcher) {
                runCatching {
                    enrichBookMetadata(pb.book, Uri.parse(pb.book.localPath), pb.format)
                        ?.let { applyAutoGroup(it) }
                }.onFailure { t ->
                    AppLog.warn(TAG, "enrich failed ${pb.book.title}: ${t.message}")
                }.getOrNull()
            }
        }
        val enriched = enrichJobs.awaitAll().filterNotNull()
        if (enriched.isNotEmpty()) {
            bookRepo.bulkUpdate(enriched, batchSize = PHASE1_CHUNK_SIZE)
            AppLog.info(TAG, "Phase2 enriched ${enriched.size}/${pendingForEnrich.size}")
        }
        BatchResult(importedInBatch, enriched.size, regroupAll.size)
    }

    /**
     * 处理单个 chunk：format 检测 + header 校验 + **原位引用**（localPath 直存原文件
     * uri，不复制）+ 原路径 dedup + 文件名解析 + applyAutoGroup → 返回待 bulkInsert
     * 的 [PendingBook] 列表。
     *
     * 失败 file `continue` skip（跟原 controller 行为一致）。
     */
    private suspend fun processChunk(
        chunk: List<FastFileScanner.ScanItem>,
        folderId: String?,
    ): ChunkResult {
        val pending = ArrayList<PendingBook>(chunk.size)
        val regroup = ArrayList<Book>()
        for ((idx, item) in chunk.withIndex()) {
            val format = detectFormat(item.name)
            if (format == BookFormat.UNKNOWN) continue
            if (!isValidFileHeader(item.uri, format)) {
                AppLog.warn(TAG, "Rejected invalid $format header: ${item.name}")
                continue
            }
            // ── 原位引用（静读天下方式）：localPath = 原文件 uri，导入零复制 ──
            //
            // File API 扫描 → file://，SAF 扫描 → tree 派生 document uri（ImportService
            // 已 takePersistableUriPermission(treeUri)，重启后子文档仍可读）。导入 = 纯
            // DB insert：1GB 文件秒导、万本不再翻倍占盘。存量书（filesDir/books 副本）
            // 不迁移，localPath 依旧有效；[LocalBookStorage] 仅保留给旧书语义参考。
            //
            // dedup 改按**原路径**判重：同一文件重复导入命中；同内容不同路径视为两本
            // （内容 hash 需要读全文件，违背零 IO 初衷——用户拍板可接受）。文件后来被
            // 移动/删除 → reader 打开时指纹探测给「文件已移动或删除」明确提示。
            val existing = bookRepo.findByLocalPath(item.uri.toString())
            if (existing != null) {
                // 已在书架：把它收编进本次导入组（除非已经在该组）。「重新导入文件夹」是
                // 强意图——文件夹里的书就该归这个文件夹组，不管之前是散落（folderId==null）、
                // 在已删的旧组（孤儿）、还是在别的组。否则 dedup 命中后直接 skip → imported=0、
                // 书进不去（用户反复报「删分组后重导无法导入」的根因：book row 顽固存在 +
                // 旧条件只收编 null）。trade-off：曾被手动移到别组的书会被拉回文件夹组，
                // 但用户主动重导该文件夹即表达了「按文件夹重新组织」的意图，可接受。
                if (folderId != null && existing.folderId != folderId) {
                    regroup.add(existing.copy(folderId = folderId))
                }
                continue
            }

            val book = applyAutoGroup(
                buildInPlaceBook(item, format, folderId, addedAtStamp = System.currentTimeMillis() + idx)
            )
            pending.add(PendingBook(book, item, format))
        }
        return ChunkResult(pending, regroup)
    }

    // ── Helper（短期复制自 ShelfImportController，Step 4 dedup） ──

    private fun isValidFileHeader(uri: Uri, format: BookFormat): Boolean =
        BookFileHealthChecker.isValid(context, uri, format)

    private fun detectFormat(filename: String): BookFormat {
        val ext = filename.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "txt" -> BookFormat.TXT
            "epub" -> BookFormat.EPUB
            "pdf" -> BookFormat.PDF
            "mobi" -> BookFormat.MOBI
            "azw3", "azw" -> BookFormat.AZW3
            "zip" -> BookFormat.CBZ
            "umd" -> BookFormat.UMD
            else -> BookFormat.UNKNOWN
        }
    }

    private suspend fun applyAutoGroup(book: Book): Book {
        val groupId = autoGroupClassifier.classify(book)
        return if (groupId != null && book.folderId == null) book.copy(folderId = groupId) else book
    }

    private fun enrichBookMetadata(book: Book, uri: Uri, format: BookFormat): Book? {
        return when (format) {
            BookFormat.EPUB -> {
                val bundle = try {
                    EpubParser.extractAllForImport(context, uri)
                } catch (e: Exception) {
                    AppLog.warn(TAG, "EPUB extractAll failed: ${e.message}")
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

    /** chunk 内本次成功 save+pending 的 book 元组。Phase 2 enrich 用。 */
    private data class PendingBook(
        val book: Book,
        val item: FastFileScanner.ScanItem,
        val format: BookFormat,
    )

    /** [processChunk] 返回：本 chunk 待 insert 的新书 + 需归组的已存在旧书。 */
    private data class ChunkResult(
        val pending: List<PendingBook>,
        val regroup: List<Book>,
    )

    /** [importBatch] 返回值。 */
    data class BatchResult(
        val phase1Inserted: Int,
        val phase2Enriched: Int,
        /** dedup 命中、被收编进本次导入组的已存在旧书数（重导收编散落书）。 */
        val regrouped: Int = 0,
    )
}
