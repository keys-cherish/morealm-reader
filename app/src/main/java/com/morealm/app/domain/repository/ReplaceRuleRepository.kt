package com.morealm.app.domain.repository

import com.morealm.app.domain.db.ReplaceRuleDao
import com.morealm.app.domain.entity.ReplaceRule
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReplaceRuleRepository @Inject constructor(
    private val dao: ReplaceRuleDao,
) {
    fun getEnabledRules(): Flow<List<ReplaceRule>> = dao.getEnabledRules()

    fun getAllRules(): Flow<List<ReplaceRule>> = dao.getAllRules()

    suspend fun getRulesForBook(bookId: String): List<ReplaceRule> = dao.getRulesForBook(bookId)

    fun getEnabledByScope(bookName: String, bookOrigin: String): List<ReplaceRule> =
        dao.getEnabledByScope(bookName, bookOrigin)

    suspend fun insert(rule: ReplaceRule) = dao.insert(rule)

    /**
     * 仅切换 enabled 标志位 — EffectiveReplacesDialog 用。先 dao 拿全 row 再 copy(enabled=…) 写回，
     * 避免靠 partial-column UPDATE 引入 dao schema 改动。规则不存在时静默返回。
     */
    suspend fun setEnabled(id: String, enabled: Boolean) {
        val all = dao.getAllSync()
        val rule = all.firstOrNull { it.id == id } ?: return
        if (rule.enabled == enabled) return
        dao.insert(rule.copy(enabled = enabled))
    }

    suspend fun delete(rule: ReplaceRule) = dao.delete(rule)

    suspend fun deleteById(id: String) = dao.deleteById(id)

    suspend fun getAllSync(): List<ReplaceRule> = dao.getAllSync()
}
