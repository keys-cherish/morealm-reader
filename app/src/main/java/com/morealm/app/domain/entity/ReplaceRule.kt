package com.morealm.app.domain.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(tableName = "replace_rules")
data class ReplaceRule(
    @PrimaryKey val id: String,
    val name: String = "",
    val pattern: String,
    val replacement: String = "",
    val isRegex: Boolean = false,
    val scope: String = "",       // empty = all books, or bookId
    val bookId: String? = null,   // null = global, specific bookId = per-book
    val scopeTitle: Boolean = false,   // apply to chapter titles
    val scopeContent: Boolean = true,  // apply to chapter content
    val enabled: Boolean = true,
    val sortOrder: Int = 0,
    val timeoutMs: Int = 3000,    // regex timeout in ms
    /**
     * 规则分类：[KIND_GENERAL] (0, 默认) = 内容替换；[KIND_PURIFY] (1) = 净化清洗。
     *
     * ContentProcessor 应用顺序固定 **先净化后替换**：净化只删不改，先把广告/版权
     * /冗余清掉，再做语义替换；倒过来会让替换规则误中广告里出现的关键字。
     *
     * 用 Int 而非 String enum，是因为 Room 列只多一个 INTEGER NOT NULL DEFAULT 0，
     * 而旧 row 直接落到 GENERAL，与现状 100% 一致；将来需要更多类别（比如「繁简转换」）
     * 时再追加常量即可，不必动 schema。
     */
    val kind: Int = KIND_GENERAL,
) {
    fun isValid(): Boolean {
        if (!isRegex) return pattern.isNotEmpty()
        return try {
            Regex(pattern)
            !pattern.endsWith("|") // trailing | causes catastrophic backtracking
        } catch (_: Exception) { false }
    }

    companion object {
        /** 内容替换 — 把 A 替换为 B（保留长度可变）。是默认分类。 */
        const val KIND_GENERAL = 0

        /** 净化清洗 — 删广告 / 版权声明 / 推广，等价于 replacement = ""。
         *  与 GENERAL 在执行顺序上有先后（净化先），UI 也分组展示。 */
        const val KIND_PURIFY = 1
    }
}
