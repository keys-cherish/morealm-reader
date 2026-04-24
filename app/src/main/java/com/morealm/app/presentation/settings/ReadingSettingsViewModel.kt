package com.morealm.app.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.morealm.app.domain.preference.AppPreferences
import com.morealm.app.domain.repository.ReaderStyleRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ReadingSettingsViewModel @Inject constructor(
    private val prefs: AppPreferences,
    private val styleRepo: ReaderStyleRepository,
) : ViewModel() {

    val pageAnim: StateFlow<String> = prefs.pageAnim
        .stateIn(viewModelScope, SharingStarted.Eagerly, "slide")

    val tapLeftAction: StateFlow<String> = prefs.tapLeftAction
        .stateIn(viewModelScope, SharingStarted.Eagerly, "next")

    val volumeKeyPage: StateFlow<Boolean> = prefs.volumeKeyPage
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val resumeLastRead: StateFlow<Boolean> = prefs.resumeLastRead
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val longPressUnderline: StateFlow<Boolean> = prefs.longPressUnderline
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val screenTimeout: StateFlow<Int> = prefs.screenTimeout
        .stateIn(viewModelScope, SharingStarted.Eagerly, -1)

    val showStatusBar: StateFlow<Boolean> = prefs.showStatusBar
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val showChapterName: StateFlow<Boolean> = prefs.showChapterName
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val showTimeBattery: StateFlow<Boolean> = prefs.showTimeBattery
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val customTxtChapterRegex: StateFlow<String> = prefs.customTxtChapterRegex
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val ttsSkipPattern: StateFlow<String> = prefs.ttsSkipPattern
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    fun setPageAnim(v: String) = viewModelScope.launch {
        prefs.setPageAnim(v)
        val activeId = prefs.activeReaderStyle.first()
        styleRepo.getById(activeId)?.let { style ->
            styleRepo.upsert(style.copy(pageAnim = v))
        }
    }
    fun setTapLeftAction(v: String) = viewModelScope.launch { prefs.setTapLeftAction(v) }
    fun setVolumeKeyPage(v: Boolean) = viewModelScope.launch { prefs.setVolumeKeyPage(v) }
    fun setResumeLastRead(v: Boolean) = viewModelScope.launch { prefs.setResumeLastRead(v) }
    fun setLongPressUnderline(v: Boolean) = viewModelScope.launch { prefs.setLongPressUnderline(v) }
    fun setScreenTimeout(v: Int) = viewModelScope.launch { prefs.setScreenTimeout(v) }
    fun setShowStatusBar(v: Boolean) = viewModelScope.launch { prefs.setShowStatusBar(v) }
    fun setShowChapterName(v: Boolean) = viewModelScope.launch { prefs.setShowChapterName(v) }
    fun setShowTimeBattery(v: Boolean) = viewModelScope.launch { prefs.setShowTimeBattery(v) }
    fun setCustomTxtChapterRegex(v: String) = viewModelScope.launch { prefs.setCustomTxtChapterRegex(v) }
    fun setTtsSkipPattern(v: String) = viewModelScope.launch { prefs.setTtsSkipPattern(v) }
}
