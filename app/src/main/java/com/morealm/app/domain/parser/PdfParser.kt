package com.morealm.app.domain.parser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.morealm.app.domain.entity.BookChapter
import com.morealm.app.core.log.AppLog
import java.io.File
import java.io.FileOutputStream

/**
 * PDF parser using Android's PdfRenderer.
 * Caches the PdfRenderer instance (like Legado's PdfFile singleton pattern)
 * to avoid re-opening the file descriptor on every page render.
 */
object PdfParser {

    private const val PAGES_PER_CHAPTER = 10
    private const val RENDER_DPI = 200

    // Cached renderer instance
    private var cachedUri: String? = null
    private var cachedPfd: ParcelFileDescriptor? = null
    private var cachedRenderer: PdfRenderer? = null

    fun parseChapters(context: Context, uri: Uri): List<BookChapter> {
        val bookId = uri.toString()
        val pageCount = withPdfRenderer(context, uri) { it.pageCount } ?: return emptyList()
        val chapters = mutableListOf<BookChapter>()
        var chapterIdx = 0
        var page = 0
        while (page < pageCount) {
            val end = (page + PAGES_PER_CHAPTER).coerceAtMost(pageCount)
            chapters.add(BookChapter(
                id = "${bookId}_$chapterIdx",
                bookId = bookId,
                index = chapterIdx,
                title = "第 ${page + 1}-$end 页",
                startPosition = page.toLong(),
                endPosition = end.toLong(),
                url = "pdf",
            ))
            chapterIdx++
            page = end
        }
        return chapters
    }

    fun readChapter(context: Context, uri: Uri, chapter: BookChapter): String {
        val startPage = chapter.startPosition.toInt()
        val endPage = chapter.endPosition.toInt()
        val cacheDir = File(context.cacheDir, "pdf_pages/${uri.hashCode()}")
        cacheDir.mkdirs()

        val sb = StringBuilder()
        // Render one page at a time, releasing the PdfRenderer lock between pages
        // so other callers (e.g. next chapter preload) are not starved.
        for (pageIdx in startPage until endPage) {
            val imgFile = File(cacheDir, "page_$pageIdx.jpg")
            if (!imgFile.exists()) {
                withPdfRenderer(context, uri) { renderer ->
                    renderPage(renderer, pageIdx, imgFile)
                }
            }
            if (imgFile.exists()) {
                val pageCount = withPdfRenderer(context, uri) { it.pageCount } ?: 0
                sb.append("<div class=\"pdf-page\" style=\"margin-bottom:8px;\">")
                sb.append("<img src=\"file://${imgFile.absolutePath}\" style=\"width:100%;\" loading=\"lazy\" />")
                sb.append("<div style=\"text-align:center;font-size:11px;color:#888;\">")
                sb.append("${pageIdx + 1} / $pageCount")
                sb.append("</div></div>")
            }
        }
        return sb.toString().ifEmpty { "（PDF 渲染失败）" }
    }

    fun extractCover(context: Context, uri: Uri): String? {
        val cacheDir = File(context.cacheDir, "pdf_covers/${uri.hashCode()}")
        cacheDir.mkdirs()
        val coverFile = File(cacheDir, "cover.jpg")
        if (coverFile.exists()) return coverFile.absolutePath

        return withPdfRenderer(context, uri) { renderer ->
            renderPage(renderer, 0, coverFile)
            if (coverFile.exists()) coverFile.absolutePath else null
        }
    }

    /** Max pixel count per rendered page to prevent OOM (≈ 4MB @ ARGB_8888) */
    private const val MAX_PAGE_PIXELS = 1024 * 1024

    private fun renderPage(renderer: PdfRenderer, pageIdx: Int, outFile: File) {
        if (pageIdx >= renderer.pageCount) return
        renderer.openPage(pageIdx).use { page ->
            val scale = RENDER_DPI / 72f
            var width = (page.width * scale).toInt()
            var height = (page.height * scale).toInt()
            // Down-scale if the page would exceed the pixel budget
            val pixels = width.toLong() * height
            if (pixels > MAX_PAGE_PIXELS) {
                val ratio = Math.sqrt(MAX_PAGE_PIXELS.toDouble() / pixels).toFloat()
                width = (width * ratio).toInt()
                height = (height * ratio).toInt()
            }
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            FileOutputStream(outFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
            }
            bitmap.recycle()
        }
    }

    @Synchronized
    private fun <T> withPdfRenderer(context: Context, uri: Uri, block: (PdfRenderer) -> T): T? {
        val uriStr = uri.toString()
        val renderer = if (uriStr == cachedUri && cachedRenderer != null) {
            cachedRenderer!!
        } else {
            releaseCache()
            try {
                val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return null
                val r = PdfRenderer(pfd)
                cachedPfd = pfd
                cachedRenderer = r
                cachedUri = uriStr
                r
            } catch (e: Exception) {
                AppLog.error("PdfParser", "PdfRenderer failed: ${e.message}")
                return null
            }
        }
        return try {
            block(renderer)
        } catch (e: Exception) {
            AppLog.error("PdfParser", "PdfRenderer operation failed: ${e.message}")
            null
        }
    }

    fun releaseCache() {
        try { cachedRenderer?.close() } catch (_: Exception) {}
        try { cachedPfd?.close() } catch (_: Exception) {}
        cachedRenderer = null
        cachedPfd = null
        cachedUri = null
    }

    fun clearCache(context: Context, uri: Uri) {
        File(context.cacheDir, "pdf_pages/${uri.hashCode()}").deleteRecursively()
        File(context.cacheDir, "pdf_covers/${uri.hashCode()}").deleteRecursively()
    }
}
