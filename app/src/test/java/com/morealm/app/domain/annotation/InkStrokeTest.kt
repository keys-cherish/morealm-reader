package com.morealm.app.domain.annotation

import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.json.Json

class InkStrokeTest {
    @Test fun `eraser hits sparse segment between samples`() {
        val stroke = InkStroke(listOf(InkPoint(0f, 0f), InkPoint(1f, 1f)), 0, 0.01f)
        assertTrue(stroke.touches(InkPoint(0.5f, 0.5f), 0.02f))
        assertFalse(stroke.touches(InkPoint(0.2f, 0.8f), 0.02f))
    }
    @Test fun `annotation roundtrip retains source anchor and normalized strokes`() {
        val note = HandwrittenNote("id", "book", 4, "chapter.xhtml", 120, "章节", 1,
            listOf(InkStroke(listOf(InkPoint(0.2f, 0.7f)), 0xff000000.toInt(), 0.01f)))
        val encoded = Json.encodeToString(HandwrittenNote.serializer(), note)
        assertEquals(note, Json.decodeFromString(HandwrittenNote.serializer(), encoded))
    }
}
