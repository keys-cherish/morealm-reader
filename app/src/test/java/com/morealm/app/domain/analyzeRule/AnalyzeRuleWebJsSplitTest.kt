package com.morealm.app.domain.analyzeRule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalyzeRuleWebJsSplitTest {

    @Test
    fun `splitSourceRule recognizes webjs prefix`() {
        val rule = AnalyzeRule()
        val parts = rule.splitSourceRule("@webjs:fetch('/x').then(r => r.text())")
        assertEquals("expected single rule, got $parts", 1, parts.size)
        // Mode.WebJs 分支必须命中 — 否则没有 WebJS_PATTERN 改动被吞
        assertEquals(AnalyzeRule.Mode.WebJs, parts[0].mode)
    }

    @Test
    fun `splitSourceRule still parses js prefix as Js mode`() {
        val rule = AnalyzeRule()
        val parts = rule.splitSourceRule("@js:result.toUpperCase()")
        assertEquals(1, parts.size)
        assertEquals(AnalyzeRule.Mode.Js, parts[0].mode)
    }

    @Test
    fun `splitSourceRule mixes plain default and webjs blocks`() {
        val rule = AnalyzeRule()
        val parts = rule.splitSourceRule(".content@text@webjs:doExtract(result)")
        assertTrue("expected at least 2 parts: $parts", parts.size >= 2)
        // 末段必须是 WebJs
        assertEquals(AnalyzeRule.Mode.WebJs, parts.last().mode)
    }

    /**
     * Copilot review 反馈：JS_PATTERN / WebJS_PATTERN 双 matcher 独立从 0 扫整串时，嵌在
     * `<js>...</js>` 内部的 `@webjs:` 字面会被 WebJS matcher 再次切一刀，导致 ruleList
     * 出现一个虚假的 WebJs token —— 单次线性扫描修复后，这条用例必须只产生 1 条 Js 规则。
     */
    @Test
    fun `splitSourceRule webjs inside js block must not be split out`() {
        val rule = AnalyzeRule()
        val parts = rule.splitSourceRule("<js>let s = '@webjs:fake'; s</js>")
        assertEquals("nested @webjs: was wrongly split: $parts", 1, parts.size)
        assertEquals(AnalyzeRule.Mode.Js, parts[0].mode)
    }

    /**
     * 老实现里 webJsMatcher 从 0 重扫，遇到 `@js:` 之后贪婪段里出现的 `@webjs:` 会被
     * 误识别，并把第二轮 start 倒回到 webJs.end()，吞掉了 JS 段之后本该保留的明文。
     * 单次扫描后，token.start < 已消费 start 的命中应被跳过。
     */
    @Test
    fun `splitSourceRule webjs literal after js prefix is swallowed by js greedy`() {
        val rule = AnalyzeRule()
        val parts = rule.splitSourceRule("@js:foo() // @webjs:bar")
        // @js: 是贪婪到串尾，这条字符串只应解析成 1 个 Js token
        assertEquals("expected single js, got $parts", 1, parts.size)
        assertEquals(AnalyzeRule.Mode.Js, parts[0].mode)
    }

    /**
     * WebJs 段在前、JS 段在后的混合：按 start 排序消费两种 token，应严格按出现顺序输出，
     * 中间的明文片段不能丢、不能被 webJsMatcher 的尾巴贪婪吃掉。
     */
    @Test
    fun `splitSourceRule preserves order when webjs precedes js block`() {
        val rule = AnalyzeRule()
        val parts = rule.splitSourceRule(".intro@text<js>x()</js>tail@text")
        // 期望：[plain ".intro@text", Js "x()", plain "tail@text"]
        assertEquals("got $parts", 3, parts.size)
        assertEquals(AnalyzeRule.Mode.Default, parts[0].mode)
        assertEquals(AnalyzeRule.Mode.Js, parts[1].mode)
        assertEquals(AnalyzeRule.Mode.Default, parts[2].mode)
    }
}
