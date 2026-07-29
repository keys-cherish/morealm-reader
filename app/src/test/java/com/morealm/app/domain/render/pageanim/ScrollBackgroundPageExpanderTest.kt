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

    /**
     * 回归：一个 TOC 章跨多个 spine item 时，整页插画 section 也必须被展开。
     *
     * 没有独立 navPoint 的 spine 项会被并进相邻章，而整页插画页恰恰常常没有目录条目。
     * 此前按章判定（要求 sections.size == 1），这类章一律被跳过，展开逻辑一次都不执行，
     * 插画被压进一行文本的高度，表现为整页图消失或只剩碎块。
     */
    @Test
    fun `一章含多个 section 时整页插画 section 单独展开`() {
        val content = multiSectionContent()
        val expanded = expandBackgroundOnlyScrollPage(
            layout = multiSectionLayout(),
            content = content,
            chapterTitle = "扉页",
            pageWidth = 920,
            resolveImageDimensions = { _, _ -> 1080 to 2400 },
        )

        val plateHeight = 920f * 2400f / 1080f
        // section 0 是真实正文，不动
        assertEquals(300f, expanded.pages[0].height, 0.01f)
        assertEquals(1, expanded.pages[0].lines.size)
        // section 1 / 2 是整页插画，各自撑成图片等比高
        assertEquals(plateHeight, expanded.pages[1].height, 0.01f)
        assertEquals(plateHeight, expanded.pages[2].height, 0.01f)
        assertTrue(expanded.pages[1].lines.isEmpty())
        // 页背景必须落到页上，否则绘制层拿不到图
        assertEquals(1, expanded.pages[1].sectionIndex)
        assertTrue(expanded.pages[1].background.isVisible)
        // 总高按两页的增量累加，不是替换成单页
        assertEquals(300f + plateHeight * 2, expanded.totalHeight, 0.01f)
        assertEquals(3, expanded.pages.size)
    }

    private fun multiSectionContent(): StructuredChapterContent {
        val blocks = listOf(
            ChapterBlock.Paragraph("扉页正文，需要正常排版"),
            ChapterBlock.Paragraph(" "),
            ChapterBlock.Paragraph(" "),
        )
        return StructuredChapterContent(
            blocks = blocks,
            sections = listOf(
                section(0, 5, "Text/top005.xhtml", 0, 1, EpubBackground.EMPTY),
                section(1, 6, "Text/top006.xhtml", 1, 2, plateBackground("file:///hj001.jpg")),
                section(2, 7, "Text/plate0.xhtml", 2, 3, plateBackground("file:///plate0.jpg")),
            ),
        )
    }

    private fun multiSectionLayout(): ScrollChapterLayout = ScrollChapterLayout(
        chapterIndex = 5,
        title = "扉页",
        pages = listOf(
            ScrollPage(0, listOf(textLine()), 300f, 5, sectionIndex = 0),
            ScrollPage(1, emptyList(), 40f, 5, sectionIndex = 1),
            ScrollPage(2, emptyList(), 40f, 5, sectionIndex = 2),
        ),
        totalHeight = 380f,
        viewWidth = 920,
        styleSignature = "test",
        totalCharCount = 30,
    )

    private fun textLine() = com.morealm.epub.render.ScrollLine(
        columns = emptyList(),
        lineTop = 0f,
        lineBottom = 300f,
        paragraphNum = 0,
        isTitle = false,
        text = "扉页正文，需要正常排版",
        firstChapterPos = 0,
        lastChapterPos = 1,
        sectionIndex = 0,
    )

    private fun section(
        index: Int,
        spine: Int,
        href: String,
        start: Int,
        end: Int,
        background: EpubBackground,
    ) = StructuredContentSection(
        sectionIndex = index,
        spineIndex = spine,
        href = href,
        blockStartIndex = start,
        blockEndExclusive = end,
        background = background,
    )

    private fun plateBackground(uri: String) = EpubBackground(
        layers = listOf(
            EpubBackgroundLayer(
                image = EpubBackgroundImage.Url(uri),
                repeat = EpubBackgroundRepeat(
                    x = EpubBackgroundRepeatMode.NO_REPEAT,
                    y = EpubBackgroundRepeatMode.NO_REPEAT,
                ),
                size = EpubBackgroundSize.Cover,
            ),
        ),
    )

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
