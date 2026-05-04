package com.morealm.app.domain.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.morealm.app.domain.entity.TtsVoice
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

/**
 * System TTS engine wrapper.
 * Uses Android's built-in TextToSpeech for speech synthesis.
 */
class SystemTtsEngine(private val context: Context) : TtsEngine {
    override val name = "系统朗读"
    override val id = "system"

    /**
     * Outcome of a [TextToSpeech] init attempt.
     * [Failed.reason] carries a Chinese, user-facing message suitable for Toast/Snackbar.
     */
    sealed class InitResult {
        object Success : InitResult()
        data class Failed(val reason: String) : InitResult()
    }

    private var tts: TextToSpeech? = null
    private var isReady = false

    /** Set exactly once when the OnInitListener fires (or it never fires — see awaitReadyResult timeout). */
    @Volatile private var initResult: InitResult? = null
    private val pendingReadyCallbacks = mutableListOf<(InitResult) -> Unit>()

    // ── Voice 列表缓存 ───────────────────────────────────────────────────────
    //
    // [TextToSpeech.getVoices] 是同步 binder IPC（远程 TTS 服务进程响应慢/挂死时
    // 会无限期阻塞调用线程）。播放热路径上的 [setVoice]、[getChineseVoices]、
    // [getVoices] 之前每次都走 IPC，一旦绑定的引擎进程异常就把调用线程冻住——
    // 历史 ANR（temp/err.txt 9 次连续 8s 主线程阻塞）的根因之一。
    //
    // 缓存策略：onInit SUCCESS 之后在 listener 线程一次性拉满 voices 列表存进
    // [cachedVoices]；后续业务流路径只读缓存，不再 IPC。voice 列表在引擎绑定后
    // 基本不变（用户安装/卸载语音包是低频操作，且会触发引擎重启），缓存命中率
    // 接近 100%。reInit 时清缓存重填。
    //
    // 缓存 miss 兜底：极少数情况（onInit 后 voices 还没就绪、用户卸载语音包后
    // 引擎没重启）由 [readVoicesFromEngineBounded] 用 [VOICES_FETCH_TIMEOUT_MS]
    // 包裹一次 IPC，超时返回 emptyList() —— 业务上等同于"没装中文音色"，
    // 走 [setLanguage] 兜底，不会再卡死任何线程。
    @Volatile private var cachedVoices: List<Voice>? = null

    // ── 批量入队模式（仿 Legado TTSReadAloudService）────────────────────────────
    //
    // 旧的 [speak] 实现每段都重建 callbackFlow + 替换 OnUtteranceProgressListener，
    // 串行 await 一段读完再喂下一段。问题：
    //   1. 引擎合并/延迟 onDone 时，旧 utteranceId 触发的回调走到新 listener 里
    //      `id == 旧utteranceId` 不匹配 → Flow 永不 close → 整个 speakLoop 卡死。
    //   2. 段间多一个 setListener+speak 调用周期，导致段间停顿明显。
    //   3. 长段（一段 200+ 字）整体扔给引擎，引擎自己分句质量参差。
    //
    // Legado 的做法：入队前 setOnUtteranceProgressListener 一次（全局常驻 listener），
    // 然后 `for(i) tts.speak(text, QUEUE_FLUSH/QUEUE_ADD, "id$i")` 一次性把整章全
    // 部入队，listener 用 utteranceId 区分回调来源。这里照搬此模型，给 host 用。
    interface BatchCallback {
        fun onUtteranceStart(utteranceId: String) {}
        fun onUtteranceDone(utteranceId: String) {}
        fun onUtteranceError(utteranceId: String, errorCode: Int) {}
        /** 引擎播报到 utterance 内 [start, end) 字符；用于句中位置追踪。 */
        fun onRangeStart(utteranceId: String, start: Int, end: Int) {}
    }

    @Volatile private var batchCallback: BatchCallback? = null

