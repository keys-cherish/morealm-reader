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
        // SCROLL 模式 bug：addBookmark 时 progress.visiblePage.chapterPosition 常停在 0
        // （首段顶仍可见时，"屏幕顶部 char index" 一直是 0），书签里只剩 scrollProgress
        // 这一条 % 信息。restoreProgress 在 SCROLL 模式跳过 page seek，LazyScroll 又只
        // 看 chapterPos，不读 scrollProgress —— 结果跳回总落在章首，丢失 N% 精度。
        //
        // 临时桥接：chapterPos=0 但 scrollProgress>0 时，把 % 折成估算 char 位置（按
        // 当前章内容总长同比例），交给统一的 chapterPos→paragraph 映射。失败时仍
        // 落在最近段首，体感比"始终回章首"好得多。
        //
        // 长期方案应该在 addBookmark 阶段拿到真实 visible-top char index，或在
        // restoreProgress 里把 scrollProgress 透传到 LazyScroll 做 px 级 scrollBy。
        // 那两条改动需要动 ReaderProgressController + LazyScrollSection，先不做。
        val effectiveChapterPos = if (bookmark.chapterPos == 0 && bookmark.scrollProgress > 0) {
            val contentLen = chapter.chapterContent.value.length
            if (contentLen > 0) {
                ((bookmark.scrollProgress.toLong() * contentLen) / 100L)
                    .toInt()
                    .coerceIn(0, contentLen - 1)
            } else bookmark.chapterPos
        } else bookmark.chapterPos
        AppLog.info(
            "BookmarkDebug",
            "jumpToBookmark id=${bookmark.id} chapterIdx=${bookmark.chapterIndex}" +
                " chapterPos=${bookmark.chapterPos}→$effectiveChapterPos" +
                " scrollProgress=${bookmark.scrollProgress}",
        )
        chapter.loadChapter(
            bookmark.chapterIndex,
            restoreProgress = bookmark.scrollProgress,
            restoreChapterPosition = effectiveChapterPos,
        )
    }
}
