package com.morealm.app.presentation.profile

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.morealm.app.core.log.AppLog
import com.morealm.app.domain.entity.TagDefinition
import com.morealm.app.domain.entity.TagType
import com.morealm.app.domain.preference.AppPreferences
import com.morealm.app.domain.repository.TagRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.util.UUID
import javax.inject.Inject

/**
 * Drives the "自动分组规则" screen — three jobs:
 *
 *   1. **Show & edit tags**: list every GENRE / USER tag, let the user tweak
 *      the comma-separated keywords or rename their own tags. Built-in tag
 *      names stay locked (otherwise upgrades that ship a new built-in 玄幻
 *      vocab would clash with whatever the user renamed it to).
 *
 *   2. **Threshold control**: how many books with the same genre before we
 *      auto-promote the tag into a folder. 3 is a friendly default; anything
 *      below 2 is silly (you'd get folders for one-off books) and above 10
 *      means folders never appear for casual genres.
 *
 *   3. **Export & share**: serialize the rule set as JSON and hand it to
 *      Android's share sheet. Users can save the JSON for backup, or send
 *      it to a friend so their shelves auto-organise the same way.
 */
@HiltViewModel
class AutoGroupRulesViewModel @Inject constructor(
    private val tagRepo: TagRepository,
    private val prefs: AppPreferences,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val genreTags = tagRepo.observeTagsByType(TagType.GENRE)
    private val userTags = tagRepo.observeTagsByType(TagType.USER)

    /** Combined list ordered: GENRE first, then USER (matches their visual prominence). */
    val tags: StateFlow<List<TagDefinition>> = combine(genreTags, userTags) { g, u ->
        g.sortedBy { it.sortOrder } + u.sortedBy { it.sortOrder }
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val threshold: StateFlow<Int> = prefs.autoFolderThreshold
        .stateIn(viewModelScope, SharingStarted.Eagerly, 3)

    val ignored: StateFlow<Set<String>> = prefs.autoFolderIgnored
        .stateIn(viewModelScope, SharingStarted.Lazily, emptySet())

    private val _exportToast = MutableStateFlow<String?>(null)
    val exportToast: StateFlow<String?> = _exportToast.asStateFlow()
    fun consumeToast() { _exportToast.value = null }

    fun setThreshold(value: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            prefs.setAutoFolderThreshold(value)
        }
    }

    /**
     * Update a tag's keywords. Built-in tags remain editable here on purpose —
     * users *should* be able to teach 玄幻 about new sub-genres they care about.
     */
    fun updateKeywords(tag: TagDefinition, keywords: String) {
        viewModelScope.launch(Dispatchers.IO) {
            tagRepo.upsertTag(tag.copy(keywords = keywords))
        }
    }

    /** Renames USER tags only — built-in tag names are part of upgrade compat. */
    fun renameTag(tag: TagDefinition, newName: String) {
        if (tag.builtin) return
        viewModelScope.launch(Dispatchers.IO) {
            tagRepo.upsertTag(tag.copy(name = newName.trim().ifBlank { tag.name }))
        }
    }

    fun unignoreTag(tagId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            prefs.removeAutoFolderIgnored(tagId)
        }
    }

    // ── USER tag CRUD ─────────────────────────────────────────────────────
    //
    // GENRE / SOURCE / FORMAT / STATUS / SYSTEM tags are seeded or derived;
    // USER tags are the only category the app exposes for "create your own".
    // Built-in flag is forced to false so future seeders never collide with
    // user IDs (we prefix with `user:` for the same reason).

    /**
     * Create a new USER tag.  [name] is the only required input — emoji /
     * color / keywords default to empty so the user can flesh the tag out
     * later via the existing keyword editor.
     *
     * Idempotent against name collisions: if a USER tag with the same name
     * already exists we return it instead of inserting a duplicate. This
     * matches user mental model — "I already have a tag called 修真" is a
     * harmless no-op, not a confusing extra row.
     */
    fun createUserTag(
        name: String,
        emoji: String? = null,
        color: String? = null,
        keywords: String = "",
    ) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) {
            _exportToast.value = "标签名不能为空"
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val existing = tagRepo.getAllTags()
                .firstOrNull { it.type == TagType.USER && it.name.equals(trimmed, ignoreCase = true) }
            if (existing != null) {
                _exportToast.value = "标签 \"$trimmed\" 已存在"
                return@launch
            }
            val id = "user:${UUID.randomUUID().toString().take(8)}"
            val tag = TagDefinition(
                id = id,
                name = trimmed,
                type = TagType.USER,
                keywords = keywords.trim(),
                color = color?.takeIf { it.isNotBlank() },
                icon = emoji?.takeIf { it.isNotBlank() },
                builtin = false,
                sortOrder = (tagRepo.getAllTags()
                    .filter { it.type == TagType.USER }
                    .maxOfOrNull { it.sortOrder } ?: 0) + 1,
            )
            tagRepo.upsertTag(tag)
            _exportToast.value = "已创建标签 \"$trimmed\""
        }
    }

    /**
     * Delete a USER tag.  Built-in tags can't be removed — they ship with the
     * app and are part of the upgrade contract.  Cascade deletes book_tag
     * rows (handled inside [TagRepository.deleteUserTag]) so chip filters
     * don't dangle after the tag is gone.
     */
    fun deleteUserTag(tag: TagDefinition) {
        if (tag.builtin || tag.type != TagType.USER) {
            _exportToast.value = "内置标签不可删除"
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            tagRepo.deleteUserTag(tag.id)
            _exportToast.value = "已删除标签 \"${tag.name}\""
        }
    }

    /**
     * Build a JSON snapshot of all current rules. Pretty-printed because users
     * often paste this into chat / git, and tabular JSON is much more legible
     * than a single-line blob.
     *
     * v2 envelope:
     *   - `format` discriminator so an import path can refuse non-rule JSON
     *     (e.g. someone tries to import a replace-rule bundle here by mistake).
     *   - `metadata` for community sharing — rule-pack name, author handle,
     *     description, app version.  All optional; `null` for anonymous local
     *     backups.
     *   - `version: 2` while keeping the v1 fields untouched, so older clients
     *     reading a v2 file still find threshold / ignoredTags / tags exactly
     *     where they used to be.
     */
    private suspend fun buildSnapshot(metadata: SnapshotMetadata? = null): RuleSnapshot {
        return RuleSnapshot(
            format = RuleSnapshot.FORMAT,
            version = RuleSnapshot.CURRENT_VERSION,
            metadata = metadata,
            exportedAt = System.currentTimeMillis(),
            threshold = prefs.getAutoFolderThreshold(),
            ignoredTags = prefs.getAutoFolderIgnored().toList().sorted(),
            tags = tagRepo.getAllTags()
                .filter { it.type == TagType.GENRE || it.type == TagType.USER }
                .map(TagSnapshot::from),
        )
    }

    /**
     * Export rules to a temp file under app cache, then fire the system share
     * intent. We use FileProvider to avoid leaking real paths over the IPC
     * boundary — most chat apps refuse content:// uris from foreign packages
     * unless the FileProvider authority is declared.
     */
    fun exportAndShare() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val snapshot = buildSnapshot(metadata = autoMetadata())
                val json = prettyJson.encodeToString(snapshot)
                val dir = File(context.cacheDir, "exports").apply { mkdirs() }
                val file = File(dir, "morealm-auto-group-rules.json")
                file.writeText(json)
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file,
                )
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/json"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, "MoRealm 自动分组规则")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val chooser = Intent.createChooser(shareIntent, "分享规则").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(chooser)
                _exportToast.value = "已生成规则 JSON"
            } catch (e: Exception) {
                AppLog.error("AutoGroupRules", "Export failed: ${e.message}", e)
                _exportToast.value = "导出失败: ${e.message?.take(60)}"
            }
        }
    }

    /**
     * Auto-fill metadata for "anonymous" exports — captures app version + tag
     * count so the import dialog on the receiving end can show "20 个标签 ·
     * 来自 MoRealm 1.0alpha2".  Users who want a named pack should go through
     * a future "导出为规则集" flow that takes name/author/description; for
     * now [exportAndShare] is the personal-backup path and stays anonymous.
     */
    private suspend fun autoMetadata(): SnapshotMetadata? {
        val packageInfo = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0)
        }.getOrNull()
        val tagCount = tagRepo.getAllTags()
            .count { it.type == TagType.GENRE || it.type == TagType.USER }
        return SnapshotMetadata(
            tagCount = tagCount,
            appVersion = packageInfo?.versionName,
        )
    }

    // ── Import pipeline ───────────────────────────────────────────────────
    //
    // Two-step UX:
    //   1. importPreview(uri)  → reads + parses + sets _pendingImport.
    //      The screen reacts by showing the merge-options dialog with
    //      metadata + tag count visible BEFORE anything touches DB.
    //   2. importApply(options) → user confirmed; merge into tagRepo / prefs
    //      under the chosen strategy.
    //
    // Splitting preview from apply matters because import is destructive
    // when "覆盖同 id" is on — users must see what's about to land first.

    private val _pendingImport = MutableStateFlow<PendingImport?>(null)
    val pendingImport: StateFlow<PendingImport?> = _pendingImport.asStateFlow()

    fun cancelPendingImport() { _pendingImport.value = null }

    /**
     * Read [uri], parse JSON, normalise v1→v2 in memory, and stash the result
     * for the dialog to consume.  Surfaces failure as a snackbar — never
     * throws back to the UI thread.
     */
    fun importPreview(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val text = context.contentResolver.openInputStream(uri)
                    ?.bufferedReader()?.readText()?.trim()
                    ?: run {
                        _exportToast.value = "无法读取文件"
                        return@launch
                    }
                val snapshot = parseSnapshot(text) ?: run {
                    _exportToast.value = "JSON 不是 MoRealm 规则文件"
                    return@launch
                }
                _pendingImport.value = PendingImport(uri = uri, snapshot = snapshot, raw = text)
            } catch (e: Exception) {
                AppLog.error("AutoGroupRules", "Import preview failed", e)
                _exportToast.value = "导入失败: ${e.message?.take(60)}"
            }
        }
    }

    /**
     * Pure parser exposed for unit tests.  Accepts both v1 (no `format` field,
     * no `metadata`) and v2 envelopes; v1 inputs are normalised to v2 shape
     * with `format = FORMAT, version = 1, metadata = null`.  Returns null on:
     *
     *  - unparseable JSON
     *  - JSON whose `format` field is set but doesn't match [RuleSnapshot.FORMAT]
     *    (we refuse rather than risk treating a replace-rule bundle as tags)
     *  - JSON that decodes but lacks both `tags` and `threshold` (clearly not
     *    our format even if it's missing the discriminator)
     */
    fun parseSnapshot(text: String): RuleSnapshot? {
        val element = runCatching { prettyJson.parseToJsonElement(text) }.getOrNull() ?: return null
        if (element !is JsonObject) return null
        // Reject foreign envelopes early.  An *absent* format is OK (v1) — only
        // a *present and wrong* format is a hard rejection.
        val declaredFormat = (element["format"] as? JsonPrimitive)?.contentOrNullSafe()
        if (declaredFormat != null && declaredFormat != RuleSnapshot.FORMAT) return null
        return runCatching { prettyJson.decodeFromString(RuleSnapshot.serializer(), text) }
            .getOrNull()
            ?.takeIf { it.tags.isNotEmpty() || it.ignoredTags.isNotEmpty() || it.threshold > 0 }
    }

    private fun JsonPrimitive.contentOrNullSafe(): String? =
        if (isString) content else null

    /**
     * Apply the previously-previewed import using [options].  No-op if there's
     * nothing pending (defensive — the dialog only enables "确认" when preview
     * succeeded, but a stale callback could still arrive).
     */
    fun importApply(options: MergeOptions) {
        val pending = _pendingImport.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val (tagsAdded, tagsMerged, tagsOverwritten) = applyTags(pending.snapshot, options)
                if (options.syncThreshold) {
                    prefs.setAutoFolderThreshold(pending.snapshot.threshold)
                }
                if (options.syncIgnoredTags) {
                    // Replace strategy: import is the source of truth for the
                    // ignored set when the user opted in.  Anything not in the
                    // imported list gets un-ignored, which matches what the
                    // user expects from "同步忽略列表".
                    val current = prefs.getAutoFolderIgnored()
                    val incoming = pending.snapshot.ignoredTags.toSet()
                    (current - incoming).forEach { prefs.removeAutoFolderIgnored(it) }
                    (incoming - current).forEach { prefs.addAutoFolderIgnored(it) }
                }
                _pendingImport.value = null
                _exportToast.value = buildString {
                    append("导入完成：")
                    if (tagsAdded > 0) append("$tagsAdded 个新增")
                    if (tagsMerged > 0) {
                        if (length > 5) append("，")
                        append("$tagsMerged 个合并")
                    }
                    if (tagsOverwritten > 0) {
                        if (length > 5) append("，")
                        append("$tagsOverwritten 个覆盖")
                    }
                    if (tagsAdded == 0 && tagsMerged == 0 && tagsOverwritten == 0) {
                        append("没有变化")
                    }
                }
            } catch (e: Exception) {
                AppLog.error("AutoGroupRules", "Import apply failed", e)
                _exportToast.value = "导入失败: ${e.message?.take(60)}"
            }
        }
    }

    /**
     * Apply tag-level merge logic.  Returns a triple of (added, merged,
     * overwritten) counts purely so the caller can build a meaningful toast.
     *
     * Conflict resolution per [MergeOptions.tagStrategy]:
     *  - **MERGE_KEYWORDS (default)**: keep existing tag's name/icon/color/
     *    builtin flag, but union the keyword sets so the user's edits and the
     *    incoming pack's edits both take effect.  This is the friendliest
     *    default for community sharing — no data is lost on either side.
     *  - **OVERWRITE**: incoming wins entirely.  Useful when the user wants
     *    to "reset to a known-good pack".
     *  - **APPEND_NEW_ONLY**: skip every conflict; only insert tags whose id
     *    is unknown locally.  Useful for cautious users who want to try a
     *    pack without disturbing their tuning.
     *
     * Built-in tag protection: regardless of strategy, we never flip an
     * existing builtin=true row to builtin=false (that would mean the next
     * seeder run could re-create it as a duplicate).  We DO let an incoming
     * pack tweak keywords on a builtin tag — same as the manual editor.
     */
    private suspend fun applyTags(
        snapshot: RuleSnapshot,
        options: MergeOptions,
    ): Triple<Int, Int, Int> {
        val existing = tagRepo.getAllTags().associateBy { it.id }
        var added = 0
        var merged = 0
        var overwritten = 0
        for (incomingSnap in snapshot.tags) {
            val incoming = incomingSnap.toEntity()
            val current = existing[incoming.id]
            if (current == null) {
                tagRepo.upsertTag(incoming.copy(builtin = false))
                added++
                continue
            }
            when (options.tagStrategy) {
                TagMergeStrategy.APPEND_NEW_ONLY -> {
                    // Skip — caller asked us not to disturb existing rows.
                }
                TagMergeStrategy.OVERWRITE -> {
                    tagRepo.upsertTag(
                        incoming.copy(
                            // Never demote a builtin to user-deletable; preserves seeder identity.
                            builtin = current.builtin || incoming.builtin,
                        )
                    )
                    overwritten++
                }
                TagMergeStrategy.MERGE_KEYWORDS -> {
                    val mergedKeywords = mergeKeywords(current.keywords, incoming.keywords)
                    if (mergedKeywords != current.keywords) {
                        tagRepo.upsertTag(current.copy(keywords = mergedKeywords))
                        merged++
                    }
                }
            }
        }
        return Triple(added, merged, overwritten)
    }

    /**
     * Union two comma-separated keyword lists, deduplicated case-sensitively
     * (a Chinese user might intentionally have both "玄幻" and "玄幻派").
     * Preserves source order: existing keywords come first, new ones appended,
     * so reading the field still tells the user "here's what I had, plus what
     * the import added".
     */
    private fun mergeKeywords(a: String, b: String): String {
        val splitChars = charArrayOf(',', '，', ';', '；', '\n', '|', '/', '、', ' ')
        fun parse(s: String) = s.split(*splitChars).map { it.trim() }.filter { it.isNotBlank() }
        val seen = LinkedHashSet<String>()
        seen += parse(a)
        seen += parse(b)
        return seen.joinToString(",")
    }

    private val prettyJson = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
        // v1 → v2: legacy snapshots omit `format` / `metadata`; treating
        // missing-but-non-null-defaulted fields as their default keeps those
        // imports passing instead of crashing on `format = null`.
        coerceInputValues = true
        isLenient = true
    }
}

