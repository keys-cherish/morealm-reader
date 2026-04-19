package com.morealm.app.domain.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(tableName = "read_stats")
data class ReadStats(
    @PrimaryKey val date: String, // yyyy-MM-dd
    val readDurationMs: Long = 0L,
    val pagesRead: Int = 0,
    val booksFinished: Int = 0,
    val wordsRead: Long = 0L,
)
