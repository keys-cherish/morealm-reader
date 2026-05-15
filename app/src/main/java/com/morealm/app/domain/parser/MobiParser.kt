package com.morealm.app.domain.parser

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.morealm.app.domain.entity.BookChapter
import com.morealm.app.core.log.AppLog
import com.morealm.app.domain.parser.mobi.KF6Book
import com.morealm.app.domain.parser.mobi.KF8Book
import com.morealm.app.domain.parser.mobi.MobiBook
import com.morealm.app.domain.parser.mobi.MobiReader
import com.morealm.app.domain.parser.mobi.entities.TOC
import java.nio.charset.Charset

/**
 * MOBI / AZW3 parser —— 两条路径：
 *
 * 1. **主路径（NCX 真章节）**：用 `mobi/` 子包内参考 Legado 的完整 [MobiReader]
 *    打开 PDB → [KF6Book] / [KF8Book]，从 NCX index 拿真实章节列表 + 通过
 *    `kindle:pos:fid:xxxx:off:yyyy` 定位每章 HTML 内容（HUFF/CDIC 解压 / KF8 fragment 拼装
 *    都在 Legado lib 内完成）。
 *
 * 2. **Fallback 路径（regex 切章节）**：极旧的 MOBI 没 NCX / Legado lib 抛错时启用。
 *    走原 PalmDOC LZ77 解压 + chapterRegex 全文匹配 + title dedup。准确率低但能保命。
 *
 * 还保留两个独立 helper：
 * - [formatMobiHtml]：HTML 清洗 + img URL 替换（mobi-img:// 协议） —— 两路径共用
 * - [extractCover]：封面提取，独立走 [MobiResourceLoader]，不依赖本类 cache
 */
object MobiParser {

    private const val TAG = "MobiParser"

    // ── Cached MobiBook + PFD（主路径用） ──
    private data class CachedMobi(val pfd: ParcelFileDescriptor, val book: MobiBook)
    private var mobiCached: CachedMobi? = null
    private var mobiCachedUri: String? = null

    // ── Cached PalmDOC text（fallback 路径用） ──
    private var textCachedUri: String? = null
    private var textCached: String? = null

    @Synchronized
    private fun <T> withMobiBook(context: Context, uri: Uri, block: (MobiBook) -> T): T? {
        val uriStr = uri.toString()
        mobiCached?.let { c ->
            if (uriStr == mobiCachedUri) {
                return runCatching { block(c.book) }.onFailure {
                    AppLog.warn(TAG, "withMobiBook cached call failed: ${it.message}")
                }.getOrNull()
            }
        }
        // 切换书 → 关老的
        closeCachedMobi()
        return try {
            val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return null
            val book = MobiReader().readMobi(pfd)
            mobiCached = CachedMobi(pfd, book)
            mobiCachedUri = uriStr
            block(book)
        } catch (e: Throwable) {
            AppLog.warn(TAG, "withMobiBook open/parse failed: ${e.message}")
            closeCachedMobi()
            null
        }
    }

    private fun closeCachedMobi() {
        mobiCached?.runCatching { pfd.close() }
        mobiCached = null
        mobiCachedUri = null
    }

    // ── 主入口：parseChapters / readChapter ──

    fun parseChapters(context: Context, uri: Uri): List<BookChapter> {
        val bookId = uri.toString()

        // 主路径：NCX → 扁平化 → BookChapter 列表
        val ncxFlat = withMobiBook(context, uri) { book ->
            val toc = book.toc.orEmpty()
            if (toc.isEmpty()) return@withMobiBook null
            val flat = ArrayList<Pair<String, String>>()
            fun walk(items: List<TOC>) {
                for (item in items) {
                    if (item.href.isNotBlank()) flat.add(item.label.trim() to item.href)
                    item.subitems?.let(::walk)
                }
            }
            walk(toc)
            flat.takeIf { it.isNotEmpty() }
        }

        if (!ncxFlat.isNullOrEmpty()) {
            AppLog.info(TAG, "parseChapters via NCX: ${ncxFlat.size} chapters")
            return ncxFlat.mapIndexed { i, (label, href) ->
                val nextHref = ncxFlat.getOrNull(i + 1)?.second
                BookChapter(
                    id = "${bookId}_$i",
                    bookId = bookId,
                    index = i,
                    title = label.ifBlank { "第 ${i + 1} 章" },
                    url = href,
                    nextUrl = nextHref,
                )
            }
        }

        // Fallback：旧 regex 路径（NCX 不可用 / Legado lib 抛错时启用）
        AppLog.info(TAG, "NCX 不可用，fallback 到 regex 路径")
        return parseChaptersLegacy(context, uri)
    }

