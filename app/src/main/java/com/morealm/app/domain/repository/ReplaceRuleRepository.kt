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

    suspend fun delete(rule: ReplaceRule) = dao.delete(rule)

    suspend fun deleteById(id: String) = dao.deleteById(id)

    suspend fun getAllSync(): List<ReplaceRule> = dao.getAllSync()
}
