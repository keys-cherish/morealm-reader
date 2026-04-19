package com.morealm.app.core.text

import org.junit.Assert.*
import org.junit.Test

class ChineseConverterTest {

    @Test
    fun `mode 0 returns original`() {
        assertEquals("你好世界", ChineseConverter.convert("你好世界", 0))
    }

    @Test
    fun `mode 1 converts simplified to traditional`() {
        val result = ChineseConverter.convert("国家", 1)
        assertEquals("國家", result)
    }

    @Test
    fun `mode 2 converts traditional to simplified`() {
        val result = ChineseConverter.convert("國家", 2)
        assertEquals("国家", result)
    }

    @Test
    fun `empty string returns empty`() {
        assertEquals("", ChineseConverter.convert("", 1))
    }

    @Test
    fun `ASCII text unchanged`() {
        assertEquals("Hello World", ChineseConverter.convert("Hello World", 1))
    }

    @Test
    fun `invalid mode returns original`() {
        assertEquals("测试", ChineseConverter.convert("测试", 99))
    }
}
