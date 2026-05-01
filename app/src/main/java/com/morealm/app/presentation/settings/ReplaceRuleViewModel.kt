package com.morealm.app.presentation.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.morealm.app.core.log.AppLog
import com.morealm.app.domain.entity.ReplaceRule
import com.morealm.app.domain.repository.ReplaceRuleRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class ReplaceRuleViewModel @Inject constructor(
    private val replaceRuleRepo: ReplaceRuleRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    /** Lenient parser:
     *  - `ignoreUnknownKeys` so future Legado fields don't break import.
     *  - `coerceInputValues` so a `null` for a non-null field becomes its default
     *    instead of crashing (Legado occasionally emits `"group": null`).
     *  - `isLenient` to tolerate trailing commas users hand-edit in. */
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    val allRules: StateFlow<List<ReplaceRule>> = replaceRuleRepo.getAllRules()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun saveRule(
        existingId: String?,
        name: String,
        pattern: String,
        replacement: String,
        isRegex: Boolean,
        scope: String,
    ) {
        val rule = ReplaceRule(
            id = existingId ?: UUID.randomUUID().toString(),
            name = name,
            pattern = pattern,
            replacement = replacement,
            isRegex = isRegex,
            scope = scope,
            sortOrder = allRules.value.size,
        )
        viewModelScope.launch(Dispatchers.IO) {
            replaceRuleRepo.insert(rule)
        }
    }

    fun toggleRule(rule: ReplaceRule) {
        viewModelScope.launch(Dispatchers.IO) {
            replaceRuleRepo.insert(rule.copy(enabled = !rule.enabled))
        }
    }

    fun deleteRule(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            replaceRuleRepo.deleteById(id)
        }
    }

    /**
     * Export every replace rule (enabled + disabled) into [outputUri] as a
     * `morealm-replace` bundle. The envelope mirrors the theme bundle pattern
     * (see `MoRealmThemeBundle`) — explicit `format` discriminator + `version`
     * field so we don't have to sniff field names on import.
     *
     * Includes disabled rules deliberately: users often disable rules
     * temporarily but still want them backed up. Filtering would silently
     * lose them on round-trip.
     */
    fun exportRules(outputUri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val rules = replaceRuleRepo.getAllSync()
                if (rules.isEmpty()) {
                    AppLog.info("ReplaceRule", "No rules to export")
                    return@launch
                }
                val bundle = MoRealmReplaceRuleBundle(
                    rules = rules.map { it.toExportData() }
                )
                val jsonStr = json.encodeToString(MoRealmReplaceRuleBundle.serializer(), bundle)
                context.contentResolver.openOutputStream(outputUri)?.use { out ->
                    out.write(jsonStr.toByteArray(Charsets.UTF_8))
                }
                AppLog.info("ReplaceRule", "Exported ${rules.size} rule(s)")
            } catch (e: Exception) {
                AppLog.error("ReplaceRule", "Export failed", e)
            }
        }
    }

    /**
     * Import replace rules from [uri]. Format detection priority:
     *
     *   1. JsonObject with `format == "morealm-replace"` → MoRealm bundle
     *   2. JsonArray + first element has `pattern` field → Legado new array
     *   3. JsonArray + first element has `regex` field → legacy Yuedu array
     *   4. JsonObject with `pattern` field → Legado new single
     *   5. JsonObject with `regex` field → legacy Yuedu single
     *
     * Rule-level validation (`ReplaceRule.isValid`) runs after the conversion
     * so a malformed rule in a 200-rule Legado dump doesn't tank the whole
     * import — it's just skipped with an INFO log. Import is "additive": it
     * does NOT clear existing rules. Same id → upsert (Legado id `1760546083765`
     * deterministically becomes MoRealm id `"legado_1760546083765"` so re-imports
     * overwrite instead of duplicate).
     */
    fun importRulesFromUri(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val text = context.contentResolver.openInputStream(uri)
                    ?.bufferedReader()?.readText()?.trim()
                    ?: return@launch
                val imported = parseRules(text)
                if (imported.isEmpty()) {
                    AppLog.info("ReplaceRule", "No valid rules found in import")
                    return@launch
                }
                imported.forEachIndexed { i, rule ->
                    // 维持原 sortOrder（如果有）；否则在末尾追加。
                    val withOrder = if (rule.sortOrder == 0)
                        rule.copy(sortOrder = allRules.value.size + i)
                    else rule
                    replaceRuleRepo.insert(withOrder)
                }
                AppLog.info("ReplaceRule", "Imported ${imported.size} rule(s)")
            } catch (e: Exception) {
                AppLog.error("ReplaceRule", "Import failed", e)
            }
        }
    }

    /**
     * Parse `text` into MoRealm rules without touching the DB. Public for
     * testability — every supported source format funnels through here so a
     * unit test can pin behavior on Legado/Yuedu sample payloads without
     * round-tripping a Uri.
     */
    fun parseRules(text: String): List<ReplaceRule> {
        val parsed = runCatching { json.parseToJsonElement(text) }.getOrNull()
            ?: return emptyList()

        // Path 1: MoRealm bundle envelope.
        if (parsed is JsonObject &&
            parsed["format"]?.jsonPrimitive?.contentOrNull == MoRealmReplaceRuleBundle.FORMAT
        ) {
            val bundle = json.decodeFromString(MoRealmReplaceRuleBundle.serializer(), text)
            return bundle.rules.mapNotNull { it.toEntity().takeIf { r -> r.isValid() } }
        }

        // Helper to detect which Legado dialect a JsonObject uses.
        fun JsonObject.dialect(): SourceDialect = when {
            containsKey("pattern") || containsKey("isEnabled") || containsKey("timeoutMillisecond") -> SourceDialect.LEGADO_NEW
            containsKey("regex") || containsKey("replaceSummary") -> SourceDialect.YUEDU_OLD
            else -> SourceDialect.UNKNOWN
        }

        return when (parsed) {
            is JsonArray -> {
                val first = parsed.firstOrNull() as? JsonObject
                when (first?.dialect()) {
                    SourceDialect.LEGADO_NEW ->
                        json.decodeFromString<List<LegadoReplaceRule>>(text)
                            .map { it.toMoRealm() }
                    SourceDialect.YUEDU_OLD ->
                        json.decodeFromString<List<YueduReplaceRule>>(text)
                            .map { it.toMoRealm() }
                    else -> emptyList()
                }.filter { it.isValid() }
            }
            is JsonObject -> when (parsed.dialect()) {
                SourceDialect.LEGADO_NEW ->
                    listOf(json.decodeFromString<LegadoReplaceRule>(text).toMoRealm())
                SourceDialect.YUEDU_OLD ->
                    listOf(json.decodeFromString<YueduReplaceRule>(text).toMoRealm())
                else -> emptyList()
            }.filter { it.isValid() }
            else -> emptyList()
        }
    }

    private enum class SourceDialect { LEGADO_NEW, YUEDU_OLD, UNKNOWN }
}

