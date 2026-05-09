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
}
