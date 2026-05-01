package com.morealm.app.domain.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
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

    /**
     * Kick off async init. The callback receives the final [InitResult].
     * Failure cases include: no TTS engine installed, init returned ERROR,
     * or the engine doesn't support / lacks data for Chinese.
     */
    fun initialize(onResult: (InitResult) -> Unit = {}) {
        com.morealm.app.core.log.AppLog.info("TTS", "SystemTtsEngine.initialize: creating TextToSpeech")
        tts = TextToSpeech(context) { status ->
            com.morealm.app.core.log.AppLog.info(
                "TTS",
                "SystemTtsEngine.OnInitListener: status=$status (SUCCESS=${TextToSpeech.SUCCESS})",
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
                        isReady = true
                        InitResult.Success
                    }
                }
            } else {
                InitResult.Failed("系统未识别到可用的 TTS 引擎，请到系统设置启用或安装一个引擎")
            }
            initResult = result
            onResult(result)
            // Snapshot + clear under the (single-threaded) listener thread to avoid
            // re-entrant mutation if a callback synchronously triggers more work.
            val snapshot = pendingReadyCallbacks.toList()
            pendingReadyCallbacks.clear()
            snapshot.forEach { it(result) }
        }
    }

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
        return tts?.voices?.map { voice ->
            TtsVoice(
                id = voice.name,
                name = voice.name,
                language = voice.locale.displayName,
                engine = id,
            )
        } ?: emptyList()
    }

    /** Set the TTS voice by name */
    fun setVoice(voiceName: String) {
        val engine = tts ?: return
        if (voiceName.isBlank()) {
            engine.language = Locale.CHINESE
            return
        }
        val voice = engine.voices?.find { it.name == voiceName }
        if (voice != null) {
            engine.voice = voice
        }
    }

    /** Get Chinese-compatible voices sorted by relevance */
    fun getChineseVoices(): List<TtsVoice> {
        val engine = tts ?: return emptyList()
        return engine.voices
            ?.filter { v ->
                val lang = v.locale.language
                lang == "zh" || lang == "cmn" || v.locale.toLanguageTag().startsWith("zh")
            }
            ?.sortedWith(compareBy(
                { if (it.isNetworkConnectionRequired) 1 else 0 },
                { it.name }
            ))
            ?.map { voice ->
                TtsVoice(
                    id = voice.name,
                    name = voice.name,
                    language = voice.locale.displayName,
                    engine = id,
                )
            } ?: emptyList()
    }

    override fun stop() { tts?.stop() }

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
    }
}
