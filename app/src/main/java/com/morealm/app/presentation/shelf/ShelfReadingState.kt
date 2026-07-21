package com.morealm.app.presentation.shelf

import com.morealm.app.domain.entity.Book

/** 书架筛选使用的互斥阅读状态。 */
internal enum class ShelfReadingState {
    WANTED,
    READING,
    FINISHED,
}

/**
 * 阅读器只在整本进度真正到达 1 时视为读完。
 * 不能使用 99.5% 一类近似阈值：章节多的书刚进入最后一章时就可能达到该比例。
 */
internal fun Book.shelfReadingState(): ShelfReadingState = when {
    readProgress.isFinite() && readProgress >= 1f -> ShelfReadingState.FINISHED
    lastReadAt > 0L || readProgress > 0f -> ShelfReadingState.READING
    else -> ShelfReadingState.WANTED
}

/**
 * 文件夹必须归入唯一状态：在读优先；全部完成才算已读；其余非空文件夹归为想读。
 * 空文件夹返回 null，因此只会出现在“全部”中。
 */
internal fun List<Book>.aggregateShelfReadingState(): ShelfReadingState? {
    if (isEmpty()) return null

    var allFinished = true
    for (book in this) {
        when (book.shelfReadingState()) {
            ShelfReadingState.READING -> return ShelfReadingState.READING
            ShelfReadingState.WANTED -> allFinished = false
            ShelfReadingState.FINISHED -> Unit
        }
    }
    return if (allFinished) ShelfReadingState.FINISHED else ShelfReadingState.WANTED
}

internal fun ShelfReadingState?.matchesShelfFilter(filter: String): Boolean = when (filter) {
    "reading" -> this == ShelfReadingState.READING
    "wanted" -> this == ShelfReadingState.WANTED
    "finished" -> this == ShelfReadingState.FINISHED
    else -> true
}
