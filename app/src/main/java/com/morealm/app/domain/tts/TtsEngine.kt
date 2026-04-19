package com.morealm.app.domain.tts

import kotlinx.coroutines.flow.Flow

/** Audio chunk from TTS engine */
data class AudioChunk(
    val data: ByteArray,
    val format: String = "pcm",
)

data class TtsVoice(
    val id: String,
    val name: String,
    val language: String,
    val engine: String,
)

/**
 * TTS engine abstraction — supports system TTS, Edge TTS, and custom APIs.
 */
interface TtsEngine {
    val name: String
    val id: String
    suspend fun speak(text: String, speed: Float = 1.0f): Flow<AudioChunk>
    suspend fun getVoices(): List<TtsVoice>
    fun stop()
    fun isAvailable(): Boolean
}
