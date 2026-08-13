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
    // "scroll" / "vertical" 偏好残留 —— v1.5 起 SCROLL 模式由 PageTurnMode=SCROLL
    // 触发 ScrollCanvasReaderHost 独立接管，不走翻页路径。PageAnim 偏好里残留
    // "scroll" 字符串映射成 NONE 避免进 CanvasRenderer SCROLL safety net 崩溃
    // (commit b176ec3 V1 删除时遗留)。
    "scroll", "vertical" -> PageAnimType.NONE
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
    /**
     * 当前章节的下划线 spans（kind=2）。语义同 [chapterHighlights]，多带
     * [HighlightSpan.underlineStyle] 决定线型。仿真翻页 bitmap 渲染时透传给
     * renderPageToBitmap 在基线下方画线。
     */
    val chapterUnderlines: List<com.morealm.app.ui.reader.renderer.HighlightSpan> = emptyList(),
    val pageForTurn: (displayIndex: Int, relativePos: Int) -> TextPage? = { displayIndex, relativePos ->
        pages.getOrNull(displayIndex + relativePos)
    },
    val currentDisplayIndex: () -> Int,
    val canTurn: (Int, ReaderPageDirection) -> Boolean,
    val onPageChanged: (Int) -> Unit,
    val onFillPage: (Int, ReaderPageDirection) -> Int?,
    val onTapCenter: () -> Unit = {},
    /** 点击九宫格动作矩阵（ReaderTapZones 生效网格；prev/next/menu 由 View 原生分发）。 */
    val tapGrid: List<String> = com.morealm.app.ui.reader.ReaderTapZones.DEFAULT_GRID,
    /** 九宫格扩展动作出口（prev_chapter/next_chapter/tts/bookmark）；null 退化呼出菜单。 */
    val onZoneAction: ((String) -> Unit)? = null,
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
    /**
     * SIMULATION 模式下的 SimulationReadView 引用 holder — caller 持有后可以把音量键 /
     * TtsPanel / 顶栏按钮翻页指令直接转给 view.keyTurnPage 跑贝塞尔翻页动画。
     * 非 SIMULATION 模式忽略；caller 不需要时传 null。
     */
    simulationViewRef: androidx.compose.runtime.MutableState<com.morealm.app.ui.reader.renderer.SimulationReadView?>? = null,
    onPageSettled: (Int) -> Unit = {},
    /**
     * **P3-3a/3c**：动画 ↔ 渲染契约线入口（详见 [com.morealm.app.ui.reader.page.PageBitmapProvider]）。
     *
     * P3-3c 起：SLIDE / SLIDE_VERTICAL / COVER 三种动画在 `bitmapProvider != null`
     * 时改走 [com.morealm.app.ui.reader.page.BitmapPageContent]（异步 load bitmap +
     * Image），否则保持现 [pageContent] 兜底。SIMULATION 暂时不接（它有自己一套
     * bitmap pipeline 由 [SimulationParams.pageForTurn] + `renderPageToBitmap` 驱动），
     * P3-3d 单独适配。
     *
     * 默认 `null` = 现有 caller 完全不受影响（pageContent 路径）。当前
     * `CanvasRenderer` / `VerticalReaderView` 都不传，零行为变化；P3-5 起 caller
     * 才会传真正的 [com.morealm.app.ui.reader.page.PageBitmapProvider] 实现。
     */
    bitmapProvider: com.morealm.app.ui.reader.page.PageBitmapProvider? = null,
    pageContent: @Composable (Int) -> Unit,
) {
    when (animType) {
        PageAnimType.SLIDE -> SlidePager(
            pagerState = pagerState,
            modifier = modifier,
            onPageSettled = onPageSettled,
            pageContent = pageContent,
            bitmapProvider = bitmapProvider,
        )
        PageAnimType.SLIDE_VERTICAL -> VerticalSlidePager(
            pagerState = pagerState,
            modifier = modifier,
            onPageSettled = onPageSettled,
            pageContent = pageContent,
            bitmapProvider = bitmapProvider,
        )
        PageAnimType.COVER -> CoverPager(
            pagerState = pagerState,
            modifier = modifier,
            onPageSettled = onPageSettled,
            pageContent = pageContent,
            bitmapProvider = bitmapProvider,
        )
        PageAnimType.SIMULATION -> {
            if (simulationParams != null) {
                SimulationPager(
                    pagerState = pagerState,
                    params = simulationParams,
                    currentDisplayPage = simulationDisplayPage,
                    modifier = modifier,
                    simulationViewRef = simulationViewRef,
                    pageContent = pageContent,
                    bitmapProvider = bitmapProvider,
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
        PageAnimType.SCROLL -> {
            // safety net：用户偏好残留 PageAnim="scroll" 时 toPageAnimType 已映射成 NONE，
            // 理论上不进本分支；保险起见 fallback 到 NONE 行为（HorizontalPager userScrollEnabled=false）
            // 避免直接崩溃。SCROLL 真正路径在 ScrollCanvasReaderHost。
            LaunchedEffect(pagerState.currentPage) { onPageSettled(pagerState.currentPage) }
            HorizontalPager(state = pagerState, modifier = modifier.fillMaxSize(), userScrollEnabled = false) { pageContent(it) }
        }
        PageAnimType.NONE -> {
            LaunchedEffect(pagerState.currentPage) {
                onPageSettled(pagerState.currentPage)
            }
            HorizontalPager(
                state = pagerState,
                modifier = modifier.fillMaxSize(),
                userScrollEnabled = false,
            ) { pageIndex ->
                // P3-5a NONE 接入：bitmapProvider != null 时改走 BitmapPageContent
                // （bitmap 静态文字 + 主题 paint）。pageContent 路径保留兜底兼容，
                // caller 不传 bitmapProvider 时回到 PageContentBox（含选区 / cursor /
                // autoPage overlay）旧行为。
                if (bitmapProvider != null) {
                    com.morealm.app.ui.reader.page.BitmapPageContent(bitmapProvider, pageIndex)
                } else {
                    pageContent(pageIndex)
                }
            }
        }
    }
}
