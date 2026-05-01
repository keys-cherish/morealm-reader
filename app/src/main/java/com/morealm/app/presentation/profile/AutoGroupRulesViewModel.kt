package com.morealm.app.presentation.profile

import android.content.Context
import android.content.Intent
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
import java.io.File
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

    /**
     * Build a JSON snapshot of all current rules. Pretty-printed because users
     * often paste this into chat / git, and tabular JSON is much more legible
     * than a single-line blob.
     */
    private suspend fun buildSnapshot(): RuleSnapshot {
        return RuleSnapshot(
            version = 1,
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
                val snapshot = buildSnapshot()
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

    private val prettyJson = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }
}

@Serializable
data class RuleSnapshot(
    val version: Int,
    val exportedAt: Long,
    val threshold: Int,
    val ignoredTags: List<String>,
    val tags: List<TagSnapshot>,
)

@Serializable
data class TagSnapshot(
    val id: String,
    val name: String,
    val type: String,
    val keywords: String,
    val icon: String?,
    val builtin: Boolean,
) {
    companion object {
        fun from(tag: TagDefinition) = TagSnapshot(
            id = tag.id,
            name = tag.name,
            type = tag.type,
            keywords = tag.keywords,
            icon = tag.icon,
            builtin = tag.builtin,
        )
    }
}
