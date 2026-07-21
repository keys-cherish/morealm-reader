package com.morealm.app.domain.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.paging.PagingSource
import com.morealm.app.domain.entity.*
import kotlinx.coroutines.flow.Flow


@Dao
interface BookDao {
    @Query("SELECT * FROM books WHERE folderId IS :folderId AND inBookshelf = 1 ORDER BY lastReadAt DESC")
    fun getBooksInFolder(folderId: String?): Flow<List<Book>>

    // 续读入口：不过滤 inBookshelf —— 只有真正读过（lastReadAt>0）的书才浮现，
    // 让"试读但未加入"的书也能从续读卡 / 桌面 widget 恢复阅读。
    @Query("SELECT * FROM books ORDER BY lastReadAt DESC LIMIT 1")
    fun getLastReadBook(): Flow<Book?>

    // 按 key 查：不过滤 —— 加载 / 去重必须无视在架状态。
    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun getById(id: String): Book?

    @Query("SELECT * FROM books WHERE inBookshelf = 1 ORDER BY lastReadAt DESC")
    fun getAllBooks(): Flow<List<Book>>

    /** 首页阅读历史：包含已实际打开的试读书，按最近访问优先排列。 */
    @Query("SELECT * FROM books WHERE lastReadAt > 0 ORDER BY lastReadAt DESC")
    fun getReadingHistory(): Flow<List<Book>>

    @Query("SELECT * FROM books WHERE inBookshelf = 1 ORDER BY title COLLATE NOCASE")
    fun getAllBooksPaging(): PagingSource<Int, Book>

    @Query("SELECT * FROM books WHERE folderId IS NULL AND inBookshelf = 1 ORDER BY title COLLATE NOCASE")
    fun getUngroupedBooksPaging(): PagingSource<Int, Book>

    @Query("SELECT * FROM books WHERE folderId IS NULL AND inBookshelf = 1 ORDER BY lastReadAt DESC")
    fun getUngroupedBooksByRecent(): PagingSource<Int, Book>

    @Query("SELECT * FROM books WHERE folderId IS NULL AND inBookshelf = 1 ORDER BY addedAt DESC")
    fun getUngroupedBooksByAddTime(): PagingSource<Int, Book>

    @Query("SELECT * FROM books WHERE folderId IS NULL AND inBookshelf = 1 ORDER BY format, title COLLATE NOCASE")
    fun getUngroupedBooksByFormat(): PagingSource<Int, Book>

    @Query("SELECT * FROM books WHERE inBookshelf = 1 ORDER BY lastReadAt DESC")
    fun getAllBooksByRecent(): PagingSource<Int, Book>

    @Query("SELECT * FROM books WHERE inBookshelf = 1 ORDER BY addedAt DESC")
    fun getAllBooksByAddTime(): PagingSource<Int, Book>

    @Query("SELECT * FROM books WHERE inBookshelf = 1 ORDER BY format, title COLLATE NOCASE")
    fun getAllBooksByFormat(): PagingSource<Int, Book>

    @Query("SELECT COUNT(*) FROM books WHERE folderId = :folderId AND inBookshelf = 1")
    fun countByFolderId(folderId: String): Flow<Int>

    @Query("SELECT * FROM books WHERE folderId = :folderId AND inBookshelf = 1 ORDER BY title COLLATE NOCASE")
    fun getBooksByFolderPaging(folderId: String): PagingSource<Int, Book>

    @Query("SELECT * FROM books WHERE folderId = :folderId AND inBookshelf = 1 ORDER BY lastReadAt DESC")
    fun getBooksByFolderByRecent(folderId: String): PagingSource<Int, Book>

    @Query("SELECT * FROM books WHERE folderId = :folderId AND inBookshelf = 1 ORDER BY addedAt DESC")
    fun getBooksByFolderByAddTime(folderId: String): PagingSource<Int, Book>

    @Query("SELECT * FROM books WHERE folderId = :folderId AND inBookshelf = 1 ORDER BY format, title COLLATE NOCASE")
    fun getBooksByFolderByFormat(folderId: String): PagingSource<Int, Book>

    @Query("SELECT * FROM books WHERE (title LIKE :keyword OR author LIKE :keyword) AND inBookshelf = 1 ORDER BY lastReadAt DESC")
    suspend fun searchBooks(keyword: String): List<Book>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(book: Book)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(books: List<Book>)

    @Update
    suspend fun update(book: Book)

    /**
     * 批量 update：@Transaction 让 forEach 内的多次 update 在单 SQLite 事务内执行，
     * 1000 本书入库速度从 5-10s 降到 < 500ms（避免每行各开一次事务）。
     * 调用方应自行 chunk 控制单次 size（≤ 100 为佳，避免长事务影响读路径）。
     */
    @Transaction
    suspend fun updateAll(books: List<Book>) {
        books.forEach { update(it) }
    }

