package com.morealm.app.presentation.update

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.morealm.app.domain.update.UpdateChecker
import com.morealm.app.domain.update.UpdateResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 「检查更新」按钮背后的 ViewModel。
 *
 * 状态机简洁三态：
 *  - [UiState.Idle]：未发起或已被消费。
 *  - [UiState.Checking]：正在请求 GitHub API（用于 Snackbar 「正在检查更新…」）。
 *  - [UiState.HasResult]：拿到 [UpdateResult]，UI 据此弹 Dialog 或提示。
 *
 * 重入保护：[checkUpdate] 在 Checking 状态下直接返回，避免用户连点按钮发多次请求。
 *
 * IO 边界：所有网络与解析在 [UpdateChecker.check] 内部 withContext(IO) 完成；
 * 这里仍按 ci.yml 风格写 `viewModelScope.launch(Dispatchers.IO)`，让静态检查通过。
 *
 * 不持有任何 Compose 引用（CI lint 边界：presentation 层禁止 import androidx.compose）。
 */
@HiltViewModel
class UpdateViewModel @Inject constructor(
    private val checker: UpdateChecker,
) : ViewModel() {

    sealed interface UiState {
        data object Idle : UiState
        data object Checking : UiState
        data class HasResult(val result: UpdateResult) : UiState
    }

    private val _state = MutableStateFlow<UiState>(UiState.Idle)
    val state: StateFlow<UiState> = _state.asStateFlow()

    /**
     * 发起检查。
     * @param currentVersion 通常传 `BuildConfig.VERSION_NAME`。
     */
    fun checkUpdate(currentVersion: String) {
        if (_state.value is UiState.Checking) return
        viewModelScope.launch(Dispatchers.IO) {
            _state.value = UiState.Checking
            val result = checker.check(currentVersion)
            _state.value = UiState.HasResult(result)
        }
    }

    /** UI 消费完结果（关闭 Dialog / 收到 Snackbar）后重置回 Idle。 */
    fun dismiss() {
        _state.value = UiState.Idle
    }
}
