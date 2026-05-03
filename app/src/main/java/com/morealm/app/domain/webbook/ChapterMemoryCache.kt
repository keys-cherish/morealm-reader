package com.morealm.app.domain.webbook

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.util.LruCache
import com.morealm.app.core.log.AppLog

/**
 * 章节正文的内存 L1 缓存（容量 50 MB，可被低端机自动 coerce 小）。
 *
 * # 为什么独立做这一层
 * [CacheBook] 之前只走 DB —— 翻页 / 切回上一章 / TTS 下一段加载都过 SQLite 读全文，
 * 每次 ~10-50ms（含反序列化）。一本章节平均 4 KB 的小说，50 MB 内存能装下 ~12500 章
 * 缓存，足够覆盖一次连续阅读会话的全部 hot path。命中后耗时降到 0.x ms 量级。
 *
 * 不与 [CacheBook] 合并的原因：CacheBook 是 DB 持久化层、suspend 接口；本类是同步
 * 内存操作。混在一起读起来反而乱。CacheBook.getContent / putContent 各自负责把
 * 本类的 get/put 串起来。
 *
 * # 容量管理
 * - 上限 50 MB（[MAX_BYTES]），低端机 coerce 到 `maxMemory / 8`
 * - sizeOf = `value.length * 2`（Kotlin String 内部 UTF-16，每字符 2 字节）
 * - 实测：起点小说一章 3-5K 字符 ≈ 6-10 KB，50 MB 能装 5000-8000 章
 *
 * # 内存回退（onTrimMemory）
 * 项目无统一 LowMemoryHandler，本类自己注册一个 [ComponentCallbacks2]：
 *  - TRIM_MEMORY_RUNNING_LOW / MODERATE → trimToSize(maxSize/2)
 *  - TRIM_MEMORY_RUNNING_CRITICAL / COMPLETE / BACKGROUND → evictAll()
 * 兜底逻辑由 [Application] 注册一次即可，靠 [register] 显式调用避免静态初始化时
 * 没有 Application context 的尴尬。
 *
 * # 线程安全
 * `LruCache` 自身是线程安全的；本类只暴露简单 read/write，不需要额外锁。
 */
object ChapterMemoryCache {

    private const val MAX_BYTES: Int = 50 * 1024 * 1024 // 50 MB

    private val cache: LruCache<String, String>

    init {
        // maxMemory 是 JVM 当前 process 能拿到的字节上限，低端机典型 ~96-128 MB。
        // 取 1/8 作为低端机时本类的上限，避免和 ImageCache (maxMemory/4) 互相挤。
        val maxMemory = Runtime.getRuntime().maxMemory()
        val cap = (maxMemory / 8).coerceAtMost(MAX_BYTES.toLong()).toInt()
        cache = object : LruCache<String, String>(cap.coerceAtLeast(4 * 1024 * 1024)) {
            override fun sizeOf(key: String, value: String): Int {
                // String 在 JVM 上以 UTF-16 存储，每字符 2 字节。这里只算正文本身，
                // String 对象头开销忽略 —— 在 50 MB 量级误差 < 1%。
                return value.length * 2
            }
        }
        AppLog.info(
            "ChapterMemCache",
            "init: capacity=${cache.maxSize() / 1024} KB (maxMemory=${maxMemory / 1024 / 1024} MB)",
        )
    }

    private fun keyOf(sourceUrl: String, chapterUrl: String): String =
        "$sourceUrl|$chapterUrl"

    /** 命中返回正文；未命中返回 null。同步、O(1)。 */
    fun get(sourceUrl: String, chapterUrl: String): String? {
        return cache.get(keyOf(sourceUrl, chapterUrl))
    }

    /** 写入正文。空串静默忽略（与 CacheBook 行为对齐：不缓存空内容）。 */
    fun put(sourceUrl: String, chapterUrl: String, content: String) {
        if (content.isEmpty()) return
        cache.put(keyOf(sourceUrl, chapterUrl), content)
    }

    /**
     * 清掉指定 source 下所有章节缓存。LruCache 没有按前缀删除的 API，这里手动遍历
     * snapshot 后逐个 remove。在用户「换源」/「清章节缓存」时调用。
     */
    fun clearBook(sourceUrl: String) {
        val prefix = "$sourceUrl|"
        // snapshot() 返回 LinkedHashMap，遍历期间 cache 自身 thread-safe；但避免在
        // iterate 时对原 map 做 remove，先收集再删。
        val toRemove = cache.snapshot().keys.filter { it.startsWith(prefix) }
        toRemove.forEach { cache.remove(it) }
        if (toRemove.isNotEmpty()) {
            AppLog.debug("ChapterMemCache", "clearBook($sourceUrl): removed ${toRemove.size} entries")
        }
    }

    /** 全清。用于「清空所有缓存」UI / 测试。 */
    fun clearAll() {
        cache.evictAll()
    }

    /** 当前估算占用字节（监控用）。 */
    fun sizeBytes(): Int = cache.size()

    /** 当前条目数（监控 / 设置页显示）。 */
    fun count(): Int = cache.snapshot().size

    // ── onTrimMemory hook ────────────────────────────────────────────────────
    //
    // Android 在系统内存吃紧时会回调 ComponentCallbacks2.onTrimMemory；本类
    // 在 [register] 时把自己挂到 Application 上，按 level 决定 trim/evict。
    // 低端机用户长时间挂在阅读器后切到其他重应用时，主动归还内存能降低被
    // LMK 杀进程的概率。

    private val callbacks = object : ComponentCallbacks2 {
        override fun onConfigurationChanged(newConfig: Configuration) {}
        override fun onLowMemory() { cache.evictAll() }
        override fun onTrimMemory(level: Int) {
            when (level) {
                ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE,
                ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
                ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> {
                    val target = cache.maxSize() / 2
                    cache.trimToSize(target)
                    AppLog.debug("ChapterMemCache", "onTrimMemory(level=$level): trim to ${target / 1024} KB")
                }
                ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
                ComponentCallbacks2.TRIM_MEMORY_COMPLETE,
                ComponentCallbacks2.TRIM_MEMORY_MODERATE,
                ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> {
                    cache.evictAll()
                    AppLog.debug("ChapterMemCache", "onTrimMemory(level=$level): evictAll")
                }
            }
        }
    }

    /**
     * 在 [Application.onCreate] 调用一次。可重入：第二次调用会先 unregister 再
     * register，便于测试场景。
     */
    fun register(app: Application) {
        runCatching { app.unregisterComponentCallbacks(callbacks) }
        app.registerComponentCallbacks(callbacks)
    }
}
