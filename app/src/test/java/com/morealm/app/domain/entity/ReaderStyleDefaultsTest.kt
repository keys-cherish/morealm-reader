package com.morealm.app.domain.entity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderStyleDefaultsTest {

    @Test
    fun `default preset matches authored-style reading baseline`() {
        val default = ReaderStyle.defaults().single { it.id == "preset_paper" }

        assertEquals(18, default.textSize)
        assertEquals(0f, default.letterSpacing, 0f)
        assertEquals(1.4f, default.lineHeight, 0f)
        assertTrue(default.isBuiltin)
        assertEquals(3, ReaderStyle.PRESET_VERSION)
    }

    @Test
    fun `version sync rows only cover builtin ids`() {
        val custom = ReaderStyle(id = "custom-reading", name = "我的排版", textSize = 23)
        val syncRows = ReaderStyle.defaults()

        assertTrue(syncRows.all { it.isBuiltin && it.id.startsWith("preset_") })
        assertFalse(syncRows.any { it.id == custom.id })

        // Room REPLACE 以主键逐行更新；同步列表不含 custom id，因此自建样式原值保留。
        val rowsAfterUpsert = (listOf(custom) + syncRows).associateBy { it.id }
        assertEquals(custom, rowsAfterUpsert[custom.id])
    }
}
