package com.morealm.app.ui.reader.renderer

import androidx.core.graphics.ColorUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NightAuthoredColorAdaptTest {
    private val nightBackground = 0xFF171817.toInt()

    @Test
    fun `day background keeps authored colors byte identical`() {
        val authored = 0xFFE4006F.toInt()
        for (role in AuthoredColorRole.entries) {
            assertEquals(authored, adaptAuthoredColorForReaderBg(authored, 0xFFFFFFFF.toInt(), role))
        }
    }

    @Test
    fun `authored black foreground becomes readable on night background`() {
        val adapted = adaptAuthoredForegroundForReaderBg(0xFF000000.toInt(), nightBackground)
        assertTrue(ColorUtils.calculateContrast(adapted, nightBackground) >= 4.5)
    }

    @Test
    fun `bright authored accents become restrained without losing hue role`() {
        for (authored in listOf(0xFF01AAEB.toInt(), 0xFFE4006F.toInt())) {
            val before = FloatArray(3).also { ColorUtils.colorToHSL(authored, it) }
            val adapted = adaptAuthoredColorForReaderBg(
                authored,
                nightBackground,
                AuthoredColorRole.BACKGROUND,
            )
            val after = FloatArray(3).also { ColorUtils.colorToHSL(adapted, it) }
            assertTrue(after[1] < before[1] * 0.5f)
            assertTrue(after[2] in 0.38f..0.5f)
        }
    }

    @Test
    fun `foreground inside adapted colored pill chooses the higher contrast polarity`() {
        val surface = 0xFF01AAEB.toInt()
        val adaptedSurface = adaptDecorationBgForReaderBg(surface, nightBackground)
        val adaptedText = adaptAuthoredForegroundForReaderBg(
            0xFFFFFFFF.toInt(),
            nightBackground,
            surface,
        )
        val lightCandidate = 0xFFC8C7C0.toInt()
        assertTrue(
            ColorUtils.calculateContrast(adaptedText, adaptedSurface) >=
                ColorUtils.calculateContrast(lightCandidate, adaptedSurface),
        )
    }
}
