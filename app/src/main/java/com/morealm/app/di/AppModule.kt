package com.morealm.app.di

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.morealm.app.core.log.AppLog
import com.morealm.app.domain.db.*
import com.morealm.app.domain.preference.AppPreferences
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

private val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `bookmarks` (`id` TEXT NOT NULL, `bookId` TEXT NOT NULL, `chapterIndex` INTEGER NOT NULL, `chapterTitle` TEXT NOT NULL, `content` TEXT NOT NULL, `scrollProgress` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
    }
}

private val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `replace_rules` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `pattern` TEXT NOT NULL, `replacement` TEXT NOT NULL, `isRegex` INTEGER NOT NULL, `scope` TEXT NOT NULL, `bookId` TEXT, `enabled` INTEGER NOT NULL, `sortOrder` INTEGER NOT NULL, PRIMARY KEY(`id`))")
    }
}

private val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS `reader_styles` (
            `id` TEXT NOT NULL,
            `name` TEXT NOT NULL,
            `sortOrder` INTEGER NOT NULL,
            `bgColor` TEXT NOT NULL,
            `bgColorNight` TEXT NOT NULL,
            `bgImageUri` TEXT,
            `bgImageUriNight` TEXT,
            `bgAlpha` INTEGER NOT NULL,
            `textColor` TEXT NOT NULL,
            `textColorNight` TEXT NOT NULL,
            `textSize` INTEGER NOT NULL,
            `fontFamily` TEXT NOT NULL,
            `customFontUri` TEXT,
            `textBold` INTEGER NOT NULL,
            `letterSpacing` REAL NOT NULL,
            `lineHeight` REAL NOT NULL,
            `paragraphSpacing` INTEGER NOT NULL,
            `paragraphIndent` TEXT NOT NULL,
            `textAlign` TEXT NOT NULL,
            `titleMode` INTEGER NOT NULL,
            `titleSize` INTEGER NOT NULL,
            `titleTopSpacing` INTEGER NOT NULL,
            `titleBottomSpacing` INTEGER NOT NULL,
            `paddingTop` INTEGER NOT NULL,
            `paddingBottom` INTEGER NOT NULL,
            `paddingLeft` INTEGER NOT NULL,
            `paddingRight` INTEGER NOT NULL,
            `pageAnim` TEXT NOT NULL,
            `showHeader` INTEGER NOT NULL,
            `showFooter` INTEGER NOT NULL,
            `headerContent` TEXT NOT NULL,
            `footerContent` TEXT NOT NULL,
            `isBuiltin` INTEGER NOT NULL,
            PRIMARY KEY(`id`)
        )""")
    }
}

private val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE book_chapters ADD COLUMN `nextUrl` TEXT")
    }
}

private val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE books ADD COLUMN `pinned` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE books ADD COLUMN `sortOrder` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE book_groups ADD COLUMN `pinned` INTEGER NOT NULL DEFAULT 0")
    }
}

private val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE replace_rules ADD COLUMN `bookId` TEXT")
        db.execSQL("ALTER TABLE replace_rules ADD COLUMN `scopeTitle` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE replace_rules ADD COLUMN `scopeContent` INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE replace_rules ADD COLUMN `timeoutMs` INTEGER NOT NULL DEFAULT 3000")
    }
}

private val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS `txt_toc_rules` (
            `id` TEXT NOT NULL, `name` TEXT NOT NULL, `pattern` TEXT NOT NULL,
            `example` TEXT NOT NULL, `enabled` INTEGER NOT NULL, `sortOrder` INTEGER NOT NULL,
            `isBuiltin` INTEGER NOT NULL, PRIMARY KEY(`id`)
        )""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS `http_tts` (
            `id` INTEGER NOT NULL, `name` TEXT NOT NULL, `url` TEXT NOT NULL,
            `contentType` TEXT, `header` TEXT, `enabled` INTEGER NOT NULL,
            `lastUpdateTime` INTEGER NOT NULL, PRIMARY KEY(`id`)
        )""")
    }
}

private val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE reader_styles ADD COLUMN `customCss` TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE reader_styles ADD COLUMN `customBgImage` TEXT NOT NULL DEFAULT ''")
    }
}

private val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE read_progress ADD COLUMN `scrollProgress` INTEGER NOT NULL DEFAULT 0")
    }
}

