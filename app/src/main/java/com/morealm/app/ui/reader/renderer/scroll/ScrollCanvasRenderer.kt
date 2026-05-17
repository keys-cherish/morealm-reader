package com.morealm.app.ui.reader.renderer.scroll

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints

/**
 * 滚动 Canvas 阅读器 UI 入口 —— 三块面板（prev/cur/next）+ pixelOffset 像素累加滚动。
 *
 * ── 与旧 [com.morealm.app.ui.reader.renderer.LazyScrollRenderer] 的根本架构差异 ──
 *
 * |             | 旧 LazyScrollRenderer                  | 本 ScrollCanvasRenderer            |
 * | ---         | ---                                    | ---                                |
 * | 滚动单元    | LazyColumn item (ScrollParagraph 段级) | 自定义 Layout 三块面板（章级）     |
 * | 章节管理    | ChapterWindowSource 动态窗口（hole 风险）| 固定 prev/cur/next 三章常驻        |
 * | 滚动机制    | LazyListState 索引 + scroll offset     | 单 Float pixelOffset state         |
 * | 跨章        | paragraphs 插段 + LazyColumn 重锚 race | swap chapter 引用（无插段无 race） |
 * | 跳章风险    | 高（hole / 锚定失败）                  | 0（物理上无插段）                  |
 *
 * ── M2.2 核心：placement-only state read ──
 *
 * 在 `Layout { layout {} }` 闭包内读 [ScrollCanvasReaderState.pixelOffset] 享受
 * Compose deferred-read 机制：pixelOffset 变化只触发 Placement，**不触发 Recomposition
 * 也不触发 Measure**（滚动 120fps 丝滑的核心）。
 *
 * ── 接入策略 ──
 *
 * M2 期间本 Composable 不接入 ReaderScreen；M6 联调时挂在 feature flag 后面，
 * 旧 LazyScrollRenderer 默认兜底。功能矩阵全绿后切默认 + 删旧路径。
 *
 * @param state 持久状态（prev/cur/next + pixelOffset + chapterIndex 等），由 ViewModel 持有
 * @param modifier 外层布局修饰符
 * @param onChapterShift swap 后通知调用方更新 currentChapterIndex（delta=±1，M2.5 接入）
 * @param onProgress 章内进度变化通知（0.0..1.0，M2.5 接入）
 */
@Composable
fun ScrollCanvasRenderer(
    state: ScrollCanvasReaderState,
    modifier: Modifier = Modifier,
    onChapterShift: (delta: Int) -> Unit = {},
    onProgress: (Float) -> Unit = {},
) {
    Layout(
        modifier = modifier.fillMaxSize(),
        content = {
            // 三块子节点固定顺序 prev/cur/next；null 章节 emit 空 Box 占位（保持
            // measurables.size == 3，简化 measure 逻辑）。
            if (state.prevChapter != null) {
                ChapterPaneCanvas(
                    chapter = state.prevChapter!!,
                    viewportTop = 0f,   // M2.3 接入 viewport 计算
                    viewportBottom = 0f,
                )
            } else {
                Box(Modifier)
            }
            if (state.currentChapter != null) {
                ChapterPaneCanvas(
                    chapter = state.currentChapter!!,
                    viewportTop = 0f,
                    viewportBottom = 0f,
                )
            } else {
                Box(Modifier)
            }
            if (state.nextChapter != null) {
                ChapterPaneCanvas(
                    chapter = state.nextChapter!!,
                    viewportTop = 0f,
                    viewportBottom = 0f,
                )
            } else {
                Box(Modifier)
            }
        },
    ) { measurables, constraints ->
        check(measurables.size == 3) { "ScrollCanvasRenderer expects exactly 3 measurables (prev/cur/next)" }
        val viewWidth = constraints.maxWidth
        val viewHeight = constraints.maxHeight

        // measure 各块 height = chapter.totalHeight；null 章节高度 = 0 占位
        val prevH = state.prevChapter?.totalHeight?.toInt() ?: 0
        val curH = state.currentChapter?.totalHeight?.toInt() ?: 0
        val nextH = state.nextChapter?.totalHeight?.toInt() ?: 0

        val prevPlaceable = measurables[0].measure(
            Constraints.fixed(width = viewWidth, height = prevH),
        )
        val curPlaceable = measurables[1].measure(
            Constraints.fixed(width = viewWidth, height = curH),
        )
        val nextPlaceable = measurables[2].measure(
            Constraints.fixed(width = viewWidth, height = nextH),
        )

        layout(viewWidth, viewHeight) {
            // ─── 关键：placement-only state read ───
            // 在 layout {} 闭包内读 state.pixelOffset，Compose deferred-read 机制保证：
            //   - pixelOffset 变化 → 仅触发本 placement block 重执行
            //   - 不触发外层 Recomposition / Measure（避免 ChapterPaneCanvas 整章重绘）
            // 这是滚动 120fps 丝滑的核心；测量 / 重组开销与 pixelOffset 解耦。
            val offset = state.pixelOffset.toInt()
            // 三块摆放：cur 顶在 -offset；prev 紧贴 cur 上方；next 紧贴 cur 下方
            curPlaceable.placeRelative(0, -offset)
            prevPlaceable.placeRelative(0, -offset - prevH)
            nextPlaceable.placeRelative(0, -offset + curH)
        }
    }
}
