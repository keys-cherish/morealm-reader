package com.morealm.app.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.morealm.app.domain.preference.AppPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 「搜索设置」页 ViewModel。仅负责把 [AppPreferences] 暴露的两个 Flow 包装成
 * StateFlow 供 Compose 使用，并把用户调节回写。
 *
 * 默认值来自 prefs.searchParallelism / sourceSearchTimeoutSec 的 fallback —
 * 这里不再重复写 16 / 30，避免双份默认值漂移。stateIn 的 initial 值给一个
 * 占位即可，第一帧 collect 会立刻刷成 prefs 的真实值。
 */
@HiltViewModel
class SearchSettingsViewModel @Inject constructor(
    private val prefs: AppPreferences,
) : ViewModel() {

    val parallelism: StateFlow<Int> = prefs.searchParallelism
        .stateIn(viewModelScope, SharingStarted.Eagerly, 16)

    val timeoutSec: StateFlow<Int> = prefs.sourceSearchTimeoutSec
        .stateIn(viewModelScope, SharingStarted.Eagerly, 30)

    fun setParallelism(value: Int) {
        viewModelScope.launch { prefs.setSearchParallelism(value) }
    }

    fun setTimeoutSec(value: Int) {
        viewModelScope.launch { prefs.setSourceSearchTimeoutSec(value) }
    }

    /** 一键还原 — UI 在恢复默认按钮调用，写回硬性默认值。 */
    fun restoreDefaults() {
        viewModelScope.launch {
            prefs.setSearchParallelism(16)
            prefs.setSourceSearchTimeoutSec(30)
        }
    }
}