// region — DTO + conversion. File-private to ReplaceRuleViewModel; promote to
//          a separate file only if more than one call site needs them.

/** Legado modern format (matches `io.legado.app.data.entities.ReplaceRule`
 *  field-for-field as serialized by GSON). Defaults match Legado's @ColumnInfo
 *  defaultValue annotations so partial JSONs from the wild still parse. */
@Serializable
private data class LegadoReplaceRule(
    val id: Long = 0L,
    val name: String = "",
    val group: String? = null,
    val pattern: String = "",
    val replacement: String = "",
    val scope: String? = null,
    val scopeTitle: Boolean = false,
    val scopeContent: Boolean = true,
    val excludeScope: String? = null,
    val isEnabled: Boolean = true,
    val isRegex: Boolean = true,
    val timeoutMillisecond: Long = 3000L,
    val order: Int = 0,
) {
    fun toMoRealm(): ReplaceRule = ReplaceRule(
        // Stable id derived from Legado's numeric id — re-importing the same
        // rule from an updated dump upserts instead of stacking duplicates.
        // Falls back to a hash of the pattern when id is missing/zero.
        id = if (id != 0L) "legado_$id" else "legado_p${pattern.hashCode()}",
        name = name,
        pattern = pattern,
        replacement = replacement,
        isRegex = isRegex,
        scope = scope.orEmpty(),
        bookId = null,
        scopeTitle = scopeTitle,
        scopeContent = scopeContent,
        enabled = isEnabled,
        sortOrder = order,
        // Legado ms is Long; MoRealm timeoutMs is Int. Clamp to a sane range
        // so a stray "timeoutMillisecond": 999999 doesn't overflow Int.
        timeoutMs = timeoutMillisecond.coerceIn(500L, 30_000L).toInt(),
        // Note: `group` and `excludeScope` are dropped — MoRealm has neither
        // field today. Document this in the user-facing help text rather than
        // pretending to support them.
    )
}

