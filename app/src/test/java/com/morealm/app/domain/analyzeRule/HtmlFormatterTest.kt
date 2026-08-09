package com.morealm.app.domain.analyzeRule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URL

class HtmlFormatterTest {

    @Test
    fun `format strips html tags and indents paragraphs`() {
        val raw = "<p>第一段</p><p>第二段</p>"
        val out = HtmlFormatter.format(raw)
        // 段落转换行 + 第二段缩进
        assertTrue("got: $out", out.contains("第一段"))
        assertTrue("got: $out", out.contains("第二段"))
        assertTrue("got: $out", out.contains("\n"))
    }

    @Test
    fun `format collapses nbsp to single space`() {
        val out = HtmlFormatter.format("a&nbsp;&nbsp;&nbsp;b")
        assertEquals("a b", out)
    }

    @Test
    fun `format strips zero-width characters`() {
        // 参照实现对齐: 处理的是 thinsp / zwnj / zwj（U+2009 / U+200C / U+200D），
        // ZWSP (U+200B) 不在剥离表里 — 与参照实现行为一致。
        val out = HtmlFormatter.format("a b‌c‍d")
        assertEquals("abcd", out)
    }

    @Test
    fun `format removes html comments`() {
        val out = HtmlFormatter.format("<p>before<!--ad-->after</p>")
        assertTrue("got: $out", out.contains("beforeafter"))
    }

    @Test
    fun `formatKeepImg rewrites relative src to absolute`() {
        val raw = "<p>段</p><img src=\"/img/a.jpg\"><p>段2</p>"
        val out = HtmlFormatter.formatKeepImg(raw, URL("https://example.com/page/"))
        assertTrue(
            "expected absolute img src in: $out",
            out.contains("<img src=\"https://example.com/img/a.jpg\">")
        )
    }

    @Test
    fun `formatKeepImg uses data-src when present`() {
        val raw = "<img data-src=\"/lazy.jpg\" src=\"placeholder.gif\">"
        val out = HtmlFormatter.formatKeepImg(raw, URL("https://x.com/"))
        assertTrue("got: $out", out.contains("https://x.com/lazy.jpg"))
    }

    @Test
    fun `null input returns empty`() {
        assertEquals("", HtmlFormatter.format(null))
        assertEquals("", HtmlFormatter.formatKeepImg(null, null))
    }
}
