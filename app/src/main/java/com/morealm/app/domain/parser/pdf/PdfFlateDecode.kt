package com.morealm.app.domain.parser.pdf

import java.io.ByteArrayOutputStream
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * FlateDecode（zlib）解压 + PDF predictor 反向滤波。
 *
 * PDF /Filter 子集只支持 FlateDecode —— outline / xref stream / objstm 全部用它。
 * 其他 filter（ASCII85, LZW, DCTDecode 等）这里不处理；调用方碰到 unsupported filter
 * 应直接放弃该流。
 */
internal object PdfFlateDecode {

    /**
     * 纯 zlib inflate，无 predictor。返回解压后字节，受 [PdfLimits.MAX_OBJSTM_DECOMPRESSED] 上限保护。
     *
     * 失败抛 [PdfParseException] —— DataFormatException、超 limit、负残余等都属于"流损坏，放弃"。
     */
    fun inflate(input: ByteArray, maxOut: Int = PdfLimits.MAX_OBJSTM_DECOMPRESSED): ByteArray {
        val inflater = Inflater()
        return try {
            inflater.setInput(input)
            val out = ByteArrayOutputStream(minOf(input.size * 4, 64 * 1024))
            val buf = ByteArray(16 * 1024)
            while (!inflater.finished()) {
                val n = try {
                    inflater.inflate(buf)
                } catch (e: DataFormatException) {
                    throw PdfParseException("flate inflate failed", e)
                }
                if (n == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) {
                        throw PdfParseException("flate stream truncated or needs dict")
                    }
                    break
                }
                if (out.size() + n > maxOut) {
                    throw PdfParseException("flate output exceeds $maxOut bytes (zip bomb?)")
                }
                out.write(buf, 0, n)
            }
            out.toByteArray()
        } finally {
            inflater.end()
        }
    }

    /**
     * 应用 PDF predictor 反滤波（spec 7.4.4.4 + RFC 2083 PNG）。
     *
     * 支持：
     *  - 1 = None（直接返回）
     *  - 2 = TIFF predictor 2（行内左侧差分，按 bitsPerComponent 拆分，xref stream 罕见）
     *  - 10 = PNG None
     *  - 11 = PNG Sub
     *  - 12 = PNG Up（xref stream 最常见）
     *  - 13 = PNG Average
     *  - 14 = PNG Paeth
     *  - 15 = PNG Optimum（每行首字节给出 10..14 的实际算法）
     *
     * xref stream 几乎都是 predictor 12 + columns = sum(/W)。这里走一个通用实现，
     * 支持任意支持的 predictor + 任意 columns。
     *
     * @param data 解压后的字节数据
     * @param predictor /DecodeParms 中的 /Predictor 值（默认 1）
     * @param columns 每行数据字节数（PNG 模式下不含开头标识字节）
     * @return 反滤波后的数据
     */
    fun applyPredictor(data: ByteArray, predictor: Int, columns: Int): ByteArray {
        if (predictor == 1) return data
        if (columns <= 0) throw PdfParseException("invalid columns: $columns")

        if (predictor == 2) {
            // TIFF predictor 2 — 假定 bitsPerComponent=8 且 colors=1（xref stream 总是这样）
            // 行内左侧差分还原：cur += prev
            val out = data.copyOf()
            var i = 0
            while (i < out.size) {
                val rowEnd = minOf(i + columns, out.size)
                for (j in (i + 1) until rowEnd) {
                    out[j] = ((out[j].toInt() and 0xFF) + (out[j - 1].toInt() and 0xFF)).toByte()
                }
                i = rowEnd
            }
            return out
        }

        // PNG 模式：每行多 1 字节算法标识；预测器值 10..15 对应文档级 hint，实际每行 first byte 才是该行用的算法
        val rowSize = columns + 1
        if (data.size % rowSize != 0) {
            throw PdfParseException("png predictor: data size ${data.size} not multiple of row $rowSize")
        }
        val rows = data.size / rowSize
        val out = ByteArray(rows * columns)
        val prevRow = ByteArray(columns) // 上一行（重建后），初始全 0

        for (r in 0 until rows) {
            val tag = data[r * rowSize].toInt() and 0xFF
            val srcOff = r * rowSize + 1
            val dstOff = r * columns
            when (tag) {
                0 -> { // None
                    System.arraycopy(data, srcOff, out, dstOff, columns)
                }
                1 -> { // Sub: cur += left
                    for (c in 0 until columns) {
                        val left = if (c == 0) 0 else out[dstOff + c - 1].toInt() and 0xFF
                        out[dstOff + c] = ((data[srcOff + c].toInt() and 0xFF) + left).toByte()
                    }
                }
                2 -> { // Up: cur += up
                    for (c in 0 until columns) {
                        val up = prevRow[c].toInt() and 0xFF
                        out[dstOff + c] = ((data[srcOff + c].toInt() and 0xFF) + up).toByte()
                    }
                }
                3 -> { // Average: cur += (left + up) / 2
                    for (c in 0 until columns) {
                        val left = if (c == 0) 0 else out[dstOff + c - 1].toInt() and 0xFF
                        val up = prevRow[c].toInt() and 0xFF
                        out[dstOff + c] = ((data[srcOff + c].toInt() and 0xFF) + (left + up) / 2).toByte()
                    }
                }
                4 -> { // Paeth
                    for (c in 0 until columns) {
                        val left = if (c == 0) 0 else out[dstOff + c - 1].toInt() and 0xFF
                        val up = prevRow[c].toInt() and 0xFF
                        val upLeft = if (c == 0) 0 else prevRow[c - 1].toInt() and 0xFF
                        val p = paeth(left, up, upLeft)
                        out[dstOff + c] = ((data[srcOff + c].toInt() and 0xFF) + p).toByte()
                    }
                }
                else -> throw PdfParseException("unsupported png filter tag: $tag (row $r)")
            }
            System.arraycopy(out, dstOff, prevRow, 0, columns)
        }
        return out
    }

    private fun paeth(a: Int, b: Int, c: Int): Int {
        val p = a + b - c
        val pa = kotlin.math.abs(p - a)
        val pb = kotlin.math.abs(p - b)
        val pc = kotlin.math.abs(p - c)
        return when {
            pa <= pb && pa <= pc -> a
            pb <= pc -> b
            else -> c
        }
    }
}