    /** 全局常驻 listener — 注册一次后所有 utterance 都走这里，按 utteranceId 分发。 */
    private val globalUtteranceListener = object : UtteranceProgressListener() {
        override fun onStart(id: String?) {
            id ?: return
            val cb = batchCallback
            if (cb == null) {
                // batchCallback 为 null 但回调还在触发——可能是 oneShot 残留，或
                // 引擎在 setBatchCallback(null) 之后还有 pending utterance。仅
                // debug 级别记录，不当作错误。
                com.morealm.app.core.log.AppLog.debug(
                    "TTS", "engine.onStart dropped (no cb): id=$id"
                )
                return
            }
            com.morealm.app.core.log.AppLog.debug("TTS", "engine.onStart → cb id=$id")
            cb.onUtteranceStart(id)
        }
        override fun onDone(id: String?) {
            id ?: return
            val cb = batchCallback
            if (cb == null) {
                com.morealm.app.core.log.AppLog.debug(
                    "TTS", "engine.onDone dropped (no cb): id=$id"
                )
                return
            }
            com.morealm.app.core.log.AppLog.debug("TTS", "engine.onDone → cb id=$id")
            cb.onUtteranceDone(id)
        }
        override fun onError(id: String?, errorCode: Int) {
            id ?: return
            com.morealm.app.core.log.AppLog.warn(
                "TTS", "engine.onError id=$id code=$errorCode (cb=${batchCallback != null})"
            )
            batchCallback?.onUtteranceError(id, errorCode)
        }
        @Deprecated("Deprecated in Java")
        override fun onError(id: String?) {
            id ?: return
            com.morealm.app.core.log.AppLog.warn(
                "TTS", "engine.onError(legacy) id=$id (cb=${batchCallback != null})"
            )
            batchCallback?.onUtteranceError(id, -1)
        }
        override fun onRangeStart(id: String?, start: Int, end: Int, frame: Int) {
            id ?: return
            // 高频回调，不在这里打日志；上层 BatchCallback 自己做去重打印。
            batchCallback?.onRangeStart(id, start, end)
        }
    }

    /**
     * 装配批量回调。传 null 解除（pause/stop 后保留 listener 但停止分发，避免回调
     * 调用已失效的对象）。每次重新启动批量都要重置一次 callback；旧 callback 上的
     * pending 回调会被静默吞掉。
     */
    fun setBatchCallback(cb: BatchCallback?) {
        com.morealm.app.core.log.AppLog.debug(
            "TTS", "setBatchCallback: ${if (cb != null) "registered" else "cleared"}"
        )
        batchCallback = cb
        if (cb != null) {
            tts?.setOnUtteranceProgressListener(globalUtteranceListener)
        }
    }

    /** 设置全局语速（影响后续所有入队 utterance）。 */
    fun setSpeechRate(speed: Float) {
        val coerced = speed.coerceIn(0.3f, 4.0f)
        com.morealm.app.core.log.AppLog.debug("TTS", "setSpeechRate: $speed → $coerced")
        tts?.setSpeechRate(coerced)
    }

    /**
     * 把 [text] 加入 TTS 引擎的播放队列。
     * @param queueMode [TextToSpeech.QUEUE_FLUSH]（清队首段）或 [TextToSpeech.QUEUE_ADD]（追加）
     * @return [TextToSpeech.SUCCESS] 或 [TextToSpeech.ERROR]
     */
    fun enqueue(text: String, utteranceId: String, queueMode: Int): Int {
        val engine = tts
        if (engine == null) {
            com.morealm.app.core.log.AppLog.warn(
                "TTS", "enqueue: engine null, returning ERROR (id=$utteranceId)"
            )
            return TextToSpeech.ERROR
        }
        // 第一条 utterance（QUEUE_FLUSH）时打一行实际绑定的包名，帮助诊断
        // "multitts 不生效"——如果 defaultEngine != 用户选的包，说明绑包失败。
        if (queueMode == TextToSpeech.QUEUE_FLUSH) {
            val actualPkg = runCatching { engine.defaultEngine }.getOrNull() ?: "?"
            com.morealm.app.core.log.AppLog.info(
                "TTS",
                "enqueue(FLUSH): actualEngine=$actualPkg, id=$utteranceId, textLen=${text.length}",
            )
        }
        return runCatching {
            engine.speak(text, queueMode, null, utteranceId)
        }.getOrElse {
            com.morealm.app.core.log.AppLog.warn(
                "TTS",
                "SystemTtsEngine.enqueue threw: ${it.message} (id=$utteranceId)",
            )
            TextToSpeech.ERROR
        }
    }

