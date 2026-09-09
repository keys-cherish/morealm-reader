package com.morealm.app.domain.replace

import com.morealm.app.domain.entity.ReplaceRule
import kotlinx.serialization.Serializable

// Import/export wire formats and entity conversions.

/** 参照实现 modern format (matches the imported replace-rule entity
 *  field-for-field as serialized by GSON). Defaults match 参照实现 @ColumnInfo
 *  defaultValue annotations so partial JSONs from the wild still parse. */
@Serializable
internal data class LegacyReplaceRule(
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
        // Stable id derived from 参照实现 numeric id — re-importing the same
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
        // 参照实现 ms is Long; MoRealm timeoutMs is Int. Clamp to a sane range
        // so a stray "timeoutMillisecond": 999999 doesn't overflow Int.
        timeoutMs = timeoutMillisecond.coerceIn(500L, 30_000L).toInt(),
        kind = if (replacement.isBlank()) ReplaceRule.KIND_PURIFY else ReplaceRule.KIND_GENERAL,
        excludeScope = excludeScope,
    )
}

/** Legacy Yuedu / older 参照实现 pre-2020 format. Identifier fields:
 *  `regex` (pattern), `replaceSummary` (name), `useTo` (scope), `enable` (enabled),
 *  `serialNumber` (sortOrder). Same conversion target so the rest of the
 *  pipeline doesn't care which dialect produced the rule. */
@Serializable
internal data class YueduReplaceRule(
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
    val chapterIndex: Int? = null,
    val scopeTitle: Boolean = false,
    val scopeContent: Boolean = true,
    val enabled: Boolean = true,
    val sortOrder: Int = 0,
    val timeoutMs: Int = 3000,
    /** kind 默认 0 (GENERAL)，让旧版本导出的 bundle 在新版本导入时仍能解析。 */
    val kind: Int = ReplaceRule.KIND_GENERAL,
    val excludeScope: String? = null,
)

internal fun ReplaceRule.toExportData(): ReplaceRuleExportData = ReplaceRuleExportData(
    id = id,
    name = name,
    pattern = pattern,
    replacement = replacement,
    isRegex = isRegex,
    scope = scope,
    bookId = bookId,
    chapterIndex = chapterIndex,
    scopeTitle = scopeTitle,
    scopeContent = scopeContent,
    enabled = enabled,
    sortOrder = sortOrder,
    timeoutMs = timeoutMs,
    kind = kind,
    excludeScope = excludeScope,
)

internal fun ReplaceRuleExportData.toEntity(): ReplaceRule = ReplaceRule(
    id = id,
    name = name,
    pattern = pattern,
    replacement = replacement,
    isRegex = isRegex,
    scope = scope,
    bookId = bookId,
    chapterIndex = chapterIndex,
    scopeTitle = scopeTitle,
    scopeContent = scopeContent,
    enabled = enabled,
    sortOrder = sortOrder,
    timeoutMs = timeoutMs,
    kind = kind,
    excludeScope = excludeScope,
)
// endregion
