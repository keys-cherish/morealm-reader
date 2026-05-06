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

    suspend fun insert(source: BookSource) = sourceDao.insert(source)

    suspend fun importAll(sources: List<BookSource>) = sourceDao.insertAll(sources)

    suspend fun delete(source: BookSource) = sourceDao.delete(source)

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
