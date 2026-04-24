package com.morealm.app.domain.repository

import com.morealm.app.domain.db.ReaderStyleDao
import com.morealm.app.domain.entity.ReaderStyle
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReaderStyleRepository @Inject constructor(
    private val dao: ReaderStyleDao,
) {
    fun getAll(): Flow<List<ReaderStyle>> = dao.getAll()

    suspend fun getAllSync(): List<ReaderStyle> = dao.getAllSync()

    suspend fun getById(id: String): ReaderStyle? = dao.getById(id)

    suspend fun upsert(style: ReaderStyle) = dao.upsert(style)

    suspend fun upsertAll(styles: List<ReaderStyle>) = dao.upsertAll(styles)

    suspend fun deleteById(id: String) = dao.deleteById(id)

    suspend fun count(): Int = dao.count()

    suspend fun ensureDefaults() {
        if (dao.count() == 0) {
            dao.upsertAll(ReaderStyle.defaults())
        }
    }
}
