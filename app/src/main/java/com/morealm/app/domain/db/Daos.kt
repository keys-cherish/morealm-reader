package com.morealm.app.domain.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
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

    @Query("SELECT * FROM books WHERE title LIKE :keyword OR author LIKE :keyword ORDER BY lastReadAt DESC")
    suspend fun searchBooks(keyword: String): List<Book>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(book: Book)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(books: List<Book>)

    @Update
    suspend fun update(book: Book)

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