    /**
     * Atomic field-level update used by ShelfRefreshController so a Toc-refresh
     * pass doesn't have to round-trip the full Book row (which races with the
     * user's edits to the same row from other screens).
     */
    @Query("UPDATE books SET totalChapters = :total, lastCheckCount = :newCount, lastCheckTime = :time WHERE id = :id")
    suspend fun updateLastCheck(id: String, total: Int, newCount: Int, time: Long)

    /** Reset the "N 新" badge counter — called when user opens the book. */
    @Query("UPDATE books SET lastCheckCount = 0 WHERE id = :id")
    suspend fun clearLastCheckCount(id: String)

    /** 显式加入 / 移出书架。inBookshelf=false = 「查看 / 试读但未加入」的临时记录，不进书架列表。 */
    @Query("UPDATE books SET inBookshelf = :inShelf WHERE id = :id")
    suspend fun setInBookshelf(id: String, inShelf: Boolean)

    /**
     * 清掉所有本地 TXT 书的文件指纹 → 下次打开时章节 DB 缓存判定失效，强制重新分章。
     * 唯一调用点：用户修改「自定义章节正则」时（章节切分规则变了，缓存的目录作废）。
     */
    @Query("UPDATE books SET fileSize = 0, fileMtime = 0 WHERE format = 'TXT' AND localPath IS NOT NULL")
    suspend fun invalidateTxtChapterFingerprints()

    /** Books eligible for batch toc refresh (web-format only, opt-in)。仅书架内的书参与自动刷新。 */
    @Query("SELECT * FROM books WHERE format = 'WEB' AND canUpdate = 1 AND inBookshelf = 1 ORDER BY lastReadAt DESC")
    suspend fun getRefreshableBooks(): List<Book>

    @Delete
    suspend fun delete(book: Book)

    @Query("DELETE FROM books WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM books WHERE folderId = :folderId")
    suspend fun deleteByFolderId(folderId: String)

    @Query("SELECT COUNT(*) FROM books")
    suspend fun count(): Int

    /** Count "logical books": each folder = 1 book, each loose file = 1 book（仅统计书架内）。 */
    @Query("SELECT (SELECT COUNT(DISTINCT folderId) FROM books WHERE folderId IS NOT NULL AND inBookshelf = 1) + (SELECT COUNT(*) FROM books WHERE folderId IS NULL AND inBookshelf = 1)")
    suspend fun countLogicalBooks(): Int

    @Query("SELECT * FROM books WHERE folderId = :folderId AND inBookshelf = 1 ORDER BY title")
    suspend fun getBooksByFolderId(folderId: String): List<Book>

    @Query("SELECT * FROM books WHERE (title LIKE '%' || :keyword || '%' OR author LIKE '%' || :keyword || '%') AND inBookshelf = 1 ORDER BY lastReadAt DESC")
    suspend fun searchBooksSync(keyword: String): List<Book>

    @Query("SELECT * FROM books WHERE localPath = :localPath LIMIT 1")
    suspend fun findByLocalPath(localPath: String): Book?

    @Query("SELECT * FROM books WHERE bookUrl = :bookUrl AND sourceUrl = :sourceUrl LIMIT 1")
    suspend fun findByBookUrl(bookUrl: String, sourceUrl: String): Book?

    @Query("SELECT * FROM books ORDER BY lastReadAt DESC")
    suspend fun getAllBooksSync(): List<Book>
}

@Dao
interface ChapterDao {
    @Query("SELECT * FROM book_chapters WHERE bookId = :bookId ORDER BY `index`")
    fun getChapters(bookId: String): Flow<List<BookChapter>>

    @Query("SELECT * FROM book_chapters WHERE bookId = :bookId AND `index` = :index")
    suspend fun getChapter(bookId: String, index: Int): BookChapter?

    @Query("SELECT COUNT(*) FROM book_chapters WHERE bookId = :bookId")
    suspend fun getChapterCount(bookId: String): Int

    @Query("SELECT * FROM book_chapters WHERE bookId = :bookId ORDER BY `index`")
    suspend fun getChaptersList(bookId: String): List<BookChapter>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(chapters: List<BookChapter>)

    @Query("DELETE FROM book_chapters WHERE bookId = :bookId")
    suspend fun deleteByBookId(bookId: String)
}

/**
 * Lightweight projection of [BookSource] used by the search dispatcher when the user
 * has imported tens of thousands of sources. Loading the full entity (with 6 nested
 * rule JSON blobs deserialized via TypeConverters) for every source would put hundreds
 * of MB on the heap and crash the app — see the OOM regression triggered by the 100k
 * source import.
 *
 * Workers fetch the full [BookSource] on demand via [BookSourceDao.getByUrl] right
 * before they actually need to call WebBook, so the rule blobs only live for the
 * duration of a single source's search.
 */
