package com.morealm.app.domain.render.canvasrecorder

import android.os.Build

/**
 * 根据 API 级别选择最优 CanvasRecorder 实现。
 * API 29+: RenderNode 硬件加速回放
 * API 24+: Picture 录制回放
 * API < 24: Bitmap 回退
 *
 * @param locked 是否需要线程安全包装（跨线程录制/绘制时使用）
 */
object CanvasRecorderFactory {

    fun create(locked: Boolean = false): CanvasRecorder {
        val recorder = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> RenderNodeCanvasRecorder()
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.N -> PictureCanvasRecorder()
            else -> BitmapCanvasRecorder()
        }
        return if (locked) CanvasRecorderLocked(recorder) else recorder
    }
}
