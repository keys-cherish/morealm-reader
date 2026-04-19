package com.morealm.app.domain.parser

import android.content.Context
import android.net.Uri
import com.morealm.app.domain.entity.BookChapter

/**
 * MOBI/AZW3 parser — basic support.
 * Parses PalmDOC header to extract text content.
 * Full MOBI support (KF8/AZW3 with images) requires more work;
 * this provides readable text extraction for 1.0.
 */
object MobiParser {

    fun parseChapters(context: Context, uri: Uri): List<BookChapter> {
        val bookId = uri.toString()
        val chapters = mutableListOf<BookChapter>()

        try {
            val text = extractMobiText(context, uri)
            if (text.isEmpty()) return chapters

            // Split by HTML heading tags or chapter patterns
            val chapterRegex = Regex(
                "<h[1-3][^>]*>(.*?)</h[1-3]>|" +
                "第[零一二三四五六七八九十百千万\\d]+[章节回].*|" +
                "Chapter\\s+\\d+.*",
                RegexOption.IGNORE_CASE
            )

            var lastEnd = 0
            var index = 0
            val matches = chapterRegex.findAll(text).toList()

            if (matches.isEmpty()) {
                // No chapters found — treat as single chapter
                chapters.add(BookChapter(
                    id = "${bookId}_0",
                    bookId = bookId,
                    index = 0,
                    title = "全文",
                    startPosition = 0,
                    endPosition = text.length.toLong(),
                ))
            } else {
                matches.forEachIndexed { i, match ->
                    if (match.range.first > lastEnd) {
                        val title = if (index == 0 && i == 0) "前言"
                            else cleanHtmlTitle(match.value)
                        chapters.add(BookChapter(
                            id = "${bookId}_$index",
                            bookId = bookId,
                            index = index,
                            title = title,
                            startPosition = lastEnd.toLong(),
                            endPosition = match.range.first.toLong(),
                        ))
                        index++
                    }
                    lastEnd = match.range.first
                }
                // Last chapter
                if (lastEnd < text.length) {
                    chapters.add(BookChapter(
                        id = "${bookId}_$index",
                        bookId = bookId,
                        index = index,
                        title = if (matches.isNotEmpty()) cleanHtmlTitle(matches.last().value) else "结尾",
                        startPosition = lastEnd.toLong(),
                        endPosition = text.length.toLong(),
                    ))
                }
            }
        } catch (_: Exception) {}

        return chapters
    }

    fun readChapter(context: Context, uri: Uri, chapter: BookChapter): String {
        return try {
            val text = extractMobiText(context, uri)
            val start = chapter.startPosition.toInt().coerceIn(0, text.length)
            val end = chapter.endPosition.toInt().coerceIn(start, text.length)
            stripHtml(text.substring(start, end))
        } catch (_: Exception) { "" }
    }

    private fun extractMobiText(context: Context, uri: Uri): String {
        // Read raw bytes and attempt PalmDOC text extraction
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: return ""

        if (bytes.size < 78) return ""

        // Check PalmDOC header
        val compression = ((bytes[80].toInt() and 0xFF) shl 8) or (bytes[81].toInt() and 0xFF)
        val textLength = ((bytes[84].toInt() and 0xFF) shl 24) or
                ((bytes[85].toInt() and 0xFF) shl 16) or
                ((bytes[86].toInt() and 0xFF) shl 8) or
                (bytes[87].toInt() and 0xFF)
        val recordCount = ((bytes[88].toInt() and 0xFF) shl 8) or (bytes[89].toInt() and 0xFF)

        if (recordCount == 0) {
            // Fallback: try reading as raw text
            return String(bytes, Charsets.UTF_8).take(textLength.coerceAtMost(5_000_000))
        }

        // For 1.0: basic uncompressed text extraction
        // Full PalmDOC LZ77 decompression would go here
        val sb = StringBuilder()
        try {
            val text = String(bytes, Charsets.UTF_8)
            // Find readable text sections
            val bodyMatch = Regex("<body[^>]*>(.*)</body>", RegexOption.DOT_MATCHES_ALL).find(text)
            if (bodyMatch != null) {
                sb.append(bodyMatch.groupValues[1])
            } else {
                sb.append(text.take(textLength.coerceAtMost(5_000_000)))
            }
        } catch (_: Exception) {}

        return sb.toString()
    }

    private fun cleanHtmlTitle(raw: String): String {
        return raw.replace(Regex("<[^>]+>"), "").trim().take(50)
    }

    private fun stripHtml(html: String): String {
        return html
            .replace(Regex("<br\\s*/?>"), "\n")
            .replace(Regex("<p[^>]*>"), "\n")
            .replace("</p>", "")
            .replace(Regex("<[^>]+>"), "")
            .replace("&nbsp;", " ")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
            .trim()
    }
}