data class BookSourceLite(
    val bookSourceUrl: String,
    val bookSourceName: String,
    val bookSourceType: Int = 0,
)

data class ExploreSourceLite(
    val bookSourceUrl: String,
    val bookSourceName: String,
)

@Dao
interface BookSourceDao {
    @Query("SELECT * FROM book_sources WHERE enabled = 1 ORDER BY customOrder")
    fun getEnabledSources(): Flow<List<BookSource>>

    @Query("SELECT * FROM book_sources WHERE enabled = 1 ORDER BY customOrder")
    suspend fun getEnabledSourcesList(): List<BookSource>

    /**
     * Lightweight projection limited to text sources (bookSourceType=0). Used by
     * SearchViewModel to schedule searches without paying the cost of materializing
     * every nested rule object. Keeps ~32 bytes/row instead of a few KB/row, letting
     * 100k-source imports search without OOM.
     */
    @Query("SELECT bookSourceUrl, bookSourceName, bookSourceType FROM book_sources WHERE enabled = 1 ORDER BY customOrder")
    suspend fun getEnabledSourcesLite(): List<BookSourceLite>

    /** Fast count of enabled sources — avoid pulling 100k rows just to display a number. */
    @Query("SELECT COUNT(*) FROM book_sources WHERE enabled = 1")
    suspend fun getEnabledSourceCount(): Int

    /** 发现页只调度带发现 URL 的源，完整规则在真正请求前按 URL 单条加载。 */
    @Query(
        "SELECT bookSourceUrl, bookSourceName FROM book_sources " +
            "WHERE enabled = 1 AND enabledExplore = 1 AND exploreUrl IS NOT NULL " +
            "AND TRIM(exploreUrl) != '' ORDER BY customOrder LIMIT :limit"
    )
    suspend fun getExploreSourcesLite(limit: Int): List<ExploreSourceLite>

    @Query("SELECT * FROM book_sources ORDER BY customOrder")
    fun getAllSources(): Flow<List<BookSource>>

    @Query("SELECT * FROM book_sources WHERE bookSourceUrl = :url")
    suspend fun getByUrl(url: String): BookSource?

    /**
     * 原子翻转 enabled 字段。修复 toggleSource race：用户连点同一开关时，read-modify-write
     * 路径有多个 viewModelScope.launch 并发抢 IO，DAO.getByUrl 都读到 stale 值 → 多次写
     * 同样的 new enabled → Room 看 list 没变不 emit → UI 永远停在初始视觉态。
     *
     * 用单条 SQL `enabled = 1 - enabled` 原子翻转：read 与 write 在同一 statement 内，
     * 并发 N 次 = 真翻转 N 次。Room 的 invalidation tracker 即时触发 Flow 重发新 list。
     */
    @Query("UPDATE book_sources SET enabled = 1 - enabled WHERE bookSourceUrl = :url")
    suspend fun toggleEnabled(url: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(source: BookSource)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(sources: List<BookSource>)

    @Delete
    suspend fun delete(source: BookSource)

    @Delete
    suspend fun delete(sources: List<BookSource>)

    @Query("DELETE FROM book_sources WHERE bookSourceUrl IN (:urls)")
    suspend fun deleteByUrls(urls: List<String>)

    /**
     * CheckSource 跑完后回写错误/时间戳。**用 UPDATE 而非 insert(REPLACE)** 的根因：
     * 用户在校验进行中可能手动删除某个源，REPLACE 会把已删除的行复活（结合
     * 后续 Compose 重组 + Service 异步写 → 可能触发闪退）。UPDATE 对不存在的 row
     * 影响 0 行，自然不复活；同时只更新两个字段，比 REPLACE 整行高效（低 IO）。
     */
    @Query("UPDATE book_sources SET errorMsg = :errorMsg, lastCheckTime = :ts WHERE bookSourceUrl = :url")
    suspend fun updateCheckResult(url: String, errorMsg: String?, ts: Long)

    @Query("DELETE FROM book_sources")
    suspend fun deleteAll()
}

@Dao
interface BookGroupDao {
    @Query("SELECT * FROM book_groups WHERE parentId IS :parentId ORDER BY sortOrder")
    fun getGroups(parentId: String?): Flow<List<BookGroup>>

    @Query("SELECT * FROM book_groups ORDER BY sortOrder")
    fun getAllGroups(): Flow<List<BookGroup>>

    @Query("SELECT * FROM book_groups WHERE id = :id")
    suspend fun getById(id: String): BookGroup?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(group: BookGroup)

    @Delete
    suspend fun delete(group: BookGroup)