private val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS `caches` (
            `key` TEXT NOT NULL,
            `value` TEXT,
            `deadline` INTEGER NOT NULL DEFAULT 0,
            PRIMARY KEY(`key`)
        )""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS `cookies` (
            `url` TEXT NOT NULL,
            `cookie` TEXT NOT NULL DEFAULT '',
            PRIMARY KEY(`url`)
        )""")
    }
}

private val MIGRATION_12_13 = object : Migration(12, 13) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE book_sources ADD COLUMN `enabledCookieJar` INTEGER")
        db.execSQL("ALTER TABLE book_sources ADD COLUMN `jsLib` TEXT")
    }
}

private val MIGRATION_13_14 = object : Migration(13, 14) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE themes ADD COLUMN `customCss` TEXT NOT NULL DEFAULT ''")
    }
}

private val MIGRATION_14_15 = object : Migration(14, 15) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE book_groups ADD COLUMN `autoKeywords` TEXT NOT NULL DEFAULT ''")
    }
}

/**
 * v15 → v16: Legado-parity background toc refresh tracking.
 *
 * - lastCheckCount  — number of new chapters discovered on the most recent toc
 *                     refresh; drives the shelf "N 新" badge. Default 0.
 * - lastCheckTime   — wall-clock ms of most recent refresh attempt. Default 0.
 * - canUpdate       — opt-out flag for batch refresh. Default 1 (opt-in).
 *
 * All three are nullable-safe with defaults; existing rows get zeroed counters
 * so the user sees a clean shelf until the next refresh kicks in. No reverse
 * migration: downgrading drops these columns silently (Room handles it via the
 * usual destructive-on-downgrade path which is already firewalled by
 * AppModule.provideDatabase's pre-upgrade backup).
 */
private val MIGRATION_15_16 = object : Migration(15, 16) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE books ADD COLUMN `lastCheckCount` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE books ADD COLUMN `lastCheckTime` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE books ADD COLUMN `canUpdate` INTEGER NOT NULL DEFAULT 1")
    }
}

/**
 * v16 → v17: auto-grouping data model.
 *
 *  - books gains `tagsAssignedBy` (AUTO/MANUAL/HYBRID) and `groupLocked` so the
 *    classifier can tell user-curated tags apart from its own guesses.
 *  - book_tags is the new many-to-many join replacing the single folderId model.
 *    folderId is **kept** for compat — every existing assignment is mirrored
 *    into book_tags as a MANUAL entry so the shelf keeps working unchanged.
 *  - tag_definitions stores the user-editable vocabulary (genres, source, format,
 *    status, custom). Existing book_groups are migrated as USER tags so users
 *    don't lose their hand-built groups when the app upgrades.
 *
 * The migration is idempotent on the data side — safe to re-run if Room replays.
 */
private val MIGRATION_16_17 = object : Migration(16, 17) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 1. New columns on books
        db.execSQL("ALTER TABLE books ADD COLUMN `tagsAssignedBy` TEXT NOT NULL DEFAULT 'AUTO'")
        db.execSQL("ALTER TABLE books ADD COLUMN `groupLocked` INTEGER NOT NULL DEFAULT 0")

        // 2. book_tags many-to-many
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `book_tags` (
                `bookId` TEXT NOT NULL,
                `tagId` TEXT NOT NULL,
                `assignedBy` TEXT NOT NULL,
                `score` REAL NOT NULL DEFAULT 0,
                `assignedAt` INTEGER NOT NULL,
                PRIMARY KEY(`bookId`, `tagId`)
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_book_tags_bookId` ON `book_tags` (`bookId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_book_tags_tagId` ON `book_tags` (`tagId`)")

        // 3. tag_definitions vocabulary
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `tag_definitions` (
                `id` TEXT NOT NULL,
                `name` TEXT NOT NULL,
                `type` TEXT NOT NULL,
                `keywords` TEXT NOT NULL DEFAULT '',
                `color` TEXT,
                `icon` TEXT,
                `sortOrder` INTEGER NOT NULL DEFAULT 0,
                `builtin` INTEGER NOT NULL DEFAULT 0,
                `createdAt` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
        """.trimIndent())

        // 4. Migrate existing BookGroup → tag_definitions(USER)
        val now = System.currentTimeMillis()
        db.execSQL("""
            INSERT OR IGNORE INTO tag_definitions (id, name, type, keywords, color, icon, sortOrder, builtin, createdAt)
            SELECT id, name, 'USER', autoKeywords, NULL, emoji, sortOrder, 0, $now FROM book_groups
        """.trimIndent())

        // 5. Mirror existing folderId assignments into book_tags as MANUAL
        db.execSQL("""
            INSERT OR IGNORE INTO book_tags (bookId, tagId, assignedBy, score, assignedAt)
            SELECT id, folderId, 'MANUAL', 1.0, $now FROM books WHERE folderId IS NOT NULL
        """.trimIndent())

        // 6. Books that already had a folderId were user-placed → mark MANUAL
        db.execSQL("UPDATE books SET tagsAssignedBy = 'MANUAL' WHERE folderId IS NOT NULL")
    }
}

