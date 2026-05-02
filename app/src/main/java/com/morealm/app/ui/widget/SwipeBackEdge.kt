package com.morealm.app.ui.widget

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/**
 * UX-10：从屏幕左缘水平拖动 → 返回。
 *
 * 设计动机：
 *  - Android 13+ 的预测性返回手势（manifest `enableOnBackInvokedCallback="true"`）
 *    在原生支持的设备上已经覆盖了大部分场景，但：
 *      1) 三键导航 + 老版本 Android 没有系统级边缘滑回
 *      2) 部分手机厂商 ROM 的全面屏手势会和应用内手势冲突，预测性返回不一定生效
 *  - 因此在二级页面根 `Modifier` 上挂一个手动手势：从屏幕左 24dp 内开始拖动，
 *    水平拖过 [thresholdDp] 像素 → 调用 [onBack]。
 *
 * 使用约束：
 *  - 不要套在阅读器上 — 阅读器自身有「点击区域翻页」逻辑，水平拖会跟翻页冲突
 *  - 不要套在嵌套 `HorizontalPager` / `LazyRow` 的页面 — 父子手势会互争
 *  - 适合：设置子页、WebDav、书源管理、书籍详情等纯纵向滚动的 stack 页面
 *
 * 实现细节：
 *  - 用 [detectHorizontalDragGestures]：与 LazyColumn 的纵向滚动冲突最小（Compose
 *    的 PointerInput 系统会优先把"主导方向"判定为纵向，垂直手势不会被吃）。
 *  - 起手点必须在屏幕左缘 [edgeWidthDp] 以内，避免误触屏幕中部的水平拖。
 *  - 触发后立即 onBack，不等手指松开 — 跟系统侧边返回的反馈节奏一致。
 */
fun Modifier.swipeBackEdge(
    onBack: () -> Unit,
    edgeWidthDp: Int = 24,
    thresholdDp: Int = 60,
): Modifier = composed {
    val density = LocalDensity.current
    val edgePx = with(density) { edgeWidthDp.dp.toPx() }
    val thresholdPx = with(density) { thresholdDp.dp.toPx() }
    pointerInput(Unit) {
        var startedAtEdge = false
        var totalDx = 0f
        detectHorizontalDragGestures(
            onDragStart = { offset ->
                startedAtEdge = offset.x <= edgePx
                totalDx = 0f
            },
            onHorizontalDrag = { _, dx ->
                if (!startedAtEdge) return@detectHorizontalDragGestures
                totalDx += dx
                if (totalDx > thresholdPx) {
                    startedAtEdge = false  // consume once
                    onBack()
                }
            },
            onDragEnd = { startedAtEdge = false; totalDx = 0f },
            onDragCancel = { startedAtEdge = false; totalDx = 0f },
        )
    }
}
