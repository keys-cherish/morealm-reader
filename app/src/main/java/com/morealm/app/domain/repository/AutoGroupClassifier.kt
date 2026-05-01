package com.morealm.app.domain.repository

import com.morealm.app.domain.entity.Book
import com.morealm.app.domain.entity.BookGroup
import com.morealm.app.domain.entity.TagAssignSource
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Top-level entry point for "where should this book live on the shelf?".
 *
 * The pipeline (v18 model):
 *   1. [TagResolver] — score the book against all GENRE + USER tags using
 *      keyword-aware matching, returning a small ranked list.
 *   2. Persist those scores in `book_tags` so chip filters / per-tag views
 *      reflect every signal we found.
 *   3. [AutoFolderManager] — decide whether the top tag should *promote* into
 *      a real auto-created folder, and return the folderId to assign.
 *
 * The classifier is the only thing call sites need to wire — the manager
 * and resolver are implementation details. Manual placements (user dragged a
 * book into a folder, or [Book.groupLocked] is set) are honoured throughout.
 */
@Singleton
class AutoGroupClassifier @Inject constructor(
    private val tagResolver: TagResolver,
    private val tagRepo: TagRepository,
    private val autoFolders: AutoFolderManager,
) {
    /**
     * Returns the folderId for [book], possibly creating an auto-folder along
     * the way. Always (re)writes AUTO tag assignments so chip filters reflect
     * the latest metadata — that step is independent of folder placement.
     */
    suspend fun classify(book: Book): String? {
        // Locked books are user's explicit "hands off" — never touch anything.
        if (book.groupLocked) return book.folderId

        // 1. Score & persist tags. We do this even for MANUAL-folder books so
        // chip filters stay fresh — only folderId is sticky for them.
        val scored = tagResolver.resolve(book)
        if (scored.isNotEmpty()) {
            tagRepo.setAutoTags(book.id, scored.map { it.tagId to it.score })
        }

        // 2. Respect MANUAL folder placement — user moved this book on
        // purpose, the resolver doesn't get to override.
        if (book.folderId != null && book.tagsAssignedBy == TagAssignSource.MANUAL) {
            return book.folderId
        }

        // 3. Try to promote the top tag into a folder (or reuse an existing one).
        val resolvedFolder = autoFolders.resolveFolder(book, scored)
        // If we got a folder id, use it. Otherwise keep whatever was there
        // (don't clobber an AUTO-set folderId on a no-match re-run).
        return resolvedFolder ?: book.folderId
    }

    /**
     * Compatibility shim for [com.morealm.app.presentation.shelf.ShelfViewModel.reclassifyUngroupedBooks].
     * Returns the first user/genre tag matching the book among the supplied [groups],
     * or null. Implemented in terms of [TagResolver] for keyword-edge correctness.
     */
    suspend fun matchGroup(book: Book, groups: List<BookGroup>): BookGroup? {
        if (groups.isEmpty()) return null
        val resolved = tagResolver.resolve(book, maxTags = 5, minScore = 0.5f)
            .firstOrNull { !it.tagId.startsWith("source:") }
            ?: return null
        // Match either by direct id (USER tags map 1:1 to groups) or by
        // tag-named auto-folder ("auto:builtin:玄幻" / name-equality).
        return groups.firstOrNull { it.id == resolved.tagId }
            ?: groups.firstOrNull { it.id == "auto:${resolved.tagId}" }
    }
}
