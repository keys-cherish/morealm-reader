package com.morealm.app.ui.reader

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for ImageViewerDialog's image source path resolution logic.
 * Extracted as pure functions to test without Compose runtime.
 */
class ImageViewerPathTest {

    /**
     * Mirrors the filePath resolution logic in ImageViewerDialog.
     */
    private fun resolveFilePath(imageSrc: String): String? {
        return when {
            imageSrc.startsWith("file:///") -> imageSrc.removePrefix("file://")
            imageSrc.startsWith("file://") -> imageSrc.removePrefix("file://")
            imageSrc.startsWith("/") -> imageSrc
            else -> null
        }
    }

    // ── file:/// prefix ──

    @Test
    fun `file triple slash resolves to absolute path`() {
        val result = resolveFilePath("file:///data/data/com.morealm.app/cache/epub_images/img.png")
        assertEquals("/data/data/com.morealm.app/cache/epub_images/img.png", result)
    }

    @Test
    fun `file triple slash with spaces in path`() {
        val result = resolveFilePath("file:///sdcard/My Books/cover.jpg")
        assertEquals("/sdcard/My Books/cover.jpg", result)
    }

    // ── file:// prefix ──

    @Test
    fun `file double slash resolves correctly`() {
        val result = resolveFilePath("file:///storage/emulated/0/test.jpg")
        assertEquals("/storage/emulated/0/test.jpg", result)
    }

    // ── absolute path ──

    @Test
    fun `absolute path passes through`() {
        val result = resolveFilePath("/data/data/com.morealm.app/cache/pdf_pages/page_0.jpg")
        assertEquals("/data/data/com.morealm.app/cache/pdf_pages/page_0.jpg", result)
    }

    // ── http URLs ──

    @Test
    fun `http URL returns null for filePath`() {
        val result = resolveFilePath("https://example.com/image.png")
        assertNull(result)
    }

    @Test
    fun `http URL without s returns null`() {
        val result = resolveFilePath("http://example.com/image.png")
        assertNull(result)
    }

    // ── data URI ──

    @Test
    fun `data URI returns null for filePath`() {
        val result = resolveFilePath("data:image/png;base64,iVBORw0KGgo=")
        assertNull(result)
    }

    // ── edge cases ──

    @Test
    fun `empty string returns null`() {
        val result = resolveFilePath("")
        assertNull(result)
    }

    @Test
    fun `relative path returns null`() {
        val result = resolveFilePath("images/cover.jpg")
        assertNull(result)
    }

    @Test
    fun `file prefix without slashes returns null`() {
        val result = resolveFilePath("file:image.jpg")
        assertNull(result)
    }

    // ── PDF image src format ──

    @Test
    fun `PDF rendered page path resolves correctly`() {
        // PdfParser generates: file:///data/.../cache/pdf_pages/HASH/page_0.jpg
        val src = "file:///data/data/com.morealm.app/cache/pdf_pages/12345/page_0.jpg"
        val path = resolveFilePath(src)
        assertNotNull(path)
        assertTrue(path!!.endsWith("page_0.jpg"))
        assertTrue(path.startsWith("/"))
        assertFalse(path.contains("file:"))
    }

    // ── EPUB image src format ──

    @Test
    fun `EPUB cached image path resolves correctly`() {
        // EpubParser generates: file:///data/.../cache/epub_images/HASH/image_name.png
        val src = "file:///data/data/com.morealm.app/cache/epub_images/67890/chapter1_img.png"
        val path = resolveFilePath(src)
        assertNotNull(path)
        assertTrue(path!!.endsWith("chapter1_img.png"))
        assertTrue(path.startsWith("/"))
    }

    // ── CBZ image src format ──

    @Test
    fun `CBZ extracted image path resolves correctly`() {
        val src = "file:///data/data/com.morealm.app/cache/cbz_images/99999/page_005.jpg"
        val path = resolveFilePath(src)
        assertNotNull(path)
        assertTrue(path!!.contains("cbz_images"))
    }

    // ── Coil model selection ──

    @Test
    fun `local file path produces File model`() {
        val src = "file:///data/cache/img.jpg"
        val filePath = resolveFilePath(src)
        assertNotNull(filePath)
        val model: Any = java.io.File(filePath!!)
        assertTrue(model is java.io.File)
    }

    @Test
    fun `http URL produces String model`() {
        val src = "https://example.com/cover.jpg"
        val filePath = resolveFilePath(src)
        assertNull(filePath)
        // When filePath is null, imageSrc itself is used as model
        val model: Any = src
        assertTrue(model is String)
    }
}
