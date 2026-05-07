package com.morealm.app.domain.db.snapshot

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.util.Base64
import androidx.sqlite.db.SupportSQLiteDatabase
import com.morealm.app.BuildConfig
import com.morealm.app.core.log.AppLog
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 全量 JSON 快照管理器：DB → JSON 文件 / JSON 文件 → DB。
 *
 * ## 核心设计原则
 *
 * 1. **始终只保留一份最新全量** [SNAPSHOT_FILE_NAME]。每次新快照覆盖旧快照——
 *    用户决定不留多份历史，只为最近一次"能恢复"。
 * 2. **schema 变化时多保留一份升级前快照** [snapshotPreSchemaFile]——这是
 *    "升级翻车回退"的最后一根稻草。文件名带 oldVersion 区分。
 * 3. **schema-agnostic 编码**：所有列值统一存为 String（[Cursor.getString]），
 *    BLOB 用 Base64。Import 按 PRAGMA table_info 里的列声明类型反向 cast。
 *    这样 entity 类整体重构（字段改名/换类型）也能恢复——只要列名 / 表名一致。
 * 4. **不依赖 Kotlin entity 类**：导出导入完全走 SQLite 元信息（sqlite_master /
 *    PRAGMA table_info），与代码生成的 DAO / @Entity 解耦。新版本删除某张表 entity
 *    后，旧 JSON 里那张表的数据自动丢弃（PRAGMA table_info 查不到列）。
 *
 * ## 不会做的事
 *
 * - **不**自动调度（不是 Worker / 不是 Service）。调度由调用方决定（每日触发器、
 *   schema 变化触发器、Settings 手动按钮）。
 * - **不**做行级增量。文件级"始终一份最新全量"已能覆盖恢复需求；行级增量复杂度
 *   不划算（参考用户决策）。
 * - **不**调 Room API。所有操作走 [SupportSQLiteDatabase] 原生接口——避免与
 *   InvalidationTracker、迁移流程纠缠。
 *
 * ## 使用流程
 *
 * ```kotlin
 * val mgr = SnapshotManager(context)
 *
 * // 导出（在 Room 已初始化后任何时机都可以，建议在 IO 协程）
 * mgr.exportToFile(db.openHelper.writableDatabase)
 *
 * // 升级前留一份升级前快照（只调用一次，覆盖式）
 * mgr.preserveAsPreSchemaSnapshot(oldSchemaVersion = 28)
 *
 * // 导入（清表 + 重写）：必须保证当前没有其他 DAO 在写
 * val report = mgr.importFromFile(db.openHelper.writableDatabase, file)
 * ```
 */
class SnapshotManager(private val context: Context) {

    private val snapshotDir: File by lazy {
        File(context.filesDir, SNAPSHOT_DIR_NAME).also { if (!it.exists()) it.mkdirs() }
    }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    /** 最新全量快照路径（永远只有一份，覆盖式更新）。 */
    val latestSnapshotFile: File get() = File(snapshotDir, SNAPSHOT_FILE_NAME)

    /** schema 升级前快照（按 oldVersion 命名，每次升级保留一份）。 */
    fun snapshotPreSchemaFile(oldVersion: Int): File =
        File(snapshotDir, "snapshot_pre_v${oldVersion}.json")