    fun readChapter(context: Context, uri: Uri, chapter: BookChapter): String {
        // 主路径：用 NCX href 取真章节内容
        if (chapter.url.isNotBlank() && chapter.url.startsWith("kindle:")) {
            val html = withMobiBook(context, uri) { book ->
                runCatching {
                    when (book) {
                        is KF8Book -> book.getTextByHref(chapter.url, chapter.nextUrl.orEmpty())
                        is KF6Book -> {
                            val section = book.getSectionByHref(chapter.url) ?: return@runCatching ""
                            book.getSectionText(section)
                        }
                        else -> ""
                    }
                }.onFailure {
                    AppLog.warn(TAG, "readChapter via NCX failed: ${it.message}")
                }.getOrNull().orEmpty()
            }
            if (!html.isNullOrBlank()) return formatMobiHtml(html, uri)
            AppLog.warn(TAG, "readChapter NCX path empty, fallback to legacy")
        }
        // Fallback：旧 byte-range 切片
        return readChapterLegacy(context, uri, chapter)
    }

    // ── Fallback：旧 regex + PalmDOC 路径（NCX 不可用时） ──

    private fun parseChaptersLegacy(context: Context, uri: Uri): List<BookChapter> {
        val bookId = uri.toString()
        val chapters = mutableListOf<BookChapter>()
        try {
            val text = getOrExtractText(context, uri)
            if (text.isEmpty()) return chapters
            val chapterRegex = Regex(
                "<h[1-3][^>]*>(.*?)</h[1-3]>|" +
                    "第[零一二三四五六七八九十百千万\\d]+[章节回].*|" +
                    "Chapter\\s+\\d+.*",
                RegexOption.IGNORE_CASE,
            )
            val rawMatches = chapterRegex.findAll(text).toList()
            val matches = rawMatches
                .groupBy { cleanHtmlTitle(it.value) }
                .mapValues { (_, ms) -> ms.maxByOrNull { it.range.first }!! }
                .values
                .sortedBy { it.range.first }
            if (matches.isEmpty()) {
                chapters.add(
                    BookChapter(
                        id = "${bookId}_0", bookId = bookId, index = 0,
                        title = "全文",
                        startPosition = 0, endPosition = text.length.toLong(),
                    ),
                )
                return chapters
            }
            val firstMatchStart = matches[0].range.first
            if (firstMatchStart > 0) {
                chapters.add(
                    BookChapter(
                        id = "${bookId}_0", bookId = bookId, index = 0,
                        title = "前言",
                        startPosition = 0, endPosition = firstMatchStart.toLong(),
                    ),
                )
            }
            matches.forEachIndexed { i, m ->
                val start = m.range.first
                val end = if (i + 1 < matches.size) matches[i + 1].range.first else text.length
                chapters.add(
                    BookChapter(
                        id = "", bookId = bookId, index = 0,
                        title = cleanHtmlTitle(m.value),
                        startPosition = start.toLong(), endPosition = end.toLong(),
                    ),
                )
            }
            // Merge short
            val merged = mutableListOf<BookChapter>()
            for (ch in chapters) {
                val last = merged.lastOrNull()
                if (last != null && (ch.startPosition - last.startPosition) < 100) {
                    val combinedTitle = if (ch.title.length > last.title.length) ch.title else last.title
                    merged[merged.size - 1] = last.copy(title = combinedTitle, endPosition = ch.endPosition)
                } else {
                    merged.add(ch)
                }
            }
            chapters.clear()
            merged.forEachIndexed { i, c -> chapters.add(c.copy(id = "${bookId}_$i", index = i)) }
        } catch (e: Exception) {
            AppLog.error(TAG, "parseChaptersLegacy failed: ${e.message}")
        }
        return chapters
    }

    private fun readChapterLegacy(context: Context, uri: Uri, chapter: BookChapter): String {
        return try {
            val text = getOrExtractText(context, uri)
            val start = chapter.startPosition.toInt().coerceIn(0, text.length)
            val end = chapter.endPosition.toInt().coerceIn(start, text.length)
            formatMobiHtml(text.substring(start, end), uri)
        } catch (_: Exception) {
            ""
        }
    }