    @Query("DELETE FROM book_groups WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT * FROM book_groups ORDER BY sortOrder")
    suspend fun getAllGroupsSync(): List<BookGroup>
}

/**
 * Many-to-many join between [Book] and [TagDefinition].
 *
 * Queries fall into three categories:
 *  - per-book: list a book's tags (used by detail / edit screens).
 *  - per-tag:  list books carrying a tag (used by the shelf chip filter).
 *  - intersection: list books carrying *all* of N tags (used by multi-chip filter).
 *
 * The intersection query uses GROUP BY + HAVING COUNT(DISTINCT tagId) = N which
 * is faster than nested IN clauses and avoids hitting SQLite's expression depth
 * limit when users stack many chips.
 */
@Dao
interface BookTagDao {
    @Query("SELECT * FROM book_tags WHERE bookId = :bookId")
    suspend fun getTagsForBook(bookId: String): List<BookTag>

    @Query("SELECT * FROM book_tags WHERE bookId = :bookId")
    fun observeTagsForBook(bookId: String): Flow<List<BookTag>>

    @Query("SELECT bookId FROM book_tags WHERE tagId = :tagId")
    suspend fun getBookIdsByTag(tagId: String): List<String>

    @Query("""
        SELECT bookId FROM book_tags
        WHERE tagId IN (:tagIds)
        GROUP BY bookId
        HAVING COUNT(DISTINCT tagId) = :tagCount
    """)
    suspend fun getBookIdsByAllTags(tagIds: List<String>, tagCount: Int): List<String>

    @Query("SELECT COUNT(DISTINCT bookId) FROM book_tags WHERE tagId = :tagId")
    fun countBooksWithTag(tagId: String): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(tag: BookTag)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(tags: List<BookTag>)

    @Query("DELETE FROM book_tags WHERE bookId = :bookId AND tagId = :tagId")
    suspend fun delete(bookId: String, tagId: String)

    @Query("DELETE FROM book_tags WHERE bookId = :bookId AND assignedBy = :assignedBy")
    suspend fun deleteAutoAssignmentsFor(bookId: String, assignedBy: String = "AUTO")

    @Query("DELETE FROM book_tags WHERE bookId = :bookId")
    suspend fun deleteAllForBook(bookId: String)

    @Query("DELETE FROM book_tags WHERE tagId = :tagId")
    suspend fun deleteAllForTag(tagId: String)
}

@Dao
interface TagDefinitionDao {
    @Query("SELECT * FROM tag_definitions ORDER BY sortOrder, name")
    fun getAllTags(): Flow<List<TagDefinition>>

    @Query("SELECT * FROM tag_definitions ORDER BY sortOrder, name")
    suspend fun getAllTagsSync(): List<TagDefinition>

    @Query("SELECT * FROM tag_definitions WHERE type = :type ORDER BY sortOrder, name")
    fun getTagsByType(type: String): Flow<List<TagDefinition>>

    @Query("SELECT * FROM tag_definitions WHERE type = :type ORDER BY sortOrder, name")
    suspend fun getTagsByTypeSync(type: String): List<TagDefinition>

    @Query("SELECT * FROM tag_definitions WHERE id = :id")
    suspend fun getById(id: String): TagDefinition?

    @Query("SELECT * FROM tag_definitions WHERE name = :name AND type = :type LIMIT 1")
    suspend fun findByName(name: String, type: String): TagDefinition?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(tag: TagDefinition)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(tags: List<TagDefinition>)

    @Update
    suspend fun update(tag: TagDefinition)

    @Query("DELETE FROM tag_definitions WHERE id = :id AND builtin = 0")
    suspend fun deleteUserTag(id: String)
}

@Dao
interface ReadProgressDao {
    @Query("SELECT * FROM read_progress WHERE bookId = :bookId")
    suspend fun getProgress(bookId: String): ReadProgress?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(progress: ReadProgress)

    /**
     * books 表中的游标是书架排序/跨设备同步需要的镜像；必须与 read_progress 在同一事务
     * 更新，并且只改进度列，避免用旧 Book 快照覆盖封面、分组或文件指纹等并发修改。
     */
    @Query(
        """UPDATE books SET
            lastReadChapter = :chapterIndex,
            lastReadPosition = :chapterPosition,
            lastReadAt = :updatedAt,
            readProgress = :totalProgress,
            totalChapters = :totalChapters
            WHERE id = :bookId"""
    )
    suspend fun updateBookProgressMirror(
        bookId: String,
        chapterIndex: Int,
        chapterPosition: Int,
        updatedAt: Long,
        totalProgress: Float,
        totalChapters: Int,
    )

    @Transaction
    suspend fun saveSnapshot(progress: ReadProgress, totalChapters: Int) {
        save(progress)
        updateBookProgressMirror(
            bookId = progress.bookId,
            chapterIndex = progress.chapterIndex,
            chapterPosition = progress.chapterPosition,
            updatedAt = progress.updatedAt,
            totalProgress = progress.totalProgress,
            totalChapters = totalChapters,
        )
    }

