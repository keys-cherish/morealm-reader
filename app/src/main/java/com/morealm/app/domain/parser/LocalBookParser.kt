package com.morealm.app.domain.parser

import android.content.Context
import android.net.Uri
import com.morealm.app.domain.entity.BookChapter
import com.morealm.app.domain.entity.BookFormat
import com.morealm.app.domain.entity.TxtTocRule
import com.morealm.app.domain.db.TxtTocRuleDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/**
 * Unified local book parser — handles TXT, EPUB, MOBI, PDF, CBZ, UMD.
 *
 * TXT parsing follows Legado's approach:
 * 1. Read enabled TxtTocRules from DB (fallback to hardcoded defaults)
 * 2. Sample first 512KB, try each rule, pick the one with most matches
 * 3. Parse full file with the best rule
 * 4. If no rule matches, auto-split by fixed size (10KB)
 */
object LocalBookParser {

    // Fallback patterns when DB has no rules
    private val defaultPatterns = listOf(
        "^\\s*第[零一二三四五六七八九十百千万\\d]+[章节回卷集部篇].*",
        "^\\s*Chapter\\s+\\d+.*",
        "^\\s*卷[零一二三四五六七八九十百千万\\d]+.*",
        "^\\s*(正文|序[章言]|楔子|番外).*",
    )

    /** Inject from DI; set once at app startup or before first parse */
    var txtTocRuleDao: TxtTocRuleDao? = null

