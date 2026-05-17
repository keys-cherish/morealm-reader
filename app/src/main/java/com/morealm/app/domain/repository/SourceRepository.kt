package com.morealm.app.domain.repository

import com.morealm.app.domain.db.BookSourceDao
import com.morealm.app.domain.entity.BookSource
import com.morealm.app.domain.http.okHttpClient
import kotlinx.coroutines.flow.Flow
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SourceRepository @Inject constructor(
    private val sourceDao: BookSourceDao,
) {

    fun getEnabledSources(): Flow<List<BookSource>> = sourceDao.getEnabledSources()

    suspend fun getEnabledSourcesList(): List<BookSource> = sourceDao.getEnabledSourcesList()

    /**
     * Lightweight projection of enabled sources. Workers load the full [BookSource]
     * on demand via [getByUrl] when they're ready to actually search — this keeps
     * the 100k-source case from OOMing on the rule JSON blobs.
     */
    suspend fun getEnabledSourcesLite() = sourceDao.getEnabledSourcesLite()

    /** O(1) count for UI / dispatcher without materializing the full source list. */
    suspend fun getEnabledSourceCount(): Int = sourceDao.getEnabledSourceCount()

    fun getAllSources(): Flow<List<BookSource>> = sourceDao.getAllSources()

    suspend fun getByUrl(url: String): BookSource? = sourceDao.getByUrl(url)

    /** 原子翻转 enabled 字段，详见 [BookSourceDao.toggleEnabled]。 */
    suspend fun toggleEnabled(url: String): Int = sourceDao.toggleEnabled(url)

    suspend fun insert(source: BookSource) = sourceDao.insert(source)

    suspend fun importAll(sources: List<BookSource>) = sourceDao.insertAll(sources)

    suspend fun delete(source: BookSource) = sourceDao.delete(source)

    /**
     * 批量删除：一次事务里删完，全程只触发一次 Room InvalidationTracker 通知。
     *
     * **Why:** 逐条 delete 时 `DefaultDispatcher` 上的 `getAllSources()` Flow
     * collector 会边读 CursorWindow 边被下一次 delete invalidate，
     * 在 `BookSourceDao_Impl.getString` 阶段抛 `Couldn't read row X col 0`。
     */
    suspend fun deleteAll(sources: List<BookSource>) {
        if (sources.isEmpty()) return
        sourceDao.delete(sources)
    }

    /** 按 URL 批量删除——不需要先 select 出 BookSource。单事务。 */
    suspend fun deleteByUrls(urls: List<String>) {
        if (urls.isEmpty()) return
        sourceDao.deleteByUrls(urls)
    }

    fun fetchSourceJson(url: String): String {
        val response = okHttpClient.newCall(
            Request.Builder().url(url)
                .header("User-Agent", "Mozilla/5.0")
                .build()
        ).execute()
        if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
        return response.body?.string() ?: throw Exception("Empty response")
    }
}
