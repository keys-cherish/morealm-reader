package com.morealm.app.ui.reader.page.animhorizontal

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.viewinterop.AndroidView
import com.morealm.app.core.log.AppLog
import com.morealm.app.domain.render.layout.ScrollPageFactory
import com.morealm.app.domain.reader.runtime.NavigationSource
import com.morealm.app.ui.reader.page.ScrollPagePageBitmapProvider
import com.morealm.app.ui.reader.renderer.SimulationReadView
import com.morealm.app.ui.reader.renderer.scroll.ScrollCanvasReaderState

/**
 * 仿真翻页 Transition（贝塞尔曲面 page curl）—— page-level 富排版路径。
 *
 * 与旧 [com.morealm.app.ui.reader.page.animation.SimulationPager] 的差异：
 *  - 旧 SimulationPager 走 [com.morealm.app.domain.render.TextPage] + PagerState 双轨设计
 *    （为 TXT / 网络书的 ChapterProvider 排版结果服务），EPUB 富数据全部丢失
 *  - 本 Transition 直接消费 [com.morealm.app.domain.render.layout.ScrollPage]，与
 *    NONE/COVER/SLIDE Transition 同源富排版，EPUB 章背景 / 段装饰 / 整屏封面 / 自带字体
 *    / drop cap 等都生效
 *
 * # 跨章无缝
 *
 * [ScrollPageFactory.prevPage] / `curPage` / [ScrollPageFactory.nextPage] 已经天然跨章
 * （章首 prevPage 返回 prev 章末页 / 章末 nextPage 返回 next 章首页），bitmap 渲染端
 * [ScrollPagePageBitmapProvider.bitmapForScrollPage] 按 `page.chapterIndex` 路由到对应
 * layout 渲染 → 仿真翻页跨章动画无缝，与 SLIDE/COVER 一致。
 *
 * # 选区 / 长按 / tap-on-highlight
 *
 * [SimulationReadView] 接管所有触摸事件（单一触摸入口，规避 Compose pointerInput 闭包
 * 陈旧值），Host 共享层 detectTapGestures 在 SIMULATION 模式被禁用。本 Transition 通过
 * lambda 参数把 Host 的 selection / highlightActionTarget state 注入到 view callback：
 *  - `onTapCenter` 中央三分之一 → 切控制栏
 *  - `onTapOnHighlight` 命中已存 highlight → 弹删除/分享菜单（return true 消费触摸）
 *  - `onLongPress` 长按 → 选区起点 hit-test（Host 内 handleLongPress 实现）
 *  - `isSelectionActive` 选区 / 高亮 popup 弹出期间 view 触摸门控（翻页手势短路）
 *  - `onDismissPopup` 门控期间点空白 → 关 popup（Host 清 selection + highlightAction）
 *
 * 注入位置：[PageLevelReaderHost] 在 SIMULATION 分支构造 + wire lambda。
 *
 * @param turnCtrl Host 翻页动画 controller —— 注册 animateToPrev/Next 让 zone tap /
 *                 pageTurnCommand 桥（音量键 / TTS / 顶栏按钮）跑 [SimulationReadView.keyTurnPage]
 *                 贝塞尔动画
 * @param simulationViewRef caller 持有的 view 引用 holder（可选）；caller 不需要时传 null
 */
