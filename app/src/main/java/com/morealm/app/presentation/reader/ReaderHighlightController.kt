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
        /**
         * 高亮种类：0=背景高亮（默认）/ 1=字体强调色。
         * 渲染层据此决定画 bgFill 还是替换前景色，详见 [Highlight.KIND_TEXT_COLOR]。
         */
        kind: Int = Highlight.KIND_BACKGROUND,
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
            kind = kind,
        )
        scope.launch(Dispatchers.IO) {
            highlightRepo.insert(highlight)
            AppLog.info("Highlight", "added id=${highlight.id} ch=$chapterIndex range=$startChapterPos..$endChapterPos kind=$kind len=${highlight.content.length}")
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

    /**
     * 橡皮擦 — 删除当前章节里所有与 `[startChapterPos, endChapterPos)` 有交集的高亮。
     *
     * 「有交集」定义：existing.start < endChapterPos && existing.end > startChapterPos。
     * 选 chapter-pos 作为坐标系是为了和 [add] 一致——正文重排不影响。
     *
     * 注意：这是覆盖删除，不做边界裁剪（用户答的是"调色板加橡皮按钮，覆盖删除"
     * 这一选项；要"切割"语义另外开门）。即使用户只选了高亮中间一小段，整条
     * 高亮也会被删除。
     *
     * 失败容忍：getForChapterSync / deleteById 都各自 try 包；某一条删除失败不
     * 影响其他条 —— 万一 DB 临时故障也能尽量擦干净，剩下的下次手动点删除。
     */
    fun eraseInRange(chapterIndex: Int, startChapterPos: Int, endChapterPos: Int) {
        if (startChapterPos >= endChapterPos) {
            AppLog.warn("Highlight",
                "eraseInRange() rejected: empty range $startChapterPos..$endChapterPos")
            return
        }
        scope.launch(Dispatchers.IO) {
            val candidates = runCatching {
                highlightRepo.getForChapterSync(bookId, chapterIndex)
            }.getOrElse {
                AppLog.warn("Highlight",
                    "eraseInRange query failed ch=$chapterIndex range=$startChapterPos..$endChapterPos: ${it.message}")
                return@launch
            }
            val overlapping = candidates.filter {
                it.startChapterPos < endChapterPos && it.endChapterPos > startChapterPos
            }
            if (overlapping.isEmpty()) {
                AppLog.info("Highlight",
                    "eraseInRange ch=$chapterIndex range=$startChapterPos..$endChapterPos no-op (0 overlap of ${candidates.size})")
                return@launch
            }
            overlapping.forEach { h ->
                runCatching { highlightRepo.deleteById(h.id) }
                    .onFailure {
                        AppLog.warn("Highlight",
                            "eraseInRange deleteById failed id=${h.id}: ${it.message}")
                    }
            }
            AppLog.info("Highlight",
                "eraseInRange ch=$chapterIndex range=$startChapterPos..$endChapterPos deleted=${overlapping.size}")
        }
    }
}
