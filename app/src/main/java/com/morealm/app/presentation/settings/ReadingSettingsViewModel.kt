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

    val volumeKeyReverse: StateFlow<Boolean> = prefs.volumeKeyReverse
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val headsetButtonPage: StateFlow<Boolean> = prefs.headsetButtonPage
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val volumeKeyLongPress: StateFlow<String> = prefs.volumeKeyLongPress
        .stateIn(viewModelScope, SharingStarted.Eagerly, "off")

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

    val readerBgImageDay: StateFlow<String> = prefs.readerBgImageDay
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val readerBgImageNight: StateFlow<String> = prefs.readerBgImageNight
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    /**
     * 选区 mini-menu 自定义配置（显示 / 顺序 / 主行分配）。订阅 prefs，设置页 UI
     * 通过 [setSelectionMenuConfig] 写回 —— reader 端通过自己持有的
     * [com.morealm.app.presentation.reader.ReaderSettingsController.selectionMenuConfig]
     * 自动收到新值，无需手动通知。
     */
    val selectionMenuConfig: StateFlow<com.morealm.app.domain.entity.SelectionMenuConfig> =
        prefs.selectionMenuConfig
            .stateIn(viewModelScope, SharingStarted.Eagerly, com.morealm.app.domain.entity.SelectionMenuConfig.DEFAULT)

    fun setPageAnim(v: String) = viewModelScope.launch {
        prefs.setPageAnim(v)
        val activeId = prefs.activeReaderStyle.first()
        styleRepo.getById(activeId)?.let { style ->
            styleRepo.upsert(style.copy(pageAnim = v))
        }
    }
    fun setTapLeftAction(v: String) = viewModelScope.launch { prefs.setTapLeftAction(v) }
    fun setVolumeKeyPage(v: Boolean) = viewModelScope.launch { prefs.setVolumeKeyPage(v) }
    fun setVolumeKeyReverse(v: Boolean) = viewModelScope.launch { prefs.setVolumeKeyReverse(v) }
    fun setHeadsetButtonPage(v: Boolean) = viewModelScope.launch { prefs.setHeadsetButtonPage(v) }
    fun setVolumeKeyLongPress(v: String) = viewModelScope.launch { prefs.setVolumeKeyLongPress(v) }
    fun setResumeLastRead(v: Boolean) = viewModelScope.launch { prefs.setResumeLastRead(v) }
    fun setLongPressUnderline(v: Boolean) = viewModelScope.launch { prefs.setLongPressUnderline(v) }
    fun setScreenTimeout(v: Int) = viewModelScope.launch { prefs.setScreenTimeout(v) }
    fun setShowStatusBar(v: Boolean) = viewModelScope.launch { prefs.setShowStatusBar(v) }
    fun setShowChapterName(v: Boolean) = viewModelScope.launch { prefs.setShowChapterName(v) }
    fun setShowTimeBattery(v: Boolean) = viewModelScope.launch { prefs.setShowTimeBattery(v) }
    fun setCustomTxtChapterRegex(v: String) = viewModelScope.launch { prefs.setCustomTxtChapterRegex(v) }
    fun setTtsSkipPattern(v: String) = viewModelScope.launch { prefs.setTtsSkipPattern(v) }
    fun setReaderBgImageDay(v: String) = viewModelScope.launch { prefs.setReaderBgImageDay(v) }
    fun setReaderBgImageNight(v: String) = viewModelScope.launch { prefs.setReaderBgImageNight(v) }

    /**
     * 保存选区菜单配置 —— 落 DataStore 前打 INFO 日志，记录三个桶的项数 +
     * 列表顺序中各 item 的位置缩写，方便排查"用户报按钮顺序丢了"。
     *
     * 不打整段 JSON：1) 项不多，缩写够诊断；2) 避免 logcat 行变得过长。
     */
    fun setSelectionMenuConfig(v: com.morealm.app.domain.entity.SelectionMenuConfig) =
        viewModelScope.launch {
            com.morealm.app.core.log.AppLog.info(
                "SelectionMenu",
                "save config: ${v.summary()} order=[${
                    v.items.joinToString(",") { "${it.item.name.first()}:${it.position.name.first()}" }
                }]",
            )
            prefs.setSelectionMenuConfig(v)
        }
}
