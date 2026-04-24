package com.morealm.app.domain.render.canvasrecorder

import android.graphics.Canvas

/**
 * CanvasRecorder 扩展函数 — 简化录制/回放调用。
 */

/**
 * 如果需要重新录制（dirty 且未锁定），执行 [block] 录制；否则跳过。
 */
inline fun CanvasRecorder.recordIfNeeded(
    width: Int,
    height: Int,
    block: (Canvas) -> Unit,
) {
    if (needRecord()) {
        record(width, height, block)
    }
}

/**
 * 无条件录制：beginRecording → block → endRecording。
 */
inline fun CanvasRecorder.record(
    width: Int,
    height: Int,
    block: (Canvas) -> Unit,
) {
    val canvas = beginRecording(width, height)
    try {
        block(canvas)
    } finally {
        endRecording()
    }
}

/**
 * 如果需要录制则录制，然后回放到目标 Canvas。
 * 这是最常用的一站式调用。
 */
inline fun CanvasRecorder.recordIfNeededThenDraw(
    targetCanvas: Canvas,
    width: Int,
    height: Int,
    block: (Canvas) -> Unit,
) {
    recordIfNeeded(width, height, block)
    draw(targetCanvas)
}
