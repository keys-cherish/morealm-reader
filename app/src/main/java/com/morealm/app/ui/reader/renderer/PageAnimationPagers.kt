package com.morealm.app.ui.reader.renderer

import android.graphics.Bitmap
import android.text.TextPaint
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.VectorConverter
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.input.pointer.pointerInput
import com.morealm.app.core.log.AppLog
import com.morealm.app.domain.render.TextPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/** Cover animation constants */
private const val COVER_SHADOW_WIDTH = 30f          // 阴影渐变宽度 (px)
private const val COVER_MAX_SHADOW_ALPHA = 0.4f     // 滑入页左侧阴影最大透明度

/** Simulation (page curl) constants */
private const val DRAG_DIRECTION_THRESHOLD = 10f    // 判定拖拽方向的最小位移 (px)
private const val PAGE_FLIP_THRESHOLD = 0.35f       // 翻页完成阈值（屏幕宽度的比例）
private const val SIMULATION_ANIMATION_SPEED = 300  // Legado ReadView.defaultAnimationSpeed
private const val TOUCH_EDGE_GUARD = 0.1f           // 触摸点边界保护值，防止除零

private data class SimulationBitmapWindow(
    val pageIndex: Int,
    val width: Int,
    val height: Int,
    val prev: Bitmap?,
    val current: Bitmap,
    val next: Bitmap?,
) {
    fun matches(index: Int, viewWidth: Int, viewHeight: Int): Boolean =
        pageIndex == index && width == viewWidth && height == viewHeight
}

private fun recycleBitmapIfDetached(bitmap: Bitmap?, vararg keep: Bitmap?) {
    if (bitmap == null || bitmap.isRecycled || keep.any { it === bitmap }) return
    bitmap.recycle()
}

private fun SimulationBitmapWindow.recycleExcept(vararg keep: Bitmap?) {
    recycleBitmapIfDetached(prev, *keep)
    recycleBitmapIfDetached(current, *keep)
    recycleBitmapIfDetached(next, *keep)
}

private fun simulationAnimationDuration(
    start: Offset,
    target: Offset,
    viewWidth: Int,
    viewHeight: Int,
): Int {
    val dx = abs(target.x - start.x)
    val dy = abs(target.y - start.y)
    val distance = if (dx > 0f) dx else dy
    val extent = if (dx > 0f) viewWidth else viewHeight
    if (extent <= 0) return 1
    return ((SIMULATION_ANIMATION_SPEED * distance) / extent).toInt().coerceAtLeast(1)
}

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
)

/**
 * `SimulationReadView.setIdleBitmap` 用的内容签名。
 *
 * 等价语义：两个 key `equals` 时，渲染出的 idle bitmap 像素一致——所以
 * View 端可以安全跳过整个 producer。
 *
 * 字段选择理由：
 * - [pageId] 用 `System.identityHashCode(page)` 而非 `page.hashCode()`：
 *   TextPage 的 `hashCode` 实现可能基于内容相等而非身份，跨页/重排时
 *   不同对象偶尔会撞 hash → 错误命中 dedupe。`identityHashCode` 严格
 *   按对象身份比较。
 * - [displayPage] 并入 key 是为了同一 TextPage 在不同 displayPage 位置
 *   时（理论上不会发生，但防御性写法）不被 dedupe 误吞。
 * - [w]/[h] 视图尺寸变化（系统栏切换、分屏等）必须重渲染。
 * - [bgColor]/[bgBitmapId]/[overlayId] 主题切换 / 背景图切换时这些会变，
 *   即使 page 对象不变也得重渲染。
 */
private data class SimulationIdleKey(
    val pageId: Int,
    val displayPage: Int,
    val w: Int,
    val h: Int,
    val bgColor: Int,
    val bgBitmapId: Int,
    val overlayId: Int,
)