    /**
     * Kick off async init. The callback receives the final [InitResult].
     * Failure cases include: no TTS engine installed, init returned ERROR,
     * or the engine doesn't support / lacks data for Chinese.
     *
     * @param enginePackage 显式绑定的引擎包名（如 "com.google.android.tts"、
     *  "net.olekdia.multispeech"）。null 或空 = 用系统默认引擎（不带 engineName
     *  的旧路径）。指定包名时使用 3 参 `TextToSpeech(ctx, listener, engineName)`
     *  构造，能在系统默认引擎不可用 / 没启动时直接绑用户选定的包，绕过
     *  "未识别到 TTS 引擎" 的兜底失败。
     */
    fun initialize(enginePackage: String? = null, onResult: (InitResult) -> Unit = {}) {
        val pkg = enginePackage?.takeIf { it.isNotBlank() }
        com.morealm.app.core.log.AppLog.info(
            "TTS",
            "SystemTtsEngine.initialize: creating TextToSpeech (pkg=${pkg ?: "<system-default>"})",
        )
        val listener = TextToSpeech.OnInitListener { status ->
            // 打印 init 结果 + 当前引擎绑到的实际包名（对比用户选的包名）
            val actualEngine = runCatching { tts?.defaultEngine }.getOrNull() ?: "?"
            com.morealm.app.core.log.AppLog.info(
                "TTS",
                "SystemTtsEngine.OnInitListener: status=$status (SUCCESS=${TextToSpeech.SUCCESS}) " +
                    "pkg=${pkg ?: "<system-default>"} actualEngine=$actualEngine",
            )
            // 打印系统上所有已安装 TTS 引擎——帮助诊断 multitts 是否被识别
            val enginesList = runCatching {
                tts?.engines?.joinToString { "${it.label}(${it.name})" }
            }.getOrNull() ?: "?"
            com.morealm.app.core.log.AppLog.info(
                "TTS",
                "SystemTtsEngine: installed engines=[$enginesList]",
            )
            val result: InitResult = if (status == TextToSpeech.SUCCESS) {
                val langStatus = try {
                    tts?.setLanguage(Locale.CHINESE) ?: TextToSpeech.LANG_NOT_SUPPORTED
                } catch (e: Exception) {
                    com.morealm.app.core.log.AppLog.warn("TTS", "setLanguage(CHINESE) threw: ${e.message}")
                    TextToSpeech.LANG_NOT_SUPPORTED
                }
                com.morealm.app.core.log.AppLog.info("TTS", "SystemTtsEngine.setLanguage → langStatus=$langStatus")
                when (langStatus) {
                    TextToSpeech.LANG_MISSING_DATA ->
                        InitResult.Failed("中文语音数据缺失，请到系统「设置 → 语言与输入 → 文字转语音」安装语音包")
                    TextToSpeech.LANG_NOT_SUPPORTED ->
                        InitResult.Failed("当前 TTS 引擎不支持中文，请更换或安装一个支持中文的引擎")
                    else -> {
                        // 在 listener 线程预填 voices 缓存。这一步阻塞也只阻塞
                        // listener 线程（不是 Main，不是业务协程），引擎进程异常
                        // 时 cachedVoices 留 null，后续走 [readVoicesFromEngineBounded]
                        // 的兜底超时；不会传染到播放路径。
                        cachedVoices = runCatching { tts?.voices?.toList() }
                            .getOrNull()
                            ?.also { voices ->
                                com.morealm.app.core.log.AppLog.info(
                                    "TTS",
                                    "SystemTtsEngine: cachedVoices populated, size=${voices.size}",
                                )
                            }
                        isReady = true
                        InitResult.Success
                    }
                }
            } else {
                // 包名带了但还是失败——附在文案里给用户看，方便定位是不是
                // 包名打错 / 该引擎进程没起来 / 系统列表没识别到。
                val pkgHint = pkg?.let { "（已绑定包名 $it）" }.orEmpty()
                InitResult.Failed("系统未识别到可用的 TTS 引擎$pkgHint，请到系统设置启用或安装一个引擎")
            }
            initResult = result
            onResult(result)
            // Snapshot + clear under the (single-threaded) listener thread to avoid
            // re-entrant mutation if a callback synchronously triggers more work.
            val snapshot = pendingReadyCallbacks.toList()
            pendingReadyCallbacks.clear()
            snapshot.forEach { it(result) }
        }
        tts = if (pkg != null) {
            TextToSpeech(context, listener, pkg)
        } else {
            TextToSpeech(context, listener)
        }
    }

