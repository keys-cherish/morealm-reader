package com.morealm.app.domain.tts

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.morealm.app.core.log.AppLog
import com.morealm.app.domain.entity.TtsVoice
import com.morealm.app.domain.http.addExceptionLoggingInterceptor
import com.morealm.app.domain.http.installDispatcherExceptionLogger
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Edge TTS engine — uses Microsoft's speech service via WebSocket.
 * Provides high-quality neural voices (Xiaoxiao, Yunxi, etc.)
 */
class EdgeTtsEngine : TtsEngine {
    override val name = "Edge 语音"
    override val id = "edge"

    private var currentWebSocket: WebSocket? = null
    private var audioTrack: AudioTrack? = null
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addExceptionLoggingInterceptor(TAG)
        .build()
        .apply { installDispatcherExceptionLogger(TAG) }

    companion object {
        // Primary endpoint with latest known working token
        private const val WSS_URL =
            "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1" +
            "?TrustedClientToken=6A5AA1D4EAFF4E9FB37E23D68491D6F4&ConnectionId="
        private const val TAG = "EdgeTTS"

        val VOICES = listOf(
            TtsVoice("zh-CN-XiaoxiaoNeural", "晓晓 (女声·温暖)", "zh-CN", "edge"),
            TtsVoice("zh-CN-YunxiNeural", "云希 (男声·阳光)", "zh-CN", "edge"),
            TtsVoice("zh-CN-YunjianNeural", "云健 (男声·沉稳)", "zh-CN", "edge"),
            TtsVoice("zh-CN-XiaoyiNeural", "晓伊 (女声·活泼)", "zh-CN", "edge"),
            TtsVoice("zh-CN-YunyangNeural", "云扬 (男声·新闻)", "zh-CN", "edge"),
            TtsVoice("zh-CN-XiaochenNeural", "晓辰 (女声·知性)", "zh-CN", "edge"),
            TtsVoice("zh-CN-XiaohanNeural", "晓涵 (女声·温柔)", "zh-CN", "edge"),
            TtsVoice("zh-CN-XiaomengNeural", "晓梦 (女声·甜美)", "zh-CN", "edge"),
            TtsVoice("zh-CN-XiaomoNeural", "晓墨 (女声·文艺)", "zh-CN", "edge"),
            TtsVoice("zh-CN-XiaoruiNeural", "晓睿 (女声·沉稳)", "zh-CN", "edge"),
            TtsVoice("zh-CN-XiaoshuangNeural", "晓双 (女声·童声)", "zh-CN", "edge"),
            TtsVoice("zh-CN-XiaoxuanNeural", "晓萱 (女声·优雅)", "zh-CN", "edge"),
            TtsVoice("zh-CN-XiaozhenNeural", "晓甄 (女声·端庄)", "zh-CN", "edge"),
            TtsVoice("zh-CN-YunfengNeural", "云枫 (男声·磁性)", "zh-CN", "edge"),
            TtsVoice("zh-CN-YunhaoNeural", "云皓 (男声·广告)", "zh-CN", "edge"),
            TtsVoice("zh-CN-YunxiaNeural", "云夏 (男声·少年)", "zh-CN", "edge"),
            TtsVoice("zh-CN-YunzeNeural", "云泽 (男声·纪录片)", "zh-CN", "edge"),
            TtsVoice("zh-TW-HsiaoChenNeural", "曉臻 (女声·台湾)", "zh-TW", "edge"),
            TtsVoice("zh-TW-YunJheNeural", "雲哲 (男声·台湾)", "zh-TW", "edge"),
            TtsVoice("zh-HK-HiuGaaiNeural", "曉佳 (女声·粤语)", "zh-HK", "edge"),
            TtsVoice("zh-HK-WanLungNeural", "雲龍 (男声·粤语)", "zh-HK", "edge"),
        )

        private const val AUDIO_SAMPLE_RATE = 24000
    }

    private var selectedVoice: String = VOICES.first().id

    fun setVoice(voiceName: String) {
        if (voiceName.isNotBlank()) {
            selectedVoice = voiceName
        }
    }

    private fun generateRequestId(): String =
        UUID.randomUUID().toString().replace("-", "")

    private fun formatTimestamp(): String {
        val sdf = SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss 'GMT'Z", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date())
    }

    private fun buildSsml(text: String, speed: Float, pitch: Float = 1.0f): String {
        // Speed: 0.5x → -50%, 1.0x → +0%, 2.0x → +100%, 3.0x → +200%
        val ratePercent = ((speed - 1.0f) * 100).toInt()
        val rateStr = if (ratePercent >= 0) "+${ratePercent}%" else "${ratePercent}%"
        val pitchStr = "+0Hz" // Keep pitch neutral
        val escaped = text
            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&apos;")
        return """<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='zh-CN'>
            <voice name='$selectedVoice'>
                <prosody rate='$rateStr' pitch='$pitchStr'>$escaped</prosody>
            </voice>
        </speak>""".trimIndent()
    }