    @Query("SELECT * FROM read_progress")
    suspend fun getAllSync(): List<ReadProgress>
}

@Dao
interface ThemeDao {
    @Query("SELECT * FROM themes ORDER BY isBuiltin DESC, name")
    fun getAllThemes(): Flow<List<ThemeEntity>>

    @Query("SELECT * FROM themes WHERE isActive = 1 LIMIT 1")
    fun getActiveTheme(): Flow<ThemeEntity?>

    @Query("SELECT COUNT(*) FROM themes WHERE isActive = 1")
    suspend fun countActiveThemes(): Int

    @Query("SELECT * FROM themes WHERE id = :id")
    suspend fun getById(id: String): ThemeEntity?

    @Query("UPDATE themes SET isActive = 0")
    suspend fun deactivateAll()

    @Query("UPDATE themes SET isActive = 1 WHERE id = :id")
    suspend fun activate(id: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(theme: ThemeEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(themes: List<ThemeEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(themes: List<ThemeEntity>)

    @Query("DELETE FROM themes WHERE id = :id AND isBuiltin = 0")
    suspend fun deleteCustomTheme(id: String)

    @Query("SELECT * FROM themes")
    suspend fun getAllSync(): List<ThemeEntity>
}

/**
 * 阅读记录 DAO —— 每日每本书的阅读时长持久层（粒度 = 进入阅读器~退出整段）。
 *
 * P1 数据层入口。配套：
 * - entity [com.morealm.app.domain.entity.ReadRecord]
 * - UI 入口（P3）：「我的 → 阅读记录」页面按 date 降序分组
 * - 写入（P2）：ReaderViewModel.onCleared / lifecycle ON_PAUSE upsert
 * - Legado 导入（P4）：LegadoImporter 备份 zip readRecord 表 → bookName fuzzy match
 *
 * 与 [ReadStatsDao] 关系：ReadStats 是天级汇总（不分书），本 DAO 是天 × 书 二维。两者并存
 * 互不冲突：ReadStats 给顶栏"今日阅读 X 小时"，本 DAO 给阅读记录页面。
 */
@Dao
interface ReadRecordDao {
    /** 全部记录，按 date DESC + 同日 lastReadAt DESC。UI 收到后 groupBy date 分组 */
    @Query("SELECT * FROM read_records ORDER BY date DESC, lastReadAt DESC")
    fun all(): Flow<List<ReadRecord>>

    /** 某天全部记录（"某日详情"页用） */
    @Query("SELECT * FROM read_records WHERE date = :date ORDER BY lastReadAt DESC")
    suspend fun getByDate(date: String): List<ReadRecord>

    /** 某书全部阅读历史（书详情页"阅读历史" tab 用） */
    @Query("SELECT * FROM read_records WHERE bookId = :bookId ORDER BY date DESC")
    fun getByBook(bookId: String): Flow<List<ReadRecord>>

    /**
     * 拿单条 readTime（写入路径累加用）：进入阅读器时拿当日已有 readTime，
     * 退出时 save(readRecord.copy(readTime = oldReadTime + sessionMs, lastReadAt = now))
     */
    @Query("SELECT readTime FROM read_records WHERE date = :date AND bookId = :bookId")
    suspend fun getReadTime(date: String, bookId: String): Long?

    /** upsert 单条（write path 调用） */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(record: ReadRecord)

    /** Legado 批量导入 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveAll(records: List<ReadRecord>)

    /** 删某天（用户清理） */
    @Query("DELETE FROM read_records WHERE date = :date")
    suspend fun deleteByDate(date: String)

    /** 全清（设置 → 清理阅读记录） */
    @Query("DELETE FROM read_records")
    suspend fun deleteAll()
}

@Dao
interface ReadStatsDao {
    @Query("SELECT * FROM read_stats WHERE date = :date")
    suspend fun getByDate(date: String): ReadStats?

    @Query("SELECT * FROM read_stats ORDER BY date DESC LIMIT :limit")
    fun getRecent(limit: Int = 30): Flow<List<ReadStats>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(stats: ReadStats)

    /** Year-level aggregates for annual report */
    @Query("SELECT * FROM read_stats WHERE date LIKE :yearPrefix || '%' ORDER BY date")
    suspend fun getByYear(yearPrefix: String): List<ReadStats>

    @Query("SELECT SUM(readDurationMs) FROM read_stats WHERE date LIKE :yearPrefix || '%'")
    suspend fun totalDurationByYear(yearPrefix: String): Long?

    @Query("SELECT SUM(wordsRead) FROM read_stats WHERE date LIKE :yearPrefix || '%'")
    suspend fun totalWordsByYear(yearPrefix: String): Long?

    @Query("SELECT SUM(booksFinished) FROM read_stats WHERE date LIKE :yearPrefix || '%'")
    suspend fun totalBooksFinishedByYear(yearPrefix: String): Int?

    @Query("SELECT COUNT(*) FROM read_stats WHERE date LIKE :yearPrefix || '%' AND readDurationMs > 0")
    suspend fun activeDaysByYear(yearPrefix: String): Int

    @Query("SELECT MAX(readDurationMs) FROM read_stats WHERE date LIKE :yearPrefix || '%'")
    suspend fun longestSessionByYear(yearPrefix: String): Long?
}

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId ORDER BY chapterIndex, scrollProgress")
    fun getBookmarks(bookId: String): Flow<List<Bookmark>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(bookmark: Bookmark)

    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM bookmarks WHERE bookId = :bookId")
    suspend fun deleteByBookId(bookId: String)

    @Query("SELECT * FROM bookmarks ORDER BY createdAt DESC")
    suspend fun getAllSync(): List<Bookmark>

    /** 全局书签 Flow，用于 BookmarksScreen 实时显示 + 删除即刷新。 */
    @Query("SELECT * FROM bookmarks ORDER BY createdAt DESC")
    fun getAll(): Flow<List<Bookmark>>
}

/**
 * Highlights — 用户在阅读器选中文字后保存的彩色标注。
 *
 * 查询模式
 * - [getForChapter] 在阅读器加载新章节时拉一次，过滤到 (bookId, chapterIndex)，
 *   渲染器据此画底色矩形。
 * - [getForBook] 给「我的高亮」总览页用（按时间降序，给读者按"最新→历史"
 *   顺序回顾）。
 * - [getAllSync] 给备份导出 / 全局统计用，不分页。
 */
@Dao
interface HighlightDao {
    @Query("SELECT * FROM highlights WHERE bookId = :bookId AND chapterIndex = :chapterIndex ORDER BY startChapterPos")
    fun getForChapter(bookId: String, chapterIndex: Int): Flow<List<Highlight>>

    @Query("SELECT * FROM highlights WHERE bookId = :bookId AND chapterIndex = :chapterIndex ORDER BY startChapterPos")
    suspend fun getForChapterSync(bookId: String, chapterIndex: Int): List<Highlight>

    @Query("SELECT * FROM highlights WHERE bookId = :bookId ORDER BY chapterIndex, startChapterPos")
    fun getForBook(bookId: String): Flow<List<Highlight>>

    @Query("SELECT * FROM highlights ORDER BY createdAt DESC")
    suspend fun getAllSync(): List<Highlight>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(highlight: Highlight)

    @Query("DELETE FROM highlights WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM highlights WHERE bookId = :bookId")
    suspend fun deleteByBookId(bookId: String)
}

/** 用户高亮词（「文字上色」Phase 2）。全局生效，词唯一（去重）。 */
@Dao
interface HighlightWordDao {
    @Query("SELECT * FROM highlight_words ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<HighlightWord>>

    /** 词唯一：同词 REPLACE（改色号 / 去重）。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(word: HighlightWord)

    @Query("DELETE FROM highlight_words WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface ReplaceRuleDao {
    @Query("SELECT * FROM replace_rules WHERE enabled = 1 ORDER BY sortOrder")
    fun getEnabledRules(): Flow<List<ReplaceRule>>

    @Query("SELECT * FROM replace_rules ORDER BY sortOrder")
    fun getAllRules(): Flow<List<ReplaceRule>>

    @Query("SELECT * FROM replace_rules WHERE enabled = 1 AND (scope = '' OR scope = :bookId) ORDER BY sortOrder")
    suspend fun getRulesForBook(bookId: String): List<ReplaceRule>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(rule: ReplaceRule)

    @Delete
    suspend fun delete(rule: ReplaceRule)

    @Query("DELETE FROM replace_rules WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT * FROM replace_rules ORDER BY sortOrder")
    suspend fun getAllSync(): List<ReplaceRule>

    /**
     * 与 Legado `findEnabledByContentScope` 同语义：
     *
     * 1. **scope 包含匹配** —— `LIKE '%name%'` / `LIKE '%origin%'` / NULL / 空串均放行。
     *    Legado scope 字段允许换行 `\n` 分隔多书名 / 多源 URL（如 `示例书\nhttp://x.com`），
     *    必须用 LIKE 才能命中；早先 `=` 精确匹配会让一键搬家来的多 scope 规则全部漏掉。
     * 2. **excludeScope 反向排除** —— Legado 用同一字段做"排除清单"，被排除的书 / 源
     *    不应用规则。NULL / 空串视为"不排除任何书"。
     * 3. **scopeContent = 1** —— 内联到 SQL 而非 Kotlin filter，让该方法直接产出
     *    "可应用于正文的有效规则"，调用方无需再次 filter。
     *
     * @return 按 sortOrder 排序的规则列表；调用方可直接 forEach 应用。
     */
    @Query(
        """SELECT * FROM replace_rules WHERE enabled = 1 AND scopeContent = 1
        AND (scope LIKE '%' || :name || '%' OR scope LIKE '%' || :origin || '%' OR scope IS NULL OR scope = '')
        AND (excludeScope IS NULL OR excludeScope = ''
             OR (excludeScope NOT LIKE '%' || :name || '%' AND excludeScope NOT LIKE '%' || :origin || '%'))
        ORDER BY sortOrder"""
    )
    fun findEnabledByContentScope(name: String, origin: String): List<ReplaceRule>

    /**
     * 与 Legado `findEnabledByTitleScope` 同语义。SQL 与 [findEnabledByContentScope]
     * 几乎一致，差异在 `scopeTitle = 1`（仅取被勾选"作用于标题"的规则）。
     *
     * 拆成两个方法而不是 union，是因为 ContentProcessor 对 title 和 content 走不同
     * 处理流程（title 不切句、不加段首缩进），SQL 直接帮我们分组省去 Kotlin filter。
     */
    @Query(
        """SELECT * FROM replace_rules WHERE enabled = 1 AND scopeTitle = 1
        AND (scope LIKE '%' || :name || '%' OR scope LIKE '%' || :origin || '%' OR scope IS NULL OR scope = '')
        AND (excludeScope IS NULL OR excludeScope = ''
             OR (excludeScope NOT LIKE '%' || :name || '%' AND excludeScope NOT LIKE '%' || :origin || '%'))
        ORDER BY sortOrder"""
    )
    fun findEnabledByTitleScope(name: String, origin: String): List<ReplaceRule>

    /**
     * 旧入口，保留向后兼容（少数测试 / 调试代码可能直接调用）。
     * **新代码请用 [findEnabledByContentScope] / [findEnabledByTitleScope]** —— 这条
     * 仍然走 LIKE/excludeScope 对齐的查询，但合并了 title+content scope，调用方需要自己
     * 按 scopeTitle/scopeContent 再 filter。
     */
    @Deprecated(
        "Use findEnabledByContentScope / findEnabledByTitleScope instead",
        ReplaceWith("findEnabledByContentScope(bookName, bookOrigin)"),
    )
    @Query(
        """SELECT * FROM replace_rules WHERE enabled = 1
        AND (scope LIKE '%' || :bookName || '%' OR scope LIKE '%' || :bookOrigin || '%' OR scope IS NULL OR scope = '')
        AND (excludeScope IS NULL OR excludeScope = ''
             OR (excludeScope NOT LIKE '%' || :bookName || '%' AND excludeScope NOT LIKE '%' || :bookOrigin || '%'))
        ORDER BY sortOrder"""
    )
    fun getEnabledByScope(bookName: String, bookOrigin: String): List<ReplaceRule>
}

@Dao
interface ReaderStyleDao {
    @Query("SELECT * FROM reader_styles ORDER BY sortOrder")
    fun getAll(): Flow<List<ReaderStyle>>

    @Query("SELECT * FROM reader_styles ORDER BY sortOrder")
    suspend fun getAllSync(): List<ReaderStyle>

    @Query("SELECT * FROM reader_styles WHERE id = :id")
    suspend fun getById(id: String): ReaderStyle?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(style: ReaderStyle)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(styles: List<ReaderStyle>)

    @Delete
    suspend fun delete(style: ReaderStyle)

    @Query("DELETE FROM reader_styles WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT COUNT(*) FROM reader_styles")
    suspend fun count(): Int
}

@Dao
interface TxtTocRuleDao {
    @Query("SELECT * FROM txt_toc_rules ORDER BY sortOrder")
    fun getAll(): Flow<List<TxtTocRule>>

    @Query("SELECT * FROM txt_toc_rules WHERE enabled = 1 ORDER BY sortOrder")
    suspend fun getEnabledRules(): List<TxtTocRule>

    @Query("SELECT * FROM txt_toc_rules WHERE enabled = 1 ORDER BY sortOrder")
    fun getEnabledRulesSync(): List<TxtTocRule>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rule: TxtTocRule)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rules: List<TxtTocRule>)

    @Delete
    suspend fun delete(rule: TxtTocRule)

    @Query("SELECT COUNT(*) FROM txt_toc_rules")
    suspend fun count(): Int
}

@Dao
interface HttpTtsDao {
    @Query("SELECT * FROM http_tts ORDER BY name")
    fun getAll(): Flow<List<HttpTts>>

