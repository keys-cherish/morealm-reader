package com.morealm.app.ui.reader.renderer

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material3.Text
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 阅读器进入书时的「加载占位」Composable。
 *
 * ── 为什么需要它 ──
 *
 * 老路径：`rememberLazyListState()` 在数据 ready 前就构造（默认顶部），数据到位后
 * `LaunchedEffect + scrollToItem` 事后纠偏 —— 用户视觉看到「顶部 → 锚点」的瞬移。
 *
 * 新路径：caller 用 `paragraphsReady = chapter.isCompleted && anchorChapterInWindow`
 * gating，false 时挂载本占位（不构造 LazyColumn），true 时挂载 LazyScrollRenderer
 * 并用 `rememberLazyListState(initialIdx, initialOffsetPx)` 让首帧就在锚点处。
 *
 * ── 视觉风格（用户选定）──
 *
 * 极简：和阅读器同色背景 + 居中章名 + 底部细线进度条。切换到正文时零闪烁
 * （颜色一致）。
 *
 * @param bgColor 阅读器背景色（用 [LocalReaderRenderTheme.bgArgb] 转 [Color] 取，
 *        和 LazyScrollRenderer 同色保证切换零闪）
 * @param textColor 文本颜色（章名、副文本）
 * @param chapterTitle 章名（顶层显示）。空字符串时显示"加载中"
 * @param chapterSubtitle 副标题（章号）。可选；为 null 时不显示
 * @param modifier 外部布局约束
 */
@Composable
fun ReaderLoadingCover(
    bgColor: Color,
    textColor: Color,
    chapterTitle: String,
    chapterSubtitle: String? = null,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(bgColor),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (chapterSubtitle != null && chapterSubtitle.isNotBlank()) {
                Text(
                    text = chapterSubtitle,
                    color = textColor.copy(alpha = 0.55f),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Normal,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(8.dp))
            }
            Text(
                text = chapterTitle.ifBlank { "" },
                color = textColor.copy(alpha = 0.85f),
                fontSize = 22.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(40.dp))
            IndeterminateThinProgressBar(
                color = textColor.copy(alpha = 0.45f),
                modifier = Modifier
                    .fillMaxWidth(0.45f)
                    .height(2.dp),
            )
        }
    }
}

/**
 * 极细（2dp）不定进度条。`LinearProgressIndicator` 的精简替代——后者默认 4dp + 圆角胶囊
 * 太"现代化"，破坏阅读器的极简调性。
 *
 * 实现：渐变 brush（透明 → 实色 → 透明）从左滑到右无限循环，肉眼像一道光在轨道上扫。
 */
@Composable
private fun IndeterminateThinProgressBar(
    color: Color,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "loadingScan")
    val progress by transition.animateFloat(
        initialValue = -0.3f,
        targetValue = 1.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "loadingScanProgress",
    )
    Box(
        modifier = modifier
            .background(color.copy(alpha = 0.15f))
            .drawBehind {
                val highlightWidth = size.width * 0.4f
                val startX = progress * size.width - highlightWidth / 2f
                drawRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            Color.Transparent,
                            color,
                            Color.Transparent,
                        ),
                        startX = startX,
                        endX = startX + highlightWidth,
                    ),
                    topLeft = Offset(0f, 0f),
                    size = Size(size.width, size.height),
                )
            },
    )
}