    override suspend fun speak(text: String, speed: Float): Flow<AudioChunk> = callbackFlow {
        if (text.isBlank()) {
            trySend(AudioChunk(ByteArray(0), "done"))
            close()
            return@callbackFlow
        }

        val requestId = generateRequestId()
        val ssml = buildSsml(text, speed)
        val audioChunks = mutableListOf<ByteArray>()

        // Create AudioTrack for playback
        val bufferSize = AudioTrack.getMinBufferSize(
            AUDIO_SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(AUDIO_SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize.coerceAtLeast(4096))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        audioTrack = track

        val baseUrl = WSS_URL
        val wsUrl = baseUrl + requestId
        val request = Request.Builder()
            .url(wsUrl)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36 Edg/130.0.0.0")
            .header("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
            .build()

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                AppLog.debug(TAG, "WebSocket connected")
                // Send config message
                val configMsg = "Content-Type:application/json; charset=utf-8\r\n" +
                    "Path:speech.config\r\n\r\n" +
                    """{"context":{"synthesis":{"audio":{"metadataoptions":{"sentenceBoundaryEnabled":"false","wordBoundaryEnabled":"false"},"outputFormat":"audio-24khz-48kbitrate-mono-mp3"}}}}"""
                webSocket.send(configMsg)

                // Send SSML
                val ssmlMsg = "X-RequestId:$requestId\r\n" +
                    "Content-Type:application/ssml+xml\r\n" +
                    "X-Timestamp:${formatTimestamp()}\r\n" +
                    "Path:ssml\r\n\r\n$ssml"
                webSocket.send(ssmlMsg)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                // Binary message — contains audio data after header
                try {
                    val data = bytes.toByteArray()
                    // First 2 bytes = header length (big-endian)
                    if (data.size < 2) return
                    val headerLen = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
                    if (data.size > headerLen + 2) {
                        val audioData = data.copyOfRange(headerLen + 2, data.size)
                        audioChunks.add(audioData)
                    }
                } catch (e: Exception) {
                    AppLog.error(TAG, "Error parsing audio data", e)
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                // Text message — check for turn.end
                if (text.contains("Path:turn.end")) {
                    AppLog.debug(TAG, "Synthesis complete, ${audioChunks.size} chunks")
                    // Play all collected audio
                    playAudioChunks(track, audioChunks)
                    trySend(AudioChunk(ByteArray(0), "done"))
                    close()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                AppLog.error(TAG, "WebSocket error (code=${response?.code}): ${t.message}")
                releaseTrack(track)
                close(Exception("Edge TTS error: ${t.message}"))
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                AppLog.debug(TAG, "WebSocket closed: $code $reason")
            }
        }

        trySend(AudioChunk(ByteArray(0), "started"))
        currentWebSocket = client.newWebSocket(request, listener)

        awaitClose {
            currentWebSocket?.cancel()
            currentWebSocket = null
            releaseTrack(track)
        }
    }

    private fun playAudioChunks(track: AudioTrack, chunks: List<ByteArray>) {
        if (chunks.isEmpty()) return
        try {
            track.play()
            // Edge TTS returns MP3 — we need to decode it
            // Use Android's MediaCodec for MP3 → PCM decoding
            val allMp3 = ByteArray(chunks.sumOf { it.size }).also { buf ->
                var offset = 0
                for (chunk in chunks) {
                    chunk.copyInto(buf, offset)
                    offset += chunk.size
                }
            }
            decodeMp3AndPlay(track, allMp3)
        } catch (e: Exception) {
            AppLog.error(TAG, "Playback error", e)
        }
    }

    private fun decodeMp3AndPlay(track: AudioTrack, mp3Data: ByteArray) {
        try {
            val codec = android.media.MediaCodec.createDecoderByType("audio/mpeg")
            val format = android.media.MediaFormat.createAudioFormat("audio/mpeg", AUDIO_SAMPLE_RATE, 1)
            codec.configure(format, null, null, 0)
            codec.start()

            var inputDone = false
            var inputOffset = 0
            val timeoutUs = 10_000L

            while (true) {
                // Feed input
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(timeoutUs)
                    if (inIdx >= 0) {
                        val inBuf = codec.getInputBuffer(inIdx) ?: continue
                        val remaining = mp3Data.size - inputOffset
                        if (remaining <= 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0,
                                android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val size = remaining.coerceAtMost(inBuf.capacity())
                            inBuf.put(mp3Data, inputOffset, size)
                            codec.queueInputBuffer(inIdx, 0, size, 0, 0)
                            inputOffset += size
                        }
                    }
                }

                // Read output
                val info = android.media.MediaCodec.BufferInfo()
                val outIdx = codec.dequeueOutputBuffer(info, timeoutUs)
                if (outIdx >= 0) {
                    val outBuf = codec.getOutputBuffer(outIdx)
                    if (outBuf != null && info.size > 0) {
                        val pcm = ByteArray(info.size)
                        outBuf.get(pcm)
                        track.write(pcm, 0, pcm.size)
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if (info.flags and android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        break
                    }
                } else if (outIdx == android.media.MediaCodec.INFO_TRY_AGAIN_LATER && inputDone) {
                    break
                }
            }

            codec.stop()
            codec.release()

            // Wait for AudioTrack to finish playing
            track.stop()
            track.flush()
        } catch (e: Exception) {
            AppLog.error(TAG, "MP3 decode error", e)
        }
    }

    private fun releaseTrack(track: AudioTrack) {
        try {
            if (track.state == AudioTrack.STATE_INITIALIZED) {
                track.stop()
                track.flush()
            }
            track.release()
        } catch (_: Exception) {}
    }

    override suspend fun getVoices(): List<TtsVoice> = VOICES

    override fun stop() {
        currentWebSocket?.cancel()
        currentWebSocket = null
        try {
            audioTrack?.let { releaseTrack(it) }
            audioTrack = null
        } catch (_: Exception) {}
    }

    override fun isAvailable(): Boolean = true
}