    // ── HTML 清洗（共用） ──

    /**
     * 把 MOBI HTML 片段转为阅读器可渲染的格式：
     *   - 保留 `<img>` 标签并把 recindex / kindle:embed 引用替换为 mobi-img:// 协议
     *   - 剥除 SVG 容器壳
     *   - XML 声明 / DOCTYPE / 注释 / CDATA 完全剥除（截图见用户实测）
     *   - 其他 HTML 标签按 EPUB 风格清理（保留 br/p 换行语义）
     */
    private fun formatMobiHtml(html: String, uri: Uri): String {
        val hash = uri.hashCode().toString()

        var result = recindexAttrPattern.replace(html) { match ->
            val idx = match.groupValues[1].trimStart('0').ifEmpty { "1" }
            "<img src=\"mobi-img://$hash/$idx\"/>"
        }
        result = recindexSrcPattern.replace(result) { match ->
            val idx = match.groupValues[1].trimStart('0').ifEmpty { "1" }
            "<img src=\"mobi-img://$hash/$idx\"/>"
        }
        result = kindleEmbedPattern.replace(result) { match ->
            val idx = match.groupValues[1].trimStart('0').ifEmpty { "1" }
            "<img src=\"mobi-img://$hash/$idx\"/>"
        }
        result = svgWrapperPattern.replace(result) { match ->
            val inner = match.groupValues[1]
            if (inner.contains("<img")) inner else ""
        }

        result = result
            .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<p[^>]*>", RegexOption.IGNORE_CASE), "\n")
            .replace("</p>", "")
            .replace(Regex("""<\?[\s\S]*?\?>"""), "")
            .replace(Regex("""<!DOCTYPE\b[\s\S]*?>""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""<!--[\s\S]*?-->"""), "")
            .replace(Regex("""<!\[CDATA\[[\s\S]*?\]\]>"""), "")
            .replace(Regex("</?(?!img\\b)[a-zA-Z][\\s\\S]*?>", RegexOption.IGNORE_CASE), "")
            .replace("&nbsp;", " ")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")

        result = result.replace(Regex("[\\n\\r\\t ]*\\n[\\n\\r\\t ]*"), "\n")
        return result.trim()
    }

    private val recindexAttrPattern = Regex(
        """<img\b[^>]*?\brecindex\s*=\s*["']?(\d+)["']?[^>]*?/?>""",
        RegexOption.IGNORE_CASE,
    )
    private val recindexSrcPattern = Regex(
        """<img\b[^>]*?\bsrc\s*=\s*["']recindex:(\d+)["'][^>]*?/?>""",
        RegexOption.IGNORE_CASE,
    )
    private val kindleEmbedPattern = Regex(
        """<image\b[^>]+xlink:href\s*=\s*["']kindle:embed:(\d+)[^"']*["'][^>]*/?>""",
        RegexOption.IGNORE_CASE,
    )
    private val svgWrapperPattern = Regex(
        """<svg[^>]*>([\s\S]*?)</svg>""",
        RegexOption.IGNORE_CASE,
    )

    // ── 全局：releaseCache / extractCover ──

    @Synchronized
    fun releaseCache() {
        closeCachedMobi()
        textCachedUri = null
        textCached = null
        MobiResourceLoader.release()
    }

    /**
     * 提取 MOBI/AZW3 封面图。
     *
     * 主路径：用 Legado [MobiBook.getCover] 走 **EXTH 201 (coverOffset)** /
     * **EXTH 202 (thumbnailOffset)** 拿到的相对 firstImageIndex 偏移，**精确定位**
     * 真封面 record，而不是粗暴取 PDB 第一个 image record（截图见用户实测 azw3 拿
     * 到了版权页扫描）。
     *
     * Fallback：EXTH 没标注 cover 时，回退到 [MobiResourceLoader] 取第一张图。
     */
    fun extractCover(context: Context, uri: Uri): String? {
        // 主路径：MobiBook.getCover() (EXTH 201 / 202)
        val coverBytes = withMobiBook(context, uri) { book ->
            runCatching { book.getCover() }.onFailure {
                AppLog.warn(TAG, "MobiBook.getCover failed: ${it.message}")
            }.getOrNull()
        }
        if (coverBytes != null && coverBytes.isNotEmpty()) {
            val saved = writeCoverFile(context, uri, coverBytes)
            if (saved != null) {
                AppLog.info(TAG, "extractCover via EXTH cover/thumbnail offset: ${coverBytes.size}B")
                return saved
            }
        }

        // Fallback：旧路径取第一张 image record
        AppLog.info(TAG, "EXTH cover offset 缺失，fallback 到第一张 image record")
        val index = MobiResourceLoader.activate(context, uri) ?: return null
        if (index.images.isEmpty()) return null
        val bytes = MobiResourceLoader.readBytes(context, index.hash, 1) ?: return null
        return writeCoverFile(context, uri, bytes, ext = when {
            index.images[0].mime.contains("png") -> "png"
            index.images[0].mime.contains("gif") -> "gif"
            else -> "jpg"
        })
    }

