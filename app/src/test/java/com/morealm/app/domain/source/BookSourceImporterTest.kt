package com.morealm.app.domain.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BookSourceImporter 解析 / 错误识别回归测试。
 *
 * 重点保护：
 *  1. 标准形态（数组 / 单对象 / 包装键）正常解析
 *  2. 第三方书源服务错误 envelope（`{code:500,message:...,data:null}`）能被识别为
 *     上游错误而不是误导性的「顶层既不是数组也不是对象」
 *  3. WRAPPER_KEYS 命中 JsonNull 时跳过继续找下一个键，不递归到 else 分支
 */
class BookSourceImporterTest {

    private fun importerError(json: String): String? {
        val res = BookSourceImporter.importFromJson(json)
        assertTrue("expected empty result, got ${res.size}", res.isEmpty())
        return BookSourceImporter.lastImportError
    }

    @Test
    fun `array of single book source parses`() {
        val json = """
            [{"bookSourceUrl":"https://example.com","bookSourceName":"测试源"}]
        """.trimIndent()
        val sources = BookSourceImporter.importFromJson(json)
        assertEquals(1, sources.size)
        assertEquals("https://example.com", sources[0].bookSourceUrl)
        assertNull(BookSourceImporter.lastImportError)
    }

    @Test
    fun `single book source object parses`() {
        val json = """{"bookSourceUrl":"https://x","bookSourceName":"X"}"""
        val sources = BookSourceImporter.importFromJson(json)
        assertEquals(1, sources.size)
        assertEquals("https://x", sources[0].bookSourceUrl)
    }

    @Test
    fun `wrapper key data unwraps to inner array`() {
        val json = """
            {"data":[{"bookSourceUrl":"https://w","bookSourceName":"W"}]}
        """.trimIndent()
        val sources = BookSourceImporter.importFromJson(json)
        assertEquals(1, sources.size)
        assertEquals("https://w", sources[0].bookSourceUrl)
    }

    // ── 错误 envelope 识别 ────────────────────────────────────────────────

    @Test
    fun `yiove style 500 envelope reports upstream error`() {
        // 真实 yiove backend 响应：
        // GET /import/book-source-collection/<uuid> → HTTP 200 + 此 body
        val json = """
            {"code":500,"message":"failed link: failed to get file: open /mnt/my/sharepoint: no such file or directory","data":null}
        """.trimIndent()
        val err = importerError(json)
        assertNotNull("应该识别为上游错误", err)
        assertTrue("错误信息应包含 code: $err", err!!.contains("code=500"))
        assertTrue("错误信息应包含上游 message: $err", err.contains("sharepoint"))
        assertTrue(
            "错误信息应明确是上游问题，而非「顶层不是数组/对象」: $err",
            err.contains("上游"),
        )
    }

    @Test
    fun `code 200 with data null falls through wrapper-not-found path`() {
        // code=200/0 表示成功 envelope 但是没数据，不应被误识别为错误。
        // 当前行为：JsonNull 跳过，继续找下一个 wrapper，最终报「找不到包装键」——
        // 比误报「上游错误 code=200」准确。
        val json = """{"code":200,"data":null}"""
        val err = importerError(json)
        assertNotNull(err)
        assertTrue(
            "code=200 不应被识别为上游错误: $err",
            !err!!.contains("上游"),
        )
    }

    @Test
    fun `wrapper key with json null skips and tries next key`() {
        // data 是 null，但 sources 是合法数组——应该跳过 data 找到 sources。
        // WRAPPER_KEYS 的顺序里 sources 在 data 之前，所以这个测试主要保护
        // 「decodeElement(JsonNull) 不再被调用」，避免误报「顶层不是数组/对象」。
        val json = """
            {"data":null,"sources":[{"bookSourceUrl":"https://a","bookSourceName":"A"}]}
        """.trimIndent()
        val sources = BookSourceImporter.importFromJson(json)
        assertEquals(1, sources.size)
    }

    @Test
    fun `code at non-error value but no wrapper falls to wrapper-not-found`() {
        // code=0 是常见「成功」语义，不应触发上游错误分支。
        val json = """{"code":0,"foo":"bar"}"""
        val err = importerError(json)
        assertNotNull(err)
        assertTrue("code=0 不应被识别为上游错误: $err", !err!!.contains("上游"))
    }

    @Test
    fun `error envelope with msg field instead of message also recognized`() {
        // 不同后端用 msg / error / message 不一，依次尝试。
        val json = """{"code":403,"msg":"unauthorized"}"""
        val err = importerError(json)
        assertNotNull(err)
        assertTrue("应识别 code=403: $err", err!!.contains("code=403"))
        assertTrue("应包含 msg 字段值: $err", err.contains("unauthorized"))
    }
}