/**
 * Paged reader with configurable page-turn animation.
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

// ── Slide animation ──
// Both current and next/prev pages slide together, matching Legado's SlidePageDelegate.

@Composable
private fun SlidePager(
    pagerState: PagerState,
    modifier: Modifier = Modifier,
    onPageSettled: (Int) -> Unit = {},
    pageContent: @Composable (Int) -> Unit,
) {
    LaunchedEffect(pagerState.currentPage, pagerState.isScrollInProgress) {
        if (!pagerState.isScrollInProgress) onPageSettled(pagerState.currentPage)
    }
    HorizontalPager(
        state = pagerState,
        modifier = modifier.fillMaxSize(),
        userScrollEnabled = false,
    ) { pageIndex ->
        // Default HorizontalPager already does slide — both pages move together.
        // This matches Legado's SlidePageDelegate behavior.
        pageContent(pageIndex)
    }
}

// ── Vertical slide animation (上下翻页) ──
// Both current and next/prev pages slide together vertically.

@Composable
private fun VerticalSlidePager(
    pagerState: PagerState,
    modifier: Modifier = Modifier,
    onPageSettled: (Int) -> Unit = {},
    pageContent: @Composable (Int) -> Unit,
) {
    LaunchedEffect(pagerState.currentPage, pagerState.isScrollInProgress) {
        if (!pagerState.isScrollInProgress) onPageSettled(pagerState.currentPage)
    }
    VerticalPager(
        state = pagerState,
        modifier = modifier.fillMaxSize(),
        userScrollEnabled = false,
    ) { pageIndex ->
        pageContent(pageIndex)
    }
}

// ── Cover animation ──
// Incoming page slides over the outgoing page with a shadow gradient.
// Matches Legado's CoverPageDelegate.

@Composable
private fun CoverPager(
    pagerState: PagerState,
    modifier: Modifier = Modifier,
    onPageSettled: (Int) -> Unit = {},
    pageContent: @Composable (Int) -> Unit,
) {
    LaunchedEffect(pagerState.currentPage, pagerState.isScrollInProgress) {
        if (!pagerState.isScrollInProgress) onPageSettled(pagerState.currentPage)
    }
    HorizontalPager(
        state = pagerState,
        modifier = modifier.fillMaxSize(),
        userScrollEnabled = false,
    ) { pageIndex ->
        val pageOffset = (pagerState.currentPage - pageIndex) +
            pagerState.currentPageOffsetFraction

        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val offset = pageOffset.coerceIn(-1f, 1f)
                    when {
                        // Incoming page (swiping left → next page slides in from right)
                        // offset < 0: this is the NEXT page. Default pager position is off-screen right.
                        // We want it to slide in from the right edge, so no extra translation needed —
                        // HorizontalPager already handles this.
                        offset < 0 -> { /* default pager behavior is correct */ }

                        // Outgoing page (being covered): should stay pinned in place.
                        // HorizontalPager moves it left by default. Counteract by adding back the offset.
                        offset > 0 -> {
                            // Pager shifts this page left by (offset * width). Undo that.
                            translationX = size.width * offset
                        }
                    }
                }
                .drawWithContent {
                    drawContent()
                    val offset = pageOffset.coerceIn(-1f, 1f)
                    if (offset < 0) {
                        // Shadow on left edge of the sliding-in page
                        val shadowAlpha = (abs(offset) * COVER_MAX_SHADOW_ALPHA).coerceIn(0f, COVER_MAX_SHADOW_ALPHA)
                        drawRect(
                            brush = Brush.horizontalGradient(
                                colors = listOf(
                                    Color.Black.copy(alpha = shadowAlpha),
                                    Color.Transparent,
                                ),
                                startX = 0f,
                                endX = COVER_SHADOW_WIDTH,
                            ),
                            size = androidx.compose.ui.geometry.Size(COVER_SHADOW_WIDTH, size.height),
                        )
                    }
                }
        ) {
            pageContent(pageIndex)
        }
    }
}

// ── Simulation (page curl) animation ──
// 真正的贝塞尔曲线仿真翻页，移植自 Legado SimulationPageDelegate。
// 不使用 HorizontalPager，而是自己管理手势 + Animatable 驱动 + Bitmap 离屏渲染。

