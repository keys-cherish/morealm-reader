package com.morealm.app.domain.parser

import android.content.Context
import android.net.Uri
import com.morealm.app.domain.entity.BookChapter
import com.morealm.app.core.log.AppLog

/**
 * MOBI/AZW3 parser with cached text extraction.
 * Caches the extracted text per URI to avoid re-reading the entire file on every chapter load.
 */
object MobiParser {

    // Cached extraction result
    private var cachedUri: String? = null
    private var cachedText: String? = null

    fun parseChapters(context: Context, uri: Uri): List<BookChapter> {
        val bookId = uri.toString()
        val chapters = mutableListOf<BookChapter>()

        try {
            val text = getOrExtractText(context, uri)
            if (text.isEmpty()) return chapters

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
                chapters.add(BookChapter(
                    id = "${bookId}_0", bookId = bookId, index = 0,
                    title = "全文",
                    startPosition = 0, endPosition = text.length.toLong(),
                ))
            } else {
                matches.forEachIndexed { i, match ->
                    if (match.range.first > lastEnd) {
                        val title = if (index == 0 && i == 0) "前言"
                            else cleanHtmlTitle(match.value)
                        chapters.add(BookChapter(
                            id = "${bookId}_$index", bookId = bookId, index = index,
                            title = title,
                            startPosition = lastEnd.toLong(),
                            endPosition = match.range.first.toLong(),
                        ))
                        index++
                    }
                    lastEnd = match.range.first
                }
                if (lastEnd < text.length) {
                    chapters.add(BookChapter(
                        id = "${bookId}_$index", bookId = bookId, index = index,
                        title = if (matches.isNotEmpty()) cleanHtmlTitle(matches.last().value) else "结尾",
                        startPosition = lastEnd.toLong(),
                        endPosition = text.length.toLong(),
                    ))
                }
            }
        } catch (e: Exception) {
            AppLog.error("MobiParser", "parseChapters failed: ${e.message}")
        }

        return chapters
    }

    fun readChapter(context: Context, uri: Uri, chapter: BookChapter): String {
        return try {
            val text = getOrExtractText(context, uri)
            val start = chapter.startPosition.toInt().coerceIn(0, text.length)
            val end = chapter.endPosition.toInt().coerceIn(start, text.length)
            stripHtml(text.substring(start, end))
        } catch (_: Exception) { "" }
    }

    @Synchronized
    private fun getOrExtractText(context: Context, uri: Uri): String {
        val uriStr = uri.toString()
        if (uriStr == cachedUri && cachedText != null) return cachedText!!

        val text = extractMobiText(context, uri)
        cachedUri = uriStr
        cachedText = text
        return text
    }

    fun releaseCache() {
        cachedUri = null
        cachedText = null
    }

    private fun extractMobiText(context: Context, uri: Uri): String {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: return ""

        if (bytes.size < 78) return ""

        val sb = StringBuilder()
        try {
            val text = String(bytes, Charsets.UTF_8)
            val bodyMatch = Regex("<body[^>]*>(.*)</body>", RegexOption.DOT_MATCHES_ALL).find(text)
            if (bodyMatch != null) {
                sb.append(bodyMatch.groupValues[1])
            } else {
                val textLength = ((bytes[84].toInt() and 0xFF) shl 24) or
                        ((bytes[85].toInt() and 0xFF) shl 16) or
                        ((bytes[86].toInt() and 0xFF) shl 8) or
                        (bytes[87].toInt() and 0xFF)
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
