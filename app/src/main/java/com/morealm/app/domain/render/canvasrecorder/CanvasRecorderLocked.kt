package com.morealm.app.domain.render.canvasrecorder

import android.graphics.Canvas
import java.util.concurrent.locks.ReentrantLock

/**
 * 线程安全的 CanvasRecorder 包装器。
 * 录制和绘制可能在不同线程（如后台渲染线程 vs UI 线程），
 * 用 ReentrantLock 保证不会同时录制和回放。
 */
class CanvasRecorderLocked(
    private val delegate: CanvasRecorder,
) : CanvasRecorder {

    private val lock = ReentrantLock()
    @Volatile
    private var isRecording = false

    override val width get() = delegate.width
    override val height get() = delegate.height

    override fun beginRecording(width: Int, height: Int): Canvas {
        lock.lock()
        isRecording = true
        return delegate.beginRecording(width, height)
    }

    override fun endRecording() {
        try {
            delegate.endRecording()
        } finally {
            isRecording = false
            lock.unlock()
        }
    }

    override fun draw(canvas: Canvas) {
        if (!lock.tryLock()) return // 正在录制，跳过本帧绘制
        try {
            delegate.draw(canvas)
        } finally {
            lock.unlock()
        }
    }

    override fun invalidate() {
        delegate.invalidate()
    }

    override fun recycle() {
        lock.lock()
        try {
            delegate.recycle()
        } finally {
            lock.unlock()
        }
    }

    override fun isDirty(): Boolean = delegate.isDirty()

    override fun isLocked(): Boolean = isRecording || delegate.isLocked()

    override fun needRecord(): Boolean = delegate.needRecord()
}
