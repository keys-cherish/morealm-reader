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
        val bookmark = Bookmark(
            id = "${bookId}_bm_${System.currentTimeMillis()}",
            bookId = bookId,
            chapterIndex = chapterIdx,
            chapterTitle = chapterObj.title,
            content = snippet,
            scrollProgress = progress._scrollProgress.value,
        )
        scope.launch(Dispatchers.IO) {
            bookmarkRepo.insert(bookmark)
        }
    }

    fun deleteBookmark(id: String) {
        scope.launch(Dispatchers.IO) { bookmarkRepo.deleteById(id) }
    }

    fun jumpToBookmark(bookmark: Bookmark) {
        chapter.loadChapter(bookmark.chapterIndex)
    }
}
