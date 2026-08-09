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
    /**
     * 反向作用域 —— 与参照实现 `ReplaceRule.excludeScope` 同义：被列在这里的书名 / 源
     * URL **不应用**该规则。参照实现 scope 用换行 `\n` 分隔多值；MoRealm 保留同一格式
     * 以便 DAO 直接走 `LIKE '%xxx%'` 包含匹配。
     *
     * - `null` (默认) 表示无排除 —— 与现网行为完全一致，旧 row 升级到 v30 后保持 null。
     * - 空串 `""` 视同 null，DAO 查询使用 `excludeScope IS NULL OR excludeScope = ''` 兜底。
     *
     * UI 目前没有专门编辑入口，主要通过一键搬家参照实现时透传；后续如需暴露给用户，
     * 在 [ReplaceRuleScreen] 里加 OutlinedTextField 即可。
     */
    val excludeScope: String? = null,
    /**
     * 章节作用域 —— `null`（默认）= 对 [scope] 圈定范围内的**所有章**生效；非 null =
     * 只对该 chapterIndex 生效。
     *
     * 动机：选区菜单的「替换」是就地纠错（错字 / 译名 / 转码乱码），有时只想动手上
     * 这一章，不想全书一刀切。旧 schema 只有「全局 / 按书」两档表达不了。
     *
     * 老规则升级到 v39 后一律为 null，行为与之前完全一致。
     */
    val chapterIndex: Int? = null,
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
