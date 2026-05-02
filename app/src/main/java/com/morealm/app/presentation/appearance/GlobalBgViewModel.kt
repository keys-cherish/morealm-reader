package com.morealm.app.presentation.appearance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.morealm.app.domain.preference.AppPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 独立 ViewModel，托管"全局背景图 + 卡片透明度 / 模糊度"三件套。
 *
 * 抽出来不挂在 ProfileViewModel 上的原因：
 *  1. GlobalBackgroundScaffold 包在 4 Tab 外面，每个 Tab 都会注入；如果挂在
 *     ProfileVM 上，发现/听书/书架 三屏没必要拉起整套备份/年度报告/WebDav 状态流。
 *  2. 只有 3 个 prefs 字段，独立 VM 保持职责单一，便于以后扩"主题/字体/色板"
 *     等同类外观项一起放进来。
 *
 * 所有写入走 IO 线程；coerceIn 在 AppPreferences setter 里已做。
 */
@HiltViewModel
class GlobalBgViewModel @Inject constructor(
    private val prefs: AppPreferences,
) : ViewModel() {

    val globalBgImage: StateFlow<String> = prefs.globalBgImage
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val globalBgCardAlpha: StateFlow<Float> = prefs.globalBgCardAlpha
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0.85f)

    val globalBgCardBlur: StateFlow<Float> = prefs.globalBgCardBlur
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0f)

    fun setGlobalBgImage(uri: String) = viewModelScope.launch(Dispatchers.IO) {
        prefs.setGlobalBgImage(uri)
    }

    fun setGlobalBgCardAlpha(alpha: Float) = viewModelScope.launch(Dispatchers.IO) {
        prefs.setGlobalBgCardAlpha(alpha)
    }

    fun setGlobalBgCardBlur(blur: Float) = viewModelScope.launch(Dispatchers.IO) {
        prefs.setGlobalBgCardBlur(blur)
    }

    /** 一键清除所有全局背景设置（外观卡片的"清除"按钮调用）。 */
    fun clearGlobalBg() = viewModelScope.launch(Dispatchers.IO) {
        prefs.setGlobalBgImage("")
        prefs.setGlobalBgCardAlpha(0.85f)
        prefs.setGlobalBgCardBlur(0f)
    }
}
