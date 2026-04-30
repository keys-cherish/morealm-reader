package com.morealm.app.domain.repository

import com.morealm.app.domain.db.BookTagDao
import com.morealm.app.domain.db.TagDefinitionDao
import com.morealm.app.domain.entity.BookTag
import com.morealm.app.domain.entity.TagAssignSource
import com.morealm.app.domain.entity.TagDefinition
import com.morealm.app.domain.entity.TagType
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Façade over [BookTagDao] + [TagDefinitionDao] for the rest of the app.
 *
 * Centralises the "AUTO vs MANUAL" semantics: callers don't need to know the
 * tag-assignment encoding, they just call [setAutoTags] / [setManualTag].
 * Tag definitions and per-book assignments are kept in two separate tables
 * but always exposed together to UI layers.
 */
@Singleton
class TagRepository @Inject constructor(
    private val tagDao: TagDefinitionDao,
    private val bookTagDao: BookTagDao,
) {
    // ── tag_definitions ────────────────────────────────────────────────

    fun observeAllTags(): Flow<List<TagDefinition>> = tagDao.getAllTags()

    fun observeTagsByType(type: String): Flow<List<TagDefinition>> = tagDao.getTagsByType(type)

    suspend fun getAllTags(): List<TagDefinition> = tagDao.getAllTagsSync()

    suspend fun getTagsByType(type: String): List<TagDefinition> = tagDao.getTagsByTypeSync(type)

    suspend fun getTag(id: String): TagDefinition? = tagDao.getById(id)

    suspend fun upsertTag(tag: TagDefinition) = tagDao.insert(tag)

    suspend fun deleteUserTag(id: String) {
        tagDao.deleteUserTag(id)
        bookTagDao.deleteAllForTag(id)
    }

    /**
     * Find or create a SOURCE tag for a given book source name. Used by the
     * resolver so books from "起点" / "番茄" auto-cluster without the user
     * having to define them up front.
     */
    suspend fun upsertSourceTag(originName: String): TagDefinition {
        val trimmed = originName.trim().ifBlank { return systemFallback() }
        tagDao.findByName(trimmed, TagType.SOURCE)?.let { return it }
        val tag = TagDefinition(
            id = "source:$trimmed",
            name = trimmed,
            type = TagType.SOURCE,
            builtin = false,
            sortOrder = 1000,
        )
        tagDao.insert(tag)
        return tag
    }

    private fun systemFallback() = TagDefinition(
        id = "system:unknown", name = "未知来源", type = TagType.SYSTEM, builtin = true,
    )

    // ── book_tags ──────────────────────────────────────────────────────

    suspend fun getTagsForBook(bookId: String): List<BookTag> = bookTagDao.getTagsForBook(bookId)

    fun observeTagsForBook(bookId: String): Flow<List<BookTag>> = bookTagDao.observeTagsForBook(bookId)

    suspend fun getBookIdsByTag(tagId: String): List<String> = bookTagDao.getBookIdsByTag(tagId)

    suspend fun getBookIdsByAllTags(tagIds: List<String>): List<String> {
        if (tagIds.isEmpty()) return emptyList()
        return bookTagDao.getBookIdsByAllTags(tagIds, tagIds.size)
    }

    fun observeBookCountForTag(tagId: String): Flow<Int> = bookTagDao.countBooksWithTag(tagId)

    /**
     * Replace this book's AUTO assignments with [scoredTags]. MANUAL tags are
     * preserved (the user pinned them; we never overwrite that). Idempotent —
     * calling twice with identical input is a no-op net of timestamps.
     */
    suspend fun setAutoTags(bookId: String, scoredTags: List<Pair<String, Float>>) {
        bookTagDao.deleteAutoAssignmentsFor(bookId, TagAssignSource.AUTO)
        if (scoredTags.isEmpty()) return
        val now = System.currentTimeMillis()
        val rows = scoredTags.map { (tagId, score) ->
            BookTag(
                bookId = bookId,
                tagId = tagId,
                assignedBy = TagAssignSource.AUTO,
                score = score,
                assignedAt = now,
            )
        }
        bookTagDao.insertAll(rows)
    }

    /** User pinned a tag → MANUAL with score 1.0. Won't be overwritten by [setAutoTags]. */
    suspend fun setManualTag(bookId: String, tagId: String) {
        bookTagDao.insert(
            BookTag(
                bookId = bookId,
                tagId = tagId,
                assignedBy = TagAssignSource.MANUAL,
                score = 1f,
                assignedAt = System.currentTimeMillis(),
            )
        )
    }

    suspend fun removeTag(bookId: String, tagId: String) = bookTagDao.delete(bookId, tagId)
}
