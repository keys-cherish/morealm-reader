package com.morealm.app.presentation.reader

import com.morealm.app.domain.entity.Book
import com.morealm.app.core.log.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages chapter navigation (next/prev), linked book navigation, and scroll-edge events.
 * Extracted from ReaderViewModel.
 */
class ReaderNavigationController(
    private val chapter: ReaderChapterController,
    private val progress: ReaderProgressController,
) {
    // ── State ──
    val _navigateDirection = MutableStateFlow(0)
    val navigateDirection: StateFlow<Int> = _navigateDirection.asStateFlow()

    val _linkedBooks = MutableStateFlow<List<Book>>(emptyList())
    val linkedBooks: StateFlow<List<Book>> = _linkedBooks.asStateFlow()

    private val _nextBookPrompt = MutableStateFlow<Book?>(null)
    val nextBookPrompt: StateFlow<Book?> = _nextBookPrompt.asStateFlow()

    private var navigateToBookCallback: ((String) -> Unit)? = null

    fun setNavigateToBookCallback(callback: (String) -> Unit) {
        navigateToBookCallback = callback
    }

    fun dismissNextBookPrompt() { _nextBookPrompt.value = null }

    // ── Navigation ──

    fun nextChapter() {
        val nextIdx = chapter.currentChapterIndex.value + 1
        AppLog.debug("Nav", "nextChapter | from=${chapter.currentChapterIndex.value} | to=$nextIdx | total=${chapter.chapters.value.size}")
        if (nextIdx < chapter.chapters.value.size) {
            _navigateDirection.value = 1
            chapter.loadChapter(nextIdx, restoreProgress = 0)
        } else {
            val linked = _linkedBooks.value
            if (linked.isNotEmpty()) {
                val nextBook = linked.first()
                val callback = navigateToBookCallback
                if (callback != null) {
                    AppLog.info("Nav", "Auto-advancing to next linked book: ${nextBook.title}")
                    callback(nextBook.id)
                } else {
                    _nextBookPrompt.value = nextBook
                }
            }
        }
    }

    fun prevChapter() {
        val prevIdx = chapter.currentChapterIndex.value - 1
        AppLog.debug("Nav", "prevChapter | from=${chapter.currentChapterIndex.value} | to=$prevIdx")
        if (prevIdx >= 0) {
            _navigateDirection.value = -1
            chapter.loadChapter(prevIdx, restoreProgress = 100)
        }
    }

    fun openNextLinkedBook() {
        _nextBookPrompt.value?.let { book ->
            val linked = _linkedBooks.value
            val callback = navigateToBookCallback
            if (linked.any { it.id == book.id } && callback != null) {
                _nextBookPrompt.value = null
                AppLog.info("Nav", "Opening linked book: ${book.title}")
                callback(book.id)
            }
        }
    }

    fun onScrollReachedBottom() {
        if (chapter.currentChapterIndex.value < chapter.chapters.value.lastIndex) {
            AppLog.debug(
                "Chapter",
                "Scroll reached temporary chapter bottom at ${chapter.currentChapterIndex.value}; " +
                    "chapter boundary must be committed by ReaderPageFactory",
            )
            chapter.onScrollNearBottom()
            return
        }
        val scrollProg = progress._scrollProgress.value
        if (scrollProg < 98) return

        val linked = _linkedBooks.value
        if (linked.isNotEmpty()) {
            _nextBookPrompt.value = linked.first()
        }
    }
}
