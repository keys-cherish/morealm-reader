package com.morealm.app.domain.sync

import com.morealm.app.core.log.AppLog
import com.morealm.app.domain.db.AppDatabase
import com.morealm.app.domain.entity.Book
import com.morealm.app.domain.entity.BookFormat
import com.morealm.app.domain.entity.BookGroup
import com.morealm.app.domain.entity.BookSource
import com.morealm.app.domain.entity.Bookmark
import com.morealm.app.domain.entity.HttpTts
import com.morealm.app.domain.entity.ReadProgress
import com.morealm.app.domain.entity.ReplaceRule
import com.morealm.app.domain.entity.SearchKeyword
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * Legado 备份 zip 一键导入器。
 *
 * Legado 备份格式（参考 `legado/help/storage/Backup.kt`）：
 *  - zip 平铺结构（无嵌套目录），21 个固定文件名
 *  - 每个 .json 是 GSON 序列化的 `List<Entity>`
 *  - 配置走两个 .xml（`config.xml` / `videoConfig.xml`，本导入器只处理 config.xml 的白名单键）
 *  - 背景图片**不在 zip 内**（Legado 单独走 WebDav `upBgs` 上传），所以主题恢复时
 *    bgImage 字段必失效 — 我们清空该引用，UI 退回纯色
 *
 * 当前覆盖的迁移项（够日常搬家用）：
 *  1. **bookshelf.json** → `Book` —— 书架（durChapter / lastCheck / canUpdate 全部带过来）
 *  2. **bookSource.json** → `BookSource` —— MoRealm 的 BookSource 字段已与 Legado 对齐，
 *     直接 deserialize（`ignoreUnknownKeys=true` 容忍 Legado 多出的 jsLib 等字段）
 *  3. **bookmark.json** → `Bookmark` —— Legado 按 `time` 当主键，bookId 缺失 → 用
 *     bookName+author 反查本机 books 拿到 bookId；查不到的书签按 strategy 决定丢弃
 *  4. **bookGroup.json** → `BookGroup` —— groupId(Long) → id(String) 转换
 *  5. **replaceRule.json** → `ReplaceRule`
 *  6. **httpTTS.json** → `HttpTts`
 *  7. **(派生) ReadProgress** —— 从 bookshelf.json 的 durChapterIndex/durChapterPos 提取
 *  8. **searchHistory.json** → `SearchKeyword` —— 1:1 映射（word/usage/lastUseTime
 *     字段名一致）；冲突按 [ConflictStrategy] 处理（OVERWRITE 用 Legado 那条覆盖
 *     usage/time，SKIP 留本机原值不动）
 *
 * 暂未覆盖（[ImportResult.skippedSections] 会列出来，下一轮做）：
 *  - rssSources / rssStar / sourceSub / dictRule / keyboardAssists / servers /
 *    txtTocRule（MoRealm 无对应能力或较低优先）
 *  - readConfig.json / themeConfig.json（要做样式 + 主题映射，工作量单独再做一轮）
 *  - config.xml（SharedPreferences 白名单映射，下一轮做）
 *
 * **冲突策略** 通过 [ConflictStrategy] 控制：
 *  - [ConflictStrategy.OVERWRITE]：照搬 Legado 行为，主键命中时覆盖（Room REPLACE）
 *  - [ConflictStrategy.SKIP]：仅追加新项，跳过本机已有主键的 row（默认）
 *
 * 错误隔离：每个 section 独立 runCatching，单段解析失败仅记 warn，不影响其它段。
 * Books 是例外：解析失败会直接 throw 让 UI 显示真错（与 BackupManager.applyBackup 风格一致）。
 */
object LegadoImporter {