    @Query("SELECT * FROM http_tts WHERE enabled = 1 ORDER BY name")
    suspend fun getEnabled(): List<HttpTts>

    @Query("SELECT * FROM http_tts WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): HttpTts?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(tts: HttpTts)

    @Delete
    suspend fun delete(tts: HttpTts)

    @Query("SELECT COUNT(*) FROM http_tts")
    suspend fun count(): Int
}

@Dao
interface CacheDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(cache: Cache)

    @Query("SELECT * FROM caches WHERE `key` = :key")
    suspend fun get(key: String): Cache?

    @Query("DELETE FROM caches WHERE `key` = :key")
    suspend fun delete(key: String)

    @Query("DELETE FROM caches WHERE `key` LIKE :prefix || '%'")
    suspend fun deleteByPrefix(prefix: String)

    @Query("DELETE FROM caches")
    suspend fun deleteAll()

    /**
     * 列出所有「章节内容」缓存的 key — 用于"清理无效缓存"功能扫描孤儿。
     * 返回 key（无 value，省内存）。CacheRepository 在内存里按前缀比对 sourceUrl 过滤。
     */
    @Query("SELECT `key` FROM caches WHERE `key` LIKE 'chapter_content_%'")
    suspend fun getAllChapterContentKeys(): List<String>
}

@Dao
interface CookieDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(cookie: Cookie)

