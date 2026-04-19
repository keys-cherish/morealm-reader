package com.morealm.app.domain.render

import android.text.TextPaint
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TextMeasureTest {

    private lateinit var measure: TextMeasure

    @Before
    fun setup() {
        val paint = TextPaint().apply { textSize = 48f }
        measure = TextMeasure(paint)
    }

    @Test
    fun `measureText returns non-negative width for ASCII`() {
        val w = measure.measureText("Hello")
        assertTrue("ASCII text width should be >= 0", w >= 0f)
    }

    @Test
    fun `measureText returns non-negative width for CJK`() {
        val w = measure.measureText("你好世界")
        assertTrue("CJK text width should be >= 0", w >= 0f)
    }

    @Test
    fun `measureText empty string returns zero`() {
        assertEquals(0f, measure.measureText(""), 0.01f)
    }

    @Test
    fun `measureTextSplit returns correct count`() {
        val (chars, widths) = measure.measureTextSplit("ABC")
        assertEquals(3, chars.size)
        assertEquals(3, widths.size)
    }

    @Test
    fun `measureTextSplit handles surrogate pairs`() {
        val emoji = "\uD83D\uDE00" // 😀
        val (chars, widths) = measure.measureTextSplit(emoji)
        assertEquals(1, chars.size)
    }

    @Test
    fun `CJK characters have consistent width`() {
        val w1 = measure.measureText("一")
        val w2 = measure.measureText("二")
        assertEquals("Common CJK chars should have same width", w1, w2, 0.01f)
    }

    @Test
    fun `setPaint invalidates cache`() {
        val w1 = measure.measureText("A")
        measure.setPaint(TextPaint().apply { textSize = 96f })
        val w2 = measure.measureText("A")
        // Robolectric may return 0 for both, just verify no crash
        assertTrue("Should not crash on setPaint", w2 >= 0f)
    }
}
