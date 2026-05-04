package com.morealm.app.ui.reader.page.animation

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
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
 * 行为对齐 Legado 的 SlidePageDelegate：当前/相邻页一起平移，不带阴影。
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
) {
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.isScrollInProgress }
            .distinctUntilChanged()
            .filter { !it }                            // idle 边沿
            .map { pagerState.currentPage }
            .distinctUntilChanged()                    // 连续 settle 同一页时去重
            .collect(onPageSettled)
    }
    HorizontalPager(
        state = pagerState,
        modifier = modifier.fillMaxSize(),
        userScrollEnabled = false,
    ) { pageIndex ->
        // Default HorizontalPager already does slide — both pages move together.
        // 这正好等价 Legado SlidePageDelegate 的行为。
        pageContent(pageIndex)
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
) {
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.isScrollInProgress }
            .distinctUntilChanged()
            .filter { !it }
            .map { pagerState.currentPage }
            .distinctUntilChanged()
            .collect(onPageSettled)
    }
    VerticalPager(
        state = pagerState,
        modifier = modifier.fillMaxSize(),
        userScrollEnabled = false,
    ) { pageIndex ->
        pageContent(pageIndex)
    }
}