    /**
     * 列出系统已安装的所有 TTS 引擎，供"听书"页 UI 让用户选指定包。
     * 不需要 init 完成 —— `getEngines` 在 binder 没建好时也能查 PackageManager。
     * 返回空列表表示真没装任何 TTS 引擎（这种情况下 picker UI 应该提示用户去
     * 系统设置安装）。
     */
    fun getInstalledEngines(): List<EngineInfo> {
        return runCatching {
            val engine = tts ?: TextToSpeech(context) {}.also { tts = it }
            engine.engines.orEmpty().map {
                EngineInfo(name = it.name.orEmpty(), label = it.label.orEmpty())
            }
        }.getOrElse {
            com.morealm.app.core.log.AppLog.warn(
                "TTS", "getInstalledEngines threw: ${it.message}",
            )
            emptyList()
        }
    }

    /** 单个系统 TTS 引擎条目。 */
    data class EngineInfo(
        /** 包名，例如 "com.google.android.tts"。直接喂给 [initialize] 的 enginePackage。 */
        val name: String,
        /** 用户可读 label。空时 UI 自己回退显示包名。 */
        val label: String,
    )

    /**
     * Suspend until init resolves, with a hard timeout so a never-firing OnInitListener
     * (Android Go / stripped ROMs / disabled engines) can't deadlock the speak loop.
     * Returns [InitResult.Failed] on timeout — never throws.
     */
    suspend fun awaitReadyResult(timeoutMs: Long = DEFAULT_INIT_TIMEOUT_MS): InitResult {
        initResult?.let { return it }
        val resolved = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<InitResult> { cont ->
                initResult?.let { cont.resume(it); return@suspendCancellableCoroutine }
                val cb: (InitResult) -> Unit = { if (cont.isActive) cont.resume(it) }
                pendingReadyCallbacks.add(cb)
                cont.invokeOnCancellation { pendingReadyCallbacks.remove(cb) }
            }
        }
        return resolved ?: InitResult.Failed(
            "TTS 引擎初始化超时（${timeoutMs / 1000}s 未响应），请检查系统 TTS 设置"
        )
    }

    /**
     * Backwards-compatible suspend variant — returns Unit and never throws.
     * Returns immediately on either Success or Failed; callers that need the reason
     * should use [awaitReadyResult] instead.
     */
    suspend fun awaitReady() {
        awaitReadyResult()
    }

    override suspend fun speak(text: String, speed: Float): Flow<AudioChunk> = callbackFlow {
        val engine = tts
        if (engine == null) {
            // Distinct from "blank text" — surface the failure so the speak loop can
            // treat it as an error and propagate a user-facing message instead of
            // silently completing and looping forever on the next paragraph.
            val reason = (initResult as? InitResult.Failed)?.reason
                ?: "TTS 引擎不可用，请检查系统 TTS 设置"
            com.morealm.app.core.log.AppLog.warn("TTS", "SystemTtsEngine.speak: engine==null, reason=$reason")
            close(IllegalStateException(reason))
            return@callbackFlow
        }
        if (text.isBlank()) {
            com.morealm.app.core.log.AppLog.debug("TTS", "SystemTtsEngine.speak: blank text → close")
            close()
            return@callbackFlow
        }

        engine.setSpeechRate(speed)

        // Voice health check — if the saved voice has been uninstalled (e.g.
        // the user removed a language pack while the TTS engine kept the
        // stale handle), `engine.voice.features` contains "notInstalled".
        // Calling speak() against a not-installed voice on Android 9-12 can
        // make tts.speak() return ERROR synchronously. Reset to default
        // Chinese language to recover. Cheap: one property read per paragraph.
        try {
            val v = engine.voice
            val notInstalled = v?.features?.contains(android.speech.tts.TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) == true
            if (v == null || notInstalled) {
                com.morealm.app.core.log.AppLog.warn(
                    "TTS",
                    "SystemTtsEngine.speak: voice unhealthy (null=${v == null}, notInstalled=$notInstalled), resetting to CHINESE",
                )
                engine.language = Locale.CHINESE
            }
        } catch (e: Exception) {
            com.morealm.app.core.log.AppLog.warn("TTS", "voice health check threw: ${e.message}")
        }

        val utteranceId = "morealm_tts_${System.nanoTime()}"
        com.morealm.app.core.log.AppLog.info(
            "TTS",
            "SystemTtsEngine.speak: id=$utteranceId, len=${text.length}, rate=$speed",
        )

        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {
                if (id == utteranceId) {
                    com.morealm.app.core.log.AppLog.debug("TTS", "SystemTtsEngine.onStart id=$id")
                    trySend(AudioChunk(ByteArray(0), "started"))
                }
            }
            override fun onDone(id: String?) {
                if (id == utteranceId) {
                    com.morealm.app.core.log.AppLog.debug("TTS", "SystemTtsEngine.onDone id=$id")
                    trySend(AudioChunk(ByteArray(0), "done"))
                    close()
                }
            }
            override fun onError(id: String?, errorCode: Int) {
                if (id == utteranceId) {
                    com.morealm.app.core.log.AppLog.warn(
                        "TTS",
                        "SystemTtsEngine.onError id=$id errorCode=$errorCode",
                    )
                    // Embed errorCode + a short id tail in the exception message
                    // so the host log line "(consec=N)" is still self-explanatory
                    // after only seeing the wrapped exception (without unfolding
                    // the AppLog "Caused by" trace).
                    close(Exception("TTS synth error code=$errorCode id=…${id?.takeLast(6) ?: "?"}"))
                }
            }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) {
                if (id == utteranceId) {
                    com.morealm.app.core.log.AppLog.warn("TTS", "SystemTtsEngine.onError(legacy) id=$id")
                    // Legacy path has no errorCode — call this out so the user-facing
                    // log makes clear *why* the cause string is so vague. The host's
                    // reInit-and-retry recovery handles both paths identically.
                    close(Exception("TTS synth error (legacy callback, no code) id=…${id?.takeLast(6) ?: "?"}"))
                }
            }
        })

        val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        com.morealm.app.core.log.AppLog.info(
            "TTS",
            "SystemTtsEngine.speak: tts.speak() returned=$result " +
                "(SUCCESS=${TextToSpeech.SUCCESS}, ERROR=${TextToSpeech.ERROR})",
        )
        if (result == TextToSpeech.ERROR) {
            // speak() failed — onDone will never be called, close immediately
            close(Exception("TTS speak() returned ERROR"))
            return@callbackFlow
        }
        awaitClose { /* don't stop here — let the caller control stopping */ }
    }

    override suspend fun getVoices(): List<TtsVoice> {
        return loadVoicesSafely().map { voice -> voice.toDomain() }
    }

    /** Set the TTS voice by name */
    fun setVoice(voiceName: String) {
        val engine = tts ?: return
        if (voiceName.isBlank()) {
            engine.language = Locale.CHINESE
            return
        }
        // 只在缓存里找——不再触发 binder IPC。缓存为空（onInit 还没完成或异常）
        // 时直接回退到 setLanguage(CHINESE)，避免拖慢/卡死调用线程。这是仿
        // Legado 但更激进的策略：宁可声音是默认而不是用户选的那个，也不让
        // setVoice 把 host 卡 8 秒以上。
        val voice = cachedVoices?.firstOrNull { it.name == voiceName }
        if (voice != null) {
            engine.voice = voice
        } else {
            com.morealm.app.core.log.AppLog.warn(
                "TTS",
                "setVoice: '$voiceName' not in cache (cacheSize=${cachedVoices?.size ?: -1}), " +
                    "falling back to setLanguage(CHINESE)",
            )
            engine.language = Locale.CHINESE
        }
    }

    /** Get Chinese-compatible voices sorted by relevance */
    fun getChineseVoices(): List<TtsVoice> {
        val voices = cachedVoices
            ?: run {
                com.morealm.app.core.log.AppLog.debug(
                    "TTS", "getChineseVoices: cache empty, returning emptyList()"
                )
                return emptyList()
            }
        return voices
            .filter { v ->
                val lang = v.locale.language
                lang == "zh" || lang == "cmn" || v.locale.toLanguageTag().startsWith("zh")
            }
            .sortedWith(compareBy(
                { if (it.isNetworkConnectionRequired) 1 else 0 },
                { it.name }
            ))
            .map { it.toDomain() }
    }

    /**
     * 业务路径外的 getVoices() 兜底——缓存 miss 时用 [VOICES_FETCH_TIMEOUT_MS]
     * 上限的同步 IPC 拉一次，并把结果回填缓存。永远不抛、永远在超时内返回。
     *
     * 这个方法**只**应该在非热路径（如 voice picker UI 首次加载）使用；播放
     * 内联调用 [setVoice] 不会再触发它。
     */
    private suspend fun loadVoicesSafely(): List<Voice> {
        cachedVoices?.let { return it }
        val engine = tts ?: return emptyList()
        val fetched = withTimeoutOrNull(VOICES_FETCH_TIMEOUT_MS) {
            runCatching { engine.voices?.toList() }.getOrNull()
        }
        if (fetched == null) {
            com.morealm.app.core.log.AppLog.warn(
                "TTS",
                "loadVoicesSafely: getVoices() timed out after ${VOICES_FETCH_TIMEOUT_MS}ms, returning empty",
            )
            return emptyList()
        }
        cachedVoices = fetched
        return fetched
    }

    /** Voice → 应用层 [TtsVoice]（id/name/language/engine 映射）。 */
    private fun Voice.toDomain(): TtsVoice = TtsVoice(
        id = name,
        name = name,
        language = locale.displayName,
        engine = id,
    )

    /**
     * 停止当前朗读。
     *
     * 实现注意：[TextToSpeech.stop] 内部是一次同步 binder transact，**部分设备**（特别是
     * 系统 TTS 进程负载高 / engine buffer 多时）会阻塞调用线程 5–8 秒以上。日志
     * `log_2026-05-04` 09:31:17 抓到一次 ANR 栈：
     *
     *     android.os.BinderProxy.transactNative
     *       ← TextToSpeech.stop:1418
     *       ← SystemTtsEngine.stop:507
     *       ← TtsEngineHost.pause:600  (在 Dispatchers.Main 的 controlMutex 内)
     *
     * 主线程被卡 >8 秒 → ANR Watchdog。修复：把 binder 调用 fire-and-forget 到背景
     * 线程；调用方（pause/stop/setEngine 路径）立刻返回，不再阻塞 Main。
     *
     * 这种 fire-and-forget 策略和 Legado `TTSReadAloudService` 一致：stop 没返回值
     * 也不依赖完成时机——引擎下一次 enqueue 时会通过 onError 回调反馈状态。
     */
    override fun stop() {
        val current = tts ?: return
        Thread({ runCatching { current.stop() } }, "SystemTts-stop").apply {
            isDaemon = true
            start()
        }
    }

    override fun isAvailable(): Boolean = isReady

    fun shutdown() {
        tts?.shutdown()
        tts = null
    }

    /**
     * Tear down and re-create the underlying [TextToSpeech] instance.
     *
     * Why this exists: on some Android builds (and after a TTS engine
     * upgrade / language pack uninstall while the app stayed alive), an
     * already-bound [TextToSpeech] can degrade into a state where
     * `speak()` returns [TextToSpeech.ERROR] synchronously even though
     * the [OnInitListener] had reported `SUCCESS`. Re-binding clears
     * the corrupted state — Legado does the same thing in its
     * `TTSReadAloudService.clearTTS()` recovery path.
     *
     * Suspends until init resolves (success or failure). Caller is
     * expected to retry the failing utterance after this returns.
     */
    suspend fun reInit() {
        com.morealm.app.core.log.AppLog.warn("TTS", "SystemTtsEngine.reInit: tearing down and rebinding")
        runCatching { tts?.stop() }
        runCatching { tts?.shutdown() }
        tts = null
        isReady = false
        initResult = null
        cachedVoices = null
        // Note: pendingReadyCallbacks is intentionally NOT cleared — any
        // coroutine currently parked in awaitReadyResult should be unblocked
        // by the FRESH listener firing once initialize() completes.
        initialize()
        awaitReadyResult()
    }

    companion object {
        /**
         * Default ceiling for [awaitReadyResult]. Most engines bind in <500ms;
         * 4s is generous enough to absorb a cold start while still giving the user
         * a timely error if the system has no engine bound at all.
         */
        const val DEFAULT_INIT_TIMEOUT_MS: Long = 4_000L

        /**
         * [loadVoicesSafely] 兜底拉取 voices 列表的硬上限。
         *
         * `TextToSpeech.getVoices()` 是同步 binder IPC——绑定的 TTS 引擎进程
         * 异常时会无限期阻塞调用线程；2s 是经验值：远高于正常引擎的响应延迟
         * （<200ms），同时能在用户察觉前回退。
         */
        private const val VOICES_FETCH_TIMEOUT_MS: Long = 2_000L
    }
}
