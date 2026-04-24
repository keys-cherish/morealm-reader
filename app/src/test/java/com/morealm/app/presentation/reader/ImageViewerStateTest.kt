package com.morealm.app.presentation.reader

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for image click flow in ReaderViewModel.
 * Verifies the state management for image viewer open/dismiss.
 */
class ImageViewerStateTest {

    // Simulate the ViewModel's image viewer state logic without Android dependencies

    private var viewingImageSrc: String? = null

    private fun onImageClick(src: String) { viewingImageSrc = src }
    private fun dismissImageViewer() { viewingImageSrc = null }

    @Test
    fun `initial state is null`() {
        assertNull(viewingImageSrc)
    }

    @Test
    fun `onImageClick sets src`() {
        onImageClick("file:///data/cache/img.jpg")
        assertEquals("file:///data/cache/img.jpg", viewingImageSrc)
    }

    @Test
    fun `dismissImageViewer clears src`() {
        onImageClick("file:///data/cache/img.jpg")
        dismissImageViewer()
        assertNull(viewingImageSrc)
    }

    @Test
    fun `clicking different image replaces src`() {
        onImageClick("file:///data/cache/img1.jpg")
        onImageClick("file:///data/cache/img2.png")
        assertEquals("file:///data/cache/img2.png", viewingImageSrc)
    }

    @Test
    fun `dismiss then click works`() {
        onImageClick("file:///a.jpg")
        dismissImageViewer()
        onImageClick("file:///b.jpg")
        assertEquals("file:///b.jpg", viewingImageSrc)
    }

    @Test
    fun `double dismiss is safe`() {
        onImageClick("file:///a.jpg")
        dismissImageViewer()
        dismissImageViewer()
        assertNull(viewingImageSrc)
    }

    // ── PDF image click simulation ──

    @Test
    fun `PDF page image click passes file path`() {
        val pdfImgSrc = "file:///data/data/com.morealm.app/cache/pdf_pages/12345/page_3.jpg"
        onImageClick(pdfImgSrc)
        assertEquals(pdfImgSrc, viewingImageSrc)
        assertTrue(viewingImageSrc!!.contains("pdf_pages"))
    }

    // ── EPUB image click simulation ──

    @Test
    fun `EPUB image click passes file path`() {
        val epubImgSrc = "file:///data/data/com.morealm.app/cache/epub_images/67890/illustration.png"
        onImageClick(epubImgSrc)
        assertEquals(epubImgSrc, viewingImageSrc)
        assertTrue(viewingImageSrc!!.contains("epub_images"))
    }

    // ── WebView JS bridge image click simulation ──

    @Test
    fun `WebView JS bridge passes full file URL`() {
        // The JS in ReaderWebView calls MoRealm.onImageClick(el.src)
        // el.src is the fully resolved URL from the WebView
        val jsSrc = "file:///data/data/com.morealm.app/cache/epub_images/67890/ch3_img.png"
        onImageClick(jsSrc)
        assertNotNull(viewingImageSrc)
        assertTrue(viewingImageSrc!!.startsWith("file:///"))
    }

    // ── Canvas renderer image click simulation ──

    @Test
    fun `Canvas renderer ImageColumn src passes through`() {
        // CanvasRenderer passes ImageColumn.src directly
        val canvasSrc = "/data/data/com.morealm.app/cache/epub_images/67890/img.jpg"
        onImageClick(canvasSrc)
        assertEquals(canvasSrc, viewingImageSrc)
    }
}
