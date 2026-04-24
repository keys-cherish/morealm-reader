package com.morealm.app.domain.repository

import com.morealm.app.domain.db.TxtTocRuleDao
import com.morealm.app.domain.entity.TxtTocRule
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TxtTocRuleRepository @Inject constructor(
    private val dao: TxtTocRuleDao,
) {
    fun getAll(): Flow<List<TxtTocRule>> = dao.getAll()

    suspend fun getEnabledRules(): List<TxtTocRule> = dao.getEnabledRules()

    suspend fun upsert(rule: TxtTocRule) = dao.upsert(rule)

    suspend fun upsertAll(rules: List<TxtTocRule>) = dao.upsertAll(rules)

    suspend fun delete(rule: TxtTocRule) = dao.delete(rule)

    suspend fun count(): Int = dao.count()
}
