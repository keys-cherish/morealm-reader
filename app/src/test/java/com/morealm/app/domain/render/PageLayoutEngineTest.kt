package com.morealm.app.domain.render

import android.text.TextPaint
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PageLayoutEngineTest {

    private lateinit var engine: PageLayoutEngine

    @Before
    fun setup() {
        val paint = TextPaint().apply { textSize = 48f; isAntiAlias = true }
        val titlePaint = TextPaint().apply { textSize = 60f; isFakeBoldText = true }
        val measure = TextMeasure(paint)
        engine = PageLayoutEngine(1080, 1920, 48, 48, 48, 48, titlePaint, paint, measure)
    }

    @Test
    fun `empty content produces one page`() {
        val pages = engine.layoutChapter("Title", "", 0)
        assertEquals(1, pages.size)
    }

    @Test
    fun `short text fits in one page`() {
        val pages = engine.layoutChapter("Chapter 1", "This is a short paragraph.", 0)
        assertEquals(1, pages.size)
        assertTrue(pages[0].lines.isNotEmpty())
    }

    @Test
    fun `long text produces at least one page`() {
        val longText = (1..200).joinToString("\n") { "这是第${it}段文字，用来测试分页功能是否正常工作。" }
        val pages = engine.layoutChapter("测试章节", longText, 0)
        assertTrue("Long text should produce at least 1 page", pages.isNotEmpty())
    }

    @Test
    fun `page count is set correctly`() {
        val text = (1..100).joinToString("\n") { "段落$it" }
        val pages = engine.layoutChapter("Title", text, 0)
        pages.forEach { assertEquals(pages.size, it.pageCount) }
    }

    @Test
    fun `title appears on first page`() {
        val pages = engine.layoutChapter("我的标题", "内容", 0)
        assertTrue(pages[0].lines.any { it.isTitle })
    }

    @Test
    fun `HTML content is parsed correctly`() {
        val html = "<p>第一段</p><p>第二段</p><img src=\"test.jpg\"><p>第三段</p>"
        val pages = engine.layoutChapter("HTML章节", html, 0)
        assertTrue(pages.isNotEmpty())
        // Should have extracted image
        val totalImages = pages.sumOf { it.images.size }
        assertEquals("Should find 1 image", 1, totalImages)
    }

    @Test
    fun `chapter index is preserved`() {
        val pages = engine.layoutChapter("Ch", "text", 42)
        assertEquals(42, pages[0].chapterIndex)
    }
}