// ── Simulation (page curl) animation ──
// Uses AndroidView wrapping a native SimulationReadView to avoid
// Compose pointerInput closure staleness issues.
// See docs/page-turn-bug-analysis.md for why this approach was chosen.

@Composable
private fun SimulationPager(
    pagerState: PagerState,
    params: SimulationParams,
    currentDisplayPage: Int,
    modifier: Modifier = Modifier,
    pageContent: @Composable (Int) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val pages = params.pages
    val pageCount = pages.size.coerceAtLeast(1)
    // 不能用 coerceIn(0, pageCount - 1) —— SimulationPager 重组时 pages 可能
    // 还在 prelayout 增量产出，size=1 会把 caller 算好的 currentDisplayPage=N
    // 强行夹回 0，导致「滚动→仿真切回时第一帧渲染章节首页」。把 cap 抬高到
    // 至少容纳 currentDisplayPage 自己，让 pageForTurn 自己处理越界（越界时
    // 它返回 null，bitmap 留白比闪一帧错的页更好）。同步修复见
    // CanvasRenderer.kt 的 safeDisplayMax 注释。
    val cap = (pageCount - 1).coerceAtLeast(currentDisplayPage)
    val displayPage = currentDisplayPage.coerceIn(0, cap)

    // Diagnostic [3p] — emitted on every recomposition of SimulationPager.
    // Triangulates between [1] (CanvasRenderer 上游算的 simulationDisplayPage)
    // 和 [3a]/[3b]/[3c]/[3d]/[3v] (View 端实际行为)。关注点：
    //  · pagerCurrentPage 在某一次重组里 ≠ displayPage 就说明 HorizontalPager
    //    没及时 scrollToPage——SIMULATION 分支虽然不绘制 pageContent，但
    //    pagerState 共享给其它分支，可能侧漏出"首页"那一帧。
    //  · cap / pages.size / displayPage 三者之间的越界期 dance
    AppLog.debug(
        "PageTurnFlicker",
        "[3p] SimulationPager COMPOSE pagerCurrentPage=${pagerState.currentPage}" +
            " currentDisplayPage=$currentDisplayPage pages.size=${pages.size}" +
            " cap=$cap displayPage=$displayPage",
    )

    // Single-layer: SimulationReadView handles both idle display and animation.
    // Idle state draws idleBitmap (rendered with correct theme bgColor).
    // No Compose/View layering — avoids transparency and z-order issues.
    androidx.compose.ui.viewinterop.AndroidView(
        factory = { context ->
            // Diagnostic [3v] — fires once per fresh SimulationReadView. Pairs
            // with [3p] COMPOSE: factory only runs when the AndroidView slot is
            // (re)mounted (mode swap from 非-SIMULATION → SIMULATION). 第一帧
            // idleBitmap=null 时 onDraw else 分支只画 bgMeanColor，所以这里也
            // 带上 bgColor 方便核对屏幕颜色是否对应。
            AppLog.debug(
                "PageTurnFlicker",
                "[3v] factory CREATE bgColor=${params.bgColor}" +
                    " currentDisplayPage=$currentDisplayPage displayPage=$displayPage" +
                    " pages.size=${pages.size}",
            )
            SimulationReadView(context).apply {
                setBackgroundColor(params.bgColor)
                bgMeanColor = params.bgMeanColor
            }
        },
        update = { view ->
            view.setBackgroundColor(params.bgColor)
            view.bgMeanColor = params.bgMeanColor
            view.canTurnNext = { params.canTurn(displayPage, ReaderPageDirection.NEXT) }
            view.canTurnPrev = { params.canTurn(displayPage, ReaderPageDirection.PREV) }
            view.bitmapProvider = { relativePos, w, h ->
                val page = params.pageForTurn(displayPage, relativePos)
                // Diagnostic [3c] — fires when SimulationReadView 的 setBitmaps()
                // (触摸开始) 调用 provider(relPos)。关注点：切换瞬间如果触发了
                // setBitmaps（不应该，因为切换不带触摸），看这条日志的
                // displayPage / relativePos 即可定位渲染了哪一页。
                // displayPage=1 + relativePos=-1 = page index 0 = 首页。
                AppLog.debug(
                    "PageTurnFlicker",
                    "[3c] bitmapProvider INVOKED relativePos=$relativePos" +
                        " displayPage=$displayPage targetIdx=${displayPage + relativePos}" +
                        " pageId=${page?.let { System.identityHashCode(it) } ?: "null"}" +
                        " viewWxH=${w}x$h",
                )
                if (page != null && w > 0 && h > 0) {
                    try {
                        renderPageToBitmap(
                            w, h, params.bgColor, page,
                            params.titlePaint, params.contentPaint,
                            chapterNumPaint = params.chapterNumPaint,
                            reuseBitmap = null, bgBitmap = params.bgBitmap,
                            pageInfoOverlay = params.pageInfoOverlay,
                        )
                    } catch (e: OutOfMemoryError) {
                        AppLog.error("Simulation", "bitmap OOM w=${w} h=${h}", e)
                        null
                    }
                } else null
            }
            view.onTapCenter = { params.onTapCenter() }
            view.onLongPress = { x, y -> params.onLongPress?.invoke(Offset(x, y)) }
            view.onSingleTap = { x, y -> params.onSingleTap?.invoke(Offset(x, y)) ?: false }
            view.onPageTurnCompleted = { isNext ->
                val direction = if (isNext) ReaderPageDirection.NEXT else ReaderPageDirection.PREV
                val committedPage = params.onFillPage(displayPage, direction)
                if (committedPage != null) {
                    val safePage = committedPage.coerceIn(0, pageCount - 1)
                    scope.launch { pagerState.scrollToPage(safePage) }
                    params.onPageChanged(safePage)
                }
            }
            // Render idle bitmap with correct theme background color
            val w = view.width
            val h = view.height
            // ─── 模式切换闪烁防御 ─────────────────────────────────────
            // 在 prelayout 流式产页阶段（pages.size 还没追上 displayPage），
            // params.pageForTurn(displayPage, 0) 会落入 PageFactory 的 fallback
            // 路径，每次重组返回不同的"占位 TextPage"——日志 19:06:54 那段：
            // pageCount 一直是 1，但 pageHash 却 186694352→97622661→
            // 146198694→105840259→… 抖动了 5 次，每张都被画进 idleBitmap →
            // 屏幕上看到"占位页内容连续闪烁"。
            //
            // 解法：只有当 displayPage 是 pages 列表里的合法索引时才更新
            // idleBitmap；越界期间保留 View 现有 idleBitmap（新建 View 时为
            // null，绘制为纯 bgColor 背景，比闪 5 张错页温和得多）。等
            // pages 列表追上后下一次 update 会用正确内容补齐。
            if (w > 0 && h > 0 && displayPage in pages.indices) {
                val page = params.pageForTurn(displayPage, 0)
                // 内容签名：相同的 (TextPage 身份, 视图尺寸, 背景颜色, 背景图,
                // info 叠加层) → 渲染结果完全一样。SimulationReadView 拿到
                // 同 key 会 short-circuit 整个渲染调用，避免 19:06:52 那段
                // 同一 pageHash=215777196 在 pageCount 增长过程被反复渲染 6
                // 次的浪费 + 闪烁。displayPage 也并入 key，跨页时不受
                // dedupe 影响。
                val contentKey: Any? = if (page != null) {
                    SimulationIdleKey(
                        pageId = System.identityHashCode(page),
                        displayPage = displayPage,
                        w = w,
                        h = h,
                        bgColor = params.bgColor,
                        bgBitmapId = params.bgBitmap?.let(System::identityHashCode) ?: 0,
                        overlayId = params.pageInfoOverlay?.let(System::identityHashCode) ?: 0,
                    )
                } else null
                // Diagnostic — pairs with setIdleBitmap's RECV log so we can
                // see which displayPage the wrong bitmap was rendered from.
                // 关键字段 isCompleted / lines.size / textPrefix 验证
                // 「TextPage 对象 mutable，prelayout 流式重写其 lines/text，
                //  identityHashCode 不变但视觉内容会变」假设：如果第一次
                // 渲染的 page.isCompleted=false 且后续 true，假设成立；
                // textPrefix 的首 30 字符让我们直接看到内容是否在变。
                val isCompleted = page?.isCompleted
                val linesSize = page?.lines?.size
                val textPrefix = page?.text?.take(30)?.replace("\n", "\\n")
                // 验证「pages[1] 在粗略分页阶段含 isChapterNum 大标题行」假设
                val firstLineText = page?.lines?.firstOrNull()?.text?.take(20)
                val firstLineIsChapterNum = page?.lines?.firstOrNull()?.isChapterNum
                val firstLineIsTitle = page?.lines?.firstOrNull()?.isTitle
                val titleLineCount = page?.lines?.count { it.isTitle || it.isChapterNum }
                AppLog.debug(
                    "PageTurnFlicker",
                    "[3a] setIdleBitmap CALLED displayPage=$displayPage" +
                        " pageHash=${page?.hashCode() ?: "null"}" +
                        " isCompleted=$isCompleted linesSize=$linesSize" +
                        " textPrefix=\"$textPrefix\"" +
                        " firstLine=\"$firstLineText\"" +
                        " firstLineIsChapterNum=$firstLineIsChapterNum firstLineIsTitle=$firstLineIsTitle" +
                        " titleLineCount=$titleLineCount" +
                        " pagesSize=${pages.size} pageCount=$pageCount viewWxH=${w}x$h" +
                        " key=$contentKey",
                )
                view.setIdleBitmap(key = contentKey) {
                    if (page != null) renderPageToBitmap(
                        w, h, params.bgColor, page,
                        params.titlePaint, params.contentPaint,
                        chapterNumPaint = params.chapterNumPaint,
                        reuseBitmap = null, bgBitmap = params.bgBitmap,
                        pageInfoOverlay = params.pageInfoOverlay,
                    ) else null
                }
            } else if (w > 0 && h > 0) {
                // 仅记日志，不动 idleBitmap——便于回看时确认"被故意跳过"
                // 而不是某个分支静默丢失了。
                AppLog.debug(
                    "PageTurnFlicker",
                    "[3a] setIdleBitmap SKIPPED out-of-range displayPage=$displayPage" +
                        " pagesSize=${pages.size} pageCount=$pageCount viewWxH=${w}x$h",
                )
            }
        },
        modifier = modifier.fillMaxSize(),
    )

}

