package com.morealm.app.domain.render.canvasrecorder

import android.graphics.Bitmap
import android.graphics.Canvas

/**
 * Bitmap 回退实现：用于 API < 24 或 Picture 不可用的场景。
 * 录制到离屏 Bitmap，回放时直接 drawBitmap。
 */
class BitmapCanvasRecorder : BaseCanvasRecorder() {

    private var bitmap: Bitmap? = null
    private var bitmapCanvas: Canvas? = null

    override val width get() = bitmap?.width ?: -1
    override val height get() = bitmap?.height ?: -1

    override fun beginRecording(width: Int, height: Int): Canvas {
        val bmp = bitmap
        if (bmp != null && bmp.width == width && bmp.height == height && !bmp.isRecycled) {
            bmp.eraseColor(0)
        } else {
            bitmap?.recycle()
            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        }
        val canvas = Canvas(bitmap!!)
        bitmapCanvas = canvas
        return canvas
    }

    override fun endRecording() {
        bitmapCanvas = null
        super.endRecording()
    }

    override fun draw(canvas: Canvas) {
        val bmp = bitmap ?: return
        if (bmp.isRecycled) return
        canvas.drawBitmap(bmp, 0f, 0f, null)
    }

    override fun recycle() {
        super.recycle()
        bitmapCanvas = null
        bitmap?.recycle()
        bitmap = null
    }
}
