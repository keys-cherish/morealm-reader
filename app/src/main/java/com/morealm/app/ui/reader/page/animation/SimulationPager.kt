package com.morealm.app.ui.reader.page.animation

import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.viewinterop.AndroidView
import com.morealm.app.core.log.AppLog
import com.morealm.app.ui.reader.renderer.PageInfoOverlaySpec
import com.morealm.app.ui.reader.renderer.ReaderPageDirection
import com.morealm.app.ui.reader.renderer.SimulationReadView
import com.morealm.app.ui.reader.renderer.renderPageToBitmap
import kotlinx.coroutines.launch
import kotlin.math.abs

// ──────────────────────────────────────────────────────────────────────────────
// 仿真翻页（贝塞尔曲面 page curl）—— 移植自 Legado SimulationPageDelegate。
//
// 关键决定：手势 + 动画 + 离屏 bitmap 全部下沉到 Android View [SimulationReadView]
// 实现，Compose 层只用 [AndroidView] 做包装。原因详见
// docs/page-turn-bug-analysis.md：早期纯 Compose 版本的 pointerInput 闭包陈旧值
// + Animatable 多重订阅导致首页闪烁/触摸坐标错乱，迁回 View 后稳定。
//
// 本文件从 [PageAnimationPagers] 抽出，目的是让仿真翻页这条最复杂的路径独立
// 维护：~200 行的 update 块、抖动抑制、idle bitmap dedupe key 等都集中在这里，
// 改 simulation 不用再翻整个 PageAnimationPagers。
// ──────────────────────────────────────────────────────────────────────────────

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
 * 仿真翻页主入口。Compose 层不写自己的手势 / 动画 —— 全部委托给 View。
 *
 * 单层结构：[SimulationReadView] 自己处理 idle 显示和翻页动画两态，避免
 * Compose / View 混合层导致的透明度与 z-order 问题。idle 帧绘 idleBitmap
 * （已带正确主题 bgColor），不和 [pageContent] 同时渲染。
 *
 * # 关键路径
 *
 * 1. **factory**：仅在「非-SIMULATION → SIMULATION」模式切换时创建一次 View。
 * 2. **update**（每次重组都跑）：
 *    - 同步背景色 / 翻页可达回调 / bitmap provider
 *    - 渲染当前 idle bitmap，带 dedupe（[SimulationIdleKey] + lastBitmapState
 *      短路）防 prelayout 流式产页期间反复重渲染
 * 3. **onPageTurnCompleted**：View 通知翻页落地，回写 [PagerState] + 调
 *    [SimulationParams.onFillPage] 让 caller 决定下一页索引。
 *
 * # 抖动抑制（B 修复）
 *
 * pages 流式增长（155→275→276）时每次 size 变化都会重组；即便 displayPage
 * 与 page identity 都没变，PageInfoOverlay 的 hash（时/电量）每秒都会换
 * → contentKey 不同 → 每次都触发完整 setIdleBitmap 路径（renderPageToBitmap
 * 是 CPU 重活）。在 [lastBitmapState] 里缓存 (displayPage, pageId)，相同就
 * 跳过 setIdleBitmap。time/电量 overlay 等下次真翻页时自然刷新。
 */
@Composable
internal fun SimulationPager(
    pagerState: PagerState,
    params: SimulationParams,
    currentDisplayPage: Int,
    modifier: Modifier = Modifier,
    @Suppress("UNUSED_PARAMETER") pageContent: @Composable (Int) -> Unit,
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

    // ── B 修复：prelayout 期间 setIdleBitmap 抖动抑制 ──────────────────────────
    // pages 流式增长（155→275→276→...）过程中，即便 displayPage=0 / pageId 不变，
    // PageInfoOverlay 的身份哈希（时间/电量刷新）会换 → contentKey 不同 → 每次 pages.size
    // 变化都触发一次完整的 setIdleBitmap 路径（渲染 bitmap / 日志 / BitmapPool 操作）。
    //
    // 解法：在 SimulationPager 这一层缓存上一次的 (displayPage, pageId)，相同就整段跳过
    // setIdleBitmap。overlayId 的变化本身不值得重绘（时间/电量每秒都在变，只有真的翻页
    // 或 displayPage 改变才重绘）。
    val lastBitmapState = remember { intArrayOf(-1, 0) } // [0]=displayPage, [1]=pageId

    AndroidView(
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
                val curPageId = page?.let(System::identityHashCode) ?: 0

                // B 修复的 short-circuit：displayPage 和 pageId 都没变 → 直接跳过。
                // 只有当 page 为 null 的无效 case，才允许第二次进入（可能被缓存修复）。
                if (page != null &&
                    displayPage == lastBitmapState[0] &&
                    curPageId == lastBitmapState[1]
                ) {
                    // 跳过整段 —— overlay 时间/电量会在下次真翻页时自然刷新
                    AppLog.debug(
                        "PageTurnFlicker",
                        "[3a] setIdleBitmap SKIPPED (unchanged) displayPage=$displayPage pageId=$curPageId",
                    )
                    return@AndroidView
                }
                lastBitmapState[0] = displayPage
                lastBitmapState[1] = curPageId

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

// ──────────────────────────────────────────────────────────────────────────────
// Vestigial helpers —— 早期纯 Compose 版本的 SimulationPager 用过；迁回 View 后
// 已经全部不引用，但暂留供未来若需要回 Compose 路径时参考。每项都没有外部依赖，
// 删除是无副作用操作（待后续清理 PR 一次性删除）。
// ──────────────────────────────────────────────────────────────────────────────

@Suppress("unused") private const val DRAG_DIRECTION_THRESHOLD = 10f
@Suppress("unused") private const val PAGE_FLIP_THRESHOLD = 0.35f
@Suppress("unused") private const val SIMULATION_ANIMATION_SPEED = 300
@Suppress("unused") private const val TOUCH_EDGE_GUARD = 0.1f

@Suppress("unused")
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

@Suppress("unused")
private fun recycleBitmapIfDetached(bitmap: Bitmap?, vararg keep: Bitmap?) {
    if (bitmap == null || bitmap.isRecycled || keep.any { it === bitmap }) return
    bitmap.recycle()
}

@Suppress("unused")
private fun SimulationBitmapWindow.recycleExcept(vararg keep: Bitmap?) {
    recycleBitmapIfDetached(prev, *keep)
    recycleBitmapIfDetached(current, *keep)
    recycleBitmapIfDetached(next, *keep)
}

@Suppress("unused")
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
