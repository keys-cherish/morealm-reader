package com.morealm.app.presentation.reader

import com.morealm.app.core.log.AppLog
import com.morealm.app.domain.entity.Highlight
import com.morealm.app.domain.repository.HighlightRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * 高亮控制器 — 当前书的高亮状态 + 增删入口。
 *
 * 范式同 [ReaderBookmarkController]：StateFlow 暴露给 UI，IO 操作在
 * Dispatchers.IO 上发起。`forCurrentChapter` 跟随 chapter 控制器的
 * currentChapterIndex 自动重订阅，进入新章时切到对应高亮列表。
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ReaderHighlightController(
    private val bookId: String,
    private val highlightRepo: HighlightRepository,
    private val scope: CoroutineScope,
    private val chapter: ReaderChapterController,
) {
    // ── State ──

    /**
     * 跟随 [ReaderChapterController.currentChapterIndex] 切换；只暴露
     * 当前可见章节的高亮，让渲染器只关心需要画的那批。
     */
    val forCurrentChapter: StateFlow<List<Highlight>> = chapter.currentChapterIndex
        .flatMapLatest { idx -> highlightRepo.getForChapter(bookId, idx) }
        .stateIn(scope, SharingStarted.Lazily, emptyList())

    // ── Public API ──

    /**
     * 新增一条高亮。`bookTitle` / `chapterTitle` 在保存时落盘冗余，方便
     * 后续删书或换源后高亮元数据仍可用。
     *
     * 调用方提供：起止章节字符 offset、内容摘要、ARGB 颜色。
     */
    fun add(
        chapterIndex: Int,
        startChapterPos: Int,
        endChapterPos: Int,
        content: String,
        colorArgb: Int,
        note: String = "",
    ) {
        if (startChapterPos >= endChapterPos) {
            AppLog.warn("Highlight", "add() rejected: empty range $startChapterPos..$endChapterPos")
            return
        }
        val chapterObj = chapter.chapters.value.getOrNull(chapterIndex)
        val bookObj = chapter.book.value
        val highlight = Highlight(
            id = UUID.randomUUID().toString(),
            bookId = bookId,
            chapterIndex = chapterIndex,
            chapterTitle = chapterObj?.title ?: "",
            bookTitle = bookObj?.title ?: "",
            startChapterPos = startChapterPos,
            endChapterPos = endChapterPos,
            content = content.take(2000),  // hard cap to keep DB rows small
            colorArgb = colorArgb,
            note = note,
        )
        scope.launch(Dispatchers.IO) {
            highlightRepo.insert(highlight)
            AppLog.info("Highlight", "added id=${highlight.id} ch=$chapterIndex range=$startChapterPos..$endChapterPos len=${highlight.content.length}")
        }
    }

    /** Update color or note on an existing highlight. */
    fun update(highlight: Highlight) {
        scope.launch(Dispatchers.IO) { highlightRepo.insert(highlight) }
    }

    fun delete(id: String) {
        scope.launch(Dispatchers.IO) {
            highlightRepo.deleteById(id)
            AppLog.info("Highlight", "deleted id=$id")
        }
    }
}
