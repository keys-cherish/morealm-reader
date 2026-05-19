package com.morealm.app.ui.reader.renderer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.morealm.app.domain.render.TextChapter

/**
 * Compose/MVVM counterpart of Legado's page.api.DataSource.
 *
 * ViewModel still owns book/chapter loading, while the renderer reads all paging
 * inputs through this interface so PageFactory is the single page-state entry.
 */
internal interface ReaderDataSource {
    val pageIndex: Int
    val currentChapter: TextChapter?
    val nextChapter: TextChapter?
    val prevChapter: TextChapter?
    val isScroll: Boolean

    fun hasNextChapter(): Boolean
    fun hasPrevChapter(): Boolean
    fun upContent(relativePosition: Int = 0, resetPageOffset: Boolean = true)
}

/**
 * 旧的一次性快照实现 —— 构造时把所有字段冻结。
 * 仅留作单元测试或一次性 snapshot 场景（V1 翻页 remember(12 key) 重建即用此）。
 * 跨章闪烁根治 (2026-05-19 阶段 B) 后 CanvasRenderer 改用 [MutableReaderDataSource]。
 */
internal class SnapshotReaderDataSource(
    override val pageIndex: Int,
    override val currentChapter: TextChapter?,
    override val nextChapter: TextChapter?,
    override val prevChapter: TextChapter?,
    override val isScroll: Boolean,
    private val hasNextChapterValue: Boolean = nextChapter != null,
    private val hasPrevChapterValue: Boolean = prevChapter != null,
    private val onUpContent: (relativePosition: Int, resetPageOffset: Boolean) -> Unit = { _, _ -> },
) : ReaderDataSource {
    override fun hasNextChapter(): Boolean = hasNextChapterValue

    override fun hasPrevChapter(): Boolean = hasPrevChapterValue

    override fun upContent(relativePosition: Int, resetPageOffset: Boolean) {
        onUpContent(relativePosition, resetPageOffset)
    }
}

/**
 * 可变 [ReaderDataSource] 实现 —— 字段全 mutableState backing，**永驻**。
 *
 * 用于跨章闪烁根治 (阶段 B)：
 * - CanvasRenderer 用 `remember { MutableReaderDataSource() }` 创建一次实例，
 *   reader 整生命周期不重建（pageFactory 同理）；
 * - 跨章 commit 走 [setAll]：4 行同栈帧赋值（仿 Legado ReadBook.moveToNextChapter
 *   prevTextChapter / curTextChapter / nextTextChapter 指针轮换模型），
 *   Compose snapshot 系统在 commit 帧合并 → observer 看到原子切换；
 * - ReaderPageFactory 通过 getter 透传字段，跨章只重 measure，不重建 factory + 不换
 *   CanvasRecorder = 真正消除"跨章那一帧顿"。
 *
 * @see com.morealm.app.ui.reader.renderer.ReaderPageFactory
 */
internal class MutableReaderDataSource(
    initialPageIndex: Int = 0,
    initialCurrentChapter: TextChapter? = null,
    initialNextChapter: TextChapter? = null,
    initialPrevChapter: TextChapter? = null,
    initialIsScroll: Boolean = false,
    initialHasNextChapter: Boolean = false,
    initialHasPrevChapter: Boolean = false,
    private val onUpContent: (relativePosition: Int, resetPageOffset: Boolean) -> Unit = { _, _ -> },
) : ReaderDataSource {
    private val pageIndexState = mutableIntStateOf(initialPageIndex)
    private val currentChapterState = mutableStateOf(initialCurrentChapter)
    private val nextChapterState = mutableStateOf(initialNextChapter)
    private val prevChapterState = mutableStateOf(initialPrevChapter)
    private val isScrollState = mutableStateOf(initialIsScroll)
    private val hasNextChapterState = mutableStateOf(initialHasNextChapter)
    private val hasPrevChapterState = mutableStateOf(initialHasPrevChapter)

    override var pageIndex: Int by pageIndexState
    override var currentChapter: TextChapter? by currentChapterState
    override var nextChapter: TextChapter? by nextChapterState
    override var prevChapter: TextChapter? by prevChapterState
    override var isScroll: Boolean by isScrollState
    var hasNextChapterValue: Boolean by hasNextChapterState
    var hasPrevChapterValue: Boolean by hasPrevChapterState

    override fun hasNextChapter(): Boolean = hasNextChapterValue

    override fun hasPrevChapter(): Boolean = hasPrevChapterValue

    override fun upContent(relativePosition: Int, resetPageOffset: Boolean) {
        onUpContent(relativePosition, resetPageOffset)
    }

    /**
     * **原子跨章 commit**（仿 Legado ReadBook.moveToNextChapter 4 行赋值）。
     *
     * Compose [androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot]
     * 把所有写入打包到同一 snapshot：observer（measure / placement / derivedState）
     * 只能看到 commit 后的最终态，不会看到中间 partial state 抖动。
     */
    fun setAll(
        currentChapter: TextChapter?,
        nextChapter: TextChapter?,
        prevChapter: TextChapter?,
        pageIndex: Int,
        hasNextChapter: Boolean = nextChapter != null,
        hasPrevChapter: Boolean = prevChapter != null,
    ) {
        androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot {
            this.currentChapter = currentChapter
            this.nextChapter = nextChapter
            this.prevChapter = prevChapter
            this.pageIndex = pageIndex
            this.hasNextChapterValue = hasNextChapter
            this.hasPrevChapterValue = hasPrevChapter
        }
    }
}
