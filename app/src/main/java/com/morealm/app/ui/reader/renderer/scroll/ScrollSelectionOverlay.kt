package com.morealm.app.ui.reader.renderer.scroll

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.morealm.epub.render.ScrollChapterLayout
import com.morealm.epub.render.findColumnAt
import kotlinx.coroutines.delay

/**
 * 选区交互层 —— 在 [ScrollCanvasRenderer] 之上覆盖。
 *
 * 职责：
 * 1. **长按选词**：pointerInput.detectTapGestures.onLongPress 触发 [handleLongPress]
 * 2. **取消选区**：选区 active 时单击 → 取消
 * 3. **handle 自绘**：选区 active 时在起末 cp 位置画两个圆点 handle
 * 4. **handle drag**：handle 接 detectDragGestures，drag 时调 [handleHandleDrag]
 * 5. **自动滚动**（M4.4）：handle drag 到 view-local y 接近屏顶 / 屏底时启动
 *    LaunchedEffect 每 16ms scrollBy ± 20px；松手停止；滚动期间选区端点跟随更新
 *
 * 坐标转换：pointerInput 接 view-local (x, y)；y → yInChapter = y + pixelOffsetProvider()。
 *
 * 事件透传：选区 inactive 时只 detectTapGestures（不消费 down event），底层
 * ScrollCanvasRenderer 的 scrollable 正常拿到 down 做 fling 检测。
 *
 * @param selection 当前选区状态
 * @param onSelectionChange 选区变更回调
 * @param layout cur 章 layout
 * @param pixelOffsetProvider lambda 返当前 pixelOffset
 * @param scrollableState scroll state（自动滚动用；null 时禁用自动滚动）
 * @param viewportHeightProvider lambda 返视口高度（自动滚动 y 边界判定用）
 */
@Composable
fun ScrollSelectionOverlay(
    selection: ScrollSelectionState,
    onSelectionChange: (ScrollSelectionState) -> Unit,
    layout: ScrollChapterLayout,
    pixelOffsetProvider: () -> Float,
    scrollableState: ScrollableState? = null,
    viewportHeightProvider: () -> Int = { 0 },
    modifier: Modifier = Modifier,
) {
    val onSelectionChangeUpdated by rememberUpdatedState(onSelectionChange)
    val pixelOffsetUpdated by rememberUpdatedState(pixelOffsetProvider)
    val viewportHeightUpdated by rememberUpdatedState(viewportHeightProvider)

    // M4.4 自动滚动方向：-1=向上 / 0=停 / 1=向下
    var autoScrollDir by remember { mutableIntStateOf(0) }
    // 当前 drag handle 的 view-local 位置（autoScroll loop 内重发 onHandleDrag 用）
    var lastDragX by remember { mutableFloatStateOf(0f) }
    var lastDragYInView by remember { mutableFloatStateOf(0f) }
    var lastDragSide by remember { mutableFloatStateOf(0f) }  // 编码：0=START, 1=END

    // autoScroll loop：方向非 0 + scrollState 注入时启动；每 16ms scrollBy + 重算
    // selection 端点（pixelOffset 已变，view-local y 不变 → chapter y 跟着变 → endCp 更新）
    LaunchedEffect(autoScrollDir, scrollableState) {
        if (autoScrollDir == 0 || scrollableState == null) return@LaunchedEffect
        while (autoScrollDir != 0) {
            scrollableState.scrollBy(autoScrollDir * AUTO_SCROLL_STEP_PX)
            // 重发当前 handle 位置触发 selection 扩展（chapter y = view-local y + 新 pixelOffset）
            val newYInChapter = lastDragYInView + pixelOffsetUpdated()
            val side = if (lastDragSide < 0.5f) ScrollHandleSide.START else ScrollHandleSide.END
            val updated = handleHandleDrag(selection, layout, side, lastDragX, newYInChapter)
            onSelectionChangeUpdated(updated)
            delay(AUTO_SCROLL_INTERVAL_MS)
        }
    }

    // 注意：本 Overlay Box 不再挂 fullscreen pointerInput（M4-revive 修拦截 down 问题）。
    // 长按选词由 ScrollCanvasRenderer 内部的 detectTapGestures(onLongPress) 触发，
    // 由 Host 转发到本 Overlay 的 onSelectionChange。
    // 取消选区由 ScrollCanvasRenderer 的 onTap 间接触发（Host 内逻辑：tap 时若选区 active 则取消 + 不切 controls）。
    // 本 Overlay 仅负责：画 handle + handle drag detection（handle 子 Composable 自己有 pointerInput
    // 仅在 24dp 触发区内消费事件，不影响整屏 scroll）。
    Box(modifier.fillMaxSize()) {
        if (selection.isActive && selection.chapterIndex == layout.chapterIndex) {
            SelectionHandles(
                selection = selection,
                layout = layout,
                pixelOffsetProvider = pixelOffsetUpdated,
                onHandleDrag = { side, xInChapter, yInChapter, yInView ->
                    // 普通 drag：直接更新 selection 端点
                    val updated = handleHandleDrag(selection, layout, side, xInChapter, yInChapter)
                    onSelectionChangeUpdated(updated)
                    // 自动滚动方向检测：view-local y 距离屏顶 / 屏底阈值内启动
                    val vh = viewportHeightUpdated()
                    lastDragX = xInChapter
                    lastDragYInView = yInView
                    lastDragSide = if (side == ScrollHandleSide.START) 0f else 1f
                    autoScrollDir = when {
                        vh == 0 -> 0
                        yInView < AUTO_SCROLL_EDGE_PX -> -1
                        yInView > vh - AUTO_SCROLL_EDGE_PX -> 1
                        else -> 0
                    }
                },
                onDragEnd = { autoScrollDir = 0 },
            )
        }
    }
}

