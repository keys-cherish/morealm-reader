package com.morealm.app.presentation.reader

import com.morealm.app.domain.entity.Book
import com.morealm.app.domain.entity.ReadProgress

/**
 * 打开阅读器时使用的一份完整游标。
 *
 * [Book] 和 [ReadProgress] 都保存了阅读位置，但它们可能因进程中断、WebDAV 合并或
 * 旧版本的分步写入而处于不同时间点。恢复时必须整份选择较新的来源，不能把一份的
 * chapterIndex 与另一份的 chapterPosition 拼起来，否则会生成用户从未浏览过的位置。
 */
internal data class ReaderResumeCursor(
    val chapterIndex: Int,
    val chapterPosition: Int,
    val chapterProgress: Int,
    val source: Source,
) {
    enum class Source { BOOK, READ_PROGRESS }
}

/**
 * 按时间戳选择完整游标，并把跨章节数变更后已经越界的位置安全地降到有效章首。
 */
internal fun resolveReaderResumeCursor(
    book: Book,
    progress: ReadProgress?,
    chapterCount: Int,
): ReaderResumeCursor {
    if (chapterCount <= 0) {
        return ReaderResumeCursor(0, 0, 0, ReaderResumeCursor.Source.BOOK)
    }

    // 同一次本地保存会给两张表写相同时间戳；平局时以专用进度表为准。
    // WebDAV 只更新 Book 时 book.lastReadAt 会更晚，因此能正确选中远端合并结果。
    val useProgress = progress != null && progress.updatedAt >= book.lastReadAt
    val rawChapterIndex = if (useProgress) progress!!.chapterIndex else book.lastReadChapter
    val totalProgress = if (useProgress) progress!!.totalProgress else book.readProgress
    val rawPosition = if (useProgress) progress!!.chapterPosition else book.lastReadPosition
    val chapterIndex = rawChapterIndex.coerceIn(0, chapterCount - 1)
    val chapterChangedByClamp = chapterIndex != rawChapterIndex

    val chapterProgress = if (chapterChangedByClamp) {
        0
    } else {
        val chapterFloat = totalProgress.coerceIn(0f, 1f) * chapterCount
        ((chapterFloat - chapterIndex) * 100f).toInt().coerceIn(0, 100)
    }

    return ReaderResumeCursor(
        chapterIndex = chapterIndex,
        // 章节索引失效时，旧章字符坐标没有任何可移植语义，不能带到新章节里。
        chapterPosition = if (chapterChangedByClamp) 0 else rawPosition.coerceAtLeast(0),
        chapterProgress = chapterProgress,
        source = if (useProgress) {
            ReaderResumeCursor.Source.READ_PROGRESS
        } else {
            ReaderResumeCursor.Source.BOOK
        },
    )
}
