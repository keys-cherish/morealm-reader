package com.morealm.app.ui.reader.page.animation

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import com.morealm.app.ui.reader.page.BitmapPageContent
import com.morealm.app.ui.reader.page.PageBitmapProvider
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

/**
 * 横向 + 纵向「滑动翻页」分支。
 *
 * 抽出动机：原 [PageAnimationPagers] 单文件 600+ 行塞了所有翻页路径
 * （SLIDE / SLIDE_VERTICAL / COVER / SIMULATION / SCROLL），改任一种都要在巨型
 * 上下文里翻找；按动画类型一类一文件后，每种动画的代码、注释、相关常量都集中
 * 在一个 ~50 行文件，单测/调试范围立刻收敛。
 *
 * 行为对齐参照实现的 SlidePageDelegate：当前/相邻页一起平移，不带阴影。
 *
 * 这两个 pager 共享同一份 settled-detect 模板，共在一个文件方便对照纵向变体
 * 的差异（仅 [HorizontalPager] vs [VerticalPager]）。
 *
 * # 现代实践：snapshotFlow 替代 LaunchedEffect 高频重启
 *
 * 旧写法 `LaunchedEffect(pagerState.currentPage, pagerState.isScrollInProgress) { ... }`
 * 在 fling 衰减期 isScrollInProgress 可能 `true→false→true` 反复抖动 → effect
 * 反复重启，每次重新构造一个跑一帧就被取消的协程。
 *
 * 新写法用 [snapshotFlow] 把状态变化外包给 Compose 的 snapshot 系统，
 * `.distinctUntilChanged()` + `.filter { !it }` 显式表达「仅在 idle 边沿触发」，
 * `.map(currentPage).distinctUntilChanged()` 进一步去重连续相同 page。整条
 * Flow 链路在后台跑，不进重组路径。
 *
 * 参考 [com.morealm.app.ui.reader.renderer.LazyScrollRenderer] 的 5 路 snapshotFlow。
 */
@Composable
internal fun SlidePager(
    pagerState: PagerState,
    modifier: Modifier = Modifier,
    onPageSettled: (Int) -> Unit = {},
    pageContent: @Composable (Int) -> Unit,
    /**
     * 视觉上是否反向排列页面。横排默认 false（左→右）；竖排右起 (vertical_rl)
     * 走 true（右→左）。pagerState 索引语义不变（0 = 第一页 / 阅读起点），仅
     * 影响 pager 在视觉上把哪一页放在屏幕右侧。
     *
     * 关键点：reverseLayout=true 下 [HorizontalPager] 的 fling / scroll 物理仍然
     * 工作 —— 用户左滑（手指向左）= currentPage++ = 进入阅读顺序的下一页（视觉上
     * 从右侧滑入），符合日漫 / 竖排古籍的翻页直觉。
     *
     * 放在 pageContent 后是为了不破坏现有 4 个横排路径的位置参数调用 —— 那些调用
     * 都是 (pagerState, modifier, onPageSettled, pageContent) 4 位置参数，加默认
     * 在前面会让位置错位。新的调用方（VerticalReaderView）必须用命名参数传 true。
     */
    reverseLayout: Boolean = false,
    /**
     * 是否允许用户拖动翻页。CanvasRenderer 横排路径走 false（自管手势），
     * 竖排 [com.morealm.app.ui.reader.vertical.VerticalReaderView] 走 true 让 pager
     * 直接处理拖动 —— 否则用户只能 tap 翻页。
     */
    userScrollEnabled: Boolean = false,
    /**
     * **P3-3c**：动画↔渲染契约线。非 null 时本 pager 用 [BitmapPageContent] 渲染
     * 每页（异步 load bitmap + Image），跳过 [pageContent] Composable 路径；为 null
     * 时维持旧 [pageContent] 行为。当前 caller (CanvasRenderer / VerticalReaderView)
     * 都不传，默认走老路径，零行为变化。P3-5 起 caller 才会传真正的 provider。
     */
    bitmapProvider: PageBitmapProvider? = null,
) {
    // rememberUpdatedState：LaunchedEffect 的 key 只有 pagerState（共享永不变），
    // 跨章时 effect 不重启；闭包里直接 collect(onPageSettled) 会冻在旧 lambda ——
    // 旧 lambda 持有的是跨章前的 coordinator 实例，settled 事件被写进死 coord，
    // 触发"章号正确但页码/内容错位"的一次性视觉 glitch。见 CoverPager 同位修复。
    val currentOnPageSettled = rememberUpdatedState(onPageSettled)
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.isScrollInProgress }
            .distinctUntilChanged()
            .filter { !it }                            // idle 边沿
            .map { pagerState.currentPage }
            .distinctUntilChanged()                    // 连续 settle 同一页时去重
            .collect { currentOnPageSettled.value(it) }
    }
    HorizontalPager(
        state = pagerState,
        modifier = modifier.fillMaxSize(),
        userScrollEnabled = userScrollEnabled,
        reverseLayout = reverseLayout,
    ) { pageIndex ->
        // Default HorizontalPager already does slide — both pages move together.
        // 这正好等价参照实现 SlidePageDelegate 的行为。
        if (bitmapProvider != null) {
            BitmapPageContent(bitmapProvider, pageIndex)
        } else {
            pageContent(pageIndex)
        }
    }
}

/**
 * 上下方向滑动翻页。和 [SlidePager] 仅差 [VerticalPager] 一项；其他行为一致。
 *
 * settled-detect 链路同 [SlidePager]（snapshotFlow + idle 边沿）。
 */
@Composable
internal fun VerticalSlidePager(
    pagerState: PagerState,
    modifier: Modifier = Modifier,
    onPageSettled: (Int) -> Unit = {},
    pageContent: @Composable (Int) -> Unit,
    /**
     * **P3-3c**：同 [SlidePager.bitmapProvider]。非 null 时改走 [BitmapPageContent]。
     */
    bitmapProvider: PageBitmapProvider? = null,
) {
    val currentOnPageSettled = rememberUpdatedState(onPageSettled)
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.isScrollInProgress }
            .distinctUntilChanged()
            .filter { !it }
            .map { pagerState.currentPage }
            .distinctUntilChanged()
            .collect { currentOnPageSettled.value(it) }
    }
    VerticalPager(
        state = pagerState,
        modifier = modifier.fillMaxSize(),
        userScrollEnabled = false,
    ) { pageIndex ->
        if (bitmapProvider != null) {
            BitmapPageContent(bitmapProvider, pageIndex)
        } else {
            pageContent(pageIndex)
        }
    }
}
