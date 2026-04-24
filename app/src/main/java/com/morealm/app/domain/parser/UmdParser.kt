package com.morealm.app.domain.parser

import android.content.Context
import android.net.Uri
import com.morealm.app.domain.entity.BookChapter
import com.morealm.app.core.log.AppLog

/**
 * UMD parser — basic support for UMD e-book format.
 * UMD is a legacy Chinese e-book format with simple structure:
 * header + chapter titles + chapter content blocks.
 *
 * Binary format:
 * - Magic: 0x89 0x9B 0x9A 0xDE (4 bytes)
 * - Chunks: each chunk has type(2) + skip(1) + length(4) + data
 * - Type 0x0001 = header, 0x0083 = chapter titles, 0x0084 = chapter content
 */
object UmdParser {

    private const val MAGIC_0 = 0x89.toByte()
    private const val MAGIC_1 = 0x9B.toByte()
    private const val MAGIC_2 = 0x9A.toByte()
    private const val MAGIC_3 = 0xDE.toByte()

    // Cached parse result
    private var cachedUri: String? = null
    private var cachedTitles: List<String>? = null
    private var cachedContents: List<String>? = null

    fun parseChapters(context: Context, uri: Uri): List<BookChapter> {
        val bookId = uri.toString()
        val (titles, _) = getOrParse(context, uri)
        if (titles.isEmpty()) return emptyList()

        return titles.mapIndexed { index, title ->
            BookChapter(
                id = "${bookId}_$index",
                bookId = bookId,
                index = index,
                title = title.ifBlank { "第${index + 1}章" },
            )
        }
    }

    fun readChapter(context: Context, uri: Uri, chapter: BookChapter): String {
        val (_, contents) = getOrParse(context, uri)
        val idx = chapter.index
        return if (idx in contents.indices) contents[idx] else "（本章内容为空）"
    }

    @Synchronized
    private fun getOrParse(context: Context, uri: Uri): Pair<List<String>, List<String>> {
        val uriStr = uri.toString()
        if (uriStr == cachedUri && cachedTitles != null && cachedContents != null) {
            return cachedTitles!! to cachedContents!!
        }

        val result = parseUmd(context, uri)
        cachedUri = uriStr
        cachedTitles = result.first
        cachedContents = result.second
        return result
    }

    fun releaseCache() {
        cachedUri = null
        cachedTitles = null
        cachedContents = null
    }

    /**
     * Parse UMD file into (titles, contents).
     * UMD uses a chunk-based binary format with zlib-compressed content blocks.
     */
    private fun parseUmd(context: Context, uri: Uri): Pair<List<String>, List<String>> {
        val titles = mutableListOf<String>()
        val contentBlocks = mutableListOf<ByteArray>()
        val chapterOffsets = mutableListOf<Int>()

        try {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return emptyList<String>() to emptyList()

            if (bytes.size < 9) return emptyList<String>() to emptyList()

            // Verify magic
            if (bytes[0] != MAGIC_0 || bytes[1] != MAGIC_1 || bytes[2] != MAGIC_2 || bytes[3] != MAGIC_3) {
                // Not a UMD file, try as plain text fallback
                return fallbackAsText(bytes)
            }

            var pos = 9 // skip header (magic + version + etc)

            while (pos + 7 < bytes.size) {
                // Each chunk: separator(1) + type(2) + skip(1) + length(4) + data
                val sep = bytes[pos].toInt() and 0xFF
                if (sep != 0x23 && sep != 0x24) { pos++; continue } // '#' or '$' separator

                if (pos + 7 >= bytes.size) break
                val chunkType = ((bytes[pos + 1].toInt() and 0xFF) shl 8) or (bytes[pos + 2].toInt() and 0xFF)
                val dataLen = readInt32BE(bytes, pos + 4) - 9
                pos += 8

                if (dataLen <= 0 || pos + dataLen > bytes.size) {
                    if (dataLen > 0) pos += dataLen.coerceAtMost(bytes.size - pos)
                    continue
                }

                when (chunkType) {
                    0x0083 -> {
                        // Chapter title block — UTF-16LE encoded titles
                        parseTitleBlock(bytes, pos, dataLen, titles)
                    }
                    0x0084 -> {
                        // Chapter content block — may be zlib compressed
                        contentBlocks.add(bytes.copyOfRange(pos, pos + dataLen))
                    }
                    0x0081 -> {
                        // Chapter offset table
                        parseOffsetBlock(bytes, pos, dataLen, chapterOffsets)
                    }
                }
                pos += dataLen
            }

            // Decompress and split content
            val fullText = decompressAndJoin(contentBlocks)
            val contents = splitByOffsets(fullText, chapterOffsets, titles.size)

            // Ensure titles and contents match
            while (titles.size < contents.size) titles.add("第${titles.size + 1}章")
            val finalContents = if (contents.size < titles.size) {
                contents + List(titles.size - contents.size) { "" }
            } else contents

            return titles to finalContents

        } catch (e: Exception) {
            AppLog.error("UmdParser", "Parse failed: ${e.message}")
            return emptyList<String>() to emptyList()
        }
    }