    // Charset cache: URI string → detected charset name
    private val charsetCache = ConcurrentHashMap<String, String>()

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
            BookFormat.UMD -> UmdParser.parseChapters(context, uri)
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
            BookFormat.UMD -> UmdParser.readChapter(context, uri, chapter)
            else -> ""
        }
    }

    // ── TXT ──────────────────────────────────────────────

    private const val BLOCK_SIZE = 512 * 1024 // 512KB blocks
    private const val MAX_LENGTH_NO_TOC = 10 * 1024 // 10KB per chapter when no TOC found
    private const val MAX_LENGTH_WITH_TOC = 200 * 1024 // 200KB max chapter with TOC

    /**
     * 一个 TOC rule 在 [BLOCK_SIZE] 采样文本中至少要命中这么多次，才被认为是真章节标题。
     *
     * 历史阈值是 1：只要 sample 里有 1 行匹配某个 pattern 就采用，跑 [parseWithTocPattern]
     * 把整本书按该 pattern 切。问题：无章节标题的纯小说 txt，正文里偶尔出现『卷一』
     * 『序章』『正文』『1.』这种字样就会误命中——一旦命中后，全文每一行被同一 pattern
     * 扫过，长度落在 1..50 的几乎所有"小段"都会被切成假章节，于是出现『249 节』这种
     * 用户视角完全不合理的目录数。
     *
     * 提到 3 的考虑：
     *  - sample 是文件前 512KB（约 17 万汉字），真有章节的小说在这个窗口里至少应该
     *    出现 3 个章节标题，否则就太"稀"了不像目录
     *  - 误命中防御：偶发 1~2 个『卷一』之类的字眼不会触发误识别，仍走 parseWithoutToc
     *    自动分章 → BookChapter.displayTitle / ReaderScreen.isAutoSplitTxt 合并显示路径
     *    会把目录折叠为单条『《书名》整本书』
     *  - 短篇容忍：正文不足 3 章的短小说本来就用不着目录，走自动分章无害
     */
    private const val MIN_TOC_MATCHES = 3

    fun parseTxtChapters(context: Context, uri: Uri, customRegex: String = ""): List<BookChapter> {
        val charsetName = getCachedCharset(context, uri)
        val cs = charset(charsetName)

        val tocPattern: Pattern? = if (customRegex.isNotBlank()) {
            try { Pattern.compile(customRegex, Pattern.MULTILINE) } catch (_: Exception) { null }
        } else {
            detectBestTocRule(context, uri, cs)
        }

        return if (tocPattern != null) {
            parseWithTocPattern(context, uri, cs, tocPattern)
        } else {
            parseWithoutToc(context, uri, cs)
        }
    }

    /**
     * Detect the best TOC rule by sampling the first block of the file.
     */
    private fun detectBestTocRule(context: Context, uri: Uri, cs: java.nio.charset.Charset): Pattern? {
        val sampleText = context.contentResolver.openInputStream(uri)?.use { stream ->
            val buffer = ByteArray(BLOCK_SIZE)
            val read = stream.read(buffer)
            if (read > 0) String(buffer, 0, read, cs) else null
        } ?: return null

        val rulePatterns: List<String> = try {
            val dbRules = txtTocRuleDao?.getEnabledRulesSync()
            if (!dbRules.isNullOrEmpty()) dbRules.map { it.pattern } else defaultPatterns
        } catch (_: Exception) {
            defaultPatterns
        }

        var bestPattern: Pattern? = null
        // 起始基准 = MIN_TOC_MATCHES - 1，配合下方 `count >= maxMatches` 让"最低 3 次匹配
        // 才采用 rule"成立。注意：保留 `>=` 而不是 `>` —— DB 里 rule 按 sortOrder 升序，
        // 这里 rulePatterns.reversed() 倒序遍历，tied count 时让低 sortOrder 的 rule 覆盖
        // 高 sortOrder 的 rule（用户语义：sortOrder=0 的 builtin_1 优先级最高）。
        var maxMatches = MIN_TOC_MATCHES - 1

        for (rulePattern in rulePatterns.reversed()) {
            val pattern = try {
                Pattern.compile(rulePattern, Pattern.MULTILINE)
            } catch (_: PatternSyntaxException) { continue }
            val matcher = pattern.matcher(sampleText)
            var count = 0
            var lastEnd = 0
            while (matcher.find()) {
                if (lastEnd == 0 || matcher.start() - lastEnd > 1000) {
                    count++
                    lastEnd = matcher.end()
                }
            }
            if (count >= maxMatches) {
                maxMatches = count
                bestPattern = pattern
            }
        }
        return bestPattern
    }

    private fun parseWithTocPattern(
        context: Context, uri: Uri,
        cs: java.nio.charset.Charset, pattern: Pattern,
    ): List<BookChapter> {
        val chapters = mutableListOf<BookChapter>()
        val bookId = ""
        var globalOffset = 0L
        var chapterStart = 0L
        var chapterTitle: String? = null
        var chapterIndex = 0
        var leftover = ""

        context.contentResolver.openInputStream(uri)?.use { stream ->
            val buffer = ByteArray(BLOCK_SIZE)
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break

                val blockText = leftover + String(buffer, 0, read, cs)
                val lines = blockText.split('\n')

                leftover = if (read == BLOCK_SIZE) lines.last() else ""
                val completeLines = if (read == BLOCK_SIZE) lines.dropLast(1) else lines

                for (line in completeLines) {
                    val lineBytes = (line + "\n").toByteArray(cs).size.toLong()
                    val trimmed = line.trim()
                    if (trimmed.length in 1..50 && pattern.matcher(trimmed).matches() && globalOffset > chapterStart) {
                        chapters.add(BookChapter(
                            id = "${bookId}_$chapterIndex", bookId = bookId,
                            index = chapterIndex,
                            title = (chapterTitle ?: "前言").trim(),
                            startPosition = chapterStart, endPosition = globalOffset,
                        ))
                        chapterIndex++
                        chapterStart = globalOffset
                        chapterTitle = trimmed
                    }
                    globalOffset += lineBytes
                }
            }
        }

        if (globalOffset > chapterStart) {
            chapters.add(BookChapter(
                id = "${bookId}_$chapterIndex", bookId = bookId,
                index = chapterIndex,
                title = (chapterTitle ?: "正文").trim(),
                startPosition = chapterStart, endPosition = globalOffset,
            ))
        }

        return chapters.flatMap { ch ->
            val size = ch.endPosition - ch.startPosition
            if (size <= MAX_LENGTH_WITH_TOC) listOf(ch)
            else splitLargeChapter(ch, 100 * 1024)
        }.mapIndexed { i, ch -> ch.copy(id = "${bookId}_$i", index = i) }
    }

    /**
     * Parse TXT without any TOC rule — split by fixed size at newline boundaries.
     */
    private fun parseWithoutToc(
        context: Context, uri: Uri, cs: java.nio.charset.Charset,
    ): List<BookChapter> {
        val chapters = mutableListOf<BookChapter>()
        val bookId = ""
        var globalOffset = 0L
        var chapterStart = 0L
        var chapterIndex = 0

        context.contentResolver.openInputStream(uri)?.use { stream ->
            val buffer = ByteArray(BLOCK_SIZE)
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break

                var scanPos = 0
                while (scanPos < read) {
                    val bytesInChapter = (globalOffset + scanPos) - chapterStart
                    if (bytesInChapter >= MAX_LENGTH_NO_TOC) {
                        // Jump ahead to find next newline instead of scanning byte-by-byte
                        var breakPos = scanPos
                        while (breakPos < read && buffer[breakPos] != 0x0a.toByte()) breakPos++
                        if (breakPos < read) breakPos++
                        val endOffset = globalOffset + breakPos
                        chapters.add(BookChapter(
                            id = "${bookId}_$chapterIndex", bookId = bookId,
                            index = chapterIndex,
                            title = "第${chapterIndex + 1}节",
                            startPosition = chapterStart, endPosition = endOffset,
                        ))
                        chapterIndex++
                        chapterStart = endOffset
                        scanPos = breakPos
                    } else {
                        // Skip ahead to where the threshold would be met
                        val remaining = MAX_LENGTH_NO_TOC - bytesInChapter
                        scanPos += remaining.toInt().coerceAtLeast(1)
                    }
                }
                globalOffset += read
            }
        }

        if (globalOffset > chapterStart) {
            chapters.add(BookChapter(
                id = "${bookId}_$chapterIndex", bookId = bookId,
                index = chapterIndex,
                title = if (chapters.isEmpty()) "正文" else "第${chapterIndex + 1}节",
                startPosition = chapterStart, endPosition = globalOffset,
            ))
        }

        return chapters.mapIndexed { i, ch -> ch.copy(id = "${bookId}_$i", index = i) }
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

    // ── TXT read buffer (ported from Legado TextFile.txtBuffer sliding window) ──
    private const val TXT_READ_BUFFER_SIZE = 8 * 1024 * 1024 // 8MB
    private var txtReadBuffer: ByteArray? = null
    private var txtBufferUri: String? = null
    private var txtBufferStart = -1L
    private var txtBufferEnd = -1L

    fun readTxtChapter(context: Context, uri: Uri, chapter: BookChapter): String {
        val charsetName = getCachedCharset(context, uri)
        val cs = charset(charsetName)
        val start = chapter.startPosition
        val length = (chapter.endPosition - start).toInt()
        if (length <= 0) return "（本章内容为空）"

        val uriStr = uri.toString()

        // Check if chapter fits in current sliding window
        if (txtBufferUri == uriStr && txtReadBuffer != null
            && start >= txtBufferStart && chapter.endPosition <= txtBufferEnd
        ) {
            val offset = (start - txtBufferStart).toInt()
            return String(txtReadBuffer!!, offset, length, cs)
        }

        // Cache miss — reload buffer window around this chapter
        return context.contentResolver.openInputStream(uri)?.use { stream ->
            val alignedStart = TXT_READ_BUFFER_SIZE * (start / TXT_READ_BUFFER_SIZE)
            var skipped = 0L
            while (skipped < alignedStart) {
                val n = stream.skip(alignedStart - skipped)
                if (n <= 0) break
                skipped += n
            }
            val bufSize = TXT_READ_BUFFER_SIZE.coerceAtMost(
                (chapter.endPosition - alignedStart + TXT_READ_BUFFER_SIZE / 2).toInt()
                    .coerceAtMost(TXT_READ_BUFFER_SIZE)
            )
            val buf = txtReadBuffer?.takeIf { it.size >= bufSize } ?: ByteArray(bufSize)
            var totalRead = 0
            while (totalRead < bufSize) {
                val n = stream.read(buf, totalRead, bufSize - totalRead)
                if (n <= 0) break
                totalRead += n
            }
            txtReadBuffer = buf
            txtBufferUri = uriStr
            txtBufferStart = alignedStart
            txtBufferEnd = alignedStart + totalRead

            val offset = (start - alignedStart).toInt()
            val readLen = length.coerceAtMost(totalRead - offset)
            if (readLen > 0) String(buf, offset, readLen, cs)
            else "（本章内容为空）"
        } ?: "（本章内容为空）"
    }

    /** Release TXT read buffer memory (call when leaving reader) */
    fun releaseTxtBuffer() {
        txtReadBuffer = null
        txtBufferUri = null
        txtBufferStart = -1L
        txtBufferEnd = -1L
    }

    // ── Charset detection with cache ─────────────────────

    private fun getCachedCharset(context: Context, uri: Uri): String {
        return charsetCache.getOrPut(uri.toString()) { detectCharset(context, uri) }
    }

    fun detectCharset(context: Context, uri: Uri): String {
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

        context.contentResolver.openInputStream(uri)?.use { stream ->
            val sample = ByteArray(8192)
            val sampleRead = stream.read(sample, 0, sample.size)
            if (sampleRead > 0) {
                if (looksLikeValidUtf8(sample, sampleRead)) return "UTF-8"
                if (looksLikeGbk(sample, sampleRead)) return "GBK"
            }
        }

        return "UTF-8"
    }

    /** Clear charset cache when a book file changes */
    fun clearCharsetCache(uri: Uri? = null) {
        if (uri != null) charsetCache.remove(uri.toString()) else charsetCache.clear()
    }

    private fun looksLikeValidUtf8(data: ByteArray, length: Int): Boolean {
        var i = 0; var multiByte = 0; var invalid = 0
        while (i < length) {
            val b = data[i].toInt() and 0xFF
            when {
                b <= 0x7F -> i++
                b in 0xC2..0xDF -> {
                    if (i + 1 >= length) { i++; continue }
                    if (data[i + 1].toInt() and 0xFF in 0x80..0xBF) { multiByte++; i += 2 } else { invalid++; i++ }
                }
                b in 0xE0..0xEF -> {
                    if (i + 2 >= length) { i++; continue }
                    val b2 = data[i + 1].toInt() and 0xFF; val b3 = data[i + 2].toInt() and 0xFF
                    if (b2 in 0x80..0xBF && b3 in 0x80..0xBF) { multiByte++; i += 3 } else { invalid++; i++ }
                }
                b in 0xF0..0xF4 -> {
                    if (i + 3 >= length) { i++; continue }
                    val b2 = data[i + 1].toInt() and 0xFF; val b3 = data[i + 2].toInt() and 0xFF; val b4 = data[i + 3].toInt() and 0xFF
                    if (b2 in 0x80..0xBF && b3 in 0x80..0xBF && b4 in 0x80..0xBF) { multiByte++; i += 4 } else { invalid++; i++ }
                }
                else -> { invalid++; i++ }
            }
        }
        return multiByte > 0 && invalid <= multiByte / 10
    }

    private fun looksLikeGbk(data: ByteArray, length: Int): Boolean {
        var i = 0; var gbkPairs = 0
        while (i < length - 1) {
            val b = data[i].toInt() and 0xFF
            if (b in 0x81..0xFE) {
                if (data[i + 1].toInt() and 0xFF in 0x40..0xFE) { gbkPairs++; i += 2; continue }
            }
            i++
        }
        return gbkPairs > length / 20
    }
}
