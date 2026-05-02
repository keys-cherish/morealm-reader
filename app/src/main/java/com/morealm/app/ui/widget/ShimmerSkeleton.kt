package com.morealm.app.ui.widget

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp

/**
 * 通用 Shimmer 占位渐变。返回一个 [Brush] 给 [Modifier.background] 用。
 * Shimmer 在 1.1s 一个周期内从左移到右，3 段渐变让"亮带"扫过灰底。
 *
 * 用法：
 * ```
 * Box(modifier = Modifier.size(80.dp).clip(shape).background(shimmerBrush()))
 * ```
 *
 * 设计要点：
 *  - 颜色取自 `surfaceVariant` 系，跟实际书架卡片底色接近，过渡到真实内容时不闪
 *  - 单个 [rememberInfiniteTransition]：一个屏幕里多块骨架共用一条时间线，节省一次性
 *    分配多个 transition 的开销
 */
@Composable
fun shimmerBrush(): Brush {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val translate by transition.animateFloat(
        initialValue = -500f,
        targetValue = 1500f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmerTranslate",
    )
    val base = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    val highlight = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f)
    return Brush.linearGradient(
        colors = listOf(base, highlight, base),
        start = Offset(translate, 0f),
        end = Offset(translate + 400f, 400f),
    )
}

/**
 * 书架网格骨架屏：占满父容器，画 3 列 × rowCount 行的卡片骨架。
 * 真实书籍未加载完时替代 spinner，让等待时间显得更短（感知速度提升）。
 */
@Composable
fun ShelfGridSkeleton(modifier: Modifier = Modifier, rowCount: Int = 6) {
    val brush = shimmerBrush()
    val placeholders = remember(rowCount) { List(rowCount * 3) { it } }
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        items(placeholders) {
            Column {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(0.72f)
                        .clip(MaterialTheme.shapes.medium)
                        .background(brush)
                )
                Spacer(Modifier.height(6.dp))
                Box(
                    Modifier
                        .width(70.dp)
                        .height(12.dp)
                        .clip(MaterialTheme.shapes.small)
                        .background(brush)
                )
            }
        }
    }
}
