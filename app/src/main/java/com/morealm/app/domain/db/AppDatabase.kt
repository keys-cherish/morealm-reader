package com.morealm.app.domain.db

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import com.morealm.app.domain.entity.*

/**
 * AppDatabase
 *
 * ## Schema 升级路线（参考 Legado 风格，强制走 AutoMigration）
 *
 * **从 v28 起，新版本必须用 AutoMigration**——Room 编译时会对比相邻版本的
 * schema JSON（位于 `app/schemas/`），自动生成 ALTER SQL。比手写 Migration
 * 更安全：Room 会校验列类型、外键、索引，错配则编译失败而不是运行时清数据。
 *
 * ### 加字段 / 加表的标准流程
 *
 * 1. 改 entity（加字段、加表）。
 * 2. 把下面 `version =` 改成 `oldVersion + 1`。
 * 3. 在 [autoMigrations] 数组里加一行：`AutoMigration(from = oldVersion, to = newVersion)`。
 * 4. 跑一次 build。KSP 会在 `app/schemas/<package>/<newVersion>.json` 生成新 schema，
 *    并在 `build/generated/ksp/.../AppDatabase_AutoMigration_${old}_${new}_Impl.java`
 *    生成迁移代码。
 * 5. 验证：如果新增字段是必填（NOT NULL 无 default）—— 必须加默认值，否则 Room 报错。
 *
 * ### 复杂变更（删列、改名、合表）
 *
 * 用 spec class：
 * ```
 * AutoMigration(from = 28, to = 29, spec = Migration_28_29::class)
 * @DeleteColumn(tableName = "books", columnName = "obsoleteField")
 * class Migration_28_29 : AutoMigrationSpec
 * ```
 *
 * ### 不能用 AutoMigration 的情况
 *
 * 跨表数据搬移、字段拆分合并等需要业务逻辑的迁移 —— 此时回退到手写
 * `Migration` 类（[com.morealm.app.di.AppModule] 里 `addMigrations(...)` 注册）。
 * 但**禁止**走 destructive 路径，宁可让 Room 抛异常崩溃，也不能静默清数据。
 */
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
    version = 28,
    exportSchema = true,
    autoMigrations = [
        // 从 v29 起新增条目都走这里。手写 MIGRATION_X_Y（已注册在 AppModule
        // addMigrations 里）覆盖了 v1~v28 的历史路径，不动它们。
        //
        // 例（占位示例，实际加字段时取消注释）：
        // AutoMigration(from = 28, to = 29),
    ],
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
