package com.morealm.app.domain.repository

import com.morealm.app.domain.db.BookSourceDao
import com.morealm.app.domain.entity.BookSource
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SourceRepository @Inject constructor(
    private val sourceDao: BookSourceDao,
) {
    fun getEnabledSources(): Flow<List<BookSource>> = sourceDao.getEnabledSources()

    suspend fun getEnabledSourcesList(): List<BookSource> = sourceDao.getEnabledSourcesList()

    fun getAllSources(): Flow<List<BookSource>> = sourceDao.getAllSources()

    suspend fun getByUrl(url: String): BookSource? = sourceDao.getByUrl(url)

    suspend fun insert(source: BookSource) = sourceDao.insert(source)

    suspend fun importAll(sources: List<BookSource>) = sourceDao.insertAll(sources)

    suspend fun delete(source: BookSource) = sourceDao.delete(source)
}
