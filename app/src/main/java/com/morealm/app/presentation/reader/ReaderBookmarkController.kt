package com.morealm.app.presentation.reader

import com.morealm.app.domain.entity.Bookmark
import com.morealm.app.domain.repository.BookmarkRepository
import com.morealm.app.core.text.stripHtml
import com.morealm.app.core.log.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Manages bookmarks for the current book.
 * Extracted from ReaderViewModel.
 */
class ReaderBookmarkController(
    private val bookId: String,
    private val bookmarkRepo: BookmarkRepository,
    private val scope: CoroutineScope,
    private val chapter: ReaderChapterController,
    private val progress: ReaderProgressController,
) {
    // ── State ──
    val bookmarks: StateFlow<List<Bookmark>> = bookmarkRepo.getBookmarks(bookId)
        .stateIn(scope, SharingStarted.Lazily, emptyList())

    // ── Bookmark Functions ──

    fun addBookmark() {
        val chapterIdx = chapter.currentChapterIndex.value
        val chapterObj = chapter.chapters.value.getOrNull(chapterIdx) ?: return
        val content = chapter.chapterContent.value
        val snippet = content.stripHtml().take(80).trim()
        // 章内字符偏移：仿真/滑动/覆盖翻页下的精确定位字段（对齐 Legado.chapterPos）；
        // _scrollProgress（0-100 百分比）保留作为滚动模式兜底。
        val chapterPos = progress._visiblePage.value.chapterPosition
        val scrollPct = progress._scrollProgress.value
        val bookmark = Bookmark(
            id = "${bookId}_bm_${System.currentTimeMillis()}",
            bookId = bookId,
            chapterIndex = chapterIdx,
            chapterTitle = chapterObj.title,
            content = snippet,
            scrollProgress = scrollPct,
            chapterPos = chapterPos,
        )
        AppLog.info(
            "BookmarkDebug",
            "addBookmark id=${bookmark.id} chapterIdx=$chapterIdx" +
                " chapterPos=$chapterPos scrollProgress=$scrollPct" +
                " title='${chapterObj.title.take(20)}' snippetLen=${snippet.length}",
        )
        scope.launch(Dispatchers.IO) {
            bookmarkRepo.insert(bookmark)
        }
    }

    fun deleteBookmark(id: String) {
        scope.launch(Dispatchers.IO) { bookmarkRepo.deleteById(id) }
    }

    fun jumpToBookmark(bookmark: Bookmark) {
        AppLog.info(
            "BookmarkDebug",
            "jumpToBookmark id=${bookmark.id} chapterIdx=${bookmark.chapterIndex}" +
                " chapterPos=${bookmark.chapterPos} scrollProgress=${bookmark.scrollProgress}",
        )
        chapter.loadChapter(
            bookmark.chapterIndex,
            restoreProgress = bookmark.scrollProgress,
            restoreChapterPosition = bookmark.chapterPos,
        )
    }
}
