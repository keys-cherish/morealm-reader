package com.morealm.app.domain.repository

import com.morealm.app.domain.entity.Book
import com.morealm.app.domain.entity.BookGroup
import com.morealm.app.domain.entity.TagAssignSource
import com.morealm.app.domain.preference.AppPreferences
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
 * 入口分两路：
 *   - [classify] / [matchGroup]  ：被动路径（导入、加书架、详情更新）使用。
 *     查 [AppPreferences.autoFolderEnabled]，开关关时直接返回原 folderId / null，
 *     不做任何分类。
 *   - [classifyForced] / [matchGroupForced]：用户主动「立即整理」使用。
 *     绕过开关，但仍尊重阈值、ignored 集合、book.groupLocked 等其他守门。
 *
 * Manual placements (用户拖拽 / [Book.groupLocked]) 在任何路径里都被尊重。
 */
@Singleton
class AutoGroupClassifier @Inject constructor(
    private val tagResolver: TagResolver,
    private val tagRepo: TagRepository,
    private val autoFolders: AutoFolderManager,
    private val prefs: AppPreferences,
) {
    /**
     * Returns the folderId for [book], possibly creating an auto-folder along
     * the way. Always (re)writes AUTO tag assignments so chip filters reflect
     * the latest metadata — that step is independent of folder placement.
     *
     * 检查 [AppPreferences.autoFolderEnabled] —— 关闭时返回 [book.folderId]，
     * 不动 folder，也不写 AUTO tag。用户的 MANUAL 标签 / folder 保留不变。
     */
    suspend fun classify(book: Book): String? {
        if (!prefs.getAutoFolderEnabled()) return book.folderId
        return classifyInternal(book)
    }

    /**
     * 强制分类入口 —— 用户主动「立即整理」使用，绕过 [AppPreferences.autoFolderEnabled]。
     * 其他守门（groupLocked / MANUAL / threshold / ignored）仍生效。
     */
    suspend fun classifyForced(book: Book): String? = classifyInternal(book)

    private suspend fun classifyInternal(book: Book): String? {
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
     *
     * 同样受 [AppPreferences.autoFolderEnabled] 守门；想绕开关请用 [matchGroupForced]。
     */
    suspend fun matchGroup(book: Book, groups: List<BookGroup>): BookGroup? {
        if (!prefs.getAutoFolderEnabled()) return null
        return matchGroupInternal(book, groups)
    }

    /** 用户主动操作时绕过开关的入口。 */
    suspend fun matchGroupForced(book: Book, groups: List<BookGroup>): BookGroup? =
        matchGroupInternal(book, groups)

    private suspend fun matchGroupInternal(book: Book, groups: List<BookGroup>): BookGroup? {
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
