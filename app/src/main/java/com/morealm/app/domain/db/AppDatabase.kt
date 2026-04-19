package com.morealm.app.domain.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.morealm.app.domain.entity.*

@Database(
    entities = [
        Book::class,
        BookChapter::class,
        BookSource::class,
        BookGroup::class,
        ReadProgress::class,
        ThemeEntity::class,
        ReadStats::class,
        Bookmark::class,
        ReplaceRule::class,
        ReaderStyle::class,
        TxtTocRule::class,
        HttpTts::class,
    ],
    version = 9,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun chapterDao(): ChapterDao
    abstract fun bookSourceDao(): BookSourceDao
    abstract fun bookGroupDao(): BookGroupDao
    abstract fun readProgressDao(): ReadProgressDao
    abstract fun themeDao(): ThemeDao
    abstract fun readStatsDao(): ReadStatsDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun replaceRuleDao(): ReplaceRuleDao
    abstract fun readerStyleDao(): ReaderStyleDao
    abstract fun txtTocRuleDao(): TxtTocRuleDao
    abstract fun httpTtsDao(): HttpTtsDao
}
