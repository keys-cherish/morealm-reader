package com.morealm.app.presentation.update

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

/**
 * 「检查更新」入口 ViewModel。
 *
 * 现策略：**不做版本检查**，点击直接展示三网盘（百度 / 夸克 / 123）下载渠道，
 * 用户自行去网盘取最新安装包。链接来自 BuildConfig（由 local.properties 注入，不进 git）。
 *
 * 之前的 GitHub Releases / jsdelivr 版本检查（UpdateChecker）已移除：国内访问 GitHub
 * 不稳定、易限流，改由国内网盘直达更可靠。
 */
@HiltViewModel
class UpdateViewModel @Inject constructor() : ViewModel() {

    sealed interface UiState {
        data object Idle : UiState
        data object ShowChannels : UiState
    }

    private val _state = MutableStateFlow<UiState>(UiState.Idle)
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** 点「检查更新」→ 直接展示三网盘下载渠道（不查版本）。 */
    fun showChannels() {
        _state.value = UiState.ShowChannels
    }

    /** UI 关闭 Dialog 后重置回 Idle。 */
    fun dismiss() {
        _state.value = UiState.Idle
    }
}
