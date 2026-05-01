package com.morealm.app.domain.repository

import com.morealm.app.core.log.AppLog
import com.morealm.app.domain.entity.Book
import com.morealm.app.domain.entity.BookGroup
import com.morealm.app.domain.entity.TagAssignSource
import com.morealm.app.domain.entity.TagDefinition
import com.morealm.app.domain.entity.TagType
import com.morealm.app.domain.preference.AppPreferences
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns scored tags into auto-created folders (BookGroups).
 *
 * ### Design
 * The user's mental model is **folders, not parallel views**. So instead of
 * surfacing genre tags as a separate "smart shelf" layer, we promote them
 * into real folders the moment they cross a usefulness threshold:
 *
 *   1. A book gets classified → [TagResolver] returns `[ScoredTag(玄幻, 1.5), …]`
 *   2. For the top non-source tag, we ask "does the user already have a folder
 *      tied to this tag?"
 *      - **Yes** (auto or manual): place the book inside that folder.
 *      - **No** but the tag is on the user's ignore list: skip it. The user
 *        deleted the auto-folder once, don't bring it back.
 *      - **No** and ≥ [autoCreateThreshold] books share the tag: create the
 *        folder named after the tag, copy the genre's emoji, and pull the
 *        peer books in too (those marked AUTO; MANUAL ones stay put).
 *      - Otherwise: leave folderId null. Wait for more peers.
 *
 * ### Threshold rationale
 * 3 books = "this genre is real for this user, not a one-off". Below that,
 * an auto-folder is noise. We pick a low number on purpose: a new user with
 * a fresh shelf wants to see folders appear early so they get the *aha*
 * moment. Power users with hundreds of books are already past the threshold
 * for every genre on first import.
 *
 * ### What this does NOT do
 *   - Never touches a [BookGroup] with `auto = false` — those are user-curated.
 *   - Never re-creates a tag the user explicitly deleted (ignore list).
 *   - Doesn't move MANUAL-assigned books — only AUTO peers join the new folder.
 */
@Singleton
class AutoFolderManager @Inject constructor(
    private val groupRepo: BookGroupRepository,
    private val tagRepo: TagRepository,
    private val bookRepo: BookRepository,
    private val prefs: AppPreferences,
) {

    /**
     * @param book        the just-classified book
     * @param scoredTags  output of [TagResolver.resolve]
     * @return folderId to assign to [book], or null if no folder applies
     */
    suspend fun resolveFolder(
        book: Book,
        scoredTags: List<ScoredTag>,
        autoCreateThreshold: Int? = null,
    ): String? {
        val threshold = autoCreateThreshold ?: prefs.getAutoFolderThreshold()
        // Pick the first non-source tag — source tags ("起点" etc.) make poor
        // folders because they mix every genre. They stay as filter chips elsewhere.
        val primaryTag = scoredTags.firstOrNull { !it.tagId.startsWith("source:") }
            ?: return null
        val tagDef = tagRepo.getTag(primaryTag.tagId) ?: return null

        // Skip USER tags here — those map 1:1 to existing user folders that
        // the migration already linked via folderId; nothing to "promote".
        // Only GENRE tags become auto-folders.
        if (tagDef.type != TagType.GENRE) {
            // For USER tags, the tagId == groupId (set up by the v17 migration),
            // so just return it directly. Group already exists.
            return tagDef.id.takeIf { groupRepo.getById(it) != null }
        }

        // 1. Folder already exists for this genre? Use it.
        existingGroupForGenre(tagDef)?.let { return it.id }

        // 2. User ignored this genre? Don't create.
        val ignored = prefs.getAutoFolderIgnored()
        if (tagDef.id in ignored) return null

        // 3. Threshold check — count AUTO-tagged books for this genre across the shelf.
        val peers = tagRepo.getBookIdsByTag(tagDef.id).toMutableSet()
        peers.add(book.id) // include the book that triggered this call
        if (peers.size < threshold) return null

        // 4. Create the auto-folder.
        val groupId = "auto:${tagDef.id}"
        val newGroup = BookGroup(
            id = groupId,
            name = tagDef.name,
            emoji = tagDef.icon,
            sortOrder = nextSortOrder(),
            auto = true,
        )
        groupRepo.insert(newGroup)
        AppLog.info("AutoFolder", "Created auto-folder '${tagDef.name}' for ${peers.size} books")

        // 5. Pull AUTO peers into the new folder. We must avoid touching MANUAL
        // ones (user moved them somewhere on purpose).
        for (peerId in peers) {
            if (peerId == book.id) continue // caller will set folderId on this one
            val peer = bookRepo.getById(peerId) ?: continue
            if (peer.tagsAssignedBy == TagAssignSource.MANUAL) continue
            if (peer.groupLocked) continue
            if (peer.folderId == null) {
                bookRepo.update(peer.copy(folderId = groupId))
            }
        }

        return groupId
    }

    /**
     * Lookup an existing BookGroup whose name matches the genre tag, *or* whose
     * id has the auto-folder shape ("auto:<tagId>"). Either way it's the
     * folder we'd reuse / collide with.
     */
    private suspend fun existingGroupForGenre(tag: TagDefinition): BookGroup? {
        val all = groupRepo.getAllGroupsSync()
        return all.firstOrNull { it.id == "auto:${tag.id}" }
            ?: all.firstOrNull { it.name.equals(tag.name, ignoreCase = true) }
    }

    private suspend fun nextSortOrder(): Int =
        (groupRepo.getAllGroupsSync().maxOfOrNull { it.sortOrder } ?: 0) + 1

    companion object {
        const val AUTO_CREATE_THRESHOLD = 3
    }
}
