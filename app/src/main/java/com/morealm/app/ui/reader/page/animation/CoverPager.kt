package com.morealm.app.ui.reader.page.animation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlin.math.abs

/** 滑入页左侧阴影渐变宽度（px）。Legado CoverPageDelegate 同名常量。*/
private const val COVER_SHADOW_WIDTH = 30f

/** 滑入页左侧阴影最大透明度。 */
private const val COVER_MAX_SHADOW_ALPHA = 0.4f

/**
 * 「覆盖式」翻页 —— 来页从右侧滑入并盖住下面的页面，下面的页面留在原地不动。
 * 参考 Legado [io.legado.app.ui.book.read.page.delegate.CoverPageDelegate]。
 *
 * # 与 [SlidePager] 的区别
 *
 * SLIDE：来页 + 当前页一起向左移
 * COVER：来页向左移，当前页停在原处（被遮住）
 *
 * # 实现要点
 *
 * 1. 仍然用 [HorizontalPager] 让 Compose 处理滑动手势 + 物理学。
 * 2. 在 `graphicsLayer` 里把「outgoing 页」的 `translationX` 抵消 pager 默认平移
 *    （`size.width * offset`），让它视觉上不动。
 * 3. 在 `drawWithContent` 里给「incoming 页」左侧画一道渐变阴影，模拟纸张叠合
 *    的层次感；阴影 alpha 跟随 `pageOffset` 衰减。
 *
 * # 现代实践：snapshotFlow 替代 LaunchedEffect 高频重启
 *
 * 见 [SlidePager] 的 settled-detect 注释。
 */
@Composable
internal fun CoverPager(
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
                            size = Size(COVER_SHADOW_WIDTH, size.height),
                        )
                    }
                }
        ) {
            pageContent(pageIndex)
        }
    }
}