/** Legacy Yuedu / older Legado pre-2020 format. Identifier fields:
 *  `regex` (pattern), `replaceSummary` (name), `useTo` (scope), `enable` (enabled),
 *  `serialNumber` (sortOrder). Same conversion target so the rest of the
 *  pipeline doesn't care which dialect produced the rule. */
@Serializable
private data class YueduReplaceRule(
    val id: Long = 0L,
    val regex: String = "",
    val replaceSummary: String = "",
    val replacement: String = "",
    val isRegex: Boolean = true,
    val useTo: String? = null,
    val enable: Boolean = true,
    val serialNumber: Int = 0,
) {
    fun toMoRealm(): ReplaceRule = ReplaceRule(
        id = if (id != 0L) "yuedu_$id" else "yuedu_p${regex.hashCode()}",
        name = replaceSummary,
        pattern = regex,
        replacement = replacement,
        isRegex = isRegex,
        scope = useTo.orEmpty(),
        bookId = null,
        scopeTitle = false,
        scopeContent = true,
        enabled = enable,
        sortOrder = serialNumber,
        timeoutMs = 3000,
    )
}

/** MoRealm's own export envelope. `format` is the load-bearing discriminator
 *  for the import side — never change its value without a migration. */
@Serializable
data class MoRealmReplaceRuleBundle(
    val format: String = FORMAT,
    val version: Int = 1,
    val rules: List<ReplaceRuleExportData> = emptyList(),
) {
    companion object { const val FORMAT = "morealm-replace" }
}

@Serializable
data class ReplaceRuleExportData(
    val id: String,
    val name: String = "",
    val pattern: String,
    val replacement: String = "",
    val isRegex: Boolean = false,
    val scope: String = "",
    val bookId: String? = null,
    val scopeTitle: Boolean = false,
    val scopeContent: Boolean = true,
    val enabled: Boolean = true,
    val sortOrder: Int = 0,
    val timeoutMs: Int = 3000,
)

private fun ReplaceRule.toExportData(): ReplaceRuleExportData = ReplaceRuleExportData(
    id = id,
    name = name,
    pattern = pattern,
    replacement = replacement,
    isRegex = isRegex,
    scope = scope,
    bookId = bookId,
    scopeTitle = scopeTitle,
    scopeContent = scopeContent,
    enabled = enabled,
    sortOrder = sortOrder,
    timeoutMs = timeoutMs,
)

private fun ReplaceRuleExportData.toEntity(): ReplaceRule = ReplaceRule(
    id = id,
    name = name,
    pattern = pattern,
    replacement = replacement,
    isRegex = isRegex,
    scope = scope,
    bookId = bookId,
    scopeTitle = scopeTitle,
    scopeContent = scopeContent,
    enabled = enabled,
    sortOrder = sortOrder,
    timeoutMs = timeoutMs,
)
// endregion
