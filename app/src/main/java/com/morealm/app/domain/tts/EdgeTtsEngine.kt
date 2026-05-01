package com.morealm.app.domain.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import com.morealm.app.core.log.AppLog
import com.morealm.app.domain.entity.TtsVoice
import com.morealm.app.domain.http.addExceptionLoggingInterceptor
import com.morealm.app.domain.http.installDispatcherExceptionLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Edge TTS 引擎 —— 直连微软 Edge 浏览器内置的 read aloud 接口（不需要 API key）。
 *
 * # 与老版本的差异（2026 升级）
 * - 流式播放：边收边解码边播，首字延迟从 1~3s 降到 ~200ms。
 * - Sec-MS-GEC 鉴权：补齐微软 2024 年起强制的时间戳 token，握手不再偶发被拒。
 * - UA 升级到 Chrome 143：与 rany2/edge-tts 对齐。
 * - MP3 缓存：重听不走网络。
 * - 远程音色列表：从硬编码 21 条扩展到 600+，按 locale 分组。
 * - 时钟偏移自校正：401/403 时取服务端 Date 头反推偏移并重试一次。
 *
 * # 接口契约（保持兼容）
 * [speak] 返回的 `Flow<AudioChunk>` 由 [com.morealm.app.service.TtsEngineHost] 的
 * `runStreamingPlayback` 通过 `.collect { /* drain */ }` 消费 —— host 不解析 chunk
 * 内容，只是用 collect 阻塞到一段播完。所以我们必须在播放真正结束（AudioTrack
 * 写完、buffer 排空）后再 close flow，host 才会推进到下一段。
 *
 * # 日志
 * tag `EdgeTTS`（已注册到 [com.morealm.app.core.log.LogTagCatalog]）。
 */
