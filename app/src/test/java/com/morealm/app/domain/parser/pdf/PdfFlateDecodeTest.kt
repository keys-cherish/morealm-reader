package com.morealm.app.domain.parser.pdf

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.util.zip.Deflater

class PdfFlateDecodeTest {

    @Test fun `inflate round trip`() {
        val original = "Hello, PDF! The quick brown fox jumps over the lazy dog.".toByteArray()
        val compressed = deflate(original)
        val decompressed = PdfFlateDecode.inflate(compressed)
        assertArrayEquals(original, decompressed)
    }

    @Test fun `inflate enforces max output`() {
        // 1KB 输入解压成 ~1KB —— 这个不会被截，但下面 maxOut = 1 必然超
        val compressed = deflate(ByteArray(1024) { it.toByte() })
        try {
            PdfFlateDecode.inflate(compressed, maxOut = 1)
            fail("expected PdfParseException for zip bomb guard")
        } catch (e: PdfParseException) {
            assertEquals(true, e.message!!.contains("exceeds"))
        }
    }

    @Test fun `predictor None returns input`() {
        val data = byteArrayOf(1, 2, 3, 4, 5)
        val out = PdfFlateDecode.applyPredictor(data, predictor = 1, columns = 5)
        assertArrayEquals(data, out)
    }

    @Test fun `png predictor 12 Up reverses correctly`() {
        // 两行，每行 columns=3。
        // 原始数据：row0 = [10, 20, 30], row1 = [13, 24, 35]
        // PNG 编码 (predictor 12 / Up filter, 每行前缀 02)：
        //   row0 编码：02 10 20 30   (前一行视为全 0，所以 cur = original - up = original - 0 = 原值)
        //   row1 编码：02 03 04 05   (3=13-10, 4=24-20, 5=35-30)
        val encoded = byteArrayOf(
            2, 10, 20, 30,
            2, 3, 4, 5,
        )
        val decoded = PdfFlateDecode.applyPredictor(encoded, predictor = 12, columns = 3)
        assertArrayEquals(byteArrayOf(10, 20, 30, 13, 24, 35), decoded)
    }

    @Test fun `png predictor handles Sub filter`() {
        // row0 = [10, 20, 30] with Sub filter: cur - left.
        //   encoded byte 0: tag=01 (Sub), then [10, 10, 10] (10, 20-10, 30-20)
        val encoded = byteArrayOf(1, 10, 10, 10)
        val decoded = PdfFlateDecode.applyPredictor(encoded, predictor = 11, columns = 3)
        assertArrayEquals(byteArrayOf(10, 20, 30), decoded)
    }

    @Test fun `unsupported png filter tag throws`() {
        val encoded = byteArrayOf(9, 1, 2, 3) // 9 = invalid filter type
        try {
            PdfFlateDecode.applyPredictor(encoded, predictor = 12, columns = 3)
            fail("expected PdfParseException")
        } catch (e: PdfParseException) {
            assertEquals(true, e.message!!.contains("unsupported png filter"))
        }
    }

    private fun deflate(data: ByteArray): ByteArray {
        val def = Deflater()
        def.setInput(data)
        def.finish()
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(1024)
        while (!def.finished()) {
            val n = def.deflate(buf)
            out.write(buf, 0, n)
        }
        def.end()
        return out.toByteArray()
    }
}
