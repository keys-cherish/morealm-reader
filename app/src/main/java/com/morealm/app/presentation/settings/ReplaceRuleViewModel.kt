package com.morealm.app.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.morealm.app.domain.db.ReplaceRuleDao
import com.morealm.app.domain.entity.ReplaceRule
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class ReplaceRuleViewModel @Inject constructor(
    private val replaceRuleDao: ReplaceRuleDao,
) : ViewModel() {

    val allRules: StateFlow<List<ReplaceRule>> = replaceRuleDao.getAllRules()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun saveRule(
        existingId: String?,
        name: String,
        pattern: String,
        replacement: String,
        isRegex: Boolean,
        scope: String,
    ) {
        val rule = ReplaceRule(
            id = existingId ?: UUID.randomUUID().toString(),
            name = name,
            pattern = pattern,
            replacement = replacement,
            isRegex = isRegex,
            scope = scope,
            sortOrder = allRules.value.size,
        )
        viewModelScope.launch(Dispatchers.IO) {
            replaceRuleDao.insert(rule)
        }
    }

    fun toggleRule(rule: ReplaceRule) {
        viewModelScope.launch(Dispatchers.IO) {
            replaceRuleDao.insert(rule.copy(enabled = !rule.enabled))
        }
    }

    fun deleteRule(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            replaceRuleDao.deleteById(id)
        }
    }
}
