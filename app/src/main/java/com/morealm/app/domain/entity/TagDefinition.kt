package com.morealm.app.domain.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * A user-visible (or system-internal) tag that can be applied to books.
 *
 * Categories of tags:
 * - **GENRE**: 玄幻 / 都市 / 言情 ... built-in seed list, user-editable keywords
 * - **SOURCE**: 起点 / 番茄 ... auto-generated per book source
 * - **FORMAT**: TXT / EPUB / PDF ... derived from [Book.format]
 * - **STATUS**: 在读 / 搁置 / 已读完 ... behaviour-derived, refreshed periodically
 * - **USER**: arbitrary user-created tag (replaces the legacy BookGroup)
 * - **SYSTEM**: smart shelves like 继续阅读 / 追更中 (rendered as views, not stored)
 *
 * `keywords` is a comma-separated text used by the classifier for substring matching;
 * users can edit it via the tag-editor sheet to teach the engine new vocabulary.
 */
@Serializable
@Entity(tableName = "tag_definitions")
data class TagDefinition(
    @PrimaryKey val id: String,
    val name: String,
    /** GENRE / SOURCE / FORMAT / STATUS / USER / SYSTEM — see [TagType] for canonical values. */
    val type: String,
    val keywords: String = "",
    /** Optional hex color "#RRGGBB". Null means use type default. */
    val color: String? = null,
    /** Optional emoji shown next to the tag name. */
    val icon: String? = null,
    val sortOrder: Int = 0,
    /** Built-in tags can't be renamed/deleted; their keywords stay user-editable. */
    val builtin: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
)

object TagType {
    const val GENRE = "GENRE"
    const val SOURCE = "SOURCE"
    const val FORMAT = "FORMAT"
    const val STATUS = "STATUS"
    const val USER = "USER"
    const val SYSTEM = "SYSTEM"
}
