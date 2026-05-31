package com.morealm.app.domain.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 用户高亮词（「文字上色」Phase 2）。登记一个词后，正文每次出现都按 [colorIndex] 对应的
 * 高亮色自动着色（长词优先、大小写不敏感由匹配器 HighlightWordMatcher 实现）。
 *
 * - [word] 唯一去重（[Index] unique）——同词只保留一条，改色 = REPLACE。
 * - [colorIndex] 指向调色板的高亮色组下标（见 RuleColorPalette 高亮色）。
 * - 全局生效（非 per-book，MVP 简化，对齐工具属性）。
 */
@Entity(
    tableName = "highlight_words",
    indices = [Index(value = ["word"], unique = true)],
)
data class HighlightWord(
    @PrimaryKey val id: String,
    val word: String,
    val colorIndex: Int,
    val createdAt: Long = System.currentTimeMillis(),
)