/**
 * v17 → v18: BookGroup gains `auto` flag distinguishing auto-created folders
 * from user-created ones.
 *
 *  - Existing groups are user-curated by definition (no auto-folder logic
 *    existed before v18) → default `auto = 0` is correct.
 *  - The flag drives UX: deleting an auto-folder records its source tag in
 *    [AppPreferences.autoFolderIgnored] so it doesn't reappear; deleting a
 *    user folder is a plain "you wanted it gone" delete.
 */
private val MIGRATION_17_18 = object : Migration(17, 18) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE book_groups ADD COLUMN `auto` INTEGER NOT NULL DEFAULT 0")
    }
}

/**
 * v18 → v19: BookSource gains CheckSource result fields.
 *
 *   - errorMsg       — last failure reason (null = passed). Surfaces as red
 *                      underline + tooltip on the source manage list.
 *   - lastCheckTime  — wall-clock ms of last CheckSource pass; 0 = never.
 *
 * Existing rows are zeroed/null — they appear "unchecked" until the user runs
 * a CheckSource pass. No reverse-migration concerns: errorMsg is a TEXT column
 * defaulted NULL and lastCheckTime is INTEGER defaulted 0; both are append-only.
 */
private val MIGRATION_18_19 = object : Migration(18, 19) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE book_sources ADD COLUMN `errorMsg` TEXT DEFAULT NULL")
        db.execSQL("ALTER TABLE book_sources ADD COLUMN `lastCheckTime` INTEGER NOT NULL DEFAULT 0")
    }
}

/**
 * v19 → v20: SearchBookCache 表新增（换源候选缓存）。
 *
 * 复合主键 (bookUrl, origin) 使同一书源下相同书的写入幂等。三组索引覆盖换源对话框
 * 的两条查询路径：(bookName, author) 拉候选；time 老化清理。
 *
 * 索引名遵循 Room 自动生成约定 `index_<table>_<col>` 以便后续 schema 校验通过。
 */
