package com.morealm.app.domain.http

import kotlinx.coroutines.delay

/**
 * 并发率限制器 - 基于书源的 concurrentRate 字段控制访问频率
 * concurrentRate 格式：
 *   "1000" — 每次访问间隔1000ms
 *   "3/5000" — 5000ms内最多3次
 */
class ConcurrentRateLimiter(
    private val sourceKey: String?,
    private val concurrentRate: String?,
) {

    companion object {
        private val concurrentRecordMap = hashMapOf<String, ConcurrentRecord>()
    }

    data class ConcurrentRecord(
        val isConcurrent: Boolean,
        var time: Long,
        var frequency: Int,
    )

    @Throws(ConcurrentException::class)
    private fun fetchStart(): ConcurrentRecord? {
        val key = sourceKey ?: return null
        val rate = concurrentRate
        if (rate.isNullOrEmpty() || rate == "0") return null

        val rateIndex = rate.indexOf("/")
        var fetchRecord = concurrentRecordMap[key]
        if (fetchRecord == null) {
            synchronized(concurrentRecordMap) {
                fetchRecord = concurrentRecordMap[key]
                if (fetchRecord == null) {
                    fetchRecord = ConcurrentRecord(rateIndex > 0, System.currentTimeMillis(), 1)
                    concurrentRecordMap[key] = fetchRecord!!
                    return fetchRecord
                }
            }
        }
        val waitTime: Int = synchronized(fetchRecord!!) {
            try {
                if (!fetchRecord!!.isConcurrent) {
                    if (fetchRecord!!.frequency > 0) {
                        return@synchronized rate.toInt()
                    }
                    val nextTime = fetchRecord!!.time + rate.toInt()
                    if (System.currentTimeMillis() >= nextTime) {
                        fetchRecord!!.time = System.currentTimeMillis()
                        fetchRecord!!.frequency = 1
                        return@synchronized 0
                    }
                    return@synchronized (nextTime - System.currentTimeMillis()).toInt()
                } else {
                    val sj = rate.substring(rateIndex + 1)
                    val nextTime = fetchRecord!!.time + sj.toInt()
                    if (System.currentTimeMillis() >= nextTime) {
                        fetchRecord!!.time = System.currentTimeMillis()
                        fetchRecord!!.frequency = 1
                        return@synchronized 0
                    }
                    val cs = rate.substring(0, rateIndex)
                    if (fetchRecord!!.frequency > cs.toInt()) {
                        return@synchronized (nextTime - System.currentTimeMillis()).toInt()
                    } else {
                        fetchRecord!!.frequency += 1
                        return@synchronized 0
                    }
                }
            } catch (_: Exception) {
                return@synchronized 0
            }
        }
        if (waitTime > 0) {
            throw ConcurrentException("需等待${waitTime}ms", waitTime)
        }
        return fetchRecord
    }

    fun fetchEnd(record: ConcurrentRecord?) {
        if (record != null && !record.isConcurrent) {
            synchronized(record) {
                record.frequency -= 1
            }
        }
    }

    suspend fun <T> withLimit(block: suspend () -> T): T {
        var record: ConcurrentRecord? = null
        while (true) {
            try {
                record = fetchStart()
                break
            } catch (e: ConcurrentException) {
                delay(e.waitTime.toLong())
            }
        }
        try {
            return block()
        } finally {
            fetchEnd(record)
        }
    }

    suspend fun <T> withLimitBlocking(block: suspend () -> T): T {
        var record: ConcurrentRecord? = null
        while (true) {
            try {
                record = fetchStart()
                break
            } catch (e: ConcurrentException) {
                delay(e.waitTime.toLong())
            }
        }
        try {
            return block()
        } finally {
            fetchEnd(record)
        }
    }
}

class ConcurrentException(msg: String, val waitTime: Int) : Exception(msg)
