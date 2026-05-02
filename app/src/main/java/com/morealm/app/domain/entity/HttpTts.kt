package com.morealm.app.domain.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * HTTP TTS engine configuration — compatible with Legado httpTTS format.
 *
 * URL template supports:
 * - {{speakText}} — the text to speak
 * - {{speakSpeed}} — speed multiplier (0.5-2.0)
 * - {{speakVoice}} — voice name
 * - {{encode}}    — URLEncoder.encode(speakText)（Legado 兼容）
 *
 * Response should be audio data (MP3/WAV/PCM).
 *
 * v26 新增字段（与 Legado [io.legado.app.data.entities.HttpTTS] 对齐）：
 * - [loginUrl] / [loginUi]：可选登录 URL 与 LoginUi JSON，用于鉴权类源
 * - [loginCheckJs]：响应预检 JS（鉴权失败时可触发重试登录）
 * - [concurrentRate]：并发限速字符串，形如 "1/1000"，由 ConcurrentRateLimiter 解析
 *
 * 旧库升级到 v26 后，这四列对所有现存 row 默认为 null，对原有功能完全无副作用。
 */
@Serializable
@Entity(tableName = "http_tts")
data class HttpTts(
    @PrimaryKey val id: Long = System.currentTimeMillis(),
    val name: String = "",
    val url: String = "",
    val contentType: String? = null,
    val header: String? = null,
    val enabled: Boolean = true,
    val lastUpdateTime: Long = System.currentTimeMillis(),
    val loginUrl: String? = null,
    val loginUi: String? = null,
    val loginCheckJs: String? = null,
    val concurrentRate: String? = null,
)

private val httpTtsHeaderJson by lazy {
    Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }
}

/**
 * 解析 [HttpTts.header] —— Legado 兼容格式：JSON 对象 `{ "K": "V" }`。
 * 解析失败 / null / 空串都返回 null，调用方按需 fallback。
 */
fun HttpTts.parseHeaderMap(): Map<String, String>? {
    val raw = header?.takeIf { it.isNotBlank() } ?: return null
    return runCatching {
        httpTtsHeaderJson.decodeFromString<Map<String, String>>(raw)
    }.getOrNull()
}
