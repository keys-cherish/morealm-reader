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
        // 优先取非 source 的 GENRE/USER 标签作主分组依据。
        // 若 web 书因书源解析失败导致 kind/category/description 全空，GENRE
        // 一个也没命中 —— 此时只剩 source 标签。开启 allowSourceFallback 后
        // 让 source 升级为兜底文件夹（id 形如 auto:source:起点中文），
        // 否则按原逻辑返回 null（书停留在根目录由「未分组」虚拟夹收纳）。
        val primaryTag = scoredTags.firstOrNull { !it.tagId.startsWith("source:") }
            ?: scoredTags.firstOrNull { it.tagId.startsWith("source:") && prefs.getAllowSourceFallback() }
            ?: return null
        val tagDef = tagRepo.getTag(primaryTag.tagId) ?: return null

        // ── 阈值守门：最高优先级 ──
        //
        // 这是 v1.2 bug 修复 —— 之前的实现先检查「已有同 id 的文件夹？有就归过去」，
        // 再做阈值。结果只要文件夹被任何方式创建过一次（手建 / migration / 之前
        // 在低阈值时自动建），之后哪怕只有 1 本同 tag 书也会被归入，阈值形同虚设。
        //
        // 现在：先算 peers 数量（同 tag 的现有 AUTO 标记书 + 当前书），不达阈值
        // 任何路径都 return null。SOURCE 标签历史豁免（effectiveThreshold=1）保留，
        // 因为「来自同一书源就自动聚」是无歧义的用户预期，不像 GENRE 那种"3 本才像兴趣"。
        val effectiveThreshold = if (tagDef.type == TagType.SOURCE) 1 else threshold
        val peers = tagRepo.getBookIdsByTag(tagDef.id).toMutableSet()
        peers.add(book.id) // include the book that triggered this call
        if (peers.size < effectiveThreshold) return null

        // USER tags 直接走 v17 迁移建好的同 id 分组（不需要 promotion）。
        // GENRE / SOURCE 都走下方 promotion 路径。USER 在通过阈值前不归。
        if (tagDef.type == TagType.USER) {
            return tagDef.id.takeIf { groupRepo.getById(it) != null }
        }

        // 已经存在的 auto:tagId 文件夹（含同名手建）直接复用。
        existingGroupForGenre(tagDef)?.let { return it.id }

        // User ignored this genre? Don't create.
        val ignored = prefs.getAutoFolderIgnored()
        if (tagDef.id in ignored) return null

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