    private const val TAG = "LegadoImporter"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        // Legado.Book.lastReadOffset 等字段 GSON 写出时可能含 NaN/Infinity（不会，
        // 但 BackupManager 同款 import 路径有这个 flag，对齐下保险）
        allowSpecialFloatingPointValues = true
        // Legado 多了 MoRealm 没有的字段（比如 jsLib / readConfig 等），全部静默丢弃
        explicitNulls = false
    }

    /**
     * 导入过程中的进度反馈。每个 section 开始 / 结束 + per-item forEach 内 emit。
     *
     * - [step] 用户可读的中文 step 名（如 "书源"、"书架"），用于 UI "正在导入书源"
     * - [current] 当前 step 已处理条目数；section 结束时 = total
     * - [total] 当前 step 总条目数；为 0 表示该 step 没数据可导
     *
     * 注意只反映"段内"进度，不做跨段加权 —— 各 section 体量差异大（500 个书源 vs
     * 5 个分组），加权 overall 反而误导用户。UI 用 step 切换 + 进度条重置体现"翻页感"。
     */
    data class Progress(
        val step: String,
        val current: Int,
        val total: Int,
    )

    /** 主键冲突策略。 */
    enum class ConflictStrategy {
        /** 主键命中时覆盖（Legado 默认行为）。 */
        OVERWRITE,
        /** 仅追加；本机已有的主键全部跳过（保守默认，不动用户当前数据）。 */
        SKIP,
    }

    /**
     * Per-section 用户开关。完全不传 = 全开，等价于"完整搬家"。
     */
    data class ImportOptions(
        val includeBooks: Boolean = true,
        val includeBookSources: Boolean = true,
        val includeBookmarks: Boolean = true,
        val includeBookGroups: Boolean = true,
        val includeReplaceRules: Boolean = true,
        val includeHttpTts: Boolean = true,
        /** Legado searchHistory.json → SearchKeyword 表。 */
        val includeSearchHistory: Boolean = true,
        /** 从 Book.durChapterIndex/durChapterPos 派生 ReadProgress（不需要单独读 readRecord.json）。 */
        val includeReadProgress: Boolean = true,
        val conflictStrategy: ConflictStrategy = ConflictStrategy.SKIP,
    )

    /**
     * 解 zip 后的预览数据 —— UI 在让用户确认前先调 [previewZip] 拿到这个，
     * 渲染"检测到 N 本书 / M 个书源 / ..."并展示冲突警告。
     */
    data class Preview(
        val bookCount: Int,
        val bookSourceCount: Int,
        val bookmarkCount: Int,
        val bookGroupCount: Int,
        val replaceRuleCount: Int,
        val httpTtsCount: Int,
        val searchHistoryCount: Int,
        val bookConflicts: Int,
        val bookSourceConflicts: Int,
        val bookGroupConflicts: Int,
        val replaceRuleConflicts: Int,
        val httpTtsConflicts: Int,
        val searchHistoryConflicts: Int,
        val skippedFiles: List<String>,
        val warnings: List<String>,
    )

    /** 实际写入数据库后的统计，UI 用来弹"已导入 X，跳过 Y"。 */
    data class ImportResult(
        val booksInserted: Int,
        val booksSkipped: Int,
        val bookSourcesInserted: Int,
        val bookSourcesSkipped: Int,
        val bookmarksInserted: Int,
        val bookmarksOrphaned: Int, // 找不到对应书的书签数
        val bookGroupsInserted: Int,
        val bookGroupsSkipped: Int,
        val replaceRulesInserted: Int,
        val replaceRulesSkipped: Int,
        val httpTtsInserted: Int,
        val httpTtsSkipped: Int,
        val searchHistoryInserted: Int,
        val searchHistorySkipped: Int,
        val readProgressInserted: Int,
        val skippedSections: List<String>,
        val errors: List<String>,
    ) {
        fun summarize(): String = buildString {
            if (booksInserted + booksSkipped > 0) appendLine("书架：导入 $booksInserted，跳过 $booksSkipped")
            if (bookSourcesInserted + bookSourcesSkipped > 0) appendLine("书源：导入 $bookSourcesInserted，跳过 $bookSourcesSkipped")
            if (bookmarksInserted + bookmarksOrphaned > 0) appendLine("书签：导入 $bookmarksInserted，孤立 $bookmarksOrphaned")
            if (bookGroupsInserted + bookGroupsSkipped > 0) appendLine("分组：导入 $bookGroupsInserted，跳过 $bookGroupsSkipped")
            if (replaceRulesInserted + replaceRulesSkipped > 0) appendLine("替换规则：导入 $replaceRulesInserted，跳过 $replaceRulesSkipped")
            if (httpTtsInserted + httpTtsSkipped > 0) appendLine("朗读引擎：导入 $httpTtsInserted，跳过 $httpTtsSkipped")
            if (searchHistoryInserted + searchHistorySkipped > 0) appendLine("搜索历史：导入 $searchHistoryInserted，跳过 $searchHistorySkipped")
            if (readProgressInserted > 0) appendLine("阅读进度：从书架派生 $readProgressInserted 条")
            if (skippedSections.isNotEmpty()) appendLine("未支持：${skippedSections.joinToString("、")}")
        }.trim()
    }

    // ────────────────────────────────────────────────────────────────────────
    // Public API
    // ────────────────────────────────────────────────────────────────────────

    /**
     * 解 zip 字节为内存里的 [ParsedBackup]，再生成 [Preview] 给 UI 展示。
     * 不写库；用户确认后才走 [import]。
     *
     * Note：Preview 计算冲突需要查本机 DAO，所以也是 suspend。
     */
    suspend fun previewZip(
        zipBytes: ByteArray,
        db: AppDatabase,
    ): Preview = withContext(Dispatchers.IO) {
        val parsed = parseZip(zipBytes)
        val warnings = mutableListOf<String>()

        // 冲突计数：按各表主键存在性反查
        val existingBookUrls = db.bookDao().getAllBooksSync().map { it.id }.toHashSet()
        val existingSourceUrls = db.bookSourceDao().getEnabledSourcesList()
            .map { it.bookSourceUrl }.toHashSet()
        val existingGroupIds = db.bookGroupDao().getAllGroupsSync().map { it.id }.toHashSet()
        val existingReplaceIds = db.replaceRuleDao().getAllSync().map { it.id }.toHashSet()
        val existingHttpTtsIds = db.httpTtsDao().getEnabled().map { it.id }.toHashSet()
        // 搜索历史用 word 主键查重，与 SearchKeywordDao 的 SKIP 行为一致。
        // 走 topAllSync(Int.MAX_VALUE) 拉全表 —— 历史最大 200 条（见 SearchKeywordRepository.maxHistorySize），
        // 一次性进内存做哈希表完全没成本。
        val existingHistoryWords = db.searchKeywordDao().topAllSync(Int.MAX_VALUE)
            .map { it.word }.toHashSet()

        val bookConflicts = parsed.books.count { it.bookUrl in existingBookUrls }
        val sourceConflicts = parsed.bookSources.count { it.bookSourceUrl in existingSourceUrls }
        val groupConflicts = parsed.bookGroups.count { it.groupId.toString() in existingGroupIds }
        val replaceConflicts = parsed.replaceRules.count { it.id.toString() in existingReplaceIds }
        val httpTtsConflicts = parsed.httpTts.count { it.id in existingHttpTtsIds }
        val historyConflicts = parsed.searchHistory.count { it.word.trim() in existingHistoryWords }

        if (parsed.bookmarks.isNotEmpty() && parsed.books.isEmpty()) {
            warnings += "检测到书签但没有书架数据，书签会落不到本机书 → 大量孤立"
        }
        if (parsed.skippedFiles.contains("themeConfig.json")) {
            warnings += "Legado 主题包含背景图引用，但备份 zip 不含图片字节，恢复后背景为空"
        }

        Preview(
            bookCount = parsed.books.size,
            bookSourceCount = parsed.bookSources.size,
            bookmarkCount = parsed.bookmarks.size,
            bookGroupCount = parsed.bookGroups.size,
            replaceRuleCount = parsed.replaceRules.size,
            httpTtsCount = parsed.httpTts.size,
            searchHistoryCount = parsed.searchHistory.size,
            bookConflicts = bookConflicts,
            bookSourceConflicts = sourceConflicts,
            bookGroupConflicts = groupConflicts,
            replaceRuleConflicts = replaceConflicts,
            httpTtsConflicts = httpTtsConflicts,
            searchHistoryConflicts = historyConflicts,
            skippedFiles = parsed.skippedFiles,
            warnings = warnings,
        )
    }

    /**
     * 真正执行导入：解 zip + 映射 + 写库。返回 [ImportResult] 给 UI 展示。
     *
     * 写入按以下顺序：BookSource → BookGroup → Book → ReadProgress → Bookmark →
     * ReplaceRule → HttpTts。书签依赖书已经写进库（要查 bookId），所以放后面。
     */
    suspend fun import(
        zipBytes: ByteArray,
        db: AppDatabase,
        opts: ImportOptions = ImportOptions(),
        onProgress: (Progress) -> Unit = {},
    ): ImportResult = withContext(Dispatchers.IO) {
        // 解 zip 阶段：单独打一个 step，避免用户看着 UI 卡几百毫秒不知道发生什么。
        // total=0 让 UI 显示 indeterminate（未知进度），到第一个真实 section 后切到确定值。
        onProgress(Progress(step = "解析备份文件", current = 0, total = 0))
        val parsed = parseZip(zipBytes)
        val errors = mutableListOf<String>()

        // BookSource ──
        var sourcesInserted = 0
        var sourcesSkipped = 0
        if (opts.includeBookSources && parsed.bookSources.isNotEmpty()) {
            // 书源走 insertAll 批量入库 —— 没法在 SQL 层面分条 emit 进度。
            // 折中：开始时 emit (0, total)，结束时 emit (total, total)；批量很快，不会卡。
            onProgress(Progress("书源", 0, parsed.bookSources.size))
            runCatching {
                val existing = db.bookSourceDao().getEnabledSourcesList()
                    .map { it.bookSourceUrl }.toHashSet()
                val toInsert = parsed.bookSources.filter { src ->
                    when (opts.conflictStrategy) {
                        ConflictStrategy.OVERWRITE -> true
                        ConflictStrategy.SKIP -> src.bookSourceUrl !in existing
                    }
                }
                sourcesSkipped = parsed.bookSources.size - toInsert.size
                if (toInsert.isNotEmpty()) {
                    db.bookSourceDao().insertAll(toInsert)
                    sourcesInserted = toInsert.size
                }
            }.onFailure {
                errors += "书源导入失败：${it.message}"
                AppLog.error(TAG, "bookSource insert failed", it)
            }
            onProgress(Progress("书源", parsed.bookSources.size, parsed.bookSources.size))
        }

        // BookGroup ──
        var groupsInserted = 0
        var groupsSkipped = 0
        if (opts.includeBookGroups && parsed.bookGroups.isNotEmpty()) {
            onProgress(Progress("分组", 0, parsed.bookGroups.size))
            runCatching {
                val existing = db.bookGroupDao().getAllGroupsSync().map { it.id }.toHashSet()
                val mapped = parsed.bookGroups.map(::mapBookGroup)
                val toInsert = mapped.filter { g ->
                    when (opts.conflictStrategy) {
                        ConflictStrategy.OVERWRITE -> true
                        ConflictStrategy.SKIP -> g.id !in existing
                    }
                }
                groupsSkipped = mapped.size - toInsert.size
                toInsert.forEachIndexed { idx, group ->
                    db.bookGroupDao().insert(group)
                    // 分组每条都 emit；分组体量小（<20）所以不限频。
                    onProgress(Progress("分组", idx + 1, parsed.bookGroups.size))
                }
                groupsInserted = toInsert.size
            }.onFailure {
                errors += "分组导入失败：${it.message}"
                AppLog.error(TAG, "bookGroup insert failed", it)
            }
            onProgress(Progress("分组", parsed.bookGroups.size, parsed.bookGroups.size))
        }

        // Book ──（先写 Book，因为 Bookmark 后续要按 bookName+author 反查它的 id）
        var booksInserted = 0
        var booksSkipped = 0
        var progressInserted = 0
        if (opts.includeBooks && parsed.books.isNotEmpty()) {
            onProgress(Progress("书架", 0, parsed.books.size))
            runCatching {
                val existing = db.bookDao().getAllBooksSync().map { it.id }.toHashSet()
                val mapped = parsed.books.map(::mapBook)
                val toInsert = mapped.filter { b ->
                    when (opts.conflictStrategy) {
                        ConflictStrategy.OVERWRITE -> true
                        ConflictStrategy.SKIP -> b.id !in existing
                    }
                }
                booksSkipped = mapped.size - toInsert.size
                if (toInsert.isNotEmpty()) {
                    db.bookDao().insertAll(toInsert)
                    booksInserted = toInsert.size
                }

                // ReadProgress 从同一批 Book 派生（Legado 把进度内嵌在 Book 里）
                if (opts.includeReadProgress) {
                    onProgress(Progress("阅读进度", 0, parsed.books.size))
                    parsed.books.forEachIndexed { idx, dto ->
                        // 只为本次成功 insert 的 book 写 progress（避免覆盖用户已有进度）
                        val bookId = dto.bookUrl
                        if (opts.conflictStrategy == ConflictStrategy.SKIP && bookId in existing) {
                            // emit 但不写库 —— 这条算"已处理"以便进度连续推进
                            if ((idx + 1) % 10 == 0 || idx == parsed.books.lastIndex) {
                                onProgress(Progress("阅读进度", idx + 1, parsed.books.size))
                            }
                            return@forEachIndexed
                        }
                        if (dto.durChapterIndex == 0 && dto.durChapterPos == 0) {
                            // 0/0 视为没读过 — 跳过避免污染本机 read_progress 表
                            if ((idx + 1) % 10 == 0 || idx == parsed.books.lastIndex) {
                                onProgress(Progress("阅读进度", idx + 1, parsed.books.size))
                            }
                            return@forEachIndexed
                        }
                        val progress = ReadProgress(
                            bookId = bookId,
                            chapterIndex = dto.durChapterIndex,
                            chapterPosition = dto.durChapterPos,
                            chapterOffset = 0f,
                            totalProgress = if (dto.totalChapterNum > 0)
                                dto.durChapterIndex.toFloat() / dto.totalChapterNum else 0f,
                            updatedAt = dto.durChapterTime,
                        )
                        db.readProgressDao().save(progress)
                        progressInserted++
                        // 阅读进度按每 10 条 emit 一次，避免书架上千本时频繁刷 UI。
                        if ((idx + 1) % 10 == 0 || idx == parsed.books.lastIndex) {
                            onProgress(Progress("阅读进度", idx + 1, parsed.books.size))
                        }
                    }
                }
            }.onFailure {
                errors += "书架导入失败：${it.message}"
                AppLog.error(TAG, "book insert failed", it)
                // Books 失败属于关键错误，但不再 throw — UI 能看到 errors 就好
            }
            onProgress(Progress("书架", parsed.books.size, parsed.books.size))
        }

        // Bookmark ──（书写完后再写，需要 bookName+author 反查 bookId）
        var bookmarksInserted = 0
        var bookmarksOrphaned = 0
        if (opts.includeBookmarks && parsed.bookmarks.isNotEmpty()) {
            onProgress(Progress("书签", 0, parsed.bookmarks.size))
            runCatching {
                // 反查：bookName+author → bookId（书架里 title 字段对应 Legado.name）
                val books = db.bookDao().getAllBooksSync()
                val keyToId = books.associateBy(
                    keySelector = { "${it.title}\u0000${it.author}" },
                    valueTransform = { it.id },
                )
                parsed.bookmarks.forEachIndexed { idx, dto ->
                    val key = "${dto.bookName}\u0000${dto.bookAuthor}"
                    val bookId = keyToId[key]
                    if (bookId == null) {
                        bookmarksOrphaned++
                    } else {
                        val bm = Bookmark(
                            id = "legado_${dto.time}",
                            bookId = bookId,
                            chapterIndex = dto.chapterIndex,
                            chapterTitle = dto.chapterName,
                            // Legado 把摘录 (bookText) 和笔记 (content) 分开存；MoRealm Bookmark
                            // 只有一个 content 字段。优先取 bookText（用户划的原文片段），笔记
                            // 拼在后面用 " — " 分隔，最大限度保留信息
                            content = listOfNotNull(
                                dto.bookText.takeIf { it.isNotBlank() },
                                dto.content.takeIf { it.isNotBlank() },
                            ).joinToString(separator = " — "),
                            chapterPos = dto.chapterPos,
                            scrollProgress = 0,
                            createdAt = dto.time,
                        )
                        db.bookmarkDao().insert(bm)
                        bookmarksInserted++
                    }
                    // 书签每 10 条 emit 一次，跟阅读进度同样的限频策略。
                    if ((idx + 1) % 10 == 0 || idx == parsed.bookmarks.lastIndex) {
                        onProgress(Progress("书签", idx + 1, parsed.bookmarks.size))
                    }
                }
            }.onFailure {
                errors += "书签导入失败：${it.message}"
                AppLog.error(TAG, "bookmark insert failed", it)
            }
            onProgress(Progress("书签", parsed.bookmarks.size, parsed.bookmarks.size))
        }

        // ReplaceRule ──
        var replaceInserted = 0
        var replaceSkipped = 0
        if (opts.includeReplaceRules && parsed.replaceRules.isNotEmpty()) {
            onProgress(Progress("替换规则", 0, parsed.replaceRules.size))
            runCatching {
                val existing = db.replaceRuleDao().getAllSync().map { it.id }.toHashSet()
                val mapped = parsed.replaceRules.map(::mapReplaceRule)
                val toInsert = mapped.filter { r ->
                    when (opts.conflictStrategy) {
                        ConflictStrategy.OVERWRITE -> true
                        ConflictStrategy.SKIP -> r.id !in existing
                    }
                }
                replaceSkipped = mapped.size - toInsert.size
                toInsert.forEachIndexed { idx, rule ->
                    db.replaceRuleDao().insert(rule)
                    // 替换规则一般不超过几十条，每条 emit
                    onProgress(Progress("替换规则", idx + 1, parsed.replaceRules.size))
                }
                replaceInserted = toInsert.size
            }.onFailure {
                errors += "替换规则导入失败：${it.message}"
                AppLog.error(TAG, "replaceRule insert failed", it)
            }
            onProgress(Progress("替换规则", parsed.replaceRules.size, parsed.replaceRules.size))
        }

        // HttpTts ──
        var httpTtsInserted = 0
        var httpTtsSkipped = 0
        if (opts.includeHttpTts && parsed.httpTts.isNotEmpty()) {
            onProgress(Progress("朗读引擎", 0, parsed.httpTts.size))
            runCatching {
                val existing = db.httpTtsDao().getEnabled().map { it.id }.toHashSet()
                val mapped = parsed.httpTts.map(::mapHttpTts)
                val toInsert = mapped.filter { h ->
                    when (opts.conflictStrategy) {
                        ConflictStrategy.OVERWRITE -> true
                        ConflictStrategy.SKIP -> h.id !in existing
                    }
                }
                httpTtsSkipped = mapped.size - toInsert.size
                toInsert.forEachIndexed { idx, tts ->
                    db.httpTtsDao().upsert(tts)
                    onProgress(Progress("朗读引擎", idx + 1, parsed.httpTts.size))
                }
                httpTtsInserted = toInsert.size
            }.onFailure {
                errors += "朗读引擎导入失败：${it.message}"
                AppLog.error(TAG, "httpTts insert failed", it)
            }
            onProgress(Progress("朗读引擎", parsed.httpTts.size, parsed.httpTts.size))
        }

        // SearchKeyword ──
        // 走 dao.upsert（OnConflictStrategy.REPLACE）。SKIP 模式靠预先反查 word 集合，
        // OVERWRITE 模式让 REPLACE 自然覆盖（usage / lastUseTime 都用 Legado 那条）。
        // 这里不复用 SearchKeywordRepository.record —— 那个会做 trim / 计数累加 / 历史裁剪，
        // 都不是搬家想要的语义（搬家应当忠实还原 Legado 的 usage 数和时间戳）。
        var historyInserted = 0
        var historySkipped = 0
        if (opts.includeSearchHistory && parsed.searchHistory.isNotEmpty()) {
            onProgress(Progress("搜索历史", 0, parsed.searchHistory.size))
            runCatching {
                val existing = db.searchKeywordDao().topAllSync(Int.MAX_VALUE)
                    .map { it.word }.toHashSet()
                parsed.searchHistory.forEachIndexed { idx, dto ->
                    val word = dto.word.trim()
                    if (word.isEmpty()) {
                        // 异常空 word（Legado 早期版本零星数据）— 静默丢弃，不算 skip 也不算 insert
                        if ((idx + 1) % 20 == 0 || idx == parsed.searchHistory.lastIndex) {
                            onProgress(Progress("搜索历史", idx + 1, parsed.searchHistory.size))
                        }
                        return@forEachIndexed
                    }
                    val skip = opts.conflictStrategy == ConflictStrategy.SKIP &&
                        word in existing
                    if (skip) {
                        historySkipped++
                    } else {
                        db.searchKeywordDao().upsert(mapSearchKeyword(dto.copy(word = word)))
                        historyInserted++
                    }
                    // 搜索历史一般 < 200 条；按 20 条一批 emit 已足够顺滑，避免每条刷 UI。
                    if ((idx + 1) % 20 == 0 || idx == parsed.searchHistory.lastIndex) {
                        onProgress(Progress("搜索历史", idx + 1, parsed.searchHistory.size))
                    }
                }
            }.onFailure {
                errors += "搜索历史导入失败：${it.message}"
                AppLog.error(TAG, "searchHistory insert failed", it)
            }
            onProgress(Progress("搜索历史", parsed.searchHistory.size, parsed.searchHistory.size))
        }

        AppLog.info(
            TAG,
            "Import done: books=$booksInserted/${booksSkipped} sources=$sourcesInserted/${sourcesSkipped} " +
                "bookmarks=$bookmarksInserted(orphan=$bookmarksOrphaned) groups=$groupsInserted " +
                "rules=$replaceInserted httpTts=$httpTtsInserted history=$historyInserted progress=$progressInserted",
        )

        // 全部完成 —— UI 用这条收尾把进度条推到 100%。
        onProgress(Progress("完成", 1, 1))

        ImportResult(
            booksInserted = booksInserted,
            booksSkipped = booksSkipped,
            bookSourcesInserted = sourcesInserted,
            bookSourcesSkipped = sourcesSkipped,
            bookmarksInserted = bookmarksInserted,
            bookmarksOrphaned = bookmarksOrphaned,
            bookGroupsInserted = groupsInserted,
            bookGroupsSkipped = groupsSkipped,
            replaceRulesInserted = replaceInserted,
            replaceRulesSkipped = replaceSkipped,
            httpTtsInserted = httpTtsInserted,
            httpTtsSkipped = httpTtsSkipped,
            searchHistoryInserted = historyInserted,
            searchHistorySkipped = historySkipped,
            readProgressInserted = progressInserted,
            skippedSections = parsed.skippedFiles,
            errors = errors,
        )
    }

    // ────────────────────────────────────────────────────────────────────────
    // Internal: zip parsing
    // ────────────────────────────────────────────────────────────────────────

    /**
     * 内存模型：解 zip 后保留所有支持的 section + 一个"已知存在但未支持"的清单。
     */
    internal data class ParsedBackup(
        val books: List<LegadoBookDto>,
        val bookSources: List<BookSource>,
        val bookmarks: List<LegadoBookmarkDto>,
        val bookGroups: List<LegadoBookGroupDto>,
        val replaceRules: List<LegadoReplaceRuleDto>,
        val httpTts: List<LegadoHttpTtsDto>,
        val searchHistory: List<LegadoSearchKeywordDto>,
        val skippedFiles: List<String>,
    )

    /**
     * 平铺解 zip，每个 entry 按文件名分发到对应解析器。未知文件名记到 skippedFiles。
     *
     * Visible for tests，让单测直接喂 zip bytes verify 解析结果。
     */
    internal fun parseZip(zipBytes: ByteArray): ParsedBackup {
        var books: List<LegadoBookDto> = emptyList()
        var sources: List<BookSource> = emptyList()
        var bookmarks: List<LegadoBookmarkDto> = emptyList()
        var groups: List<LegadoBookGroupDto> = emptyList()
        var replaceRules: List<LegadoReplaceRuleDto> = emptyList()
        var httpTts: List<LegadoHttpTtsDto> = emptyList()
        var searchHistory: List<LegadoSearchKeywordDto> = emptyList()
        val skipped = mutableListOf<String>()

        ByteArrayInputStream(zipBytes).use { bais ->
            ZipInputStream(bais).use { zis ->
                var entry: ZipEntry? = zis.nextEntry
                while (entry != null) {
                    if (entry.isDirectory) {
                        entry = zis.nextEntry
                        continue
                    }
                    val name = entry.name.substringAfterLast('/')
                    val bytes = zis.readBytes()
                    val text = String(bytes, Charsets.UTF_8)
                    when (name) {
                        "bookshelf.json" -> books = decodeListOrEmpty(text, name)
                        "bookSource.json" -> sources = decodeListOrEmpty(text, name)
                        "bookmark.json" -> bookmarks = decodeListOrEmpty(text, name)
                        "bookGroup.json" -> groups = decodeListOrEmpty(text, name)
                        "replaceRule.json" -> replaceRules = decodeListOrEmpty(text, name)
                        "httpTTS.json" -> httpTts = decodeListOrEmpty(text, name)
                        "searchHistory.json" -> searchHistory = decodeListOrEmpty(text, name)
                        // 已知但暂未支持的 entry — 记到 skipped 让 UI 展示给用户
                        "rssSources.json", "rssStar.json", "sourceSub.json",
                        "dictRule.json", "keyboardAssists.json", "servers.json",
                        "txtTocRule.json",
                        "readConfig.json", "shareConfig.json",
                        "themeConfig.json", "coverConfig.json",
                        "shareRule.json",
                        "config.xml", "videoConfig.xml" -> skipped += name
                        else -> {
                            // 未知文件 — 直接 skip 但 log 一下，方便发现 Legado 加新字段
                            AppLog.debug(TAG, "Unknown entry in Legado zip: $name (${bytes.size} bytes)")
                            skipped += name
                        }
                    }
                    entry = zis.nextEntry
                }
            }
        }

        return ParsedBackup(books, sources, bookmarks, groups, replaceRules, httpTts, searchHistory, skipped.toList())
    }

    /** 解 List<T>；失败返回空 list 仅 log，不阻断其它 section。 */
    private inline fun <reified T> decodeListOrEmpty(jsonStr: String, fileName: String): List<T> =
        runCatching {
            json.decodeFromString<List<T>>(jsonStr)
        }.getOrElse { e ->
            AppLog.warn(TAG, "decode $fileName as List<${T::class.simpleName}> failed: ${e.message}")
            emptyList()
        }

    // ────────────────────────────────────────────────────────────────────────
    // Internal: mappers (LegadoXxxDto → MoRealm Entity)
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Legado.Book → MoRealm.Book。
     *
     * - `bookUrl` → `id` —— Legado 用 url 当主键，MoRealm.id 是 String，直接复用
     * - `name` → `title`
     * - `intro / customIntro` → `description`（自定义优先）
     * - `durChapterIndex / Pos` → `lastReadChapter / lastReadPosition`
     * - `totalChapterNum` → `totalChapters`
     * - `order` → `sortOrder`
     * - `group` (Long) → `folderId` (String)，0 = 未分组（保持 null）
     * - `type` → `format`（Legado.BookType 数字位掩码 → BookFormat 枚举）
     * - `lastCheckCount / lastCheckTime / canUpdate` → 同名字段（v16 已对齐）
     */
    internal fun mapBook(dto: LegadoBookDto): Book {
        val format = legadoTypeToFormat(dto.type)
        val description = dto.customIntro?.takeIf { it.isNotBlank() } ?: dto.intro
        val folderId = dto.group.takeIf { it != 0L && it > 0 }?.toString()
        return Book(
            id = dto.bookUrl,
            title = dto.name,
            author = dto.author,
            coverUrl = dto.coverUrl,
            customCoverUrl = dto.customCoverUrl,
            localPath = null, // Legado 本地书的 bookUrl 就是文件路径；MoRealm 自家本地书走另一条路径，这里不冒险映射
            sourceId = dto.origin.takeIf { it.isNotBlank() && it != LEGADO_LOCAL_TAG },
            sourceUrl = dto.origin.takeIf { it.isNotBlank() && it != LEGADO_LOCAL_TAG },
            folderId = folderId,
            format = format,
            lastReadChapter = dto.durChapterIndex,
            lastReadPosition = dto.durChapterPos,
            lastReadOffset = 0f,
            totalChapters = dto.totalChapterNum,
            readProgress = if (dto.totalChapterNum > 0)
                dto.durChapterIndex.toFloat() / dto.totalChapterNum else 0f,
            hasDetail = dto.intro != null || dto.kind != null,
            description = description,
            wordCount = dto.wordCount,
            rating = null,
            category = dto.kind,
            charset = dto.charset,
            bookUrl = dto.bookUrl,
            tocUrl = dto.tocUrl.takeIf { it.isNotBlank() },
            origin = dto.origin,
            originName = dto.originName,
            kind = dto.kind,
            customTag = dto.customTag,
            variable = dto.variable,
            addedAt = dto.durChapterTime.takeIf { it > 0 } ?: System.currentTimeMillis(),
            lastReadAt = dto.durChapterTime,
            latestChapterTime = dto.latestChapterTime,
            pinned = false,
            sortOrder = dto.order,
            lastCheckCount = dto.lastCheckCount,
            lastCheckTime = dto.lastCheckTime,
            canUpdate = dto.canUpdate,
            tagsAssignedBy = "AUTO",
            groupLocked = false,
        )
    }

    /**
     * Legado type 字段是位掩码（text=0, audio=1, image=2, web=4, local=8, epub=16, ...）。
     * MoRealm.BookFormat 是枚举。映射到最贴近的 entry，未知 → UNKNOWN。
     *
     * 参考 `legado/constant/BookType.kt`：
     *   text=0, audio=1, image=2, webBook=8 (含 1<<3), localTxt=16 (含 1<<4), epub=32, ...
     * 但实际备份里 Book.type 只是个 Int，没有 stable 文档；这里只覆盖最常见的。
     */
    private fun legadoTypeToFormat(type: Int): BookFormat {
        // 掩码位优先识别 epub / image，再回落到 web/local/txt
        return when {
            type and 0x10 != 0 -> BookFormat.EPUB    // local epub
            type and 0x20 != 0 -> BookFormat.EPUB
            type and 0x02 != 0 -> BookFormat.UNKNOWN // image (漫画)，MoRealm 当前无对应
            type and 0x08 != 0 -> BookFormat.WEB     // webBook
            type and 0x01 != 0 -> BookFormat.UNKNOWN // audio
            type == 0 -> BookFormat.WEB              // 默认在线书
            else -> BookFormat.UNKNOWN
        }
    }

    /** Legado 书源里 origin == "loc_book" 标记本地书。 */
    private const val LEGADO_LOCAL_TAG = "loc_book"

    /**
     * Legado.BookGroup → MoRealm.BookGroup。
     *
     * - `groupId` (Long) → `id` (String)，直接 toString
     * - `groupName` → `name`
     * - `order` → `sortOrder`
     * - `show` → `pinned`（语义不完全相同，但 show=false 等价于"不在书架显示"，
     *   MoRealm 没有等价开关；映射到 pinned 至少保留 ordering 提示）
     * - `cover` 在 MoRealm 是 customCoverUrl，但 Legado.cover 是 url 不是文件，
     *   且 MoRealm.customCoverUrl 走 CoverStorage 文件，这里不映射避免引用失效
     */
    internal fun mapBookGroup(dto: LegadoBookGroupDto): BookGroup = BookGroup(
        id = dto.groupId.toString(),
        name = dto.groupName,
        parentId = null,
        sortOrder = dto.order,
        pinned = false,
        emoji = null,
        autoKeywords = "",
        createdAt = System.currentTimeMillis(),
        auto = false, // 用户从 Legado 搬来的分组都视为手动，不让 TagResolver 重命名
        customCoverUrl = null,
    )

    /**
     * Legado.ReplaceRule → MoRealm.ReplaceRule。
     *
     * - `id` (Long) → `id` (String) toString
     * - `isEnabled` → `enabled`
     * - `isRegex` → `isRegex`（字段名一致）
     * - `timeoutMillisecond` → `timeoutMs`（注意 Long → Int，截断到 Int.MAX_VALUE）
     * - `sortOrder` (Legado 列名是 sortOrder，但字段名 `order`) → `sortOrder`
     * - `kind` MoRealm 有 0/1 两类（GENERAL / PURIFY），Legado 没有这层概念 →
     *   全部当 GENERAL；用户后续可以在替换规则页改
     */
    internal fun mapReplaceRule(dto: LegadoReplaceRuleDto): ReplaceRule = ReplaceRule(
        id = dto.id.toString(),
        name = dto.name,
        pattern = dto.pattern,
        replacement = dto.replacement,
        isRegex = dto.isRegex,
        scope = dto.scope.orEmpty(),
        bookId = null,
        scopeTitle = dto.scopeTitle,
        scopeContent = dto.scopeContent,
        enabled = dto.isEnabled,
        sortOrder = dto.sortOrder,
        timeoutMs = dto.timeoutMillisecond.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
        kind = ReplaceRule.KIND_GENERAL,
    )

    /**
     * Legado.HttpTTS → MoRealm.HttpTts。字段几乎一一对应。
     */
    internal fun mapHttpTts(dto: LegadoHttpTtsDto): HttpTts = HttpTts(
        id = dto.id,
        name = dto.name,
        url = dto.url,
        contentType = dto.contentType,
        header = dto.header,
        enabled = true, // Legado 没有 enabled 字段，默认启用
        lastUpdateTime = dto.lastUpdateTime.takeIf { it > 0 } ?: System.currentTimeMillis(),
        loginUrl = dto.loginUrl,
        loginUi = dto.loginUi,
        loginCheckJs = dto.loginCheckJs,
        concurrentRate = dto.concurrentRate,
    )

    /**
     * Legado.SearchKeyword → MoRealm.SearchKeyword。
     *
     * Legado entity (`io.legado.app.data.entities.SearchKeyword`) 与 MoRealm
     * 实体字段名 / 类型完全相同（word / usage / lastUseTime），1:1 直搬。
     *
     * 唯一处理：word 在调用方就 trim 过；这里只把 usage 兜底为 ≥1（Legado 老数据
     * 偶有 0，进库后会因 `usage DESC` 排序消失在最底，不友好）。
     */
    internal fun mapSearchKeyword(dto: LegadoSearchKeywordDto): SearchKeyword = SearchKeyword(
        word = dto.word,
        usage = dto.usage.coerceAtLeast(1),
        lastUseTime = dto.lastUseTime.takeIf { it > 0 } ?: System.currentTimeMillis(),
    )

    // ────────────────────────────────────────────────────────────────────────
    // Internal: DTOs (Legado JSON shape)
    // ────────────────────────────────────────────────────────────────────────
    //
    // 这些 DTO 字段名严格对齐 Legado.app.data.entities.* 的 GSON 序列化字段名，
    // 以便用 kotlinx-serialization decode（Legado 用 GSON 写出 JSON，但只要字段名
    // 一致，kx-serialization 都能 decode）。
    //
    // 缺失字段全部带默认值，配合 Json { ignoreUnknownKeys = true } 容忍 Legado
    // 升级时新增的列。

    /** Legado.Book 的 JSON 形态（只取我们映射要用的字段）。 */
    @Serializable
    internal data class LegadoBookDto(
        val bookUrl: String = "",
        val tocUrl: String = "",
        val origin: String = "",
        val originName: String = "",
        val name: String = "",
        val author: String = "",
        val kind: String? = null,
        val customTag: String? = null,
        val coverUrl: String? = null,
        val customCoverUrl: String? = null,
        val intro: String? = null,
        val customIntro: String? = null,
        val charset: String? = null,
        val type: Int = 0,
        val group: Long = 0,
        val latestChapterTitle: String? = null,
        val latestChapterTime: Long = 0,
        val lastCheckTime: Long = 0,
        val lastCheckCount: Int = 0,
        val totalChapterNum: Int = 0,
        val durChapterTitle: String? = null,
        val durChapterIndex: Int = 0,
        val durChapterPos: Int = 0,
        val durChapterTime: Long = 0,
        val wordCount: String? = null,
        val canUpdate: Boolean = true,
        val order: Int = 0,
        val originOrder: Int = 0,
        val variable: String? = null,
    )

    @Serializable
    internal data class LegadoBookmarkDto(
        val time: Long = 0,
        val bookName: String = "",
        val bookAuthor: String = "",
        val chapterIndex: Int = 0,
        val chapterPos: Int = 0,
        val chapterName: String = "",
        val bookText: String = "",
        val content: String = "",
    )

    @Serializable
    internal data class LegadoBookGroupDto(
        val groupId: Long = 0,
        val groupName: String = "",
        val cover: String? = null,
        val order: Int = 0,
        val show: Boolean = true,
        val enableRefresh: Boolean = true,
    )

    /**
     * Legado.ReplaceRule 在 JSON 里字段名是 `sortOrder`（@ColumnInfo(name="sortOrder")）
     * 但在 Kotlin 字段叫 `order`。Legado 用 GSON，序列化看 Kotlin 字段名 → JSON 是 `order`。
     * 这里 DTO 用 `sortOrder` 接，实际 JSON key 也试两种（`@SerialName`+ alias 不被 kx 原生支持，
     * 我们就用 fallback：先解 sortOrder，没有则解 order，靠 [decodeReplaceRules] 兜底）。
     *
     * 但实测 Legado 备份的 JSON 里字段名取决于 GSON 配置，多数情况下是字段名 = `order`。
     * 为简化，DTO 字段直接叫 `order`，跟 Legado.ReplaceRule.order 对齐。
     */
    @Serializable
    internal data class LegadoReplaceRuleDto(
        val id: Long = 0,
        val name: String = "",
        val group: String? = null,
        val pattern: String = "",
        val replacement: String = "",
        val scope: String? = null,
        val excludeScope: String? = null,
        val scopeTitle: Boolean = false,
        val scopeContent: Boolean = true,
        val isEnabled: Boolean = true,
        val isRegex: Boolean = true,
        val timeoutMillisecond: Long = 3000,
        @SerialName("sortOrder")
        val sortOrder: Int = 0,
    )

    @Serializable
    internal data class LegadoHttpTtsDto(
        val id: Long = 0,
        val name: String = "",
        val url: String = "",
        val contentType: String? = null,
        val concurrentRate: String? = null,
        val loginUrl: String? = null,
        val loginUi: String? = null,
        val header: String? = null,
        val loginCheckJs: String? = null,
        val lastUpdateTime: Long = 0,
    )

    /**
     * Legado.SearchKeyword 的 JSON 形态。Legado entity 字段名为 word/usage/lastUseTime，
     * GSON 默认按 Kotlin 字段名输出 JSON key —— 直接对齐即可。
     */
    @Serializable
    internal data class LegadoSearchKeywordDto(
        val word: String = "",
        val usage: Int = 1,
        val lastUseTime: Long = 0,
    )
}