private val MIGRATION_19_20 = object : Migration(19, 20) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `search_book_cache` (
                `bookUrl` TEXT NOT NULL,
                `origin` TEXT NOT NULL,
                `originName` TEXT NOT NULL,
                `bookName` TEXT NOT NULL,
                `author` TEXT NOT NULL,
                `type` INTEGER NOT NULL DEFAULT 0,
                `coverUrl` TEXT,
                `intro` TEXT,
                `kind` TEXT,
                `wordCount` TEXT,
                `latestChapterTitle` TEXT,
                `tocUrl` TEXT NOT NULL DEFAULT '',
                `originOrder` INTEGER NOT NULL DEFAULT 0,
                `responseTime` INTEGER NOT NULL DEFAULT 0,
                `time` INTEGER NOT NULL,
                PRIMARY KEY(`bookUrl`, `origin`)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_search_book_cache_bookName` ON `search_book_cache` (`bookName`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_search_book_cache_author` ON `search_book_cache` (`author`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_search_book_cache_time` ON `search_book_cache` (`time`)")
    }
}

/**
 * v20→v21: 高亮表 highlights — 用户在阅读器选中文字后保存的彩色标注。
 *
 * 设计要点（与 Highlight.kt 注释呼应）：
 * - 主键 id 是 UUID 字符串，避免和 Bookmark 自增 id 冲突，方便后续从备份 zip
 *   原样还原。
 * - 章节级字符 offset (startChapterPos / endChapterPos) 而非 (line, col)
 *   作为定位主键 — 排版变化（字号/字体/页宽）下仍能命中同一段原文。
 * - bookTitle / chapterTitle 冗余存盘，删除原书或换书源后高亮元数据仍可用。
 * - 索引：(bookId) 给「我的高亮」总览，(bookId, chapterIndex) 给阅读器加载
 *   单章高亮时点查，(createdAt) 给"最近高亮"列表排序。
 */
private val MIGRATION_20_21 = object : Migration(20, 21) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `highlights` (
                `id` TEXT NOT NULL,
                `bookId` TEXT NOT NULL,
                `chapterIndex` INTEGER NOT NULL,
                `chapterTitle` TEXT NOT NULL DEFAULT '',
                `bookTitle` TEXT NOT NULL DEFAULT '',
                `startChapterPos` INTEGER NOT NULL,
                `endChapterPos` INTEGER NOT NULL,
                `content` TEXT NOT NULL,
                `colorArgb` INTEGER NOT NULL,
                `note` TEXT NOT NULL DEFAULT '',
                `createdAt` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_highlights_bookId` ON `highlights` (`bookId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_highlights_bookId_chapterIndex` ON `highlights` (`bookId`, `chapterIndex`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_highlights_createdAt` ON `highlights` (`createdAt`)")
    }
}

/**
 * v21→v22: replace_rules.kind — 净化分类标记。
 *
 * 0 = GENERAL（替换；与现状语义完全一致），1 = PURIFY（净化）。所有现存 row
 * 默认落 0，因此任何已配置的规则在升级后行为不变；新增的"净化"分类只在用户
 * 主动创建/编辑时才会出现 1 值。NOT NULL DEFAULT 0 让旧 ROM 上 ALTER TABLE
 * 直接成功，无需 reflow。
 */
private val MIGRATION_21_22 = object : Migration(21, 22) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE replace_rules ADD COLUMN `kind` INTEGER NOT NULL DEFAULT 0")
    }
}

/**
 * v22→v23: 搜索历史 search_keyword 表。
 *
 * 主键直接用 word（TEXT NOT NULL），upsert 时凭主键冲突累加 usage / 刷新 lastUseTime；
 * 不带自增 id —— word 本身天然唯一。lastUseTime / usage 默认 0 防止 NULL，给冷启动
 * 安全。索引未额外建，因为：
 *   - 排序 ORDER BY usage DESC, lastUseTime DESC 时表很小（最多几百行），全表扫足够；
 *   - 前缀联想 LIKE 'q%' 可走 PRIMARY KEY 索引，无需重复建 (word) 索引。
 */
private val MIGRATION_22_23 = object : Migration(22, 23) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `search_keyword` (
                `word` TEXT NOT NULL,
                `lastUseTime` INTEGER NOT NULL DEFAULT 0,
                `usage` INTEGER NOT NULL DEFAULT 1,
                PRIMARY KEY(`word`)
            )
            """.trimIndent()
        )
    }
}

private val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // book_sources 表结构完全重构，删除旧表并重建
        db.execSQL("DROP TABLE IF EXISTS `book_sources`")
        db.execSQL("""CREATE TABLE IF NOT EXISTS `book_sources` (
            `bookSourceUrl` TEXT NOT NULL,
            `bookSourceName` TEXT NOT NULL,
            `bookSourceGroup` TEXT,
            `bookSourceType` INTEGER NOT NULL DEFAULT 0,
            `bookUrlPattern` TEXT,
            `customOrder` INTEGER NOT NULL DEFAULT 0,
            `enabled` INTEGER NOT NULL DEFAULT 1,
            `enabledExplore` INTEGER NOT NULL DEFAULT 1,
            `concurrentRate` TEXT,
            `header` TEXT,
            `loginUrl` TEXT,
            `loginUi` TEXT,
            `loginCheckJs` TEXT,
            `coverDecodeJs` TEXT,
            `bookSourceComment` TEXT,
            `variableComment` TEXT,
            `lastUpdateTime` INTEGER NOT NULL DEFAULT 0,
            `respondTime` INTEGER NOT NULL DEFAULT 180000,
            `weight` INTEGER NOT NULL DEFAULT 0,
            `exploreUrl` TEXT,
            `searchUrl` TEXT,
            `ruleExplore` TEXT,
            `ruleSearch` TEXT,
            `ruleBookInfo` TEXT,
            `ruleToc` TEXT,
            `ruleContent` TEXT,
            `ruleReview` TEXT,
            PRIMARY KEY(`bookSourceUrl`)
        )""")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_book_sources_bookSourceUrl` ON `book_sources` (`bookSourceUrl`)")
    }
}

/**
 * Auto-backup database before any migration (upgrade or downgrade).
 * Keeps the 2 most recent backups in app internal storage.
 */
private fun backupDatabaseBeforeMigration(context: Context) {
    try {
        val dbFile = context.getDatabasePath("morealm.db")
        if (!dbFile.exists()) return
        val backupDir = File(context.filesDir, "db_backup")
        if (!backupDir.exists()) backupDir.mkdirs()
        val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault())
            .format(java.util.Date())
        val backupFile = File(backupDir, "morealm_$ts.db")
        dbFile.copyTo(backupFile, overwrite = true)
        // Also backup WAL/SHM if present
        val wal = File(dbFile.path + "-wal")
        val shm = File(dbFile.path + "-shm")
        if (wal.exists()) wal.copyTo(File(backupFile.path + "-wal"), overwrite = true)
        if (shm.exists()) shm.copyTo(File(backupFile.path + "-shm"), overwrite = true)
        // Keep only 2 most recent backups
        backupDir.listFiles { f -> f.name.endsWith(".db") }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(2)
            ?.forEach { old ->
                old.delete()
                File(old.path + "-wal").delete()
                File(old.path + "-shm").delete()
            }
        AppLog.info("DB", "Pre-migration backup: ${backupFile.name}")
    } catch (e: Exception) {
        AppLog.error("DB", "Backup failed", e)
    }
}

/**
 * Attempt to restore database from the most recent backup.
 * Called when Room detects a downgrade or migration failure.
 */
private fun restoreFromBackup(context: Context): Boolean {
    try {
        val backupDir = File(context.filesDir, "db_backup")
        val latest = backupDir.listFiles { f -> f.name.endsWith(".db") }
            ?.maxByOrNull { it.lastModified() } ?: return false
        val dbFile = context.getDatabasePath("morealm.db")
        latest.copyTo(dbFile, overwrite = true)
        val wal = File(latest.path + "-wal")
        val shm = File(latest.path + "-shm")
        if (wal.exists()) wal.copyTo(File(dbFile.path + "-wal"), overwrite = true)
        if (shm.exists()) shm.copyTo(File(dbFile.path + "-shm"), overwrite = true)
        AppLog.info("DB", "Restored from backup: ${latest.name}")
        return true
    } catch (e: Exception) {
        AppLog.error("DB", "Restore failed", e)
        return false
    }
}

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase {
        // Backup before Room opens (covers both upgrade and downgrade)
        backupDatabaseBeforeMigration(context)

        return Room.databaseBuilder(context, AppDatabase::class.java, "morealm.db")
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .addMigrations(
                MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5,
                MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9,
                MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13,
                MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18,
                MIGRATION_18_19,
                MIGRATION_19_20,
                MIGRATION_20_21,
                MIGRATION_21_22,
                MIGRATION_22_23,
            )
            // On downgrade: try restore from backup, otherwise keep tables as-is
            .addCallback(object : RoomDatabase.Callback() {
                override fun onDestructiveMigration(db: SupportSQLiteDatabase) {
                    AppLog.error("DB", "Destructive migration triggered! Attempting restore...")
                    restoreFromBackup(context)
                }
            })
            .build()
    }

    @Provides fun provideBookDao(db: AppDatabase): BookDao = db.bookDao()
    @Provides fun provideChapterDao(db: AppDatabase): ChapterDao = db.chapterDao()
    @Provides fun provideBookSourceDao(db: AppDatabase): BookSourceDao = db.bookSourceDao()
    @Provides fun provideBookGroupDao(db: AppDatabase): BookGroupDao = db.bookGroupDao()
    @Provides fun provideBookTagDao(db: AppDatabase): BookTagDao = db.bookTagDao()
    @Provides fun provideTagDefinitionDao(db: AppDatabase): TagDefinitionDao = db.tagDefinitionDao()
    @Provides fun provideReadProgressDao(db: AppDatabase): ReadProgressDao = db.readProgressDao()
    @Provides fun provideThemeDao(db: AppDatabase): ThemeDao = db.themeDao()
    @Provides fun provideReadStatsDao(db: AppDatabase): ReadStatsDao = db.readStatsDao()
    @Provides fun provideBookmarkDao(db: AppDatabase): BookmarkDao = db.bookmarkDao()
    @Provides fun provideHighlightDao(db: AppDatabase): HighlightDao = db.highlightDao()
    @Provides fun provideReplaceRuleDao(db: AppDatabase): ReplaceRuleDao = db.replaceRuleDao()
    @Provides fun provideReaderStyleDao(db: AppDatabase): ReaderStyleDao = db.readerStyleDao()
    @Provides fun provideTxtTocRuleDao(db: AppDatabase): TxtTocRuleDao = db.txtTocRuleDao()
    @Provides fun provideCacheDao(db: AppDatabase): CacheDao = db.cacheDao()
    @Provides fun provideCookieDao(db: AppDatabase): CookieDao = db.cookieDao()
    @Provides fun provideSearchBookCacheDao(db: AppDatabase): SearchBookCacheDao = db.searchBookCacheDao()
    @Provides fun provideSearchKeywordDao(db: AppDatabase): SearchKeywordDao = db.searchKeywordDao()

    @Provides
    @Singleton
    fun providePreferences(@ApplicationContext context: Context): AppPreferences =
        AppPreferences(context)
}
