package com.morealm.app.ui.reader.page.animation

import android.graphics.Bitmap
import android.text.TextPaint
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import com.morealm.app.core.log.AppLog
import com.morealm.app.domain.render.TextPage
import com.morealm.app.ui.reader.renderer.PageInfoOverlaySpec
import com.morealm.app.ui.reader.renderer.ReaderPageDirection

// ──────────────────────────────────────────────────────────────────────────────
// 翻页动画分支总入口 —— 各类型 pager 已按动画类型拆到独立文件，本文件仅做：
//
//   1. [PageAnimType] 枚举定义 + [String.toPageAnimType] 字符串映射
//   2. [SimulationParams] 仿真翻页的入参聚合（由 [com.morealm.app.ui.reader.page.animation.rememberSimulationParams] 构建）
//   3. [AnimatedPageReader] dispatch —— 按 animType 选对应 pager 渲染
//
// 各 pager 实现：
//   - [SlidePager] / [VerticalSlidePager]  → SlidePager.kt
//   - [CoverPager]                          → CoverPager.kt
//   - [SimulationPager]                     → SimulationPager.kt
//   - [ScrollPager]                         → ScrollPager.kt
//
// 抽出动机：本文件曾达 600+ 行（5 个 pager + 各种 simulation 残留 helpers），
// 改任一种动画都得在巨型上下文里翻找。按动画类型拆文件后，每种动画的代码、
// 注释、相关常量都集中在 ~50–280 行之间，单测/调试范围立刻收敛。
// ──────────────────────────────────────────────────────────────────────────────

/**
 * Page animation types supported by the reader.
 */
enum class PageAnimType {
    NONE,           // Instant page change
    SLIDE,          // Both pages slide together horizontally
    SLIDE_VERTICAL, // Both pages slide together vertically (上下翻页)
    COVER,          // Incoming page slides over, outgoing stays
    SIMULATION,     // Page curl effect with bezier curves
    SCROLL,         // Vertical continuous scroll
}

fun String.toPageAnimType(): PageAnimType = when (this.lowercase()) {
    "none" -> PageAnimType.NONE
    "slide" -> PageAnimType.SLIDE
    "slide_vertical", "vertical_slide", "上下翻页" -> PageAnimType.SLIDE_VERTICAL
    "cover" -> PageAnimType.COVER
    "simulation" -> PageAnimType.SIMULATION
    "scroll", "vertical" -> PageAnimType.SCROLL
    else -> PageAnimType.SLIDE
}

/**
 * 仿真翻页所需的额外参数。
 * 由 CanvasRenderer 构建并传入 AnimatedPageReader。
 */
class SimulationParams(
    val pages: List<TextPage>,
    val titlePaint: TextPaint,
    val contentPaint: TextPaint,
    val chapterNumPaint: TextPaint? = null,
    val bgColor: Int,
    val bgBitmap: Bitmap? = null,
    val bgMeanColor: Int = bgColor,
    val pageInfoOverlay: PageInfoOverlaySpec? = null,
    /**
     * 当前章节的用户高亮（kind=0，画底色矩形）。SimulationPager.bitmapProvider 每次
     * 渲染 page bitmap 时按页 chapter range 过滤后传给 [renderPageToBitmap]。
     * 用户保存/删除高亮 → CanvasRenderer 透传新 SimulationParams（remember 入参变化）
     * → SimulationReadView 收到后下一帧重出 bitmap。
     */
    val chapterHighlights: List<com.morealm.app.ui.reader.renderer.HighlightSpan> = emptyList(),
    /**
     * 当前章节的字体强调色 spans（kind=1，替换 paint.color）。
     */
    val chapterTextColorSpans: List<com.morealm.app.ui.reader.renderer.HighlightSpan> = emptyList(),
    val pageForTurn: (displayIndex: Int, relativePos: Int) -> TextPage? = { displayIndex, relativePos ->
        pages.getOrNull(displayIndex + relativePos)
    },
    val currentDisplayIndex: () -> Int,
    val canTurn: (Int, ReaderPageDirection) -> Boolean,
    val onPageChanged: (Int) -> Unit,
    val onFillPage: (Int, ReaderPageDirection) -> Int?,
    val onTapCenter: () -> Unit = {},
    val onLongPress: ((Offset) -> Unit)? = null,
    /**
     * 在 simulation 模式下做"已存高亮命中检测"的入口。返回 true = 本次 tap
     * 已被消费（弹出高亮 action menu），SimulationReadView 不再走 zone 翻页路由。
     */
    val onSingleTap: ((Offset) -> Boolean)? = null,
    /**
     * 选区 / 高亮 popup 是否正在显示。返回 `true` 时 [SimulationPager] 把
     * `SimulationReadView.shouldGateTouch` 抬起，让仿真翻页手势在 popup 弹出
     * 期间整个静默——避免出现「mini-menu 弹着但卷边动画也在拉」的二义体验。
     * 缺省 `{ false }` 保持旧行为，调用方未填时退化为不门控。
     */
    val isSelectionActive: () -> Boolean = { false },
    /**
     * popup 弹出期间用户在阅读区点了空白：通知调用方关掉 popup（清选区 +
     * 清 highlightActionTarget）。SLIDE / COVER 路径靠 `detectTapGestures` 兜底，
     * SIMULATION 路径把所有触摸接管到 [com.morealm.app.ui.reader.renderer.SimulationReadView]，
     * 没有这条回调就只能等用户点 Popup 内按钮才关。null = 不接管，保持旧行为。
     */
    val onDismissPopup: (() -> Unit)? = null,
)