    /**
     * 列出当前可用的所有快照文件（按修改时间倒序，最新在前）。
     * 用于 RecoveryActivity 的 UI 展示。
     */
    fun listSnapshots(): List<File> =
        snapshotDir.listFiles { f -> f.name.endsWith(".json") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

    /**
     * 是否到了应当跑「每日全量快照」的时机：
     *   - 还没有任何快照文件 → 必跑（首次启动）
     *   - 最新快照的 lastModified < 今天 0:00 → 必跑（昨天或之前的快照过期）
     *   - 最新快照已是今天产生 → 跳过
     *
     * 用文件 mtime 而不是 SharedPreferences 记 lastSnapshotDate——少一处状态来源，
     * 文件本身就是真理。即使 SharedPreferences 被清也不会重复跑。
     */
    fun isDailySnapshotDue(): Boolean {
        if (!latestSnapshotFile.exists()) return true
        val midnightMs = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        return latestSnapshotFile.lastModified() < midnightMs
    }

    /**
     * 便捷封装：检查 + 跑全量快照。Application 启动时 fire-and-forget。
     *
     * 不可重入保护交给调用方（用 launch 串行调度即可）。失败仅 log，不传递异常。
     */
    fun runDailySnapshotIfDue(db: SupportSQLiteDatabase) {
        if (!isDailySnapshotDue()) {
            AppLog.debug("Snapshot", "Daily snapshot skipped (already done today)")
            return
        }
        val t0 = System.currentTimeMillis()
        exportToFile(db).onSuccess {
            AppLog.info("Snapshot", "Daily snapshot done in ${System.currentTimeMillis() - t0}ms")
        }.onFailure {
            AppLog.error("Snapshot", "Daily snapshot failed", it)
        }
    }

    /**
     * 把当前 DB 全量导出到 [latestSnapshotFile]，覆盖式。
     *
     * **不可重入**：调用方需保证此刻无并发写入（建议在 IO 调度器 + DB 静止期）。
     * 即使中途崩溃，原 latest 文件也不会损坏——通过 tmp 文件 + rename 原子替换。
     */
    fun exportToFile(db: SupportSQLiteDatabase): Result<File> = runCatching {
        val snapshot = exportToObject(db)
        val tmp = File(snapshotDir, "$SNAPSHOT_FILE_NAME.tmp")
        tmp.outputStream().bufferedWriter().use { writer ->
            writer.write(json.encodeToString(snapshot))
        }
        if (latestSnapshotFile.exists()) latestSnapshotFile.delete()
        if (!tmp.renameTo(latestSnapshotFile)) {
            tmp.copyTo(latestSnapshotFile, overwrite = true)
            tmp.delete()
        }
        AppLog.info(
            "Snapshot",
            "Export OK: tables=${snapshot.tables.size}" +
                " rows=${snapshot.tables.sumOf { it.rows.size }} bytes=${latestSnapshotFile.length()}",
        )
        latestSnapshotFile
    }

    /**
     * 把当前 [latestSnapshotFile] 复制为 [snapshotPreSchemaFile]——schema 升级触发器
     * 在 Room 打开 DB **之前**调用，留一份"升级前的最后状态"。
     *
     * 如果 latest 文件不存在（比如初次安装），此函数 no-op。
     * 如果 oldVersion 对应的 pre-schema 文件已存在（之前升过这个版本），覆盖。
     */
    fun preserveAsPreSchemaSnapshot(oldVersion: Int): Result<File?> = runCatching {
        if (!latestSnapshotFile.exists()) {
            AppLog.info("Snapshot", "preserveAsPreSchemaSnapshot skipped (no latest)")
            return@runCatching null
        }
        val target = snapshotPreSchemaFile(oldVersion)
        latestSnapshotFile.copyTo(target, overwrite = true)
        AppLog.info("Snapshot", "Pre-schema snapshot saved: ${target.name}")
        target
    }

    /**
     * 从 JSON 文件恢复到当前 DB。
     *
     * **危险操作**：
     * - 会清空 [DbSnapshot.tables] 里每张表的现有数据（DELETE FROM）
     * - 然后按 JSON 重新 INSERT
     * - 整个过程在单个 transaction 里，失败回滚
     * - 当前 schema 里**不存在**的表（JSON 里有的）会被跳过
     * - JSON 里**没有**的表保持现状（不动）
     *
     * @return [ImportReport] 含每张表的 inserted/skipped 统计。
     */
    fun importFromFile(db: SupportSQLiteDatabase, file: File): Result<ImportReport> = runCatching {
        val text = file.readText()
        val snapshot = json.decodeFromString<DbSnapshot>(text)
        importFromObject(db, snapshot)
    }

    // ── 核心导出/导入实现 ──

    fun exportToObject(db: SupportSQLiteDatabase): DbSnapshot {
        val tableNames = db.query(
            "SELECT name FROM sqlite_master WHERE type='table' " +
                "AND name NOT LIKE 'android_metadata' " +
                "AND name NOT LIKE 'sqlite_%' " +
                "AND name NOT LIKE 'room_master_table'",
        ).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }

        val tables = tableNames.map { tableName ->
            val typesByCol = readDeclaredColumnTypes(db, tableName)
            val rows = db.query("SELECT * FROM `$tableName`").use { cursor ->
                val cols = (0 until cursor.columnCount).map { cursor.getColumnName(it) }
                buildList {
                    while (cursor.moveToNext()) {
                        add(buildMap<String, String?>(cols.size) {
                            cols.forEachIndexed { i, col ->
                                put(col, encodeCellValue(cursor, i))
                            }
                        })
                    }
                }
            }
            TableSnapshot(name = tableName, declaredColumnTypes = typesByCol, rows = rows)
        }

        return DbSnapshot(
            schemaVersion = db.version,
            createdAtMs = System.currentTimeMillis(),
            appVersionCode = BuildConfig.VERSION_CODE,
            tables = tables,
        )
    }

