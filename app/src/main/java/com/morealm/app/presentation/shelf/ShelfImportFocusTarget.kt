package com.morealm.app.presentation.shelf

import com.morealm.app.domain.entity.Book

/** 导入完成后需要在书架中显式展示的稳定实体。 */
sealed interface ShelfImportFocusTarget {
    data class BookTarget(val bookId: String) : ShelfImportFocusTarget
    data class FolderTarget(val folderId: String) : ShelfImportFocusTarget
}

/**
 * 根据当前已经投影到 UI 的顺序计算滚动位置。返回 null 表示 Room/Compose 状态尚未
 * 收敛，调用方应保留请求并在下一次数据更新后重试。
 */
internal fun resolveShelfImportFocusIndex(
    target: ShelfImportFocusTarget,
    allBooks: List<Book>,
    visibleFolderIds: List<String>,
    visibleBookIds: List<String>,
    currentFolderId: String?,
): Int? {
    return when (target) {
        is ShelfImportFocusTarget.FolderTarget -> {
            if (currentFolderId != null) null else visibleFolderIds.indexOf(target.folderId).takeIf { it >= 0 }
        }
        is ShelfImportFocusTarget.BookTarget -> {
            val book = allBooks.find { it.id == target.bookId } ?: return null
            if (currentFolderId != book.folderId) return null
            val bookIndex = visibleBookIds.indexOf(target.bookId).takeIf { it >= 0 } ?: return null
            bookIndex + if (currentFolderId == null) visibleFolderIds.size else 0
        }
    }
}