/**
 * Paged reader with configurable page-turn animation.
 *
 * 各类型分支已下沉到独立文件，本函数仅做 [animType] dispatch。fallback 路径
 * （SIMULATION + simulationParams==null）仍走 [SlidePager]，但带 diagnostic
 * 日志便于定位「切到仿真先闪 B 第一页」等症状的根因。
 */
@Composable
fun AnimatedPageReader(
    pagerState: PagerState,
    animType: PageAnimType,
    modifier: Modifier = Modifier,
    simulationParams: SimulationParams? = null,
    simulationDisplayPage: Int = 0,
    onPageSettled: (Int) -> Unit = {},
    pageContent: @Composable (Int) -> Unit,
) {
    when (animType) {
        PageAnimType.SLIDE -> SlidePager(pagerState, modifier, onPageSettled, pageContent)
        PageAnimType.SLIDE_VERTICAL -> VerticalSlidePager(pagerState, modifier, onPageSettled, pageContent)
        PageAnimType.COVER -> CoverPager(pagerState, modifier, onPageSettled, pageContent)
        PageAnimType.SIMULATION -> {
            if (simulationParams != null) {
                SimulationPager(
                    pagerState = pagerState,
                    params = simulationParams,
                    currentDisplayPage = simulationDisplayPage,
                    modifier = modifier,
                    pageContent = pageContent,
                )
            } else {
                // Diagnostic [3w] — simulationParams==null fallback. simulationParams
                // 来自 CanvasRenderer:822 `if (pageAnimType==SIMULATION && pages.isNotEmpty())`，
                // 也就是说激活到这条 fallback 当且仅当「SIMULATION 模式 + pages 暂空」。
                // SlidePager 内部用 HorizontalPager 渲染 pageContent(pagerState.currentPage)，
                // pagerState.currentPage=0 时就会画 pages[0] = 章节首页大字标题。
                // 这正是「切到仿真先闪 B 第一页」+「首页进书从头显示」两个症状的元凶。
                AppLog.debug(
                    "PageTurnFlicker",
                    "[3w] SIMULATION FALLBACK SlidePager (simulationParams=null)" +
                        " pagerCurrentPage=${pagerState.currentPage}" +
                        " simulationDisplayPage=$simulationDisplayPage",
                )
                // Fallback if no params provided
                SlidePager(pagerState, modifier, onPageSettled, pageContent)
            }
        }
        PageAnimType.SCROLL -> ScrollPager(pagerState, modifier, pageContent)
        PageAnimType.NONE -> {
            LaunchedEffect(pagerState.currentPage) {
                onPageSettled(pagerState.currentPage)
            }
            HorizontalPager(
                state = pagerState,
                modifier = modifier.fillMaxSize(),
                userScrollEnabled = false,
            ) { pageIndex ->
                pageContent(pageIndex)
            }
        }
    }
}
