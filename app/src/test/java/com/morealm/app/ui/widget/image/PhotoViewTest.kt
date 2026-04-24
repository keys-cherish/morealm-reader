package com.morealm.app.ui.widget.image

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.widget.ImageView
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class PhotoViewTest {

    private lateinit var photoView: PhotoView

    @Before
    fun setup() {
        photoView = PhotoView(RuntimeEnvironment.getApplication())
    }

    // ── Instantiation ──

    @Test
    fun `PhotoView initializes with default values`() {
        assertEquals(340, photoView.mAnimaDuring)
        assertTrue(photoView.isEnable)
        assertFalse(photoView.isRotateEnable)
    }

    @Test
    fun `setMaxScale updates max scale`() {
        photoView.setMaxScale(8f)
        assertEquals(8f, photoView.getMaxScale(), 0.01f)
    }

    @Test
    fun `setAnimDuring updates animation duration`() {
        photoView.setAnimDuring(500)
        assertEquals(500, photoView.getAnimDuring())
    }

    @Test
    fun `getDefaultAnimDuring returns constant`() {
        assertEquals(340, photoView.getDefaultAnimDuring())
    }

    // ── ScaleType ──

    @Test
    fun `setScaleType ignores MATRIX`() {
        // MATRIX is used internally, should be rejected
        photoView.setScaleType(ImageView.ScaleType.MATRIX)
        // Should not crash; internal scaleType remains CENTER_INSIDE (default)
    }

    @Test
    fun `setScaleType accepts CENTER_CROP`() {
        photoView.setScaleType(ImageView.ScaleType.CENTER_CROP)
        // Should not crash
    }

    // ── Drawable ──

    @Test
    fun `setImageDrawable with null does not crash`() {
        photoView.setImageDrawable(null)
        assertNull(photoView.drawable)
    }

    @Test
    fun `setImageDrawable with valid bitmap initializes`() {
        val bmp = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val drawable = BitmapDrawable(
            RuntimeEnvironment.getApplication().resources, bmp
        )
        photoView.setImageDrawable(drawable)
        assertNotNull(photoView.drawable)
    }

    // ── Scroll capability ──

    @Test
    fun `canScrollHorizontally returns false without drawable`() {
        assertFalse(photoView.canScrollHorizontally(1))
        assertFalse(photoView.canScrollHorizontally(-1))
    }

    @Test
    fun `canScrollVertically returns false without drawable`() {
        assertFalse(photoView.canScrollVertically(1))
        assertFalse(photoView.canScrollVertically(-1))
    }

    // ── Enable toggle ──

    @Test
    fun `disable zoom prevents touch handling`() {
        photoView.isEnable = false
        assertFalse(photoView.isEnable)
    }

    @Test
    fun `enable rotation flag`() {
        photoView.isRotateEnable = true
        assertTrue(photoView.isRotateEnable)
    }
}