// Old Compose-based SimulationPager removed — replaced by AndroidView + SimulationReadView.
// See docs/page-turn-bug-analysis.md for rationale.

// ── Vertical scroll animation ──
// Continuous vertical scrolling through pages using LazyColumn.
// Inspired by Legado's ScrollPageDelegate — pages are laid out vertically,
// each taking full screen height, with native fling and smooth transitions.

@Composable
private fun ScrollPager(
    pagerState: PagerState,
    modifier: Modifier = Modifier,
    pageContent: @Composable (Int) -> Unit,
) {
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = pagerState.currentPage)
    val scope = rememberCoroutineScope()

    // 将 LazyColumn 的可见页同步回 pagerState，使外部逻辑（进度、章节切换等）正常工作
    val firstVisibleIndex by remember {
        derivedStateOf { listState.firstVisibleItemIndex }
    }
    LaunchedEffect(firstVisibleIndex) {
        if (firstVisibleIndex != pagerState.currentPage) {
            pagerState.scrollToPage(firstVisibleIndex)
        }
    }

    // 当外部通过 pagerState 跳页时（如目录跳转），同步到 LazyColumn
    LaunchedEffect(pagerState.currentPage) {
        if (pagerState.currentPage != listState.firstVisibleItemIndex) {
            listState.scrollToItem(pagerState.currentPage)
        }
    }

    // 用 BoxWithConstraints 获取屏幕高度，确保每页占满全屏
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val pageHeight = maxHeight

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
        ) {
            items(pagerState.pageCount) { pageIndex ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(pageHeight)
                ) {
                    pageContent(pageIndex)
                }
            }
        }
    }
}
