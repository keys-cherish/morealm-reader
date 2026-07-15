package com.morealm.app.ui.reader.renderer.scroll

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PageInfoCanvasDrawerTest {

    @Test
    fun `drawer bakes header and footer into page without touching content area`() {
        val bitmap = Bitmap.createBitmap(300, 160, Bitmap.Config.ARGB_8888)
        val snapshot = PageInfoSnapshot(
            chapterIndex = 3,
            chapterTitle = "当前章",
            pageIndex = 1,
            pageCount = 6,
            scrollPercent = 33.3f,
            header = PageInfoLineSnapshot(
                left = PageInfoSlotValue.Text("当前章"),
                center = PageInfoSlotValue.None,
                right = PageInfoSlotValue.Text("10:10"),
            ),
            footer = PageInfoLineSnapshot(
                left = PageInfoSlotValue.Text("2/6"),
                center = PageInfoSlotValue.None,
                right = PageInfoSlotValue.Text("33.3%"),
            ),
            textColorArgb = Color.BLACK,
            backgroundColorArgb = Color.WHITE,
            hasBgImage = false,
            paddingHorizontalPx = 8f,
            topInsetPx = 0f,
            bottomInsetPx = 0f,
            cornerInsetPx = 0f,
            lineHeightPx = 40f,
            textSizePx = 20f,
            density = 1f,
            infoVersion = 1L,
        )

        PageInfoCanvasDrawer().draw(Canvas(bitmap), snapshot, bitmap.width, bitmap.height)

        assertTrue(bitmap.hasNonTransparentPixel(yFrom = 0, yUntil = 40))
        assertTrue(bitmap.hasNonTransparentPixel(yFrom = 120, yUntil = 160))
        assertFalse(bitmap.hasNonTransparentPixel(yFrom = 48, yUntil = 112))
    }

    private fun Bitmap.hasNonTransparentPixel(yFrom: Int, yUntil: Int): Boolean {
        for (y in yFrom until yUntil) {
            for (x in 0 until width) {
                if (Color.alpha(getPixel(x, y)) != 0) return true
            }
        }
        return false
    }
}
