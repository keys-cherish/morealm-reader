package com.morealm.app.ui.reader.page.animation

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * 「滚动模式」分支的老路径 —— 把 pages 平铺到 [LazyColumn]，每页 fillMaxSize
 * 高度。基于 PagerState 构造的简化版滚动渲染，灵感来自 Legado 的 ScrollPageDelegate。
 *
 * # 与 [com.morealm.app.ui.reader.renderer.LazyScrollRenderer] / [com.morealm.app.ui.reader.renderer.ScrollRenderer] 的区分
 *
 * 这是 [AnimatedPageReader] 内部的「页级滚动」分支，仍以 page（章内分页）为单位
 * 滚动；而 `LazyScrollRenderer` 是段落级的章间无缝瀑布流（contentType 复用 +
 * snapshotFlow）。两者面向不同体验场景：
 *
 * - 本 pager：page 为最小单位，跨章必须切 chapter；最简单的连续滚阅读。
 * - LazyScrollRenderer：段落为最小单位，prev/cur/next 三章扁平共一窗，跨章无缝。
 *
 * MoRealm 的 SCROLL 模式现在固定走 [com.morealm.app.ui.reader.renderer.LazyScrollRenderer]
 * 段落级 LazyColumn 瀑布流（老 ScrollRenderer 已下线），本 pager 仅做 PagerState 衔接
 * 的兼容入口。
 *
 * # 现代实践：snapshotFlow 替代双向 LaunchedEffect 同步
 *
 * 旧实现两个 `LaunchedEffect` 互相同步 listState ↔ pagerState，存在 fling 期间
 * **自激励**风险：用户滚动 → firstVisibleItemIndex 变 → effect 重启 →
 * pagerState.scrollToPage → pagerState.currentPage 变 → 反向 effect 重启 →
 * listState.scrollToItem → 又触发第一路 effect …
 *
 * 改写要点：
 *
 * 1. 两路都用 `snapshotFlow + distinctUntilChanged` —— Compose 自带的状态变化
 *    去重，相同值连续写不重复触发。
 * 2. **反向路径加 `!listState.isScrollInProgress` 守卫** —— 用户正在主动滚动时，
 *    pagerState 的更新都来自正向路径自己的回写；此时反向路径不能再去 scrollToItem，
 *    否则会抢走用户拖动 listState 的控制权。
 * 3. 正向路径的 `idx != pagerState.currentPage` 是天然的循环终止条件：写过一次后
 *    第二次 collect 就 short-circuit。
 */
@Composable
internal fun ScrollPager(
    pagerState: PagerState,
    modifier: Modifier = Modifier,
    pageContent: @Composable (Int) -> Unit,
) {
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = pagerState.currentPage)

    // 正向同步：listState → pagerState。snapshotFlow 把 firstVisibleItemIndex 的变化
    // 串成 Flow，distinctUntilChanged 去掉同值重复（fling 期间 Compose 重组多次但
    // 索引可能没变）。
    LaunchedEffect(pagerState, listState) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { idx ->
                if (idx != pagerState.currentPage) {
                    pagerState.scrollToPage(idx)
                }
            }
    }

    // 反向同步：pagerState → listState。仅响应「外部代码」改 pagerState（如目录跳转），
    // 用户主动滚动时由正向路径回写——此时跳过反向，避免抢走用户控制。
    LaunchedEffect(pagerState, listState) {
        snapshotFlow { pagerState.currentPage }
            .distinctUntilChanged()
            .collect { page ->
                if (page != listState.firstVisibleItemIndex && !listState.isScrollInProgress) {
                    listState.scrollToItem(page)
                }
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
