package com.morealm.app.ui.widget.image.photo

import android.view.MotionEvent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RotateGestureDetectorTest {

    private var lastDegrees = 0f
    private var callCount = 0

    private val listener = object : OnRotateListener {
        override fun onRotate(degrees: Float, focusX: Float, focusY: Float) {
            lastDegrees = degrees
            callCount++
        }
    }

    @Test
    fun `detector instantiates without crash`() {
        val detector = RotateGestureDetector(listener)
        assertNotNull(detector)
    }

    @Test
    fun `single finger event does not trigger rotate`() {
        val detector = RotateGestureDetector(listener)
        callCount = 0
        val down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 100f, 100f, 0)
        detector.onTouchEvent(down)
        down.recycle()
        assertEquals(0, callCount)
    }
}
