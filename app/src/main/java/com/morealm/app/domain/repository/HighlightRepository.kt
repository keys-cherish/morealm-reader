package com.morealm.app.domain.repository

import com.morealm.app.domain.db.HighlightDao
import com.morealm.app.domain.entity.Highlight
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 仓库门面 — 屏蔽 DAO 细节，方便 ViewModel 单测时换成假实现。
 *
 * 所有写操作都是 suspend；读操作既提供 Flow（阅读器订阅当前章高亮），也提供
 * sync 版本（备份导出 / 测试）。
 */
@Singleton
class HighlightRepository @Inject constructor(
    private val dao: HighlightDao,
) {
    fun getForChapter(bookId: String, chapterIndex: Int): Flow<List<Highlight>> =
        dao.getForChapter(bookId, chapterIndex)

    suspend fun getForChapterSync(bookId: String, chapterIndex: Int): List<Highlight> =
        dao.getForChapterSync(bookId, chapterIndex)

    fun getForBook(bookId: String): Flow<List<Highlight>> = dao.getForBook(bookId)

    suspend fun insert(highlight: Highlight) = dao.insert(highlight)

    suspend fun deleteById(id: String) = dao.deleteById(id)

    suspend fun deleteByBookId(bookId: String) = dao.deleteByBookId(bookId)

    suspend fun getAllSync(): List<Highlight> = dao.getAllSync()
}
