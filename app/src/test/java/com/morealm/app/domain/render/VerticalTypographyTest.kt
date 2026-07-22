package com.morealm.app.domain.render

import android.text.TextPaint
import com.morealm.app.ui.reader.renderer.verticalPresentationGlyph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
class VerticalTypographyTest {

    @Test
    fun `vertical layout follows font size line height and letter spacing`() {
        val textSize = 40f
        val paint = TextPaint().apply {
            this.textSize = textSize
            letterSpacing = 0.25f
        }
        val provider = ChapterProvider(
            viewWidth = 500,
            viewHeight = 320,
            paddingLeft = 20,
            paddingRight = 20,
            paddingTop = 20,
            paddingBottom = 20,
            titlePaint = TextPaint(paint),
            contentPaint = paint,
            textMeasure = TextMeasure(paint),
            paragraphIndent = "",
            titleMode = 2,
            lineSpacingExtra = 1.8f,
        )

        val chapter = provider.layoutChapter(
            title = "",
            content = "天地玄黄宇宙洪荒日月盈昃辰宿列张寒来暑往秋收冬藏",
            chapterIndex = 0,
            readingDirection = ReadingDirection.VERTICAL_RL,
        )
        val lines = requireNotNull(chapter.getPage(0)).lines
        assertTrue(lines.size >= 2)
        val glyphs = lines.first().columns.filterIsInstance<TextColumn>()
        assertTrue(glyphs.size >= 2)
        assertEquals(textSize * 1.25f, glyphs[1].start - glyphs[0].start, 0.5f)
        assertEquals(textSize * 1.8f, abs(lines[1].columnLeftX - lines[0].columnLeftX), 0.5f)
    }

    @Test
    fun `vertical punctuation uses unicode presentation forms`() {
        assertEquals("︐", verticalPresentationGlyph("，"))
        assertEquals("︒", verticalPresentationGlyph("。"))
        assertEquals("︙", verticalPresentationGlyph("……".take(1)))
        assertEquals("︽", verticalPresentationGlyph("《"))
        assertEquals("︾", verticalPresentationGlyph("》"))
        assertEquals("︱", verticalPresentationGlyph("—"))
        assertEquals("文", verticalPresentationGlyph("文"))
    }
}