/**
 * In-flight import state.  Held in the ViewModel after [importPreview] reads
 * the file and before the user confirms the merge dialog.  Stays around so
 * the user can cancel without losing what they had selected.
 *
 * `raw` is kept for diagnostic logging — when an apply step fails we can dump
 * the snippet that caused it without re-reading the Uri (which may already
 * be revoked by SAF).
 */
data class PendingImport(
    val uri: Uri,
    val snapshot: RuleSnapshot,
    val raw: String,
)

/**
 * What the user picked in the merge dialog.  Defaults match the screen's
 * default checkbox state — `tagStrategy = MERGE_KEYWORDS` is the friendliest
 * shared-pack outcome (no data lost on either side).  `syncThreshold` and
 * `syncIgnoredTags` default to false because they're "global state" that a
 * cautious user will want to opt into rather than have silently overwritten.
 */
data class MergeOptions(
    val tagStrategy: TagMergeStrategy = TagMergeStrategy.MERGE_KEYWORDS,
    val syncThreshold: Boolean = false,
    val syncIgnoredTags: Boolean = false,
)

enum class TagMergeStrategy { MERGE_KEYWORDS, OVERWRITE, APPEND_NEW_ONLY }

/**
 * Auto-group rule snapshot envelope.
 *
 * **Backward / forward compatibility contract** (read this before changing fields):
 *  - `format` is the load-bearing discriminator. Never rename without a migration —
 *    the import side rejects anything that doesn't match [FORMAT].
 *  - `version` bumps when *meaning* changes, not just when fields are added.
 *    A v1 file omits `format`/`metadata` but keeps the same `threshold` /
 *    `ignoredTags` / `tags` shape, so `coerceInputValues + ignoreUnknownKeys`
 *    handles it transparently. Importing v1 produces a normalised v2 in memory.
 *  - Adding optional fields with sane defaults is a v2.x bump (no version
 *    change). Removing or repurposing a field is a v3 bump and must add a
 *    migration branch in the importer.
 *
 * `metadata` is null for "anonymous" local backups; community packs should
 * fill it in so the import dialog can show name + author before merging.
 */
