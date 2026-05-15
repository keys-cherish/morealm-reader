package com.morealm.app.domain.parser

import android.content.Context
import android.net.Uri
import com.morealm.app.core.log.AppLog
import com.morealm.app.domain.entity.BookFormat

/**
 * 漫画书检测器。用于在导入 MOBI/AZW3/CBZ 时分流到独立的漫画阅读管线。
 *
 * ## 设计原则：零正文解压
 *
 * MOBI/AZW3 漫画文件常常 50–500 MB，全量解压 PalmDOC 文本 + 正则去标签会触发 OOM
 * 并且很慢（数秒）。本类只读文件**前 64 KB**，覆盖 PDB header + record table +
 * record 0（MOBI header + EXTH）—— 足以做出判断，单文件耗时通常 < 5 ms。
 *
 * ## 判定优先级
 *
 *  1. **EXTH cdeType / book-type 元数据**（毫秒级，O(1)）
 *     - `cdeType == "MAGZ"` → 杂志（漫画归类）
 *     - 任意 `book-type` 字符串包含 `comic` / `manga` → 漫画
 *
 *  2. **PDB record 大小比例**（O(N) N=record 数，仅算偏移量差，不读字节）
 *     - 文本 records 总字节（rec1 .. rec(firstImage-1)）
 *     - 图片 records 总字节（rec(firstImage) .. rec(last)）
 *     - 图片占比 > 80% → 漫画
 *
 *  3. 上述都失败 → false（保守默认，让用户走熟悉的小说阅读器）
 *
 * 与小说渲染管线解耦：命中的本子打 `Book.isComic=true`，打开后走独立的
 * `ui/reader/comic/ComicReaderScreen`（LazyColumn + Coil），不触碰 ChapterProvider /
 * CanvasRenderer。漫画专属交互（缩放 / 翻图 / 条漫滚动）的演进不影响小说精排。
 */
object ComicBookDetector {

    private const val TAG = "ComicDetector"

    /** 读取文件头的最大字节数。覆盖绝大多数 MOBI 的 PDB header + record table + record 0。 */
    private const val HEADER_PROBE_BYTES = 64 * 1024

    /** 图片 records 占总文件比例的阈值。超过此值视为漫画。 */
    private const val IMAGE_RATIO_THRESHOLD = 0.80f

    /** 统一入口 —— 根据格式分发。CBZ 按定义就是漫画，直接 true。 */
    fun detect(context: Context, uri: Uri, format: BookFormat): Boolean {
        return when (format) {
            BookFormat.MOBI, BookFormat.AZW3 -> detectMobi(context, uri)
            BookFormat.CBZ -> true
            BookFormat.EPUB -> runCatching { EpubParser.detectIsComic(context, uri) }
                .onFailure { AppLog.warn(TAG, "detectEpub failed: ${it.message}") }
                .getOrDefault(false)
            else -> false
        }
    }

    /**
     * 检测 MOBI/AZW3 是否为漫画。不抛异常 —— 失败返回 false（保守默认）。
     */
    fun detectMobi(context: Context, uri: Uri): Boolean {
        return try {
            val head = readHead(context, uri) ?: return false
            val pdb = parsePdbStructure(head) ?: return false

            // 方案 1：EXTH 元数据
            val exth = parseExthRecords(head, pdb)
            if (exth != null) {
                val cdeType = exth[EXTH_CDE_TYPE]?.let { String(it, Charsets.US_ASCII).trim() }
                if (cdeType == "MAGZ") {
                    AppLog.info(TAG, "detectMobi: cdeType=MAGZ → comic")
                    return true
                }
                // book-type 字段值通常是 "comic" / "manga" / "children" 之类
                val bookType = exth[EXTH_BOOK_TYPE]?.let { String(it, Charsets.UTF_8).lowercase() }
                if (bookType != null && ("comic" in bookType || "manga" in bookType)) {
                    AppLog.info(TAG, "detectMobi: book-type=$bookType → comic")
                    return true
                }
            }

            // 方案 2：record 大小比例
            val firstImage = pdb.firstImageIndex
            if (firstImage <= 0 || firstImage >= pdb.numRecords) {
                AppLog.info(TAG, "detectMobi: no image records → not comic")
                return false
            }
            val offsets = pdb.recordOffsets
            val fileSize = pdb.fileSize
            // 文本 records 字节累计：rec1 .. rec(firstImage-1)（rec0 是 MOBI header 不算）
            var textBytes = 0L
            for (i in 1 until firstImage) {
                val end = if (i + 1 < pdb.numRecords) offsets[i + 1] else fileSize
                textBytes += (end - offsets[i]).coerceAtLeast(0)
            }
            // 图片 records 字节累计：rec(firstImage) .. rec(numRecords-1)
            var imageBytes = 0L
            for (i in firstImage until pdb.numRecords) {
                val end = if (i + 1 < pdb.numRecords) offsets[i + 1] else fileSize
                imageBytes += (end - offsets[i]).coerceAtLeast(0)
            }
            val total = (textBytes + imageBytes).coerceAtLeast(1)
            val ratio = imageBytes.toFloat() / total
            val isComic = ratio >= IMAGE_RATIO_THRESHOLD
            AppLog.info(
                TAG,
                "detectMobi: text=${textBytes}B image=${imageBytes}B ratio=${"%.2f".format(ratio)} → isComic=$isComic",
            )
            isComic
        } catch (e: Exception) {
            AppLog.warn(TAG, "detectMobi failed: ${e.message}")
            false
        }
    }

