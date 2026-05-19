package com.morealm.app.ui.reader.renderer.scroll

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.morealm.app.domain.render.scroll.ScrollChapterDataSource
import com.morealm.app.domain.render.scroll.ScrollChapterLayout

/**
 * 滚动 Canvas 阅读器持久状态 —— Host 持有，UI Composable 观察。
 *
 * ── page+全 重构后的设计要点 ──
 *
 * 1. **实现 [ScrollChapterDataSource]**：自身就是 `ScrollPageFactory` 的数据源；
 *    Factory 注入 `this` 即可。
 * 2. **currentChapterIndex 单向真值**（Phase 5 private set）：仅由 [swapToNext] /
 *    [swapToPrev] / [setExternalChapterIndex] 修改 —— 根治旧 V2 prop ↔ state 双向 race。
 * 3. **pageOffset 替代旧 pixelOffset**（Phase 6 完成）：page 内偏移
 *    `[0f, curPage.height]`，所有调用方已迁移；chapter-relative Y 由
 *    pageFactory.pageIndex + pageOffset 累加算出。
 * 4. **swap 方法暴露给 Factory 的 chapterShiftCallback 调用**：保证 Factory
 *    内部不直接写 state 字段（解耦）。
 *
 * 不可变性：所有 var 字段通过 mutableState 包装，Compose 重组安全。
 */
@Stable
class ScrollCanvasReaderState(
    initialChapterIndex: Int = 0,
    initialPageOffset: Float = 0f,
) : ScrollChapterDataSource {

    /**
     * 当前 cur 章的全书 idx（0-based）—— **单向真值**（Phase 5 收紧 setter）。
     *
     * 仅由本类 [swapToNext] / [swapToPrev] / [setExternalChapterIndex] 修改。
     * 外部 Host 通过 prop 传入的初始值仅在构造时 / [setExternalChapterIndex] 内
     * 被消化，prop ↔ state 双向 race 已根治。
     */
    var currentChapterIndex: Int by mutableStateOf(initialChapterIndex)
        private set

    override var prevChapter: ScrollChapterLayout? by mutableStateOf(null)

    override var currentChapter: ScrollChapterLayout? by mutableStateOf(null)

    override var nextChapter: ScrollChapterLayout? by mutableStateOf(null)

    /** 全书章节总数（Host 启动时由 ViewModel 填充）。 */
    var chapterCount: Int by mutableStateOf(0)

    override fun hasPrevChapter(): Boolean = currentChapterIndex > 0

    override fun hasNextChapter(): Boolean = currentChapterIndex < chapterCount - 1

    /**
     * **page-level 偏移**（Phase 3 起 page-level 滚动唯一真值，Phase 6 完成后旧 pixelOffset
     * 字段已删除）—— 相对当前 `ScrollPageFactory.curPage` 顶的像素偏移。
     *
     * 范围 `[0f, curPage.height]`（正值约定，与 V2 旧 pixelOffset 符号一致；
     * 与 Legado `pageOffset` 负值约定相反）：
     * - `0f` = curPage 顶贴视口顶（page 完全可见且贴顶）
     * - `curPage.height` = 走完一整 page（即下一 page 顶贴视口顶，准备 swap 到下一 page）
     *
     * 越界 → 由 [applyPageScrollDelta] 触发 factory.moveToNext/Prev + offset 回归范围内。
     *
     * 用 [mutableFloatStateOf]：避免 fling 60-120fps 高频写 boxing。Layout placement-only
     * read：滚动只触发 Placement，不触发 Recomposition / Measure。
     */
    var pageOffset: Float by mutableFloatStateOf(initialPageOffset)

    /**
     * 跨章向后 swap —— [com.morealm.app.domain.render.scroll.ScrollPageFactory]
     * chapterShiftCallback(+1) 时调用。
     *
     * 时序契约：Factory 已先把 pageIndex 重设为 0（新章 page 0），再调本方法；
     * 本方法 swap 引用 + currentChapterIndex 自增。pixelOffset / pageOffset
     * 由 scroll handler 在 swap 完成后独立设置（page 顶贴视口）。
     */
    fun swapToNext() {
        // 4 连写包在 Compose 事务里 —— observer 只能在 commit 时看到最终态，
        // 避免 partial state 被 LaunchedEffect / derivedStateOf 拍到。
        // 详 memory `project_v2_architecture_failure_mode.md` 第 4 方质疑发现：
        // mutableState 每次写都 schedule dispatch，4 写入暴露 4 race window；
        // Legado View 体系靠同栈帧 + postInvalidate 帧末合并天然原子，
        // Compose 必须用 Snapshot.withMutableSnapshot 拿回同等原子性。
        androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot {
            prevChapter = currentChapter
            currentChapter = nextChapter
            nextChapter = null
            currentChapterIndex += 1
        }
    }

    /**
     * 与 [swapToNext] 对偶 —— chapterShiftCallback(-1) 时调用。
     */
    fun swapToPrev() {
        androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot {
            nextChapter = currentChapter
            currentChapter = prevChapter
            prevChapter = null
            currentChapterIndex -= 1
        }
    }

    /**
     * 外部跳章接口 —— 书签 / 目录 / Slider 拖动 / 续读 / 搜索定位 用。
     *
     * 与 page swap 路径不同：本方法整体重设 state（prev/cur/next 全 null），
     * Host 在 LaunchedEffect 监听 currentChapterIndex 变化重新加载 3 章 + 排版。
     * pixelOffset 也重设为 0（章首页起点）；如需 cp/progress 级精确定位由 Host 在
     * layout 加载完后另设。
     */
    fun setExternalChapterIndex(idx: Int) {
        androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot {
            currentChapterIndex = idx
            pageOffset = 0f
            prevChapter = null
            currentChapter = null
            nextChapter = null
        }
    }
}
