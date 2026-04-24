package com.morealm.app.domain.render.canvasrecorder

import android.graphics.Canvas
import android.graphics.Picture
import android.graphics.RenderNode
import android.os.Build
import androidx.annotation.RequiresApi

/**
 * API 29+ 实现：Picture 录制 + RenderNode 硬件加速回放。
 * 硬件加速 Canvas 上用 RenderNode 回放，软件 Canvas 上用 Picture 回放。
 */
@RequiresApi(Build.VERSION_CODES.Q)
class RenderNodeCanvasRecorder : BaseCanvasRecorder() {

    private var renderNode: RenderNode? = null
    private var picture: Picture? = null

    override val width get() = renderNode?.width ?: -1
    override val height get() = renderNode?.height ?: -1

    private fun init() {
        if (renderNode == null) renderNode = RenderNode("PageRecorder")
        if (picture == null) picture = Picture()
    }

    private fun flushRenderNode() {
        val rc = renderNode!!.beginRecording()
        rc.drawPicture(picture!!)
        renderNode!!.endRecording()
    }

    override fun beginRecording(width: Int, height: Int): Canvas {
        init()
        renderNode!!.setPosition(0, 0, width, height)
        return picture!!.beginRecording(width, height)
    }

    override fun endRecording() {
        picture!!.endRecording()
        flushRenderNode()
        super.endRecording()
    }

    override fun draw(canvas: Canvas) {
        if (renderNode == null || picture == null) return
        if (canvas.isHardwareAccelerated) {
            if (!renderNode!!.hasDisplayList()) flushRenderNode()
            canvas.drawRenderNode(renderNode!!)
        } else {
            canvas.drawPicture(picture!!)
        }
    }

    override fun recycle() {
        super.recycle()
        renderNode?.discardDisplayList()
        renderNode = null
        picture = null
    }
}
