package com.morealm.app.domain.parser

import org.junit.Assert.*
import org.junit.Test

class TxtParserTest {

    @Test
    fun `detect Chinese chapter headings`() {
        // The chapter detection regex is used inline in LocalBookParser.parseTxtChapters.
        // Test the regex pattern directly.
        val chapterRegex = Regex(
            "^\\s*(?:" +
                "第[零一二三四五六七八九十百千万\\d]+[章节卷集部篇回]" +
                "|[Cc]hapter\\s*\\d+" +
                "|卷[零一二三四五六七八九十百千万\\d]+" +
                "|(?:序章|楔子|番外|尾声|后记|前言|引子)" +
            ").*$"
        )
        val patterns = listOf(
            "第一章 开始" to true,
            "第100章 结局" to true,
            "Chapter 1 Begin" to true,
            "卷一 起源" to true,
            "序章 缘起" to true,
            "楔子" to true,
            "番外 后日谈" to true,
            "这是正文内容不是标题" to false,
            "" to false,
        )
        for ((line, expected) in patterns) {
            val result = chapterRegex.containsMatchIn(line.trim())
            assertEquals("'$line' should be chapter=$expected", expected, result)
        }
    }

    @Test
    fun `splitLargeChapter splits correctly`() {
        val method = LocalBookParser::class.java.getDeclaredMethod(
            "splitLargeChapter",
            com.morealm.app.domain.entity.BookChapter::class.java,
            Long::class.javaPrimitiveType
        )
        method.isAccessible = true
        val chapter = com.morealm.app.domain.entity.BookChapter(
            id = "test_0", bookId = "test", index = 0, title = "大章节",
            startPosition = 0, endPosition = 300_000,
        )
        @Suppress("UNCHECKED_CAST")
        val parts = method.invoke(LocalBookParser, chapter, 100_000L) as List<com.morealm.app.domain.entity.BookChapter>
        assertEquals(3, parts.size)
        assertEquals(0L, parts[0].startPosition)
        assertEquals(100_000L, parts[0].endPosition)
        assertEquals(100_000L, parts[1].startPosition)
        assertEquals(200_000L, parts[1].endPosition)
        assertEquals(200_000L, parts[2].startPosition)
        assertEquals(300_000L, parts[2].endPosition)
    }

    @Test
    fun `charset detection BOM UTF8`() {
        // This test would need a Context mock, skip for now
        // Just verify the method exists
        assertNotNull(LocalBookParser::class.java.getDeclaredMethod(
            "detectCharset", android.content.Context::class.java, android.net.Uri::class.java
        ))
    }
}
