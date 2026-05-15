package com.morealm.app.domain.parser.pdf

import java.io.FileInputStream
import java.io.FileDescriptor
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * PDF 字节级随机读封装：基于 [FileChannel.read]（positional read，不动 channel position，线程安全）。
 *
 * 持有 [FileChannel]，由 [PdfOutlineParser] 在顶层管理生命周期（close 时关闭 channel；
 * 上游 ParcelFileDescriptor 不在这里关）。
 */
internal class PdfRandomReader(private val channel: FileChannel) : AutoCloseable {

    val size: Long = channel.size()

    /**
     * 从 [position] 读 [length] 字节。短读视为损坏，抛 [PdfParseException]。
     * 越界（`position + length > size`）自动 clamp 到文件末尾，返回实际长度。
     */
    fun read(position: Long, length: Int): ByteArray {
        require(length >= 0) { "length < 0: $length" }
        if (length == 0) return ByteArray(0)
        if (position < 0 || position >= size) {
            throw PdfParseException("read out of range: pos=$position size=$size")
        }
        val toRead = minOf(length.toLong(), size - position).toInt()
        val buf = ByteBuffer.allocate(toRead)
        var read = 0
        var pos = position
        while (read < toRead) {
            val n = channel.read(buf, pos)
            if (n <= 0) break
            read += n
            pos += n
        }
        if (read != toRead) {
            throw PdfParseException("short read at pos=$position want=$toRead got=$read")
        }
        return buf.array()
    }

    /**
     * 从文件末尾向前读 [window] 字节（不足时取全部）。用于反扫 `startxref` / `%%EOF`。
     */
    fun readTail(window: Int): ByteArray {
        val len = minOf(window.toLong(), size).toInt()
        return read(size - len, len)
    }

    /**
     * 反扫 byte pattern。返回 ASCII 子串在 [bytes] 中最后一次出现的偏移；找不到返回 -1。
     */
    fun lastIndexOfAscii(bytes: ByteArray, pattern: String): Int {
        val needle = pattern.toByteArray(Charsets.US_ASCII)
        if (needle.isEmpty() || needle.size > bytes.size) return -1
        outer@ for (i in bytes.size - needle.size downTo 0) {
            for (j in needle.indices) {
                if (bytes[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }

    override fun close() {
        try { channel.close() } catch (_: Throwable) {}
    }

    companion object {
        /**
         * 从 [FileDescriptor] 建 reader。
         *
         * 注意：caller 拿到的 [java.io.FileDescriptor] 必须是真实可 seek 文件（不是 pipe/socket），
         * 否则 [FileChannel.size] 会抛或返回 0；上层在调用前应用 `ParcelFileDescriptor.statSize` 守卫。
         */
        fun open(fd: FileDescriptor): PdfRandomReader {
            val channel = FileInputStream(fd).channel
            return PdfRandomReader(channel)
        }

        /**
         * 从普通 [java.io.File] 建 reader。仅给单元测试用（生产代码走 [ParcelFileDescriptor]）。
         */
        internal fun fromFile(file: java.io.File): PdfRandomReader {
            val channel = java.io.RandomAccessFile(file, "r").channel
            return PdfRandomReader(channel)
        }
    }
}
