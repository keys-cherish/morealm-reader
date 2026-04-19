package com.morealm.app.domain.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import kotlin.coroutines.resume

/**
 * System TTS engine wrapper.
 * Uses Android's built-in TextToSpeech for speech synthesis.
 */
class SystemTtsEngine(private val context: Context) : TtsEngine {
    override val name = "系统朗读"
    override val id = "system"

    private var tts: TextToSpeech? = null
    private var isReady = false
    private var pendingReadyCallbacks = mutableListOf<() -> Unit>()

    fun initialize(onReady: () -> Unit = {}) {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.CHINESE
                isReady = true
                onReady()
                pendingReadyCallbacks.forEach { it() }
                pendingReadyCallbacks.clear()
            }
        }
    }

    /** Suspend until the TTS engine is initialized and ready */
    suspend fun awaitReady() {
        if (isReady) return
        suspendCancellableCoroutine { cont ->
            if (isReady) {
                cont.resume(Unit)
            } else {
                pendingReadyCallbacks.add { cont.resume(Unit) }
            }
        }
    }

    override suspend fun speak(text: String, speed: Float): Flow<AudioChunk> = callbackFlow {
        val engine = tts
        if (engine == null || text.isBlank()) { close(); return@callbackFlow }

        engine.setSpeechRate(speed)

        val utteranceId = "morealm_tts_${System.nanoTime()}"

        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {
                if (id == utteranceId) {
                    trySend(AudioChunk(ByteArray(0), "started"))
                }
            }
            override fun onDone(id: String?) {
                if (id == utteranceId) {
                    trySend(AudioChunk(ByteArray(0), "done"))
                    close()
                }
            }
            override fun onError(id: String?, errorCode: Int) {
                if (id == utteranceId) {
                    close(Exception("TTS error: $errorCode"))
                }
            }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) {
                if (id == utteranceId) {
                    close(Exception("TTS error"))
                }
            }
        })

        val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
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
}
