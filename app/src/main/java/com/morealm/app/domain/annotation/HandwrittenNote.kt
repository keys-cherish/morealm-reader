package com.morealm.app.domain.annotation

import kotlinx.serialization.Serializable

@Serializable
data class InkPoint(val x: Float, val y: Float)

@Serializable
data class InkStroke(val points: List<InkPoint>, val color: Int, val width: Float)

/** Strokes use normalized coordinates on the frozen page; the text anchor survives reflow. */
@Serializable
data class HandwrittenNote(
    val id: String,
    val bookId: String,
    val chapterIndex: Int,
    val chapterId: String,
    val chapterPosition: Int,
    val chapterTitle: String,
    val createdAt: Long,
    val strokes: List<InkStroke> = emptyList(),
    val version: Int = 1,
)

/** Erase intersecting strokes, including a segment crossed between sparse sampled points. */
fun InkStroke.touches(point: InkPoint, radius: Float): Boolean {
    fun distanceSquared(a: InkPoint, b: InkPoint): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val length = dx * dx + dy * dy
        val t = if (length == 0f) 0f else (((point.x - a.x) * dx + (point.y - a.y) * dy) / length).coerceIn(0f, 1f)
        val x = point.x - (a.x + t * dx)
        val y = point.y - (a.y + t * dy)
        return x * x + y * y
    }
    return when (points.size) {
        0 -> false
        1 -> distanceSquared(points[0], points[0]) <= radius * radius
        else -> points.zipWithNext().any { (a, b) -> distanceSquared(a, b) <= radius * radius }
    }
}
