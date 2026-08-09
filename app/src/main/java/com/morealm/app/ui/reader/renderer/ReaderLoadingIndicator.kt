package com.morealm.app.ui.reader.renderer

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * 阅读器章节加载指示 —— 章 layout 未就绪期间的占位动画（修「点下一章突兀屏闪」）。
 *
 * 设计：
 * - **延迟 150ms 才出现**：邻章预载命中时切章是秒级的，指示器立刻闪一下反而更晃眼；
 *   只有真的长加载（网络取章 / 远章排版）才值得展示。
 * - **淡入**：出现时 200ms fade，避免二次突兀。
 * - 颜色取正文字色半透明，随日夜/主题自适应，不引入新的主题依赖。
 *
 * 必须在 Box 内调用（用 [BoxScope.align] 居中）。
 */
@Composable
fun BoxScope.ReaderLoadingIndicator(textColorArgb: Int) {
    var show by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(150)
        show = true
    }
    val alpha by animateFloatAsState(
        targetValue = if (show) 1f else 0f,
        animationSpec = tween(200),
        label = "readerLoadingAlpha",
    )
    if (show) {
        CircularProgressIndicator(
            modifier = Modifier
                .align(Alignment.Center)
                .size(32.dp)
                .alpha(alpha),
            color = Color(textColorArgb).copy(alpha = 0.45f),
            strokeWidth = 3.dp,
        )
    }
}
