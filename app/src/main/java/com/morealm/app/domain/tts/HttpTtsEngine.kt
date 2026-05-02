package com.morealm.app.domain.tts

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.morealm.app.core.log.AppLog
import com.morealm.app.domain.analyzeRule.AnalyzeUrl
import com.morealm.app.domain.analyzeRule.JsExtensions
import com.morealm.app.domain.entity.HttpTts
import com.morealm.app.domain.entity.TtsVoice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.awaitClose
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * HTTP TTS 引擎 — 把段落文本交给用户配置的 HTTP 朗读源（Legado httpTTS 兼容），
 * 拉回音频字节、缓存到本地文件、用 ExoPlayer 播放。
 *
 * 与 Legado [io.legado.app.service.HttpReadAloudService] 的对应关系：
 * - URL 模板 / header / loginCheckJs：通过 [AnalyzeUrl] 渲染（替换 `{{speakText}}`/
 *   `{{speakSpeed}}`/`{{encode}}`、合并 header、执行 `<js>...</js>`）。
 * - 音频缓存：`context.cacheDir/httpTTS/<md5>.mp3`，命名与 Legado
 *   `md5SpeakFileName` 对齐 (`md5(chapterTitle)_md5(url-|-speed-|-text)`)，
 *   下次重听同一章同一段直接读文件不发请求。
 * - 播放：ExoPlayer 单实例 + playlist。speak() 单段同步等播完；speakChapter()
 *   批量入队并通过 [Player.Listener.onMediaItemTransition] 把段进度回调给 host。
 * - 错误：单段失败计入 [consecutiveErrors]，连续 5 次抛 [IOException]，host 自己
 *   决定是否回退到 system；与 Legado HttpReadAloudService.kt:393-409 的"5 次"阈值一致。
 *
 * **本实现刻意省略**：
 * - SimpleCache 流式（用户答允许；文件级缓存够用，重听都命中）
 * - silent_sound 占位音（直接跳段，[onParagraphStart] 推进到下一段）
 * - loginCheckJs 真实运行（字段保存就行；后续 PR 再接，这次只接 url/header）
 *
 * ExoPlayer 必须在主线程构造与调用 ([ExoPlayer.Builder.build] 检查 Looper）。
 * 本类内所有播放器操作都用 [withContext(Dispatchers.Main)] 或 [mainHandler] 切回主线程。
 */
class HttpTtsEngine(
    private val context: Context,
    private var config: HttpTts,
) : TtsEngine {

    override val name get() = config.name
    override val id get() = "http_${config.id}"

    private val cacheDir: File by lazy {
        File(context.cacheDir, "httpTTS").apply { if (!exists()) mkdirs() }
    }

    /** 主线程懒构造，由 [ensureExoPlayer] 在 Main dispatcher 内创建。 */
    @Volatile private var exoPlayer: ExoPlayer? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    /**
     * 连续失败计数。speak() 与 speakChapter() 共享一份。
     * - 单段 OK → 归零
     * - 单段失败 → +1
     * - >= [MAX_CONSECUTIVE_ERRORS] 时抛 [IOException]，由 host 决定是否回退到 system。
     */
    @Volatile private var consecutiveErrors = 0

    fun updateConfig(newConfig: HttpTts) {
        config = newConfig
        AppLog.info(TAG, "updateConfig: id=${newConfig.id} name='${newConfig.name}'")
    }

    // ── TtsEngine ────────────────────────────────────────────────────────────

    override suspend fun speak(text: String, speed: Float): Flow<AudioChunk> = callbackFlow {
        if (text.isBlank()) {
            trySend(AudioChunk(ByteArray(0), "done"))
            close(); return@callbackFlow
        }
        trySend(AudioChunk(ByteArray(0), "started"))
        try {
            val file = downloadOrFromCache(text, speed, chapterTitleHint = "oneshot")
            playSingleFileBlocking(file)
            consecutiveErrors = 0
            trySend(AudioChunk(ByteArray(0), "done"))
            close()
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            AppLog.warn(TAG, "speak failed: ${e.message}", e)
            consecutiveErrors++
            if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                close(IOException("TTS 服务器连续 $MAX_CONSECUTIVE_ERRORS 次失败：${e.message}"))
            } else {
                close(e)
            }
        }
        awaitClose { stop() }
    }

    override suspend fun getVoices(): List<TtsVoice> =
        listOf(TtsVoice(id = config.name, name = config.name, language = "custom", engine = "http"))

    override fun stop() {
        mainHandler.post {
            try {
                exoPlayer?.let {
                    it.stop()
                    it.clearMediaItems()
                }
            } catch (e: Exception) {
                AppLog.debug(TAG, "stop swallow: ${e.message}")
            }
        }
    }

    override fun isAvailable(): Boolean = config.url.isNotBlank()

    // ── speakChapter (batch) ─────────────────────────────────────────────────

    /**
     * 整章批量朗读。签名贴近 [EdgeTtsEngine.speakChapter] 让 [TtsEngineHost] 走相同
     * 的入口分流。流程：
     *
     * 1. 第一段同步下载（首段必须就位才能开始播）
     * 2. 入队 ExoPlayer 单 mediaItem 并 prepare → playWhenReady
     * 3. 后续段在协程里顺序下载（每段一个文件），下载完成立即 addMediaItem
     * 4. ExoPlayer 监听 onMediaItemTransition：每次切到下一段就回调
     *    [onParagraphStart](localIdx)；STATE_ENDED 触发 finished.complete()
     * 5. 期间任意 cancel → 在 awaitClose 里 stop player + clear items
     *
     * **不实现 streaming/SimpleCache**：每段一个文件、顺序拉、重听命中文件缓存。
     *
     * @param paragraphs 已经被 host 过滤掉空/skip 段的"将要朗读的段"列表。**索引就是
     *                   localIdx**，host 用 origIndices[] 映射回真实 paragraphIndex。
     * @param onParagraphStart 段开始播报时回调，参数 = paragraphs 中的相对索引（0..n-1）
     */
    suspend fun speakChapter(
        paragraphs: List<String>,
        speed: Float,
        chapterTitleHint: String = "",
        onParagraphStart: (relativeIdx: Int) -> Unit = {},
    ): Flow<AudioChunk> = callbackFlow {
        if (paragraphs.isEmpty()) {
            trySend(AudioChunk(ByteArray(0), "done"))
            close(); return@callbackFlow
        }
        AppLog.info(
            TAG,
            "speakChapter: paragraphs=${paragraphs.size} " +
                "totalChars=${paragraphs.sumOf { it.length }} chapter='$chapterTitleHint'",
        )
        trySend(AudioChunk(ByteArray(0), "started"))

        val finished = CompletableDeferred<Unit>()
        val listener = object : Player.Listener {
            override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                val player = exoPlayer ?: return
                val idx = player.currentMediaItemIndex
                if (idx in paragraphs.indices) {
                    runCatching { onParagraphStart(idx) }
                }
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED && !finished.isCompleted) {
                    finished.complete(Unit)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                AppLog.warn(TAG, "ExoPlayer error: ${error.errorCodeName} ${error.message}")
                if (!finished.isCompleted) finished.completeExceptionally(error)
            }
        }

        try {
            // 第一段同步下载，先开播
            val firstFile = downloadOrFromCache(paragraphs[0], speed, chapterTitleHint)
            withContext(Dispatchers.Main) {
                ensureExoPlayer()
                val player = exoPlayer!!
                player.removeListener(listener) // 防重复
                player.addListener(listener)
                player.setMediaItem(MediaItem.fromUri(firstFile.toUri()))
                player.prepare()
                player.playWhenReady = true
            }
            // 第一段开播后立刻回调一次（onMediaItemTransition 不会在初次 setMediaItem 后触发）
            runCatching { onParagraphStart(0) }
            consecutiveErrors = 0

            // 后续段：协程顺序下载并 addMediaItem
            for (i in 1 until paragraphs.size) {
                if (finished.isCompleted) break // 用户 stop 或 player 自爆
                val text = paragraphs[i]
                val file = try {
                    downloadOrFromCache(text, speed, chapterTitleHint)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    consecutiveErrors++
                    AppLog.warn(TAG, "para $i download failed (consec=$consecutiveErrors): ${e.message}")
                    if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                        finished.completeExceptionally(
                            IOException("TTS 服务器连续 $MAX_CONSECUTIVE_ERRORS 次失败：${e.message}")
                        )
                        break
                    }
                    continue // 跳过这段
                }
                consecutiveErrors = 0
                withContext(Dispatchers.Main) {
                    exoPlayer?.addMediaItem(MediaItem.fromUri(file.toUri()))
                }
            }

            // 等待播放结束（或失败）
            finished.await()
            trySend(AudioChunk(ByteArray(0), "done"))
            close()
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            AppLog.warn(TAG, "speakChapter failed: ${e.message}", e)
            close(e)
        }

        awaitClose {
            mainHandler.post {
                try {
                    exoPlayer?.removeListener(listener)
                    exoPlayer?.stop()
                    exoPlayer?.clearMediaItems()
                } catch (_: Exception) {}
            }
        }
    }

    // ── Internal: download + play ────────────────────────────────────────────

    /**
     * 下载或命中文件缓存。命名沿用 Legado [HttpReadAloudService.md5SpeakFileName]
     * 风格：`md5_16(chapterTitle)_md5_16(url-|-speed-|-text)`。
     *
     * 缓存命中时直接返回 [File]，不发请求。否则走 [AnalyzeUrl] 渲染请求 → OkHttp →
     * 写入 cacheDir → 返回 [File]。
     */
    private suspend fun downloadOrFromCache(
        text: String,
        speed: Float,
        chapterTitleHint: String,
    ): File = withContext(Dispatchers.IO) {
        val cfg = config
        if (cfg.url.isBlank()) throw IOException("HttpTts URL 为空")

        // 与 Legado 同款 speed 整数化（rate = (speed-1)*10），避免浮点漂移导致缓存 miss
        val rateInt = ((speed - 1f) * 10).toInt()
        val cacheKey = JsExtensions.md5Encode16(chapterTitleHint) + "_" +
            JsExtensions.md5Encode16("${cfg.url}-|-$rateInt-|-$text")
        val file = File(cacheDir, "$cacheKey.mp3")
        if (file.exists() && file.length() > 0) {
            AppLog.debug(TAG, "cacheHit: ${file.name} (${file.length()} bytes)")
            return@withContext file
        }

        val analyze = AnalyzeUrl(
            mUrl = cfg.url,
            speakText = text,
            speakSpeed = rateInt,
            httpTts = cfg,
        )
        val finalUrl = analyze.url.ifBlank {
            throw IOException("AnalyzeUrl 渲染后 URL 为空（原模板：${cfg.url.take(80)}）")
        }
        val reqBuilder = Request.Builder().url(finalUrl)
        analyze.headerMap.forEach { (k, v) -> reqBuilder.header(k, v) }

        AppLog.debug(TAG, "GET ${finalUrl.take(120)} headers=${analyze.headerMap.size}")
        client.newCall(reqBuilder.build()).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IOException("HTTP ${resp.code} ${resp.message}")
            }
            val ct = resp.header("Content-Type")?.substringBefore(";")?.trim().orEmpty()
            // 服务端回 JSON / 文本 → 八成是错误信息或鉴权失败，直接抛
            if (ct == "application/json" || ct.startsWith("text/")) {
                val body = runCatching { resp.body?.string()?.take(300) }.getOrNull().orEmpty()
                throw IOException("TTS 服务器返回非音频 content-type=$ct: $body")
            }
            val expectedCt = cfg.contentType?.takeIf { it.isNotBlank() }
            if (expectedCt != null && !runCatching { ct.matches(expectedCt.toRegex()) }.getOrDefault(true)) {
                throw IOException("Content-Type 不匹配：期望 $expectedCt，实际 $ct")
            }
            val body = resp.body ?: throw IOException("响应 body 为空")
            // 写入临时文件后 rename，避免下载中断留半截损坏文件命中下次缓存
            val tmp = File(cacheDir, "$cacheKey.mp3.tmp")
            tmp.outputStream().use { out -> body.byteStream().use { it.copyTo(out) } }
            if (tmp.length() == 0L) {
                tmp.delete()
                throw IOException("下载得到空文件")
            }
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
            AppLog.debug(TAG, "downloaded ${file.name}: ${file.length()} bytes")
        }
        file
    }

    /**
     * 单段同步等待播放结束。仅 [speak] 用——chapter 路径不阻塞、由 onPlaybackStateChanged
     * 自管。
     */
    private suspend fun playSingleFileBlocking(file: File) {
        val deferred = CompletableDeferred<Unit>()
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED && !deferred.isCompleted) deferred.complete(Unit)
            }

            override fun onPlayerError(error: PlaybackException) {
                if (!deferred.isCompleted) deferred.completeExceptionally(error)
            }
        }
        withContext(Dispatchers.Main) {
            ensureExoPlayer()
            val player = exoPlayer!!
            player.addListener(listener)
            player.setMediaItem(MediaItem.fromUri(file.toUri()))
            player.prepare()
            player.playWhenReady = true
        }
        try {
            deferred.await()
        } finally {
            withContext(Dispatchers.Main) {
                exoPlayer?.removeListener(listener)
            }
        }
    }

    /** Main dispatcher 内调用：确保 exoPlayer 已经在主线程构造。 */
    private fun ensureExoPlayer() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            error("ensureExoPlayer must be called on Main looper")
        }
        if (exoPlayer == null) {
            exoPlayer = ExoPlayer.Builder(context).build()
            AppLog.info(TAG, "ExoPlayer instantiated")
        }
    }

    /**
     * 释放 ExoPlayer。host 切换源 / service 销毁时调用。
     */
    fun release() {
        mainHandler.post {
            try {
                exoPlayer?.release()
            } catch (_: Exception) {}
            exoPlayer = null
        }
    }

    companion object {
        private const val TAG = "HttpTTS"
        private const val MAX_CONSECUTIVE_ERRORS = 5
    }
}
