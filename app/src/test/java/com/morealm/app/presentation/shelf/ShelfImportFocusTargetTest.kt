package com.morealm.app.presentation.shelf

import com.morealm.app.domain.entity.Book
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShelfImportFocusTargetTest {

    @Test
    fun `root book index follows folder cards`() {
        val books = listOf(book("first"), book("target"))

        val index = resolveShelfImportFocusIndex(
            target = ShelfImportFocusTarget.BookTarget("target"),
            allBooks = books,
            visibleFolderIds = listOf("folder-a", "folder-b"),
            visibleBookIds = books.map { it.id },
            currentFolderId = null,
        )

        assertEquals(3, index)
    }

    @Test
    fun `folder book waits until its folder is open`() {
        val target = book("target", folderId = "folder-a")

        assertNull(
            resolveShelfImportFocusIndex(
                target = ShelfImportFocusTarget.BookTarget(target.id),
                allBooks = listOf(target),
                visibleFolderIds = listOf("folder-a"),
                visibleBookIds = emptyList(),
                currentFolderId = null,
            )
        )
        assertEquals(
            0,
            resolveShelfImportFocusIndex(
                target = ShelfImportFocusTarget.BookTarget(target.id),
                allBooks = listOf(target),
                visibleFolderIds = emptyList(),
                visibleBookIds = listOf(target.id),
                currentFolderId = "folder-a",
            )
        )
    }

    @Test
    fun `folder target uses rendered folder order`() {
        val index = resolveShelfImportFocusIndex(
            target = ShelfImportFocusTarget.FolderTarget("folder-b"),
            allBooks = emptyList(),
            visibleFolderIds = listOf("folder-a", "folder-b"),
            visibleBookIds = emptyList(),
            currentFolderId = null,
        )

        assertEquals(1, index)
    }

    @Test
    fun `target stays pending while database projection is absent`() {
        assertNull(
            resolveShelfImportFocusIndex(
                target = ShelfImportFocusTarget.BookTarget("missing"),
                allBooks = emptyList(),
                visibleFolderIds = emptyList(),
                visibleBookIds = emptyList(),
                currentFolderId = null,
            )
        )
    }

    private fun book(id: String, folderId: String? = null): Book = Book(
        id = id,
        title = id,
        author = "",
        localPath = "content://books/$id.epub",
        folderId = folderId,
    )
}
