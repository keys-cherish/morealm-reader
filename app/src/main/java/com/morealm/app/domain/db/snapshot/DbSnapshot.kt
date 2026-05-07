package com.morealm.app.domain.db.snapshot

import kotlinx.serialization.Serializable

/**
 * 单张表的全量快照。
 *
 * **schema-agnostic 设计**：rows 用 Map<String, String?> 存储，键 = 列名，
 * 值 = SQLite cursor.getString() 拿到的字符串形式（NULL 用 Kotlin null，BLOB 用 Base64）。
 *
 * 跨 schema 版本恢复时按列名做交集匹配：
 * - JSON 里有但当前 schema 没有的列 → 丢弃（字段被删了）
 * - 当前 schema 有但 JSON 没有的列 → 用 column default（字段是新加的）
 * - 两边都有 → 按 SQLite 类型亲和性回填（PRAGMA table_info 里的 type）
 *
 * 这样即使 entity 类完全重构（@Entity 字段改名/换类型），只要列名一致都能恢复。
 *
 * @property name 表名（不含反引号）。
 * @property declaredColumnTypes 列名 → SQLite 声明类型（来自 PRAGMA table_info）。
 *   Import 时按这个反向 cast；NULL 类型表示无类型亲和性，按 String 写入。
 * @property rows 行集合。每行 = 列名 → 字符串值（或 null）。
 */
@Serializable
data class TableSnapshot(
    val name: String,
    val declaredColumnTypes: Map<String, String>,
    val rows: List<Map<String, String?>>,
)

/**
 * 全库快照。一份 JSON 文件一个 [DbSnapshot]。
 *
 * @property schemaVersion 导出时的 SQLite user_version。Import 时用它判断兼容性。
 * @property createdAtMs 快照生成时的 epoch 毫秒。
 * @property appVersionCode 导出时 app 的 versionCode（debug 信息，恢复决策时参考）。
 * @property tables 全部业务表，已剔除 sqlite_master / android_metadata / room_master_table 等系统表。
 */
@Serializable
data class DbSnapshot(
    val schemaVersion: Int,
    val createdAtMs: Long,
    val appVersionCode: Int,
    val tables: List<TableSnapshot>,
)
