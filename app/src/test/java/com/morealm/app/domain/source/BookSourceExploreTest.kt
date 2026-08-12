package com.morealm.app.domain.source

import com.morealm.app.domain.entity.BookSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * exploreKinds 解析行为对照参照实现 BookSourceExtensions：
 * JSON 数组 / `标题::URL` 文本（`&&` 或换行分隔）/ 分节标题 / 缓存复用。
 * JS 求值路径依赖 Rhino + CacheManager 运行时，走真机集成验证，不在纯 JVM 单测覆盖。
 */
class BookSourceExploreTest {

    private fun source(url: String, exploreUrl: String?) = BookSource(
        bookSourceUrl = url,
        bookSourceName = "test",
        exploreUrl = exploreUrl,
    )

    @Test
    fun `json array explore config parses title url and style`() = runBlocking {
        val kinds = source(
            "https://a.test/json",
            """[
                {"title":"玄幻","url":"/xuanhuan/{{page}}.html"},
                {"title":"分割线","style":{"layout_flexGrow":1,"layout_wrapBefore":true}},
                {"title":"都市","url":"/dushi/{{page}}.html"}
            ]""",
        ).exploreKinds()

        assertEquals(3, kinds.size)
        assertEquals("玄幻", kinds[0].title)
        assertEquals("/xuanhuan/{{page}}.html", kinds[0].url)
        assertNull(kinds[1].url)
        assertEquals(1f, kinds[1].style().layout_flexGrow)
        assertTrue(kinds[1].style().layout_wrapBefore)
        assertEquals("都市", kinds[2].title)
    }

    @Test
    fun `newline separated title-url pairs parse in order`() = runBlocking {
        val kinds = source(
            "https://b.test/lines",
            "玄幻::/xh/{{page}}\n都市::/ds/{{page}}\n完本::/wb/{{page}}",
        ).exploreKinds()

        assertEquals(listOf("玄幻", "都市", "完本"), kinds.map { it.title })
        assertEquals("/ds/{{page}}", kinds[1].url)
    }

    @Test
    fun `ampersand separated pairs and section headers parse together`() = runBlocking {
        val kinds = source(
            "https://c.test/amp",
            "男生频道&&玄幻::/xh/{{page}}&&都市::/ds/{{page}}",
        ).exploreKinds()

        assertEquals(3, kinds.size)
        // 首项没有 :: → 分节标题，url 为 null（参照实现语义：不可点击）
        assertEquals("男生频道", kinds[0].title)
        assertNull(kinds[0].url)
        assertEquals("/xh/{{page}}", kinds[1].url)
    }

    @Test
    fun `blank explore url yields empty list`() = runBlocking {
        assertTrue(source("https://d.test/empty", "").exploreKinds().isEmpty())
        assertTrue(source("https://d.test/null", null).exploreKinds().isEmpty())
    }

    @Test
    fun `second call returns memory-cached instance`() = runBlocking {
        val src = source("https://e.test/cache", "玄幻::/xh/{{page}}")
        val first = src.exploreKinds()
        val second = src.exploreKinds()
        assertSame(first, second)
    }

    @Test
    fun `changing explore config invalidates cache key`() = runBlocking {
        val url = "https://f.test/rekey"
        val first = source(url, "玄幻::/xh/{{page}}").exploreKinds()
        // 同一源改了 exploreUrl → md5 key 变化 → 重新解析而非命中旧缓存
        val second = source(url, "都市::/ds/{{page}}").exploreKinds()
        assertEquals("玄幻", first[0].title)
        assertEquals("都市", second[0].title)
    }

    @Test
    fun `malformed json falls back to error kind`() = runBlocking {
        val kinds = source(
            "https://g.test/badjson",
            """[{"title":"玄幻","url":}]""",
        ).exploreKinds()

        assertEquals(1, kinds.size)
        assertTrue(kinds[0].title.startsWith("ERROR:"))
    }
}
