package com.morealm.app.domain.source

import com.morealm.app.core.log.AppLog
import com.morealm.app.domain.analyzeRule.JsExtensions
import com.morealm.app.domain.entity.BookSource
import com.morealm.app.domain.entity.rule.ExploreKind
import com.morealm.app.domain.http.CacheManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

/**
 * 书源发现分类解析（参照实现 BookSourceExtensions.exploreKinds）。
 *
 * exploreUrl 是"分类配置"而非可直接请求的 URL，支持三种形态：
 *  1. JSON 数组：`[{"title":"玄幻","url":"/xuanhuan/{{page}}","style":{...}}, ...]`
 *  2. `标题::URL` 多行/`&&` 分隔文本：`玄幻::/xuanhuan/{{page}}&&都市::/dushi/{{page}}`
 *  3. `@js:` / `<js>` 脚本——求值后再按 1/2 解析
 *
 * 缓存策略与参照实现对齐：
 *  - 解析结果进内存 [exploreKindsMap]（key = md5(bookSourceUrl + exploreUrl)，
 *    分类配置一变 key 即变，无需手动失效）；
 *  - JS 求值结果额外落 [CacheManager]（DB 持久化）——脚本可能拉网络/耗时，
 *    冷启动直接复用上次求值文本，避免重复执行；
 *  - 每源一把 [Mutex]，发现页并发展开同一源时只解析一次。
 */
object BookSourceExplore {

    private const val TAG = "BookSourceExplore"
    private const val JS_CACHE_PREFIX = "exploreKindsJs_"

    private val jsonParser = Json { ignoreUnknownKeys = true; isLenient = true }
    private val mutexMap = ConcurrentHashMap<String, Mutex>()
    private val exploreKindsMap = ConcurrentHashMap<String, List<ExploreKind>>()

    private fun BookSource.exploreKindsKey(): String =
        JsExtensions.md5Encode(bookSourceUrl + exploreUrl.orEmpty())

    /**
     * 解析书源的发现分类列表。线程安全，可从任意协程调用；结果缓存后 O(1) 返回。
     * 解析失败时返回单项 `ERROR:` 分类（title 带错误消息，url 是堆栈），与参照实现
     * 一致——发现页可以据此给用户展示可点击的错误详情而不是静默空列表。
     */
    suspend fun exploreKinds(source: BookSource): List<ExploreKind> {
        val exploreUrl = source.exploreUrl
        if (exploreUrl.isNullOrBlank()) return emptyList()
        val key = source.exploreKindsKey()
        exploreKindsMap[key]?.let { return it }
        val mutex = mutexMap.getOrPut(source.bookSourceUrl) { Mutex() }
        mutex.withLock {
            exploreKindsMap[key]?.let { return it }
            val kinds = withContext(Dispatchers.IO) {
                parseExploreKinds(source, exploreUrl, key)
            }
            exploreKindsMap[key] = kinds
            return kinds
        }
    }

    /** 清空某源的分类缓存（内存 + JS 求值缓存），下次 [exploreKinds] 重新解析。 */
    suspend fun clearCache(source: BookSource) {
        withContext(Dispatchers.IO) {
            val key = source.exploreKindsKey()
            CacheManager.delete(JS_CACHE_PREFIX + key)
            exploreKindsMap.remove(key)
        }
    }

    private fun parseExploreKinds(
        source: BookSource,
        exploreUrl: String,
        cacheKey: String,
    ): List<ExploreKind> {
        val kinds = arrayListOf<ExploreKind>()
        runCatching {
            var ruleStr: String = exploreUrl
            if (exploreUrl.startsWith("<js>", true) || exploreUrl.startsWith("@js:", true)) {
                val cached = CacheManager.get(JS_CACHE_PREFIX + cacheKey)
                ruleStr = if (!cached.isNullOrBlank()) {
                    cached
                } else {
                    val jsStr = if (exploreUrl.startsWith("@")) {
                        exploreUrl.substring(4)
                    } else {
                        exploreUrl.substring(4, exploreUrl.lastIndexOf("<"))
                    }
                    val evaluated = source.evalJS(jsStr).toString().trim()
                    CacheManager.put(JS_CACHE_PREFIX + cacheKey, evaluated)
                    evaluated
                }
            }
            if (ruleStr.isJsonArray()) {
                kinds.addAll(
                    jsonParser.decodeFromString(ListSerializer(ExploreKind.serializer()), ruleStr)
                )
            } else {
                ruleStr.split("(&&|\n)+".toRegex()).forEach { kindStr ->
                    val kindCfg = kindStr.split("::")
                    kinds.add(ExploreKind(kindCfg.first(), kindCfg.getOrNull(1)))
                }
            }
        }.onFailure {
            kinds.add(ExploreKind("ERROR:${it.localizedMessage}", it.stackTraceToString()))
            AppLog.warn(TAG, "exploreKinds parse failed for '${source.bookSourceName}': ${it.message}")
        }
        return kinds
    }

    private fun String.isJsonArray(): Boolean {
        val str = trim()
        return str.startsWith("[") && str.endsWith("]")
    }
}

suspend fun BookSource.exploreKinds(): List<ExploreKind> = BookSourceExplore.exploreKinds(this)

suspend fun BookSource.clearExploreKindsCache() = BookSourceExplore.clearCache(this)
