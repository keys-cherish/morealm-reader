package com.morealm.app.domain.webbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BookChapterListTest {

    @Test
    fun `common html fallback reads raw chapter container and ignores recommendations`() {
        val html = """
            <html><body>
              <div class="zhangjie-tuijian">
                <a href="/book/info/other">相关推荐</a>
              </div>
              <ul id="all-chapters-raw">
                <li><a href="/book/read/2">第二章</a></li>
                <li><a href="/book/read/1">第一章</a></li>
                <li><a href="/book/read/1">第一章重复链接</a></li>
              </ul>
            </body></html>
        """.trimIndent()

        val chapters = BookChapterList.parseCommonHtmlChapterList(
            body = html,
            baseUrl = "https://TARGET/book/info/1",
        )

        assertEquals(listOf("第二章", "第一章"), chapters.map { it.title })
        assertEquals(
            listOf("https://TARGET/book/read/2", "https://TARGET/book/read/1"),
            chapters.map { it.url },
        )
    }

    @Test
    fun `common html fallback stays empty without a high confidence toc container`() {
        val html = """
            <html><body>
              <div class="recommendations"><a href="/book/info/2">另一本书</a></div>
            </body></html>
        """.trimIndent()

        val chapters = BookChapterList.parseCommonHtmlChapterList(
            body = html,
            baseUrl = "https://TARGET/book/info/1",
        )

        assertTrue(chapters.isEmpty())
    }

    @Test
    fun `common content fallback keeps chapter text and images`() {
        val html = """
            <html><body>
              <div class="recommendations">无关推荐</div>
              <div id="content">
                <p>正文第一段</p>
                <p><img src="/images/illustration.jpg"></p>
                <script>removeMe()</script>
              </div>
            </body></html>
        """.trimIndent()

        val content = BookContent.parseCommonHtmlContent(
            body = html,
            baseUrl = "https://TARGET/book/read/1",
        )

        assertTrue(content.contains("正文第一段"))
        assertTrue(content.contains("illustration.jpg"))
        assertTrue(!content.contains("removeMe"))
        assertTrue(!content.contains("无关推荐"))
    }
}
