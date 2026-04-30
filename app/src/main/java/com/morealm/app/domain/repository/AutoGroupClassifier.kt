package com.morealm.app.domain.repository

import com.morealm.app.domain.entity.Book
import com.morealm.app.domain.entity.BookGroup
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Legacy adapter that the rest of the codebase still calls during the v17
 * transition: returns a single `folderId` so the existing single-bucket
 * shelf queries keep working.
 *
 * Internally it now defers to [TagResolver] (multi-tag, scored, word-aware)
 * and additionally writes the full tag set to `book_tags` so the new shelf
 * UI (chip filters, smart views) can read multi-tag data without each call
 * site having to know about it.
 *
 * Migration plan (out of scope for this change): once all call sites talk
 * directly to [TagResolver] / [TagRepository], drop this adapter entirely.
 */
@Singleton
class AutoGroupClassifier @Inject constructor(
    private val tagResolver: TagResolver,
    private val tagRepo: TagRepository,
) {
    /**
     * Returns a folderId for the book. Side-effect: writes all resolved
     * tags into `book_tags` as AUTO assignments. Manual assignments on the
     * book are preserved.
     *
     * Honours [Book.groupLocked] — locked books are never reclassified.
     */
    suspend fun classify(book: Book): String? {
        // groupLocked is the user's explicit "hands off" — never touch tags or folder.
        if (book.groupLocked) return book.folderId

        // Always (re)compute scored tags so chip filters and smart views stay fresh
        // when metadata gets backfilled (e.g. detail-page kind arrives after add-to-shelf).
        val scored = tagResolver.resolve(book)
        if (scored.isNotEmpty()) {
            tagRepo.setAutoTags(book.id, scored.map { it.tagId to it.score })
        }

        // For folderId, however, respect MANUAL placements — user moved it on
        // purpose, don't second-guess them just because the auto-resolver
        // would now pick differently.
        if (book.folderId != null && book.tagsAssignedBy == "MANUAL") return book.folderId

        // Pick the primary "user-or-genre" tag for folderId. Source tags are
        // useful as filters but make terrible folders ("起点" lumps fantasy +
        // romance + sci-fi together), so we skip them here.
        return scored.firstOrNull { !it.tagId.startsWith("source:") }?.tagId
            ?: book.folderId // Don't clobber a previously-set folderId on a no-match run.
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
        return groups.firstOrNull { it.id == resolved.tagId }
    }
}
