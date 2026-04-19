package com.morealm.app.domain.parser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import com.morealm.app.domain.entity.BookChapter
import com.morealm.app.core.log.AppLog
import java.io.File
import java.io.FileOutputStream

/**
 * PDF parser using Android's PdfRenderer.
 *
 * Strategy:
 * - Chapters: every 10 pages = 1 chapter
 * - Content: renders pages as bitmaps → HTML <img> tags for WebView display
 * - Cover: first page rendered as JPEG
 * - All renders cached to disk for fast re-access
 */
object PdfParser {

    private const val PAGES_PER_CHAPTER = 10
    private const val RENDER_DPI = 200 // balance between quality and memory

    fun parseChapters(context: Context, uri: Uri): List<BookChapter> {
        val bookId = uri.toString()
        val pageCount = getPageCount(context, uri) ?: return emptyList()
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
        withPdfRenderer(context, uri) { renderer ->
            for (pageIdx in startPage until endPage) {
                val imgFile = File(cacheDir, "page_$pageIdx.jpg")
                if (!imgFile.exists()) {
                    renderPage(renderer, pageIdx, imgFile)
                }
                if (imgFile.exists()) {
                    sb.append("<div class=\"pdf-page\" style=\"margin-bottom:8px;\">")
                    sb.append("<img src=\"file://${imgFile.absolutePath}\" style=\"width:100%;\" />")
                    sb.append("<div style=\"text-align:center;font-size:11px;color:#888;\">")
                    sb.append("${pageIdx + 1} / ${renderer.pageCount}")
                    sb.append("</div></div>")
                }
            }
        }
        return sb.toString().ifEmpty { "（PDF 渲染失败）" }
    }

    /** Extract cover from first page */
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

    private fun getPageCount(context: Context, uri: Uri): Int? {
        return withPdfRenderer(context, uri) { it.pageCount }
    }

    private fun renderPage(renderer: PdfRenderer, pageIdx: Int, outFile: File) {
        if (pageIdx >= renderer.pageCount) return
        renderer.openPage(pageIdx).use { page ->
            val scale = RENDER_DPI / 72f
            val width = (page.width * scale).toInt()
            val height = (page.height * scale).toInt()
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            FileOutputStream(outFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
            }
            bitmap.recycle()
        }
    }

    private fun <T> withPdfRenderer(context: Context, uri: Uri, block: (PdfRenderer) -> T): T? {
        return try {
            val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return null
            pfd.use {
                val renderer = PdfRenderer(it)
                renderer.use { r -> block(r) }
            }
        } catch (e: Exception) {
            AppLog.error("PdfParser", "PdfRenderer failed: ${e.message}")
            null
        }
    }

    fun clearCache(context: Context, uri: Uri) {
        File(context.cacheDir, "pdf_pages/${uri.hashCode()}").deleteRecursively()
        File(context.cacheDir, "pdf_covers/${uri.hashCode()}").deleteRecursively()
    }
}
