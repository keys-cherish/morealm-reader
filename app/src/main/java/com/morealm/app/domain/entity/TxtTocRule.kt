package com.morealm.app.domain.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * TXT chapter detection rule — user-configurable regex patterns for TOC parsing.
 * Multiple rules can be enabled; the parser picks the best-matching one.
 */
@Serializable
@Entity(tableName = "txt_toc_rules")
data class TxtTocRule(
    @PrimaryKey val id: String,
    val name: String,
    val pattern: String,
    val example: String = "",
    val enabled: Boolean = true,
    val sortOrder: Int = 0,
    val isBuiltin: Boolean = false,
) {
    companion object {
        fun defaults(): List<TxtTocRule> = listOf(
            TxtTocRule("builtin_1", "第X章", "^\\s*第[零一二三四五六七八九十百千万\\d]+[章节回卷集部篇].*", "第一章 开始", true, 0, true),
            TxtTocRule("builtin_2", "Chapter N", "^\\s*Chapter\\s+\\d+.*", "Chapter 1 Begin", true, 1, true),
            TxtTocRule("builtin_3", "卷X", "^\\s*卷[零一二三四五六七八九十百千万\\d]+.*", "卷一 起源", true, 2, true),
            TxtTocRule("builtin_4", "正文/序章/楔子/番外", "^\\s*(正文|序[章言]|楔子|番外).*", "序章 缘起", true, 3, true),
            TxtTocRule("builtin_5", "数字. 标题", "^\\s*\\d{1,4}[.、]\\s*.+", "1. 开端", false, 4, true),
            TxtTocRule("builtin_6", "【标题】", "^\\s*【.+】.*", "【第一话】", false, 5, true),
        )
    }
}
