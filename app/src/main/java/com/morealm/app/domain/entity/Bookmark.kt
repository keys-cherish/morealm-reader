package com.morealm.app.domain.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(
    tableName = "bookmarks",
    indices = [Index("bookId")]
)
data class Bookmark(
    @PrimaryKey val id: String,
    val bookId: String,
    val chapterIndex: Int,
    val chapterTitle: String = "",
    val content: String = "",  // snippet of text near bookmark
    val scrollProgress: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
)
