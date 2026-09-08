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
import java.util.UUID
import kotlinx.coroutines.flow.receiveAsFlow
import com.morealm.app.domain.replace.ReplaceRuleCodec
import javax.inject.Inject

@HiltViewModel
class ReplaceRuleViewModel @Inject constructor(
    private val replaceRuleRepo: ReplaceRuleRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val notifications = kotlinx.coroutines.channels.Channel<String>(kotlinx.coroutines.channels.Channel.BUFFERED)
    val messages = notifications.receiveAsFlow()

    val allRules: StateFlow<List<ReplaceRule>> = replaceRuleRepo.getAllRules()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun saveRule(
        existingId: String?,
        name: String,
        pattern: String,
        replacement: String,
        isRegex: Boolean,
        scope: String,
        kind: Int = ReplaceRule.KIND_GENERAL,
    ) {
        // 编辑现有规则时，如果调用方没显式传 kind（默认 GENERAL），
        // 不要静默把"净化"改成"替换" —— 找到旧 row 把它的 kind 透传过来。
        val resolvedKind = if (existingId != null && kind == ReplaceRule.KIND_GENERAL) {
            allRules.value.firstOrNull { it.id == existingId }?.kind ?: kind
        } else kind
        val rule = ReplaceRule(
            id = existingId ?: UUID.randomUUID().toString(),
            name = name,
            pattern = pattern,
            replacement = replacement,
            isRegex = isRegex,
            scope = scope,
            sortOrder = allRules.value.size,
            kind = resolvedKind,
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
                val jsonStr = ReplaceRuleCodec.encode(rules)
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
     *   2. JsonArray + first element has `pattern` field → 参照实现 new array
     *   3. JsonArray + first element has `regex` field → legacy Yuedu array
     *   4. JsonObject with `pattern` field → 参照实现 new single
     *   5. JsonObject with `regex` field → legacy Yuedu single
     *
     * Rule-level validation (`ReplaceRule.isValid`) runs after the conversion
     * so a malformed rule in a 200-rule 参照实现 dump doesn't tank the whole
     * import — it's just skipped with an INFO log. Import is "additive": it
     * does NOT clear existing rules. Same id → upsert (参照实现 id `1760546083765`
     * deterministically becomes MoRealm id `"legado_1760546083765"` so re-imports
     * overwrite instead of duplicate).
     */
    fun importRulesFromUri(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: error("无法读取规则文件")
                val imported = ReplaceRuleCodec.decode(bytes)
                if (imported.isEmpty()) {
                    notifications.send("文件中没有有效规则，请检查格式")
                    return@launch
                }
                imported.forEachIndexed { i, rule ->
                    // 维持原 sortOrder（如果有）；否则在末尾追加。
                    val withOrder = if (rule.sortOrder == 0)
                        rule.copy(sortOrder = allRules.value.size + i)
                    else rule
                    replaceRuleRepo.insert(withOrder)
                }
                notifications.send("已导入 ${imported.size} 条规则")
                AppLog.info("ReplaceRule", "Imported ${imported.size} rule(s)")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                notifications.send("导入失败：${e.message ?: "文件编码或格式不正确"}")
                AppLog.error("ReplaceRule", "Import failed", e)
            }
        }
    }

    fun parseRules(text: String): List<ReplaceRule> = ReplaceRuleCodec.decode(text)
}
