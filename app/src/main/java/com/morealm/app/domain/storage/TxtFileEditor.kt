package com.morealm.app.domain.storage

import android.content.Context
import android.net.Uri
import com.morealm.app.core.log.AppLog
import com.morealm.app.domain.entity.BookChapter
import com.morealm.app.domain.parser.LocalBookParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.charset.Charset
import java.util.regex.Matcher
import java.util.regex.Pattern

/** 查找/替换的作用域。在线书和非 TXT 格式不会进入这个编辑器。 */
enum class TxtEditScope { CHAPTER, FULL_TEXT }

data class TxtReplaceRequest(
    val query: String,
    val replacement: String,
    val isRegex: Boolean = false,
    val isCaseSensitive: Boolean = false,
)

data class TxtReplaceResult(
    val replacedCount: Int,
    val fileChanged: Boolean,
)

/**
 * TXT 原文件编辑器。
 *
 * 章节索引保存的是字节偏移，因此这里始终在字节层切片，再用探测到的字符集解码；
 * 不用 Kotlin 的 substring 直接拼整本文件，避免大 TXT 在搜索替换时产生双份内存峰值。
 */
object TxtFileEditor {
    private const val TAG = "TxtFileEditor"

    suspend fun replace(
        context: Context,
        uri: Uri,
        chapters: List<BookChapter>,
        scope: TxtEditScope,
        request: TxtReplaceRequest,
        targetChapterIndex: Int? = null,
        targetMatchOrdinal: Int? = null,
    ): TxtReplaceResult = withContext(Dispatchers.IO) {
        require(request.query.isNotEmpty()) { "搜索内容不能为空" }
        val pattern = compilePattern(request)
        val backup = File.createTempFile("morealm-txt-backup-", ".bin", context.cacheDir)
        val output = File.createTempFile("morealm-txt-output-", ".bin", context.cacheDir)
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(backup).use { saved -> input.copyTo(saved) }
            } ?: error("无法读取 TXT 文件")

