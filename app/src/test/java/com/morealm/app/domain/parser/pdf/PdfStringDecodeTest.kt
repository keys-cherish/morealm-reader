package com.morealm.app.domain.parser.pdf

import org.junit.Assert.assertEquals
import org.junit.Test

class PdfStringDecodeTest {

    @Test fun `utf-16BE BOM string`() {
        // BOM FE FF + "中文" UTF-16BE
        val bytes = byteArrayOf(
            0xFE.toByte(), 0xFF.toByte(),
            0x4E, 0x2D,  // 中
            0x65.toByte(), 0x87.toByte(),  // 文
        )
        assertEquals("中文", PdfStringDecode.decode(bytes))
    }

    @Test fun `utf-8 BOM string`() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            "ASCII test".toByteArray(Charsets.UTF_8)
        assertEquals("ASCII test", PdfStringDecode.decode(bytes))
    }

    @Test fun `pdfdoc encoding ascii passthrough`() {
        val bytes = "Chapter 1".toByteArray(Charsets.ISO_8859_1)
        assertEquals("Chapter 1", PdfStringDecode.decode(bytes))
    }

    @Test fun `pdfdoc encoding bullet at 0x80`() {
        val bytes = byteArrayOf(0x80.toByte())
        assertEquals("•", PdfStringDecode.decode(bytes))
    }

    @Test fun `pdfdoc encoding euro at 0xA0`() {
        // PDFDoc 0xA0 = € (not NBSP like Latin-1)
        val bytes = byteArrayOf(0xA0.toByte())
        assertEquals("€", PdfStringDecode.decode(bytes))
    }

    @Test fun `pdfdoc encoding latin1 range above A1`() {
        // 0xE9 in PDFDoc = é (same as Latin-1 here)
        val bytes = byteArrayOf(0xE9.toByte())
        assertEquals("é", PdfStringDecode.decode(bytes))
    }

    @Test fun `empty string`() {
        assertEquals("", PdfStringDecode.decode(ByteArray(0)))
    }

    @Test fun `respects max length`() {
        // 5000 字节 PDFDoc → 字符串只保留 MAX_STRING_LEN 个字符
        val bytes = ByteArray(5000) { 'a'.code.toByte() }
        val decoded = PdfStringDecode.decode(bytes)
        assertEquals(PdfLimits.MAX_STRING_LEN, decoded.length)
    }
}
