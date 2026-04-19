package com.morealm.app.presentation.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.morealm.app.domain.preference.AppPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TtsEngineInfo(val id: String, val name: String)
data class TtsSession(val bookTitle: String, val chapterTitle: String, val timestamp: Long)

@HiltViewModel
class ListenViewModel @Inject constructor(
    private val prefs: AppPreferences,
) : ViewModel() {

    val availableEngines = MutableStateFlow(listOf(
        TtsEngineInfo("system", "系统 TTS"),
    ))

    val selectedEngine: StateFlow<String> = prefs.ttsEngine
        .stateIn(viewModelScope, SharingStarted.Eagerly, "system")

    private val _recentSessions = MutableStateFlow<List<TtsSession>>(emptyList())
    val recentSessions: StateFlow<List<TtsSession>> = _recentSessions.asStateFlow()

    fun selectEngine(engineId: String) {
        viewModelScope.launch {
            prefs.setTtsEngine(engineId)
        }
    }
}
