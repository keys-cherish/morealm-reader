package com.morealm.app.domain.parser

import android.content.Context
import android.net.Uri
import com.morealm.app.domain.entity.Book
import com.morealm.app.domain.entity.BookChapter
import com.morealm.app.domain.entity.BookFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Unified local book parser — handles TXT, EPUB, MOBI, PDF.
 * Designed for speed: streaming parse, lazy chapter loading, parallel image decode.
 *
 * Key design:
 * - TXT: streaming regex chapter detection, no full-file load
 * - EPUB: ZIP streaming, OPF spine parse, lazy XHTML extraction
 * - MOBI: header parse + PalmDOC decompress (basic support)
 * - Images: extracted to cache on first access, not at import time
 */
object LocalBookParser {

    // Common Chinese chapter patterns
    private val chapterPatterns = listOf(
        Regex("^\\s*第[零一二三四五六七八九十百千万\\d]+[章节回卷集部篇].*"),
        Regex("^\\s*Chapter\\s+\\d+.*", RegexOption.IGNORE_CASE),
        Regex("^\\s*卷[零一二三四五六七八九十百千万\\d]+.*"),
        Regex("^\\s*正文\\s.*"),
        Regex("^\\s*序[章言].*"),
        Regex("^\\s*楔子.*"),
        Regex("^\\s*番外.*"),
    )

    suspend fun parseChapters(
        context: Context,
        uri: Uri,
        format: BookFormat,
        customTxtChapterRegex: String = "",
    ): List<BookChapter> = withContext(Dispatchers.IO) {
        when (format) {
            BookFormat.TXT -> parseTxtChapters(context, uri, customTxtChapterRegex)
            BookFormat.EPUB -> EpubParser.parseChapters(context, uri)
            BookFormat.MOBI, BookFormat.AZW3 -> MobiParser.parseChapters(context, uri)
            BookFormat.PDF -> PdfParser.parseChapters(context, uri)
            BookFormat.CBZ -> CbzParser.parseChapters(context, uri)
            else -> emptyList()
        }
    }

    suspend fun readChapter(
        context: Context,
        uri: Uri,
        format: BookFormat,
        chapter: BookChapter,
    ): String = withContext(Dispatchers.IO) {
        when (format) {
            BookFormat.TXT -> readTxtChapter(context, uri, chapter)
            BookFormat.EPUB -> EpubParser.readChapter(context, uri, chapter)
            BookFormat.MOBI, BookFormat.AZW3 -> MobiParser.readChapter(context, uri, chapter)
            BookFormat.PDF -> PdfParser.readChapter(context, uri, chapter)
            BookFormat.CBZ -> CbzParser.readChapter(context, uri, chapter)
            else -> ""
        }
    }

    // ── TXT ──────────────────────────────────────────────

    private const val BLOCK_SIZE = 512 * 1024 // 512KB blocks

    fun parseTxtChapters(context: Context, uri: Uri, customRegex: String = ""): List<BookChapter> {
        val chapters = mutableListOf<BookChapter>()
        val bookId = ""
        val charsetName = detectCharset(context, uri)
        val cs = charset(charsetName)
        val customPattern = customRegex.takeIf { it.isNotBlank() }?.let {
            try { Regex(it) } catch (_: Exception) { null }
        }

        var globalOffset = 0L
        var chapterStart = 0L
        var chapterTitle: String? = null
        var chapterIndex = 0
        var leftover = "" // Partial line from previous block

        context.contentResolver.openInputStream(uri)?.use { stream ->
            val buffer = ByteArray(BLOCK_SIZE)
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break

                val blockText = leftover + String(buffer, 0, read, cs)
                val lines = blockText.split('\n')

                // Last element may be incomplete — save for next block
                leftover = if (read == BLOCK_SIZE) lines.last() else ""
                val completeLines = if (read == BLOCK_SIZE) lines.dropLast(1) else lines

                for (line in completeLines) {
                    val lineBytes = (line + "\n").toByteArray(cs).size.toLong()
                    if (isChapterTitle(line, customPattern) && globalOffset > chapterStart) {
                        chapters.add(BookChapter(
                            id = "${bookId}_$chapterIndex", bookId = bookId,
                            index = chapterIndex,
                            title = (chapterTitle ?: "正文").trim(),
                            startPosition = chapterStart, endPosition = globalOffset,
                        ))
                        chapterIndex++
                        chapterStart = globalOffset
                        chapterTitle = line.trim()
                    }
                    globalOffset += lineBytes
                }
            }
        }

        // Final chapter
        if (globalOffset > chapterStart) {
            chapters.add(BookChapter(
                id = "${bookId}_$chapterIndex", bookId = bookId,
                index = chapterIndex,
                title = (chapterTitle ?: "正文").trim(),
                startPosition = chapterStart, endPosition = globalOffset,
            ))
        }