@Serializable
data class RuleSnapshot(
    val format: String = FORMAT,
    val version: Int = CURRENT_VERSION,
    val metadata: SnapshotMetadata? = null,
    val exportedAt: Long,
    val threshold: Int,
    val ignoredTags: List<String>,
    val tags: List<TagSnapshot>,
) {
    companion object {
        /** Discriminator written into every export; the importer refuses anything else. */
        const val FORMAT = "morealm-auto-group-rules"
        /** Bump when the on-disk *meaning* of fields changes; pure additions don't bump. */
        const val CURRENT_VERSION = 2
    }
}

/**
 * Optional human-readable metadata for community sharing. All fields are
 * optional — a bare `SnapshotMetadata()` is a valid "untitled" pack.
 *
 * `appVersion` lets the importer warn when a pack was authored on a much
 * newer client (probable schema mismatch) without forcing us to maintain a
 * full version range table.
 */
@Serializable
data class SnapshotMetadata(
    val name: String? = null,
    val author: String? = null,
    val description: String? = null,
    val tagCount: Int? = null,
    val appVersion: String? = null,
)

@Serializable
data class TagSnapshot(
    val id: String,
    val name: String,
    val type: String,
    val keywords: String,
    val icon: String?,
    /** Hex color "#RRGGBB" — v2 added; null for legacy v1 imports. */
    val color: String? = null,
    val builtin: Boolean,
) {
    companion object {
        fun from(tag: TagDefinition) = TagSnapshot(
            id = tag.id,
            name = tag.name,
            type = tag.type,
            keywords = tag.keywords,
            icon = tag.icon,
            color = tag.color,
            builtin = tag.builtin,
        )
    }

    /** Reverse mapping for the importer. Preserves builtin flag from the source pack. */
    fun toEntity(): TagDefinition = TagDefinition(
        id = id,
        name = name,
        type = type,
        keywords = keywords,
        icon = icon,
        color = color,
        builtin = builtin,
    )
}
