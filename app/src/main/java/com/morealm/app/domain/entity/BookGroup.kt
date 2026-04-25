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
)
