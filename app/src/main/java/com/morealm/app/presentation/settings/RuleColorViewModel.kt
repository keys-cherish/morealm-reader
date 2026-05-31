package com.morealm.app.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.morealm.app.domain.preference.AppPreferences
import com.morealm.app.domain.render.color.RuleColorCategory
import com.morealm.app.domain.render.color.RuleColorPalette
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 「文字上色」设置子页的 ViewModel：总开关 + 调色板覆盖（Phase 3）。
 *
 * 后续 Phase 2 的用户高亮词管理（含 Aho-Corasick 自动机重建）也挂这里。
 * 与 [ReadingSettingsViewModel] 同构：注入 [AppPreferences]，stateIn 暴露读、viewModelScope 写回。
 */
@HiltViewModel
class RuleColorViewModel @Inject constructor(
    private val prefs: AppPreferences,
) : ViewModel() {

    val enabled: StateFlow<Boolean> = prefs.ruleColorEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setEnabled(v: Boolean) = viewModelScope.launch { prefs.setRuleColorEnabled(v) }

    // ── 调色板覆盖（明 / 暗各一组，仅含被用户改过的类别）──
    private val paletteRaw: StateFlow<String> = prefs.ruleColorPalette
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val lightOverrides: StateFlow<Map<RuleColorCategory, Int>> = paletteRaw
        .map { RuleColorPalette.decodeOverrides(it).first }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    val darkOverrides: StateFlow<Map<RuleColorCategory, Int>> = paletteRaw
        .map { RuleColorPalette.decodeOverrides(it).second }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /** 改某类别在指定主题（[isNight]）下的颜色。 */
    fun setColor(category: RuleColorCategory, isNight: Boolean, color: Int) = persist { l, d ->
        if (isNight) d[category] = color else l[category] = color
    }

    /** 单个类别恢复内置默认（移除覆盖）。 */
    fun resetColor(category: RuleColorCategory, isNight: Boolean) = persist { l, d ->
        if (isNight) d.remove(category) else l.remove(category)
    }

    /** 当前主题全部类别恢复默认。 */
    fun resetAll(isNight: Boolean) = persist { l, d ->
        if (isNight) d.clear() else l.clear()
    }

    private inline fun persist(
        crossinline mutate: (
            MutableMap<RuleColorCategory, Int>,
            MutableMap<RuleColorCategory, Int>,
        ) -> Unit,
    ) = viewModelScope.launch {
        val (l0, d0) = RuleColorPalette.decodeOverrides(paletteRaw.value)
        val l = l0.toMutableMap()
        val d = d0.toMutableMap()
        mutate(l, d)
        prefs.setRuleColorPalette(RuleColorPalette.encodeOverrides(l, d))
    }
}