class EdgeTtsEngine(
    private val context: Context,
) : TtsEngine {
    override val name = "Edge 语音"
    override val id = "edge"

    private val cache: EdgeTtsCache = EdgeTtsCache(context.applicationContext)

    /** 当前活跃的 WebSocket，[stop] 调用时取消。AtomicReference 防并发 cancel race。 */
    private val currentWebSocket = AtomicReference<WebSocket?>(null)

    /** 当前活跃的 AudioTrack，[stop] 时释放。 */
    private val currentAudioTrack = AtomicReference<AudioTrack?>(null)

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .addExceptionLoggingInterceptor(TAG)
        .build()
        .apply { installDispatcherExceptionLogger(TAG) }

    private var selectedVoice: String = HARDCODED_VOICES.first().id

    fun setVoice(voiceName: String) {
        selectedVoice = if (voiceName.isBlank()) {
            HARDCODED_VOICES.first().id
        } else {
            // 注意：用户可能选了远程拉来的非硬编码音色，所以不再做 firstOrNull 过滤。
            voiceName
        }
    }

    // ── 公开接口 ──────────────────────────────────────────────────────────

    override suspend fun speak(text: String, speed: Float): Flow<AudioChunk> = callbackFlow {
        if (text.isBlank()) {
            trySend(AudioChunk(ByteArray(0), "done"))
            close()
            return@callbackFlow
        }

        val rateStr = formatRate(speed)
        val pitchStr = "+0Hz"
        val cacheKey = cache.keyFor(selectedVoice, rateStr, pitchStr, text)
        val started = System.currentTimeMillis()

        trySend(AudioChunk(ByteArray(0), "started"))

        // ── 1. 缓存命中：直接喂解码器，零网络 ────────────────────────────
        val cachedFile = cache.get(cacheKey)
        if (cachedFile != null) {
            AppLog.debug(TAG, "cache hit: key=${cacheKey.take(8)}… size=${cachedFile.length()}")
            try {
                val mp3Bytes = cachedFile.readBytes()
                playFromMp3Bytes(mp3Bytes)
                AppLog.debug(TAG, "cache playback done in ${System.currentTimeMillis() - started}ms")
                trySend(AudioChunk(ByteArray(0), "done"))
                close()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.warn(TAG, "cache playback failed, fallback to network: ${e.message}")
                runWebSocketPlayback(text, rateStr, pitchStr, cacheKey, started)
            }
            awaitClose { cleanup() }
            return@callbackFlow
        }

        // ── 2. 缓存未命中：走 WSS 流式播放 ──────────────────────────────
        try {
            runWebSocketPlayback(text, rateStr, pitchStr, cacheKey, started)
            trySend(AudioChunk(ByteArray(0), "done"))
            close()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.warn(TAG, "WSS playback failed: ${e.message}")
            close(e)
        }

        awaitClose { cleanup() }
    }

    override suspend fun getVoices(): List<TtsVoice> = HARDCODED_VOICES

    /**
     * 远程拉取最新音色列表。失败回退到硬编码 [HARDCODED_VOICES]。
     * 结果带 24h TTL 缓存到 `edge_tts/voices.json`。
     *
     * 由 [com.morealm.app.service.TtsEngineHost.loadVoicesForEngine] 在切到 edge
     * 引擎时调用 —— 同步耗时通常 < 1s，且失败有回退，可放心走前台请求。
     */
    suspend fun fetchRemoteVoices(): List<TtsVoice> = withContext(Dispatchers.IO) {
        val voicesCacheFile = File(context.applicationContext.cacheDir, "edge_tts/voices.json")
        val now = System.currentTimeMillis()
        val ttlMs = 24L * 3600L * 1000L

        // 缓存命中：直接复用
        if (voicesCacheFile.exists() && now - voicesCacheFile.lastModified() < ttlMs) {
            try {
                val parsed = parseVoicesJson(voicesCacheFile.readText())
                if (parsed.isNotEmpty()) {
                    AppLog.debug(TAG, "voices: cache hit (${parsed.size} entries)")
                    return@withContext parsed
                }
            } catch (e: Exception) {
                AppLog.debug(TAG, "voices cache parse failed: ${e.message}, refetching")
            }
        }

        // 远程拉取
        val token = EdgeTtsAuth.generateSecMsGec()
        val url = "https://speech.platform.bing.com/consumer/speech/synthesize/readaloud" +
            "/voices/list?trustedclienttoken=${EdgeTtsAuth.trustedClientToken}" +
            "&Sec-MS-GEC=$token&Sec-MS-GEC-Version=${EdgeTtsAuth.SEC_MS_GEC_VERSION}"
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", EdgeTtsAuth.userAgent)
            .header("Accept", "application/json")
            .header("Origin", EdgeTtsAuth.ORIGIN)
            .build()

        val parsed = try {
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    AppLog.warn(TAG, "voices fetch HTTP ${resp.code}, fallback")
                    return@use emptyList<TtsVoice>()
                }
                val body = resp.body?.string().orEmpty()
                val list = parseVoicesJson(body)
                // 写缓存（即使 list 为空也跳过写，下次还会重试）
                if (list.isNotEmpty()) {
                    runCatching {
                        voicesCacheFile.parentFile?.mkdirs()
                        voicesCacheFile.writeText(body)
                    }
                }
                list
            }
        } catch (e: Exception) {
            AppLog.warn(TAG, "voices fetch failed: ${e.message}, fallback to hardcoded")
            emptyList()
        }

        if (parsed.isEmpty()) HARDCODED_VOICES else parsed
    }

    override fun stop() {
        cleanup()
    }

    override fun isAvailable(): Boolean = true

    /**
     * 删除远程音色缓存文件，强制下次 [fetchRemoteVoices] 走网络重拉。
     * 用户在"听书"页点"刷新音色列表"时调用，处理"调了系统时区/网络/换地区后远程列表本应变化"
     * 但因为 24h TTL 还没过被卡在旧数据的场景。
     */
    fun invalidateRemoteVoicesCache() {
        val voicesCacheFile = File(context.applicationContext.cacheDir, "edge_tts/voices.json")
        if (voicesCacheFile.exists()) {
            val deleted = voicesCacheFile.delete()
            AppLog.info(TAG, "invalidateRemoteVoicesCache: deleted=$deleted")
        } else {
            AppLog.debug(TAG, "invalidateRemoteVoicesCache: no cache file present")
        }
    }

    // ── WSS 流式播放主循环 ────────────────────────────────────────────────

    /**
     * 单次 speak 的 WSS 播放，含一次失败重试（针对 GEC 鉴权过期 / 时钟偏移）。
     */
    private suspend fun runWebSocketPlayback(
        text: String,
        rateStr: String,
        pitchStr: String,
        cacheKey: String,
        startedAt: Long,
    ) {
        var lastError: Throwable? = null
        for (attempt in 1..MAX_ATTEMPTS) {
            try {
                doSynthesize(text, rateStr, pitchStr, cacheKey, startedAt, attempt)
                return // 成功
            } catch (e: CancellationException) {
                throw e
            } catch (e: AuthFailedException) {
                // 服务端拒了：调时钟偏移，下一轮重生 token
                lastError = e
                EdgeTtsAuth.adjustClockSkew(e.serverDate)
                AppLog.info(
                    TAG,
                    "auth failed (attempt $attempt), adjusted clock skew, retrying...",
                )
                if (attempt == MAX_ATTEMPTS) break
            } catch (e: Exception) {
                lastError = e
                if (attempt == MAX_ATTEMPTS) break
                AppLog.warn(TAG, "synthesize failed (attempt $attempt): ${e.message}, retrying")
            }
        }
        throw lastError ?: RuntimeException("Edge TTS failed after $MAX_ATTEMPTS attempts")
    }

    /**
     * 一次合成的全部协程编排：
     *
     * ```
     * ┌─ WS listener (OkHttp 工作线程) ─┐
     * │   onMessage(bytes) → channel + buffer
     * │   onMessage("turn.end") → channel.close()
     * │   onFailure → completion.completeException()
     * └────────────────────────────────┘
     *                  │ chunks
     *                  ▼
     *      decoder 协程 (Dispatchers.IO)
     *        MediaCodec edge-fed
     *        AudioTrack PCM write
     *        完成 → completion.complete(buffer)
     * ```
     */
    private suspend fun doSynthesize(
        text: String,
        rateStr: String,
        pitchStr: String,
        cacheKey: String,
        startedAt: Long,
        attempt: Int,
    ) = coroutineScope {
        val requestId = generateRequestId()
        val ssml = buildSsml(text, selectedVoice, rateStr, pitchStr)

        // chunk 流：WS 推、decoder 拉
        val chunkChannel = Channel<ByteArray>(Channel.UNLIMITED)
        // 音频字节累积到缓冲，turn.end 后写缓存
        val mp3Buffer = java.io.ByteArrayOutputStream()
        // WS 完成（成功 / 失败）的信号；decoder 完成由 channel 关闭驱动
        val wsCompletion = CompletableDeferred<Unit>()
        // 第一个音频字节到达的时间戳，用于打首字延迟日志。
        // OkHttp WS listener 回调全部在同一个 worker 线程，不存在竞态，普通 Long 即可。
        val firstByteAt = java.util.concurrent.atomic.AtomicLong(0L)

        // ── AudioTrack 准备 ──
        val track = createAudioTrack()
        currentAudioTrack.set(track)

        // ── decoder 协程：从 channel 流式喂 MediaCodec → AudioTrack ──
        val decoderJob = launch(Dispatchers.IO) {
            try {
                streamingDecode(chunkChannel, track)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.warn(TAG, "decoder error: ${e.message}")
            }
        }

        // ── WSS 连接 ──
        val wsUrl = buildWssUrl(requestId)
        val request = Request.Builder()
            .url(wsUrl)
            .header("User-Agent", EdgeTtsAuth.userAgent)
            .header("Origin", EdgeTtsAuth.ORIGIN)
            .header("Pragma", "no-cache")
            .header("Cache-Control", "no-cache")
            .build()

        val ws = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                AppLog.debug(TAG, "WSS opened (attempt $attempt)")
                // 1. config message
                val config = "Content-Type:application/json; charset=utf-8\r\n" +
                    "Path:speech.config\r\n\r\n" +
                    """{"context":{"synthesis":{"audio":{""" +
                    """"metadataoptions":{"sentenceBoundaryEnabled":"false",""" +
                    """"wordBoundaryEnabled":"false"},""" +
                    """"outputFormat":"audio-24khz-48kbitrate-mono-mp3"}}}}"""
                webSocket.send(config)
                // 2. SSML
                val ssmlMsg = "X-RequestId:$requestId\r\n" +
                    "Content-Type:application/ssml+xml\r\n" +
                    "X-Timestamp:${rfc1123Now()}Z\r\n" +
                    "Path:ssml\r\n\r\n$ssml"
                webSocket.send(ssmlMsg)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                try {
                    val data = bytes.toByteArray()
                    if (data.size < 2) return
                    // 头长度 = big-endian 前 2 字节
                    val headerLen = ((data[0].toInt() and 0xFF) shl 8) or
                        (data[1].toInt() and 0xFF)
                    if (data.size <= headerLen + 2) return
                    val audio = data.copyOfRange(headerLen + 2, data.size)
                    if (firstByteAt.get() == 0L) {
                        firstByteAt.set(System.currentTimeMillis())
                        AppLog.info(
                            TAG,
                            "first audio byte at +${firstByteAt.get() - startedAt}ms",
                        )
                    }
                    // 同时入 channel（喂 codec）和 buffer（用于落缓存）
                    chunkChannel.trySend(audio)
                    synchronized(mp3Buffer) { mp3Buffer.write(audio) }
                } catch (e: Exception) {
                    AppLog.warn(TAG, "onMessage(bytes) parse error", e)
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.contains("Path:turn.end")) {
                    AppLog.debug(TAG, "turn.end received, closing channel")
                    chunkChannel.close()
                    if (!wsCompletion.isCompleted) wsCompletion.complete(Unit)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val code = response?.code ?: -1
                val serverDate = response?.headers?.get("Date")
                AppLog.warn(TAG, "WSS failure (code=$code): ${t.message}")
                chunkChannel.close(t)
                if (!wsCompletion.isCompleted) {
                    if (code == 401 || code == 403) {
                        wsCompletion.completeExceptionally(
                            AuthFailedException(code, serverDate, t)
                        )
                    } else {
                        wsCompletion.completeExceptionally(t)
                    }
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                AppLog.debug(TAG, "WSS closed: $code $reason")
                chunkChannel.close()
                if (!wsCompletion.isCompleted) wsCompletion.complete(Unit)
            }
        })
        currentWebSocket.set(ws)

        try {
            // 等 WSS 拿完所有音频
            wsCompletion.await()
            // 等 decoder 把 channel 里剩余 chunk + EOS 处理完，再 release track
            decoderJob.join()
            // 写缓存（即使是空的也不写）
            val finalBytes = synchronized(mp3Buffer) { mp3Buffer.toByteArray() }
            if (finalBytes.isNotEmpty()) {
                cache.put(cacheKey, finalBytes)
                AppLog.debug(
                    TAG,
                    "cached ${finalBytes.size} bytes, total=${System.currentTimeMillis() - startedAt}ms",
                )
            }
        } finally {
            // 即使被取消也要清干净 WS / track
            ws.cancel()
            currentWebSocket.compareAndSet(ws, null)
            releaseTrack(track)
            currentAudioTrack.compareAndSet(track, null)
        }
    }

    // ── 流式解码：从 channel 拉 MP3 → MediaCodec → AudioTrack ──────────────

    /**
     * 边收边解码边播。
     *
     * 关键点：
     * - inputBuffer 从 channel.receive() 取一段就喂；channel 关闭 + buffer 耗尽 → EOS
     * - outputBuffer 拿到 PCM 立刻 write 到 AudioTrack（不等所有解码完）
     * - 用 10ms 短超时轮询 dequeue，避免输入饥饿时长阻塞
     */
    private suspend fun streamingDecode(
        chunkChannel: Channel<ByteArray>,
        track: AudioTrack,
    ) {
        val codec = MediaCodec.createDecoderByType("audio/mpeg")
        val format = MediaFormat.createAudioFormat("audio/mpeg", AUDIO_SAMPLE_RATE, 1)
        codec.configure(format, null, null, 0)
        codec.start()

        var trackStarted = false
        var inputDone = false
        var outputDone = false
        val info = MediaCodec.BufferInfo()
        val timeoutUs = 10_000L

        // 当前持有的 chunk 字节，喂完一段才向 channel 取下一段
        var pendingChunk: ByteArray? = null
        var pendingOffset = 0

        try {
            while (!outputDone) {
                // ── 喂输入 ──
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(timeoutUs)
                    if (inIdx >= 0) {
                        // 没有 pending 就尝试从 channel 拿一个新的
                        if (pendingChunk == null) {
                            val r = chunkChannel.tryReceive()
                            when {
                                r.isSuccess -> {
                                    pendingChunk = r.getOrNull()
                                    pendingOffset = 0
                                }
                                r.isClosed -> {
                                    // channel 关闭且空 → 喂 EOS
                                    codec.queueInputBuffer(
                                        inIdx, 0, 0, 0,
                                        MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                                    )
                                    inputDone = true
                                }
                                else -> {
                                    // channel 还开着但暂时没数据 → 阻塞等一段（释放 inputBuffer）
                                    // 直接 suspend receive，等到拿到再下一轮喂
                                    val next = try {
                                        chunkChannel.receive()
                                    } catch (_: kotlinx.coroutines.channels.ClosedReceiveChannelException) {
                                        codec.queueInputBuffer(
                                            inIdx, 0, 0, 0,
                                            MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                                        )
                                        inputDone = true
                                        null
                                    }
                                    if (next != null) {
                                        pendingChunk = next
                                        pendingOffset = 0
                                    }
                                }
                            }
                        }
                        // 喂当前 pending
                        if (!inputDone && pendingChunk != null) {
                            val inBuf = codec.getInputBuffer(inIdx)
                            if (inBuf != null) {
                                val cap = inBuf.capacity()
                                val remaining = pendingChunk!!.size - pendingOffset
                                val size = minOf(remaining, cap)
                                inBuf.clear()
                                inBuf.put(pendingChunk!!, pendingOffset, size)
                                codec.queueInputBuffer(inIdx, 0, size, 0, 0)
                                pendingOffset += size
                                if (pendingOffset >= pendingChunk!!.size) {
                                    pendingChunk = null
                                    pendingOffset = 0
                                }
                            } else {
                                // input buffer 拿不到，回收 idx 进入下一轮
                                codec.queueInputBuffer(inIdx, 0, 0, 0, 0)
                            }
                        }
                    }
                }

                // ── 拉输出 ──
                val outIdx = codec.dequeueOutputBuffer(info, timeoutUs)
                if (outIdx >= 0) {
                    val outBuf = codec.getOutputBuffer(outIdx)
                    if (outBuf != null && info.size > 0) {
                        if (!trackStarted) {
                            track.play()
                            trackStarted = true
                        }
                        val pcm = ByteArray(info.size)
                        outBuf.get(pcm)
                        // 阻塞 write —— 满 buffer 时会等 AudioTrack 消化
                        track.write(pcm, 0, pcm.size)
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputDone = true
                    }
                }
                // INFO_TRY_AGAIN_LATER / INFO_OUTPUT_FORMAT_CHANGED 自然落入下一轮
            }
        } finally {
            try { codec.stop() } catch (_: Exception) {}
            codec.release()
            // 让 AudioTrack 把缓冲里剩余的 PCM 播完再返回
            try {
                if (trackStarted) {
                    track.stop()
                }
            } catch (_: Exception) {}
        }
    }

    // ── 缓存命中路径：一次性解码已有 MP3 字节 ──────────────────────────────

    private suspend fun playFromMp3Bytes(mp3Bytes: ByteArray) = withContext(Dispatchers.IO) {
        val track = createAudioTrack()
        currentAudioTrack.set(track)
        val chunkChannel = Channel<ByteArray>(Channel.UNLIMITED)
        chunkChannel.trySend(mp3Bytes)
        chunkChannel.close()
        try {
            streamingDecode(chunkChannel, track)
        } finally {
            releaseTrack(track)
            currentAudioTrack.compareAndSet(track, null)
        }
    }

    // ── 资源清理 ───────────────────────────────────────────────────────────

    private fun cleanup() {
        currentWebSocket.getAndSet(null)?.cancel()
        currentAudioTrack.getAndSet(null)?.let { releaseTrack(it) }
    }

    private fun releaseTrack(track: AudioTrack) {
        try {
            if (track.state == AudioTrack.STATE_INITIALIZED) {
                runCatching { track.stop() }
                runCatching { track.flush() }
            }
            track.release()
        } catch (_: Exception) {}
    }

    private fun createAudioTrack(): AudioTrack {
        val bufferSize = AudioTrack.getMinBufferSize(
            AUDIO_SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        return AudioTrack.Builder()
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
            .setBufferSizeInBytes(bufferSize.coerceAtLeast(8192))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    // ── URL / SSML 构造 ────────────────────────────────────────────────────

    private fun buildWssUrl(requestId: String): String {
        val token = EdgeTtsAuth.generateSecMsGec()
        // Sec-MS-GEC 放 query string —— 与 rany2/edge-tts 一致，且兼容 OkHttp WS 握手
        return "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud" +
            "/edge/v1?TrustedClientToken=${EdgeTtsAuth.trustedClientToken}" +
            "&Sec-MS-GEC=$token" +
            "&Sec-MS-GEC-Version=${EdgeTtsAuth.SEC_MS_GEC_VERSION}" +
            "&ConnectionId=$requestId"
    }

    private fun buildSsml(text: String, voice: String, rate: String, pitch: String): String {
        val escaped = text
            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&apos;")
        return "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' " +
            "xml:lang='zh-CN'>" +
            "<voice name='$voice'>" +
            "<prosody rate='$rate' pitch='$pitch'>$escaped</prosody>" +
            "</voice>" +
            "</speak>"
    }

    private fun formatRate(speed: Float): String {
        val pct = ((speed - 1.0f) * 100).toInt()
        return if (pct >= 0) "+${pct}%" else "${pct}%"
    }

    private fun generateRequestId(): String =
        UUID.randomUUID().toString().replace("-", "")

    private fun rfc1123Now(): String {
        val sdf = SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss 'GMT'Z", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date())
    }

    // ── 远程音色解析 ──────────────────────────────────────────────────────

    /**
     * 解析 voices/list 接口返回的 JSON。每个元素形如：
     * ```
     * {"Name":"...","ShortName":"zh-CN-XiaoxiaoNeural","Gender":"Female",
     *  "Locale":"zh-CN","FriendlyName":"...","Status":"GA",
     *  "VoiceTag":{...}}
     * ```
     * 返回排序后的 [TtsVoice] 列表（zh-* 优先，然后按 locale 字母序）。
     */
    private fun parseVoicesJson(json: String): List<TtsVoice> {
        if (json.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            val list = ArrayList<TtsVoice>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val shortName = obj.optString("ShortName").orEmpty()
                if (shortName.isBlank()) continue
                val locale = obj.optString("Locale", "")
                val friendly = obj.optString("FriendlyName", shortName)
                val gender = obj.optString("Gender", "")
                // 把 FriendlyName 简化成"语种-音色 (性别)"形式
                val displayName = simplifyFriendlyName(friendly, shortName, gender, locale)
                list.add(
                    TtsVoice(
                        id = shortName,
                        name = displayName,
                        language = locale,
                        engine = "edge",
                    )
                )
            }
            // 排序：zh- 系列优先，其余按 locale 字母序，相同 locale 内按 id 字母序
            list.sortedWith(
                compareBy(
                    { if (it.language.startsWith("zh", ignoreCase = true)) 0 else 1 },
                    { it.language },
                    { it.id },
                )
            )
        } catch (e: Exception) {
            AppLog.warn(TAG, "voices json parse failed: ${e.message}")
            emptyList()
        }
    }

    /**
     * 把微软返回的冗长 FriendlyName（例 "Microsoft Xiaoxiao Online (Natural) - Chinese (Mainland)"）
     * 简化成 "晓晓 (女声·zh-CN)" 这种紧凑形式。
     */
    private fun simplifyFriendlyName(
        friendly: String,
        shortName: String,
        gender: String,
        locale: String,
    ): String {
        // 取 shortName 的中段（"zh-CN-XiaoxiaoNeural" → "Xiaoxiao"）
        val coreName = shortName.removeSuffix("Neural")
            .substringAfterLast('-')
            .ifBlank { friendly }
        val genderTag = when (gender) {
            "Female" -> "女声"
            "Male" -> "男声"
            else -> ""
        }
        return buildString {
            append(coreName)
            if (genderTag.isNotEmpty() || locale.isNotEmpty()) {
                append(" (")
                if (genderTag.isNotEmpty()) append(genderTag)
                if (genderTag.isNotEmpty() && locale.isNotEmpty()) append("·")
                if (locale.isNotEmpty()) append(locale)
                append(")")
            }
        }
    }

    // ── 异常类型 ──────────────────────────────────────────────────────────

    /** 401/403 鉴权失败，触发时钟偏移自校正后重试。 */
    private class AuthFailedException(
        val httpCode: Int,
        val serverDate: String?,
        cause: Throwable?,
    ) : RuntimeException("Edge TTS auth failed (HTTP $httpCode)", cause)

    companion object {
        private const val TAG = "EdgeTTS"
        private const val AUDIO_SAMPLE_RATE = 24000
        private const val MAX_ATTEMPTS = 2

        /** 网络失败时的回退音色（向后兼容老用户保存的 voiceName）。 */
        val HARDCODED_VOICES = listOf(
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

        /** 兼容旧代码：原来 EdgeTtsEngine.VOICES 是 public static field，TtsEngineHost 直接引用 */
        @JvmField
        val VOICES: List<TtsVoice> = HARDCODED_VOICES
    }
}
