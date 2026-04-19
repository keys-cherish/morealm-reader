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
)

@Serializable
enum class BookFormat {
    TXT, EPUB, PDF, MOBI, AZW3, CBZ, UNKNOWN
}