    private fun parseTitleBlock(bytes: ByteArray, offset: Int, length: Int, titles: MutableList<String>) {
        // Titles are stored as UTF-16LE strings, each preceded by a 1-byte length
        var pos = offset
        val end = offset + length
        while (pos < end) {
            if (pos >= bytes.size) break
            val titleLen = (bytes[pos].toInt() and 0xFF)
            pos++
            if (titleLen == 0 || pos + titleLen > end) break
            try {
                val title = String(bytes, pos, titleLen, Charsets.UTF_16LE).trim()
                if (title.isNotEmpty()) titles.add(title)
            } catch (_: Exception) {}
            pos += titleLen
        }
    }

    private fun parseOffsetBlock(bytes: ByteArray, offset: Int, length: Int, offsets: MutableList<Int>) {
        var pos = offset
        val end = offset + length
        while (pos + 4 <= end) {
            offsets.add(readInt32BE(bytes, pos))
            pos += 4
        }
    }

    private fun decompressAndJoin(blocks: List<ByteArray>): String {
        val sb = StringBuilder()
        for (block in blocks) {
            try {
                // Try zlib decompression first
                val inflater = java.util.zip.Inflater()
                inflater.setInput(block)
                val output = ByteArray(block.size * 4)
                val decompressed = ByteArray(0).toMutableList()
                while (!inflater.finished()) {
                    val count = inflater.inflate(output)
                    if (count == 0) break
                    for (i in 0 until count) decompressed.add(output[i])
                }
                inflater.end()
                if (decompressed.isNotEmpty()) {
                    sb.append(String(decompressed.toByteArray(), Charsets.UTF_16LE))
                }
            } catch (_: Exception) {
                // Not compressed, try as raw UTF-16LE
                try {
                    sb.append(String(block, Charsets.UTF_16LE))
                } catch (_: Exception) {}
            }
        }
        return sb.toString()
    }

    private fun splitByOffsets(text: String, offsets: List<Int>, titleCount: Int): List<String> {
        if (text.isEmpty()) return emptyList()
        if (offsets.isEmpty() || titleCount <= 1) {
            // No offset info — split evenly or return as single chapter
            if (titleCount <= 1) return listOf(text)
            val chunkSize = text.length / titleCount
            return (0 until titleCount).map { i ->
                val start = i * chunkSize
                val end = if (i == titleCount - 1) text.length else (i + 1) * chunkSize
                text.substring(start, end.coerceAtMost(text.length))
            }
        }

        // Use offsets (they're character offsets into the decompressed text)
        val contents = mutableListOf<String>()
        for (i in offsets.indices) {
            val start = (offsets[i] / 2).coerceAtMost(text.length) // byte offset → char offset (UTF-16)
            val end = if (i + 1 < offsets.size) (offsets[i + 1] / 2).coerceAtMost(text.length) else text.length
            if (start < end) {
                contents.add(text.substring(start, end))
            } else {
                contents.add("")
            }
        }
        return contents
    }

    /** Fallback: treat as plain text if not valid UMD */
    private fun fallbackAsText(bytes: ByteArray): Pair<List<String>, List<String>> {
        val text = try { String(bytes, Charsets.UTF_8) } catch (_: Exception) { return emptyList<String>() to emptyList() }
        return listOf("全文") to listOf(text)
    }

    private fun readInt32BE(bytes: ByteArray, offset: Int): Int {
        return ((bytes[offset].toInt() and 0xFF) shl 24) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
                (bytes[offset + 3].toInt() and 0xFF)
    }
}
