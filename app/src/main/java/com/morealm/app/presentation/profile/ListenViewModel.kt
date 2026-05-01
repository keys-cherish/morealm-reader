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
        // host 启动后只在 start() 里读了一次 prefs，之后只看 Command。
        // 不发 Command 的话听书 Tab 的引擎切换形同虚设——音频继续走旧引擎。
        TtsEventBus.sendCommand(TtsEventBus.Command.SetEngine(engineId))
    }

    fun selectSpeed(speed: Float) {
        viewModelScope.launch { prefs.setTtsSpeed(speed) }
        // 同上：缺 SetSpeed 时 host 的 speed 字段不会更新，下一段仍按旧速朗读。
        TtsEventBus.sendCommand(TtsEventBus.Command.SetSpeed(speed))
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
        // 同上：只写 prefs 不发 Command 时，host 不会调用引擎的 setVoice，
        // 即使下拉选了不同语音，朗读仍是旧的。
        TtsEventBus.sendCommand(TtsEventBus.Command.SetVoice(resolvedVoice))
    }

    fun sendPlayPause() {
        // 直接 sendCommand 而不是 sendEvent(PlayPause)：
        // Event 是 Service→ViewModel 方向，依赖 ReaderViewModel 在线监听并转 Command。
        // 听书 Tab 单独使用（reader 已退出）时 Event 无人响应，按钮失效。
        if (TtsEventBus.playbackState.value.isPlaying) {
            TtsEventBus.sendCommand(TtsEventBus.Command.Pause)
        } else {
            TtsEventBus.sendCommand(TtsEventBus.Command.Play)
        }
    }

    fun sendPrevChapter() {
        // 新 Command 包装：host 收到会转发 Event.PrevChapter 给 reader VM 处理。
        TtsEventBus.sendCommand(TtsEventBus.Command.PrevChapter)
    }

    fun sendNextChapter() {
        TtsEventBus.sendCommand(TtsEventBus.Command.NextChapter)
    }

    fun sendPrevParagraph() {
        TtsEventBus.sendCommand(TtsEventBus.Command.PrevParagraph)
    }

    fun sendNextParagraph() {
        TtsEventBus.sendCommand(TtsEventBus.Command.NextParagraph)
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