    // ── 内部：读头 + 解析 PDB / EXTH ──────────────────────────────────────────

    private fun readHead(context: Context, uri: Uri): ByteArray? {
        return context.contentResolver.openInputStream(uri)?.use { stream ->
            val buf = ByteArray(HEADER_PROBE_BYTES)
            var total = 0
            while (total < HEADER_PROBE_BYTES) {
                val n = stream.read(buf, total, HEADER_PROBE_BYTES - total)
                if (n <= 0) break
                total += n
            }
            if (total < 78) null else buf.copyOf(total)
        }
    }

    /**
     * PDB header + record table + 关键字段（仅 metadata，不含正文/图片字节）。
     * @param fileSize 实际文件总长度 —— 通过 ContentResolver 拿到（用于算最后一个 record 大小）。
     */
    private data class PdbStructure(
        val numRecords: Int,
        val recordOffsets: IntArray,
        val firstImageIndex: Int,
        val mobiHeaderOffset: Int,    // = recordOffsets[0]
        val mobiHeaderLength: Int,    // 在 rec0 + 20 的 U32
        val exthFlag: Int,            // 在 rec0 + 128 的 U32，bit 6 表示有 EXTH
        val fileSize: Int,
    )

    private fun parsePdbStructure(head: ByteArray): PdbStructure? {
        if (head.size < 78 + 8) return null
        val numRecords = readU16(head, 76)
        if (numRecords < 2) return null
        val recordListEnd = 78 + numRecords * 8
        if (recordListEnd > head.size) return null

        val offsets = IntArray(numRecords) { i -> readU32(head, 78 + i * 8) }
        val rec0 = offsets[0]
        if (rec0 < 0 || rec0 + 132 > head.size) return null

        // MOBI magic at rec0 + 16
        val magic = String(head, rec0 + 16, 4, Charsets.US_ASCII)
        if (magic != "MOBI") return null

        val mobiHeaderLength = readU32(head, rec0 + 20)
        val firstImageIndex = readU32(head, rec0 + 108)
        val exthFlag = readU32(head, rec0 + 128)

        // 文件总长度：用最后一个 record 的 offset + 估算的尾部长度（不影响判断精度）
        // 真实的 fileSize 需要 ContentResolver.openAssetFileDescriptor 拿，但我们也可以用
        // 最后一个 record 的 offset 当作估算下界 —— 漫画书 record 大小比例算法对 ±KB 误差不敏感。
        val lastRecordOffset = offsets[numRecords - 1]
        val fileSize = lastRecordOffset.coerceAtLeast(head.size)

        return PdbStructure(
            numRecords = numRecords,
            recordOffsets = offsets,
            firstImageIndex = firstImageIndex,
            mobiHeaderOffset = rec0,
            mobiHeaderLength = mobiHeaderLength,
            exthFlag = exthFlag,
            fileSize = fileSize,
        )
    }

    /**
     * EXTH 记录解析。EXTH 在 MOBI header 之后，结构：
     *   - magic "EXTH" (4 字节)
     *   - header length (U32)
     *   - record count (U32)
     *   - 每条记录: recordType (U32) + recordLength (U32, 含头部 8 字节) + data
     *
     * 返回 Map<recordType, data>。data 为原始字节（用时按需 decode）。
     * exthFlag bit 6 (0x40) 未置位时返回 null。
     */
    private fun parseExthRecords(head: ByteArray, pdb: PdbStructure): Map<Int, ByteArray>? {
        if ((pdb.exthFlag and 0x40) == 0) return null
        val exthStart = pdb.mobiHeaderOffset + pdb.mobiHeaderLength
        if (exthStart + 12 > head.size) return null
        val magic = String(head, exthStart, 4, Charsets.US_ASCII)
        if (magic != "EXTH") return null

        val count = readU32(head, exthStart + 8)
        if (count !in 1..2048) return null   // 防御性上限

        val records = HashMap<Int, ByteArray>(count.coerceAtMost(64))
        var pos = exthStart + 12
        repeat(count) {
            if (pos + 8 > head.size) return@repeat
            val type = readU32(head, pos)
            val length = readU32(head, pos + 4)
            if (length < 8 || pos + length > head.size) return@repeat
            val data = head.copyOfRange(pos + 8, pos + length)
            records[type] = data
            pos += length
        }
        return records
    }

    /** EXTH `cdeType` —— 4 字节 ASCII，"EBOK"/"EBSP"/"PDOC"/"MAGZ"。 */
    private const val EXTH_CDE_TYPE = 501

    /** EXTH `book-type` —— UTF-8 字符串，常见值 "comic" / "manga" / "children"。 */
    private const val EXTH_BOOK_TYPE = 522

    private fun readU16(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)

    private fun readU32(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 24) or
        ((b[off + 1].toInt() and 0xFF) shl 16) or
        ((b[off + 2].toInt() and 0xFF) shl 8) or
        (b[off + 3].toInt() and 0xFF)
}
