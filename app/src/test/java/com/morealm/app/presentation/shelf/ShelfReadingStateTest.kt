package com.morealm.app.presentation.shelf

import com.morealm.app.domain.entity.Book
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShelfReadingStateTest {

    @Test
    fun `book is finished only when progress reaches one`() {
        assertEquals(ShelfReadingState.READING, book("almost", 0.9999f, 1L).shelfReadingState())
        assertEquals(ShelfReadingState.FINISHED, book("done", 1f, 1L).shelfReadingState())
    }

    @Test
    fun `opened book with zero progress is reading`() {
        assertEquals(ShelfReadingState.READING, book("opened", 0f, 1L).shelfReadingState())
    }

    @Test
    fun `untouched book is wanted`() {
        assertEquals(ShelfReadingState.WANTED, book("new", 0f, 0L).shelfReadingState())
    }

    @Test
    fun `folder containing a reading book is reading`() {
        val books = listOf(
            book("reading", 0.3f, 1L),
            book("untouched-1", 0f, 0L),
            book("untouched-2", 0f, 0L),
        )

        assertEquals(ShelfReadingState.READING, books.aggregateShelfReadingState())
    }

    @Test
    fun `folder is finished only when every book is finished`() {
        val completed = listOf(book("done-1", 1f, 1L), book("done-2", 1f, 2L))
        val mixed = completed + book("untouched", 0f, 0L)

        assertEquals(ShelfReadingState.FINISHED, completed.aggregateShelfReadingState())
        assertEquals(ShelfReadingState.WANTED, mixed.aggregateShelfReadingState())
    }

    @Test
    fun `empty folder has no reading state and only matches all`() {
        val state = emptyList<Book>().aggregateShelfReadingState()

        assertNull(state)
        assertTrue(state.matchesShelfFilter("all"))
        assertFalse(state.matchesShelfFilter("reading"))
        assertFalse(state.matchesShelfFilter("wanted"))
        assertFalse(state.matchesShelfFilter("finished"))
    }

    private fun book(id: String, readProgress: Float, lastReadAt: Long): Book = Book(
        id = id,
        title = id,
        readProgress = readProgress,
        lastReadAt = lastReadAt,
    )
}
