package com.morealm.app.domain.render.canvasrecorder

import androidx.annotation.CallSuper

abstract class BaseCanvasRecorder : CanvasRecorder {

    @JvmField
    protected var isDirty = true

    override fun invalidate() {
        isDirty = true
    }

    @CallSuper
    override fun recycle() {
        isDirty = true
    }

    @CallSuper
    override fun endRecording() {
        isDirty = false
    }

    override fun isDirty(): Boolean = isDirty

    override fun isLocked(): Boolean = false

    override fun needRecord(): Boolean = isDirty() && !isLocked()
}
