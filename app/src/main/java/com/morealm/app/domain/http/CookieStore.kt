package com.morealm.app.domain.http

import androidx.annotation.Keep
import com.morealm.app.core.log.AppLog
import com.morealm.app.domain.db.CookieDao
import com.morealm.app.domain.entity.Cookie
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * Cookie 存储 — Legado parity.
 *
 * 双层存储：
 * - 持久化层：DB（Cookie 表，按 sub-domain key）
 * - 内存层：CacheManager memory（用于 hot-cache + session cookie）
 *
 * 公共 JS API（书源里调用）：
 * - cookie.getCookie(url) / cookie.setCookie(url, value) / cookie.removeCookie(url) / cookie.replaceCookie(url, value)
 *
 * 注意：登录后从响应自动持久化的逻辑在 [CookieManager] 里通过 OkHttp 拦截器完成，
 * 所以这里**只**负责"已知 cookie"的存取，不做 HTTP 拦截。
 */
@Keep
@Suppress("unused")
object CookieStore {

    private const val TAG = "CookieStore"
    private lateinit var cookieDao: CookieDao

    fun init(dao: CookieDao) {
        cookieDao = dao
    }

    /** 保存 cookie 到 DB + 内存缓存（按 sub-domain key） */
    fun setCookie(url: String, cookie: String?) {
        if (url.isBlank()) return
        try {
            val domain = NetworkUtils.getSubDomain(url)
            val value = cookie ?: ""
            CacheManager.putMemory("${domain}_cookie", value)
            runBlocking(Dispatchers.IO) {
                cookieDao.insert(Cookie(domain, value))
            }
        } catch (e: Exception) {
            AppLog.warn(TAG, "setCookie failed: ${e.message}")
        }
    }

    /** 合并写入 cookie：保留 oldCookie 中的 key/value，被新 cookie 中的同名 key 覆盖 */
    fun replaceCookie(url: String, cookie: String) {
        if (url.isBlank() || cookie.isBlank()) return
        val oldCookie = getCookieNoSession(url)
        if (oldCookie.isBlank()) {
            setCookie(url, cookie)
        } else {
            val cookieMap = cookieToMap(oldCookie).toMutableMap()
            cookieMap.putAll(cookieToMap(cookie))
            setCookie(url, mapToCookie(cookieMap))
        }
    }

    /**
     * 获取该 URL 域名下的全部 cookie（持久化 + session 合并）。
     * 长度超过 4096 时随机剔除部分 key 防止 header 超限。
     */
    fun getCookie(url: String): String {
        if (url.isBlank()) return ""
        val domain = NetworkUtils.getSubDomain(url)
        val persistent = getCookieNoSession(url)
        val session = CookieManager.getSessionCookie(domain)
        val merged = CookieManager.mergeCookiesToMap(persistent, session)
        var ck = mapToCookie(merged)
        while (ck.length > 4096) {
            val removeKey = merged.keys.randomOrNull() ?: break
            CookieManager.removeCookie(url, removeKey)
            merged.remove(removeKey)
            ck = mapToCookie(merged)
        }
        return ck
    }

    /** 仅从持久化层取（不含 session） — 内部和 CookieManager 用 */
    fun getCookieNoSession(url: String): String {
        if (url.isBlank()) return ""
        val domain = NetworkUtils.getSubDomain(url)
        val cached = CacheManager.getFromMemory("${domain}_cookie") as? String
        if (cached != null) return cached
        return runBlocking(Dispatchers.IO) {
            cookieDao.get(domain)?.cookie ?: ""
        }
    }

    /** 取该 URL 单个 cookie key 的值 */
    fun getKey(url: String, key: String): String {
        return cookieToMap(getCookie(url))[key] ?: ""
    }

    fun removeCookie(url: String) {
        if (url.isBlank()) return
        val domain = NetworkUtils.getSubDomain(url)
        runBlocking(Dispatchers.IO) { cookieDao.delete(domain) }
        CacheManager.deleteMemory("${domain}_cookie")
        CacheManager.deleteMemory("${domain}_session_cookie")
    }

    fun cookieToMap(cookie: String): Map<String, String> {
        if (cookie.isBlank()) return emptyMap()
        val map = LinkedHashMap<String, String>()
        for (pair in cookie.split(";")) {
            val parts = pair.split("=", limit = 2)
            if (parts.size == 2) {
                val k = parts[0].trim()
                val v = parts[1].trim()
                if (k.isNotEmpty()) map[k] = v
            }
        }
        return map
    }

    fun mapToCookie(cookieMap: Map<String, String>?): String {
        if (cookieMap.isNullOrEmpty()) return ""
        return cookieMap.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }
}
