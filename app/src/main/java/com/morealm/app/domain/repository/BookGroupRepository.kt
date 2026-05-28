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

    /**
     * 幂等地按 [segments] 建嵌套分组链。给「远程书架 v2」用：远程 `A/B/X.epub` 导入
     * 时要把 `X.epub` 挂到本地 `BookGroup(name=B, parentId=BookGroup(name=A, ...))` 下。
     *
     * Stable id 策略 `"$sourcePrefix:$pathSoFar"` —— 同源同路径必同 id，跨次导入
     * 走 [BookGroupDao.insert] (REPLACE) 不会产生重复行；与用户手建分组的 UUID id
     * 命名空间隔离，互不影响。
     *
     * Trade-off：用户改名 `webdav:A` group 后下次重导仍会写回原 segment 名 `"A"`
     * （REPLACE 行为）—— 接受这个 trade-off，因为本任务的目标是"远程改了同步到本地"。
     *
     * @param segments 远程路径分段（不含 booksDir 前缀），如 `["A", "B"]` 表示 `A/B`
     * @param sourcePrefix id 命名空间前缀，默认 `"webdav"`
     * @return 叶子分组的 id（调用方据此设 [com.morealm.app.domain.entity.Book.folderId]）
     * @throws IllegalArgumentException 当 [segments] 为空（无 path 无 group 链）
     */
    suspend fun ensureGroupTree(
        segments: List<String>,
        sourcePrefix: String = "webdav",
    ): String {
        require(segments.isNotEmpty()) { "ensureGroupTree 需要至少一段 path segment" }
        var parentId: String? = null
        var pathSoFar = ""
        for (seg in segments) {
            pathSoFar = if (pathSoFar.isEmpty()) seg else "$pathSoFar/$seg"
            val id = "$sourcePrefix:$pathSoFar"
            groupDao.insert(BookGroup(id = id, name = seg, parentId = parentId))
            parentId = id
        }
        return parentId!!
    }
}
