package com.morealm.app.domain.repository

import com.morealm.app.domain.db.BookGroupDao
import com.morealm.app.domain.entity.BookGroup
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BookGroupRepository @Inject constructor(
    private val groupDao: BookGroupDao,
) {
    fun getAllGroups(): Flow<List<BookGroup>> = groupDao.getAllGroups()

    fun getGroups(parentId: String?): Flow<List<BookGroup>> = groupDao.getGroups(parentId)

    suspend fun getById(id: String): BookGroup? = groupDao.getById(id)

    suspend fun insert(group: BookGroup) = groupDao.insert(group)

    suspend fun delete(group: BookGroup) = groupDao.delete(group)

    suspend fun deleteById(id: String) = groupDao.deleteById(id)

    suspend fun getAllGroupsSync(): List<BookGroup> = groupDao.getAllGroupsSync()
}
