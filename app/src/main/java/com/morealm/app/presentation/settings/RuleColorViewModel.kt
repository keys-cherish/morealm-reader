package com.morealm.app.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.morealm.app.domain.db.HighlightWordDao
import com.morealm.app.domain.entity.HighlightWord
import com.morealm.app.domain.preference.AppPreferences
import com.morealm.app.domain.render.color.RuleColorCategory
import com.morealm.app.domain.render.color.RuleColorPalette
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/**
 * 「文字上色」设置子页的 ViewModel：总开关 + 调色板覆盖（Phase 3）+ 用户高亮词（Phase 2）。
 * 与 [ReadingSettingsViewModel] 同构：注入 [AppPreferences] / [HighlightWordDao]，
 * stateIn 暴露读、viewModelScope 写回。
 */
@HiltViewModel
class RuleColorViewModel @Inject constructor(
    private val prefs: AppPreferences,
    private val highlightWordDao: HighlightWordDao,
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

    fun setColor(category: RuleColorCategory, isNight: Boolean, color: Int) = persist { l, d ->
        if (isNight) d[category] = color else l[category] = color
    }

    fun resetColor(category: RuleColorCategory, isNight: Boolean) = persist { l, d ->
        if (isNight) d.remove(category) else l.remove(category)
    }

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

    // ── 用户高亮词（Phase 2）──
    val highlightWords: StateFlow<List<HighlightWord>> = highlightWordDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** 添加高亮词（去首尾空白；空词忽略；同词由 DAO REPLACE 去重 / 改色）。 */
    fun addHighlightWord(word: String, colorIndex: Int) {
        val w = word.trim()
        if (w.isEmpty()) return
        viewModelScope.launch {
            highlightWordDao.upsert(
                HighlightWord(id = UUID.randomUUID().toString(), word = w, colorIndex = colorIndex),
            )
        }
    }

    fun deleteHighlightWord(id: String) = viewModelScope.launch { highlightWordDao.deleteById(id) }
}
