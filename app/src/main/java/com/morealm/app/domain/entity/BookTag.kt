package com.morealm.app.domain.entity

import androidx.room.Entity
import androidx.room.Index
import kotlinx.serialization.Serializable

/**
 * Many-to-many relation: a book can carry several tags simultaneously.
 *
 * Each row records *who* assigned the tag and how confident the assignment is —
 * this lets the auto-grouping engine distinguish user-pinned tags (manual,
 * never overwritten) from heuristic guesses (auto, may be replaced by a
 * higher-scoring tag when new metadata arrives).
 */
@Serializable
@Entity(
    tableName = "book_tags",
    primaryKeys = ["bookId", "tagId"],
    indices = [Index("bookId"), Index("tagId")],
)
data class BookTag(
    val bookId: String,
    val tagId: String,
    /** AUTO / MANUAL / SUGGESTED — see [TagAssignSource] for canonical values. */
    val assignedBy: String,
    /** Classifier confidence in 0..1; manual assignments use 1.0. */
    val score: Float = 0f,
    val assignedAt: Long = System.currentTimeMillis(),
)

object TagAssignSource {
    const val AUTO = "AUTO"
    const val MANUAL = "MANUAL"
    const val SUGGESTED = "SUGGESTED"
}
