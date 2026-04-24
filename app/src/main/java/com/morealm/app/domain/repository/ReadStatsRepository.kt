package com.morealm.app.domain.repository

import com.morealm.app.domain.db.ReadStatsDao
import com.morealm.app.domain.entity.ReadStats
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReadStatsRepository @Inject constructor(
    private val dao: ReadStatsDao,
) {
    suspend fun getByDate(date: String): ReadStats? = dao.getByDate(date)

    fun getRecent(limit: Int = 30): Flow<List<ReadStats>> = dao.getRecent(limit)

    suspend fun save(stats: ReadStats) = dao.save(stats)

    suspend fun getByYear(yearPrefix: String): List<ReadStats> = dao.getByYear(yearPrefix)
}
