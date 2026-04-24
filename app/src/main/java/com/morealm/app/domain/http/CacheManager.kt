package com.morealm.app.domain.http

import androidx.annotation.Keep
import com.morealm.app.domain.db.CacheDao
import com.morealm.app.domain.entity.Cache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * 缓存管理器，供书源JS中调用 cache.get/cache.put
 * JS 引擎回调是同步的，必须用 runBlocking，但强制切到 IO 线程避免阻塞主线程。
 */
@Keep
@Suppress("unused")
object CacheManager {

    private lateinit var cacheDao: CacheDao

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
}
