package com.morealm.app.domain.entity

import org.junit.Assert.*
import org.junit.Test

class ReplaceRuleTest {

    @Test
    fun `valid plain text rule`() {
        val rule = ReplaceRule(id = "1", pattern = "广告", isRegex = false)
        assertTrue(rule.isValid())
    }

    @Test
    fun `empty pattern is invalid`() {
        val rule = ReplaceRule(id = "1", pattern = "", isRegex = false)
        assertFalse(rule.isValid())
    }

    @Test
    fun `valid regex rule`() {
        val rule = ReplaceRule(id = "1", pattern = "\\d+", isRegex = true)
        assertTrue(rule.isValid())
    }

    @Test
    fun `invalid regex syntax`() {
        val rule = ReplaceRule(id = "1", pattern = "[invalid", isRegex = true)
        assertFalse(rule.isValid())
    }

    @Test
    fun `trailing pipe is invalid`() {
        val rule = ReplaceRule(id = "1", pattern = "a|b|", isRegex = true)
        assertFalse(rule.isValid())
    }

    @Test
    fun `scopeTitle and scopeContent defaults`() {
        val rule = ReplaceRule(id = "1", pattern = "test")
        assertFalse(rule.scopeTitle)
        assertTrue(rule.scopeContent)
    }

    @Test
    fun `default timeout is 3000ms`() {
        val rule = ReplaceRule(id = "1", pattern = "test")
        assertEquals(3000, rule.timeoutMs)
    }
}
