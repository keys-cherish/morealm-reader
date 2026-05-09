package com.morealm.app.domain.parser

import android.content.Context
import android.net.Uri
import com.morealm.app.domain.entity.BookChapter
import com.morealm.app.core.log.AppLog
import java.nio.charset.Charset

/**
 * MOBI / AZW3 parser.
 *
 * Pipeline:
 *   1. Read whole file into memory (MOBI 通常 <10MB，可接受)。
 *   2. Parse PDB header → record offsets。
 *   3. Parse record 0 → PalmDOC header (compression / textLength / recordCount)
 *      + 可选 MOBI header (textEncoding)。
 *   4. For each text record: 按 PalmDOC LZ77 解压拼接。
 *   5. 按 textLength 截断后用 textEncoding 解码为 String。
 *   6. 交给现有章节正则做切分。
 *
 * 若任一步骤失败，兜底走最早的 raw-bytes 方案（把原始字节当 UTF-8 读后找 <body>），
 * 保证老路径不退化。
 */
object MobiParser {

    private const val TAG = "MobiParser"

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
            AppLog.error(TAG, "parseChapters failed: ${e.message}")
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

        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
        val text = extractPalmDocText(bytes) ?: extractRawFallback(bytes)
        cachedUri = uriStr
        cachedText = text
        return text
    }

    fun releaseCache() {
        cachedUri = null
        cachedText = null
    }

    // ───────────────────────── MOBI / PalmDOC 解压路径 ─────────────────────────

    /**
     * 按 PDB + PalmDOC 规范解压 text 记录。解析失败返回 null，交给 [extractRawFallback] 兜底。
     */
    private fun extractPalmDocText(bytes: ByteArray): String? {
        if (bytes.size < 78 + 8) return null
        val numRecords = readU16(bytes, 76)
        if (numRecords < 2) return null
        val recordListEnd = 78 + numRecords * 8
        if (recordListEnd > bytes.size) return null

        val recordOffsets = IntArray(numRecords) { i ->
            readU32(bytes, 78 + i * 8)
        }
        val rec0 = recordOffsets[0]
        if (rec0 < 0 || rec0 + 16 > bytes.size) return null

        val compression = readU16(bytes, rec0)
        val textLength = readU32(bytes, rec0 + 4)
        val recordCount = readU16(bytes, rec0 + 8)
        if (recordCount <= 0 || recordCount >= numRecords) return null

        // 可选 MOBI header：record 0 从 offset 16 起若 magic == "MOBI"，textEncoding 在 +12
        // offset（也就是 rec0 + 28）。MOBI 中常见 65001=UTF-8 / 1252=CP1252。
        val charset = detectCharset(bytes, rec0)

        val out = GrowingByteArray(initial = textLength.coerceIn(4096, 1 shl 22))
        for (i in 1..recordCount) {
            if (i >= numRecords) break
            val start = recordOffsets[i]
            val end = if (i + 1 < numRecords) recordOffsets[i + 1] else bytes.size
            if (start < 0 || end <= start || end > bytes.size) continue
            val recordData = bytes.copyOfRange(start, end)
            when (compression) {
                1 -> out.write(recordData, 0, recordData.size)
                2 -> palmDocDecompress(recordData, out)
                else -> {
                    AppLog.warn(TAG, "不支持的压缩类型: $compression")
                    return null
                }
            }
            // 按 textLength 提前退出，避免 trailing entries 污染尾部
            if (out.size >= textLength) break
        }
        val raw = out.toTrimmedByteArray(textLength)
        return try {
            String(raw, charset)
        } catch (e: Exception) {
            AppLog.warn(TAG, "按 ${charset.name()} 解码失败，回退 UTF-8: ${e.message}")
            String(raw, Charsets.UTF_8)
        }
    }

    private fun detectCharset(bytes: ByteArray, rec0: Int): Charset {
        val magicStart = rec0 + 16
        if (magicStart + 16 > bytes.size) return Charsets.UTF_8
        val magic = String(bytes, magicStart, 4, Charsets.US_ASCII)
        if (magic != "MOBI") return Charsets.UTF_8
        val enc = readU32(bytes, rec0 + 28)
        return when (enc) {
            65001 -> Charsets.UTF_8
            1252 -> runCatching { Charset.forName("windows-1252") }.getOrDefault(Charsets.UTF_8)
            else -> Charsets.UTF_8
        }
    }

    /**
     * PalmDOC LZ77 解压算法（按字节扫描）：
     *   - b == 0            → 字面 0
     *   - 1..8              → 紧跟 b 个字节原样输出（literal run）
     *   - 9..127            → 输出字节 b
     *   - 128..191          → 与下一字节组成 14 位 back-reference：
     *                           distance = ((b & 0x3F) << 5) | (b2 >> 3)
     *                           length   = (b2 & 0x07) + 3
     *                         自引用（distance < length）按字节逐个复制。
     *   - 192..255          → 输出 ASCII 空格 + (b ^ 0x80)。
     */
    private fun palmDocDecompress(input: ByteArray, out: GrowingByteArray) {
        var i = 0
        while (i < input.size) {
            val b = input[i].toInt() and 0xFF
            i++
            when {
                b == 0 -> out.writeByte(0)
                b in 1..8 -> {
                    val end = (i + b).coerceAtMost(input.size)
                    out.write(input, i, end - i)
                    i = end
                }
                b in 9..127 -> out.writeByte(b)
                b in 128..191 -> {
                    if (i >= input.size) break
                    val b2 = input[i].toInt() and 0xFF
                    i++
                    val combined = (b shl 8) or b2
                    val distance = (combined shr 3) and 0x07FF
                    val length = (combined and 0x0007) + 3
                    out.copyFromSelf(distance, length)
                }
                else -> {
                    out.writeByte(0x20)
                    out.writeByte(b xor 0x80)
                }
            }
        }
    }

    // ───────────────────────── Fallback：原 raw-bytes 路径 ─────────────────────────

    private fun extractRawFallback(bytes: ByteArray): String {
        if (bytes.size < 78) return ""
        return try {
            val text = String(bytes, Charsets.UTF_8)
            val bodyMatch = Regex("<body[^>]*>(.*)</body>", RegexOption.DOT_MATCHES_ALL).find(text)
            if (bodyMatch != null) {
                bodyMatch.groupValues[1]
            } else {
                val textLength = readU32(bytes, 84)
                text.take(textLength.coerceAtMost(5_000_000))
            }
        } catch (_: Exception) { "" }
    }

    // ───────────────────────── 工具 ─────────────────────────

    private fun readU16(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)

    private fun readU32(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 24) or
        ((b[off + 1].toInt() and 0xFF) shl 16) or
        ((b[off + 2].toInt() and 0xFF) shl 8) or
        (b[off + 3].toInt() and 0xFF)

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

    /**
     * 可随机自引用的字节缓冲。专为 PalmDOC back-reference 准备：
     * [copyFromSelf] 按字节逐个写，支持 distance < length 的滚动重复模式。
     */
    private class GrowingByteArray(initial: Int = 4096) {
        private var buf: ByteArray = ByteArray(initial.coerceAtLeast(64))
        var size: Int = 0
            private set

        fun writeByte(v: Int) {
            ensure(size + 1)
            buf[size] = v.toByte()
            size++
        }

        fun write(src: ByteArray, off: Int, count: Int) {
            if (count <= 0) return
            ensure(size + count)
            System.arraycopy(src, off, buf, size, count)
            size += count
        }

        /** 从当前尾部前 [distance] 处连续拷贝 [length] 字节，支持自引用。 */
        fun copyFromSelf(distance: Int, length: Int) {
            if (distance <= 0 || length <= 0) return
            ensure(size + length)
            repeat(length) {
                val srcIndex = size - distance
                if (srcIndex < 0 || srcIndex >= size) return
                buf[size] = buf[srcIndex]
                size++
            }
        }

        fun toTrimmedByteArray(max: Int): ByteArray {
            val end = if (max in 1 until size) max else size
            return buf.copyOf(end)
        }

        private fun ensure(needed: Int) {
            if (needed <= buf.size) return
            var newCap = buf.size
            while (newCap < needed) newCap = (newCap * 2).coerceAtLeast(needed)
            buf = buf.copyOf(newCap)
        }
    }
}
