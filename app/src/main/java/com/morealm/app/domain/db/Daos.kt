package com.morealm.app.domain.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.paging.PagingSource
import com.morealm.app.domain.entity.*
import kotlinx.coroutines.flow.Flow


@Dao
interface BookDao {
    @Query("SELECT * FROM books WHERE folderId IS :folderId ORDER BY lastReadAt DESC")
    fun getBooksInFolder(folderId: String?): Flow<List<Book>>

    @Query("SELECT * FROM books ORDER BY lastReadAt DESC LIMIT 1")
    fun getLastReadBook(): Flow<Book?>

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun getById(id: String): Book?

    @Query("SELECT * FROM books ORDER BY lastReadAt DESC")
    fun getAllBooks(): Flow<List<Book>>

    @Query("SELECT * FROM books ORDER BY title COLLATE NOCASE")
    fun getAllBooksPaging(): PagingSource<Int, Book>

    @Query("SELECT * FROM books WHERE folderId IS NULL ORDER BY title COLLATE NOCASE")
    fun getUngroupedBooksPaging(): PagingSource<Int, Book>

    @Query("SELECT * FROM books WHERE folderId IS NULL ORDER BY lastReadAt DESC")
    fun getUngroupedBooksByRecent(): PagingSource<Int, Book>

    @Query("SELECT * FROM books WHERE folderId IS NULL ORDER BY addedAt DESC")
    fun getUngroupedBooksByAddTime(): PagingSource<Int, Book>

    @Query("SELECT * FROM books WHERE folderId IS NULL ORDER BY format, title COLLATE NOCASE")
    fun getUngroupedBooksByFormat(): PagingSource<Int, Book>

    @Query("SELECT * FROM books ORDER BY lastReadAt DESC")
    fun getAllBooksByRecent(): PagingSource<Int, Book>

    @Query("SELECT * FROM books ORDER BY addedAt DESC")
    fun getAllBooksByAddTime(): PagingSource<Int, Book>

    @Query("SELECT * FROM books ORDER BY format, title COLLATE NOCASE")
    fun getAllBooksByFormat(): PagingSource<Int, Book>

    @Query("SELECT COUNT(*) FROM books WHERE folderId = :folderId")
    fun countByFolderId(folderId: String): Flow<Int>

    @Query("SELECT * FROM books WHERE folderId = :folderId ORDER BY title COLLATE NOCASE")
    fun getBooksByFolderPaging(folderId: String): PagingSource<Int, Book>

    @Query("SELECT * FROM books WHERE folderId = :folderId ORDER BY lastReadAt DESC")
    fun getBooksByFolderByRecent(folderId: String): PagingSource<Int, Book>

    @Query("SELECT * FROM books WHERE folderId = :folderId ORDER BY addedAt DESC")
    fun getBooksByFolderByAddTime(folderId: String): PagingSource<Int, Book>

    @Query("SELECT * FROM books WHERE folderId = :folderId ORDER BY format, title COLLATE NOCASE")
    fun getBooksByFolderByFormat(folderId: String): PagingSource<Int, Book>

    @Query("SELECT * FROM books WHERE title LIKE :keyword OR author LIKE :keyword ORDER BY lastReadAt DESC")
    suspend fun searchBooks(keyword: String): List<Book>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(book: Book)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(books: List<Book>)

    @Update
    suspend fun update(book: Book)

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

    /** Books eligible for batch toc refresh (web-format only, opt-in). */
    @Query("SELECT * FROM books WHERE format = 'WEB' AND canUpdate = 1 ORDER BY lastReadAt DESC")
    suspend fun getRefreshableBooks(): List<Book>

    @Delete
    suspend fun delete(book: Book)

    @Query("DELETE FROM books WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM books WHERE folderId = :folderId")
    suspend fun deleteByFolderId(folderId: String)

    @Query("SELECT COUNT(*) FROM books")
    suspend fun count(): Int

    /** Count "logical books": each folder = 1 book, each loose file = 1 book */
    @Query("SELECT (SELECT COUNT(DISTINCT folderId) FROM books WHERE folderId IS NOT NULL) + (SELECT COUNT(*) FROM books WHERE folderId IS NULL)")
    suspend fun countLogicalBooks(): Int

    @Query("SELECT * FROM books WHERE folderId = :folderId ORDER BY title")
    suspend fun getBooksByFolderId(folderId: String): List<Book>

    @Query("SELECT * FROM books WHERE title LIKE '%' || :keyword || '%' OR author LIKE '%' || :keyword || '%' ORDER BY lastReadAt DESC")
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

@Dao
interface BookSourceDao {
    @Query("SELECT * FROM book_sources WHERE enabled = 1 ORDER BY customOrder")
    fun getEnabledSources(): Flow<List<BookSource>>

    @Query("SELECT * FROM book_sources WHERE enabled = 1 ORDER BY customOrder")
    suspend fun getEnabledSourcesList(): List<BookSource>

    @Query("SELECT * FROM book_sources ORDER BY customOrder")
    fun getAllSources(): Flow<List<BookSource>>

    @Query("SELECT * FROM book_sources WHERE bookSourceUrl = :url")
    suspend fun getByUrl(url: String): BookSource?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(source: BookSource)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(sources: List<BookSource>)

    @Delete
    suspend fun delete(source: BookSource)

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

    @Query("SELECT * FROM replace_rules WHERE enabled = 1 AND (scope = '' OR scope = :bookName OR scope = :bookOrigin) ORDER BY sortOrder")
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
