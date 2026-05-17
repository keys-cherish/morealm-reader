package com.morealm.app.ui.reader.renderer.scroll

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
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
    /** ChapterPaneCanvas 用的正文 paint —— 必须与排版引擎同一份。 */
    contentPaint: android.text.TextPaint,
    titlePaint: android.text.TextPaint,
    chapterNumPaint: android.text.TextPaint,
    /** 跳转后呼吸高亮（命中 currentChapter 时才画）。 */
    revealHighlight: com.morealm.app.ui.reader.renderer.RevealHighlight? = null,
    /** 搜索高亮命中章 idx；与 currentChapter 不一致时不画 */
    searchHighlightChapterIndex: Int = -1,
    searchHighlightCpRange: IntRange = IntRange.EMPTY,
    searchHighlightArgb: Int = 0x55FFFF00.toInt(),
    /** 选区命中章 idx；与 currentChapter 不一致时不画选区背景 */
    selectionChapterIndex: Int = -1,
    selectionCpRange: IntRange = IntRange.EMPTY,
    selectionArgb: Int = 0x4D5B6CFE.toInt(),
    /** prev/cur/next 章的高亮 spec 投影（已按章过滤）。 */
    prevHighlightSpecs: List<com.morealm.app.domain.render.scroll.ScrollHighlightDrawSpec> = emptyList(),
    curHighlightSpecs: List<com.morealm.app.domain.render.scroll.ScrollHighlightDrawSpec> = emptyList(),
    nextHighlightSpecs: List<com.morealm.app.domain.render.scroll.ScrollHighlightDrawSpec> = emptyList(),
    /** prev/cur/next 章的书签 cp 列表（已按章过滤）。 */
    prevBookmarkCps: List<Int> = emptyList(),
    curBookmarkCps: List<Int> = emptyList(),
    nextBookmarkCps: List<Int> = emptyList(),
    modifier: Modifier = Modifier,
    onChapterShift: (delta: Int) -> Unit = {},
    onProgress: (Float) -> Unit = {},
    onTapCenter: () -> Unit = {},
    /**
     * tap (xInView, yInView) 回调 —— Host 内做 tap-on-highlight 命中判定。
     * 返回 true 表示已消费（如弹了 highlight 菜单），false 表示放过 → 调 onTapCenter。
     */
    onTap: (androidx.compose.ui.geometry.Offset) -> Boolean = { false },
    /** 长按 (xInView, yInView) 触发回调 —— Host 内做选区命中。 */
    onLongPress: (androidx.compose.ui.geometry.Offset) -> Unit = {},
) {
    // ── M2.4 滚动手势接入 ──
    // rememberScrollableState consume delta：跨章 swap 时 applyScrollDelta 内同帧
    // 完成 chapter 引用切换 + pixelOffset reset，全程消费 delta 不留尾。
    // Compose 的 scrollable 自带 fling decay（无需额外做）。
    // rememberUpdatedState 防止 onChapterShift lambda 闭包陈旧 —— 调用方每次 recompose
    // 传入的 lambda 可能不同，但 scrollState 是 remember 的，闭包内必须读 updated 引用。
    val onChapterShiftUpdated by rememberUpdatedState(onChapterShift)
    val scrollState = rememberScrollableState { delta ->
        applyScrollDelta(state, delta, onChapterShiftUpdated)
    }

    // viewportHeightPx 通过 onSizeChanged 维护（容器尺寸变化 / 屏幕旋转时更新）
    // ChapterPaneCanvas 视口剔除 lambda 用这个值算 viewport 在各块内的可见范围。
    var viewportHeightPx by remember { mutableIntStateOf(0) }

    val onTapCenterUpdated by rememberUpdatedState(onTapCenter)
    val onTapUpdated by rememberUpdatedState(onTap)
    val onLongPressUpdated by rememberUpdatedState(onLongPress)
    Layout(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { viewportHeightPx = it.height }
            // 羽化（顶/底 5% DstOut 渐隐）—— 复用旧 LazyScrollRenderer 同款方案：
            // graphicsLayer Offscreen 把内容画到独立 RenderNode（Android 9+ 硬件加速），
            // drawWithContent 阶段做 DstOut 渐变 mask。
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                val fadeHeight = size.height * 0.05f
                drawRect(
                    brush = Brush.verticalGradient(
                        0f to Color.Black,
                        1f to Color.Transparent,
                        startY = 0f,
                        endY = fadeHeight,
                    ),
                    blendMode = BlendMode.DstOut,
                )
                drawRect(
                    brush = Brush.verticalGradient(
                        0f to Color.Transparent,
                        1f to Color.Black,
                        startY = size.height - fadeHeight,
                        endY = size.height,
                    ),
                    blendMode = BlendMode.DstOut,
                )
            }
            .pointerInput(Unit) {
                // detectTapGestures 不消费 down event 直到判定 tap / longPress，
                // 与 .scrollable 共存：tap 先尝试 onTap（高亮命中），未命中再 fall-through 到
                // onTapCenter（切控制栏）；longPress 触发 onLongPress（选区）；
                // move 触发 scrollable 处理 fling。
                detectTapGestures(
                    onTap = { offset ->
                        val consumed = onTapUpdated(offset)
                        if (!consumed) onTapCenterUpdated()
                    },
                    onLongPress = { offset -> onLongPressUpdated(offset) },
                )
            }
            .scrollable(state = scrollState, orientation = Orientation.Vertical),
        content = {
            // 三块子节点固定顺序 prev/cur/next；null 章节 emit 空 Box 占位。
            // viewportRangeProvider lambda 在 draw scope 内调用：享受 draw-only re-execution
            // （pixelOffset 高频变化只触发 ChapterPaneCanvas redraw，不触发 measure / recompose）。
            val prev = state.prevChapter
            val cur = state.currentChapter
            val next = state.nextChapter
            if (prev != null) {
                ChapterPaneCanvas(
                    chapter = prev,
                    contentPaint = contentPaint,
                    titlePaint = titlePaint,
                    chapterNumPaint = chapterNumPaint,
                    highlightSpecs = prevHighlightSpecs,
                    bookmarkCps = prevBookmarkCps,
                    viewportRangeProvider = {
                        // prev 章在 view 中位置 = -offset - prevH；viewport 顶 view y = 0
                        // → viewport 在 prev 章内 y 范围 = [offset + prevH, offset + prevH + viewportH]
                        val offset = state.pixelOffset
                        val top = offset + prev.totalHeight
                        top to (top + viewportHeightPx)
                    },
                )
            } else {
                Box(Modifier)
            }
            if (cur != null) {
                // reveal / search / selection 只在命中当前章时透传给中间块（prev/next 块从来不画这些）
                val revealForCur = revealHighlight?.takeIf { it.chapterIndex == cur.chapterIndex }
                val searchRangeForCur = if (searchHighlightChapterIndex == cur.chapterIndex) {
                    searchHighlightCpRange
                } else IntRange.EMPTY
                val selectionRangeForCur = if (selectionChapterIndex == cur.chapterIndex) {
                    selectionCpRange
                } else IntRange.EMPTY
                ChapterPaneCanvas(
                    chapter = cur,
                    contentPaint = contentPaint,
                    titlePaint = titlePaint,
                    chapterNumPaint = chapterNumPaint,
                    highlightSpecs = curHighlightSpecs,
                    bookmarkCps = curBookmarkCps,
                    revealHighlight = revealForCur,
                    searchHighlightCpRange = searchRangeForCur,
                    searchHighlightArgb = searchHighlightArgb,
                    selectionCpRange = selectionRangeForCur,
                    selectionArgb = selectionArgb,
                    viewportRangeProvider = {
                        val offset = state.pixelOffset
                        offset to (offset + viewportHeightPx)
                    },
                )
            } else {
                Box(Modifier)
            }
            if (next != null) {
                ChapterPaneCanvas(
                    chapter = next,
                    contentPaint = contentPaint,
                    titlePaint = titlePaint,
                    chapterNumPaint = chapterNumPaint,
                    highlightSpecs = nextHighlightSpecs,
                    bookmarkCps = nextBookmarkCps,
                    viewportRangeProvider = {
                        // next 章在 view 中位置 = -offset + curH；viewport 顶 view y = 0
                        // → viewport 在 next 章内 y 范围 = [offset - curH, offset - curH + viewportH]
                        val offset = state.pixelOffset
                        val curH = state.currentChapter?.totalHeight ?: 0f
                        val top = offset - curH
                        top to (top + viewportHeightPx)
                    },
                )
            } else {
                Box(Modifier)
            }
        },
    ) { measurables, constraints ->
        check(measurables.size == 3) { "ScrollCanvasRenderer expects exactly 3 measurables (prev/cur/next)" }
        val viewWidth = constraints.maxWidth
        val viewHeight = constraints.maxHeight

        // 诊断日志（吞字根因排查）：层 Layout 拿到的真实容器 constraints vs
        // 章节排版时使用的 viewWidth（来自 Host 入参，即 screenWidthDp.toPx()）。
        // 若 constraints.maxWidth < chapter.viewWidth → ScrollCanvasReaderHost 接收的
        // viewWidth 比实际容器宽，layout 出来的 column.end 超出可见区被截。
        val curIdx = state.currentChapter?.chapterIndex ?: -1
        val curLayoutVw = state.currentChapter?.viewWidth ?: -1
        com.morealm.app.core.log.AppLog.info(
            "ScrollCanvasRenderer",
            "MEASURE constraints=${viewWidth}x${viewHeight} curChIdx=$curIdx curLayoutViewWidth=$curLayoutVw " +
                "mismatch=${curLayoutVw > 0 && curLayoutVw != viewWidth}",
        )

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
            val offset = state.pixelOffset.toInt()
            // 三块拼接时章间 padding 重叠（避免 prev.paddingBottom + cur.paddingTop 双倍空白）：
            //   - prev 摆放位置 += prev.paddingBottom：让 prev 末 padding 重叠到 cur 章顶之上（不可见）
            //   - next 摆放位置 -= cur.paddingBottom：让 cur 末 padding 重叠到 next 章首 padding 内
            //     结果：相邻章衔接处只显示一次 padding（cur 的 padding 那一次）。
            val prevPadBot = state.prevChapter?.paddingBottom ?: 0
            val curPadBot = state.currentChapter?.paddingBottom ?: 0
            curPlaceable.placeRelative(0, -offset)
            prevPlaceable.placeRelative(0, -offset - prevH + prevPadBot)
            nextPlaceable.placeRelative(0, -offset + curH - curPadBot)
        }
    }
}
