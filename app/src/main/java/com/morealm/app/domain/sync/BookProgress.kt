package com.morealm.app.domain.sync

import com.morealm.app.domain.entity.Book
import com.morealm.app.domain.entity.ReadProgress
import kotlinx.serialization.Serializable

/**
 * Per-book reading progress as serialised on the WebDav cloud.
 *
 * One JSON file per book (`<webDavDir>/bookProgress/<bookId>.json`); reader
 * uploads on chapter change, app start downloads all and merges newer
 * remote progress into the local db. Modeled after Legado's
 * `BookProgress` so users migrating from Legado would feel at home, but
 * keyed by [bookId] (stable Room PK) instead of name+author so renames
 * don't break the link.
 *
 * @property bookId          stable identifier — primary join key
 * @property name            book title at upload time, debug-only
 * @property author          book author at upload time, debug-only
 * @property chapterIndex    `Book.lastReadChapter` snapshot
 * @property chapterPosition `Book.lastReadPosition` (intra-chapter)
 * @property totalProgress   `Book.readProgress` (0-1 fraction)
 * @property scrollProgress  Deprecated since v28：本地 [ReadProgress] 不再保留
 *                           滚动百分比；这里保留字段是为了和老 WebDav JSON 保持
 *                           可解析，写入时填 0，读到 > 0 也忽略。
 * @property updatedAt       client wall-clock at upload; the merge step
 *                           prefers the larger of (remote, local) when
 *                           breaking ties on the cursor itself.
 */
@Serializable
data class BookProgress(
    val bookId: String,
    val name: String,
    val author: String,
    val chapterIndex: Int,
    val chapterPosition: Int,
    val totalProgress: Float,
    /** @see BookProgress KDoc — 字段保留仅为 JSON 兼容，本地不再使用。 */
    val scrollProgress: Int = 0,
    val updatedAt: Long = System.currentTimeMillis(),
) {
    companion object {
        fun from(book: Book, progress: ReadProgress): BookProgress = BookProgress(
            bookId = book.id,
            name = book.title,
            author = book.author,
            chapterIndex = progress.chapterIndex,
            chapterPosition = progress.chapterPosition,
            totalProgress = progress.totalProgress,
            // 本地实体已不存 scrollProgress；写云端时占位 0，老客户端读到也不会崩。
            scrollProgress = 0,
            updatedAt = System.currentTimeMillis(),
        )
    }
}
