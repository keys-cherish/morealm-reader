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
        // 章内字符偏移：仿真/滑动/覆盖翻页下的精确定位字段（对齐参照实现.chapterPos）；
        // _scrollProgress（0-100 百分比）保留作为滚动模式兜底。
        val visible = progress._visiblePage.value
        val chapterPos = visible.chapterPosition
        val scrollPct = progress._scrollProgress.value
        // 锚点 v2：渲染 Host 上报过位置快照（书签位置处的正文）就用它当 content，
        // 并写 chapterId 作「positional content」标记 —— 恢复端可据此自校验/重定位。
        // 快照缺失（legacy 渲染路径）退回旧行为：章首文本 + chapterId 空（标记
        // 不可重定位，防止拿章首文本把书签挪到章首）。
        val positionalSnippet = visible.anchorSnippet
            .takeIf { visible.chapterIndex == chapterIdx && it.isNotBlank() }
        val content = chapter.chapterContent.value
        val snippet = positionalSnippet ?: content.stripHtml().take(80).trim()
        val bookmark = Bookmark(
            id = "${bookId}_bm_${System.currentTimeMillis()}",
            bookId = bookId,
            chapterIndex = chapterIdx,
            chapterTitle = chapterObj.title,
            content = snippet,
            scrollProgress = scrollPct,
            chapterPos = chapterPos,
            chapterId = if (positionalSnippet != null) chapterObj.url else "",
        )
        AppLog.info(
            "BookmarkDebug",
            "addBookmark id=${bookmark.id} chapterIdx=$chapterIdx" +
                " chapterPos=$chapterPos scrollProgress=$scrollPct" +
                " positional=${positionalSnippet != null}" +
                " title='${chapterObj.title.take(20)}' snippetLen=${snippet.length}",
        )
        scope.launch(Dispatchers.IO) {
            bookmarkRepo.insert(bookmark)
        }
    }

    fun deleteBookmark(id: String) {
        scope.launch(Dispatchers.IO) { bookmarkRepo.deleteById(id) }
    }

    /**
     * 锚点自愈（锚点 v2）：章 layout 就绪后，对该章「positional」书签（chapterId
     * 非空，content = 书签位置处正文快照）做内容自校验，失配就快照重定位并写回。
     * 旧书签（chapterId 空，content 是章首文本）一律不动 —— 拿章首文本重定位
     * 等于把书签挪到章首，是数据损坏。
     */
    fun relocateChapterAnchors(
        chapterIndex: Int,
        textIndex: com.morealm.app.domain.render.layout.AnchorTextIndex,
    ) {
        scope.launch(Dispatchers.IO) {
            var moved = 0
            bookmarks.value
                .filter { it.chapterIndex == chapterIndex && it.chapterId.isNotEmpty() }
                .forEach { bm ->
                    if (bm.content.length < com.morealm.app.domain.render.layout.MIN_SNIPPET_CHARS) return@forEach
                    if (textIndex.verifyAt(bm.chapterPos, bm.content)) return@forEach
                    val hit = textIndex.findNearestCp(bm.content, bm.chapterPos) ?: return@forEach
                    if (hit.startCp == bm.chapterPos) return@forEach
                    runCatching {
                        bookmarkRepo.insert(bm.copy(chapterPos = hit.startCp))
                        moved++
                    }
                }
            if (moved > 0) {
                AppLog.info("BookmarkDebug", "relocateChapterAnchors ch=$chapterIndex moved=$moved")
            }
        }
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
            // 锚点 v2 书签（chapterId 非空 = content 是书签位置处的正文快照）：
            // 透传给渲染 Host 做内容自校验/快照重定位。旧书签 content 是章首文本，
            // 绝不能当快照传（会把落点拽到章首）。
            restoreAnchorSnippet = if (bookmark.chapterId.isNotEmpty()) bookmark.content else "",
        )
    }
}
