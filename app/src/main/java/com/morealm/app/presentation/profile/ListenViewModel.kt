package com.morealm.app.presentation.profile

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.morealm.app.domain.entity.TtsVoice
import com.morealm.app.domain.preference.AppPreferences
import com.morealm.app.domain.tts.EdgeTtsEngine
import com.morealm.app.domain.tts.SystemTtsEngine
import com.morealm.app.service.TtsEventBus
import com.morealm.app.service.TtsPlaybackState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ListenViewModel @Inject constructor(
    private val prefs: AppPreferences,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    /** Live TTS playback state from ReaderTtsController via TtsEventBus */
    val playbackState: StateFlow<TtsPlaybackState> = TtsEventBus.playbackState

    private val systemTtsEngine = SystemTtsEngine(context)

    private val _voices = MutableStateFlow<List<TtsVoice>>(emptyList())
    val voices: StateFlow<List<TtsVoice>> = _voices.asStateFlow()

    val selectedEngine: StateFlow<String> = prefs.ttsEngine
        .stateIn(viewModelScope, SharingStarted.Eagerly, "system")

    val selectedSpeed: StateFlow<Float> = prefs.ttsSpeed
        .stateIn(viewModelScope, SharingStarted.Eagerly, 1.0f)

    val selectedVoice: StateFlow<String> = combine(
        selectedEngine,
        prefs.ttsSystemVoice,
        prefs.ttsEdgeVoice,
        prefs.ttsVoice,
    ) { engine, systemVoice, edgeVoice, legacyVoice ->
        when (engine) {
            "edge" -> edgeVoice.ifBlank { legacyVoice }
            "system" -> systemVoice.ifBlank { legacyVoice }
            else -> ""
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, "")

    init {
        systemTtsEngine.initialize()
        viewModelScope.launch {
            selectedEngine.collectLatest { engine ->
                refreshVoices(engine)
            }
        }
    }

    fun selectEngine(engineId: String) {
        viewModelScope.launch { prefs.setTtsEngine(engineId) }
    }

    fun selectSpeed(speed: Float) {
        viewModelScope.launch { prefs.setTtsSpeed(speed) }
    }

    fun selectVoice(voiceId: String) {
        val engineId = voiceEngineId(selectedEngine.value) ?: return
        val resolvedVoice = validVoiceOrDefault(voiceId, _voices.value)
        viewModelScope.launch {
            if (engineId == "edge") {
                prefs.setTtsEdgeVoice(resolvedVoice)
            } else {
                prefs.setTtsSystemVoice(resolvedVoice)
            }
            prefs.setTtsVoice(resolvedVoice)
        }
    }

    fun sendPlayPause() {
        TtsEventBus.sendEvent(TtsEventBus.Event.PlayPause)
    }

    fun sendPrevChapter() {
        TtsEventBus.sendEvent(TtsEventBus.Event.PrevChapter)
    }

    fun sendNextChapter() {
        TtsEventBus.sendEvent(TtsEventBus.Event.NextChapter)
    }

    private fun voiceEngineId(engine: String): String? =
        when (engine) {
            "edge", "system" -> engine
            else -> null
        }

    private fun validVoiceOrDefault(voiceId: String, voices: List<TtsVoice>): String {
        if (voiceId.isBlank()) return ""
        return voiceId.takeIf { selected -> voices.any { it.id == selected } } ?: ""
    }

    private suspend fun refreshVoices(engine: String) {
        val engineId = voiceEngineId(engine) ?: run {
            _voices.value = emptyList()
            return
        }
        _voices.value = when (engineId) {
            "edge" -> EdgeTtsEngine.VOICES
            else -> {
                // Mirror the host-side fix: bound the wait so a missing/broken system
                // TTS engine doesn't leave the picker spinning. Failed init surfaces
                // as a TtsEventBus.Error so the settings UI can react via snackbar.
                when (val initRes = systemTtsEngine.awaitReadyResult()) {
                    is SystemTtsEngine.InitResult.Failed -> {
                        TtsEventBus.sendEvent(
                            TtsEventBus.Event.Error(initRes.reason, canOpenSettings = true)
                        )
                        emptyList()
                    }
                    SystemTtsEngine.InitResult.Success -> systemTtsEngine.getChineseVoices()
                }
            }
        }
        val savedVoice = if (engineId == "edge") {
            prefs.ttsEdgeVoice.first()
        } else {
            prefs.ttsSystemVoice.first()
        }.ifBlank { prefs.ttsVoice.first() }
        if (savedVoice.isNotBlank() && validVoiceOrDefault(savedVoice, _voices.value).isBlank()) {
            selectVoice("")
        }
    }

    override fun onCleared() {
        super.onCleared()
        systemTtsEngine.shutdown()
    }
}
