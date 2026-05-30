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
 * 「文字上色」设置子页的 ViewModel。
 *
 * 当前仅承载总开关；后续 Phase 的调色板覆盖、用户高亮词管理（含 Aho-Corasick
 * 自动机重建）都挂这里。与 [ReadingSettingsViewModel] 同构：注入 [AppPreferences]，
 * stateIn 暴露读、viewModelScope 写回。
 */
@HiltViewModel
class RuleColorViewModel @Inject constructor(
    private val prefs: AppPreferences,
) : ViewModel() {

    val enabled: StateFlow<Boolean> = prefs.ruleColorEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setEnabled(v: Boolean) = viewModelScope.launch { prefs.setRuleColorEnabled(v) }
}
