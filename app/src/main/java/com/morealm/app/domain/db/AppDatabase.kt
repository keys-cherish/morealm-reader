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
 * 2. 把下面 [SCHEMA_VERSION] 常量改成 `oldVersion + 1`——`@Database(version=...)`
 *    自动跟随。
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
 *
 * ### Schema 版本号的真理来源
 *
 * [SCHEMA_VERSION] 是当前 Room schema 版本的**唯一权威来源**：
 * - `@Database(version = SCHEMA_VERSION)` 直接引用它（注解参数允许引用同 class
 *   的 `const val`，编译期就解析好）。
 * - [com.morealm.app.di.APP_DB_SCHEMA_VERSION] 也引用它，给 RecoveryGuard 等
 *   不持有 db 实例的模块比较 file user_version 用。
 *
 * 历史 bug：曾经在 AppModule 里手抄一份 `const val APP_DB_SCHEMA_VERSION = 28`，
 * v28→v29 升级时忘记同步导致 RecoveryGuard 把已升级的 DB 误判为「降级」 →
 * 启动死循环弹恢复界面。改用 const val 引用后**编译期保证一致**，再不会双源
 * 不同步。
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
    version = AppDatabase.SCHEMA_VERSION,
    exportSchema = true,
    autoMigrations = [
        // 手写 MIGRATION_X_Y（已注册在 AppModule addMigrations 里）覆盖了 v1~v29 的
        // 历史路径，不动它们。其中 v28→v29 因为同时 drop 列 + 业务 UPDATE，走手写
        // Migration 而非 AutoMigration——AutoMigration spec 只能 @DeleteColumn drop 列，
        // 没法附带业务 UPDATE 5 个 builtin row。
        //
        // 例（占位示例，实际加字段时取消注释）：
        // AutoMigration(from = 29, to = 30),
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

    companion object {
        /**
         * 当前 Room schema 版本。**改这里就够了** —— `@Database(version=...)` 通过
         * 注解直接引用，外部模块（[com.morealm.app.di.APP_DB_SCHEMA_VERSION]）也
         * 通过 const val 编译期同步。
         */
        const val SCHEMA_VERSION = 31
    }
}
