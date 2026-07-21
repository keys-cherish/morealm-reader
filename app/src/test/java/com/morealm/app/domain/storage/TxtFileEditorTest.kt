package com.morealm.app.domain.storage

import org.junit.Assert.assertEquals
import org.junit.Test

class TxtFileEditorTest {

    @Test
    fun `literal replacement supports case insensitive matching`() {
        val result = TxtFileEditor.replaceTextForTest(
            text = "Alpha alpha BETA",
            request = TxtReplaceRequest("alpha", "done", isCaseSensitive = false),
        )

        assertEquals("done done BETA", result.first)
        assertEquals(2, result.second)
    }

    @Test
    fun `regex replacement expands capture groups`() {
        val result = TxtFileEditor.replaceTextForTest(
            text = "第12章 第34章",
            request = TxtReplaceRequest("第(\\d+)章", "Chapter \$1", isRegex = true),
        )

        assertEquals("Chapter 12 Chapter 34", result.first)
        assertEquals(2, result.second)
    }

    @Test
    fun `target ordinal replaces only selected match`() {
        val result = TxtFileEditor.replaceTextForTest(
            text = "a a a",
            request = TxtReplaceRequest("a", "x"),
            targetOrdinal = 1,
        )

        assertEquals("a x a", result.first)
        assertEquals(1, result.second)
    }
}