    fun importFromObject(db: SupportSQLiteDatabase, snapshot: DbSnapshot): ImportReport {
        val tableReports = mutableMapOf<String, TableImportStat>()
        // 关闭 FK 约束防止恢复顺序冲突；transaction 结束后开回。
        db.execSQL("PRAGMA foreign_keys = OFF")
        db.beginTransaction()
        try {
            for (table in snapshot.tables) {
                val currentCols = readDeclaredColumnTypes(db, table.name)
                if (currentCols.isEmpty()) {
                    tableReports[table.name] = TableImportStat(
                        skippedTableMissing = true, totalInJson = table.rows.size,
                    )
                    continue
                }
                db.execSQL("DELETE FROM `${table.name}`")
                var inserted = 0
                var failed = 0
                for (row in table.rows) {
                    val cv = ContentValues()
                    var hasAnyCol = false
                    for ((col, raw) in row) {
                        val declType = currentCols[col] ?: continue // 当前 schema 已删除此列
                        applyValueToContentValues(cv, col, raw, declType)
                        hasAnyCol = true
                    }
                    if (!hasAnyCol) {
                        failed++
                        continue
                    }
                    try {
                        db.insert(table.name, SQLiteDatabase.CONFLICT_REPLACE, cv)
                        inserted++
                    } catch (e: Exception) {
                        AppLog.warn("Snapshot", "Insert ${table.name} failed: ${e.message}")
                        failed++
                    }
                }
                tableReports[table.name] = TableImportStat(
                    inserted = inserted, failed = failed, totalInJson = table.rows.size,
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
            db.execSQL("PRAGMA foreign_keys = ON")
        }
        return ImportReport(snapshotMeta = snapshot, tables = tableReports)
    }

    /** 取所有列的声明类型（PRAGMA table_info）—— 列名（保持原大小写）→ TEXT/INTEGER/REAL/BLOB/NUMERIC。 */
    private fun readDeclaredColumnTypes(db: SupportSQLiteDatabase, tableName: String): Map<String, String> =
        db.query("PRAGMA table_info(`$tableName`)").use { c ->
            buildMap {
                val nameIdx = c.getColumnIndexOrThrow("name")
                val typeIdx = c.getColumnIndexOrThrow("type")
                while (c.moveToNext()) {
                    put(c.getString(nameIdx), c.getString(typeIdx).orEmpty().uppercase())
                }
            }
        }

    private fun encodeCellValue(cursor: Cursor, columnIndex: Int): String? = when (cursor.getType(columnIndex)) {
        Cursor.FIELD_TYPE_NULL -> null
        Cursor.FIELD_TYPE_BLOB -> Base64.encodeToString(cursor.getBlob(columnIndex), Base64.NO_WRAP)
        else -> cursor.getString(columnIndex)
    }

    /**
     * 把 JSON 里的 String 值按当前 schema 列声明类型回填到 ContentValues。
     *
     * 类型亲和性表：
     * - INTEGER → toLongOrNull() ?: 0
     * - REAL → toDoubleOrNull() ?: 0.0
     * - BLOB → Base64.decode（导出时 Base64 编码过）
     * - TEXT / NUMERIC / 空类型 → 原样 String
     */
    private fun applyValueToContentValues(
        cv: ContentValues,
        col: String,
        raw: String?,
        declType: String,
    ) {
        if (raw == null) {
            cv.putNull(col)
            return
        }
        val typeUpper = declType.uppercase()
        when {
            typeUpper.contains("INT") -> cv.put(col, raw.toLongOrNull() ?: 0L)
            typeUpper.contains("REAL") || typeUpper.contains("FLOA") || typeUpper.contains("DOUB") ->
                cv.put(col, raw.toDoubleOrNull() ?: 0.0)
            typeUpper.contains("BLOB") -> cv.put(
                col,
                runCatching { Base64.decode(raw, Base64.NO_WRAP) }.getOrElse { ByteArray(0) },
            )
            else -> cv.put(col, raw)
        }
    }

    companion object {
        private const val SNAPSHOT_DIR_NAME = "db_snapshot"
        private const val SNAPSHOT_FILE_NAME = "snapshot.json"
    }
}

/** 单张表的导入结果统计。 */
data class TableImportStat(
    val inserted: Int = 0,
    val failed: Int = 0,
    /** 跳过原因：当前 schema 已不存在此表。 */
    val skippedTableMissing: Boolean = false,
    /** JSON 里这张表的总行数。 */
    val totalInJson: Int = 0,
)

/** 整次导入的报告，UI 层展示用。 */
data class ImportReport(
    val snapshotMeta: DbSnapshot,
    val tables: Map<String, TableImportStat>,
) {
    val totalInserted: Int get() = tables.values.sumOf { it.inserted }
    val totalFailed: Int get() = tables.values.sumOf { it.failed }
    val tablesSkipped: List<String> get() = tables.filterValues { it.skippedTableMissing }.keys.toList()
}
