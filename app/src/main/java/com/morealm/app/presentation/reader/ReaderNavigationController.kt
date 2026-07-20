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
    private val shared: ReaderSharedState,
) {
    // ── State（真值持有在 ReaderSharedState，保留原属性名减小 diff）──
    val _navigateDirection = shared._navigateDirection
    val navigateDirection: StateFlow<Int> = _navigateDirection.asStateFlow()

    val _linkedBooks = shared._linkedBooks
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
            shared.commitNavigateDirection(1, "nextChapter button")
            // Phase 2 一致性修复：所有跨章入口（按钮 / 长按按键 / 滚动 commit）统一
            // 优先走同步腾挪。若不走这条路径，老 loadChapter 不腾挪 _prev/_cur/
            // _nextTextChapter 三个真值流，会导致后续滚动 commit 永久 REJECT。
            if (chapter.commitChapterShiftNext()) {
                AppLog.debug("Nav", "nextChapter via sync moveToNextChapter")
                return
            }
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

    /**
     * 跨章 PREV（参考成熟开源阅读器实现的跨章 PREV 指针腾挪）。
     *
     * @param toLast `true`（**默认**）= 跳上一章**末页**（手势 PREV 连续阅读，常见场景）；
     *               `false` = 跳上一章**章头**（按钮 PREV，显式覆盖默认）。
     * 详见 MEMORY.md「阅读器导航语义」段。
     */
    fun prevChapter(toLast: Boolean = true) {
        val prevIdx = chapter.currentChapterIndex.value - 1
        AppLog.debug("Nav", "prevChapter | from=${chapter.currentChapterIndex.value} | to=$prevIdx | toLast=$toLast")
        if (prevIdx >= 0) {
            shared.commitNavigateDirection(-1, "prevChapter button")
            if (chapter.commitChapterShiftPrev(toLast)) {
                AppLog.debug("Nav", "prevChapter via sync moveToPrevChapter")
                return
            }
            // fallback async loadChapter — 末页 restoreProgress=100，章头=0
            chapter.loadChapter(prevIdx, restoreProgress = if (toLast) 100 else 0)
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
