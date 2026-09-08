package com.morealm.app.domain.replace

import com.morealm.app.core.text.ImportTextDecoder
import com.morealm.app.domain.entity.ReplaceRule
import org.junit.Assert.*
import org.junit.Test
import java.nio.charset.Charset

class ReplaceRuleCodecTest {
    private val payload = """[{"name":"净化广告","pattern":"关注公众号.*","replacement":"","isRegex":true}]"""

    @Test fun `Chinese rules survive UTF8 GBK GB18030 and UTF16 files`() {
        for (encoding in listOf("UTF-8", "GBK", "GB18030", "UTF-16LE", "UTF-16BE")) {
            val rule = ReplaceRuleCodec.decode(payload.toByteArray(Charset.forName(encoding))).single()
            assertEquals(encoding, "净化广告", rule.name)
            assertEquals(encoding, "关注公众号.*", rule.pattern)
            assertEquals(ReplaceRule.KIND_PURIFY, rule.kind)
        }
    }

    @Test fun `Unicode BOMs are removed before parsing`() {
        val variants = listOf(
            byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) + payload.toByteArray(),
            byteArrayOf(0xff.toByte(), 0xfe.toByte()) + payload.toByteArray(Charsets.UTF_16LE),
            byteArrayOf(0xfe.toByte(), 0xff.toByte()) + payload.toByteArray(Charsets.UTF_16BE),
        )
        variants.forEach { assertEquals("净化广告", ReplaceRuleCodec.decode(it).single().name) }
    }

    @Test fun `mixed formats and malformed rows do not discard valid rules`() {
        val text = """[null, {"pattern": []}, {"name":"广告","pattern":"广告"},
            {"replaceSummary":"旧规则","regex":"推广"}]"""
        assertEquals(listOf("广告", "旧规则"), ReplaceRuleCodec.decode(text).map { it.name })
    }

    @Test fun `export and reimport preserve disabled rules and exact replacement text`() {
        val original = ReplaceRule(id = "test", name = "标点", pattern = "a", replacement = "中文\n", enabled = false, chapterIndex = 7)
        assertEquals(listOf(original), ReplaceRuleCodec.decode(ReplaceRuleCodec.encode(listOf(original))))
    }

    @Test fun `GB18030 supplementary characters are decoded without replacement characters`() {
        val text = "净化规则 😀"
        assertEquals(text, ImportTextDecoder.decode(text.toByteArray(Charset.forName("GB18030"))))
    }
    @Test fun `older self exports without format discriminator are still accepted`() {
        val old = """{"rules":[{"id":"legacy","name":"旧备份","pattern":"广告"}]}"""
        assertEquals("旧备份", ReplaceRuleCodec.decode(old).single().name)
    }

}