    /**
     * 写封面字节到 cacheDir/mobi_covers/{hash}/cover.{ext}，返回绝对路径。
     * **每次都覆盖**——防止旧算法写的错误封面（如版权页扫描）被 file.exists()
     * 永远卡住。算法升级后用户重新导入即可见效。
     */
    private fun writeCoverFile(context: Context, uri: Uri, bytes: ByteArray, ext: String? = null): String? {
        return try {
            val cacheDir = java.io.File(context.cacheDir, "mobi_covers/${uri.hashCode()}")
            cacheDir.mkdirs()
            val resolvedExt = ext ?: detectImageExt(bytes)
            // 先清理同目录下旧扩展名的 cover.* 避免残留
            cacheDir.listFiles()?.filter { it.name.startsWith("cover.") }?.forEach { it.delete() }
            val file = java.io.File(cacheDir, "cover.$resolvedExt")
            file.writeBytes(bytes)
            file.absolutePath
        } catch (e: Exception) {
            AppLog.warn(TAG, "writeCoverFile failed: ${e.message}")
            null
        }
    }

    /** 按文件头 magic 推断图片扩展名（JPEG/PNG/WebP/GIF/BMP）。无法识别返回 jpg。 */
    private fun detectImageExt(bytes: ByteArray): String {
        if (bytes.size < 4) return "jpg"
        val b0 = bytes[0].toInt() and 0xFF
        val b1 = bytes[1].toInt() and 0xFF
        val b2 = bytes[2].toInt() and 0xFF
        val b3 = bytes[3].toInt() and 0xFF
        return when {
            b0 == 0xFF && b1 == 0xD8 && b2 == 0xFF -> "jpg"                // JPEG
            b0 == 0x89 && b1 == 0x50 && b2 == 0x4E && b3 == 0x47 -> "png"  // PNG
            b0 == 0x47 && b1 == 0x49 && b2 == 0x46 -> "gif"                // GIF
            b0 == 0x42 && b1 == 0x4D -> "bmp"                              // BMP
            b0 == 0x52 && b1 == 0x49 && b2 == 0x46 && b3 == 0x46 -> "webp" // RIFF (WebP)
            else -> "jpg"
        }
    }

    // ── Fallback 用的 PalmDOC 解压（共用 text 缓存） ──

    @Synchronized
    private fun getOrExtractText(context: Context, uri: Uri): String {
        val uriStr = uri.toString()
        if (uriStr == textCachedUri && textCached != null) return textCached!!
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
        val text = extractPalmDocText(bytes) ?: extractRawFallback(bytes)
        textCachedUri = uriStr
        textCached = text
        MobiResourceLoader.activate(context, uri)
        return text
    }

    private fun extractPalmDocText(bytes: ByteArray): String? {
        if (bytes.size < 78 + 8) return null
        val numRecords = readU16(bytes, 76)
        if (numRecords < 2) return null
        val recordListEnd = 78 + numRecords * 8
        if (recordListEnd > bytes.size) return null
        val recordOffsets = IntArray(numRecords) { i -> readU32(bytes, 78 + i * 8) }
        val rec0 = recordOffsets[0]
        if (rec0 < 0 || rec0 + 16 > bytes.size) return null
        val compression = readU16(bytes, rec0)
        val textLength = readU32(bytes, rec0 + 4)
        val recordCount = readU16(bytes, rec0 + 8)
        if (recordCount <= 0 || recordCount >= numRecords) return null
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
                    AppLog.info(TAG, "PalmDOC fallback 不支持压缩类型 $compression（HUFF/CDIC 等走主路径）")
                    return null
                }
            }
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

    // ── 工具 ──

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
