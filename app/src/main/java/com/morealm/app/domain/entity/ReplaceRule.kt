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
) {
    fun isValid(): Boolean {
        if (!isRegex) return pattern.isNotEmpty()
        return try {
            Regex(pattern)
            !pattern.endsWith("|") // trailing | causes catastrophic backtracking
        } catch (_: Exception) { false }
    }
}
