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
        BookTag::class,
        TagDefinition::class,
        ReadProgress::class,
        ThemeEntity::class,
        ReadStats::class,
        Bookmark::class,
        Highlight::class,
        ReplaceRule::class,
        ReaderStyle::class,
        TxtTocRule::class,
        HttpTts::class,
        Cache::class,
        Cookie::class,
        SearchBookCache::class,
        SearchKeyword::class,
    ],
    version = 27,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun chapterDao(): ChapterDao
    abstract fun bookSourceDao(): BookSourceDao
    abstract fun bookGroupDao(): BookGroupDao
    abstract fun bookTagDao(): BookTagDao
    abstract fun tagDefinitionDao(): TagDefinitionDao
    abstract fun readProgressDao(): ReadProgressDao
    abstract fun themeDao(): ThemeDao
    abstract fun readStatsDao(): ReadStatsDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun highlightDao(): HighlightDao
    abstract fun replaceRuleDao(): ReplaceRuleDao
    abstract fun readerStyleDao(): ReaderStyleDao
    abstract fun txtTocRuleDao(): TxtTocRuleDao
    abstract fun httpTtsDao(): HttpTtsDao
    abstract fun cacheDao(): CacheDao
    abstract fun cookieDao(): CookieDao
    abstract fun searchBookCacheDao(): SearchBookCacheDao
    abstract fun searchKeywordDao(): SearchKeywordDao
}
