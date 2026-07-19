package com.morealm.app.domain.render.pageanim

import com.morealm.epub.compat.ChapterBlock
import com.morealm.epub.compat.StructuredChapterContent
import com.morealm.epub.compat.StructuredContentSection
import com.morealm.epub.compat.TextSpan
import com.morealm.epub.css.EpubBackground
import com.morealm.epub.css.EpubBackgroundImage
import com.morealm.epub.css.EpubBackgroundLayer
import com.morealm.epub.css.EpubBackgroundRepeat
import com.morealm.epub.css.EpubBackgroundRepeatMode
import com.morealm.epub.css.EpubBackgroundSize
import com.morealm.epub.render.ScrollChapterLayout
import com.morealm.epub.render.ScrollPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrollBackgroundPageExpanderTest {

    @Test
    fun `纯背景人物页按图片比例完整展开并移除额外标题行`() {
        val content = contentWith(ChapterBlock.Heading(2, listOf(TextSpan("姜尚真"))))
        val expanded = expandBackgroundOnlyScrollPage(
            layout = layout(height = 320f),
            content = content,
            chapterTitle = "  姜尚真",
            pageWidth = 920,
            resolveImageDimensions = { _, _ -> 1080 to 2400 },
        )

        val expectedHeight = 920f * 2400f / 1080f
        assertEquals(expectedHeight, expanded.totalHeight, 0.01f)
        assertEquals(expectedHeight, expanded.pages.single().height, 0.01f)
        assertTrue(expanded.pages.single().lines.isEmpty())
        val region = expanded.pages.single().sectionRegions.single()
        assertEquals(0f, region.top)
        assertEquals(expectedHeight, region.bottom, 0.01f)
        assertEquals(expectedHeight, region.sectionHeight, 0.01f)
    }

    @Test
    fun `背景上存在真实正文时保持原布局`() {
        val original = layout(height = 320f)
        val content = contentWith(ChapterBlock.Paragraph("这是需要正常排版的正文"))

        val result = expandBackgroundOnlyScrollPage(
            layout = original,
            content = content,
            chapterTitle = "章节标题",
            pageWidth = 920,
            resolveImageDimensions = { _, _ -> 1080 to 2400 },
        )

        assertSame(original, result)
    }

    private fun contentWith(block: ChapterBlock): StructuredChapterContent {
        val background = EpubBackground(
            layers = listOf(
                EpubBackgroundLayer(
                    image = EpubBackgroundImage.Url("file:///back14.jpg"),
                    repeat = EpubBackgroundRepeat(
                        x = EpubBackgroundRepeatMode.NO_REPEAT,
                        y = EpubBackgroundRepeatMode.NO_REPEAT,
                    ),
                    size = EpubBackgroundSize.Cover,
                ),
            ),
        )
        return StructuredChapterContent(
            blocks = listOf(block),
            sections = listOf(
                StructuredContentSection(
                    sectionIndex = 0,
                    spineIndex = 10,
                    href = "Text/part10.xhtml",
                    blockStartIndex = 0,
                    blockEndExclusive = 1,
                    background = background,
                ),
            ),
        )
    }

    private fun layout(height: Float): ScrollChapterLayout = ScrollChapterLayout(
        chapterIndex = 15,
        title = "姜尚真",
        pages = listOf(ScrollPage(0, emptyList(), height, 15)),
        totalHeight = height,
        viewWidth = 920,
        styleSignature = "test",
        totalCharCount = 3,
    )
}
