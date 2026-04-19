package com.morealm.app.domain.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * HTTP TTS engine configuration — compatible with Legado httpTTS format.
 *
 * URL template supports:
 * - {{speakText}} — the text to speak
 * - {{speakSpeed}} — speed multiplier (0.5-2.0)
 * - {{speakVoice}} — voice name
 *
 * Response should be audio data (MP3/WAV/PCM).
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
)
