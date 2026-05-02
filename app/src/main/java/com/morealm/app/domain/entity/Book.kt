package com.morealm.app.domain.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(
    tableName = "books",
    indices = [
        Index("folderId"),
        Index("lastReadAt"),
        Index("sourceId"),
    ]
)
data class Book(
    @PrimaryKey val id: String,
    val title: String,
    val author: String = "",
    val coverUrl: String? = null,
    /**
     * 用户自定义封面（走 CoverStorage，存为 WebP 在 filesDir/covers/BOOK/{id}.webp）。
     * 非 null 时显示优先级高于 coverUrl；null 时退回 coverUrl。
     */
    val customCoverUrl: String? = null,
    val localPath: String? = null,
    val sourceId: String? = null,
    val sourceUrl: String? = null,
    val folderId: String? = null,
    val format: BookFormat = BookFormat.TXT,

    // Reading state
    val lastReadChapter: Int = 0,
    val lastReadPosition: Int = 0,
    val lastReadOffset: Float = 0f,
    val totalChapters: Int = 0,
    val readProgress: Float = 0f,

    // Metadata
    val hasDetail: Boolean = false,
    val description: String? = null,
    val wordCount: String? = null,
    val rating: String? = null,
    val category: String? = null,
    val charset: String? = null,

    // Source-related fields
    val bookUrl: String = "",
    val tocUrl: String? = null,
    val origin: String = "",
    val originName: String = "",
    val kind: String? = null,
    val customTag: String? = null,
    val variable: String? = null,

    // Timestamps
    val addedAt: Long = System.currentTimeMillis(),
    val lastReadAt: Long = 0L,
    val latestChapterTime: Long = 0L,

    // Sort & display
    val pinned: Boolean = false,
    val sortOrder: Int = 0,

    // ── Update tracking (Legado-parity, since v16) ──
    /**
     * Number of new chapters discovered on the most recent toc refresh.
     * Used by the shelf "N 新" badge. Cleared (set to 0) when the user opens
     * the book — not when they finish reading the new chapters, matching
     * Legado's `Book.lastCheckCount` semantics.
     */
    val lastCheckCount: Int = 0,
    /** Wall-clock time (ms) of the most recent toc refresh attempt for this book. */
    val lastCheckTime: Long = 0L,
    /** When false, batch toc-refresh skips this book (user opted out). */
    val canUpdate: Boolean = true,

    // ── Auto-grouping bookkeeping (since v17) ──
    /** AUTO = TagResolver assigned the current folderId; MANUAL = user moved it; HYBRID = mixed. */
    val tagsAssignedBy: String = "AUTO",
    /** When true, TagResolver never overwrites this book's tags or folderId. */
    val groupLocked: Boolean = false,
)

@Serializable
enum class BookFormat {
    TXT, EPUB, PDF, MOBI, AZW3, CBZ, UMD, WEB, UNKNOWN
}