        // Auto-split oversized chapters (>200KB)
        return chapters.flatMap { ch ->
            val size = ch.endPosition - ch.startPosition
            if (size <= 200 * 1024) return@flatMap listOf(ch)
            splitLargeChapter(ch, 100 * 1024)
        }.mapIndexed { i, ch -> ch.copy(id = "${bookId}_$i", index = i) }
    }

    private fun splitLargeChapter(chapter: BookChapter, maxSize: Long): List<BookChapter> {
        val parts = mutableListOf<BookChapter>()
        var pos = chapter.startPosition
        var partIdx = 1
        while (pos < chapter.endPosition) {
            val end = (pos + maxSize).coerceAtMost(chapter.endPosition)
            val title = if (partIdx == 1) chapter.title else "${chapter.title} ($partIdx)"
            parts.add(chapter.copy(title = title, startPosition = pos, endPosition = end))
            pos = end
            partIdx++
        }
        return parts
    }

    fun readTxtChapter(context: Context, uri: Uri, chapter: BookChapter): String {
        val charsetName = detectCharset(context, uri)
        val start = chapter.startPosition
        val length = (chapter.endPosition - start).toInt()
        if (length <= 0) return "（本章内容为空）"

        return context.contentResolver.openInputStream(uri)?.use { stream ->
            // Skip directly to chapter start — O(1) instead of O(n)
            var skipped = 0L
            while (skipped < start) {
                val n = stream.skip(start - skipped)
                if (n <= 0) break
                skipped += n
            }
            val buffer = ByteArray(length.coerceAtMost(512 * 1024))
            val read = stream.read(buffer, 0, buffer.size)
            if (read > 0) String(buffer, 0, read, charset(charsetName))
            else "（本章内容为空）"
        } ?: "（本章内容为空）"
    }

    private fun isChapterTitle(line: String, customPattern: Regex? = null): Boolean {
        val trimmed = line.trim()
        if (trimmed.length > 50 || trimmed.isEmpty()) return false
        // Custom pattern takes priority
        if (customPattern != null) return customPattern.matches(trimmed)
        return chapterPatterns.any { it.matches(trimmed) }
    }

    fun detectCharset(context: Context, uri: Uri): String {
        // Check BOM first
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val bom = ByteArray(4)
            val read = stream.read(bom)
            if (read >= 3 && bom[0] == 0xEF.toByte() && bom[1] == 0xBB.toByte() && bom[2] == 0xBF.toByte())
                return "UTF-8"
            if (read >= 2 && bom[0] == 0xFF.toByte() && bom[1] == 0xFE.toByte())
                return "UTF-16LE"
            if (read >= 2 && bom[0] == 0xFE.toByte() && bom[1] == 0xFF.toByte())
                return "UTF-16BE"
        }

        // Heuristic: sample first 8KB
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val sample = ByteArray(8192)
            val sampleRead = stream.read(sample, 0, sample.size)
            if (sampleRead > 0) {
                // If it's valid UTF-8 with multi-byte sequences, it's UTF-8
                if (looksLikeValidUtf8(sample, sampleRead)) return "UTF-8"
                // Otherwise check GBK
                if (looksLikeGbk(sample, sampleRead)) return "GBK"
            }
        }

        return "UTF-8"
    }

    /** Check if data forms valid UTF-8 sequences (with at least some multi-byte chars). */
    private fun looksLikeValidUtf8(data: ByteArray, length: Int): Boolean {
        var i = 0
        var multiByte = 0
        var invalid = 0
        while (i < length) {
            val b = data[i].toInt() and 0xFF
            when {
                b <= 0x7F -> { i++ } // ASCII
                b in 0xC2..0xDF -> { // 2-byte
                    if (i + 1 >= length) { i++; continue }
                    val b2 = data[i + 1].toInt() and 0xFF
                    if (b2 in 0x80..0xBF) { multiByte++; i += 2 } else { invalid++; i++ }
                }
                b in 0xE0..0xEF -> { // 3-byte (CJK lives here)
                    if (i + 2 >= length) { i++; continue }
                    val b2 = data[i + 1].toInt() and 0xFF
                    val b3 = data[i + 2].toInt() and 0xFF
                    if (b2 in 0x80..0xBF && b3 in 0x80..0xBF) { multiByte++; i += 3 } else { invalid++; i++ }
                }
                b in 0xF0..0xF4 -> { // 4-byte
                    if (i + 3 >= length) { i++; continue }
                    val b2 = data[i + 1].toInt() and 0xFF
                    val b3 = data[i + 2].toInt() and 0xFF
                    val b4 = data[i + 3].toInt() and 0xFF
                    if (b2 in 0x80..0xBF && b3 in 0x80..0xBF && b4 in 0x80..0xBF) { multiByte++; i += 4 } else { invalid++; i++ }
                }
                else -> { invalid++; i++ } // 0x80-0xC1, 0xF5+ are invalid UTF-8 lead bytes
            }
        }
        // Valid UTF-8 if we found multi-byte sequences and very few invalid ones
        return multiByte > 0 && invalid <= multiByte / 10
    }

    private fun looksLikeGbk(data: ByteArray, length: Int): Boolean {
        var i = 0
        var gbkPairs = 0
        while (i < length - 1) {
            val b = data[i].toInt() and 0xFF
            if (b in 0x81..0xFE) {
                val b2 = data[i + 1].toInt() and 0xFF
                if (b2 in 0x40..0xFE) { gbkPairs++; i += 2; continue }
            }
            i++
        }
        return gbkPairs > length / 20
    }
}
