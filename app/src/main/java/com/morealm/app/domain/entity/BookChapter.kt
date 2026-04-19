package com.morealm.app.domain.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(
    tableName = "book_chapters",
    indices = [Index("bookId")]
)
data class BookChapter(
    @PrimaryKey val id: String,
    val bookId: String,
    val index: Int,
    val title: String,
    val url: String = "",
    val nextUrl: String? = null,
    val startPosition: Long = 0L,
    val endPosition: Long = 0L,
    val isVolume: Boolean = false,
    val variable: String? = null,
)
