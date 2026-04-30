package com.morealm.app.domain.http

import androidx.annotation.Keep
import com.morealm.app.domain.db.CacheDao
import com.morealm.app.domain.entity.Cache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentHashMap

/**
 * 缓存管理器，供书源JS中调用 cache.get/cache.put
 * JS 引擎回调是同步的，必须用 runBlocking，但强制切到 IO 线程避免阻塞主线程。
 *
 * 双层缓存：
 * - DB 持久化（put/get/delete）
 * - 内存级 session 缓存（putMemory/getFromMemory/deleteMemory）—— 用于 session cookie 等不应落库的临时态
 */
@Keep
@Suppress("unused")
object CacheManager {

    private lateinit var cacheDao: CacheDao

    /** Memory-only cache (lost on process restart). Used by CookieManager for session cookies. */
    private val memoryCache = ConcurrentHashMap<String, Any>()

    fun init(dao: CacheDao) {
        cacheDao = dao
    }

    @JvmOverloads
    fun put(key: String, value: Any, saveTime: Int = 0) {
        val deadline = if (saveTime == 0) 0L else System.currentTimeMillis() + saveTime * 1000L
        val cache = Cache(key, value.toString(), deadline)
        runBlocking(Dispatchers.IO) { cacheDao.insert(cache) }
    }

    fun get(key: String): String? {
        val cache = runBlocking(Dispatchers.IO) { cacheDao.get(key) } ?: return null
        if (cache.deadline == 0L || cache.deadline > System.currentTimeMillis()) {
            return cache.value
        }
        return null
    }

    fun delete(key: String) {
        runBlocking(Dispatchers.IO) { cacheDao.delete(key) }
    }

    // ── Memory-only API (Legado-parity) ──

    fun putMemory(key: String, value: Any) {
        memoryCache[key] = value
    }

    fun getFromMemory(key: String): Any? = memoryCache[key]

    fun deleteMemory(key: String) {
        memoryCache.remove(key)
    }
}