            val charset = Charset.forName(LocalBookParser.detectCharset(context, uri))
            val ordered = chapters
                .filter { it.endPosition > it.startPosition }
                .sortedBy { it.startPosition }
            val total = when (scope) {
                TxtEditScope.CHAPTER -> replaceChapter(
                    backup = backup,
                    output = output,
                    charset = charset,
                    chapters = ordered,
                    targetChapterIndex = targetChapterIndex,
                    targetMatchOrdinal = targetMatchOrdinal,
                    pattern = pattern,
                    request = request,
                )
                TxtEditScope.FULL_TEXT -> replaceFullText(
                    backup = backup,
                    output = output,
                    charset = charset,
                    chapters = ordered,
                    pattern = pattern,
                    request = request,
                )
            }
            if (total == 0) return@withContext TxtReplaceResult(0, false)
            writeBack(context, uri, output, backup)
            LocalBookParser.clearCharsetCache(uri)
            LocalBookParser.releaseTxtBuffer()
            AppLog.info(TAG, "replace scope=$scope count=$total uri=$uri")
            TxtReplaceResult(total, true)
        } finally {
            backup.delete()
            output.delete()
        }
    }

    private fun replaceChapter(
        backup: File,
        output: File,
        charset: Charset,
        chapters: List<BookChapter>,
        targetChapterIndex: Int?,
        targetMatchOrdinal: Int?,
        pattern: Pattern,
        request: TxtReplaceRequest,
    ): Int {
        val chapter = chapters.firstOrNull { it.index == targetChapterIndex }
            ?: error("当前章节不存在")
        RandomAccessFile(backup, "r").use { input ->
            FileOutputStream(output).use { out ->
                copyRange(input, out, chapter.startPosition)
                val source = readRange(backup, chapter.startPosition, chapter.endPosition)
                val (changed, count) = replaceText(
                    String(source, charset), pattern, request.replacement, targetMatchOrdinal,
                )
                out.write(if (count == 0) source else changed.toByteArray(charset))
                input.seek(chapter.endPosition)
                copyRange(input, out, backup.length() - chapter.endPosition)
                return count
            }
        }
    }

    private fun replaceFullText(
        backup: File,
        output: File,
        charset: Charset,
        chapters: List<BookChapter>,
        pattern: Pattern,
        request: TxtReplaceRequest,
    ): Int {
        var cursor = 0L
        var total = 0
        RandomAccessFile(backup, "r").use { input ->
            FileOutputStream(output).use { out ->
                for (chapter in chapters) {
                    val start = chapter.startPosition.coerceIn(cursor, backup.length())
                    val end = chapter.endPosition.coerceIn(start, backup.length())
                    if (start > cursor) {
                        input.seek(cursor)
                        copyRange(input, out, start - cursor)
                    }
                    val source = readRange(backup, start, end)
                    val (changed, count) = replaceText(String(source, charset), pattern, request.replacement, null)
                    if (count == 0) out.write(source) else out.write(changed.toByteArray(charset))
                    total += count
                    cursor = end
                }
                if (cursor < backup.length()) {
                    input.seek(cursor)
                    copyRange(input, out, backup.length() - cursor)
                }
            }
        }
        return total
    }

    private fun compilePattern(request: TxtReplaceRequest): Pattern {
        val source = if (request.isRegex) request.query else Pattern.quote(request.query)
        var flags = Pattern.MULTILINE
        if (!request.isCaseSensitive) flags = flags or Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
        return try {
            Pattern.compile(source, flags)
        } catch (e: Exception) {
            throw IllegalArgumentException("正则表达式无效：${e.message?.take(120)}", e)
        }
    }

    /** 返回转换文本和命中数；targetOrdinal 非空时只替换该章节内的第 N 个匹配。 */
    private fun replaceText(
        text: String,
        pattern: Pattern,
        replacement: String,
        targetOrdinal: Int?,
    ): Pair<String, Int> {
        val matcher = pattern.matcher(text)
        val out = StringBuilder(text.length)
        var cursor = 0
        var ordinal = 0
        var count = 0
        while (matcher.find()) {
            val shouldReplace = targetOrdinal == null || ordinal == targetOrdinal
            out.append(text, cursor, matcher.start())
            if (shouldReplace) {
                out.append(expandReplacement(matcher, replacement))
                count++
            } else {
                out.append(text, matcher.start(), matcher.end())
            }
            cursor = matcher.end()
            ordinal++
            if (targetOrdinal != null && shouldReplace) {
                out.append(text, cursor, text.length)
                return out.toString() to count
            }
        }
        if (count == 0) return text to 0
        out.append(text, cursor, text.length)
        return out.toString() to count
    }

    internal fun replaceTextForTest(
        text: String,
        request: TxtReplaceRequest,
        targetOrdinal: Int? = null,
    ): Pair<String, Int> = replaceText(
        text = text,
        pattern = compilePattern(request),
        replacement = request.replacement,
        targetOrdinal = targetOrdinal,
    )

    /** 支持 $1 / $2 捕获组，同时把反斜杠后的字符按字面量处理。 */
    private fun expandReplacement(matcher: Matcher, replacement: String): String {
        val out = StringBuilder(replacement.length)
        var i = 0
        while (i < replacement.length) {
            when (val ch = replacement[i]) {
                '\\' -> {
                    if (i + 1 < replacement.length) out.append(replacement[++i]) else out.append(ch)
                }
                '$' -> {
                    var j = i + 1
                    while (j < replacement.length && replacement[j].isDigit()) j++
                    if (j > i + 1) {
                        val group = replacement.substring(i + 1, j).toIntOrNull()
                        if (group != null && group <= matcher.groupCount()) out.append(matcher.group(group).orEmpty())
                        else out.append(replacement, i, j)
                        i = j - 1
                    } else out.append(ch)
                }
                else -> out.append(ch)
            }
            i++
        }
        return out.toString()
    }

    private fun readRange(file: File, start: Long, end: Long): ByteArray {
        val size = (end - start).coerceAtLeast(0L)
        require(size <= Int.MAX_VALUE) { "单章节过大，无法编辑" }
        val bytes = ByteArray(size.toInt())
        RandomAccessFile(file, "r").use { input ->
            input.seek(start)
            input.readFully(bytes)
        }
        return bytes
    }

    private fun copyRange(input: RandomAccessFile, output: FileOutputStream, length: Long) {
        var remaining = length
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read <= 0) break
            output.write(buffer, 0, read)
            remaining -= read
        }
    }

    private fun writeBack(context: Context, uri: Uri, output: File, backup: File) {
        fun write(file: File) {
            if (uri.scheme == null || uri.scheme == "file") {
                val target = File(requireNotNull(uri.path) { "TXT 文件路径为空" })
                FileOutputStream(target, false).use { out -> file.inputStream().use { it.copyTo(out) } }
            } else {
                context.contentResolver.openOutputStream(uri, "rwt")?.use { out ->
                    file.inputStream().use { it.copyTo(out) }
                } ?: error("无法打开 TXT 写入流")
            }
        }
        try {
            write(output)
        } catch (error: Throwable) {
            runCatching { write(backup) }
            throw error
        }
    }
}
