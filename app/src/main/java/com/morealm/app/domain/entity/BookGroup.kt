package com.morealm.app.domain.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(tableName = "book_groups")
data class BookGroup(
    @PrimaryKey val id: String,
    val name: String,
    val parentId: String? = null,
    val sortOrder: Int = 0,
    val pinned: Boolean = false,
    val emoji: String? = null,
    val autoKeywords: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    /**
     * True iff this folder was auto-created by [AutoFolderManager] in response
     * to crossing the genre threshold (e.g. user has 3+ books tagged 玄幻 and
     * had no folder named 玄幻 yet, so we made one).
     *
     * User-created folders always have `auto = false` and are *never* renamed,
     * deleted, or reshuffled by the auto-grouping engine. When the user
     * deletes an auto-folder, its source genre tag id is recorded in
     * [AppPreferences.autoFolderIgnored] so we don't recreate it next time.
     */
    val auto: Boolean = false,
    /**
     * 用户自定义分组封面（走 CoverStorage，WebP 存 filesDir/covers/GROUP/{id}.webp）。
     * 非 null 时覆盖自动拼图封面；null 时 UI 回退到自动 2×2 九宫格。
     */
    val customCoverUrl: String? = null,
)
