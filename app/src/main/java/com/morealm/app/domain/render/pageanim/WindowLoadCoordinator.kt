package com.morealm.app.domain.render.pageanim

import com.morealm.app.core.log.AppLog
import com.morealm.app.domain.reader.runtime.ArtifactLoader
import com.morealm.app.domain.reader.runtime.EnsureResult
import com.morealm.app.domain.reader.runtime.ReaderRuntimeAdapter
import com.morealm.app.domain.reader.runtime.ReaderWindowStore
import com.morealm.app.ui.reader.renderer.scroll.ScrollCanvasReaderState
import com.morealm.epub.render.ScrollChapterLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 窗口加载协调器 —— 把 [ReaderWindowStore] 的 ensure/complete/fail 编排为实际的协程
 * 加载，并把完成结果投影进 [ScrollCanvasReaderState]。
 *
 * 并发去重 = store 的 `Loading(requestId)`（仅 Started 才起 Job）。[activeLoads] 只做
 * 失效时取消用的记账，不做 join。仅主线程访问（scope 是主线程 rememberCoroutineScope，
 * loader 内部自切 IO/Default）。
 */
internal class WindowLoadCoordinator(
    private val store: ReaderWindowStore,
    private val state: ScrollCanvasReaderState,
    private val scope: CoroutineScope,
    private val bookId: String,
) {
    /** 每次重组由 SideEffect 刷新：engine 重建 → 新 loader 实例（换代判定靠引用比较）。 */
    var loader: ArtifactLoader? = null
        private set
    var contentVersion: Long = 0L

    /**
     * loader 换代入口（SideEffect 每次重组调用，引用相同为 no-op）。
     *
     * 换代时若有旧 loader 的在飞加载，立刻取消并清 store —— 否则旧 engine 的排版
     * 会白跑到终点线才被 [launchLoad] 的引用比较作废再重派（进阅读器 / 进程死亡
     * 恢复时首帧 engine 与实测尺寸 engine 不同，cur 章整整多排一遍的根因）。
     * 重派不在这里做：load effect 以 engine 为 key，换代后必然重跑并 requestLoad。
     */
    fun updateLoader(newLoader: ArtifactLoader) {
        val old = loader
        loader = newLoader
        if (old != null && old !== newLoader && activeLoads.isNotEmpty()) {
            AppLog.info("PageLevelCore", "loader superseded with ${activeLoads.size} in-flight -> cancel & reload")
            activeLoads.values.forEach { it.cancel() }
            activeLoads.clear()
            store.invalidateAll()
        }
    }

    /** cur 章排版就绪回调（reflow 锚点恢复 / 跨章 reset 在这里做，由 composable 注入）。 */
    var onCurrentReady: (ScrollChapterLayout) -> Unit = {}

    private val activeLoads = mutableMapOf<Long, Job>()

    fun alignCurrent(chapterIndex: Int) {
        val unit = ReaderRuntimeAdapter.unitIdForChapter(chapterIndex)
        if (store.currentId != unit) {
            store.moveTo(
                unit,
                previous = (chapterIndex - 1).takeIf { it >= 0 }
                    ?.let(ReaderRuntimeAdapter::unitIdForChapter),
                next = (chapterIndex + 1).takeIf { it < state.chapterCount }
                    ?.let(ReaderRuntimeAdapter::unitIdForChapter),
            )
        }
    }

    fun requestLoad(chapterIndex: Int) {
        if (chapterIndex < 0) return
        val loader = this.loader ?: return
        val unit = ReaderRuntimeAdapter.unitIdForChapter(chapterIndex)
        // 窗口外的单元不加载（窗口移动后过期的重派请求在此自然消失）
        val window = store.snapshot
        if (unit != window.previous && unit != window.current && unit != window.next) return
        when (val result = store.ensure(unit)) {
            is EnsureResult.Started -> launchLoad(chapterIndex, result.requestId, loader)
            // Loading 但无对应 Job：请求由 session.prepare 等旁路 start、无人加载 → 收养
            is EnsureResult.Loading ->
                if (activeLoads[result.requestId] == null) launchLoad(chapterIndex, result.requestId, loader)
            is EnsureResult.Ready -> result.artifact.sourceLayout?.let { deliver(chapterIndex, it) }
        }
    }

    /** cur 就绪后调用：对齐邻窗 + 预载缺失的 prev/next。 */
    fun ensureNeighbors(chapterIndex: Int) {
        if (chapterIndex != state.currentChapterIndex) return
        val prevIdx = (chapterIndex - 1).takeIf { it >= 0 }
        val nextIdx = (chapterIndex + 1).takeIf { it < state.chapterCount }
        store.setNeighbors(
            previous = prevIdx?.let(ReaderRuntimeAdapter::unitIdForChapter),
            next = nextIdx?.let(ReaderRuntimeAdapter::unitIdForChapter),
        )
        if (prevIdx != null) requestLoad(prevIdx)
        if (nextIdx != null) requestLoad(nextIdx)
    }

    /** engine / contentVersion 换代：取消在飞加载 + 清空 store 条目（投影由调用方清）。 */
    fun invalidateAll() {
        activeLoads.values.forEach { it.cancel() }
        activeLoads.clear()
        store.invalidateAll()
    }

    private fun launchLoad(chapterIndex: Int, requestId: Long, loader: ArtifactLoader) {
        val unit = ReaderRuntimeAdapter.unitIdForChapter(chapterIndex)
        val version = contentVersion
        activeLoads[requestId] = scope.launch {
            val layout = loader.load(chapterIndex)
            activeLoads.remove(requestId)
            if (loader !== this@WindowLoadCoordinator.loader) {
                // engine 已换代：本结果按旧参数排版，作废并用新 loader 重派
                // （requestLoad 对 Failed 条目会重启新请求；窗口已移走则直接 no-op）。
                store.fail(unit, requestId, "engine superseded")
                requestLoad(chapterIndex)
                return@launch
            }
            if (layout == null) {
                store.fail(unit, requestId, "chapter $chapterIndex load failed")
                if (chapterIndex == state.currentChapterIndex) {
                    AppLog.warn("PageLevelCore", "cur NULL idx=$chapterIndex")
                }
            } else {
                val artifact = ReaderRuntimeAdapter.artifactFrom(bookId, layout, version)
                if (store.complete(unit, requestId, artifact)) deliver(chapterIndex, layout)
            }
        }
    }

    private fun deliver(chapterIndex: Int, layout: ScrollChapterLayout) {
        val curIdx = state.currentChapterIndex
        when (chapterIndex) {
            curIdx -> {
                if (state.currentChapter?.chapterIndex != chapterIndex) onCurrentReady(layout)
                ensureNeighbors(curIdx)
            }
            curIdx - 1 -> state.prevChapter = layout
            curIdx + 1 -> state.nextChapter = layout
            // 其余：窗口已移走的过期结果 —— store.complete 已被 requestId 门控拒绝，不会到这
        }
    }
}
