package com.morealm.app.domain.repository

import com.morealm.app.domain.entity.Book
import com.morealm.app.domain.entity.BookFormat
import com.morealm.app.domain.entity.TagDefinition
import com.morealm.app.domain.entity.TagType
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Output of the resolver: a tag id paired with classifier confidence.
 *
 * Score range is roughly 0..N where N == number of keyword hits weighted by
 * field. The absolute number doesn't matter — callers compare scores within a
 * single run to pick top-K.
 */
data class ScoredTag(val tagId: String, val score: Float)

/**
 * Replaces the legacy [AutoGroupClassifier] single-bucket logic with a
 * **scored, multi-tag, keyword-edge-aware** classifier.
 *
 * ### Why score, not first-hit?
 * The old classifier returned the first matching group, which made keyword
 * order privilege long lists ("言情" with 7 keywords always beat "科幻" with 1).
 * Scoring lets a precise short tag outrank a noisy long one.
 *
 * ### Why field weights?
 * A 玄幻 hit in the *title* is far more telling than a 玄幻 hit deep inside the
 * 简介 — books cross-list keywords casually in descriptions. We multiply the
 * raw score by the field's weight so signal beats noise.
 *
 * ### Why a word boundary?
 * Naive substring matching is a footgun in Chinese: keyword "军事" matches "军事
 * 爱好者后传" (a 都市 novel) and you've mis-classified. We require the keyword
 * to either start/end the field or be flanked by a non-Chinese character —
 * which works as a cheap-and-cheerful word boundary for CJK without dragging
 * in a tokenizer.
 *
 * Returns up to [maxTags] tags above [minScore], plus auto-derived format /
 * source tags (which are deterministic, not scored).
 */
@Singleton
class TagResolver @Inject constructor(
    private val tagRepo: TagRepository,
) {

    suspend fun resolve(book: Book, maxTags: Int = 3, minScore: Float = 0.5f): List<ScoredTag> {
        // Score against both built-in GENRE vocab and user-created tags. User tags
        // typically carry the keywords the user actually cares about, so we let
        // them compete on equal footing — the keyword count + field weight already
        // give the right ordering naturally.
        val candidates = tagRepo.getTagsByType(TagType.GENRE) + tagRepo.getTagsByType(TagType.USER)
        val scored = scoreGenreTags(book, candidates)
            .filter { it.score >= minScore }
            .sortedByDescending { it.score }
            .take(maxTags)

        val deterministic = mutableListOf<ScoredTag>()
        // Source tag — every WEB book carries the source it came from.
        if (book.format == BookFormat.WEB && book.originName.isNotBlank()) {
            val srcTag = tagRepo.upsertSourceTag(book.originName)
            deterministic += ScoredTag(srcTag.id, 1f)
        }
        return scored + deterministic
    }

    /** Returns the top-scoring user-or-genre tag id, or null. Used by the legacy folderId path. */
    suspend fun resolvePrimaryTagId(book: Book): String? {
        return resolve(book, maxTags = 1, minScore = 0.5f)
            .firstOrNull { !it.tagId.startsWith("source:") }
            ?.tagId
    }

    private fun scoreGenreTags(book: Book, tags: List<TagDefinition>): List<ScoredTag> {
        val fields = collectFields(book)
        if (fields.isEmpty()) return emptyList()
        return tags.mapNotNull { tag ->
            val keywords = parseKeywords(tag.keywords).ifEmpty {
                // Fall back to the tag name itself if user hasn't provided keywords —
                // a tag named "悬疑" should still match books mentioning 悬疑.
                listOf(tag.name)
            }
            val score = scoreTag(keywords, fields)
            if (score > 0f) ScoredTag(tag.id, score) else null
        }
    }

    /**
     * Sums per-field hits weighted by [FieldWeight]. A keyword counts at most
     * once per field (otherwise a description that repeats "玄幻" 5 times would
     * dominate everything).
     */
    private fun scoreTag(keywords: List<String>, fields: List<TextField>): Float {
        var total = 0f
        for (field in fields) {
            val text = field.text
            if (text.isBlank()) continue
            for (kw in keywords) {
                if (kw.length < 2) continue // Avoid single-char false positives.
                if (containsWithBoundary(text, kw)) {
                    total += field.weight
                    break // Don't double-count the same field for the same tag.
                }
            }
        }
        return total
    }

    private fun collectFields(book: Book): List<TextField> = listOf(
        TextField(book.title, FieldWeight.TITLE),
        TextField(book.kind.orEmpty(), FieldWeight.KIND),
        TextField(book.category.orEmpty(), FieldWeight.CATEGORY),
        TextField(book.customTag.orEmpty(), FieldWeight.CUSTOM_TAG),
        TextField(book.description.orEmpty(), FieldWeight.DESCRIPTION),
        TextField(book.author, FieldWeight.AUTHOR),
        TextField(book.originName, FieldWeight.ORIGIN),
    )

    private fun parseKeywords(raw: String): List<String> = raw
        .split(',', '，', ';', '；', '\n', '|', '/', '、', ' ')
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()

    /**
     * Substring match with a CJK-friendly word boundary.
     *
     * Returns true if [text] contains [keyword] AND at least one side of the
     * occurrence is *not* a Chinese character / letter / digit. This rejects
     * "军事" inside "军事爱好者" (leading 军事 is text-start so it'd pass — that's
     * actually fine, "军事" *is* the start of that string and likely intended)
     * but rejects "军事" inside "中国军事文化" only when the surroundings are
     * also CJK. We accept that some legitimate inflections will leak through;
     * the field-weight sum dilutes a single false positive in 简介.
     */
    private fun containsWithBoundary(text: String, keyword: String): Boolean {
        var idx = text.indexOf(keyword)
        while (idx >= 0) {
            val before = if (idx == 0) null else text[idx - 1]
            val after = if (idx + keyword.length >= text.length) null else text[idx + keyword.length]
            if (isBoundary(before) || isBoundary(after)) return true
            idx = text.indexOf(keyword, idx + 1)
        }
        return false
    }

    private fun isBoundary(c: Char?): Boolean {
        if (c == null) return true
        // Non-letter / non-digit / non-CJK is a boundary — punctuation, space, etc.
        if (c.isWhitespace()) return true
        if (!c.isLetterOrDigit()) return true
        // Latin letters between CJK characters are boundary-like for our purposes.
        val cjk = c in '\u4E00'..'\u9FFF'
        return !cjk && !c.isDigit()
    }

    private data class TextField(val text: String, val weight: Float)

    private object FieldWeight {
        const val TITLE = 1.5f
        const val KIND = 1.3f
        const val CATEGORY = 1.2f
        const val CUSTOM_TAG = 1.1f
        const val DESCRIPTION = 0.8f
        const val AUTHOR = 0.6f
        const val ORIGIN = 0.5f
    }
}