@Composable
fun SimulationPageTransition(
    state: ScrollCanvasReaderState,
    pageFactory: ScrollPageFactory,
    bitmapProvider: ScrollPagePageBitmapProvider,
    backgroundColor: Int,
    bgMeanColor: Int = backgroundColor,
    gesturesEnabled: Boolean = true,
    onTapCenter: () -> Unit = {},
    /** 点击区域翻页动作（透传给 SimulationReadView 的九宫格 tap）。默认左 prev 右 next。 */
    tapActionTopLeft: String = "prev",
    tapActionTopRight: String = "next",
    tapActionBottomLeft: String = "prev",
    tapActionBottomRight: String = "next",
    onTapOnHighlight: ((Offset) -> Boolean)? = null,
    onLongPress: ((Offset) -> Unit)? = null,
    isSelectionActive: () -> Boolean = { false },
    onDismissPopup: (() -> Unit)? = null,
    turnCtrl: PageTurnAnimController? = null,
    commitPageTurn: (isNext: Boolean, source: NavigationSource) -> Boolean,
    simulationViewRef: MutableState<SimulationReadView?>? = null,
    modifier: Modifier = Modifier,
) {
    @Suppress("UNUSED_VARIABLE")
    val _state = state // 当前未直接读，预留：未来如需 chapter-level info bar / TTS hint 接入
    val viewHolder = remember { mutableStateOf<SimulationReadView?>(null) }
    var pendingNavigationSource by remember { mutableStateOf(NavigationSource.GESTURE) }

    // turnCtrl 注册：Host zone tap + pageTurnCommand 桥（音量键 / TTS / 顶栏按钮）共用
    // 一个 turnCtrl 路径；SIMULATION 这里把 animateToPrev/Next 接到 view.keyTurnPage。
    // view.keyTurnPage 内部走 Scroller + invalidate，无阻塞，suspend lambda 即返。
    DisposableEffect(turnCtrl) {
        if (turnCtrl != null) {
            turnCtrl.animateToNext = { source ->
                viewHolder.value?.let { v ->
                    pendingNavigationSource = source
                    AppLog.debug("SimPageTransition", "animateToNext -> view.keyTurnPage(true)")
                    v.keyTurnPage(isNext = true)
                }
            }
            turnCtrl.animateToPrev = { source ->
                viewHolder.value?.let { v ->
                    pendingNavigationSource = source
                    AppLog.debug("SimPageTransition", "animateToPrev -> view.keyTurnPage(false)")
                    v.keyTurnPage(isNext = false)
                }
            }
        }
        onDispose {
            turnCtrl?.animateToNext = null
            turnCtrl?.animateToPrev = null
        }
    }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { context ->
            AppLog.debug(
                "SimPageTransition",
                "factory CREATE bgColor=$backgroundColor pageIndex=${pageFactory.pageIndex}",
            )
            SimulationReadView(context).apply {
                setBackgroundColor(backgroundColor)
                this.bgMeanColor = bgMeanColor
                viewHolder.value = this
                simulationViewRef?.value = this
            }
        },
        update = { view ->
            viewHolder.value = view
            simulationViewRef?.value = view
            view.setBackgroundColor(backgroundColor)
            view.bgMeanColor = bgMeanColor

            // 主题 / 字号 / 字体变化时 bitmapProvider identity 会变 → 主动 unpin 让
            // 下一次 setIdleBitmap 落地（onAnimStop 后 idleBitmap 默认 pin 直到 ACTION_DOWN，
            // 此期间换主题 setIdleBitmap 会被 REJECT；主动 unpin 让新主题立即生效）。
            view.unpinIdleBitmap()

            view.canTurnNext = { gesturesEnabled && pageFactory.hasNext() }
            view.canTurnPrev = { gesturesEnabled && pageFactory.hasPrev() }

            // ── bitmap provider：跨章自动 work ──
            // ScrollPageFactory.prevPage/nextPage 已经跨章（章首返 prevChapter 末页 / 章末
            // 返 nextChapter 首页），bitmapForScrollPage 按 page.chapterIndex 路由到对应
            // layout。SimulationReadView 三块离屏 bitmap 自然跨章。
            view.bitmapProvider = { relativePos, w, h ->
                val page = when (relativePos) {
                    -1 -> pageFactory.prevPage
                    0 -> pageFactory.curPage
                    1 -> pageFactory.nextPage
                    else -> null
                }
                if (page != null && page.chapterIndex >= 0 && w > 0 && h > 0) {
                    bitmapProvider.bitmapForScrollPage(page, w, h)
                } else null
            }

            // ── onPageTurnCompleted：动画落地后驱动 pageFactory 翻页 / 跨章 swap ──
            // 章内：moveToNext/Prev 自己更新 pageIndex；
            // 章末/章首：moveToNext/Prev 内部触发 chapterShiftCallback → state.swapToNext/Prev，
            //   下次重组 PageLevelReaderHost 看到 currentChapter 已切，BitmapProvider 重建。
            view.onPageTurnCompleted = { isNext ->
                AppLog.debug(
                    "SimPageTransition",
                    "onPageTurnCompleted isNext=$isNext curPgIdx=${pageFactory.pageIndex} " +
                        "hasNext=${pageFactory.hasNext()} hasPrev=${pageFactory.hasPrev()}",
                )
                commitPageTurn(isNext, pendingNavigationSource)
                pendingNavigationSource = NavigationSource.GESTURE
            }

            // ── Host 共享交互透传 ──
            view.onTapCenter = if (gesturesEnabled) onTapCenter else null
            view.tapActionTopLeft = tapActionTopLeft
            view.tapActionTopRight = tapActionTopRight
            view.tapActionBottomLeft = tapActionBottomLeft
            view.tapActionBottomRight = tapActionBottomRight
            view.onSingleTap = if (onTapOnHighlight != null) {
                { x, y -> onTapOnHighlight.invoke(Offset(x, y)) }
            } else null
            view.onLongPress = if (onLongPress != null) {
                { x, y -> onLongPress.invoke(Offset(x, y)) }
            } else null
            view.shouldGateTouch = isSelectionActive
            view.onTapWhileGated = onDismissPopup

            // ── idle bitmap（每次重组都尝试写入；dedupe 由 view 内部按 key 处理）──
            val w = view.width
            val h = view.height
            val pgIdx = pageFactory.pageIndex
            val curPage = pageFactory.curPage
            if (w > 0 && h > 0 && curPage.chapterIndex >= 0) {
                val key = SimulationIdleKey(
                    providerId = System.identityHashCode(bitmapProvider),
                    infoVersion = bitmapProvider.infoVersion,
                    chapterIndex = curPage.chapterIndex,
                    pageIndex = pgIdx,
                    w = w, h = h,
                    bgColor = backgroundColor,
                )
                view.setIdleBitmap(key = key) {
                    try {
                        bitmapProvider.bitmapForScrollPage(curPage, w, h)
                    } catch (e: OutOfMemoryError) {
                        AppLog.error("SimPageTransition", "idle bitmap OOM w=$w h=$h", e)
                        null
                    }
                }
            }
        },
    )
}

/**
 * SimulationReadView setIdleBitmap dedupe key（page-level provider 路径）。
 *
 *  - [providerId] = `System.identityHashCode(bitmapProvider)`：内容版本管理交给
 *    [com.morealm.app.ui.reader.page.ScrollPagePageBitmapProvider]，caller 在主题 / 字号 /
 *    章节切换 / 高亮增删时 remember 出新 provider 实例 → identity 不同 → dedupe 失效
 *  - [chapterIndex] + [pageIndex] 章节 / 章内 page 切换天然让 key 不等
 *  - [w]/[h] 视图尺寸（系统栏切换 / 分屏）
 *  - [bgColor] 日夜模式底色（与 providerId 共同覆盖主题变化）
 */
private data class SimulationIdleKey(
    val providerId: Int,
    val infoVersion: Long,
    val chapterIndex: Int,
    val pageIndex: Int,
    val w: Int,
    val h: Int,
    val bgColor: Int,
)
