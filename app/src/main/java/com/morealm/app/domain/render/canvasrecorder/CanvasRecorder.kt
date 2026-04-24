package com.morealm.app.domain.render.canvasrecorder

import android.graphics.Canvas

/**
 * 绘制录制回放接口 — 移植自 Legado。
 * 录制一次绘制命令，后续帧直接回放，避免重复绘制。
 */
interface CanvasRecorder {

    val width: Int
    val height: Int

    fun beginRecording(width: Int, height: Int): Canvas
    fun endRecording()
    fun draw(canvas: Canvas)
    fun invalidate()
    fun recycle()
    fun isDirty(): Boolean
    fun isLocked(): Boolean
    fun needRecord(): Boolean

}
