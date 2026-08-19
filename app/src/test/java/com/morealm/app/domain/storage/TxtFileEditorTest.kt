package com.morealm.app.domain.storage

import android.net.Uri
import com.morealm.app.domain.entity.BookChapter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

@RunWith(RobolectricTestRunner::class)
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

    @Test
    fun `literal replacement preserves backslashes and group-like text`() {
        val result = TxtFileEditor.replaceTextForTest(
            text = "旧文字",
            request = TxtReplaceRequest("旧文字", "\\n-\$1", isRegex = false),
        )

        assertEquals("\\n-\$1", result.first)
        assertEquals(1, result.second)
    }

    @Test
    fun `successful replacement keeps one undo snapshot and restore writes original bytes`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val original = "第一章\n旧文字\n"
        val file = File.createTempFile("txt-editor-test-", ".txt", context.cacheDir)
        file.writeText(original, Charsets.UTF_8)
        val uri = Uri.fromFile(file)
        val chapter = BookChapter(
            id = "book_0",
            bookId = "book",
            index = 0,
            title = "第一章",
            startPosition = 0,
            endPosition = file.length(),
        )

        val result = TxtFileEditor.replace(
            context = context,
            uri = uri,
            chapters = listOf(chapter),
            scope = TxtEditScope.CHAPTER,
            request = TxtReplaceRequest("旧文字", "新文字"),
            targetChapterIndex = 0,
        )

        assertEquals(1, result.replacedCount)
        assertTrue(result.fileChanged)
        assertEquals("第一章\n新文字\n", file.readText(Charsets.UTF_8))
        val snapshot = requireNotNull(result.undoSnapshot)
        assertTrue(snapshot.backupFile.isFile)

        TxtFileEditor.restore(context, uri, snapshot)
        assertEquals(original, file.readText(Charsets.UTF_8))
        snapshot.discard()
        assertFalse(snapshot.backupFile.exists())
        file.delete()
        Unit
    }
}
