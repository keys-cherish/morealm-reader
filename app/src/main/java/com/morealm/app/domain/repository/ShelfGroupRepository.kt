package com.morealm.app.domain.repository

import com.morealm.app.domain.db.ShelfGroupDao
import com.morealm.app.domain.entity.ShelfGroup
import com.morealm.app.domain.entity.ShelfGroupBook
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 书架 tab 自定义分组（[ShelfGroup]）仓库。与 [BookGroupRepository]（文件夹）
 * 并存——见 [ShelfGroup] KDoc 的两套组织维度说明。
 */
@Singleton
class ShelfGroupRepository @Inject constructor(
    private val dao: ShelfGroupDao,
) {
    fun getAllGroups(): Flow<List<ShelfGroup>> = dao.getAllGroups()

    fun getAllRelations(): Flow<List<ShelfGroupBook>> = dao.getAllRelations()

    suspend fun insert(group: ShelfGroup) = dao.insert(group)

    /** 删分组连带清成员关联（事务）。 */
    suspend fun deleteGroup(id: String) = dao.deleteGroupWithMembers(id)

    suspend fun addBooks(groupId: String, bookIds: List<String>) =
        dao.addBooks(bookIds.map { ShelfGroupBook(groupId = groupId, bookId = it) })

    suspend fun removeBooks(groupId: String, bookIds: List<String>) =
        dao.removeBooks(groupId, bookIds)
}
