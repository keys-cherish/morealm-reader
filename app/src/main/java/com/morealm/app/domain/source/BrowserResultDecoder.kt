package com.morealm.app.domain.source

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

/** WebView returns a JSON string, including Unicode escapes and escaped HTML attributes. */
internal fun decodeBrowserHtml(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    return runCatching {
        (Json.parseToJsonElement(raw) as? JsonPrimitive)?.takeIf { it.isString }?.content
    }.getOrNull()
}
