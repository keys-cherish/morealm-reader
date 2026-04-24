package com.morealm.app.domain.render.canvasrecorder

import android.graphics.Canvas
import android.graphics.Picture

/**
 * API 24+ 实现：用 Picture 录制绘制命令，回放时零开销。
 */
class PictureCanvasRecorder : BaseCanvasRecorder() {

    private var picture: Picture? = null

    override val width get() = picture?.width ?: -1
    override val height get() = picture?.height ?: -1

    override fun beginRecording(width: Int, height: Int): Canvas {
        if (picture == null) picture = Picture()
        return picture!!.beginRecording(width, height)
    }

    override fun endRecording() {
        picture!!.endRecording()
        super.endRecording()
    }

    override fun draw(canvas: Canvas) {
        picture?.let { canvas.drawPicture(it) }
    }

    override fun recycle() {
        super.recycle()
        picture = null
    }
}
