package com.morealm.app.domain.tts

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import com.morealm.app.core.log.AppLog
import com.morealm.app.domain.entity.HttpTts
import com.morealm.app.domain.entity.TtsVoice
import com.morealm.app.domain.http.addExceptionLoggingInterceptor
import com.morealm.app.domain.http.installDispatcherExceptionLogger
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * HTTP TTS engine — sends text to a user-configured HTTP API and plays the audio response.
 * Compatible with Legado's httpTTS format.
 */
class HttpTtsEngine(private var config: HttpTts) : TtsEngine {
    override val name get() = config.name
    override val id get() = "http_${config.id}"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addExceptionLoggingInterceptor("HttpTTS")
        .build()
        .apply { installDispatcherExceptionLogger("HttpTTS") }

    private var audioTrack: AudioTrack? = null

    fun updateConfig(newConfig: HttpTts) { config = newConfig }

    override suspend fun speak(text: String, speed: Float): Flow<AudioChunk> = callbackFlow {
        if (text.isBlank()) {
            trySend(AudioChunk(ByteArray(0), "done"))
            close()
            return@callbackFlow
        }

        trySend(AudioChunk(ByteArray(0), "started"))

        try {
            val url = config.url
                .replace("{{speakText}}", URLEncoder.encode(text, "UTF-8"))
                .replace("{{speakSpeed}}", speed.toString())
                .replace("{{encode}}", URLEncoder.encode(text, "UTF-8"))
                .replace("\n.*".toRegex(), "")

            val reqBuilder = Request.Builder().url(url)
            // Apply custom headers
            config.header?.let { headerJson ->
                try {
                    val headers = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                        .decodeFromString<Map<String, String>>(headerJson)
                    headers.forEach { (k, v) -> reqBuilder.header(k, v) }
                } catch (_: Exception) {}
            }

            val response = client.newCall(reqBuilder.build()).execute()
            val audioBytes = response.body?.bytes()
            if (audioBytes != null && audioBytes.isNotEmpty()) {
                val contentType = config.contentType ?: response.header("Content-Type") ?: ""
                playAudio(audioBytes, contentType)
            }
        } catch (e: Exception) {
            AppLog.error("HttpTTS", "Speak failed: ${e.message}")
        }

        trySend(AudioChunk(ByteArray(0), "done"))
        close()

        awaitClose { releaseAudio() }
    }

    private fun releaseAudio() {
        try {
            audioTrack?.stop()
            audioTrack?.release()
            audioTrack = null
        } catch (_: Exception) {}
    }

    private suspend fun playAudio(data: ByteArray, contentType: String) {
        try {
            val mimeType = when {
                contentType.contains("mp3") || contentType.contains("mpeg") -> "audio/mpeg"
                contentType.contains("wav") -> "audio/raw"
                contentType.contains("ogg") -> "audio/ogg"
                contentType.contains("pcm") -> "audio/raw"
                else -> "audio/mpeg"
            }

            if (mimeType == "audio/raw") {
                // Raw PCM — play directly
                val track = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setAudioFormat(AudioFormat.Builder()
                        .setSampleRate(16000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setBufferSizeInBytes(data.size)
                    .setTransferMode(AudioTrack.MODE_STATIC).build()
                audioTrack = track
                track.write(data, 0, data.size)
                track.play()
                // Wait for playback
                val durationMs = (data.size.toLong() * 1000) / (16000 * 2)
                kotlinx.coroutines.delay(durationMs)
                track.stop()
                return
            }

            // MP3/OGG — decode with MediaCodec
            val codec = MediaCodec.createDecoderByType(mimeType)
            val format = MediaFormat.createAudioFormat(mimeType, 24000, 1)
            codec.configure(format, null, null, 0)
            codec.start()

            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder()
                    .setSampleRate(24000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(AudioTrack.getMinBufferSize(24000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(4096))
                .setTransferMode(AudioTrack.MODE_STREAM).build()
            audioTrack = track
            track.play()

            var inputOffset = 0
            var inputDone = false
            val timeoutUs = 10_000L

            while (true) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(timeoutUs)
                    if (inIdx >= 0) {
                        val inBuf = codec.getInputBuffer(inIdx) ?: continue
                        val remaining = data.size - inputOffset
                        if (remaining <= 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val size = remaining.coerceAtMost(inBuf.capacity())
                            inBuf.put(data, inputOffset, size)
                            codec.queueInputBuffer(inIdx, 0, size, 0, 0)
                            inputOffset += size
                        }
                    }
                }
                val info = MediaCodec.BufferInfo()
                val outIdx = codec.dequeueOutputBuffer(info, timeoutUs)
                if (outIdx >= 0) {
                    val outBuf = codec.getOutputBuffer(outIdx)
                    if (outBuf != null && info.size > 0) {
                        val pcm = ByteArray(info.size)
                        outBuf.get(pcm)
                        track.write(pcm, 0, pcm.size)
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                } else if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER && inputDone) break
            }

            codec.stop(); codec.release()
            track.stop(); track.flush()
        } catch (e: Exception) {
            AppLog.error("HttpTTS", "Audio playback failed: ${e.message}")
        }
    }

    override suspend fun getVoices(): List<TtsVoice> =
        listOf(TtsVoice(config.name, config.name, "custom", "http"))

    override fun stop() { releaseAudio() }

    override fun isAvailable(): Boolean = config.url.isNotBlank()
}
