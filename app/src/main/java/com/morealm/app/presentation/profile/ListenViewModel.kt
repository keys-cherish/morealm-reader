package com.morealm.app.presentation.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.morealm.app.domain.preference.AppPreferences
import com.morealm.app.service.TtsEventBus
import com.morealm.app.service.TtsPlaybackState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ListenViewModel @Inject constructor(
    private val prefs: AppPreferences,
) : ViewModel() {

    /** Live TTS playback state from ReaderTtsController via TtsEventBus */
    val playbackState: StateFlow<TtsPlaybackState> = TtsEventBus.playbackState

    val selectedEngine: StateFlow<String> = prefs.ttsEngine
        .stateIn(viewModelScope, SharingStarted.Eagerly, "system")

    val selectedSpeed: StateFlow<Float> = prefs.ttsSpeed
        .stateIn(viewModelScope, SharingStarted.Eagerly, 1.0f)

    fun selectEngine(engineId: String) {
        viewModelScope.launch { prefs.setTtsEngine(engineId) }
    }

    fun selectSpeed(speed: Float) {
        viewModelScope.launch { prefs.setTtsSpeed(speed) }
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
}
