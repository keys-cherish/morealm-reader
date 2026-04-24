package com.morealm.app.ui.widget.image

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.view.MotionEvent
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class PhotoViewGestureTest {

    private lateinit var photoView: PhotoView

    @Before
    fun setup() {
        photoView = PhotoView(RuntimeEnvironment.getApplication())
        // Give it a size and a drawable so gesture logic is active
        val bmp = Bitmap.createBitmap(800, 1200, Bitmap.Config.ARGB_8888)
        photoView.setImageDrawable(
            BitmapDrawable(RuntimeEnvironment.getApplication().resources, bmp)
        )
        photoView.layout(0, 0, 1080, 1920)
    }

    private fun tap(x: Float, y: Float) {
        val down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, x, y, 0)
        val up = MotionEvent.obtain(0, 50, MotionEvent.ACTION_UP, x, y, 0)
        photoView.dispatchTouchEvent(down)
        photoView.dispatchTouchEvent(up)
        down.recycle()
        up.recycle()
    }

    @Test
    fun `single tap does not crash`() {
        tap(540f, 960f)
    }

    @Test
    fun `double tap does not crash`() {
        tap(540f, 960f)
        tap(540f, 960f)
    }

    @Test
    fun `dispatchTouchEvent returns true when enabled`() {
        photoView.isEnable = true
        val down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 540f, 960f, 0)
        assertTrue(photoView.dispatchTouchEvent(down))
        down.recycle()
    }

    @Test
    fun `rotate gesture does not crash`() {
        photoView.isRotateEnable = true
        photoView.rotate(45f)
        // Should not throw
    }
}