    @Query("SELECT * FROM cookies WHERE url = :url")
    suspend fun get(url: String): Cookie?

    @Query("DELETE FROM cookies WHERE url = :url")
    suspend fun delete(url: String)

    @Query("DELETE FROM cookies")
    suspend fun deleteAll()
}

/**
 * 换源候选缓存（[SearchBookCache]）。
 *
 * 查询路径：
 *  - `getByBook`：打开换源对话框时按 (bookName, author) 拉所有候选，按 originOrder/responseTime 排好序。
 *  - `deleteByBook`：刷新前清空目标书旧记录，避免污染。
 *  - `deleteOlderThan`：定期清理 7 天前缓存，避免无限增长。
 *  - `deleteByOrigin`：用户禁用/删除某源时同步擦除该源所有缓存。
 */
@Dao
interface SearchBookCacheDao {
    @Query("SELECT * FROM search_book_cache WHERE bookName = :bookName AND author = :author ORDER BY originOrder, responseTime")
    suspend fun getByBook(bookName: String, author: String): List<SearchBookCache>

    @Query("DELETE FROM search_book_cache WHERE bookName = :bookName AND author = :author")
    suspend fun deleteByBook(bookName: String, author: String)

    @Query("DELETE FROM search_book_cache WHERE time < :before")
    suspend fun deleteOlderThan(before: Long)

