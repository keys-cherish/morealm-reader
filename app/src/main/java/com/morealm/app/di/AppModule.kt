package com.morealm.app.di

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.morealm.app.domain.db.*
import com.morealm.app.domain.preference.AppPreferences
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
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

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "morealm.db")
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15)
            .fallbackToDestructiveMigrationOnDowngrade()
            .build()

    @Provides fun provideBookDao(db: AppDatabase): BookDao = db.bookDao()
    @Provides fun provideChapterDao(db: AppDatabase): ChapterDao = db.chapterDao()
    @Provides fun provideBookSourceDao(db: AppDatabase): BookSourceDao = db.bookSourceDao()
    @Provides fun provideBookGroupDao(db: AppDatabase): BookGroupDao = db.bookGroupDao()
    @Provides fun provideReadProgressDao(db: AppDatabase): ReadProgressDao = db.readProgressDao()
    @Provides fun provideThemeDao(db: AppDatabase): ThemeDao = db.themeDao()
    @Provides fun provideReadStatsDao(db: AppDatabase): ReadStatsDao = db.readStatsDao()
    @Provides fun provideBookmarkDao(db: AppDatabase): BookmarkDao = db.bookmarkDao()
    @Provides fun provideReplaceRuleDao(db: AppDatabase): ReplaceRuleDao = db.replaceRuleDao()
    @Provides fun provideReaderStyleDao(db: AppDatabase): ReaderStyleDao = db.readerStyleDao()
    @Provides fun provideTxtTocRuleDao(db: AppDatabase): TxtTocRuleDao = db.txtTocRuleDao()
    @Provides fun provideCacheDao(db: AppDatabase): CacheDao = db.cacheDao()
    @Provides fun provideCookieDao(db: AppDatabase): CookieDao = db.cookieDao()

    @Provides
    @Singleton
    fun providePreferences(@ApplicationContext context: Context): AppPreferences =
        AppPreferences(context)
}
