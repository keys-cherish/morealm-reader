package com.morealm.app.domain.render.color

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [RuleColorScanner] 纯逻辑单测（无 Android 依赖，普通 JUnit）。
 *
 * 用「标识色」palette（每类别一个独特小整数）让断言可读：out 数组 == 期望类别序列。
 * PUNCT=1 / NUM=2 / LATIN=3 / SPECIAL=4 / QUOTE_INNER=5 / BRACKET_INNER=6；NONE 走 0。
 */
class RuleColorScannerTest {

    private val scanner = RuleColorScanner(
        RuleColorPalette(
            mapOf(
                RuleColorCategory.PUNCTUATION to 1,
                RuleColorCategory.NUMBER to 2,
                RuleColorCategory.LATIN to 3,
                RuleColorCategory.SPECIAL to 4,
                RuleColorCategory.QUOTE_INNER to 5,
                RuleColorCategory.BRACKET_INNER to 6,
            ),
        ),
    )

    @Test
    fun `拉丁词 + 数字 + 标点`() {
        assertArrayEquals(intArrayOf(3, 3, 3, 2, 2, 2, 1), scanner.scan("abc123，"))
    }

    @Test
    fun `CJK 正文不上色`() {
        assertArrayEquals(intArrayOf(0, 0), scanner.scan("你好"))
    }

    @Test
    fun `引号内 CJK 走 quoteInner，引号本身是标点`() {
        assertArrayEquals(intArrayOf(1, 5, 5, 1), scanner.scan("「张三」"))
    }

    @Test
    fun `括号内数字仍按数字类，括号本身标点`() {
        assertArrayEquals(intArrayOf(1, 2, 2, 2, 1), scanner.scan("（123）"))
    }

    @Test
    fun `括号内 CJK 走 bracketInner`() {
        assertArrayEquals(intArrayOf(1, 6, 6, 1), scanner.scan("（你好）"))
    }

    @Test
    fun `引号内可嵌一层括号，括号内 CJK 是 bracketInner 不是 quoteInner`() {
        assertArrayEquals(intArrayOf(1, 1, 6, 1, 1), scanner.scan("「（你）」"))
    }

    @Test
    fun `特殊符号 × 从拉丁排除归 special`() {
        assertArrayEquals(intArrayOf(3, 4, 3), scanner.scan("A×B"))
    }

    @Test
    fun `全角数字与全角字母`() {
        assertArrayEquals(intArrayOf(2, 2, 2), scanner.scan("１２３"))
        assertArrayEquals(intArrayOf(3, 3), scanner.scan("ＡＢ"))
    }

    @Test
    fun `失配引号不跨行 — 换行 pop 回 root`() {
        // 「张\n三：「(标点) 张(引号内) \n(pop→0) 三(回 root CJK→0)
        assertArrayEquals(intArrayOf(1, 5, 0, 0), scanner.scan("「张\n三"))
    }

    @Test
    fun `cp 口径 — surrogate emoji 算 1 个码点`() {
        // A😀B：😀 补充平面(2 char 1 cp)，root 兜底 0
        val out = scanner.scan("A😀B")
        assertEquals(3, out.size)
        assertArrayEquals(intArrayOf(3, 0, 3), out)
    }

    @Test
    fun `ASCII 双引号开闭同字符成对`() {
        // "A"：" 开(1) A(3) " 闭(1)
        assertArrayEquals(intArrayOf(1, 3, 1), scanner.scan("\"A\""))
    }

    @Test
    fun `拉丁词连续吃成同色（不在词中断）`() {
        assertArrayEquals(intArrayOf(3, 3, 3, 3, 3), scanner.scan("Hello"))
    }

    @Test
    fun `空串返回空数组`() {
        assertEquals(0, scanner.scan("").size)
    }

    @Test
    fun `mergeCssPriority — CSS 非 0 优先，TXT null 全用规则`() {
        assertArrayEquals(
            intArrayOf(1, 9, 3),
            RuleColorScanner.mergeCssPriority(intArrayOf(0, 9, 0), intArrayOf(1, 2, 3)),
        )
        assertArrayEquals(
            intArrayOf(1, 2, 3),
            RuleColorScanner.mergeCssPriority(null, intArrayOf(1, 2, 3)),
        )
    }
}
