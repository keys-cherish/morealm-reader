package com.morealm.app.domain.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(tableName = "read_progress")
data class ReadProgress(
    @PrimaryKey val bookId: String,
    val chapterIndex: Int = 0,
    val chapterPosition: Int = 0,
    val chapterOffset: Float = 0f,
    val totalProgress: Float = 0f,
    val updatedAt: Long = System.currentTimeMillis(),
)
