package com.morealm.app.domain.storage

import android.app.Application
import android.graphics.Bitmap
import com.morealm.app.domain.annotation.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE, sdk = [28])
class HandwritingRepositoryTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun `saved notes reopen edits keep the page and deletion removes both`() = runBlocking {
        val repo = HandwritingRepository(temp.newFolder())
        val note = HandwrittenNote("note", "book", 2, "chapter.xhtml", 90, "章节", 1)
        repo.save(note, Bitmap.createBitmap(40, 80, Bitmap.Config.ARGB_8888))
        val updated = note.copy(strokes = listOf(InkStroke(listOf(InkPoint(0.2f, 0.5f)), 0xff000000.toInt(), 0.01f)))
        repo.save(updated, null)
        assertEquals(listOf(updated), repo.list("book"))
        assertEquals(80, repo.page(updated)?.height)
        assertTrue(repo.list("another book").isEmpty())
        repo.delete(updated)
        assertTrue(repo.list("book").isEmpty())
        assertNull(repo.page(updated))
    }
    @Test fun `interrupted metadata writes recover the last saved annotation`() = runBlocking {
        val repo = HandwritingRepository(temp.newFolder())
        val note = HandwrittenNote("recover", "book", 2, "chapter.xhtml", 90, "章节", 1)
        repo.save(note, Bitmap.createBitmap(40, 80, Bitmap.Config.ARGB_8888))
        val metadata = temp.root.walkTopDown().single { it.extension == "json" }
        android.util.AtomicFile(metadata).startWrite().use {
            it.write("{incomplete".toByteArray())
        }
        assertEquals(listOf(note), repo.list("book"))
    }

}
