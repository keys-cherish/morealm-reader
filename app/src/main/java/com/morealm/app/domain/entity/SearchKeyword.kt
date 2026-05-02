package com.morealm.app.domain.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 搜索历史关键词。每个关键词一行，用 [word] 作主键去重；同一词被再次搜索时
 * 通过 upsert（OnConflictStrategy.REPLACE）累加 [usage]、刷新 [lastUseTime]。
 *
 * 与 Legado 同表设计差异：
 *   - 不带自增 id —— word 本身是天然主键，省一个 INTEGER 列；
 *   - 不带 group 字段 —— Legado 把"图书 / 漫画 / 小说"分组，MoRealm 当前搜索是
 *     混合源，分组无意义；将来真要分时再加列。
 *
 * UI 排序策略由 DAO 决定：默认 usage DESC, lastUseTime DESC，让"用得多 / 用得新"
 * 的词排前面。"猜你想搜"前缀联想用 LIKE 'q%'。
 */
@Entity(tableName = "search_keyword")
data class SearchKeyword(
    @PrimaryKey val word: String,
    val lastUseTime: Long,
    val usage: Int = 1,
)
