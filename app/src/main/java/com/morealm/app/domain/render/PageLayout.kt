package com.morealm.app.domain.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.text.TextPaint
import java.io.File

/**
 * A single rendered page of text, ready for Canvas drawing.
 */
data class TextPage(
    val index: Int = 0,
    val lines: List<TextLine> = emptyList(),
    val images: List<PageImage> = emptyList(),
    val height: Float = 0f,
    val title: String = "",
    val chapterIndex: Int = 0,
    val pageCount: Int = 0,
)

data class TextLine(
    val text: String,
    val columns: List<TextColumn>,
    val y: Float,
    val isTitle: Boolean = false,
    val isParagraphEnd: Boolean = false,
)

data class TextColumn(
    val text: String,
    val x: Float,
    val width: Float,
)

data class PageImage(
    val path: String,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
)

/**
 * Layout engine: splits chapter text into pages.
 * Handles both plain text and HTML content (strips tags, preserves images).
 */
class PageLayoutEngine(
    private val viewWidth: Int,
    private val viewHeight: Int,
    private val paddingLeft: Int,
    private val paddingRight: Int,
    private val paddingTop: Int,
    private val paddingBottom: Int,
    private val titlePaint: TextPaint,
    private val contentPaint: TextPaint,
    private val textMeasure: TextMeasure,
) {
    private val contentWidth = viewWidth - paddingLeft - paddingRight
    private val contentHeight = viewHeight - paddingTop - paddingBottom

    /**
     * Layout chapter content. Handles both plain text and HTML.
     * For HTML: strips tags, extracts image paths, renders text with Canvas.
     */
    fun layoutChapter(title: String, content: String, chapterIndex: Int): List<TextPage> {
        val isHtml = content.trimStart().let { it.startsWith("<") && (it.contains("<p") || it.contains("<div") || it.contains("<img")) }
        val (paragraphs, imagePaths) = if (isHtml) parseHtmlContent(content) else Pair(
            content.lines().filter { it.isNotBlank() }.map { "　　$it" },
            emptyList()
        )
        return paginateContent(title, paragraphs, imagePaths, chapterIndex)
    }

    private fun parseHtmlContent(html: String): Pair<List<String>, List<String>> {
        val images = mutableListOf<String>()
        // Extract image paths
        val imgRegex = Regex("""<img[^>]+src="([^"]+)"[^>]*>""", RegexOption.IGNORE_CASE)
        imgRegex.findAll(html).forEach { images.add(it.groupValues[1]) }

        // Strip HTML to plain text with paragraph boundaries
        val text = html
            .replace(Regex("<img[^>]*>", RegexOption.IGNORE_CASE), "\n[IMG]\n")
            .replace(Regex("</p>|</div>|</li>|</h[1-6]>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<[^>]+>"), "")
            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&nbsp;", " ").replace("&quot;", "\"")

        val paragraphs = text.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .map { if (it == "[IMG]") it else "　　$it" }

        return paragraphs to images
    }

    private fun paginateContent(
        title: String, paragraphs: List<String>,
        imagePaths: List<String>, chapterIndex: Int,
    ): List<TextPage> {
        val pages = mutableListOf<TextPage>()
        var currentLines = mutableListOf<TextLine>()
        var currentImages = mutableListOf<PageImage>()
        var currentY = 0f
        var pageIndex = 0
        var imageIndex = 0
        val titleHeight = titlePaint.fontMetrics.let { it.descent - it.ascent }
        val lineHeight = contentPaint.fontMetrics.let { it.descent - it.ascent }
        val paraSpacing = lineHeight * 0.5f

        // Title on first page
        if (title.isNotBlank()) {
            val titleLine = layoutLine(title, titlePaint, paddingLeft.toFloat(), currentY + paddingTop, isTitle = true)
            currentLines.add(titleLine)
            currentY += titleHeight + paraSpacing
        }

        for (para in paragraphs) {
            if (para == "[IMG]" && imageIndex < imagePaths.size) {
                // Image placeholder — reserve space
                val imgPath = imagePaths[imageIndex++]
                val imgHeight = (contentWidth * 0.6f).coerceAtMost(contentHeight * 0.5f)
                if (currentY + imgHeight > contentHeight) {
                    pages.add(TextPage(pageIndex, currentLines.toList(), currentImages.toList(), currentY, title, chapterIndex))
                    pageIndex++; currentLines = mutableListOf(); currentImages = mutableListOf(); currentY = 0f
                }
                currentImages.add(PageImage(imgPath, paddingLeft.toFloat(), currentY + paddingTop, contentWidth.toFloat(), imgHeight))
                currentY += imgHeight + paraSpacing
                continue
            }

            val wrappedLines = wrapText(para, contentPaint, contentWidth.toFloat())
            for ((i, lineText) in wrappedLines.withIndex()) {
                if (currentY + lineHeight > contentHeight) {
                    pages.add(TextPage(pageIndex, currentLines.toList(), currentImages.toList(), currentY, title, chapterIndex))
                    pageIndex++; currentLines = mutableListOf(); currentImages = mutableListOf(); currentY = 0f
                }
                currentLines.add(layoutLine(lineText, contentPaint, paddingLeft.toFloat(), currentY + paddingTop, isParagraphEnd = i == wrappedLines.lastIndex))
                currentY += lineHeight
            }
            currentY += paraSpacing
        }

        if (currentLines.isNotEmpty() || currentImages.isNotEmpty()) {
            pages.add(TextPage(pageIndex, currentLines.toList(), currentImages.toList(), currentY, title, chapterIndex))
        }

        val total = pages.size
        return pages.map { it.copy(pageCount = total) }.ifEmpty { listOf(TextPage(title = title, pageCount = 1)) }
    }

    private fun layoutLine(text: String, paint: TextPaint, startX: Float, y: Float,
                           isTitle: Boolean = false, isParagraphEnd: Boolean = false): TextLine {
        val (chars, widths) = textMeasure.measureTextSplit(text)
        val columns = mutableListOf<TextColumn>()
        var x = startX
        for (i in chars.indices) {
            columns.add(TextColumn(chars[i], x, widths[i]))
            x += widths[i]
        }
        return TextLine(text, columns, y, isTitle, isParagraphEnd)
    }

    private fun wrapText(text: String, paint: TextPaint, maxWidth: Float): List<String> {
        val (chars, widths) = textMeasure.measureTextSplit(text)
        val lines = mutableListOf<String>()
        var lineStart = 0
        var lineWidth = 0f
        for (i in chars.indices) {
            lineWidth += widths[i]
            if (lineWidth > maxWidth && i > lineStart) {
                lines.add(chars.subList(lineStart, i).joinToString(""))
                lineStart = i; lineWidth = widths[i]
            }
        }
        if (lineStart < chars.size) lines.add(chars.subList(lineStart, chars.size).joinToString(""))
        return lines.ifEmpty { listOf("") }
    }
}
