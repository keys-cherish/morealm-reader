package com.morealm.app.domain.render.color

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [HighlightWordMatcher] 纯逻辑单测（无 Android 依赖，普通 JUnit）。
 *
 * match() 返回每**码点**命中的色号（-1 = 未命中）。验证长词优先 / 大小写折叠 / 不重叠 /
 * cp 口径 / 空词表。fixture 用通用示例词（非真实作品名）。
 */
class HighlightWordMatcherTest {

    @Test
    fun `单词命中标对应色号，其余 -1`() {
        val m = HighlightWordMatcher(listOf("张三" to 7))
        // 「我是张三」→ 我(-1) 是(-1) 张(7) 三(7)
        assertArrayEquals(intArrayOf(-1, -1, 7, 7), m.match("我是张三"))
    }

    @Test
    fun `同词多次出现都命中`() {
        val m = HighlightWordMatcher(listOf("ab" to 3))
        assertArrayEquals(intArrayOf(3, 3, -1, 3, 3), m.match("abxab"))
    }

    @Test
    fun `长词优先 — 长词盖短词，短词不覆盖长词已占码点`() {
        // 词表含「张三」(2) 与「张三丰」(5)；文本「张三丰」应全标 5（长词），不被「张三」截断
        val m = HighlightWordMatcher(listOf("张三" to 2, "张三丰" to 5))
        assertArrayEquals(intArrayOf(5, 5, 5), m.match("张三丰"))
    }

    @Test
    fun `长词优先 — 长词不出现时短词仍命中`() {
        val m = HighlightWordMatcher(listOf("张三" to 2, "张三丰" to 5))
        // 「张三说」→ 张三(2) 说(-1)；无「丰」故走短词
        assertArrayEquals(intArrayOf(2, 2, -1), m.match("张三说"))
    }

    @Test
    fun `大小写不敏感`() {
        val m = HighlightWordMatcher(listOf("hello" to 1))
        assertArrayEquals(intArrayOf(1, 1, 1, 1, 1), m.match("HeLLo"))
    }

    @Test
    fun `相邻不同词各自上色`() {
        val m = HighlightWordMatcher(listOf("张三" to 1, "李四" to 2))
        assertArrayEquals(intArrayOf(1, 1, 2, 2), m.match("张三李四"))
    }

    @Test
    fun `空词表 — isEmpty 且全 -1`() {
        val m = HighlightWordMatcher(emptyList())
        assertTrue(m.isEmpty)
        assertArrayEquals(intArrayOf(-1, -1, -1), m.match("abc"))
    }

    @Test
    fun `空白词被过滤，正常词照常匹配`() {
        val m = HighlightWordMatcher(listOf("" to 1, "x" to 2))
        assertFalse(m.isEmpty)
        assertArrayEquals(intArrayOf(2, -1), m.match("xy"))
    }

    @Test
    fun `cp 口径 — surrogate emoji 算 1 码点，词不串位`() {
        val m = HighlightWordMatcher(listOf("B" to 4))
        // A😀B → A(-1) 😀(-1) B(4)，size == 3
        val out = m.match("A😀B")
        assertEquals(3, out.size)
        assertArrayEquals(intArrayOf(-1, -1, 4), out)
    }

    @Test
    fun `未命中全 -1`() {
        val m = HighlightWordMatcher(listOf("xyz" to 1))
        assertArrayEquals(intArrayOf(-1, -1, -1), m.match("abc"))
    }

    @Test
    fun `空文本返回空数组`() {
        val m = HighlightWordMatcher(listOf("a" to 1))
        assertEquals(0, m.match("").size)
    }
}
