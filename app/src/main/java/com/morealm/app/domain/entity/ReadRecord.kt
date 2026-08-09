package com.morealm.app.domain.entity

import androidx.room.Entity
import androidx.room.Index
import kotlinx.serialization.Serializable

/**
 * 阅读记录 entity —— 每日每本书阅读时长。
 *
 * **粒度（已与用户对齐）**：进入阅读器 → 退出 的整段时长累加（同参照实现 ReadRecord）。
 *
 * **UI 用途**：「我的 → 阅读记录」页面按 [date] 降序分组列表，每日下面列当天读过的书。
 *
 * **主键** = `(date, bookId)` → 每天每本书 1 行；同日多次进入阅读器累加 [readTime] + 刷新 [lastReadAt]。
 *
 * **参照实现导入兼容**：bookId 不可为 null（参照实现仅有 `bookName + deviceId`），按 bookName fuzzy
 * match MoRealm 书架拿 bookId；未匹配的 fallback `legado_imported_<bookname>` —— 仍可入库展示
 * 阅读历史，只是点不进对应书籍。bookName 冗余存储，参照实现兼容查询 + UI 不用 JOIN。
 *
 * **冗余字段说明**：[bookName] / [coverPath] 都冗余自 books 表，是为了在书被删除后阅读记录仍
 * 能展示（"已删除的书 X"）+ 参照实现导入不强依赖书架匹配。代价：~50 字节/行，可接受。
 *
 * 关联：MEMORY.md「阅读器导航语义」+ 参考实现的阅读记录实体。
 */
@Serializable
@Entity(
    tableName = "read_records",
    primaryKeys = ["date", "bookId"],
    indices = [Index(value = ["date"]), Index(value = ["bookId"])],
)
data class ReadRecord(
    /** yyyy-MM-dd 用户视角分组键，UI 列表 groupBy 此字段 */
    val date: String,
    /** 书 id；MoRealm 内部 UUID 或参照实现导入兜底 "legado_imported_<bookname>" */
    val bookId: String,
    /** 冗余存储：参照实现导入按 bookName 索引、UI 不用 JOIN、书被删后记录仍可展示 */
    val bookName: String,
    /** 当日累计阅读时长（毫秒） */
    val readTime: Long = 0L,
    /** 当日最后阅读时间戳（epoch ms），UI 同一天内按此降序排 */
    val lastReadAt: Long = 0L,
    /** 封面路径（冗余，书被删后展示用） */
    val coverPath: String? = null,
)