    @Query("DELETE FROM search_book_cache WHERE origin = :origin")
    suspend fun deleteByOrigin(origin: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(cache: SearchBookCache)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(caches: List<SearchBookCache>)
}

/**
 * 搜索历史 ([SearchKeyword]) DAO。读侧策略：
 *
 *   - `topAll` → 默认下拉里看到的"最近 / 最常用"列表，usage DESC + lastUseTime DESC
 *     双键排序让"经常搜的"和"刚搜过的"自然冒上来；
 *   - `searchPrefix` → 用户开始输入时做联想前缀匹配。LIKE 'q%' 不带 %prefix% 是为了
 *     可走 PRIMARY KEY 索引（word 是主键，前缀 LIKE 可用 B-tree），高频 IME 输入
 *     时不会卡顿；
 *   - `clear` 一键清空整个历史，与"删除单条"分开，UI 二次确认；
 *   - `delete` 单条删除供长按弹"删除该词"。
 *
 * 写侧用 upsert + 计数累加：详见 [com.morealm.app.domain.repository.SearchKeywordRepository.record]。
 */
@Dao
interface SearchKeywordDao {
    @Query("SELECT * FROM search_keyword ORDER BY usage DESC, lastUseTime DESC LIMIT :limit")
    fun topAll(limit: Int = 50): Flow<List<SearchKeyword>>

    @Query("SELECT * FROM search_keyword ORDER BY usage DESC, lastUseTime DESC LIMIT :limit")
    suspend fun topAllSync(limit: Int = 50): List<SearchKeyword>

    @Query("SELECT * FROM search_keyword WHERE word LIKE :prefix || '%' ORDER BY usage DESC, lastUseTime DESC LIMIT :limit")
    suspend fun searchPrefix(prefix: String, limit: Int = 10): List<SearchKeyword>

    @Query("SELECT * FROM search_keyword WHERE word = :word")
    suspend fun get(word: String): SearchKeyword?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(keyword: SearchKeyword)

    @Query("DELETE FROM search_keyword WHERE word = :word")
    suspend fun deleteByWord(word: String)

    @Query("DELETE FROM search_keyword")
    suspend fun clear()
}
