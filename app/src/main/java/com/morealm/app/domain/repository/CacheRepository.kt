package com.morealm.app.domain.repository

import android.content.Context
import android.net.Uri
import com.morealm.app.core.log.AppLog
import com.morealm.app.domain.db.BookDao
import com.morealm.app.domain.db.CacheDao
import com.morealm.app.domain.db.ChapterDao
import com.morealm.app.domain.db.ReplaceRuleDao
import com.morealm.app.domain.entity.Book
import com.morealm.app.domain.entity.BookFormat
import com.morealm.app.domain.webbook.ContentProcessor
import com.morealm.app.service.CacheBookService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CacheRepository @Inject constructor(
    private val bookDao: BookDao,
    private val chapterDao: ChapterDao,
    private val cacheDao: CacheDao,
    private val replaceRuleDao: ReplaceRuleDao,
    @ApplicationContext private val context: Context,
) {
    fun getWebBooks(): Flow<List<Book>> = bookDao.getAllBooks()
        .map { books -> books.filter { it.format == BookFormat.WEB && !it.sourceUrl.isNullOrBlank() } }

    val isDownloading: StateFlow<Boolean> = CacheBookService.isRunning

    /**
     * 多本并行进度表 — bookId → 该书当前 [CacheBookService.DownloadProgress]。
     * UI 用这个做：
     *   - 顶栏聚合（sum total / sum done）
     *   - 单本卡片本地进度（map[bookId]）
     * 替代了旧的单 downloadProgress（后者在多本并行时会"哪本最后更新就显示哪本"，
     * 与单本卡片状态冲突）。
     */
    val progresses: StateFlow<Map<String, CacheBookService.DownloadProgress>> =
        CacheBookService.progresses

    /**
     * Legacy 单本进度 — 转发 [CacheBookService.progress]。
     * 现在仅用于 ShelfViewModel / BookDetailViewModel 这种"我只关心一本书"的订阅者。
     * 新代码请用 [progresses] 自取所需。
     */
    val downloadProgress: StateFlow<CacheBookService.DownloadProgress> =
        CacheBookService.progress

    suspend fun getCacheStat(bookId: String, sourceUrl: String): Pair<Int, Int> {
        val chapters = chapterDao.getChaptersList(bookId)
        var cached = 0
        for (ch in chapters) {
            if (ch.url.isBlank() || ch.isVolume) continue
            if (cacheDao.get("chapter_content_${sourceUrl}_${ch.url}") != null) cached++
        }
        return chapters.size to cached
    }

    suspend fun clearCache(sourceUrl: String) {
        cacheDao.deleteByPrefix("chapter_content_${sourceUrl}_")
    }

    /**
     * 批量清缓存：对每个 sourceUrl 调 [clearCache]。事务级一次性删除。返回受影响 source 数。
     */
    suspend fun clearCacheBatch(sourceUrls: Collection<String>): Int {
        var n = 0
        for (url in sourceUrls) {
            if (url.isBlank()) continue
            cacheDao.deleteByPrefix("chapter_content_${url}_")
            n++
        }
        return n
    }

    /**
     * 清理无效缓存（孤儿）— 任务 #4。
     *
     * 「无效」 = cache 表里某个 sourceUrl 已经没有任何 Book 在用。覆盖三种情况：
     *  1. 用户从书架删了书，BookSource 还在；
     *  2. 用户给某本书换了源，旧源缓存留下；
     *  3. 用户彻底删了 BookSource，cache 还残留。
     *
     * 算法：拉所有书的 sourceUrl 集合 → 扫描 chapter_content_* 全部 key → 对每个 key
     * 检查是否有任何 active sourceUrl 是它的前缀；不匹配就删。
     *
     * 注意：sourceUrl 中可能含 `_`（如 `https://book_x.com`），所以不用从 key 反推
     * sourceUrl，而是用 startsWith 暴力匹配。一次扫描 O(N×M)，N=cache 条目数，
     * M=活跃源数，量级万级×百级仍可秒级完成。
     *
     * @return 删除的孤儿条目数。
     */
    suspend fun clearOrphanedCache(): Int = withContext(Dispatchers.IO) {
        val activeSourceUrls = bookDao.getAllBooksSync()
            .mapNotNull { it.sourceUrl?.takeIf { url -> url.isNotBlank() } }
            .toSet()
        // 即使一本书都没有也得扫描（删全部）—— 允许返回正数代表确实清了东西。
        val keys = cacheDao.getAllChapterContentKeys()
        var deleted = 0
        for (key in keys) {
            // key 形如 chapter_content_<sourceUrl>_<chapterUrl>。
            val isOrphan = activeSourceUrls.none { src ->
                key.startsWith("chapter_content_${src}_")
            }
            if (isOrphan) {
                cacheDao.delete(key)
                deleted++
            }
        }
        AppLog.info("CacheCleanup", "Cleared $deleted orphaned cache entries (${activeSourceUrls.size} active sources)")
        deleted
    }

    fun startDownload(bookId: String, sourceUrl: String, startIndex: Int = 0, endIndex: Int = -1) {
        CacheBookService.start(context, bookId, sourceUrl, startIndex, endIndex)
    }

    fun stopDownload() {
        CacheBookService.stop(context)
    }

    /**
     * Export a cached web book to a plain-text TXT file at [outputUri] (chosen via SAF
     * CreateDocument by the UI). Returns the count of chapters successfully written.
     *
     * **Range support** (Stage A of P0 #1):
     *  - [startIndex] / [endIndex] are 0-based indices into the *non-volume* chapter
     *    list (volume entries are filtered out before indexing — that's what TXT
     *    export has always done). [endIndex] = -1 means "to the last chapter",
     *    matching the convention CacheBookService.start uses for download ranges.
     *  - Header (book name / author / intro / divider) is only written when the
     *    range starts at chapter 0, so a partial export ("第 50–80 章") doesn't
     *    awkwardly repeat the book metadata. The caller sees this through the
     *    [includeHeader] override (default = auto by startIndex).
     *  - Out-of-range / inverted ranges are clamped silently — better partial
     *    export than throwing for the user.
     *
     * Format (full export):
     *   <title>\n
     *   作者: <author>\n
     *   <intro>\n\n────────\n\n
     *   <chapter 1 title + body, ContentProcessor-cleaned>\n\n
     *   <chapter 2 …>
     *
     * Chapters not yet cached are skipped (logged but not aborted) — partial export
     * is more useful than failing the whole file.
     */
    suspend fun exportTxt(
        book: Book,
        outputUri: Uri,
        startIndex: Int = 0,
        endIndex: Int = -1,
        includeHeader: Boolean = startIndex <= 0,
        onProgress: (current: Int, total: Int) -> Unit = { _, _ -> },
    ): Int = withContext(Dispatchers.IO) {
        val sourceUrl = book.sourceUrl ?: return@withContext 0
        val allChapters = chapterDao.getChaptersList(book.id).filter { !it.isVolume && it.url.isNotBlank() }
        if (allChapters.isEmpty()) return@withContext 0

        // Clamp [startIndex, endIndex] into a sane sub-list. endIndex = -1 → last.
        val from = startIndex.coerceIn(0, allChapters.lastIndex)
        val to = if (endIndex < 0) allChapters.lastIndex else endIndex.coerceIn(from, allChapters.lastIndex)
        val chapters = allChapters.subList(from, to + 1)
        val isFullRange = from == 0 && to == allChapters.lastIndex
        val processor = ContentProcessor(book.title, book.originName, replaceRuleDao)

        var written = 0
        try {
            context.contentResolver.openOutputStream(outputUri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use { bw ->
                if (includeHeader) {
                    // Header (full book metadata)
                    bw.write(book.title)
                    bw.newLine()
                    if (book.author.isNotBlank()) {
                        bw.write("作者：${book.author}")
                        bw.newLine()
                    }
                    if (!book.description.isNullOrBlank()) {
                        bw.newLine()
                        bw.write(book.description.trim())
                        bw.newLine()
                    }
                    if (!isFullRange) {
                        // 给范围导出的文件加一行可见标识，避免用户混淆「这文件是不是完整的」。
                        bw.newLine()
                        bw.write("（范围导出：第 ${from + 1} 章 — 第 ${to + 1} 章 / 共 ${allChapters.size} 章）")
                        bw.newLine()
                    }
                    bw.newLine()
                    bw.write("─".repeat(20))
                    bw.newLine()
                    bw.newLine()
                }
                // Chapters
                for ((offset, ch) in chapters.withIndex()) {
                    val key = "chapter_content_${sourceUrl}_${ch.url}"
                    val cached = cacheDao.get(key)?.value
                    if (cached.isNullOrBlank()) {
                        AppLog.warn("Export", "skip uncached chapter [${offset + 1}/${chapters.size}] ${ch.title}")
                        onProgress(offset + 1, chapters.size)
                        continue
                    }
                    val processed = try {
                        processor.process(ch.title, cached, useReplace = true, includeTitle = true)
                    } catch (_: Exception) {
                        // ContentProcessor failure should not kill export — write raw.
                        "${ch.title}\n\n$cached"
                    }
                    bw.write(processed)
                    bw.newLine()
                    bw.newLine()
                    written++
                    onProgress(offset + 1, chapters.size)
                }
                bw.flush()
            } ?: run {
                AppLog.warn("Export", "openOutputStream returned null for $outputUri")
            }
        } catch (e: Exception) {
            AppLog.warn("Export", "TXT export failed: ${e.message?.take(160)}")
            throw e
        }
        written
    }

    /**
     * Lightweight chapter-list peek used by the range-export dialog so the UI can
     * show the user "第 N 章 标题" entries to pick start/end against. Returns only
     * non-volume, valid-URL chapters — same filter [exportTxt] applies — so the
     * indices the user sees here line up 1:1 with the indices [exportTxt] uses.
     */
    suspend fun listExportableChapters(bookId: String): List<com.morealm.app.domain.entity.BookChapter> =
        withContext(Dispatchers.IO) {
            chapterDao.getChaptersList(bookId).filter { !it.isVolume && it.url.isNotBlank() }
        }

    /**
     * Stage B: 导出已缓存章节为 EPUB3。复用 [EpubExporter] 手写 zip，输出与 Legado
     * 兼容的目录结构（mimetype + META-INF/container.xml + OEBPS/{content.opf, nav.xhtml,
     * toc.ncx, Text/, Styles/, Images/}）。
     *
     * 范围参数与 [exportTxt] 同义：startIndex/endIndex 为 0-based 在「非卷标且有 URL」
     * 章节列表上的下标；endIndex=-1 表示到末章。
     *
     * 未缓存章节的处理：仍占 spine 一席，正文显示「（此章未缓存…）」占位 — 这样目录
     * 完整、用户后续补缓存重导后位置不会变。返回值是「实际写入有内容的章节数」。
     *
     * 封面：调用方提供 [coverBytes]（JPEG）。Repository 这里不下载 — 上层 ViewModel
     * 通常通过 Coil 拿到 Bitmap 再压成 jpeg 字节，避免 Repository 层引入图片依赖。
     */
    suspend fun exportEpub(
        book: Book,
        outputUri: Uri,
        startIndex: Int = 0,
        endIndex: Int = -1,
        coverBytes: ByteArray? = null,
        onProgress: (current: Int, total: Int) -> Unit = { _, _ -> },
    ): Int = withContext(Dispatchers.IO) {
        val sourceUrl = book.sourceUrl ?: return@withContext 0
        val allChapters = chapterDao.getChaptersList(book.id).filter { !it.isVolume && it.url.isNotBlank() }
        if (allChapters.isEmpty()) return@withContext 0

        val from = startIndex.coerceIn(0, allChapters.lastIndex)
        val to = if (endIndex < 0) allChapters.lastIndex else endIndex.coerceIn(from, allChapters.lastIndex)
        val chapters = allChapters.subList(from, to + 1)
        val isFullRange = from == 0 && to == allChapters.lastIndex
        val rangeNote = if (isFullRange) "" else
            "范围导出：第 ${from + 1} 章 — 第 ${to + 1} 章 / 全书共 ${allChapters.size} 章"

        val processor = ContentProcessor(book.title, book.originName, replaceRuleDao)

        // 章节正文供给器：被 EpubExporter.writeTo 在它自己的 suspend 上下文里调用。
        // 注意必须显式标 `suspend (Int) -> String?` —— 把 lambda 直接写在
        // `contentProvider = { ... }` 上时，Kotlin 编译器在某些 named-argument 组合
        // 下不会把 lambda 推断为 suspend，结果体内 `cacheDao.get(...)` 报
        // "Suspension functions can only be called within coroutine body."
        // 抽到带类型注解的 val 上消除歧义。
        val contentProvider: suspend (Int) -> String? = { localIdx ->
            val ch = chapters[localIdx]
            val key = "chapter_content_${sourceUrl}_${ch.url}"
            val cached = cacheDao.get(key)?.value
            if (cached.isNullOrBlank()) {
                AppLog.warn("EpubExport", "skip uncached [${localIdx + 1}/${chapters.size}] ${ch.title}")
                null
            } else {
                runCatching {
                    processor.process(ch.title, cached, useReplace = true, includeTitle = false)
                }.getOrElse { cached }
            }
        }

        val exporter = EpubExporter(
            book = book,
            chapters = chapters,
            contentProvider = contentProvider,
            coverBytes = coverBytes,
            rangeNote = rangeNote,
            onProgress = onProgress,
        )

        var written = 0
        try {
            context.contentResolver.openOutputStream(outputUri, "wt")?.use { os ->
                written = exporter.writeTo(context, os)
            } ?: AppLog.warn("EpubExport", "openOutputStream returned null for $outputUri")
        } catch (e: Exception) {
            AppLog.warn("EpubExport", "EPUB export failed: ${e.message?.take(160)}")
            throw e
        }
        written
    }

    /**
     * Stage C：把范围 [startIndex, endIndex] 内的章节按 [volumeSize] 章一卷切成多个
     * EPUB 文件，写入用户挑选的目录树 [treeUri]（来自 SAF `OpenDocumentTree`）。
     *
     * 文件名格式：`<safe-title>_第X-Y章_卷i_共N卷.epub` —— 用户在文件管理器里一眼能
     * 看出每个文件的对应范围，避免「卷1.epub」这种不知道含哪些章的命名。
     *
     * 实现：直接复用 [exportEpub]，把每个子区间当作独立的范围导出走一遍。任何一卷
     * 写失败不会中断后续卷（log warn 后继续）—— 比中途整个崩好得多，用户至少留下
     * 已成功的卷。
     *
     * 返回：`(成功写入的卷数, 累计写入的有内容章节数)`。
     *
     * 进度回调 [onProgress] 携带 `(volIdx 1-based, volTotal, chapterCurInVol, chapterTotalInVol)`
     * 让 UI 能拼出"卷 i/N · 章 x/y"这样的两层进度。
     */
    suspend fun exportEpubMultiVolume(
        book: Book,
        treeUri: Uri,
        startIndex: Int,
        endIndex: Int,
        volumeSize: Int,
        coverBytes: ByteArray? = null,
        onProgress: (volIdx: Int, volTotal: Int, chapterCurInVol: Int, chapterTotalInVol: Int) -> Unit =
            { _, _, _, _ -> },
    ): Pair<Int, Int> = withContext(Dispatchers.IO) {
        if (book.sourceUrl == null) return@withContext 0 to 0
        val allChapters = chapterDao.getChaptersList(book.id)
            .filter { !it.isVolume && it.url.isNotBlank() }
        if (allChapters.isEmpty()) return@withContext 0 to 0

        val from = startIndex.coerceIn(0, allChapters.lastIndex)
        val to = if (endIndex < 0) allChapters.lastIndex
                 else endIndex.coerceIn(from, allChapters.lastIndex)
        val size = volumeSize.coerceAtLeast(1)

        val tree = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, treeUri)
        if (tree == null || !tree.isDirectory) {
            AppLog.warn("EpubExportMV", "tree URI not a directory: $treeUri")
            return@withContext 0 to 0
        }

        // 切区间 — 闭区间，每卷 size 章。最后一卷可能不足 size。
        val ranges = buildList {
            var s = from
            while (s <= to) {
                val e = minOf(to, s + size - 1)
                add(s..e)
                s = e + 1
            }
        }
        val volTotal = ranges.size
        // FAT/exFAT 文件名禁字符；title 截短防止全路径过长。
        val safeTitle = book.title.replace(Regex("""[\\/:*?"<>|]"""), "_").take(50)

        var volumesWritten = 0
        var chaptersWritten = 0
        ranges.forEachIndexed { idx, range ->
            val volIdx = idx + 1
            val displayName =
                "${safeTitle}_第${range.first + 1}-${range.last + 1}章_卷${volIdx}共${volTotal}卷.epub"
            val fileDoc = try {
                tree.createFile("application/epub+zip", displayName)
            } catch (e: Exception) {
                AppLog.warn("EpubExportMV", "createFile threw: ${e.message?.take(120)}")
                null
            }
            if (fileDoc == null) {
                AppLog.warn("EpubExportMV", "createFile returned null: $displayName")
                return@forEachIndexed
            }
            val perVol = range.last - range.first + 1
            try {
                val w = exportEpub(
                    book = book,
                    outputUri = fileDoc.uri,
                    startIndex = range.first,
                    endIndex = range.last,
                    coverBytes = coverBytes,
                ) { cur, _ -> onProgress(volIdx, volTotal, cur, perVol) }
                volumesWritten++
                chaptersWritten += w
            } catch (e: Exception) {
                AppLog.warn("EpubExportMV", "vol $volIdx failed: ${e.message?.take(120)}")
                // 不 rethrow — 让后续卷继续，最后由调用方根据 (vol,ch) 计数判断是否全成
            }
        }
        volumesWritten to chaptersWritten
    }
}
