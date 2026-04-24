package com.morealm.app.ui.widget.image.photo

import android.graphics.PointF
import android.graphics.RectF
import android.widget.ImageView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class InfoTest {

    @Test
    fun `Info stores all fields correctly`() {
        val rect = RectF(10f, 20f, 300f, 400f)
        val img = RectF(0f, 0f, 200f, 300f)
        val widget = RectF(0f, 0f, 1080f, 1920f)
        val base = RectF(0f, 0f, 200f, 300f)
        val center = PointF(540f, 960f)

        val info = Info(rect, img, widget, base, center, 1.5f, 90f, ImageView.ScaleType.CENTER_INSIDE)

        assertEquals(10f, info.mRect.left, 0.01f)
        assertEquals(20f, info.mRect.top, 0.01f)
        assertEquals(300f, info.mRect.right, 0.01f)
        assertEquals(400f, info.mRect.bottom, 0.01f)
        assertEquals(0f, info.mImgRect.left, 0.01f)
        assertEquals(200f, info.mImgRect.right, 0.01f)
        assertEquals(1080f, info.mWidgetRect.right, 0.01f)
        assertEquals(1920f, info.mWidgetRect.bottom, 0.01f)
        assertEquals(200f, info.mBaseRect.right, 0.01f)
        assertEquals(540f, info.mScreenCenter.x, 0.01f)
        assertEquals(960f, info.mScreenCenter.y, 0.01f)
        assertEquals(1.5f, info.mScale, 0.01f)
        assertEquals(90f, info.mDegrees, 0.01f)
        assertEquals(ImageView.ScaleType.CENTER_INSIDE, info.mScaleType)
    }

    @Test
    fun `Info with zero scale`() {
        val info = Info(RectF(), RectF(), RectF(), RectF(), PointF(), 0f, 0f, null)
        assertEquals(0f, info.mScale, 0.01f)
        assertNull(info.mScaleType)
    }

    @Test
    fun `Info fields are mutable`() {
        val info = Info(RectF(), RectF(), RectF(), RectF(), PointF(), 1f, 0f, null)
        info.mScale = 3f
        info.mDegrees = 180f
        assertEquals(3f, info.mScale, 0.01f)
        assertEquals(180f, info.mDegrees, 0.01f)
    }
}
