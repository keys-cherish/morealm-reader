package com.morealm.app.ui.reader

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

internal data class CatalogScrollbarMetrics(
    val thumbSizeFraction: Float,
    val thumbOffsetFraction: Float,
    val maxFirstVisibleItemIndex: Int,
)

/**
 * 把 LazyColumn 的离散 item 位置投影为滚动条比例。
 *
 * 目录行高并非严格一致（关联书标题、长标题等），因此 UI 层先用当前可见项平均高度估算
 * viewport 覆盖的 item 数；这里保持纯函数，集中处理短列表隐藏与越界钳制。
 */
internal fun calculateCatalogScrollbarMetrics(
    totalItemsCount: Int,
    visibleItemsCount: Float,
    firstVisibleItemIndex: Int,
    firstVisibleItemScrollFraction: Float = 0f,
): CatalogScrollbarMetrics? {
    if (totalItemsCount <= 0 || !visibleItemsCount.isFinite() || visibleItemsCount <= 0f) {
        return null
    }

    val boundedVisibleItems = visibleItemsCount.coerceAtMost(totalItemsCount.toFloat())
    val scrollableItems = totalItemsCount - boundedVisibleItems
    if (scrollableItems <= 0f) return null

    val currentItemPosition = (
        firstVisibleItemIndex.coerceAtLeast(0) +
            firstVisibleItemScrollFraction.coerceIn(0f, 1f)
        ).coerceAtMost(scrollableItems)

    return CatalogScrollbarMetrics(
        thumbSizeFraction = (boundedVisibleItems / totalItemsCount).coerceIn(0f, 1f),
        thumbOffsetFraction = (currentItemPosition / scrollableItems).coerceIn(0f, 1f),
        maxFirstVisibleItemIndex = scrollableItems.roundToInt().coerceAtLeast(1),
    )
}

/** 章节目录专用滚动条；右侧手势带支持点击与拖动快速跳转。 */
@Composable
internal fun ReaderCatalogScrollbar(
    state: LazyListState,
    modifier: Modifier = Modifier,
    trackColor: Color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.14f),
    thumbColor: Color = MaterialTheme.colorScheme.primary.copy(alpha = 0.72f),
) {
    val metrics by remember(state) {
        derivedStateOf {
            val layoutInfo = state.layoutInfo
            val visibleItems = layoutInfo.visibleItemsInfo
            val viewportHeight = layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset
            val averageItemHeight = visibleItems
                .map { it.size }
                .average()
                .toFloat()

            if (visibleItems.isEmpty() || viewportHeight <= 0 || averageItemHeight <= 0f) {
                null
            } else {
                calculateCatalogScrollbarMetrics(
                    totalItemsCount = layoutInfo.totalItemsCount,
                    visibleItemsCount = viewportHeight / averageItemHeight,
                    firstVisibleItemIndex = state.firstVisibleItemIndex,
                    firstVisibleItemScrollFraction =
                        state.firstVisibleItemScrollOffset / averageItemHeight,
                )
            }
        }
    }
    val currentMetrics by rememberUpdatedState(metrics)
    val scope = rememberCoroutineScope()
    val scrollJob = remember { mutableStateOf<Job?>(null) }

    if (metrics == null) return

    fun scrollToPointer(pointerY: Float, trackHeight: Float) {
        val current = currentMetrics ?: return
        if (trackHeight <= 0f) return
        val target = (
            (pointerY / trackHeight).coerceIn(0f, 1f) * current.maxFirstVisibleItemIndex
            ).roundToInt()
        scrollJob.value?.cancel()
        scrollJob.value = scope.launch { state.scrollToItem(target) }
    }

    Canvas(
        modifier = modifier
            .width(20.dp)
            .fillMaxHeight()
            .semantics { contentDescription = "章节目录滚动条" }
            .pointerInput(state) {
                detectTapGestures { offset -> scrollToPointer(offset.y, size.height.toFloat()) }
            }
            .pointerInput(state) {
                detectDragGestures(
                    onDragStart = { offset -> scrollToPointer(offset.y, size.height.toFloat()) },
                    onDrag = { change, _ ->
                        change.consume()
                        scrollToPointer(change.position.y, size.height.toFloat())
                    },
                    onDragCancel = { scrollJob.value?.cancel() },
                )
            },
    ) {
        val current = currentMetrics ?: return@Canvas
        val trackWidth = 3.dp.toPx()
        val trackX = size.width - 7.dp.toPx() - trackWidth
        val radius = CornerRadius(trackWidth / 2f, trackWidth / 2f)
        drawRoundRect(
            color = trackColor,
            topLeft = Offset(trackX, 0f),
            size = Size(trackWidth, size.height),
            cornerRadius = radius,
        )

        val thumbHeight = (size.height * current.thumbSizeFraction)
            .coerceAtLeast(28.dp.toPx())
            .coerceAtMost(size.height)
        val thumbTop = (size.height - thumbHeight) * current.thumbOffsetFraction
        drawRoundRect(
            color = thumbColor,
            topLeft = Offset(trackX, thumbTop),
            size = Size(trackWidth, thumbHeight),
            cornerRadius = radius,
        )
    }
}
