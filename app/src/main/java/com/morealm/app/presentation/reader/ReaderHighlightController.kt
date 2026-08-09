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
         * 高亮种类：0=背景高亮（默认）/ 1=字体强调色 / 2=下划线。
         * 渲染层据此决定画 bgFill / 替换前景色 / 还是基线下画 stroke 线。
         */
        kind: Int = Highlight.KIND_BACKGROUND,
        /**
         * 下划线线型 —— 仅当 [kind] == [Highlight.KIND_UNDERLINE] 时被消费。
         * 取值 0..3，对应 [Highlight.UNDERLINE_STYLE_SOLID / DASHED / DOTTED / WAVY]。
         */
        underlineStyle: Int = Highlight.UNDERLINE_STYLE_SOLID,
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
            underlineStyle = underlineStyle,
            // 锚点 v2：章稳定 id。content 本就是选区原文（内容快照），配合它
            // 章文本变化后可自校验/重定位（relocateChapterAnchors）。
            chapterId = chapterObj?.url ?: "",
        )
        scope.launch(Dispatchers.IO) {
            highlightRepo.insert(highlight)
            AppLog.info("Highlight", "added id=${highlight.id} ch=$chapterIndex range=$startChapterPos..$endChapterPos kind=$kind underlineStyle=$underlineStyle len=${highlight.content.length}")
        }
    }

    /**
     * 锚点自愈（锚点 v2）：章 layout 就绪后，对该章所有高亮做内容自校验 ——
     * startChapterPos 处正文与 content 对不上（wire 协议变 / 替换规则变 / 换源）
     * 时用 content 快照就近搜索重定位，并把新区间写回 DB。
     *
     * 幂等：校验通过的条目直接跳过，重定位成功后下次进章即走「通过」分支；
     * 找不到的条目保持原样（宁可错位显示也不删用户数据）。
     * 区间长度保持原 cp 跨度（快照搜索可能用了截短核心串，end 不能取命中串尾）。
     */
    fun relocateChapterAnchors(
        chapterIndex: Int,
        textIndex: com.morealm.app.domain.render.layout.AnchorTextIndex,
    ) {
        scope.launch(Dispatchers.IO) {
            val candidates = runCatching {
                highlightRepo.getForChapterSync(bookId, chapterIndex)
            }.getOrElse { return@launch }
            var moved = 0
            candidates.forEach { h ->
                val snippet = h.content
                if (snippet.length < com.morealm.app.domain.render.layout.MIN_SNIPPET_CHARS) return@forEach
                if (textIndex.verifyAt(h.startChapterPos, snippet)) return@forEach
                val hit = textIndex.findNearestCp(snippet, h.startChapterPos) ?: return@forEach
                val span = (h.endChapterPos - h.startChapterPos).coerceAtLeast(1)
                if (hit.startCp == h.startChapterPos) return@forEach
                runCatching {
                    highlightRepo.insert(
                        h.copy(startChapterPos = hit.startCp, endChapterPos = hit.startCp + span),
                    )
                    moved++
                }
            }
            if (moved > 0) {
                AppLog.info("Highlight", "relocateChapterAnchors ch=$chapterIndex moved=$moved/${candidates.size}")
            }
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
