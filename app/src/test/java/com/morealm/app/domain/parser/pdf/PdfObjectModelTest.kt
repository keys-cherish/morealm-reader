package com.morealm.app.domain.parser.pdf

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PdfObjectModelTest {

    private fun parse(input: String): PdfValue {
        val bytes = input.toByteArray(Charsets.ISO_8859_1)
        val r = PdfObjectReader(bytes)
        r.skipWhitespaceAndComments()
        return r.readValue()
    }

    // ── Tokenizer / lexer ──

    @Test fun `parses boolean true`() = assertEquals(PdfValue.Bool(true), parse("true"))
    @Test fun `parses boolean false`() = assertEquals(PdfValue.Bool(false), parse("false"))
    @Test fun `parses null`() = assertEquals(PdfValue.Null, parse("null"))

    @Test fun `parses integer`() {
        val v = parse("42") as PdfValue.Num
        assertEquals(42, v.toInt())
    }

    @Test fun `parses negative real`() {
        val v = parse("-3.14") as PdfValue.Num
        assertEquals(-3.14, v.value, 0.001)
    }

    @Test fun `parses signed leading dot`() {
        val v = parse("+.5") as PdfValue.Num
        assertEquals(0.5, v.value, 0.001)
    }

    @Test fun `parses name with hex escape`() {
        val v = parse("/A#20B") as PdfValue.Name
        assertEquals("A B", v.value)
    }

    @Test fun `parses name lowercase hex escape`() {
        val v = parse("/foo#7e") as PdfValue.Name
        assertEquals("foo~", v.value)
    }

    @Test fun `literal string with nested parens`() {
        val v = parse("(outer (inner) end)") as PdfValue.PString
        assertEquals("outer (inner) end", String(v.bytes, Charsets.ISO_8859_1))
    }

    @Test fun `literal string with escapes`() {
        val v = parse("(a\\nb\\tc\\(d\\))") as PdfValue.PString
        assertEquals("a\nb\tc(d)", String(v.bytes, Charsets.ISO_8859_1))
    }

    @Test fun `literal string with octal escape`() {
        val v = parse("(\\053)") as PdfValue.PString
        // \053 = 0o53 = 0x2B = '+'
        assertEquals("+", String(v.bytes, Charsets.ISO_8859_1))
    }

    @Test fun `literal string normalizes CR to LF`() {
        val v = parse("(a\rb\r\nc)") as PdfValue.PString
        assertEquals("a\nb\nc", String(v.bytes, Charsets.ISO_8859_1))
    }

    @Test fun `hex string with whitespace`() {
        val v = parse("<48 65 6C 6C 6F>") as PdfValue.PString
        assertEquals("Hello", String(v.bytes, Charsets.ISO_8859_1))
    }

    @Test fun `hex string odd length pads zero`() {
        val v = parse("<F>") as PdfValue.PString
        // 'F' → 'F0' → 0xF0
        assertArrayEquals(byteArrayOf(0xF0.toByte()), v.bytes)
    }

    // ── Reader: arrays, dicts, refs ──

    @Test fun `empty array`() {
        val v = parse("[]") as PdfValue.Array
        assertTrue(v.items.isEmpty())
    }

    @Test fun `mixed array`() {
        val v = parse("[1 2.5 /Foo (bar) true]") as PdfValue.Array
        assertEquals(5, v.items.size)
        assertEquals(1, (v.items[0] as PdfValue.Num).toInt())
        assertEquals("Foo", (v.items[2] as PdfValue.Name).value)
    }

    @Test fun `nested arrays`() {
        val v = parse("[[1 2] [3 4]]") as PdfValue.Array
        assertEquals(2, v.items.size)
        val inner = v.items[0] as PdfValue.Array
        assertEquals(2, inner.items.size)
        assertEquals(1, (inner.items[0] as PdfValue.Num).toInt())
    }

    @Test fun `empty dict`() {
        val v = parse("<<>>") as PdfValue.Dict
        assertTrue(v.entries.isEmpty())
    }

    @Test fun `simple dict`() {
        val v = parse("<< /Type /Page /Count 5 >>") as PdfValue.Dict
        assertEquals("Page", (v["Type"] as PdfValue.Name).value)
        assertEquals(5, (v["Count"] as PdfValue.Num).toInt())
    }

    @Test fun `nested dict`() {
        val v = parse("<< /Inner << /K 1 >> >>") as PdfValue.Dict
        val inner = v["Inner"] as PdfValue.Dict
        assertEquals(1, (inner["K"] as PdfValue.Num).toInt())
    }

    @Test fun `reference parses as Ref`() {
        val v = parse("5 0 R") as PdfValue.Ref
        assertEquals(5, v.objNum)
        assertEquals(0, v.gen)
    }

    @Test fun `two adjacent numbers are not a Ref`() {
        // 在 array 上下文里
        val v = parse("[5 0]") as PdfValue.Array
        assertEquals(2, v.items.size)
        assertEquals(5, (v.items[0] as PdfValue.Num).toInt())
        assertEquals(0, (v.items[1] as PdfValue.Num).toInt())
    }

    @Test fun `comments are skipped`() {
        val v = parse("% header comment\n/Foo % trailing\n") as PdfValue.Name
        assertEquals("Foo", v.value)
    }

    // ── Indirect object ──

    @Test fun `indirect object reads dict body`() {
        val input = "5 0 obj\n<< /Type /Page >>\nendobj"
        val r = PdfObjectReader(input.toByteArray(Charsets.ISO_8859_1))
        val v = r.readIndirectObject() as PdfValue.Dict
        assertEquals("Page", (v["Type"] as PdfValue.Name).value)
    }

    @Test fun `indirect object with stream`() {
        val body = "Hello PDF"
        val input = "7 0 obj\n<< /Length ${body.length} >>\nstream\n$body\nendstream\nendobj"
        val r = PdfObjectReader(input.toByteArray(Charsets.ISO_8859_1))
        val v = r.readIndirectObject() as PdfValue.Stream
        assertEquals(body.length, (v.dict["Length"] as PdfValue.Num).toInt())
        assertArrayEquals(body.toByteArray(Charsets.ISO_8859_1), v.rawBytes)
    }

    // ── Failure cases ──

    @Test fun `unterminated literal string throws`() {
        try {
            parse("(no close")
            fail("expected PdfParseException")
        } catch (e: PdfParseException) {
            assertTrue(e.message!!.contains("unterminated"))
        }
    }

    @Test fun `unknown keyword throws`() {
        try {
            parse("nonesuch")
            fail("expected PdfParseException")
        } catch (e: PdfParseException) {
            assertTrue(e.message!!.contains("unknown"))
        }
    }
}
