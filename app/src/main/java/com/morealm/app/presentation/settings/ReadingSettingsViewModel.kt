package com.morealm.app.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.morealm.app.domain.preference.AppPreferences
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
    private val bookRepo: com.morealm.app.domain.repository.BookRepository,
) : ViewModel() {

    val pageAnim: StateFlow<String> = prefs.pageAnim
        .stateIn(viewModelScope, SharingStarted.Eagerly, "slide")

    /** 阅读方向。Phase 1：horizontal / vertical_rl，仅持久化。 */
    val readingDirection: StateFlow<String> = prefs.readingDirection
        .stateIn(viewModelScope, SharingStarted.Eagerly, "horizontal")

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

    /** 「文字上色」总开关 —— 只读，供阅读设置入口行显示「已开/已关」；写操作在 RuleColorScreen / RuleColorViewModel。 */
    val ruleColorEnabled: StateFlow<Boolean> = prefs.ruleColorEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val longPressUnderline: StateFlow<Boolean> = prefs.longPressUnderline
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val screenTimeout: StateFlow<Int> = prefs.screenTimeout
        .stateIn(viewModelScope, SharingStarted.Eagerly, -1)

    val isStatusBarVisible: StateFlow<Boolean> = prefs.isStatusBarVisible
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val isChapterNameVisible: StateFlow<Boolean> = prefs.isChapterNameVisible
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val isTimeBatteryVisible: StateFlow<Boolean> = prefs.isTimeBatteryVisible
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    /**
     * 章节标题对齐方式：0=左 / 1=中 / 2=右。默认 0。
     * UI 用 [setTitleAlign] 写回；排版层会自动 recompose（CanvasRenderer 监听）。
     */
    val titleAlign: StateFlow<Int> = prefs.titleAlign
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    val customTxtChapterRegex: StateFlow<String> = prefs.customTxtChapterRegex
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    /** 文内搜索方向模式：all/forward/backward。详见 [AppPreferences.innerSearchMode]。 */
    val innerSearchMode: StateFlow<String> = prefs.innerSearchMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, "all")

    val ttsSkipPattern: StateFlow<String> = prefs.ttsSkipPattern
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    /** 见 [com.morealm.app.domain.preference.AppPreferences.ttsKeepCpuAwake]——默认关。 */
    val ttsKeepCpuAwake: StateFlow<Boolean> = prefs.ttsKeepCpuAwake
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

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
    }
    fun setReadingDirection(v: String) = viewModelScope.launch { prefs.setReadingDirection(v) }
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
    fun setTitleAlign(v: Int) = viewModelScope.launch { prefs.setTitleAlign(v) }
    /**
     * 保存自定义 TXT 章节正则。切分规则**实际变化**时才作废章节缓存：
     * 清掉本地 TXT 书的文件指纹，下次打开走重新分章（而不是命中 DB 缓存拿到旧目录）。
     * 值没变（重复点保存）不清——避免无意义地让全部 TXT 重新解析。
     */
    fun setCustomTxtChapterRegex(v: String) = viewModelScope.launch {
        val old = prefs.customTxtChapterRegex.first()
        prefs.setCustomTxtChapterRegex(v)
        if (old != v) bookRepo.invalidateTxtChapterFingerprints()
    }
    fun setInnerSearchMode(v: String) = viewModelScope.launch { prefs.setInnerSearchMode(v) }
    fun setTtsSkipPattern(v: String) = viewModelScope.launch { prefs.setTtsSkipPattern(v) }
    fun setTtsKeepCpuAwake(v: Boolean) = viewModelScope.launch { prefs.setTtsKeepCpuAwake(v) }
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