/**
 * 选区起末两个 handle 自绘 + drag 检测。
 *
 * 视觉：handle = 12dp 直径蓝色圆点，从选区底部下方偏移开始（避免遮挡文字）。
 * drag 检测半径 ≈ 24dp（比视觉大让手指更易抓）。
 *
 * @param onHandleDrag (side, xInChapter, yInChapter, yInView) → 调用方更新 selection
 *                     + 检测自动滚动边界（yInView 是 view-local 坐标，用于边界判定）
 * @param onDragEnd 抬手回调（清自动滚动方向）
 */
@Composable
private fun SelectionHandles(
    selection: ScrollSelectionState,
    layout: ScrollChapterLayout,
    pixelOffsetProvider: () -> Float,
    onHandleDrag: (ScrollHandleSide, xInChapter: Float, yInChapter: Float, yInView: Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    val cpRange = selection.cpRange
    val startHit = layout.findColumnAt(cpRange.first) ?: return
    val endHit = layout.findColumnAt(cpRange.last) ?: return
    val startCol = startHit.column ?: return
    val endCol = endHit.column ?: return

    val density = LocalDensity.current
    val handleSizePx = with(density) { 12.dp.toPx() }
    val touchRadiusPx = with(density) { 24.dp.toPx() }

    // chapter-local x（column.x 在 0..visibleWidth 内）→ view-x 需要加 layout.paddingLeft
    // 修复用户反馈"handle dot 与选区背景位置不齐"：ChapterPaneCanvas 用 translate(paddingLeft)
    // 把 BG / 文字向右挪 paddingLeft 后绘制；handle 之前直接用 chapter-x 当 view-x 渲染
    // → 偏左 paddingLeft (141 px)。
    val padL = layout.paddingLeft.toFloat()
    val startXInView = startCol.start + padL
    val endXInView = endCol.end + padL
    val startYInChapter = computeLineBottomYInChapterPublic(layout, cpRange.first)
    val endYInChapter = computeLineBottomYInChapterPublic(layout, cpRange.last)
    val offsetY = pixelOffsetProvider()
    androidx.compose.runtime.LaunchedEffect(cpRange) {
        com.morealm.app.core.log.AppLog.info(
            "ScrollSelection",
            "SelectionHandles cpRange=$cpRange anchorInBox=${selection.anchorInBox} " +
                "startCol(x=${startCol.start}..${startCol.end} char='${startCol.charData}') " +
                "endCol(x=${endCol.start}..${endCol.end} char='${endCol.charData}') " +
                "startXInView=$startXInView endXInView=$endXInView " +
                "startYInChapter=$startYInChapter endYInChapter=$endYInChapter pixelOffset=$offsetY padL=$padL",
        )
    }

    HandleDot(
        xInView = startXInView,
        yInView = startYInChapter - offsetY + handleSizePx / 2f,
        sizePx = handleSizePx,
        touchRadiusPx = touchRadiusPx,
        onDrag = { dragPos ->
            // dragPos.x 是 view-x（HandleDot 初始位置 = xInView，delta 加在 view 空间），
            // 转 chapter-x 才能交给 handleHandleDrag 反查 column。
            val xInChapter = dragPos.x - padL
            val yInChapter = dragPos.y + pixelOffsetProvider()
            onHandleDrag(ScrollHandleSide.START, xInChapter, yInChapter, dragPos.y)
        },
        onDragEnd = onDragEnd,
    )
    HandleDot(
        xInView = endXInView,
        yInView = endYInChapter - offsetY + handleSizePx / 2f,
        sizePx = handleSizePx,
        touchRadiusPx = touchRadiusPx,
        onDrag = { dragPos ->
            val xInChapter = dragPos.x - padL
            val yInChapter = dragPos.y + pixelOffsetProvider()
            onHandleDrag(ScrollHandleSide.END, xInChapter, yInChapter, dragPos.y)
        },
        onDragEnd = onDragEnd,
    )
}

/**
 * 单个 handle 圆点 + drag 检测。
 *
 * 圆点用 Canvas 画 12dp 直径；touchRadiusPx (24dp) 是 detectDragGestures 可触发区。
 * drag 时 [onDrag] 回调拿绝对 view-local 位置；松手时 [onDragEnd] 回调。
 */
@Composable
private fun HandleDot(
    xInView: Float,
    yInView: Float,
    sizePx: Float,
    touchRadiusPx: Float,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
) {
    val density = LocalDensity.current
    val touchSize = with(density) { (touchRadiusPx * 2).toDp() }
    // 关键修复（用户反馈"一选一大块"）：handle 位置（xInView/yInView）随 selection.cpRange
    // 变化而变化 —— 之前 pointerInput key 用 (xInView, yInView) → 每次 selection 端点变就
    // 重启 detectDragGestures → 用户拖动中途 detector 被销毁重建，drag 上下文丢失，
    // 表现为"拖一下就跳一大块"或"drag 失效"。
    //
    // 修：pointerInput key 用 Unit（detector 不重启），用 rememberUpdatedState 让闭包
    // 读到最新 anchor。drag 起点 onDragStart 时取 latest，后续 += delta 累加。
    // 与 V1 CursorHandle 同款思路 (TextSelection.kt L915-950)。
    val anchorX by rememberUpdatedState(xInView)
    val anchorY by rememberUpdatedState(yInView)
    val onDragUpdated by rememberUpdatedState(onDrag)
    val onDragEndUpdated by rememberUpdatedState(onDragEnd)

    Box(
        Modifier
            .offset {
                IntOffset(
                    x = (xInView - touchRadiusPx).toInt(),
                    y = (yInView - touchRadiusPx).toInt(),
                )
            }
            .size(touchSize)
            .pointerInput(Unit) {
                var draggedTo = Offset(anchorX, anchorY)
                detectDragGestures(
                    onDragStart = { _ ->
                        // 取最新 anchor 作 drag 起点，不依赖 pointerInput 重启
                        draggedTo = Offset(anchorX, anchorY)
                    },
                    onDragEnd = { onDragEndUpdated() },
                    onDragCancel = { onDragEndUpdated() },
                    onDrag = { change, delta ->
                        change.consume()
                        draggedTo += delta
                        onDragUpdated(draggedTo)
                    },
                )
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(
                color = Color(0xFF1976D2),
                radius = sizePx / 2f,
                center = Offset(touchRadiusPx, touchRadiusPx),
            )
        }
    }
}

private const val AUTO_SCROLL_STEP_PX: Float = 20f
private const val AUTO_SCROLL_INTERVAL_MS: Long = 16L
private const val AUTO_SCROLL_EDGE_PX: Float = 80f
