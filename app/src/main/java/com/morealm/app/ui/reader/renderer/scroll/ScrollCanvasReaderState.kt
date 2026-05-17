package com.morealm.app.ui.reader.renderer.scroll

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.morealm.app.domain.render.scroll.ScrollChapterLayout

/**
 * 滚动 Canvas 阅读器持久状态 —— ViewModel 持有，UI Composable 观察。
 *
 * 设计要点（M2 架构核心）：
 *   - **pixelOffset 是 Float 而非 LazyListState 索引** —— 跳章 bug 物理上不可能（无索引/锚定 race）
 *   - **prev/cur/next 是三个独立 ScrollChapterLayout 引用** —— 章节 swap = swap 引用，
 *     永远不会"插段到中间产生 hole"（旧 ChapterWindowSource 的根因）
 *   - **chapterCount + currentChapterIndex** 跟踪全书位置；prev=null/next=null 表示
 *     已到首/末章或仍异步加载中
 *
 * 不可变性：本 class 的 var 字段通过 mutableState 包装，Compose 重组安全。
 * 章节 swap 由 [com.morealm.app.presentation.reader.ReaderViewModel]（M2.5 接入）
 * 触发的协程操作（异步加载新章 → swap 引用）。
 */
@Stable
class ScrollCanvasReaderState(
    initialChapterIndex: Int = 0,
    initialPixelOffset: Float = 0f,
) {
    /** 当前 cur 章节的全书 idx（0-based）。swap 后更新。 */
    var currentChapterIndex: Int by mutableStateOf(initialChapterIndex)

    /** 上一章已排版结果；null = 已到首章或仍异步加载。 */
    var prevChapter: ScrollChapterLayout? by mutableStateOf(null)

    /** 当前章已排版结果；ViewModel 启动后赋值。null 时 UI 显示加载占位。 */
    var currentChapter: ScrollChapterLayout? by mutableStateOf(null)

    /** 下一章已排版结果；null = 已到末章或仍异步加载。 */
    var nextChapter: ScrollChapterLayout? by mutableStateOf(null)

    /**
     * 像素级滚动偏移（相对 cur 章顶，范围 0..cur.totalHeight）。
     *
     * 用 [mutableFloatStateOf] 而非 [mutableStateOf]：避免 Float autoboxing 开销
     * （fling 期间 60-120 fps 高频写）。
     *
     * 关键：UI 层在 `Layout { layout { } }` 闭包内**读 placement-only** 这个 state，
     * 滚动只触发 Placement，不触发 Recomposition / Measure（120fps 丝滑核心）。
     */
    var pixelOffset: Float by mutableFloatStateOf(initialPixelOffset)

    /** 全书章节总数（首屏加载后由 ViewModel 填充）。 */
    var chapterCount: Int by mutableStateOf(0)
}
