package com.morealm.app.domain.replace

import com.morealm.app.core.text.ImportTextDecoder
import com.morealm.app.domain.entity.ReplaceRule
import kotlinx.serialization.json.*

/** Platform-independent import/export; URI access, feedback and persistence stay in the caller. */
object ReplaceRuleCodec {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    fun encode(rules: List<ReplaceRule>): String = json.encodeToString(
        MoRealmReplaceRuleBundle.serializer(),
        MoRealmReplaceRuleBundle(rules = rules.map { it.toExportData() }),
    )

    fun decode(bytes: ByteArray): List<ReplaceRule> = decode(ImportTextDecoder.decode(bytes))

    fun decode(text: String): List<ReplaceRule> {
        val parsed = json.parseToJsonElement(text.trim().removePrefix("\uFEFF"))
        if (parsed is JsonObject && (
                parsed["format"]?.jsonPrimitive?.contentOrNull == MoRealmReplaceRuleBundle.FORMAT ||
                    ("format" !in parsed && parsed["rules"] is JsonArray)
            )) {
            return json.decodeFromJsonElement(MoRealmReplaceRuleBundle.serializer(), parsed).rules
                .map { it.toEntity() }.filter { it.isValid() }
        }
        val rows = when (parsed) {
            is JsonArray -> parsed
            is JsonObject -> listOf(parsed)
            else -> emptyList()
        }
        // Detect per row: a bad or mixed-format entry must not discard the rest of a bundle.
        return rows.mapNotNull { row ->
            val item = row as? JsonObject ?: return@mapNotNull null
            runCatching {
                when {
                    "pattern" in item -> json.decodeFromJsonElement(LegadoReplaceRule.serializer(), item).toMoRealm()
                    "regex" in item -> json.decodeFromJsonElement(YueduReplaceRule.serializer(), item).toMoRealm()
                    else -> null
                }
            }.getOrNull()?.takeIf { it.isValid() }
        }
    }
}
