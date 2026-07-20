package com.morealm.app.util

import org.junit.Assert.assertEquals
import org.junit.Test

class PinyinInitialsTest {

    @Test
    fun `common hanzi map to expected letters`() {
        assertEquals('H', PinyinInitials.groupOf("红砖学园"))
        assertEquals('D', PinyinInitials.groupOf("冬日拾遗"))
        assertEquals('Y', PinyinInitials.groupOf("远星书简"))
        assertEquals('Z', PinyinInitials.groupOf("追风筝的人"))
    }

    @Test
    fun `ascii titles use own letter`() {
        assertEquals('T', PinyinInitials.groupOf("txt_converted"))
        assertEquals('A', PinyinInitials.groupOf("Alice"))
    }

    @Test
    fun `symbols and empty go to hash group`() {
        assertEquals('#', PinyinInitials.groupOf(""))
        assertEquals('#', PinyinInitials.groupOf("《"))
        assertEquals('#', PinyinInitials.groupOf("123"))
    }

    @Test
    fun `initials support acronym search`() {
        assertEquals("HZXY", PinyinInitials.initials("红砖学园"))
        assertEquals("DRSY", PinyinInitials.initials("冬日拾遗"))
    }
}
