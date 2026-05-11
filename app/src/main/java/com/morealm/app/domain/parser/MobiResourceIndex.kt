package com.morealm.app.domain.parser

import android.content.Context
import android.net.Uri
import com.morealm.app.core.log.AppLog

/**
 * MOBI/AZW3 图片资源索引 —— 按需流式读取的核心。
 *
 * 只在内存中保存每张图片在原始文件中的 offset + length，
 * 不预解压到 cacheDir，翻页时才通过 [MobiResourceLoader.readBytes] 流式读取。
 */
data class MobiImageEntry(
    val pdbIndex: Int,
    val offset: Int,
    val length: Int,
    val mime: String,
)

class MobiResourceIndex(
    val uri: Uri,
    val hash: String,
    val pdbRecordOffsets: IntArray,
    val pdbFileSize: Int,
    val firstImageIndex: Int,
    val images: List<MobiImageEntry>,
)

/**
 * 单例加载器 —— 维护当前打开的 MOBI 文件的图片索引。
 *
 * 生命周期与 MobiParser.cachedUri 对齐：
 *   - [activate] 在 parseChapters / readChapter 入口调用
 *   - [release] 在 MobiParser.releaseCache() 时一并调用
 *   - [readBytes] 在 ImageCache 绘制阶段按需调用
 */
object MobiResourceLoader {

    private const val TAG = "MobiResource"

    private var current: MobiResourceIndex? = null

    @Synchronized
    fun activate(context: Context, uri: Uri): MobiResourceIndex? {
        val hash = uri.hashCode().toString()
        current?.let { if (it.hash == hash) return it }

        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: return null
        val index = buildIndex(uri, hash, bytes) ?: return null
        current = index
        return index
    }

    @Synchronized
    fun release() {
        current = null
    }

    fun lookup(hash: String, imageIndex: Int): MobiImageEntry? {
        val idx = current ?: return null
        if (idx.hash != hash) return null
        val zeroIdx = imageIndex - 1
        if (zeroIdx < 0 || zeroIdx >= idx.images.size) return null
        return idx.images[zeroIdx]
    }

    fun readBytes(context: Context, hash: String, imageIndex: Int): ByteArray? {
        val idx = current ?: return null
        if (idx.hash != hash) return null
        val entry = lookup(hash, imageIndex) ?: return null
        return try {
            context.contentResolver.openInputStream(idx.uri)?.use { stream ->
                var skipped = 0L
                while (skipped < entry.offset) {
                    val n = stream.skip((entry.offset - skipped).toLong())
                    if (n <= 0) break
                    skipped += n
                }
                val buf = ByteArray(entry.length)
                var read = 0
                while (read < entry.length) {
                    val n = stream.read(buf, read, entry.length - read)
                    if (n <= 0) break
                    read += n
                }
                if (read >= 4) buf.copyOf(read) else null
            }
        } catch (e: Exception) {
            AppLog.warn(TAG, "readBytes failed idx=$imageIndex: ${e.message}")
            null
        }
    }

    // ── 内部构建 ──

    private fun buildIndex(uri: Uri, hash: String, bytes: ByteArray): MobiResourceIndex? {
        if (bytes.size < 78 + 8) return null
        val numRecords = readU16(bytes, 76)
        if (numRecords < 2) return null
        val recordListEnd = 78 + numRecords * 8
        if (recordListEnd > bytes.size) return null

        val recordOffsets = IntArray(numRecords) { i -> readU32(bytes, 78 + i * 8) }
        val rec0 = recordOffsets[0]
        if (rec0 < 0 || rec0 + 112 > bytes.size) return null

        // MOBI magic check
        val magicStart = rec0 + 16
        if (magicStart + 4 > bytes.size) return null
        val magic = String(bytes, magicStart, 4, Charsets.US_ASCII)
        if (magic != "MOBI") return null

        // firstImageIndex at rec0 + 108
        val firstImageIndex = readU32(bytes, rec0 + 108)
        if (firstImageIndex <= 0 || firstImageIndex >= numRecords) {
            AppLog.info(TAG, "No image records (firstImageIndex=$firstImageIndex)")
            return MobiResourceIndex(uri, hash, recordOffsets, bytes.size, firstImageIndex, emptyList())
        }

        // Scan image records
        val images = mutableListOf<MobiImageEntry>()
        for (i in firstImageIndex until numRecords) {
            val start = recordOffsets[i]
            val end = if (i + 1 < numRecords) recordOffsets[i + 1] else bytes.size
            if (start < 0 || end <= start || end > bytes.size) continue
            val length = end - start
            if (length < 4) continue
            val mime = detectImageMime(bytes, start) ?: continue
            images.add(MobiImageEntry(pdbIndex = i, offset = start, length = length, mime = mime))
        }
        AppLog.info(TAG, "Built index: ${images.size} images from record $firstImageIndex")
        return MobiResourceIndex(uri, hash, recordOffsets, bytes.size, firstImageIndex, images)
    }

    private fun detectImageMime(bytes: ByteArray, offset: Int): String? {
        if (offset + 8 > bytes.size) return null
        val b0 = bytes[offset].toInt() and 0xFF
        val b1 = bytes[offset + 1].toInt() and 0xFF
        val b2 = bytes[offset + 2].toInt() and 0xFF
        return when {
            // JPEG: FF D8 FF
            b0 == 0xFF && b1 == 0xD8 && b2 == 0xFF -> "image/jpeg"
            // PNG: 89 50 4E 47
            b0 == 0x89 && b1 == 0x50 && b2 == 0x4E
                && (bytes[offset + 3].toInt() and 0xFF) == 0x47 -> "image/png"
            // GIF: 47 49 46 38
            b0 == 0x47 && b1 == 0x49 && b2 == 0x46
                && (bytes[offset + 3].toInt() and 0xFF) == 0x38 -> "image/gif"
            // BMP: 42 4D
            b0 == 0x42 && b1 == 0x4D -> "image/bmp"
            // WebP: RIFF....WEBP
            b0 == 0x52 && b1 == 0x49 && b2 == 0x46
                && (bytes[offset + 3].toInt() and 0xFF) == 0x46
                && offset + 12 <= bytes.size
                && bytes[offset + 8] == 0x57.toByte()
                && bytes[offset + 9] == 0x45.toByte()
                && bytes[offset + 10] == 0x42.toByte()
                && bytes[offset + 11] == 0x50.toByte() -> "image/webp"
            else -> null
        }
    }

    private fun readU16(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)

    private fun readU32(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 24) or
        ((b[off + 1].toInt() and 0xFF) shl 16) or
        ((b[off + 2].toInt() and 0xFF) shl 8) or
        (b[off + 3].toInt() and 0xFF)
}
