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

            val matches = chapterRegex.findAll(text).toList()

            if (matches.isEmpty()) {
                chapters.add(BookChapter(
                    id = "${bookId}_0", bookId = bookId, index = 0,
                    title = "全文",
                    startPosition = 0, endPosition = text.length.toLong(),
                ))
                return chapters
            }

            // ── 标题与内容对齐 ──
            //
            // 老代码：在每次 match 时新建章节，title 用**当前 match**，但 content
            // range 是 [上次 match.start .. 当前 match.start]。结果第 i 个章节的
            // title 来自定义其**结束**的 match，而不是定义其**开始**的 match ——
            // 用户看到的"章节内容跟标题完全错位 1 位"现象（截图 12 三个并列
            // "第三章 / 第三章 新的起飞 / 新的起飞"，每个章节内容显示的是邻章正文）。
            //
            // 修复：第 i 个 match 定义第 i 个章节的**起点**，title=match[i] 的清理值，
            // content range=[match[i].start .. match[i+1].start]（最后一个用 text.length）。
            // 第一个 match 之前如果有内容，单独作为"前言"。
            val firstMatchStart = matches[0].range.first
            if (firstMatchStart > 0) {
                chapters.add(BookChapter(
                    id = "${bookId}_0", bookId = bookId, index = 0,
                    title = "前言",
                    startPosition = 0,
                    endPosition = firstMatchStart.toLong(),
                ))
            }
            matches.forEachIndexed { i, m ->
                val start = m.range.first
                val end = if (i + 1 < matches.size) matches[i + 1].range.first else text.length
                chapters.add(BookChapter(
                    id = "", bookId = bookId, index = 0,
                    title = cleanHtmlTitle(m.value),
                    startPosition = start.toLong(),
                    endPosition = end.toLong(),
                ))
            }

            // ── 合并过短的相邻章节 ──
            //
            // EPUB/MOBI 的章节扉页常见结构：
            //   <h1>第三章 新的起飞</h1>
            //   <h2>第三章</h2>
            //   <h3>新的起飞</h3>
            // 三个 h 标签紧挨，正则匹配到 3 次 → 截图 12 的「第三章 新的起飞 / 第三章
            // / 新的起飞」三条并列。修复：相邻章节内容 < 100 字符的合并到前一章，
            // 标题取最长（"第三章 新的起飞" 通常比子标题信息量大）。100 字符够
            // 容纳"扉页 + 副标题 + 空白"，不会误伤真正只是非常短的章节。
            val MIN_CHAPTER_CHARS = 100
            val merged = mutableListOf<BookChapter>()
            for (ch in chapters) {
                val last = merged.lastOrNull()
                if (last != null && (ch.startPosition - last.startPosition) < MIN_CHAPTER_CHARS) {
                    // 短到不像独立章节 —— 与上一章合并
                    val combinedTitle = if (ch.title.length > last.title.length) ch.title else last.title
                    merged[merged.size - 1] = last.copy(
                        title = combinedTitle,
                        endPosition = ch.endPosition,
                    )
                } else {
                    merged.add(ch)
                }
            }

            // 重排 id / index
            chapters.clear()
            merged.forEachIndexed { i, c ->
                chapters.add(c.copy(id = "${bookId}_$i", index = i))
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
            val raw = text.substring(start, end)
            formatMobiHtml(raw, uri)
        } catch (_: Exception) { "" }
    }

    /**
     * 把 MOBI HTML 片段转为阅读器可渲染的格式：
     *   - 保留 `<img>` 标签并把 recindex / kindle:embed 引用替换为 mobi-img:// 协议
     *   - 剥除 SVG 容器壳
     *   - 其他 HTML 标签按 EPUB 风格清理（保留 br/p 换行语义）
     *   - 漫画书（连续 img 段落）压缩多余空白，让相邻图片紧贴
     */
    private fun formatMobiHtml(html: String, uri: Uri): String {
        val hash = uri.hashCode().toString()

        // 1. 替换 KF7 recindex 属性（变体一）: <img recindex="00060" width="800" .../>
        //    用非贪婪 [^>]*? 避免吞掉后续标签，\b 确保属性名边界精确。
        var result = recindexAttrPattern.replace(html) { match ->
            val idx = match.groupValues[1].trimStart('0').ifEmpty { "1" }
            "<img src=\"mobi-img://$hash/$idx\"/>"
        }

        // 2. 替换 KF7 recindex src 形式（变体二）: <img src="recindex:00001"/>
        result = recindexSrcPattern.replace(result) { match ->
            val idx = match.groupValues[1].trimStart('0').ifEmpty { "1" }
            "<img src=\"mobi-img://$hash/$idx\"/>"
        }

        // 3. 替换 KF8 kindle:embed 引用（含 SVG 包裹）
        result = kindleEmbedPattern.replace(result) { match ->
            val idx = match.groupValues[1].trimStart('0').ifEmpty { "1" }
            "<img src=\"mobi-img://$hash/$idx\"/>"
        }

        // 4. 剥除 SVG 容器壳（保留内部已替换的 img）
        result = svgWrapperPattern.replace(result) { match ->
            val inner = match.groupValues[1]
            if (inner.contains("<img")) inner else ""
        }

        // 5. 清理其他 HTML 标签（保留 img / br / p 换行语义）
        result = result
            .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<p[^>]*>", RegexOption.IGNORE_CASE), "\n")
            .replace("</p>", "")
            .replace(Regex("</?(?!img\\b)[a-zA-Z]+(?=[\\s>])[^<>]*>", RegexOption.IGNORE_CASE), "")
            .replace("&nbsp;", " ")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")

        // 6. 漫画书压缩：连续多个换行合并为单个 —— 让相邻图片紧贴，不让空段落
        //    在阅读器里被排成空行造成"图片之间一大段黑"。
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

    @Synchronized
    private fun getOrExtractText(context: Context, uri: Uri): String {
        val uriStr = uri.toString()
        if (uriStr == cachedUri && cachedText != null) return cachedText!!

        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
        val text = extractPalmDocText(bytes) ?: extractRawFallback(bytes)
        cachedUri = uriStr
        cachedText = text
        // 同步激活图片索引（仅解析 header + offset，不读图片字节）
        MobiResourceLoader.activate(context, uri)
        return text
    }

    fun releaseCache() {
        cachedUri = null
        cachedText = null
        MobiResourceLoader.release()
    }

    /**
     * 提取 MOBI/AZW3 封面图（第一张图片 record）并写入 cacheDir。
     * 返回本地文件路径供 Coil 加载，失败返回 null。
     */
    fun extractCover(context: Context, uri: Uri): String? {
        val index = MobiResourceLoader.activate(context, uri) ?: return null
        if (index.images.isEmpty()) return null
        val bytes = MobiResourceLoader.readBytes(context, index.hash, 1) ?: return null
        return try {
            val cacheDir = java.io.File(context.cacheDir, "mobi_covers/${uri.hashCode()}")
            cacheDir.mkdirs()
            val ext = when {
                index.images[0].mime.contains("png") -> "png"
                index.images[0].mime.contains("gif") -> "gif"
                else -> "jpg"
            }
            val file = java.io.File(cacheDir, "cover.$ext")
            if (!file.exists()) file.writeBytes(bytes)
            file.absolutePath
        } catch (e: Exception) {
            AppLog.warn(TAG, "extractCover failed: ${e.message}")
            null
        }
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
